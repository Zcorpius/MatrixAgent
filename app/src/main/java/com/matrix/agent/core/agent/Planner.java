package com.matrix.agent.core.agent;
import com.matrix.agent.core.identity.*;
import com.matrix.agent.core.session.*;


/**
 * @deprecated V0.4.0 起 Agent Loop 使用 {@link ModelGateway}。Planner 仅作为
 * {@code LlmPlanner} 的兼容接口保留,后续版本会删除。
 */
@Deprecated
public interface Planner {
    TaskPlan plan(AgentRequest request, SessionContext sessionContext);
}
