package com.matrix.agent.platform.voice;

import android.util.Log;

import com.matrix.agent.core.voice.AsrPort;

import java.io.IOException;

/**
 * {@link AsrPort} 的 Vosk 实现。Voice V1 Stage 2。
 *
 * <p>委托 {@link VoskAsrEngine},把 engine 的 partial / final 转成 {@link AsrPort.Listener} 回调。
 * 与 {@link VoskEndpointAdapter} 共享同一 engine 实例(构造注入);endpoint 信号由 EndpointAdapter
 * 监听,本类忽略。
 */
public final class VoskAsrAdapter implements AsrPort {
    private static final String TAG = "MatrixAgent";
    private static final String ERR_START = "VOSK_ASR_START_FAILED";

    private final VoskAsrEngine engine;
    private AsrPort.Listener listener;
    private VoskAsrEngine.Listener bridge;

    public VoskAsrAdapter(VoskAsrEngine engine) {
        if (engine == null) throw new IllegalArgumentException("engine 不能为空");
        this.engine = engine;
    }

    @Override
    public void setListener(AsrPort.Listener listener) {
        this.listener = listener;
        if (bridge != null) engine.removeListener(bridge);
        bridge = new VoskAsrEngine.Listener() {
            @Override
            public void onPartial(String text) {
                if (VoskAsrAdapter.this.listener != null) VoskAsrAdapter.this.listener.onPartial(text);
            }

            @Override
            public void onFinal(String text) {
                if (VoskAsrAdapter.this.listener != null) VoskAsrAdapter.this.listener.onFinal(text);
            }

            @Override
            public void onEndpoint() {
                // 由 VoskEndpointAdapter 处理。
            }
        };
        engine.addListener(bridge);
    }

    @Override
    public void start() {
        try {
            engine.start();
        } catch (IOException e) {
            Log.e(TAG, "[Voice] VoskAsr 启动失败: " + e.getMessage());
            if (listener != null) listener.onError(ERR_START);
        }
    }

    @Override
    public void feed(byte[] pcm) {
        engine.feed(pcm);
    }

    @Override
    public void stop() {
        engine.stop();
    }
}
