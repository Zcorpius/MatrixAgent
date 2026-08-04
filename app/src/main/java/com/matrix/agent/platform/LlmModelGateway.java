package com.matrix.agent.platform;

import android.util.Log;

import com.matrix.agent.core.agent.AgentMessage;
import com.matrix.agent.core.agent.ModelGateway;
import com.matrix.agent.core.agent.ModelTurn;
import com.matrix.agent.core.agent.ModelTurnRequest;
import com.matrix.agent.core.capability.CapabilityRegistry;
import com.matrix.agent.core.capability.ToolDefinition;
import com.matrix.agent.core.identity.AgentRequest;
import com.matrix.agent.core.memory.MemoryStore;
import com.matrix.agent.core.session.SessionContext;

import java.util.List;

/**
 * V0.4.0 ModelGateway 的 LLM 实现。
 *
 * 路由策略:
 * - NATIVE_TOOL_CALLING + ANTHROPIC_MESSAGES:走 {@link ModelApiClient#callAnthropicWithTools}
 *   多轮原生 Tool Calling(连续 tool_result 合并到同一 user message,Tool Call ID 透传)。
 * - NATIVE_TOOL_CALLING + OPENAI_CHAT:走 {@link ModelApiClient#callOpenAiWithTools}
 *   多轮原生 Tool Calling(每个 tool_call 一条独立 tool message,tool_call_id 透传)。
 * - NATIVE_TOOL_CALLING + GEMINI_GENERATE_CONTENT(V0.5.2 Stage 10):走
 *   {@link ModelApiClient#callGeminiWithTools} 多轮原生 Tool Calling
 *   (functionResponse 合并到 user message,name 关联,Gemini 协议无 id 字段)。
 * - 其他组合(STRUCTURED_JSON_COMPATIBILITY 或不支持原生 Tool Calling 的协议):
 *   走 {@link LlmPlanner} 兼容路径,单轮规划,看到 Observation 后直接答复。
 */
public final class LlmModelGateway implements ModelGateway {
    private static final String TAG = "MatrixAgent";
    private final ModelApiClient client;
    private final ModelConfig config;
    private final LlmPlanner legacy;
    private final CapabilityRegistry registry;
    private final boolean useAnthropicNative;
    private final boolean useOpenAiNative;
    private final boolean useGeminiNative;

    public LlmModelGateway(ModelApiClient client, ModelConfig config, CapabilityRegistry registry) {
        this(client, config, registry, null);
    }

    public LlmModelGateway(ModelApiClient client, ModelConfig config, CapabilityRegistry registry,
            MemoryStore memoryStore) {
        this.client = client;
        this.config = config;
        this.legacy = new LlmPlanner(client, config, registry, memoryStore);
        this.registry = registry;
        this.useAnthropicNative = config.plannerMode == PlannerMode.NATIVE_TOOL_CALLING
                && config.protocol == ApiProtocol.ANTHROPIC_MESSAGES;
        this.useOpenAiNative = config.plannerMode == PlannerMode.NATIVE_TOOL_CALLING
                && config.protocol == ApiProtocol.OPENAI_CHAT;
        this.useGeminiNative = config.plannerMode == PlannerMode.NATIVE_TOOL_CALLING
                && config.protocol == ApiProtocol.GEMINI_GENERATE_CONTENT;
        Log.i(TAG, "[LlmGateway] init provider=" + config.displayName
                + " model=" + config.model
                + " protocol=" + config.protocol
                + " plannerMode=" + config.plannerMode
                + " anthropicNativeRoute=" + useAnthropicNative
                + " openAiNativeRoute=" + useOpenAiNative
                + " geminiNativeRoute=" + useGeminiNative
                + " memoryStore=" + (memoryStore == null ? "null" : memoryStore.getClass().getSimpleName()));
    }

    /** V0.5.0 Stage 3:把 Memory 召回器透传给内部 LlmPlanner(结构化 JSON 兼容路径)。 */
    public void setMemoryRecaller(com.matrix.agent.core.memory.MemoryRecaller recaller) {
        this.legacy.setMemoryRecaller(recaller);
    }

    @Override
    public ModelTurn decide(ModelTurnRequest request) {
        // V0.4.3 Stage C:per-request zone 投影——主驾/副驾看到不同 tool 列表,
        // V0.4.2 Stage E 加的 toToolDefinitions(VehicleZone) 在 V0.4.3 才真正接入。
        java.util.List<ToolDefinition> tools = registry.toToolDefinitions(
                request.getAgentRequest().getOccupantZone());
        Log.d(TAG, "[LlmGateway] decide req=" + request.getAgentRequest().getRequestId()
                + " zone=" + request.getAgentRequest().getOccupantZone()
                + " tools=" + tools.size()
                + " route=" + routeName()
                + " conversationMsgs=" + request.getConversation().size());
        try {
            com.matrix.agent.core.identity.CancellationToken token =
                    request.getAgentRequest().getCancellationToken();
            long deadlineAtMillis = request.getAgentRequest().getDeadlineAtMillis();
            if (useAnthropicNative) {
                ModelTurn turn = client.callAnthropicWithTools(config, request.getSystemPrompt(),
                        request.getConversation(), tools, token, deadlineAtMillis);
                Log.d(TAG, "[LlmGateway] anthropic-native returned hasToolCalls=" + turn.hasToolCalls()
                        + " toolCalls=" + (turn.hasToolCalls() ? turn.getToolCalls().size() : 0));
                return turn;
            }
            if (useOpenAiNative) {
                ModelTurn turn = client.callOpenAiWithTools(config, request.getSystemPrompt(),
                        request.getConversation(), tools, token, deadlineAtMillis);
                Log.d(TAG, "[LlmGateway] openai-native returned hasToolCalls=" + turn.hasToolCalls()
                        + " toolCalls=" + (turn.hasToolCalls() ? turn.getToolCalls().size() : 0));
                return turn;
            }
            if (useGeminiNative) {
                ModelTurn turn = client.callGeminiWithTools(config, request.getSystemPrompt(),
                        request.getConversation(), tools, token, deadlineAtMillis);
                Log.d(TAG, "[LlmGateway] gemini-native returned hasToolCalls=" + turn.hasToolCalls()
                        + " toolCalls=" + (turn.hasToolCalls() ? turn.getToolCalls().size() : 0));
                return turn;
            }
            ModelTurn legacyTurn = legacyDecide(request);
            Log.d(TAG, "[LlmGateway] legacy returned hasToolCalls=" + legacyTurn.hasToolCalls()
                    + " toolCalls=" + (legacyTurn.hasToolCalls() ? legacyTurn.getToolCalls().size() : 0));
            return legacyTurn;
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            Log.e(TAG, "[LlmGateway] decide failed: " + safeMessage(error), error);
            throw new IllegalStateException("LLM 模型决策失败:" + safeMessage(error), error);
        }
    }

    private String routeName() {
        if (useAnthropicNative) return "anthropic-native-tools";
        if (useOpenAiNative) return "openai-native-tools";
        if (useGeminiNative) return "gemini-native-tools";
        return "legacy-compat-plan";
    }

    private ModelTurn legacyDecide(ModelTurnRequest request) {
        if (hasToolResult(request.getConversation())) {
            Log.d(TAG, "[LlmGateway] legacy: tool_result observed -> direct answer");
            return ModelTurn.directAnswer("LLM 兼容路径:单轮规划已执行,任务结束");
        }
        AgentRequest agentRequest = request.getAgentRequest();
        SessionContext context = request.getSessionContext();
        com.matrix.agent.core.agent.TaskPlan plan = legacy.plan(agentRequest, context);
        Log.d(TAG, "[LlmGateway] legacy plan steps=" + plan.getSteps().size()
                + " summary=\"" + safeTruncate(plan.getSummary(), 120) + "\"");
        if (plan.getSteps().isEmpty()) {
            return ModelTurn.directAnswer(plan.getSummary());
        }
        return ModelTurn.ofToolCalls(plan.getSteps(), plan.getSummary());
    }

    private static boolean hasToolResult(List<AgentMessage> conversation) {
        for (AgentMessage message : conversation) {
            if (message.getRole() == AgentMessage.Role.TOOL) return true;
        }
        return false;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null) return error.getClass().getSimpleName();
        return message.length() > 120 ? message.substring(0, 120) + "…" : message;
    }

    private static String safeTruncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
