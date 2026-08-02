package com.matrix.agent.core.policy;

import android.util.Log;

import com.matrix.agent.core.capability.*;
import com.matrix.agent.core.identity.*;
import com.matrix.agent.core.tool.*;


public final class PolicyEngine {
    private static final String TAG = "MatrixAgent";
    private final CapabilityRegistry registry;

    public PolicyEngine(CapabilityRegistry registry) {
        this.registry = registry;
    }

    public PolicyDecision evaluate(AgentRequest request, ToolCall call) {
        String cap = call.getCapabilityName();
        // 第七轮 P2-4:Tool 参数含 destination / home_address / preferred_temperature 等业务敏感字段,
        // 不能整段进 logcat。这里只暴露 capability 名 + 元数据(actor / occupantZone)。
        Log.d(TAG, "[Policy] evaluate cap=" + cap
                + " actor=" + request.getActor()
                + " occupantZone=" + request.getOccupantZone()
                + " args=" + com.matrix.agent.core.agent.SafeLog.TOOL_ARGS_PLACEHOLDER);

        CapabilityDefinition definition = registry.find(cap);
        if (definition == null) {
            Log.w(TAG, "[Policy]   DENY (capability) Capability 未注册或未进入白名单: " + cap);
            return PolicyDecision.denyCapability("Capability 未注册或未进入白名单");
        }
        if (definition.getRiskLevel() == RiskLevel.R3_PROHIBITED) {
            Log.w(TAG, "[Policy]   DENY (capability) R3 禁止能力: " + cap);
            return PolicyDecision.denyCapability("R3 禁止能力：不允许由 Agent 控制");
        }

        // 第七轮 P1-1:写操作 + MULTI_TARGET/CONFLICT 用户意图 → fail-closed。
        // 必须在参数校验之前——多目标 / 否定冲突的 ToolCall 不允许执行,与参数是否合法无关。
        if (definition.isWriteOperation()) {
            PolicyDecision blocked = checkExplicitIntentBlocked(request, cap);
            if (blocked != null) return blocked;
        }

        // 第七轮 P1-2:strict schema 校验——模型输出是不可信输入,即使 JSON Schema 设了
        // additionalProperties=false 也不能依赖。在 PolicyEngine(受信任执行边界)拒绝所有
        // 未声明字段;统一 required / range / enum / type 校验。
        PolicyDecision schemaDecision = checkStrictSchema(definition, call, cap);
        if (schemaDecision != null) return schemaDecision;

        // 参数级:数值/格式/可补全参数(CapabilityValidator lambda,业务自定义)
        String violation = definition.validateArguments(call.getArguments());
        if (violation != null) {
            Log.i(TAG, "[Policy]   DENY (parameter) cap=" + cap + " violation=" + violation);
            return PolicyDecision.denyParameter(violation);
        }

        if (definition.isTargetZoneRequired() && !call.getArguments().containsKey("zone")) {
            Log.i(TAG, "[Policy]   DENY (parameter) cap=" + cap + " 缺少 zone");
            return PolicyDecision.denyParameter("缺少参数：zone");
        }
        if (call.getArguments().containsKey("zone")) {
            VehicleZone targetZone = VehicleZone.parse(call.argument("zone"));
            if (targetZone == null) {
                Log.i(TAG, "[Policy]   DENY (parameter) cap=" + cap + " zone 解析失败");
                return PolicyDecision.denyParameter("无效的车辆区域：zone");
            }
            // 能力级:该 Capability 不支持此区域,换参数解决不了
            if (!definition.getAllowedTargetZones().contains(targetZone)) {
                Log.w(TAG, "[Policy]   DENY (capability) cap=" + cap
                        + " 不支持目标区域: " + targetZone);
                return PolicyDecision.denyCapability("该 Capability 不支持目标区域：" + targetZone);
            }
            // 第四轮 P1-1:显式用户目标统一前置约束。权威来源是 ExplicitIntentConstraints,
            // 由 Runtime 受信任边界(关键词识别 / NLU)提取,**不依赖模型 adapter 标 provenance**。
            //
            // 评审指出的两种遗漏场景(都必须堵):
            //   1. 副驾说"主驾调温",模型第一次直接返回 zone=passenger → 用户原意被替换
            //   2. 主驾说"副驾调温",模型返回 zone=driver → 用户原意被替换
            //
            // 修复策略(对所有写操作):
            //   - explicitZone 存在 + targetZone != explicitZone → PARAMETER 拒绝,让模型保持用户原目标重试
            //   - 重试用对 zone 后,还要独立校验 explicitZone 本身是否越权
            //     (副驾不可写主驾,即使模型已经"修正"成合法区域)
            if (definition.isWriteOperation()) {
                PolicyDecision explicitDecision = checkExplicitZoneConsistency(
                        request, call, cap, targetZone);
                if (explicitDecision != null) return explicitDecision;
            }
        }
        Log.d(TAG, "[Policy]   ALLOW cap=" + cap);
        return PolicyDecision.allow();
    }

    /**
     * 第七轮 P1-1:写操作 + 用户意图被降级(MULTI_TARGET/CONFLICT)时 fail-closed。
     * 在参数 / zone 校验之前执行,确保多目标 / 否定冲突的 ToolCall 永不进入 Provider。
     */
    private static PolicyDecision checkExplicitIntentBlocked(AgentRequest request, String cap) {
        ExplicitIntentConstraints constraints = request.getExplicitIntent();
        if (constraints == null || !constraints.isBlocked()) return null;
        String reason = constraints.getIntentType() == ExplicitIntentConstraints.IntentType.MULTI_TARGET
                ? "指令包含多个区域目标,出于安全考虑,请逐个区域分开下达"
                : "指令的目标区域不明确,请明确指定单个 zone";
        Log.w(TAG, "[Policy]   DENY (capability) cap=" + cap
                + " intentType=" + constraints.getIntentType() + " reason=" + reason);
        return PolicyDecision.denyCapability(reason);
    }

    /**
     * 第七轮 P1-2:strict schema 校验——拒绝 schema 外字段、检查 required/type/range/enum。
     *
     * <p>模型 JSON Schema 设了 {@code additionalProperties=false},但模型输出是不可信输入。
     * 必须在受信任执行边界统一拒绝未声明字段,生成可信参数视图供后续 Provider 使用。
     *
     * <p>校验通过返回 null(允许进入下一步);失败返回 PARAMETER 拒绝(模型可换参数重试)。
     * capability 业务级 validator 仍由 {@link CapabilityDefinition#validateArguments} 处理。
     */
    private static PolicyDecision checkStrictSchema(CapabilityDefinition definition,
            ToolCall call, String cap) {
        java.util.Set<String> declared = new java.util.HashSet<>();
        for (ToolParameterDefinition param : definition.getToolParameters()) {
            declared.add(param.getName());
        }
        // 1. 拒绝未声明字段(additionalProperties=false)
        for (String key : call.getArguments().keySet()) {
            if (!declared.contains(key)) {
                Log.i(TAG, "[Policy]   DENY (parameter) cap=" + cap
                        + " 未声明参数: " + key + " (strict schema)");
                return PolicyDecision.denyParameter(
                        "未声明参数:" + key + "(strict schema 不允许 additionalProperties)");
            }
        }
        // 2. required / type / range / enum
        for (ToolParameterDefinition param : definition.getToolParameters()) {
            if (!call.getArguments().containsKey(param.getName())) {
                if (param.isRequired()) {
                    Log.i(TAG, "[Policy]   DENY (parameter) cap=" + cap
                            + " 缺少必需参数: " + param.getName());
                    return PolicyDecision.denyParameter("缺少必需参数:" + param.getName());
                }
                continue;
            }
            Object value = call.getArguments().get(param.getName());
            String typed = checkTypeAndRangeAndEnum(param, value);
            if (typed != null) {
                Log.i(TAG, "[Policy]   DENY (parameter) cap=" + cap
                        + " 参数 " + param.getName() + " " + typed);
                return PolicyDecision.denyParameter(
                        "参数 " + param.getName() + " " + typed);
            }
        }
        return null;
    }

    /** type / range / enum 校验,失败返回描述,通过返回 null。 */
    private static String checkTypeAndRangeAndEnum(ToolParameterDefinition param, Object value) {
        if (value == null) return "不能为 null";
        switch (param.getType()) {
            case INTEGER:
                if (!(value instanceof Number)) return "必须是整数";
                double iv = ((Number) value).doubleValue();
                if (!Double.isFinite(iv) || iv != Math.rint(iv)) return "必须是整数";
                if (param.getMinimum() != null && iv < param.getMinimum()) {
                    return "不能小于 " + param.getMinimum();
                }
                if (param.getMaximum() != null && iv > param.getMaximum()) {
                    return "不能大于 " + param.getMaximum();
                }
                if (!param.getEnumValues().isEmpty()) {
                    String repr = String.valueOf((int) iv);
                    if (!param.getEnumValues().contains(repr)) {
                        return "枚举值不在允许集合内";
                    }
                }
                break;
            case NUMBER:
                if (!(value instanceof Number)) return "必须是数字";
                double nv = ((Number) value).doubleValue();
                if (param.getMinimum() != null && nv < param.getMinimum()) {
                    return "不能小于 " + param.getMinimum();
                }
                if (param.getMaximum() != null && nv > param.getMaximum()) {
                    return "不能大于 " + param.getMaximum();
                }
                break;
            case BOOLEAN:
                if (!(value instanceof Boolean)) return "必须是布尔值";
                break;
            case STRING:
                if (!(value instanceof String)) return "必须是字符串";
                String sv = (String) value;
                if (sv.trim().isEmpty()) return "不能为空字符串";
                if (!param.getEnumValues().isEmpty()) {
                    boolean matched = false;
                    for (String allowed : param.getEnumValues()) {
                        if (allowed.equalsIgnoreCase(sv)) {
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        return "枚举值不在允许集合内:" + param.getEnumValues();
                    }
                }
                break;
            default:
                return "未知参数类型";
        }
        return null;
    }

    /**
     * 第四轮 P1-1:显式 zone 一致性 + 越权双检查。返回非 null 表示已得出决策(允许 / 拒绝),
     * 返回 null 表示没有显式约束,交回主流程兜底处理。
     *
     * <p>顺序:
     * <ol>
     *   <li>explicitZone 缺失 → 返回 null(主流程走 OccupantZone 兜底)</li>
     *   <li>explicitZone 与模型 targetZone 不一致 → PARAMETER 拒绝,要求模型用 explicitZone 重试</li>
     *   <li>explicitZone 本身越权(发起者无权写此 zone)→ CAPABILITY 拒绝,不可上诉</li>
     *   <li>explicitZone 与 targetZone 一致且不越权 → ALLOW</li>
     * </ol>
     *
     * <p>第七轮 P1-1:MULTI_TARGET / CONFLICT 已在 {@link #checkExplicitIntentBlocked} 前置
     * fail-closed,本方法只处理 SINGLE_TARGET 与 UNSPECIFIED。
     */
    private PolicyDecision checkExplicitZoneConsistency(AgentRequest request, ToolCall call,
            String cap, VehicleZone targetZone) {
        ExplicitIntentConstraints constraints = request.getExplicitIntent();
        if (constraints == null
                || constraints.getIntentType() == ExplicitIntentConstraints.IntentType.UNSPECIFIED) {
            return fallbackWithoutExplicitIntent(request, call, cap, targetZone);
        }

        VehicleZone explicitZone = constraints.getExplicitZone();

        // 1. 模型目标与用户明确目标不一致 → 让模型保持用户原目标重试
        if (targetZone != explicitZone) {
            Log.i(TAG, "[Policy]   DENY (parameter) cap=" + cap
                    + " 模型 zone=" + targetZone + " 与用户明确 zone=" + explicitZone
                    + " 不一致,要求用 zone=" + explicitZone + " 重试");
            return PolicyDecision.denyParameter(
                    "用户明确要求 zone=" + explicitZone + ",请保持用户目标重试,不要替换为 zone=" + targetZone);
        }

        // 2. explicitZone 本身越权(发起者无权写此 zone)→ CAPABILITY 拒绝不可上诉
        //    覆盖场景:副驾说"主驾调温"模型已用 zone=driver,但 driver 越权
        if (isCrossZoneViolation(request.getOccupantZone(), explicitZone)) {
            Log.w(TAG, "[Policy]   DENY (capability) cap=" + cap
                    + " 用户明确越权: occupantZone=" + request.getOccupantZone()
                    + " 不能写 explicitZone=" + explicitZone + ",不可改 zone 重试");
            return PolicyDecision.denyCapability(
                    "用户明确要求越权:occupantZone=" + request.getOccupantZone()
                            + " 不能写 zone=" + explicitZone);
        }

        return PolicyDecision.allow();
    }

    /**
     * 无显式意图约束(UNSPECIFIED 或 constraints==null)时的兜底:
     * 仅靠发起者 zone 与模型 targetZone 判定是否越权。
     */
    private static PolicyDecision fallbackWithoutExplicitIntent(AgentRequest request, ToolCall call,
            String cap, VehicleZone targetZone) {
        if (request.getOccupantZone() == VehicleZone.PASSENGER
                && targetZone == VehicleZone.DRIVER) {
            boolean userExplicitProvenance =
                    call.provenanceOf("zone") == ToolCall.ArgumentProvenance.USER_EXPLICIT;
            if (userExplicitProvenance) {
                Log.w(TAG, "[Policy]   DENY (capability) cap=" + cap
                        + " 副驾越权 zone=DRIVER(provenance=USER_EXPLICIT),不可改 zone 重试");
                return PolicyDecision.denyCapability("副驾不能修改主驾区域：用户明确要求越权");
            }
            Log.i(TAG, "[Policy]   DENY (parameter) cap=" + cap
                    + " 副驾 zone=DRIVER(模型脑补),可改 zone=passenger 重试");
            return PolicyDecision.denyParameter("副驾不能修改主驾区域，请改用 zone=passenger");
        }
        return null;
    }

    /**
     * 判断发起者所在 zone 是否有权写目标 zone。
     *
     * <p>V0.4.0 规则:副驾(PASSENGER)不能写主驾(DRIVER)。其他组合(主驾写副驾、
     * 主驾写主驾、副驾写副驾)允许。后排 / 第三排留待 V0.5.0+ 扩展。
     */
    private static boolean isCrossZoneViolation(VehicleZone occupantZone, VehicleZone targetZone) {
        return occupantZone == VehicleZone.PASSENGER && targetZone == VehicleZone.DRIVER;
    }

    public CapabilityDefinition findDefinition(String capabilityName) {
        return registry.find(capabilityName);
    }
}
