package com.matrix.agent.core.agent;
import com.matrix.agent.core.tool.*;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @deprecated V0.4.0 起 Agent Loop 使用 {@link Trajectory}。TaskPlan 仅由 {@code LlmPlanner}
 * 兼容路径使用,后续版本会删除。
 */
@Deprecated
public final class TaskPlan {
    private final String summary;
    private final List<ToolCall> steps;

    public TaskPlan(String summary, List<ToolCall> steps) {
        this.summary = summary;
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
    }

    public String getSummary() {
        return summary;
    }

    public List<ToolCall> getSteps() {
        return steps;
    }
}
