package com.matrix.agent.platform.voice;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import com.matrix.agent.core.voice.SpeakableResponse;
import com.matrix.agent.core.voice.TtsPort;

import java.util.Locale;

/**
 * {@link TtsPort} 的 Android 实现。Voice V1 Stage 4。
 *
 * <p>封装 {@link TextToSpeech}:speak 用 {@code QUEUE_FLUSH}(新播报打断旧的),
 * {@link UtteranceProgressListener} 的 onDone/onError 转 {@link Listener}。空文本(CANCELLED 静默)
 * 直接 onDone,让状态机推进到 IDLE。
 */
public final class AndroidTtsAdapter implements TtsPort {
    private static final String TAG = "MatrixAgent";
    private static final String UTTERANCE_ID = "voice_tts";

    // 非 final:OnInitListener lambda 在 new TextToSpeech 期间引用 tts,
    // blank final 会触发"可能尚未初始化";运行期单次赋值,逻辑上不可变。
    private TextToSpeech tts;
    private volatile boolean ready = false;
    private Listener listener;

    public AndroidTtsAdapter(Context context) {
        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                ready = true;
                Log.i(TAG, "[Voice] TTS 初始化就绪");
            } else {
                Log.e(TAG, "[Voice] TTS 初始化失败 status=" + status);
            }
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
            }

            @Override
            public void onDone(String utteranceId) {
                Listener l = listener;
                if (l != null) l.onDone();
            }

            @Override
            public void onError(String utteranceId) {
                Listener l = listener;
                if (l != null) l.onError("TTS_UTTERANCE_ERROR");
            }
        });
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void speak(SpeakableResponse response) {
        if (!ready) {
            Log.w(TAG, "[Voice] TTS 未就绪,跳过播报");
            Listener l = listener;
            if (l != null) l.onError("TTS_NOT_READY");
            return;
        }
        String text = response.getText();
        if (text == null || text.isEmpty()) {
            // CANCELLED 静默:不播报,直接 done 让状态机回 IDLE。
            Listener l = listener;
            if (l != null) l.onDone();
            return;
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID);
    }

    @Override
    public void stop() {
        try {
            tts.stop();
        } catch (Exception e) {
            Log.w(TAG, "[Voice] TTS stop: " + e.getMessage());
        }
    }

    /** 释放 TTS 引擎(ViewModel onCleared 调)。 */
    public void shutdown() {
        try {
            tts.stop();
            tts.shutdown();
        } catch (Exception e) {
            Log.w(TAG, "[Voice] TTS shutdown: " + e.getMessage());
        }
    }
}
