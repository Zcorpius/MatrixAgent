package com.matrix.agent.core.voice;

/**
 * TTS 播报端口。Voice V1。
 *
 * <p>消费 {@link SpeakableResponse} 播报,完成后回调 {@link Listener#onDone()}。Demo 实现为
 * {@code platform.voice.AndroidTtsAdapter}(第 4 步,Android {@code TextToSpeech});本接口让
 * {@code VoiceSessionController} 可在 JVM 单测中用 fake 替换。
 */
public interface TtsPort {
    /** 注册回调。 */
    void setListener(Listener listener);

    /** 播报 SpeakableResponse(已脱敏、已截断)。完成后触发 {@link Listener#onDone()}。 */
    void speak(SpeakableResponse response);

    /** 立即停止当前播报(barge-in / cancel)。 */
    void stop();

    /** TTS 回调。 */
    interface Listener {
        /** 播报完成。 */
        void onDone();

        /** 播报错误。 */
        void onError(String code);
    }
}
