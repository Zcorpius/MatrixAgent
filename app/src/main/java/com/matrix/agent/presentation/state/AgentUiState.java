package com.matrix.agent.presentation.state;

public final class AgentUiState {
    public final boolean loading;
    public final String planner;
    public final String output;

    public AgentUiState(boolean loading, String planner, String output) {
        this.loading = loading;
        this.planner = planner;
        this.output = output;
    }
}
