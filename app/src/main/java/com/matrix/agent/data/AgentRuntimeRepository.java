package com.matrix.agent.data;

import android.util.Log;

import com.matrix.agent.core.agent.AgentBudget;
import com.matrix.agent.core.agent.AgentEngine;
import com.matrix.agent.core.agent.AgentOutcome;
import com.matrix.agent.core.agent.ModelGateway;
import com.matrix.agent.core.identity.Actor;
import com.matrix.agent.core.identity.AgentRequest;
import com.matrix.agent.core.identity.CancellationToken;
import com.matrix.agent.core.identity.VehicleZone;
import com.matrix.agent.core.memory.MemoryStore;
import com.matrix.agent.core.session.SessionManager;
import com.matrix.agent.core.tool.MockCapabilityProvider;

import java.util.List;
import java.util.Map;

public final class AgentRuntimeRepository {
    private static final String TAG = "MatrixAgent";
    private final AgentEngineFactory engineFactory;
    private final MockCapabilityProvider mockProvider;
    private final SessionManager sessionManager;
    private final MemoryStore memoryStore;
    /**
     * 第七轮 P2-2:AgentRequest.timeoutMillis 必须用 budget.totalDeadlineMillis 作为单一权威来源,
     * 不再硬编码 60_000L。AppContainer 把同一个 AgentBudget 注入 Repository 和 AgentEngine。
     */
    private final AgentBudget budget;
    private volatile AgentEngine agentEngine;
    private volatile String activeModelGateway;

    public AgentRuntimeRepository(AgentEngineFactory engineFactory,
            MockCapabilityProvider mockProvider, SessionManager sessionManager, MemoryStore memoryStore,
            ModelGateway initialGateway, String initialDisplayName) {
        this(engineFactory, mockProvider, sessionManager, memoryStore, initialGateway,
                initialDisplayName, new AgentBudget());
    }

    public AgentRuntimeRepository(AgentEngineFactory engineFactory,
            MockCapabilityProvider mockProvider, SessionManager sessionManager, MemoryStore memoryStore,
            ModelGateway initialGateway, String initialDisplayName, AgentBudget budget) {
        this.engineFactory = engineFactory;
        this.mockProvider = mockProvider;
        this.sessionManager = sessionManager;
        this.memoryStore = memoryStore;
        this.budget = budget == null ? new AgentBudget() : budget;
        setModelGateway(initialGateway, initialDisplayName);
    }

    public AgentOutcome execute(String command, Actor actor, CancellationToken token) {
        String sessionId = actor == Actor.DRIVER ? "demo-driver" : "demo-passenger";
        // 第七轮 P2-4:command 是完整用户输入,绝不原文进 logcat。仅保留字符数 + 元数据。
        Log.i(TAG, "[Repo] execute command=" + com.matrix.agent.core.agent.SafeLog.USER_INPUT_PLACEHOLDER
                + " commandChars=" + (command == null ? 0 : command.length())
                + " actor=" + actor + " session=" + sessionId
                + " budgetDeadlineMs=" + budget.getTotalDeadlineMillis());
        AgentRequest request = AgentRequest.builder(command, actor)
                .sessionId(sessionId)
                .occupantZone(actor == Actor.DRIVER ? VehicleZone.DRIVER : VehicleZone.PASSENGER)
                .timeoutMillis(budget.getTotalDeadlineMillis())
                .cancellationToken(token)
                .build();
        AgentOutcome outcome = agentEngine.execute(request);
        Log.i(TAG, "[Repo] <- outcome state=" + outcome.getFinalState()
                + " stop=" + outcome.getStopReason()
                + " durationMs=" + outcome.getDurationMillis());
        return outcome;
    }

    public synchronized void setModelGateway(ModelGateway gateway, String displayName) {
        Log.i(TAG, "[Repo] switch gateway -> " + displayName
                + " (" + gateway.getClass().getSimpleName() + ")");
        agentEngine = engineFactory.create(gateway);
        activeModelGateway = displayName;
    }

    public String getActiveModelGateway() { return activeModelGateway; }
    public Map<String, Object> getVehicleState() { return mockProvider.snapshotVehicleState(); }
    public Map<String, List<String>> getSessionTurns() { return sessionManager.snapshotTurns(); }
    public void clearUserData() {
        Log.i(TAG, "[Repo] clearUserData (driver + passenger memory + all sessions)");
        memoryStore.clear("demo-driver");
        memoryStore.clear("demo-passenger");
        sessionManager.clear();
    }

    @FunctionalInterface
    public interface AgentEngineFactory {
        AgentEngine create(ModelGateway gateway);
    }
}
