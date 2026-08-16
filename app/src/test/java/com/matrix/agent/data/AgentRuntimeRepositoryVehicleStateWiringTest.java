package com.matrix.agent.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.matrix.agent.core.agent.AgentBudget;
import com.matrix.agent.core.agent.AgentEngine;
import com.matrix.agent.core.agent.AgentOutcome;
import com.matrix.agent.core.agent.DefaultContextUpdater;
import com.matrix.agent.core.agent.ModelCallExecutor;
import com.matrix.agent.core.agent.ModelGateway;
import com.matrix.agent.core.agent.ModelTurn;
import com.matrix.agent.core.agent.ModelTurnRequest;
import com.matrix.agent.core.agent.SteerMailbox;
import com.matrix.agent.core.agent.TaskScheduler;
import com.matrix.agent.core.capability.CapabilityRegistry;
import com.matrix.agent.core.identity.Actor;
import com.matrix.agent.core.identity.CancellationToken;
import com.matrix.agent.core.identity.MockVehicleStateSource;
import com.matrix.agent.core.identity.VehicleState;
import com.matrix.agent.core.memory.InMemoryMemoryStore;
import com.matrix.agent.core.policy.PolicyEngine;
import com.matrix.agent.core.session.SessionLockManager;
import com.matrix.agent.core.session.SessionManager;
import com.matrix.agent.core.tool.MockCapabilityProvider;
import com.matrix.agent.core.tool.ToolExecutor;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * 验证 Repository.execute 把 {@link com.matrix.agent.core.identity.VehicleStateSource#snapshot()}
 * 真正注入到 AgentRequest.currentVehicleState,而不是走旧版默认 satisfyAllPredicates。
 *
 * <p>构造 CapturingGateway 在 decide() 中捕获 AgentRequest,验证其 vehicleState 与
 * stateSource.snapshot() 一致;切换 stateSource 后再次 execute,验证捕获的 vehicleState 同步变化。
 */
public final class AgentRuntimeRepositoryVehicleStateWiringTest {

    @Test
    public void repositoryInjectsStateSourceSnapshotIntoRequest() {
        MockVehicleStateSource stateSource = new MockVehicleStateSource();
        stateSource.setGear(VehicleState.Gear.P);
        AtomicReference<com.matrix.agent.core.identity.AgentRequest> captured = new AtomicReference<>();
        ModelGateway capturingGateway = req -> {
            captured.set(req.getAgentRequest());
            return ModelTurn.directAnswer("ok");
        };

        AgentRuntimeRepository repo = buildRepository(capturingGateway, stateSource);
        AgentOutcome outcome = repo.execute("查电量", Actor.DRIVER, new CancellationToken());
        assertNotNull(outcome);
        assertNotNull(captured.get());
        assertEquals("stateSource gear=P 应被注入到 request",
                VehicleState.Gear.P, captured.get().getCurrentVehicleState().getGear());
    }

    @Test
    public void repositoryReflectsStateSourceMutationAcrossRequests() {
        MockVehicleStateSource stateSource = new MockVehicleStateSource();
        AtomicReference<com.matrix.agent.core.identity.AgentRequest> captured = new AtomicReference<>();
        ModelGateway capturingGateway = req -> {
            captured.set(req.getAgentRequest());
            return ModelTurn.directAnswer("ok");
        };

        AgentRuntimeRepository repo = buildRepository(capturingGateway, stateSource);

        stateSource.setGear(VehicleState.Gear.P);
        repo.execute("第一次", Actor.DRIVER, new CancellationToken());
        assertEquals(VehicleState.Gear.P, captured.get().getCurrentVehicleState().getGear());

        stateSource.setGear(VehicleState.Gear.D);
        repo.execute("第二次", Actor.DRIVER, new CancellationToken());
        assertEquals("切换 stateSource 后 request.vehicleState 应同步变化",
                VehicleState.Gear.D, captured.get().getCurrentVehicleState().getGear());
    }

    private static AgentRuntimeRepository buildRepository(ModelGateway gateway,
            MockVehicleStateSource stateSource) {
        CapabilityRegistry registry = CapabilityRegistry.createDemoRegistry();
        PolicyEngine policyEngine = new PolicyEngine(registry);
        SessionManager sessionManager = new SessionManager();
        SessionLockManager sessionLockManager = new SessionLockManager();
        ToolExecutor toolExecutor = new ToolExecutor(2);
        ModelCallExecutor modelCallExecutor = new ModelCallExecutor(2);
        SteerMailbox mailbox = new SteerMailbox();
        AgentBudget budget = new AgentBudget();
        MockCapabilityProvider provider = new MockCapabilityProvider(new InMemoryMemoryStore());
        TaskScheduler scheduler = new TaskScheduler(2, sessionLockManager);
        AgentRuntimeRepository.AgentEngineFactory engineFactory = gw -> new AgentEngine(
                gw, modelCallExecutor, policyEngine, registry, provider, sessionManager,
                new DefaultContextUpdater(), sessionLockManager, toolExecutor, budget, mailbox);
        return new AgentRuntimeRepository(engineFactory, provider, sessionManager,
                new InMemoryMemoryStore(), gateway, "capturing-gateway", budget, scheduler,
                stateSource, registry);
    }
}
