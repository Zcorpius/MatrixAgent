package com.matrix.agent.core.agent;

import android.util.Log;

import com.matrix.agent.core.capability.CapabilityDefinition;
import com.matrix.agent.core.capability.CapabilityProvider;
import com.matrix.agent.core.capability.CapabilityRegistry;
import com.matrix.agent.core.capability.ToolDefinition;
import com.matrix.agent.core.identity.AgentRequest;
import com.matrix.agent.core.policy.PolicyDecision;
import com.matrix.agent.core.policy.PolicyEngine;
import com.matrix.agent.core.session.SessionContext;
import com.matrix.agent.core.session.SessionLockManager;
import com.matrix.agent.core.session.SessionManager;
import com.matrix.agent.core.tool.ToolCall;
import com.matrix.agent.core.tool.ToolExecutor;
import com.matrix.agent.core.tool.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * V0.4.0 Agent Loop Runtime。
 *
 * 主循环:LLM → ToolCall → Policy → Execute → Observation → LLM。
 * 每轮先做 5 个终止检查(cancel / deadline / 字符预算 / 无 tool_call / tool call 累计上限),
 * 再走 ModelCallExecutor 包裹 ModelGateway 单轮决策,然后顺序处理模型给出的 ToolCall。
 *
 * Policy 二分:
 * - CAPABILITY 拒绝(R3/越权/未注册/区域越界)被累计进 blockedCapabilities,本轮和后续迭代都不再放行。
 * - PARAMETER 拒绝(数值/格式/可补全)回传 Observation,模型可在剩余预算内换参数重试。
 *
 * 终止原因见 {@link StopReason};最终 TaskState 由 {@link #computeFinalState} 给出。
 */
public final class AgentEngine {
    private static final String TAG = "MatrixAgent";

    private final ModelGateway modelGateway;
    private final ModelCallExecutor modelCallExecutor;
    private final PolicyEngine policyEngine;
    private final CapabilityRegistry registry;
    private final CapabilityProvider provider;
    private final SessionManager sessionManager;
    private final ContextUpdater contextUpdater;
    private final SessionLockManager sessionLockManager;
    private final ToolExecutor toolExecutor;
    private final AgentBudget budget;
    private final ModelSanitizer modelSanitizer;
    private final AuditRedactor auditRedactor;

    public AgentEngine(ModelGateway modelGateway, ModelCallExecutor modelCallExecutor,
            PolicyEngine policyEngine, CapabilityRegistry registry, CapabilityProvider provider,
            SessionManager sessionManager, ContextUpdater contextUpdater,
            SessionLockManager sessionLockManager, ToolExecutor toolExecutor) {
        this(modelGateway, modelCallExecutor, policyEngine, registry, provider, sessionManager,
                contextUpdater, sessionLockManager, toolExecutor, new AgentBudget());
    }

    public AgentEngine(ModelGateway modelGateway, ModelCallExecutor modelCallExecutor,
            PolicyEngine policyEngine, CapabilityRegistry registry, CapabilityProvider provider,
            SessionManager sessionManager, ContextUpdater contextUpdater,
            SessionLockManager sessionLockManager, ToolExecutor toolExecutor, AgentBudget budget) {
        if (modelGateway == null) throw new IllegalArgumentException("modelGateway 不能为空");
        if (modelCallExecutor == null) throw new IllegalArgumentException("modelCallExecutor 不能为空");
        if (policyEngine == null) throw new IllegalArgumentException("policyEngine 不能为空");
        if (registry == null) throw new IllegalArgumentException("registry 不能为空");
        if (provider == null) throw new IllegalArgumentException("provider 不能为空");
        if (sessionManager == null) throw new IllegalArgumentException("sessionManager 不能为空");
        if (contextUpdater == null) throw new IllegalArgumentException("contextUpdater 不能为空");
        if (sessionLockManager == null) throw new IllegalArgumentException("sessionLockManager 不能为空");
        if (toolExecutor == null) throw new IllegalArgumentException("toolExecutor 不能为空");
        if (budget == null) throw new IllegalArgumentException("budget 不能为空");
        this.modelGateway = modelGateway;
        this.modelCallExecutor = modelCallExecutor;
        this.policyEngine = policyEngine;
        this.registry = registry;
        this.provider = provider;
        this.sessionManager = sessionManager;
        this.contextUpdater = contextUpdater;
        this.sessionLockManager = sessionLockManager;
        this.toolExecutor = toolExecutor;
        this.budget = budget;
        this.modelSanitizer = new ModelSanitizer(budget.getMaxMessageChars());
        // 第四轮 P1-2:注入 CapabilityRegistry,AuditRedactor 按 schema 脱敏 memory.preference.value、
        // navigation.destination、contact.phone 等业务敏感字段,不再依赖自由文本凭据正则。
        this.auditRedactor = new AuditRedactor(budget.getMaxMessageChars(), registry);
        Log.i(TAG, "[Engine] init gateway=" + modelGateway.getClass().getSimpleName()
                + " budget=" + budget);
    }

    public AgentOutcome execute(AgentRequest request) {
        long started = System.nanoTime();
        // 第七轮 P2-4:BEGIN 行只暴露元数据,用户输入原文用 SafeLog 占位符包裹。
        // 旧实现把 truncate(text, 80) 直接写 logcat——前 80 字符可能含目的地 / 联系人 / 偏好值。
        Log.i(TAG, "[Engine] BEGIN req=" + request.getRequestId()
                + " session=" + request.getSessionId()
                + " actor=" + request.getActor()
                + " zone=" + request.getOccupantZone()
                + " input=" + request.getInputSource()
                + " remainingMs=" + request.remainingMillis()
                + " text=" + SafeLog.USER_INPUT_PLACEHOLDER
                + " textChars=" + safeLength(request.getText()));
        try (SessionLockManager.Handle handle = sessionLockManager.tryAcquire(
                request.getSessionId(), request.remainingMillis())) {
            if (handle == null) {
                Log.w(TAG, "[Engine] session lock acquire timeout, terminal=TIMED_OUT");
                return terminalOutcome(request, TaskState.TIMED_OUT, StopReason.TIMEOUT,
                        "等待会话执行锁超时", started);
            }
            Log.d(TAG, "[Engine] session lock acquired for " + request.getSessionId());
            return executeLocked(request, started);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "[Engine] interrupted by cancellation, terminal=CANCELLED");
            return terminalOutcome(request, TaskState.CANCELLED, StopReason.CANCELLED,
                    "任务已取消", started);
        }
    }

    private AgentOutcome executeLocked(AgentRequest request, long started) {
        Trajectory trajectory = new Trajectory();
        SessionContext sessionContext = sessionManager.getOrCreate(request.getSessionId());
        Log.d(TAG, "[Engine] session context ready turns=" + sessionContext.getRecentTurns().size());

        TaskState preTerminal = cancellationState(request);
        if (preTerminal != null) {
            StopReason reason = preTerminal == TaskState.CANCELLED
                    ? StopReason.CANCELLED : StopReason.TIMEOUT;
            Log.w(TAG, "[Engine] pre-loop terminal state=" + preTerminal + " reason=" + reason);
            trajectory.finish(reason, elapsedMillis(started), 0);
            sessionContext.addTurn("USER: " + SafeLog.USER_INPUT_PLACEHOLDER
                    + " | RESULT: " + preTerminal);
            return new AgentOutcome(request.getRequestId(), preTerminal, reason, trajectory,
                    elapsedMillis(started));
        }

        String systemPrompt = buildSystemPrompt(request);
        List<ToolDefinition> tools = registry.toToolDefinitions();
        List<AgentMessage> conversation = new ArrayList<>();
        // 第七轮 P2-2:maxMessageChars 限制单条消息长度——system/user 输入过长会撑爆总字符预算,
        // 也可能直接被模型 API 拒绝。所有进入 conversation 的消息统一过 enforceMessageBudget。
        conversation.add(enforceMessageBudget(AgentMessage.system(systemPrompt)));
        conversation.add(enforceMessageBudget(AgentMessage.user(request.getText())));
        Log.d(TAG, "[Engine] tools=" + tools.size() + " systemPromptChars=" + systemPrompt.length()
                + " convChars=" + estimateConversationChars(conversation));

        Set<String> blockedCapabilities = new HashSet<>();
        int totalToolCalls = 0;
        StopReason stopReason = StopReason.MAX_ITERATIONS;
        String stopMessage = "达到最大迭代数";
        List<ToolResult> internalResults = new ArrayList<>();

        for (int iteration = 1; iteration <= budget.getMaxIterations(); iteration++) {
            Log.d(TAG, "[Engine] --- iteration " + iteration + "/" + budget.getMaxIterations()
                    + " blocked=" + blockedCapabilities + " totalCalls=" + totalToolCalls
                    + " convChars=" + estimateConversationChars(conversation) + " ---");

            TaskState terminal = cancellationState(request);
            if (terminal != null) {
                stopReason = terminal == TaskState.CANCELLED
                        ? StopReason.CANCELLED : StopReason.TIMEOUT;
                stopMessage = terminal == TaskState.CANCELLED ? "任务已取消" : "任务超过截止时间";
                Log.w(TAG, "[Engine] iter " + iteration + " terminal=" + terminal + " reason=" + stopReason);
                break;
            }
            if (estimateConversationChars(conversation) > budget.getTotalInputChars()) {
                stopReason = StopReason.BUDGET_EXHAUSTED;
                stopMessage = "输入字符预算耗尽";
                Log.w(TAG, "[Engine] iter " + iteration + " budget exhausted convChars="
                        + estimateConversationChars(conversation) + " > "
                        + budget.getTotalInputChars());
                break;
            }

            long iterationStarted = System.nanoTime();
            ModelTurnRequest turnRequest = new ModelTurnRequest(request, conversation, tools,
                    systemPrompt, sessionContext);
            Log.d(TAG, "[Engine] iter " + iteration + " -> ModelGateway.decide()");
            ModelCallExecutor.Result result = modelCallExecutor.decide(modelGateway, turnRequest);
            if (!result.isSuccess()) {
                stopReason = result.getTerminalReason();
                stopMessage = result.getMessage();
                Log.w(TAG, "[Engine] iter " + iteration + " model call terminal reason="
                        + stopReason + " msg=" + truncate(result.getMessage(), 160));
                break;
            }
            Log.d(TAG, "[Engine] iter " + iteration + " <- model turn cost="
                    + elapsedMillis(iterationStarted) + "ms");

            ModelTurn turn = result.getTurn();
            // 第七轮 P2-2:assistant message 加入前同样过预算检查(单条截断 + 条数/字符上限)
            if (!appendMessageWithBudget(conversation, turn.getAssistantMessage())) {
                stopReason = StopReason.BUDGET_EXHAUSTED;
                stopMessage = "加入 assistant 消息后超过字符或条数预算";
                Log.w(TAG, "[Engine] iter " + iteration + " budget exhausted on assistant append"
                        + " convChars=" + estimateConversationChars(conversation)
                        + " msgCount=" + conversation.size());
                trajectory.addIteration(new AgentIteration(iteration,
                        auditRedactAssistant(turn.getAssistantMessage()),
                        snapshots(turn.getToolCalls()), Collections.emptyList(),
                        Collections.emptyList(), elapsedMillis(iterationStarted)));
                break;
            }

            // 第四轮 P2-2:模型输出因 max_tokens 截断时,Observation/答复可能不完整,
            // 不能视为正常完成。无论是否伴随 tool_call 都走异常终止。
            if (turn.getFinishReason() == FinishReason.LENGTH) {
                stopReason = StopReason.LENGTH_EXCEEDED;
                stopMessage = "模型输出因 max_tokens 截断(finish_reason=length)";
                Log.w(TAG, "[Engine] iter " + iteration + " LENGTH_EXCEEDED"
                        + " contentChars=" + safeLength(turn.getAssistantMessage().getContent())
                        + " toolCalls=" + turn.getToolCalls().size());
                trajectory.addIteration(new AgentIteration(iteration,
                        auditRedactAssistant(turn.getAssistantMessage()),
                        snapshots(turn.getToolCalls()), Collections.emptyList(),
                        Collections.emptyList(), elapsedMillis(iterationStarted)));
                break;
            }

            // 第五轮 P2-1:Provider 协议不一致(finish_reason 缺失/未知,或 STOP 但 content 空)
            // 必须终止,不能被错判为 NO_TOOL_CALL → SUCCEEDED。ModelApiClient 解析器侧已把
            // STOP + 空 content 降级为 NONE,这里见到 NONE 就走 PROTOCOL_ERROR。
            if (turn.getFinishReason() == FinishReason.NONE) {
                stopReason = StopReason.PROTOCOL_ERROR;
                stopMessage = "Provider 协议不一致(finish_reason 缺失/未知或 STOP 但 content 为空)";
                Log.w(TAG, "[Engine] iter " + iteration + " PROTOCOL_ERROR"
                        + " contentChars=" + safeLength(turn.getAssistantMessage().getContent())
                        + " toolCalls=" + turn.getToolCalls().size());
                trajectory.addIteration(new AgentIteration(iteration,
                        auditRedactAssistant(turn.getAssistantMessage()),
                        snapshots(turn.getToolCalls()), Collections.emptyList(),
                        Collections.emptyList(), elapsedMillis(iterationStarted)));
                break;
            }

            if (!turn.hasToolCalls()) {
                stopReason = StopReason.NO_TOOL_CALL;
                stopMessage = "模型选择直接答复用户";
                Log.i(TAG, "[Engine] iter " + iteration + " no tool_call -> STOP(模型直接答复)"
                        + " contentChars=" + safeLength(turn.getAssistantMessage().getContent()));
                trajectory.addIteration(new AgentIteration(iteration,
                        auditRedactAssistant(turn.getAssistantMessage()),
                        snapshots(turn.getToolCalls()), Collections.emptyList(),
                        Collections.emptyList(), elapsedMillis(iterationStarted)));
                break;
            }

            if (totalToolCalls + turn.getToolCalls().size() > budget.getMaxToolCalls()) {
                stopReason = StopReason.MAX_TOOL_CALLS;
                stopMessage = "超过累计 Tool Call 上限";
                Log.w(TAG, "[Engine] iter " + iteration + " tool_calls overflow total="
                        + totalToolCalls + " + new=" + turn.getToolCalls().size()
                        + " > budget=" + budget.getMaxToolCalls());
                trajectory.addIteration(new AgentIteration(iteration,
                        auditRedactAssistant(turn.getAssistantMessage()),
                        snapshots(turn.getToolCalls()), Collections.emptyList(),
                        Collections.emptyList(), elapsedMillis(iterationStarted)));
                break;
            }

            Log.i(TAG, "[Engine] iter " + iteration + " processing "
                    + turn.getToolCalls().size() + " tool call(s)");

            List<ToolObservation> observations = new ArrayList<>();
            List<PolicyDecision> decisions = new ArrayList<>();
            for (ToolCall call : turn.getToolCalls()) {
                totalToolCalls++;
                // 第七轮 P2-4:Tool args 含目的地 / 联系人 / memory value,绝不原文进 logcat。
                // 旧实现直接打印 call.getArguments()——destination 等业务字段会泄漏。
                Log.d(TAG, "[Engine]   call #" + totalToolCalls
                        + " cap=" + call.getCapabilityName()
                        + " id=" + call.getStepId()
                        + " args=" + SafeLog.TOOL_ARGS_PLACEHOLDER
                        + " argKeys=" + call.getArguments().keySet());

                if (blockedCapabilities.contains(call.getCapabilityName())) {
                    String reason = "Capability 已被策略禁用:" + call.getCapabilityName();
                    Log.w(TAG, "[Engine]   -> BLOCKED (already disabled) " + call.getCapabilityName());
                    observations.add(ToolObservation.rejected(call, reason, true));
                    decisions.add(PolicyDecision.denyCapability(reason));
                    continue;
                }
                PolicyDecision decision = policyEngine.evaluate(request, call);
                decisions.add(decision);
                if (!decision.isAllowed()) {
                    boolean capabilityBlock =
                            decision.getRejectionType() == PolicyDecision.RejectionType.CAPABILITY;
                    if (capabilityBlock) {
                        blockedCapabilities.add(call.getCapabilityName());
                        Log.w(TAG, "[Engine]   -> POLICY CAPABILITY_REJECTED cap="
                                + call.getCapabilityName() + " reason=" + decision.getReason()
                                + " (now blocked for rest of loop)");
                    } else {
                        Log.i(TAG, "[Engine]   -> POLICY PARAMETER_REJECTED cap="
                                + call.getCapabilityName() + " reason=" + decision.getReason()
                                + " (model may retry with new args)");
                    }
                    observations.add(ToolObservation.rejected(call, decision.getReason(),
                            capabilityBlock));
                    continue;
                }
                Log.d(TAG, "[Engine]   -> policy ALLOW, executing provider");
                CapabilityDefinition definition = policyEngine.findDefinition(call.getCapabilityName());
                ToolResult toolResult = toolExecutor.execute(provider, definition, request, call);
                // 第七轮 P2-4:ToolResult message 是 Provider 给的诊断文本,可能含目的地 / 联系人
                // (如"导航到北京市某小区失败"),用 SafeLog 占位符包裹。status / verified / durationMs
                // 是结构性指标可保留。
                Log.d(TAG, "[Engine]   <- tool result status=" + toolResult.getStatus()
                        + " verified=" + toolResult.isVerified()
                        + " durationMs=" + toolResult.getDurationMillis()
                        + " msg=" + SafeLog.TOOL_RESULT_PLACEHOLDER);
                if (definition.isVerificationRequired() && toolResult.isSuccess()
                        && !toolResult.isVerified()) {
                    Log.w(TAG, "[Engine]   verification required but readback unverified -> VERIFICATION_FAILED");
                    toolResult = new ToolResult(ToolResult.Status.VERIFICATION_FAILED,
                            toolResult.getCapabilityName(), "Capability 未通过强制回读验证",
                            toolResult.getObservedState(), false, toolResult.getDurationMillis());
                }
                observations.add(ToolObservation.of(call, toolResult));
                contextUpdater.onToolCompleted(sessionContext, call, toolResult);
            }

            // 数据边界(P1-2 修复后的正确方向):
            // - Conversation(喂模型):ModelSanitizer 只脱凭据,保留 memory.preference.* 真实 value
            //   等任务语义,否则模型答不出"我喜欢多少度"。
            // - Trajectory(UI/审计):AuditRedactor 字段级脱敏——memory preference value → <memory>。
            // 第七轮 P2-2:每条 tool message 加入前过预算检查(单条截断 + 条数/字符上限)。
            boolean budgetExhausted = false;
            for (ToolObservation observation : observations) {
                ToolObservation sanitized = modelSanitizer.sanitize(observation);
                AgentMessage toolMsg = sanitized.toToolMessage();
                if (!appendMessageWithBudget(conversation, toolMsg)) {
                    Log.w(TAG, "[Engine] iter " + iteration + " budget exhausted on tool message"
                            + " cap=" + observation.getCapabilityName()
                            + " convChars=" + estimateConversationChars(conversation)
                            + " msgCount=" + conversation.size());
                    budgetExhausted = true;
                    break;
                }
            }

            // 内部可信域:累积原始 ToolResult——业务调用方通过 AgentOutcome.getInternalResults() 取真实值,
            // 不被 audit 占位符(<memory>)影响(第三轮 P2-1 修复)。
            for (ToolObservation observation : observations) {
                if (observation.getResult() != null) {
                    internalResults.add(observation.getResult());
                } else {
                    internalResults.add(ToolResult.rejected(
                            observation.getCapabilityName(), observation.getRejectionReason()));
                }
            }

            List<ToolObservation> auditObservations = new ArrayList<>(observations.size());
            for (ToolObservation observation : observations) {
                auditObservations.add(auditRedactor.redact(observation));
            }
            AgentMessage auditAssistant = auditRedactAssistant(turn.getAssistantMessage());
            trajectory.addIteration(new AgentIteration(iteration, auditAssistant,
                    snapshots(turn.getToolCalls()), auditObservations, decisions,
                    elapsedMillis(iterationStarted)));
            Log.d(TAG, "[Engine] iter " + iteration + " observations=" + observations.size()
                    + " conversation=sanitized trajectory=audit-redacted");
            if (budgetExhausted) {
                stopReason = StopReason.BUDGET_EXHAUSTED;
                stopMessage = "加入 tool observation 后超过字符或条数预算";
                break;
            }
        }

        trajectory.finish(stopReason, elapsedMillis(started), totalToolCalls);
        TaskState finalState = computeFinalState(trajectory, stopReason);
        Log.i(TAG, "[Engine] END req=" + request.getRequestId()
                + " finalState=" + finalState
                + " stopReason=" + stopReason
                + " iterations=" + trajectory.getIterations().size()
                + " totalToolCalls=" + totalToolCalls
                + " successToolCalls=" + trajectory.countSuccessfulToolCalls()
                + " durationMs=" + elapsedMillis(started)
                + " msg=\"" + truncate(stopMessage, 120) + "\"");
        // 第七轮 P2-4:session turn 字符串原保留完整用户输入,改用 SafeLog 占位符。
        // SessionContext 仍保留语义(USER / RESULT / STOP),但不存原文。
        sessionContext.addTurn("USER: " + SafeLog.USER_INPUT_PLACEHOLDER
                + " | RESULT: " + finalState + " | STOP: " + stopReason
                + " | " + stopMessage);
        return new AgentOutcome(request.getRequestId(), finalState, stopReason, trajectory,
                elapsedMillis(started), internalResults);
    }

    /**
     * 按 {@link StopReason} 先判终止性质,再算最终状态(P1-3 修复):
     * <ul>
     *   <li>{@code DONE} / {@code NO_TOOL_CALL}:正常结束,按 Tool 结果分 SUCCEEDED / PARTIALLY / FAILED。</li>
     *   <li>{@code CANCELLED} / {@code TIMEOUT}:直接对应 TaskState。</li>
     *   <li>{@code MAX_ITERATIONS} / {@code MAX_TOOL_CALLS} / {@code BUDGET_EXHAUSTED} / {@code POLICY_HALT}:
     *       异常终止——有部分成功结果最多 PARTIALLY_SUCCEEDED,**永远不能 SUCCEEDED**。
     *       否则失控循环(模型一直调成功查询 Tool 直到耗尽预算)会被错判为成功。</li>
     * </ul>
     */
    private static TaskState computeFinalState(Trajectory trajectory, StopReason stopReason) {
        if (stopReason == StopReason.CANCELLED) return TaskState.CANCELLED;
        if (stopReason == StopReason.TIMEOUT) return TaskState.TIMED_OUT;

        int successCount = trajectory.countSuccessfulToolCalls();
        int total = trajectory.getTotalToolCalls();
        boolean normalStop = stopReason == StopReason.DONE || stopReason == StopReason.NO_TOOL_CALL;

        if (normalStop) {
            if (total == 0) return TaskState.SUCCEEDED;
            if (successCount == 0) return TaskState.FAILED;
            return countHardFailures(trajectory) == 0
                    ? TaskState.SUCCEEDED
                    : TaskState.PARTIALLY_SUCCEEDED;
        }
        // 异常终止:即使有部分成功结果也不能算 SUCCEEDED
        return successCount == 0 ? TaskState.FAILED : TaskState.PARTIALLY_SUCCEEDED;
    }

    /**
     * Audit Assistant:content 走文本 redact,ToolCall 的 arguments 走字段级 redactArguments。
     *
     * <p>第四轮 P1-3 修复:旧实现只 redact content,ToolCall 列表原样复制——
     * Trajectory 中 ToolCallSnapshot.arguments 已脱敏,但 AssistantMessage.toolCalls.arguments
     * 是原始值,导致同一 Trajectory 同时存在脱敏与未脱敏两份参数。这里把 ToolCall 也重新构造,
     * 保留 stepId 和 capabilityName(协议层 ID 关联不能丢),但 arguments 全部脱敏。
     *
     * <p>第四轮 P1-2:用 schema-aware 版本 {@link AuditRedactor#redactArguments(String, Map)}
     * 让 navigation.destination / memory.preference.value 等业务敏感字段也走 capability
     * schema 脱敏。
     */
    private AgentMessage auditRedactAssistant(AgentMessage original) {
        if (original == null) return null;
        String redacted = auditRedactor.redact(original.getContent());
        List<ToolCall> redactedCalls;
        if (original.getToolCalls() == null || original.getToolCalls().isEmpty()) {
            redactedCalls = Collections.emptyList();
        } else {
            redactedCalls = new ArrayList<>(original.getToolCalls().size());
            for (ToolCall call : original.getToolCalls()) {
                redactedCalls.add(ToolCall.withId(call.getStepId(), call.getCapabilityName(),
                        auditRedactor.redactArguments(call.getCapabilityName(), call.getArguments())));
            }
        }
        return AgentMessage.assistant(redacted, redactedCalls);
    }

    /**
     * 硬失败:执行失败 / 验证失败 / Capability 被禁用。PARAMETER 拒绝不计入硬失败,
     * 因为模型可在剩余预算内换参数重试——只要后续有成功执行,前一次的 PARAMETER 拒绝
     * 不应把整个任务拖到 PARTIALLY_SUCCEEDED。
     */
    private static int countHardFailures(Trajectory trajectory) {
        int count = 0;
        for (AgentIteration iteration : trajectory.getIterations()) {
            for (ToolObservation observation : iteration.getObservations()) {
                if (observation.getResult() != null) {
                    if (!observation.getResult().isSuccess()) count++;
                } else if (observation.isCapabilityBlocked()) {
                    count++;
                }
            }
        }
        return count;
    }

    private static TaskState cancellationState(AgentRequest request) {
        if (request.isCancelled()) return TaskState.CANCELLED;
        if (request.isTimedOut()) return TaskState.TIMED_OUT;
        return null;
    }

    private static AgentOutcome terminalOutcome(AgentRequest request, TaskState state,
            StopReason reason, String message, long started) {
        Trajectory trajectory = new Trajectory();
        trajectory.finish(reason, elapsedMillis(started), 0);
        return new AgentOutcome(request.getRequestId(), state, reason, trajectory,
                elapsedMillis(started));
    }

    private static String buildSystemPrompt(AgentRequest request) {
        return "你是车机 AI 助理。当前发起者=" + request.getActor()
                + ",区域=" + request.getOccupantZone()
                + ",输入来源=" + request.getInputSource() + "。"
                + "可调用的 capability 见本轮 tools 列表,不允许创造名字。"
                + "每轮可请求调用 0 或多个 capability,工具结果会以 Observation 形式回传。"
                + "任务完成后请直接给出最终答复(不带 tool_call)。"
                + "对 PARAMETER_REJECTED 的 Observation 请修正参数后重试;"
                + "对 CAPABILITY_REJECTED 的 Observation 不要再尝试同一能力。";
    }

    private static int estimateConversationChars(List<AgentMessage> conversation) {
        int total = 0;
        for (AgentMessage message : conversation) total += message.estimateChars();
        return total;
    }

    /**
     * 第七轮 P2-2:统一消息入预算入口。
     *
     * <p>单条消息超过 {@code maxMessageChars} → 截断(保留 role / toolCalls,只截 content)。
     * 用于 system/user 等首条加入 conversation 的消息。
     */
    private AgentMessage enforceMessageBudget(AgentMessage message) {
        if (message == null) return null;
        String content = message.getContent();
        if (content == null || content.length() <= budget.getMaxMessageChars()) return message;
        String truncated = ModelSanitizer.truncateWithSuffix(content, budget.getMaxMessageChars());
        Log.w(TAG, "[Engine] message truncated role=" + message.getRole()
                + " origChars=" + content.length()
                + " truncatedChars=" + truncated.length()
                + " maxMessageChars=" + budget.getMaxMessageChars());
        switch (message.getRole()) {
            case ASSISTANT:
                return AgentMessage.assistant(truncated, message.getToolCalls());
            case TOOL:
                return AgentMessage.tool(message.getToolCallId(), message.getToolName(), truncated);
            case USER:
                return AgentMessage.user(truncated);
            case SYSTEM:
            default:
                return AgentMessage.system(truncated);
        }
    }

    /**
     * 第七轮 P2-2:把消息加入 conversation 前检查所有预算维度。
     *
     * <p>返回 true 表示已加入;返回 false 表示**未加入**且应当走 BUDGET_EXHAUSTED 终止。
     * 检查顺序:
     * <ol>
     *   <li>单条消息字符上限(超 → 先截断再继续后续检查);</li>
     *   <li>消息条数上限 {@code maxMessageCount}(超 → 拒绝加入);</li>
     *   <li>conversation 累计字符上限 {@code totalInputChars}(超 → 拒绝加入)。</li>
     * </ol>
     */
    private boolean appendMessageWithBudget(List<AgentMessage> conversation, AgentMessage message) {
        AgentMessage truncated = enforceMessageBudget(message);
        if (conversation.size() + 1 > budget.getMaxMessageCount()) {
            Log.w(TAG, "[Engine] maxMessageCount exceeded currentCount=" + conversation.size()
                    + " + 1 > budget=" + budget.getMaxMessageCount());
            return false;
        }
        int projected = estimateConversationChars(conversation) + truncated.estimateChars();
        if (projected > budget.getTotalInputChars()) {
            Log.w(TAG, "[Engine] totalInputChars exceeded projected=" + projected
                    + " > budget=" + budget.getTotalInputChars());
            return false;
        }
        conversation.add(truncated);
        return true;
    }

    /**
     * Trajectory 中 ToolCallSnapshot 的 arguments 必须过 AuditRedactor 字段级脱敏——
     * 否则导航 destination / 联系人 / memory save 的 value 会原文进 UI/Log(第三轮 P1-3 修复)。
     */
    private List<AgentIteration.ToolCallSnapshot> snapshots(List<ToolCall> calls) {
        if (calls.isEmpty()) return Collections.emptyList();
        List<AgentIteration.ToolCallSnapshot> list = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            list.add(new AgentIteration.ToolCallSnapshot(call.getStepId(),
                    call.getCapabilityName(),
                    auditRedactor.redactArguments(call.getCapabilityName(), call.getArguments())));
        }
        return list;
    }

    private static int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
