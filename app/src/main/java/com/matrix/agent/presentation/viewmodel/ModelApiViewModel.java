package com.matrix.agent.presentation.viewmodel;

import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.matrix.agent.data.AgentRuntimeRepository;
import com.matrix.agent.data.ModelGatewayRepository;
import com.matrix.agent.platform.ModelConfig;
import com.matrix.agent.platform.ModelProviderPreset;
import com.matrix.agent.presentation.state.ModelUiState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public final class ModelApiViewModel extends ViewModel {
    private static final String TAG = "MatrixAgent";
    private final AgentRuntimeRepository runtimeRepository;
    private final ModelGatewayRepository modelRepository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MutableLiveData<ModelUiState> uiState = new MutableLiveData<>();
    private final AtomicLong operationSequence = new AtomicLong();
    private final Map<String, ModelConfig> drafts = new HashMap<>();
    private Future<?> activeFuture;
    /**
     * V0.5.3 评审 P1-3:MemoryStore 降级标志——从 AppContainer 透传,所有 UiState 更新携带。
     * UI 显示 banner 提醒用户"记忆已降级,重启后丢失"。
     */
    private final boolean memoryDegraded;

    public ModelApiViewModel(AgentRuntimeRepository runtimeRepository,
            ModelGatewayRepository modelRepository) {
        this(runtimeRepository, modelRepository, false);
    }

    /** V0.5.3 P1-3:3 参构造器,MatrixViewModelFactory 透传 AppContainer.isMemoryDegraded()。 */
    public ModelApiViewModel(AgentRuntimeRepository runtimeRepository,
            ModelGatewayRepository modelRepository, boolean memoryDegraded) {
        this.runtimeRepository = runtimeRepository;
        this.modelRepository = modelRepository;
        this.memoryDegraded = memoryDegraded;
        updateUiState(false, "当前 Gateway：" + runtimeRepository.getActiveModelGateway());
        // 预填"已保存 provider"的草稿,这样切回去时能看到上次保存的完整配置
        ModelConfig saved = modelRepository.load();
        if (saved != null && saved.providerId != null) {
            drafts.put(saved.providerId, saved);
            Log.d(TAG, "[ModelApi] seed draft provider=" + saved.providerId
                    + " model=" + saved.model);
        }
    }

    /** V0.5.3 P1-3:统一 UiState 更新入口,自动透传 memoryDegraded(避免 6 处 new ModelUiState 重复)。 */
    private void updateUiState(boolean loading, String status) {
        uiState.setValue(new ModelUiState(loading, status, memoryDegraded));
    }

    public LiveData<ModelUiState> getUiState() { return uiState; }
    public List<ModelProviderPreset> getProviderPresets() { return modelRepository.getProviderPresets(); }
    public ModelConfig getSavedConfig() { return modelRepository.load(); }

    /**
     * 保存某个 provider 的表单草稿。切 provider 前调用,把当前输入框内容缓存住,
     * 这样切回时不需要重新输入。
     */
    public synchronized void saveDraft(String providerId, ModelConfig draft) {
        if (providerId == null || draft == null) return;
        drafts.put(providerId, draft);
        Log.d(TAG, "[ModelApi] draft saved provider=" + providerId
                + " endpointChars=" + draft.endpoint.length()
                + " apiKeyChars=" + (draft.apiKey == null ? 0 : draft.apiKey.length()));
    }

    public synchronized ModelConfig loadDraft(String providerId) {
        return drafts.get(providerId);
    }

    public void testConnection(ModelConfig config) {
        try {
            config.validate();
        } catch (Exception error) {
            uiState.setValue(new ModelUiState(false, safeMessage(error), memoryDegraded));
            return;
        }
        long operationId = beginOperation("正在连接 " + config.displayName + "…");
        activeFuture = executor.submit(() -> {
            try {
                long start = System.nanoTime();
                String reply = modelRepository.testConnection(config);
                long millis = (System.nanoTime() - start) / 1_000_000L;
                postIfCurrent(operationId, "连接成功 · " + millis + " ms\n" + truncate(reply));
            } catch (Exception error) {
                postIfCurrent(operationId, "连接失败\n" + safeMessage(error));
            }
        });
    }

    public void saveAndApply(ModelConfig config) {
        try {
            config.validate();
        } catch (Exception error) {
            uiState.setValue(new ModelUiState(false, "保存失败\n" + safeMessage(error), memoryDegraded));
            return;
        }
        long operationId = beginOperation("正在加密保存配置…");
        activeFuture = executor.submit(() -> {
            try {
                // P1: 先验证 gateway 可创建（端侧会 load 模型），成功后再落盘——避免"存了但加载失败"的分裂
                com.matrix.agent.core.agent.ModelGateway gateway =
                        modelRepository.createModelGateway(config);
                modelRepository.save(config);
                runtimeRepository.setModelGateway(gateway,
                        modelRepository.displayName(config));
                // V0.5.2-rev 评审 P2-2:Gateway 与 IntentClassifier 同步切换——
                // 旧实现只更 Gateway,IntentClassifier 维持启动时配置,意图分类仍用旧 Provider / Keyword。
                runtimeRepository.setIntentClassifier(modelRepository.buildIntentClassifier(config));
                postIfCurrent(operationId,
                        "已加密保存并应用\n当前 Gateway：" + runtimeRepository.getActiveModelGateway());
            } catch (Exception error) {
                postIfCurrent(operationId, "保存失败\n" + safeMessage(error));
            }
        });
    }

    public void useDemoGateway() {
        operationSequence.incrementAndGet();
        cancelActiveOperation();
        runtimeRepository.setModelGateway(modelRepository.createDemoGateway(), "离线 DemoModelGateway");
        // V0.5.2-rev 评审 P2-2:Demo 路径回退 Keyword,确保切回离线时 LLM 不再被调用。
        runtimeRepository.setIntentClassifier(modelRepository.buildKeywordClassifier());
        updateUiState(false, "已切换：" + runtimeRepository.getActiveModelGateway());
    }

    private long beginOperation(String status) {
        long operationId = operationSequence.incrementAndGet();
        cancelActiveOperation();
        updateUiState(true, status);
        return operationId;
    }

    private void postIfCurrent(long operationId, String status) {
        if (operationSequence.get() == operationId) {
            uiState.postValue(new ModelUiState(false, status, memoryDegraded));
        }
    }

    private synchronized void cancelActiveOperation() {
        if (activeFuture != null) activeFuture.cancel(true);
        activeFuture = null;
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() <= 600 ? value : value.substring(0, 600) + "…";
    }

    private static String safeMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override
    protected void onCleared() {
        cancelActiveOperation();
        executor.shutdownNow();
    }
}
