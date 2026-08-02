package com.matrix.agent.core.agent;

/**
 * @deprecated V0.4.0 起被 {@link AgentIteration} + {@link Trajectory} 取代。
 * 保留以便 UI / 测试逐步迁移,后续版本会删除。
 */
@Deprecated
public final class TraceEvent {
    private final long timestampMillis;
    private final TaskState state;
    private final String message;

    public TraceEvent(TaskState state, String message) {
        this.timestampMillis = System.currentTimeMillis();
        this.state = state;
        this.message = message;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public TaskState getState() {
        return state;
    }

    public String getMessage() {
        return message;
    }
}
