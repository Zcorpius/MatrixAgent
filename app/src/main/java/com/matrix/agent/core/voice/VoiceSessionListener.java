package com.matrix.agent.core.voice;

/**
 * 语音会话 UI 观察者。Voice V1。
 *
 * <p>Controller 把 partial 文本与状态迁移通知给 UI(Demo 第 4 步 Activity 实现它刷新界面)。
 * 方法默认空实现,UI 按需覆盖。
 */
public interface VoiceSessionListener {
    /** ASR partial 中间文本。 */
    default void onPartial(String text) {
    }

    /** 会话状态迁移。 */
    default void onStateChanged(VoiceSessionState.State state) {
    }
}
