package com.matrix.agent.app;

import android.content.Context;
import android.util.Log;

import com.matrix.agent.core.agent.AgentBudget;
import com.matrix.agent.core.agent.AgentEngine;
import com.matrix.agent.core.agent.DemoModelGateway;
import com.matrix.agent.core.agent.DefaultContextUpdater;
import com.matrix.agent.core.agent.ModelCallExecutor;
import com.matrix.agent.core.capability.CapabilityRegistry;
import com.matrix.agent.core.policy.PolicyEngine;
import com.matrix.agent.core.session.SessionLockManager;
import com.matrix.agent.core.session.SessionManager;
import com.matrix.agent.core.tool.MockCapabilityProvider;
import com.matrix.agent.core.tool.ToolExecutor;
import com.matrix.agent.data.AgentRuntimeRepository;
import com.matrix.agent.data.ModelGatewayRepository;
import com.matrix.agent.platform.ModelApiClient;
import com.matrix.agent.platform.ModelConfig;
import com.matrix.agent.platform.SecureModelConfigStore;
import com.matrix.agent.platform.SharedPreferencesMemoryStore;

/** Explicit composition root for replaceable runtime and platform dependencies. */
public final class AppContainer {
    private static final String TAG = "MatrixAgent";
    private final AgentRuntimeRepository agentRuntimeRepository;
    private final ModelGatewayRepository modelGatewayRepository;

    public AppContainer(Context context) {
        long started = System.nanoTime();
        Log.i(TAG, "[App] AppContainer init start");
        Context appContext = context.getApplicationContext();
        CapabilityRegistry registry = CapabilityRegistry.createDemoRegistry();
        PolicyEngine policyEngine = new PolicyEngine(registry);
        SessionManager sessionManager = new SessionManager();
        SessionLockManager sessionLockManager = new SessionLockManager();
        ToolExecutor toolExecutor = new ToolExecutor(4);
        ModelCallExecutor modelCallExecutor = new ModelCallExecutor(2);
        SharedPreferencesMemoryStore memoryStore = new SharedPreferencesMemoryStore(appContext);
        MockCapabilityProvider provider = new MockCapabilityProvider(memoryStore);
        ModelApiClient modelClient = new ModelApiClient();
        SecureModelConfigStore configStore = new SecureModelConfigStore(appContext);
        modelGatewayRepository = new ModelGatewayRepository(configStore, modelClient, registry, memoryStore);

        // 第七轮 P2-2:同一个 AgentBudget 同时驱动 AgentEngine(字符/迭代/Tool 上限)
        // 和 AgentRequest.timeoutMillis(总 deadline)。Repository 不再硬编码 60_000L。
        AgentBudget sharedBudget = new AgentBudget();
        AgentRuntimeRepository.AgentEngineFactory engineFactory = gateway -> new AgentEngine(
                gateway, modelCallExecutor, policyEngine, registry, provider, sessionManager,
                new DefaultContextUpdater(), sessionLockManager, toolExecutor, sharedBudget);
        agentRuntimeRepository = new AgentRuntimeRepository(engineFactory, provider, sessionManager,
                memoryStore, new DemoModelGateway(), "离线 DemoModelGateway", sharedBudget);

        ModelConfig saved = modelGatewayRepository.load();
        if (saved != null) {
            Log.i(TAG, "[App] saved model config found, applying gateway");
            agentRuntimeRepository.setModelGateway(
                    modelGatewayRepository.createModelGateway(saved),
                    modelGatewayRepository.displayName(saved));
        } else {
            Log.i(TAG, "[App] no saved model config, defaulting to offline DemoModelGateway");
        }
        Log.i(TAG, "[App] AppContainer init done capabilities=" + registry.toToolDefinitions().size()
                + " durationMs=" + ((System.nanoTime() - started) / 1_000_000L));
    }

    public AgentRuntimeRepository getAgentRuntimeRepository() { return agentRuntimeRepository; }
    public ModelGatewayRepository getModelGatewayRepository() { return modelGatewayRepository; }
}
