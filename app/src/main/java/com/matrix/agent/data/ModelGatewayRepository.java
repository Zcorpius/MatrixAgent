package com.matrix.agent.data;

import android.util.Log;

import com.matrix.agent.core.agent.DemoModelGateway;
import com.matrix.agent.core.agent.ModelGateway;
import com.matrix.agent.core.capability.CapabilityRegistry;
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

    public ModelGatewayRepository(SecureModelConfigStore configStore,
            ModelApiClient modelClient, CapabilityRegistry registry) {
        this(configStore, modelClient, registry, null);
    }

    public ModelGatewayRepository(SecureModelConfigStore configStore,
            ModelApiClient modelClient, CapabilityRegistry registry, MemoryStore memoryStore) {
        this.configStore = configStore;
        this.modelClient = modelClient;
        this.registry = registry;
        this.memoryStore = memoryStore;
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
        return new LlmModelGateway(modelClient, config, registry, memoryStore);
    }

    public ModelGateway createDemoGateway() {
        Log.i(TAG, "[ModelRepo] create DemoModelGateway (offline)");
        return new DemoModelGateway();
    }

    public String displayName(ModelConfig config) {
        return config.displayName + " / " + config.model + " / " + config.plannerMode.displayName;
    }
}
