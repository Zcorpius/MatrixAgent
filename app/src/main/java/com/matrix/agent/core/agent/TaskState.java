package com.matrix.agent.core.agent;

public enum TaskState {
    RECEIVED,
    UNDERSTANDING,
    PLANNED,
    EXECUTING,
    VERIFYING,
    SUCCEEDED,
    PARTIALLY_SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT
}
