package com.matrix.agent.core.capability;
import com.matrix.agent.core.identity.*;


import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class CapabilityDefinition {
    private final String name;
    private final String description;
    private final RiskLevel riskLevel;
    private final boolean writeOperation;
    private final boolean idempotent;
    private final long timeoutMillis;
    private final int maxRetries;
    private final boolean verificationRequired;
    private final boolean targetZoneRequired;
    private final Set<VehicleZone> allowedTargetZones;
    private final CapabilityValidator validator;
    private final List<ToolParameterDefinition> toolParameters;
    // 第五轮 P1-2:Audit 视图投影规则。
    private final String auditMessageTemplate;
    private final Map<String, String> sensitiveObservedFields;
    // 第七轮 P1-3:失败状态专用模板 + observedState allowlist(失败 / 未知字段 fail-closed)。
    private final String auditFailureMessageTemplate;
    private final Set<String> auditObservedAllowlist;

    private CapabilityDefinition(Builder builder) {
        name = builder.name;
        description = builder.description;
        riskLevel = builder.riskLevel;
        writeOperation = builder.writeOperation;
        idempotent = builder.idempotent;
        timeoutMillis = builder.timeoutMillis;
        maxRetries = builder.maxRetries;
        verificationRequired = builder.verificationRequired;
        targetZoneRequired = builder.targetZoneRequired;
        allowedTargetZones = Collections.unmodifiableSet(EnumSet.copyOf(builder.allowedTargetZones));
        validator = builder.validator;
        toolParameters = Collections.unmodifiableList(new ArrayList<>(builder.toolParameters));
        auditMessageTemplate = builder.auditMessageTemplate;
        sensitiveObservedFields = Collections.unmodifiableMap(new LinkedHashMap<>(builder.sensitiveObservedFields));
        auditFailureMessageTemplate = builder.auditFailureMessageTemplate;
        auditObservedAllowlist = Collections.unmodifiableSet(new LinkedHashSet<>(builder.auditObservedAllowlist));
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public boolean isWriteOperation() { return writeOperation; }
    public boolean isIdempotent() { return idempotent; }
    public long getTimeoutMillis() { return timeoutMillis; }
    public int getMaxRetries() { return maxRetries; }
    public boolean isVerificationRequired() { return verificationRequired; }
    public boolean isTargetZoneRequired() { return targetZoneRequired; }
    public Set<VehicleZone> getAllowedTargetZones() { return allowedTargetZones; }
    public List<ToolParameterDefinition> getToolParameters() { return toolParameters; }

    /**
     * 第五轮 P1-2:Audit 视图中 ToolResult.message 的固定模板。
     *
     * <p>当 capability 涉及业务敏感字段时(导航目的地 / 联系人 / 地址),Provider 的 message
     * 通常会把真实值嵌入(如 "已开始导航到 \"北京市某小区\"")——这种字符串原样进 Trajectory /
     * UI / 日志会泄漏。配 auditMessageTemplate 后,AuditRedactor 用此模板替换原 message。
     */
    public String getAuditMessageTemplate() { return auditMessageTemplate; }

    /**
     * 第五轮 P1-2:ToolResult.observedState 中的敏感字段及其占位符。
     *
     * <p>key = observedState 字段名(如 "navigation.destination"),
     * value = 替换占位符(如 "&lt;destination&gt;")。
     * AuditRedactor.redact(observation) 按此 map 替换 observedState 对应字段。
     */
    public Map<String, String> getSensitiveObservedFields() { return sensitiveObservedFields; }

    /**
     * 第七轮 P1-3:失败状态(EXECUTION_FAILED / VERIFICATION_FAILED)专用 Audit 模板。
     *
     * <p>背景:Provider 失败 message 常含业务值(如 "导航到北京市某小区失败:网络不可用"),
     * 通用凭据正则只识别 API Key/Token/Bearer,识别不出目的地、地址、联系人。
     * 仅当 capability 已经配置了 {@link #getAuditMessageTemplate()}(声明 Audit Schema),
     * 失败状态也必须按 schema 投影——配了 failure template 用之,没配用通用占位符,
     * 不允许 fall through 到 {@code redact(message)}(凭据正则识别不出业务值)。
     */
    public String getAuditFailureMessageTemplate() { return auditFailureMessageTemplate; }

    /**
     * 第七轮 P1-3:observedState 的显式 allowlist(诊断安全字段)。
     *
     * <p>非空时,AuditRedactor 走 fail-closed:sensitiveObservedFields 中的字段用占位符,
     * allowlist 中的字段走 redactValue(允许通过),**其余字段一律替换为 {@code ***}**。
     *
     * <p>Provider 新增 schema 外字段(如 navigation.poi / route.destination)默认 mask,
     * 防止真实 Provider 把目的地 / 联系人 / 地址塞进未知回读字段。
     */
    public Set<String> getAuditObservedAllowlist() { return auditObservedAllowlist; }

    public String validateArguments(java.util.Map<String, Object> arguments) {
        return validator == null ? null : validator.validate(arguments);
    }

    public static Builder builder(String name, RiskLevel riskLevel) {
        return new Builder(name, riskLevel);
    }

    public static final class Builder {
        private final String name;
        private final RiskLevel riskLevel;
        private String description = "";
        private boolean writeOperation;
        private boolean idempotent = true;
        private long timeoutMillis = 3_000L;
        private int maxRetries;
        private boolean verificationRequired;
        private boolean targetZoneRequired;
        private Set<VehicleZone> allowedTargetZones = EnumSet.allOf(VehicleZone.class);
        private CapabilityValidator validator;
        private final List<ToolParameterDefinition> toolParameters = new ArrayList<>();
        private String auditMessageTemplate;
        private final Map<String, String> sensitiveObservedFields = new LinkedHashMap<>();
        // 第七轮 P1-3
        private String auditFailureMessageTemplate;
        private final Set<String> auditObservedAllowlist = new LinkedHashSet<>();

        private Builder(String name, RiskLevel riskLevel) {
            if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("Capability name 不能为空");
            this.name = name;
            this.riskLevel = riskLevel;
        }

        public Builder description(String value) { description = value == null ? "" : value; return this; }
        public Builder writeOperation(boolean value) { writeOperation = value; return this; }
        public Builder idempotent(boolean value) { idempotent = value; return this; }
        public Builder timeoutMillis(long value) { timeoutMillis = value; return this; }
        public Builder maxRetries(int value) { maxRetries = value; return this; }
        public Builder verificationRequired(boolean value) { verificationRequired = value; return this; }
        public Builder targetZoneRequired(boolean value) { targetZoneRequired = value; return this; }
        public Builder allowedTargetZones(Set<VehicleZone> value) {
            allowedTargetZones = value == null || value.isEmpty()
                    ? EnumSet.noneOf(VehicleZone.class) : EnumSet.copyOf(value);
            return this;
        }
        public Builder validator(CapabilityValidator value) { validator = value; return this; }
        public Builder parameter(ToolParameterDefinition value) {
            if (value == null) throw new IllegalArgumentException("Tool 参数不能为空");
            for (ToolParameterDefinition existing : toolParameters) {
                if (existing.getName().equals(value.getName())) {
                    throw new IllegalArgumentException("重复 Tool 参数：" + value.getName());
                }
            }
            toolParameters.add(value);
            return this;
        }
        /** 第五轮 P1-2:设置 Audit 视图中 ToolResult.message 的固定模板。 */
        public Builder auditMessageTemplate(String value) {
            auditMessageTemplate = value == null || value.isEmpty() ? null : value;
            return this;
        }
        /** 第五轮 P1-2:声明 observedState 中的敏感字段及其占位符。 */
        public Builder sensitiveObservedField(String fieldName, String placeholder) {
            if (fieldName == null || fieldName.isEmpty()) {
                throw new IllegalArgumentException("敏感 observed 字段名不能为空");
            }
            if (placeholder == null || placeholder.isEmpty()) {
                throw new IllegalArgumentException("占位符不能为空");
            }
            sensitiveObservedFields.put(fieldName, placeholder);
            return this;
        }
        /** 第七轮 P1-3:失败状态(EXECUTION_FAILED / VERIFICATION_FAILED)专用 Audit 模板。 */
        public Builder auditFailureMessageTemplate(String value) {
            auditFailureMessageTemplate = value == null || value.isEmpty() ? null : value;
            return this;
        }
        /** 第七轮 P1-3:声明 observedState 中允许进 Audit 视图的诊断安全字段。 */
        public Builder auditObservedAllowlist(String... fields) {
            auditObservedAllowlist.clear();
            if (fields == null) return this;
            for (String f : fields) {
                if (f != null && !f.isEmpty()) auditObservedAllowlist.add(f);
            }
            return this;
        }
        public CapabilityDefinition build() {
            if (timeoutMillis <= 0) throw new IllegalArgumentException("timeoutMillis 必须大于 0");
            if (maxRetries < 0) throw new IllegalArgumentException("maxRetries 不能小于 0");
            return new CapabilityDefinition(this);
        }
    }
}
