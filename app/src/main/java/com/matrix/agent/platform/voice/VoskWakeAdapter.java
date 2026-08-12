package com.matrix.agent.platform.voice;

import android.util.Log;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;

/**
 * {@link com.matrix.agent.core.voice.WakeWordPort} 的 Vosk 实现。Voice V1 Stage 2。
 *
 * <p>用英文小模型 + grammar 受限词表做关键词检测:grammar 只含 "hey matrix" 与 "[unk]",
 * 命中唤醒词时触发 {@link com.matrix.agent.core.voice.WakeWordPort.Listener#onWake()}。
 * 同时检查 final 与 partial,降低响应延迟。
 *
 * <p><b>线程安全</b>:细粒度锁 {@link #recognizerLock} 只保护 recognizer 的 native 操作
 * (acceptWaveForm + 取 result)与 close;唤醒词匹配与 {@code fireWake} 在锁外。
 *
 * <p>PCM 约定:16kHz、单声道、16bit little-endian。
 */
public final class VoskWakeAdapter implements com.matrix.agent.core.voice.WakeWordPort {
    private static final String TAG = "MatrixAgent";
    private static final String ERR_START = "VOSK_WAKE_START_FAILED";

    /** grammar JSON:唤醒词 + [unk] 通配(其它声音不触发)。 */
    private static final String GRAMMAR = "[\"hey matrix\", \"[unk]\"]";
    private static final String WAKE_PHRASE = "hey matrix";

    private final Model enModel;
    private final Object recognizerLock = new Object();
    private volatile Listener listener;
    private Recognizer recognizer; // recognizerLock 保护

    public VoskWakeAdapter(Model enModel) {
        if (enModel == null) throw new IllegalArgumentException("enModel 不能为空");
        this.enModel = enModel;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void start() {
        try {
            synchronized (recognizerLock) {
                if (recognizer == null) {
                    recognizer = new Recognizer(enModel, VoskAsrEngine.SAMPLE_RATE, GRAMMAR);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "[Voice] VoskWake 启动失败: " + e.getMessage());
            Listener l = listener;
            if (l != null) l.onError(ERR_START);
        }
    }

    @Override
    public void feed(byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        String raw;
        boolean endpoint;
        synchronized (recognizerLock) {
            if (recognizer == null) return;
            endpoint = recognizer.acceptWaveForm(pcm, pcm.length);
            raw = endpoint ? recognizer.getResult() : recognizer.getPartialResult();
        }
        // 锁外:JSON 解析 + 唤醒词匹配 + 回调
        String text = parse(raw, endpoint ? "text" : "partial");
        if (containsWake(text)) {
            Listener l = listener;
            if (l != null) l.onWake();
        }
    }

    @Override
    public void stop() {
        synchronized (recognizerLock) {
            if (recognizer != null) {
                recognizer.close();
                recognizer = null;
            }
        }
    }

    private static boolean containsWake(String text) {
        return text != null && text.toLowerCase().contains(WAKE_PHRASE);
    }

    private static String parse(String json, String field) {
        try {
            return new JSONObject(json).optString(field, "").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
