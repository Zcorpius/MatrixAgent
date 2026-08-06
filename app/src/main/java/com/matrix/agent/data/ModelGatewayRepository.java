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
import com.matrix.agent.platform.ApiProtocol;
import com.matrix.agent.platform.OnDeviceModelGateway;
import com.matrix.agent.ondevice.MnnLoadOptions;
import com.matrix.agent.ondevice.OnDeviceLlm;
import com.matrix.agent.ondevice.OnDeviceLlmFactory;

import android.content.Context;

import java.util.List;

public final class ModelGatewayRepository {
    private static final String TAG = "MatrixAgent";
    private final SecureModelConfigStore configStore;
    private final ModelApiClient modelClient;
    private final CapabilityRegistry registry;
    private final MemoryStore memoryStore;
    /** V0.5.0 Stage 3:可选 Memory 召回——null 时 LlmPlanner 退化为 V0.4.3 行为。 */
    private final MemoryRecaller memoryRecaller;
    /** 端侧推理：appContext（模型目录 filesDir/models/mnn）+ 工厂；null 表示未装配端侧。 */
    private final Context appContext;
    private final OnDeviceLlmFactory onDeviceLlmFactory;

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
        this(configStore, modelClient, registry, memoryStore, memoryRecaller, null, null);
    }

    /** 端侧推理装配：注入 appContext（模型目录 filesDir/models/mnn）+ OnDeviceLlmFactory。 */
    public ModelGatewayRepository(SecureModelConfigStore configStore,
            ModelApiClient modelClient, CapabilityRegistry registry, MemoryStore memoryStore,
            MemoryRecaller memoryRecaller, Context appContext, OnDeviceLlmFactory onDeviceLlmFactory) {
        this.configStore = configStore;
        this.modelClient = modelClient;
        this.registry = registry;
        this.memoryStore = memoryStore;
        this.memoryRecaller = memoryRecaller;
        this.appContext = appContext;
        this.onDeviceLlmFactory = onDeviceLlmFactory;
    }

    public String testConnection(ModelConfig config) throws Exception {
        config.validate();
        if (config.protocol == ApiProtocol.ON_DEVICE) {
            return testOnDevice(config);
        }
        Log.i(TAG, "[ModelRepo] testConnection provider=" + config.displayName
                + " model=" + config.model + " protocol=" + config.protocol);
        String reply = modelClient.complete(config, "你是连接测试助手。", "只回复 OK");
        Log.i(TAG, "[ModelRepo] testConnection OK replyChars=" + reply.length());
        return reply;
    }

    /** 端侧测试：create(load 模型) → generate("OK") → close，验证模型可用。 */
    private String testOnDevice(ModelConfig config) throws Exception {
        if (appContext == null || onDeviceLlmFactory == null) {
            throw new IllegalStateException("端侧推理未装配（缺 appContext/OnDeviceLlmFactory）");
        }
        if (!isArm64()) {
            throw new IllegalStateException("端侧推理仅支持 arm64-v8a（当前设备 ABI: "
                    + android.os.Build.SUPPORTED_ABIS[0] + "）");
        }
        // P1: 只接受私有目录（计划一致）
        String modelDir = appContext.getFilesDir().getAbsolutePath() + "/models/mnn/" + config.model;
        Log.i(TAG, "[ModelRepo] testOnDevice modelDir=" + modelDir);
        OnDeviceLlm llm = onDeviceLlmFactory.create(modelDir, MnnLoadOptions.cpuDefaults());
        try {
            com.matrix.agent.ondevice.GenerationResult r = llm.generate(
                    "[{\"role\":\"user\",\"content\":\"只回复 OK\"}]", null, 64, () -> false);
            if (r.finishReason == com.matrix.agent.ondevice.OnDeviceFinishReason.FAILED) {
                throw new RuntimeException("端侧推理失败: " + r.nativeError);
            }
            if (r.finishReason == com.matrix.agent.ondevice.OnDeviceFinishReason.CANCELLED) {
                throw new RuntimeException("端侧推理被取消");
            }
            String text = r.text == null ? "" : r.text;
            if (text.trim().isEmpty()) {
                throw new RuntimeException("端侧模型返回空答复");
            }
            Log.i(TAG, "[ModelRepo] testOnDevice OK genTokens=" + r.generatedTokens
                    + " prefillMs=" + (r.prefillUs / 1000));
            return "端侧加载成功 · genTokens=" + r.generatedTokens
                    + " prefillMs=" + (r.prefillUs / 1000) + " · " + text;
        } finally {
            llm.close();
        }
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
        if (config.protocol == ApiProtocol.ON_DEVICE) {
            return createOnDeviceGateway(config);
        }
        Log.i(TAG, "[ModelRepo] create LlmModelGateway provider=" + config.displayName);
        LlmModelGateway gateway = new LlmModelGateway(modelClient, config, registry, memoryStore);
        // V0.5.0 Stage 3:把 Memory 召回器透传给 LlmPlanner(结构化 JSON 兼容路径)
        if (memoryRecaller != null) gateway.setMemoryRecaller(memoryRecaller);
        return gateway;
    }

    /** 端侧：model 目录 = filesDir/models/mnn/<config.model>。加载耗时，调用方须在 worker 线程。 */
    private ModelGateway createOnDeviceGateway(ModelConfig config) {
        if (appContext == null || onDeviceLlmFactory == null) {
            throw new IllegalStateException("端侧推理未装配（缺 appContext/OnDeviceLlmFactory）");
        }
        if (!isArm64()) {
            throw new IllegalStateException("端侧推理仅支持 arm64-v8a（当前设备 ABI: "
                    + android.os.Build.SUPPORTED_ABIS[0] + "）");
        }
        if (config.model != null && config.model.contains("..")) {
            throw new IllegalStateException("模型路径不能包含 '..'（路径遍历防护）");
        }
        // P1: 只接受私有目录 filesDir/models/mnn/<model>（计划一致，不再接受任意绝对路径）
        String modelDir = appContext.getFilesDir().getAbsolutePath() + "/models/mnn/" + config.model;
        Log.i(TAG, "[ModelRepo] create OnDeviceModelGateway modelDir=" + modelDir);
        try {
            OnDeviceLlm llm = onDeviceLlmFactory.create(modelDir, MnnLoadOptions.cpuDefaults());
            return new OnDeviceModelGateway(llm, config.displayName, 1536);
        } catch (Exception e) {
            throw new RuntimeException("端侧模型加载失败: " + e.getMessage(), e);
        }
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
        if (config.protocol == ApiProtocol.ON_DEVICE) {
            Log.i(TAG, "[ModelRepo] build KeywordIntentClassifier (on-device offline)");
            return KeywordIntentClassifier.INSTANCE;
        }
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

    /** P1: 端侧 .so 仅 arm64-v8a，非 arm64 设备前置拒绝（避免 UnsatisfiedLinkError）。 */
    private static boolean isArm64() {
        for (String abi : android.os.Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    public String displayName(ModelConfig config) {
        return config.displayName + " / " + config.model + " / " + config.plannerMode.displayName;
    }
}
