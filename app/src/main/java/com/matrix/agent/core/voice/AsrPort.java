package com.matrix.agent.core.voice;

/**
 * 流式 ASR 端口。Voice V1。
 *
 * <p>消费 16kHz / 单声道 / 16bit little-endian PCM,产出 partial / final 文本回调。
 * Demo 实现为 {@code platform.voice.VoskAsrAdapter}(中文小模型,自由 ASR);量产可替换为
 * OEM 或端侧引擎,不影响 {@link VoiceSessionState} 与 Controller。
 *
 * <p>线程模型:{@link #feed(byte[])} 由采音线程串行调用;回调通过 {@link Listener} 触发,
 * Controller 侧应将回调转投到状态机线程后再 {@code transit}。
 */
public interface AsrPort {
    /** 注册回调。 */
    void setListener(Listener listener);

    /** 初始化 ASR 引擎(加载模型与 Recognizer)。 */
    void start();

    /**
     * 喂一帧 PCM(16kHz mono 16bit LE)。引擎内部 {@code acceptWaveForm} 返回 true 时
     * 同时触发 {@link Listener#onFinal(String)} 与(若共享)VadPort 的 ENDPOINT。
     */
    void feed(byte[] pcm);

    /** 释放当前 Recognizer,停止识别。 */
    void stop();

    /** ASR 回调。 */
    interface Listener {
        /** 中间识别结果(partial)。 */
        void onPartial(String text);

        /** 端点后的最终结果(final)。 */
        void onFinal(String text);

        /** 识别错误(模型未加载 / native 错误)。 */
        void onError(String code);
    }
}
