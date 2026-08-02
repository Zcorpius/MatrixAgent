package com.matrix.agent.presentation.state;

public final class ModelUiState {
    public final boolean loading;
    public final String status;

    public ModelUiState(boolean loading, String status) {
        this.loading = loading;
        this.status = status;
    }
}
