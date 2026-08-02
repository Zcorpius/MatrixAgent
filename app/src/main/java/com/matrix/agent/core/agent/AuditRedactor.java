package com.matrix.agent.core.agent;

import android.util.Log;

import com.matrix.agent.core.capability.CapabilityRegistry;
import com.matrix.agent.core.capability.ToolDefinition;
import com.matrix.agent.core.capability.ToolParameterDefinition;
import com.matrix.agent.core.tool.ToolResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 审计侧 Observation 脱敏器——喂 {@link Trajectory} / UI / Log,**字段级脱敏**。
 *
 * <p>数据边界(P1-2 修复后的正确方向):
 * <ul>
 *   <li>Conversation(模型侧):走 {@link ModelSanitizer},保留任务语义。</li>
 *   <li>Trajectory / UI / Log(本类处理):字段级脱敏——memory.preference.* 的 value
 *       一律替换为 {@code <memory>},message 替换为 {@link #MEMORY_MESSAGE_PLACEHOLDER}。</li>
 * </ul>
 *
 * <p>"用户看自己的偏好不是隐私"在 AAOS 不是通用假设——主驾副驾可能共用 Android User,
 * UI 查看者未必是数据所有者,V0.5.0 持久化后暴露面更大。
 *
 * <p>本类还做超长截断({@code maxChars})。
 */
public final class AuditRedactor {
    private static final String TAG = "MatrixAgent";
    private static final Pattern[] SECRET_PATTERNS = {
            Pattern.compile("sk-ant-[A-Za-z0-9_\\-]{16,}"),
            Pattern.compile("sk-[A-Za-z0-9]{16,}"),
            Pattern.compile("AIza[A-Za-z0-9_\\-]{16,}"),
            Pattern.compile("ghp_[A-Za-z0-9]{16,}"),
            Pattern.compile("(?i)Bearer\\s+\\S+")
    };
    /** 把整条 observation 全脱敏的 capability 集合——value 不该进 Trajectory/UI/Log。 */
    private static final Set<String> FULL_REDACT_CAPABILITIES = new HashSet<>(Arrays.asList(
            "memory.preference.save", "memory.preference.get"));

    /** memory.preference.* observation 脱敏后的 message 占位符,测试与 UI 渲染都会用。 */
    public static final String MEMORY_MESSAGE_PLACEHOLDER = "[user memory preference redacted]";

    /**
     * 第七轮 P1-3:capability 已声明 Audit Schema(auditMessageTemplate 非空)但未配置
     * {@code auditFailureMessageTemplate} 时,失败状态 message 的兜底占位符。
     *
     * <p>不能 fall through 到 {@link #redact(String)} ——通用凭据正则识别不出业务值
     * (目的地 / 地址 / 联系人),Provider 失败 message 会把真实业务值写入 Trajectory/UI/Log。
     */
    public static final String FAILURE_MESSAGE_FALLBACK = "[capability failed: details redacted]";

    /**
     * 第四轮 P1-2:即使 schema 没标 sensitive,这些字段名也无条件视为凭据/敏感字段。
     * 大小写不敏感匹配。这是 schema 元数据缺失时的兜底。
     *
     * <p>注意:不包括 {@code key} 这种过于通用的字段名——memory.preference 等业务场景下
     * key 是偏好名(preferred_temperature / home_address),不能误判为凭据。
     */
    private static final Set<String> BUILTIN_CREDENTIAL_KEYS = new HashSet<>(Arrays.asList(
            "api_key", "apikey", "token", "access_token", "refresh_token", "id_token",
            "authorization", "auth", "bearer", "secret", "client_secret",
            "password", "passwd", "credential", "credentials"));

    private final int maxChars;
    private final CapabilityRegistry capabilityRegistry;

    public AuditRedactor(int maxChars) {
        this(maxChars, null);
    }

    /** 第四轮 P1-2:注入 CapabilityRegistry 后,redactArguments(cap, args) 可以按 schema 脱敏。 */
    public AuditRedactor(int maxChars, CapabilityRegistry registry) {
        if (maxChars <= 0) throw new IllegalArgumentException("maxChars 必须大于 0");
        this.maxChars = maxChars;
        this.capabilityRegistry = registry;
    }

    public String redact(String content) {
        if (content == null) return "";
        String result = content;
        int totalMasked = 0;
        for (Pattern pattern : SECRET_PATTERNS) {
            Matcher m = pattern.matcher(result);
            int hits = 0;
            while (m.find()) hits++;
            if (hits > 0) {
                result = m.replaceAll("***");
                totalMasked += hits;
            }
        }
        boolean truncated = result.length() > maxChars;
        if (truncated) {
            result = ModelSanitizer.truncateWithSuffix(result, maxChars);
        }
        if (totalMasked > 0 || truncated) {
            Log.d(TAG, "[Audit] applied secretMask=" + totalMasked
                    + " truncated=" + truncated
                    + " origChars=" + content.length() + " redactedChars=" + result.length());
        }
        return result;
    }

    /**
     * 递归脱敏 Map——嵌套 Map / List 都会逐层处理,String value 过 secret mask(第三轮 P1-3 修复)。
     * 不识别业务敏感字段(destination=家 等)——V0.5.0 加 Capability Schema 级敏感字段元数据。
     */
    public Map<String, Object> redactMap(Map<String, Object> observed) {
        return redactMapRecursive(observed);
    }

    /**
     * 给 Tool Arguments 用——同 redactMap,只是语义上更明确(参数也是字段级脱敏对象)。
     * 不感知 capability,只跑递归凭据正则;调用方需要 schema 级脱敏时改用
     * {@link #redactArguments(String, Map)}。
     */
    public Map<String, Object> redactArguments(Map<String, Object> args) {
        return redactMapRecursive(args);
    }

    /**
     * 第四轮 P1-2 + 第五轮 P1-3:按 capability schema 脱敏 arguments,fail-closed。
     *
     * <p>处理顺序:
     * <ol>
     *   <li>registry 为空(legacy 调用方):走 {@link #redactArguments(Map)}(只有凭据正则)。</li>
     *   <li>capability 不在 registry / 被 R3 阻挡(findToolDefinition == null):
     *       <b>fail-closed</b>——所有 value 替换为 {@code ***}。
     *       未注册 capability 要么是模型脑补,要么是 R3 被禁用的——两种都不该让 args 原文进 audit。</li>
     *   <li>capability 在 registry 中:对每个 entry,
     *       <ul>
     *         <li>schema 标 sensitive → 替换为 {@link ToolParameterDefinition#getSensitivePlaceholder()}</li>
     *         <li>key 命中 builtin 凭据名(大小写不敏感) → 替换为 {@code ***}</li>
     *         <li>key 不在 schema parameters(strict schema 外的额外字段) → 替换为 {@code ***}
     *             <b>fail-closed</b>——模型多塞字段违反 strict schema,值不该进 audit。</li>
     *         <li>否则递归 redactValue(原有凭据正则 + 截断)</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <p>导航 destination、memory preference value/key 等业务敏感字段在 Audit 视图
     * 不再以原值出现;未注册 / R3 / 额外字段全部 fail-closed。
     */
    public Map<String, Object> redactArguments(String capabilityName, Map<String, Object> args) {
        if (args == null || args.isEmpty()) return Collections.emptyMap();
        if (capabilityRegistry == null) return redactArguments(args);
        ToolDefinition tool = findToolDefinition(capabilityName);
        if (tool == null) {
            // 第五轮 P1-3:未注册 / R3 capability → fail-closed,所有 value 一律 mask。
            Log.w(TAG, "[Audit] capability=" + capabilityName
                    + " not in registry (unregistered or R3-blocked) — fail-closed mask all args");
            Map<String, Object> redacted = new LinkedHashMap<>();
            for (String key : args.keySet()) redacted.put(key, "***");
            return redacted;
        }

        Map<String, String> sensitivePlaceholders = new LinkedHashMap<>();
        Set<String> schemaParameterNames = new HashSet<>();
        for (ToolParameterDefinition param : tool.getParameters()) {
            if (param.isSensitive()) {
                sensitivePlaceholders.put(param.getName(), param.getSensitivePlaceholder());
            }
            schemaParameterNames.add(param.getName());
        }
        Map<String, Object> redacted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (sensitivePlaceholders.containsKey(key)) {
                redacted.put(key, sensitivePlaceholders.get(key));
            } else if (isBuiltinCredentialKey(key)) {
                redacted.put(key, "***");
            } else if (!schemaParameterNames.contains(key)) {
                // 第五轮 P1-3:schema 外的额外字段 fail-closed。
                Log.w(TAG, "[Audit] capability=" + capabilityName
                        + " arg key=\"" + key + "\" not in schema — fail-closed mask");
                redacted.put(key, "***");
            } else {
                redacted.put(key, redactValue(value));
            }
        }
        return redacted;
    }

    private ToolDefinition findToolDefinition(String capabilityName) {
        if (capabilityRegistry == null) return null;
        for (ToolDefinition tool : capabilityRegistry.toToolDefinitions()) {
            if (tool.getCapabilityName().equals(capabilityName)) return tool;
        }
        return null;
    }

    private static boolean isBuiltinCredentialKey(String key) {
        if (key == null) return false;
        return BUILTIN_CREDENTIAL_KEYS.contains(key.toLowerCase(Locale.ROOT));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> redactMapRecursive(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return Collections.emptyMap();
        Map<String, Object> redacted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            redacted.put(entry.getKey(), redactValue(entry.getValue()));
        }
        return redacted;
    }

    @SuppressWarnings("unchecked")
    private Object redactValue(Object value) {
        if (value == null) return null;
        if (value instanceof Map) return redactMapRecursive((Map<String, Object>) value);
        if (value instanceof List) {
            List<Object> list = new ArrayList<>(((List<?>) value).size());
            for (Object item : (List<?>) value) list.add(redactValue(item));
            return list;
        }
        return value instanceof String ? redact((String) value) : value;
    }

    /** 把 Observation 内的 message/observedState/rejectionReason 全部过一遍,返回新 Observation。 */
    public ToolObservation redact(ToolObservation observation) {
        if (observation == null) return null;
        if (observation.getResult() == null) {
            return ToolObservation.fromParts(observation.getToolCallId(),
                    observation.getCapabilityName(), null,
                    redact(observation.getRejectionReason()),
                    observation.isCapabilityBlocked());
        }
        ToolResult original = observation.getResult();
        if (FULL_REDACT_CAPABILITIES.contains(observation.getCapabilityName())) {
            return redactMemoryObservation(observation, original);
        }
        // 第五轮 P1-2:有 AuditSchema 时按 schema 投影——message 用模板,
        // observedState 中 sensitiveObservedFields 列出的字段替换为占位符,
        // 其余字段仍走默认 secret mask + 截断。
        com.matrix.agent.core.capability.CapabilityDefinition capDef =
                capabilityRegistry == null ? null : capabilityRegistry.find(observation.getCapabilityName());
        if (capDef != null) {
            String template = capDef.getAuditMessageTemplate();
            java.util.Map<String, String> sensitiveObserved = capDef.getSensitiveObservedFields();
            String failureTemplate = capDef.getAuditFailureMessageTemplate();
            java.util.Set<String> observedAllowlist = capDef.getAuditObservedAllowlist();
            if (template != null || !sensitiveObserved.isEmpty()
                    || failureTemplate != null || !observedAllowlist.isEmpty()) {
                return redactWithSchema(observation, original, template, failureTemplate,
                        sensitiveObserved, observedAllowlist);
            }
        }
        ToolResult redacted = new ToolResult(original.getStatus(), original.getCapabilityName(),
                redact(original.getMessage()), redactMap(original.getObservedState()),
                original.isVerified(), original.getDurationMillis());
        return ToolObservation.fromParts(observation.getToolCallId(), observation.getCapabilityName(),
                redacted, null, false);
    }

    /**
     * 第五轮 P1-2 + 第七轮 P1-3:按 AuditSchema 投影 ToolResult。
     *
     * <p>Provider 路径不变——MockCapabilityProvider 仍返回真实 message / observedState 给 ModelSanitizer
     * (模型需要真实回读);只在 Audit 视图(Trajectory / UI / Log)替换为模板与占位符。
     *
     * <p>第七轮 P1-3 修复:message 与 observedState 都走 fail-closed 路径。
     * <ul>
     *   <li>SUCCESS:message 用 {@code messageTemplate}(未配置则 redact 原文)。</li>
     *   <li>FAILURE(EXECUTION_FAILED / VERIFICATION_FAILED):message 优先用
     *       {@code failureTemplate},未配置但已声明 Audit Schema(messageTemplate 非空)
     *       时用 {@link #FAILURE_MESSAGE_FALLBACK},**绝不 fall through 到 redact 原文**
     *       ——Provider 失败文本常含目的地 / 地址 / 联系人,凭据正则识别不出。</li>
     *   <li>observedState:进入本方法即 fail-closed——sensitiveObservedFields 字段用占位符;
     *       allowlist 字段走 redactValue;**其余字段一律 {@code ***}**。
     *       Provider 新增 schema 外字段(如 navigation.poi / route.destination)默认 mask。</li>
     * </ul>
     */
    private ToolObservation redactWithSchema(ToolObservation observation, ToolResult original,
            String messageTemplate, String failureTemplate,
            java.util.Map<String, String> sensitiveObserved, java.util.Set<String> observedAllowlist) {
        // 1. message 处理 —— SUCCESS 走模板 / FAILURE fail-closed
        String auditMessage;
        boolean usedFailureTemplate = false;
        boolean usedFallback = false;
        if (original.isSuccess()) {
            auditMessage = messageTemplate != null ? messageTemplate : redact(original.getMessage());
        } else {
            if (failureTemplate != null) {
                auditMessage = failureTemplate;
                usedFailureTemplate = true;
            } else if (messageTemplate != null) {
                // capability 已声明 Audit Schema(messageTemplate 非空)→ 失败也必须 fail-closed,
                // 不允许 fall through 到 redact(message)(凭据正则识别不出业务值)。
                auditMessage = FAILURE_MESSAGE_FALLBACK;
                usedFallback = true;
            } else {
                auditMessage = redact(original.getMessage());
            }
        }

        // 2. observedState 处理 —— 进入 redactWithSchema 即 fail-closed:
        //    sensitiveObservedFields 用占位符;allowlist 字段走 redactValue;**其余一律 {@code ***}**。
        //    Provider 新增 schema 外字段(如 navigation.poi / route.destination)默认 mask。
        java.util.Map<String, Object> redactedObserved = new LinkedHashMap<>();
        int maskedUnknown = 0;
        if (original.getObservedState() != null) {
            for (java.util.Map.Entry<String, Object> entry : original.getObservedState().entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                String placeholder = sensitiveObserved.get(key);
                if (placeholder != null) {
                    redactedObserved.put(key, placeholder);
                } else if (observedAllowlist.contains(key)) {
                    redactedObserved.put(key, redactValue(value));
                } else {
                    // 第七轮 P1-3:schema 外 observedState 字段 fail-closed
                    redactedObserved.put(key, "***");
                    maskedUnknown++;
                }
            }
        }
        Log.d(TAG, "[Audit] schema-projected capability=" + observation.getCapabilityName()
                + " status=" + original.getStatus()
                + " usedFailureTemplate=" + usedFailureTemplate
                + " usedFallback=" + usedFallback
                + " maskedUnknownFields=" + maskedUnknown
                + " sensitiveFields=" + sensitiveObserved.keySet()
                + " allowlist=" + observedAllowlist);
        ToolResult redacted = new ToolResult(original.getStatus(), original.getCapabilityName(),
                auditMessage, redactedObserved, original.isVerified(), original.getDurationMillis());
        return ToolObservation.fromParts(observation.getToolCallId(), observation.getCapabilityName(),
                redacted, null, false);
    }

    /**
     * memory.preference.* 类 observation 全脱敏:所有 value → {@code <memory>},
     * message → {@link #MEMORY_MESSAGE_PLACEHOLDER}。
     */
    private ToolObservation redactMemoryObservation(ToolObservation observation, ToolResult original) {
        Map<String, Object> redactedMap = new LinkedHashMap<>();
        int masked = 0;
        if (original.getObservedState() != null) {
            for (Map.Entry<String, Object> entry : original.getObservedState().entrySet()) {
                redactedMap.put(entry.getKey(), "<memory>");
                masked++;
            }
        }
        Log.d(TAG, "[Audit] memory capability=" + observation.getCapabilityName()
                + " maskedValues=" + masked);
        ToolResult redacted = new ToolResult(original.getStatus(), original.getCapabilityName(),
                MEMORY_MESSAGE_PLACEHOLDER, redactedMap,
                original.isVerified(), original.getDurationMillis());
        return ToolObservation.fromParts(observation.getToolCallId(), observation.getCapabilityName(),
                redacted, null, false);
    }
}
