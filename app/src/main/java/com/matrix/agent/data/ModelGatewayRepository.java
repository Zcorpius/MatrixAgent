package com.matrix.agent.data;

import android.util.Log;

import com.matrix.agent.core.agent.DemoModelGateway;
import com.matrix.agent.core.agent.ModelGateway;
import com.matrix.agent.core.capability.CapabilityRegistry;
import com.matrix.agent.core.identity.FallbackIntentClassifier;
import com.matrix.agent.core.identity.IntentClassifier;
import com.matrix.agent.core.identity.KeywordIntentClassifier;
import com.matrix.agent.core.identity.LlmIntentClassifier;
import com.matrix.agent.core.memory.MemoryRecaller;
import com.matrix.agent.core.memory.MemoryStore;
import com.matrix.agent.platform.LlmModelGateway;
import com.matrix.agent.platform.ModelApiClient;
import com.matrix.agent.platform.ModelConfig;
import com.matrix.agent.platform.ModelProviderPreset;
import com.matrix.agent.platform.SecureModelConfigStore;

import java.util.List;

public final class ModelGatewayRepository {
    private static final String TAG = "MatrixAgent";
    private final SecureModelConfigStore configStore;
    private final ModelApiClient modelClient;
    private final CapabilityRegistry registry;
    private final MemoryStore memoryStore;
    /** V0.5.0 Stage 3:可选 Memory 召回——null 时 LlmPlanner 退化为 V0.4.3 行为。 */
    private final MemoryRecaller memoryRecaller;

    public ModelGatewayRepository(SecureModelConfigStore configStore,
            ModelApiClient modelClient, CapabilityRegistry registry) {
        this(configStore, modelClient, registry, null);
    }

    public ModelGatewayRepository(SecureModelConfigStore configStore,
            ModelApiClient modelClient, CapabilityRegistry registry, MemoryStore memoryStore) {
        this(configStore, modelClient, registry, memoryStore, null);
    }

    /** V0.5.0 Stage 3:注入 Memory 召回器,createModelGateway 内部传给 LlmPlanner。 */
    public ModelGatewayRepository(SecureModelConfigStore configStore,
            ModelApiClient modelClient, CapabilityRegistry registry, MemoryStore memoryStore,
            MemoryRecaller memoryRecaller) {
        this.configStore = configStore;
        this.modelClient = modelClient;
        this.registry = registry;
        this.memoryStore = memoryStore;
        this.memoryRecaller = memoryRecaller;
    }

    public String testConnection(ModelConfig config) throws Exception {
        config.validate();
        Log.i(TAG, "[ModelRepo] testConnection provider=" + config.displayName
                + " model=" + config.model + " protocol=" + config.protocol);
        String reply = modelClient.complete(config, "你是连接测试助手。", "只回复 OK");
        Log.i(TAG, "[ModelRepo] testConnection OK replyChars=" + reply.length());
        return reply;
    }

    public void save(ModelConfig config) throws Exception {
        config.validate();
        Log.i(TAG, "[ModelRepo] save provider=" + config.displayName
                + " model=" + config.model + " mode=" + config.plannerMode
                + " (key length=" + (config.apiKey == null ? 0 : config.apiKey.length()) + ")");
        configStore.save(config);
    }

    public ModelConfig load() {
        ModelConfig loaded = configStore.load();
        Log.d(TAG, "[ModelRepo] load -> " + (loaded == null ? "null" :
                "provider=" + loaded.displayName + " model=" + loaded.model + " mode=" + loaded.plannerMode));
        return loaded;
    }
    public List<ModelProviderPreset> getProviderPresets() { return ModelProviderPreset.all(); }

    public ModelGateway createModelGateway(ModelConfig config) {
        Log.i(TAG, "[ModelRepo] create LlmModelGateway provider=" + config.displayName);
        LlmModelGateway gateway = new LlmModelGateway(modelClient, config, registry, memoryStore);
        // V0.5.0 Stage 3:把 Memory 召回器透传给 LlmPlanner(结构化 JSON 兼容路径)
        if (memoryRecaller != null) gateway.setMemoryRecaller(memoryRecaller);
        return gateway;
    }

    public ModelGateway createDemoGateway() {
        Log.i(TAG, "[ModelRepo] create DemoModelGateway (offline)");
        return new DemoModelGateway();
    }

    /**
     * V0.5.2-rev 评审 P2-2:用 {@link ModelConfig} 构建 {@link IntentClassifier}。
     *
     * <p>返回 {@link FallbackIntentClassifier}(LlmIntentClassifier 主路径,失败 / 低置信度退 Keyword)。
     * 调用方({@code ModelApiViewModel.saveAndApply})在 setModelGateway 后调 setIntentClassifier,
     * 让意图分类与新 Provider 同步切换——旧实现只切 Gateway,IntentClassifier 维持启动时的旧配置,
     * 出现"已应用"但分类仍用 Keyword 的伪装状态。
     */
    public IntentClassifier buildIntentClassifier(ModelConfig config) {
        Log.i(TAG, "[ModelRepo] build FallbackIntentClassifier provider=" + config.displayName);
        LlmIntentClassifier llm = new LlmIntentClassifier(modelClient, config);
        return new FallbackIntentClassifier(llm, KeywordIntentClassifier.INSTANCE);
    }

    /**
     * V0.5.2-rev 评审 P2-2:Demo 路径回退 Keyword——零成本 fallback,确保切回离线时
     * LLM 不再被调用。
     */
    public IntentClassifier buildKeywordClassifier() {
        Log.i(TAG, "[ModelRepo] build KeywordIntentClassifier (offline demo)");
        return KeywordIntentClassifier.INSTANCE;
    }

    public String displayName(ModelConfig config) {
        return config.displayName + " / " + config.model + " / " + config.plannerMode.displayName;
    }
}
