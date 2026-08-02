package com.matrix.agent.core.agent;
import com.matrix.agent.core.session.*;
import com.matrix.agent.core.tool.*;


public interface ContextUpdater {
    void onToolCompleted(SessionContext context, ToolCall call, ToolResult result);
}
