package com.matrix.agent.platform;

public final class ModelConfig {
    public final String providerId;
    public final String displayName;
    public final ApiProtocol protocol;
    public final String endpoint;
    public final String model;
    public final String apiKey;
    public final boolean apiKeyRequired;
    public final PlannerMode plannerMode;

    public ModelConfig(String providerId, String displayName, ApiProtocol protocol,
            String endpoint, String model, String apiKey, boolean apiKeyRequired) {
        this(providerId, displayName, protocol, endpoint, model, apiKey, apiKeyRequired,
                PlannerMode.STRUCTURED_JSON_COMPATIBILITY);
    }

    public ModelConfig(String providerId, String displayName, ApiProtocol protocol,
            String endpoint, String model, String apiKey, boolean apiKeyRequired,
            PlannerMode plannerMode) {
        this.providerId = providerId;
        this.displayName = displayName;
        this.protocol = protocol;
        this.endpoint = endpoint;
        this.model = model;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.apiKeyRequired = apiKeyRequired;
        this.plannerMode = plannerMode == null
                ? PlannerMode.STRUCTURED_JSON_COMPATIBILITY : plannerMode;
    }

    public void validate() {
        if (protocol == ApiProtocol.ON_DEVICE) {
            // 端侧不走 HTTP：跳过 endpoint/apiKey，只校验模型名/路径非空
            if (model == null || model.trim().isEmpty())
                throw new IllegalArgumentException("端侧模型名/路径不能为空");
            return;
        }
        if (endpoint == null || endpoint.trim().isEmpty()) throw new IllegalArgumentException("Endpoint 不能为空");
        if (model == null || model.trim().isEmpty()) throw new IllegalArgumentException("Model 不能为空");
        if (apiKeyRequired && apiKey.trim().isEmpty()) throw new IllegalArgumentException("该厂商需要 API Key");
        if (plannerMode == PlannerMode.NATIVE_TOOL_CALLING
                && protocol != ApiProtocol.OPENAI_CHAT
                && protocol != ApiProtocol.ANTHROPIC_MESSAGES
                && protocol != ApiProtocol.GEMINI_GENERATE_CONTENT) {
            throw new IllegalArgumentException(
                    "原生 Tool Calling 当前仅支持 OpenAI-Compatible / Anthropic Messages / Gemini 协议");
        }
    }
}
