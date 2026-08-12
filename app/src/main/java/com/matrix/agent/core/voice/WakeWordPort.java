package com.matrix.agent.core.voice;

/**
 * 唤醒词端口。Voice V1。
 *
 * <p>消费 16kHz / 单声道 / 16bit LE PCM,检测到唤醒词时触发 {@link Listener#onWake()}。
 * Demo 实现为 {@code platform.voice.VoskWakeAdapter}:英文小模型 + grammar 受限词表
 * ("hey matrix")做关键词检测。量产替换为 OEM DSP / VoiceInteractionService 可信唤醒事件。
 */
public interface WakeWordPort {
    void setListener(Listener listener);

    /** 初始化唤醒引擎(加载唤醒模型与 grammar Recognizer)。 */
    void start();

    /** 喂一帧 PCM。命中 grammar 唤醒词时触发 onWake。 */
    void feed(byte[] pcm);

    /** 释放 Recognizer,停止唤醒检测。 */
    void stop();

    /** 唤醒回调。 */
    interface Listener {
        /** 检测到唤醒词。 */
        void onWake();

        void onError(String code);
    }
}
