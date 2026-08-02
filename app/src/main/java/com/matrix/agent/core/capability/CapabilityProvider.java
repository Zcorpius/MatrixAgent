package com.matrix.agent.core.capability;
import com.matrix.agent.core.identity.*;
import com.matrix.agent.core.tool.*;


public interface CapabilityProvider {
    ToolResult execute(AgentRequest request, ToolCall call);
}
