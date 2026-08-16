package com.matrix.agent.presentation.state;

import com.matrix.agent.core.voice.VoiceSessionState;

/**
 * 语音闭环界面状态。
 *
 * <p>{@code state} 是当前会话状态机;{@code partial} 是 ASR 中间文本;{@code status} 是提示文案
 * (下载进度 / 就绪 / 错误)。
 */
public final class VoiceUiState {
    public final VoiceSessionState.State state;
    public final String partial;
    public final String status;

    public VoiceUiState(VoiceSessionState.State state, String partial, String status) {
        this.state = state;
        this.partial = partial;
        this.status = status;
    }
}
