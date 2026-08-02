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

    public ModelApiViewModel(AgentRuntimeRepository runtimeRepository,
            ModelGatewayRepository modelRepository) {
        this.runtimeRepository = runtimeRepository;
        this.modelRepository = modelRepository;
        uiState.setValue(new ModelUiState(false, "当前 Gateway：" + runtimeRepository.getActiveModelGateway()));
        // 预填"已保存 provider"的草稿,这样切回去时能看到上次保存的完整配置
        ModelConfig saved = modelRepository.load();
        if (saved != null && saved.providerId != null) {
            drafts.put(saved.providerId, saved);
            Log.d(TAG, "[ModelApi] seed draft provider=" + saved.providerId
                    + " model=" + saved.model);
        }
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
            uiState.setValue(new ModelUiState(false, safeMessage(error)));
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
            uiState.setValue(new ModelUiState(false, "保存失败\n" + safeMessage(error)));
            return;
        }
        long operationId = beginOperation("正在加密保存配置…");
        activeFuture = executor.submit(() -> {
            try {
                modelRepository.save(config);
                runtimeRepository.setModelGateway(modelRepository.createModelGateway(config),
                        modelRepository.displayName(config));
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
        uiState.setValue(new ModelUiState(false, "已切换：" + runtimeRepository.getActiveModelGateway()));
    }

    private long beginOperation(String status) {
        long operationId = operationSequence.incrementAndGet();
        cancelActiveOperation();
        uiState.setValue(new ModelUiState(true, status));
        return operationId;
    }

    private void postIfCurrent(long operationId, String status) {
        if (operationSequence.get() == operationId) uiState.postValue(new ModelUiState(false, status));
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
