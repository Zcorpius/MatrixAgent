package com.matrix.agent.presentation.state;

/**
 * 加 memoryDegraded 字段——AppContainer 装配期检测到 SQLCipher/Room 不可用
 * 时 set true,UI 显示"记忆已降级"banner。旧 2 参构造器保留(default false)向后兼容。
 */
public final class ModelUiState {
    public final boolean loading;
    public final String status;
    public final boolean memoryDegraded;

    public ModelUiState(boolean loading, String status) {
        this(loading, status, false);
    }

    public ModelUiState(boolean loading, String status, boolean memoryDegraded) {
        this.loading = loading;
        this.status = status;
        this.memoryDegraded = memoryDegraded;
    }
}
