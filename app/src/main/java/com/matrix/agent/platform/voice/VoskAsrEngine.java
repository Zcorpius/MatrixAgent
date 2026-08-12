package com.matrix.agent.platform.voice;

import android.util.Log;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Vosk ASR 共享内核。Voice V1 Stage 2。
 *
 * <p>封装中文 ASR {@link Recognizer}(无 grammar),feed 一帧 PCM 一次,产出 partial / final /
 * endpoint 三类回调。{@link VoskAsrAdapter} 与 {@link VoskEndpointAdapter} 共享同一实例。
 *
 * <p><b>线程安全</b>:采音线程的 {@code acceptWaveForm} 与 Controller 线程的 {@code close} 必须互斥
 * (否则 native use-after-close 崩溃)。用细粒度锁 {@link #recognizerLock}——**只在 recognizer 的
 * native 操作**({@code acceptWaveForm} + 取 result)处持锁;JSON 解析、listeners 回调在锁外进行
 * (不碰 recognizer,无需互斥,且避免回调链拖长锁持有时间)。{@code start}/{@code reset}/{@code stop}
 * 共用同一把锁。listeners 用 {@link CopyOnWriteArrayList},增删无锁。
 *
 * <p>PCM 约定:16kHz、单声道、16bit little-endian。
 */
public final class VoskAsrEngine {
    private static final String TAG = "MatrixAgent";

    /** Vosk 采样率固定 16kHz。 */
    public static final float SAMPLE_RATE = 16000f;

    private final Model cnModel;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    /** 只保护 recognizer 字段的 native 操作与 close,不覆盖 JSON/回调。 */
    private final Object recognizerLock = new Object();
    private Recognizer recognizer; // recognizerLock 保护

    public VoskAsrEngine(Model cnModel) {
        if (cnModel == null) throw new IllegalArgumentException("cnModel 不能为空");
        this.cnModel = cnModel;
    }

    /** engine 回调:partial / final / endpoint。AsrAdapter 与 EndpointAdapter 各注册一个。 */
    public interface Listener {
        void onPartial(String text);

        void onFinal(String text);

        void onEndpoint();
    }

    public void addListener(Listener l) {
        if (l != null) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** 创建 ASR Recognizer(无 grammar,自由识别)。 */
    public void start() throws IOException {
        synchronized (recognizerLock) {
            if (recognizer == null) {
                recognizer = new Recognizer(cnModel, SAMPLE_RATE);
            }
        }
    }

    /**
     * 喂一帧 PCM。锁内只做 recognizer 的 native 操作(acceptWaveForm + 取 result);
     * JSON 解析与 listeners 回调在锁外。
     */
    public void feed(byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        String raw;
        boolean endpoint;
        synchronized (recognizerLock) {
            if (recognizer == null) return;
            endpoint = recognizer.acceptWaveForm(pcm, pcm.length);
            raw = endpoint ? recognizer.getResult() : recognizer.getPartialResult();
        }
        if (endpoint) {
            String text = parseText(raw);
            for (Listener l : listeners) {
                l.onFinal(text);
                l.onEndpoint();
            }
        } else {
            String partial = parsePartial(raw);
            if (!partial.isEmpty()) {
                for (Listener l : listeners) l.onPartial(partial);
            }
        }
    }

    /** 重置 Recognizer 状态(丢弃当前 partial)。 */
    public void reset() {
        synchronized (recognizerLock) {
            if (recognizer != null) recognizer.reset();
        }
    }

    /** 释放 Recognizer。与 feed 的 native 调用互斥,避免 use-after-close。 */
    public void stop() {
        synchronized (recognizerLock) {
            if (recognizer != null) {
                recognizer.close();
                recognizer = null;
            }
        }
    }

    private static String parseText(String json) {
        try {
            return new JSONObject(json).optString("text", "").trim();
        } catch (Exception e) {
            Log.w(TAG, "[Voice] Vosk result JSON 解析失败: " + e.getMessage());
            return "";
        }
    }

    private static String parsePartial(String json) {
        try {
            return new JSONObject(json).optString("partial", "").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
