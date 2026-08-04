package com.matrix.agent.data;

import android.util.Log;

import com.matrix.agent.core.agent.AgentBudget;
import com.matrix.agent.core.agent.AgentEngine;
import com.matrix.agent.core.agent.AgentOutcome;
import com.matrix.agent.core.agent.ModelGateway;
import com.matrix.agent.core.agent.StopReason;
import com.matrix.agent.core.agent.SteerMailbox;
import com.matrix.agent.core.agent.TaskScheduler;
import com.matrix.agent.core.agent.TaskState;
import com.matrix.agent.core.agent.Trajectory;
import com.matrix.agent.core.capability.CapabilityRegistry;
import com.matrix.agent.core.identity.Actor;
import com.matrix.agent.core.identity.AgentRequest;
import com.matrix.agent.core.identity.CancellationToken;
import com.matrix.agent.core.identity.IntentClassifier;
import com.matrix.agent.core.identity.KeywordIntentClassifier;
import com.matrix.agent.core.identity.KeywordMemoryIntentDetector;
import com.matrix.agent.core.identity.MemoryIntentDetector;
import com.matrix.agent.core.identity.VehicleStateSource;
import com.matrix.agent.core.identity.VehicleZone;
import com.matrix.agent.core.memory.MemoryStore;
import com.matrix.agent.core.session.SessionManager;
import com.matrix.agent.core.tool.MockCapabilityProvider;
import com.matrix.agent.data.audit.AuditEventRecorder;
import com.matrix.agent.data.audit.AuditRepository;
import com.matrix.agent.data.audit.ClearOutcome;
import com.matrix.agent.data.audit.NoopAuditRepository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class AgentRuntimeRepository {
    private static final String TAG = "MatrixAgent";
    /**
     * V0.4.3 Round 2 / Round 3:主驾和副驾共享同一调度仲裁队列(arbitrationKey),
     * 让 TaskScheduler 的主驾优先抢占协议在 APK 路径真正可触达。
     *
     * <p>Round 3 P1.2 修复:Round 2 直接把 sessionId 共享导致 SessionManager.getOrCreate
     * 与 SteerMailbox 也跟着共享——副驾的 REPROMPT / FORCE_TOOL / DEFER 可能进入主驾 mailbox,
     * 后续 V0.5 上下文持久化时也会发生主副驾上下文串扰。Round 3 拆成两个 key:
     * <ul>
     *   <li>{@code ARBITRATION_KEY = "demo-vehicle"} —— TaskScheduler 用的同车仲裁键</li>
     *   <li>{@code sessionId = "demo-driver" / "demo-passenger"} —— SessionManager / SteerMailbox 用的乘员隔离键</li>
     * </ul>
     */
    private static final String ARBITRATION_KEY = "demo-vehicle";
    /**
     * V0.5.0 第六轮评审 P1:Repository 外层 deadline/interrupt 触发后的收敛窗口。
     *
     * <p>token.cancel() 之后,ToolExecutor 在 worker 线程上会进入 tryAbort 分支:
     * 不可中断 Provider 返回 EXECUTION_UNKNOWN,可中断 Provider 调 abortIfSupported 后
     * 也返回 EXECUTION_UNKNOWN。这给了 Engine 一个机会把真实(含 EXECUTION_UNKNOWN)的 outcome
     * 写入 Future,而不是被 Repository 用空 TIMED_OUT / CANCELLED 覆盖。
     *
     * <p>窗口大小取 500ms——既给 Engine/Tool 时间收敛(Engine 主循环一轮约 50~200ms,
     * ToolExecutor 的 future.cancel(true) + Provider interrupt 响应通常 <100ms),又不让
     * Repository 在 deadline 之后阻塞过久(主驾 UI 等待 Repository 返回,过久会让用户误以为卡死)。
     */
    private static final long GRACE_WINDOW_MILLIS = 500L;
    /**
     * V0.5.1 Stage 6 P1.1 顺手修:Repository future.get 提前量,根治
     * {@code repositoryTimeoutWithDispatchedWriteContainsExecutionUnknown} flaky。
     *
     * <p>根因:Repository {@code future.get(request.remainingMillis())} 与 Engine return
     * (budget.totalDeadline 到期)用同一 deadline——Engine 内部 ToolExecutor budgetMillis
     * = min(capability.timeout, request.remainingMillis) 累积耗到 deadline 时 Engine 退出,
     * 与 Repository future.get(timeout) 几乎同时,谁先取决于 CPU 调度。Engine 早完成 →
     * Repository future.get 立即拿到 outcome 不进 catch → token.cancel 不调 →
     * "token 必须 cancel" 断言失败(Stage 2 测试侧时序调整只能改统计偏向,不消除 race)。
     *
     * <p>产品侧修复:Repository 提前 SAFETY_MARGIN_MILLIS 进 catch TimeoutException,
     * 下发 token.cancel 协作取消信号,再用 GRACE_WINDOW_MILLIS=500ms 收敛 Engine 真实
     * outcome。SAFETY_MARGIN_MILLIS=20 远小于 GRACE_WINDOW_MILLIS,不影响"Repository
     * 在 budget 后 500ms 内返回"的 UI 等待语义。
     *
     * <p>正常路径(budget 充足 + Engine 在 budget 内完成):future.get 在 SAFETY_MARGIN_MILLIS
     * 之前已拿到 outcome,SAFETY_MARGIN 不参与计时。
     */
    private static final long SAFETY_MARGIN_MILLIS = 20L;
    private final AgentEngineFactory engineFactory;
    private final MockCapabilityProvider mockProvider;
    private final SessionManager sessionManager;
    private final MemoryStore memoryStore;
    /**
     * 第七轮 P2-2:AgentRequest.timeoutMillis 必须用 budget.totalDeadlineMillis 作为单一权威来源,
     * 不再硬编码 60_000L。AppContainer 把同一个 AgentBudget 注入 Repository 和 AgentEngine。
     */
    private final AgentBudget budget;
    /**
     * V0.4.3 Stage A:V0.4.1 主驾优先调度器——V0.4.3 才真正接入 APK 链路。
     * Repository.execute 改走 {@link TaskScheduler#submit},把抢占/排队语义生效。
     */
    private final TaskScheduler scheduler;
    /**
     * V0.4.3 Stage B:车辆运动状态源——Repository.execute build 时调
     * {@link VehicleStateSource#snapshot()} 注入到 AgentRequest,
     * 让 PolicyEngine 的 requiredVehicleStates 前置约束真正生效。
     */
    private final VehicleStateSource vehicleStateSource;
    /**
     * V0.4.3 Stage C:per-zone readOnlyHint 派生源——按 actor.zone 派生 TaskScheduler 抢占判定 hint。
     */
    private final CapabilityRegistry registry;
    /**
     * V0.4.3 Round 3 P1.1:模型调用前的查询/写意图分类器——决定 readOnlyHint,
     * 让车控写操作不被主驾优先调度半路强制中断。V0.5.0 可替换为 LLM-based classifier。
     *
     * <p>V0.5.2 评审 P1-5:改为 volatile —— AppContainer.setModelGateway 切换 LLM 网关时,
     * 通过 {@link #setIntentClassifier(IntentClassifier)} 替换为 FallbackIntentClassifier
     * (新 LlmIntentClassifier + Keyword),让运行时分类器与 Provider 配置同步演进。
     */
    private volatile IntentClassifier intentClassifier;
    /**
     * V0.5.4 评审 P1-2:显式语义记忆意图检测器——决定 {@code AgentRequest.memorySaveAllowed},
     * 让 {@code memory.semantic.save} capability handler 在 false 时直接 POLICY_REJECTED。
     *
     * <p>默认 {@link MemoryIntentDetector#NOOP}(始终返回 false)——保守起点,AppContainer
     * 装配 {@link KeywordMemoryIntentDetector#INSTANCE} 后才放行命中关键词的请求。与
     * {@link #intentClassifier} 同 volatile + setter 模式,后续版本可替换为 LLM-based detector。
     */
    private volatile MemoryIntentDetector memoryIntentDetector = MemoryIntentDetector.NOOP;
    /**
     * V0.5.0 第三轮评审 P1:Repository 层兜底 Audit——保证所有终态(含 Repository
     * catch 分支构造的 TIMED_OUT / CANCELLED + Scheduler 内部生成的 TIMED_OUT / CANCELLED)
     * 都进 Audit,与 AgentEngine 5 个出口点审计短期并存(requestId 作幂等键,REPLACE 语义)。
     */
    private final AuditRepository auditRepository;
    private volatile AgentEngine agentEngine;
    private volatile String activeModelGateway;
    /**
     * 第七轮 P1.3:当前在途任务 token——{@link #clearUserData()} 时统一 cancel(),
     * 让 Engine 进入协作取消 + grace window 收敛(第六轮 P1 已就位),
     * 避免写操作清完后才落盘,把刚清的数据写回。
     */
    private final java.util.Set<com.matrix.agent.core.identity.CancellationToken> activeTokens =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * 第七轮 P1.3:Repository 必须能 clearAll SteerMailbox——AppContainer 显式注入。
     * 为兼容现有测试(不依赖 mailbox 清理),setter 为可选;clearUserData 调用时 null 仅记 warn。
     */
    private volatile SteerMailbox steerMailbox;
    /**
     * V0.5.2 评审 P1-3:增量 audit_event recorder——可选注入(默认 NOOP)。
     *
     * <p>clearUserDataDetailed 在 epoch 自增后调 {@link AuditEventRecorder#advanceEpoch(long)}
     * 让在队列里、还未落库的旧 epoch 事件被 gate drop;在清理 audit 表前调
     * {@link AuditEventRecorder#dropByUserZone(String, String, long)} 把 pending 队列里
     * 指定 user+zone 的事件 drain 掉。两步配合 epoch gate,杜绝"clearUserData 后异步
     * AuditEvent 又把旧事件重新 insert 回去"。
     */
    private volatile AuditEventRecorder auditEventRecorder = AuditEventRecorder.NOOP;
    /**
     * 第八轮 P1.3 引入 epoch gate / 第九轮 P1.1 修正:MemoryStore 是 epoch 单一权威。
     *
     * <p>删除本类的 epochCounter——评审指出双来源在进程重启后失步 (Repository 重建回到 0,
     * SharedPreferencesMemoryStore 从 prefs 加载保留 N),导致 clearData 重启再 clearData 后
     * 新写入被错误拒绝。改为 execute 入口直接读 {@link MemoryStore#currentEpoch()},
     * clearUserData 只调 {@link MemoryStore#bumpEpoch()},二者始终同步。
     */

    public AgentRuntimeRepository(AgentEngineFactory engineFactory,
            MockCapabilityProvider mockProvider, SessionManager sessionManager, MemoryStore memoryStore,
            ModelGateway initialGateway, String initialDisplayName, TaskScheduler scheduler,
            VehicleStateSource vehicleStateSource, CapabilityRegistry registry) {
        this(engineFactory, mockProvider, sessionManager, memoryStore, initialGateway,
                initialDisplayName, new AgentBudget(), scheduler, vehicleStateSource, registry,
                KeywordIntentClassifier.INSTANCE, NoopAuditRepository.INSTANCE);
    }

    public AgentRuntimeRepository(AgentEngineFactory engineFactory,
            MockCapabilityProvider mockProvider, SessionManager sessionManager, MemoryStore memoryStore,
            ModelGateway initialGateway, String initialDisplayName, AgentBudget budget,
            TaskScheduler scheduler, VehicleStateSource vehicleStateSource, CapabilityRegistry registry) {
        this(engineFactory, mockProvider, sessionManager, memoryStore, initialGateway,
                initialDisplayName, budget, scheduler, vehicleStateSource, registry,
                KeywordIntentClassifier.INSTANCE, NoopAuditRepository.INSTANCE);
    }

    /** V0.4.3 Round 3 P1.1:注入自定义 IntentClassifier(测试 / 未来 LLM-based 替换用)。 */
    public AgentRuntimeRepository(AgentEngineFactory engineFactory,
            MockCapabilityProvider mockProvider, SessionManager sessionManager, MemoryStore memoryStore,
            ModelGateway initialGateway, String initialDisplayName, AgentBudget budget,
            TaskScheduler scheduler, VehicleStateSource vehicleStateSource, CapabilityRegistry registry,
            IntentClassifier intentClassifier) {
        this(engineFactory, mockProvider, sessionManager, memoryStore, initialGateway,
                initialDisplayName, budget, scheduler, vehicleStateSource, registry,
                intentClassifier, NoopAuditRepository.INSTANCE);
    }

    /**
     * V0.5.0 第三轮评审 P1:Repository 层兜底 Audit 装配入口。
     *
     * <p>AppContainer 显式传入 RoomAuditRepository(与 AgentEngine 共享同一实例);
     * Repository.execute 在所有 return outcome 路径统一 persist,与 Engine 5 出口点
     * 短期并存(requestId 作幂等键,TrajectoryDao `@Insert(REPLACE)` 让 Repository 后写覆盖)。
     *
     * <p>旧 3 个构造器链默认 NoopAuditRepository.INSTANCE,V0.4.3 现有测试 0 回归。
     */
    public AgentRuntimeRepository(AgentEngineFactory engineFactory,
            MockCapabilityProvider mockProvider, SessionManager sessionManager, MemoryStore memoryStore,
            ModelGateway initialGateway, String initialDisplayName, AgentBudget budget,
            TaskScheduler scheduler, VehicleStateSource vehicleStateSource, CapabilityRegistry registry,
            IntentClassifier intentClassifier, AuditRepository auditRepository) {
        if (scheduler == null) throw new IllegalArgumentException("scheduler 不能为空");
        if (vehicleStateSource == null) throw new IllegalArgumentException("vehicleStateSource 不能为空");
        if (registry == null) throw new IllegalArgumentException("registry 不能为空");
        if (intentClassifier == null) throw new IllegalArgumentException("intentClassifier 不能为空");
        if (auditRepository == null) throw new IllegalArgumentException("auditRepository 不能为空");
        this.engineFactory = engineFactory;
        this.mockProvider = mockProvider;
        this.sessionManager = sessionManager;
        this.memoryStore = memoryStore;
        this.budget = budget == null ? new AgentBudget() : budget;
        this.scheduler = scheduler;
        this.vehicleStateSource = vehicleStateSource;
        this.registry = registry;
        this.intentClassifier = intentClassifier;
        this.auditRepository = auditRepository;
        setModelGateway(initialGateway, initialDisplayName);
    }

    public AgentOutcome execute(String command, Actor actor, CancellationToken token) {
        // V0.4.3 Round 3 P1.2:sessionId 按乘员隔离(SessionManager / SteerMailbox 用),
        // arbitrationKey 同车共享(TaskScheduler 抢占仲裁用)。两个 key 解耦后,
        // 副驾的 REPROMPT/FORCE_TOOL/DEFER 不会进入主驾 mailbox,对话上下文也不串扰。
        String sessionId = actor == Actor.DRIVER ? "demo-driver" : "demo-passenger";
        String arbitrationKey = ARBITRATION_KEY;
        // V0.4.3 Round 3 P1.1:IntentClassifier 在 LLM 调用前给出保守的查询/写意图分类,
        // 决定 readOnlyHint。车控写操作(打开空调/设座椅加热/开始导航)→ false → 不可抢占;
        // 查询类(查电量/查胎压)→ true → 可被主驾查询抢占;未知 → false(保守)。
        boolean intentReadOnly = intentClassifier.isReadOnly(command);
        // V0.5.4 评审 P1-2:用户硬约束——"长期记忆仅由用户决定"。Repository.execute 入口
        // (模型调用前)用 MemoryIntentDetector 判定用户原文是否含"记住/保存/以后默认/不要忘记"
        // 等显式动词。false 时 memory.semantic.save handler 直接 POLICY_REJECTED,
        // 杜绝模型在"查天气"等纯查询请求里自由调 save 污染长期记忆(prompt 约定 + 硬 gate 双重防线)。
        boolean memorySaveAllowed = memoryIntentDetector.isExplicitMemorySave(command);
        // 第七轮 P2-4:command 是完整用户输入,绝不原文进 logcat。仅保留字符数 + 元数据。
        Log.i(TAG, "[Repo] execute command=" + com.matrix.agent.core.agent.SafeLog.USER_INPUT_PLACEHOLDER
                + " commandChars=" + (command == null ? 0 : command.length())
                + " actor=" + actor + " session=" + sessionId
                + " arbitration=" + arbitrationKey
                + " intentReadOnly=" + intentReadOnly
                + " memSave=" + memorySaveAllowed
                + " budgetDeadlineMs=" + budget.getTotalDeadlineMillis());
        VehicleZone zone = actor == Actor.DRIVER ? VehicleZone.DRIVER : VehicleZone.PASSENGER;
        // 第八轮 P1.3 / 第九轮 P1.1:MemoryStore 是 epoch 单一权威——execute 入口直接读本值,
        // 不再维护本类独立 epochCounter (评审指出双来源失步:Repository 重启回 0 / SP 保留 N
        // 会让 clearData 重启再 clearData 后新写入被错误拒绝)。
        long capturedEpoch = memoryStore.currentEpoch();
        AgentRequest request = AgentRequest.builder(command, actor)
                .sessionId(sessionId)
                .arbitrationKey(arbitrationKey)
                .occupantZone(zone)
                .timeoutMillis(budget.getTotalDeadlineMillis())
                .cancellationToken(token)
                // V0.4.3 Stage B:注入车辆运动状态,让 PolicyEngine 的 requiredVehicleStates
                // 前置约束(PARKED_ONLY 等)真正生效。V0.4.2 默认 satisfyAllPredicates 让所有约束放行,
                // 这里替换为实时快照——demo 切 gear=D 即可触发 PARKED_ONLY 拒绝。
                .vehicleState(vehicleStateSource.snapshot())
                // V0.4.3 Round 3 P1.1:readOnlyHint 来自 IntentClassifier——车控写不被抢占
                .readOnlyHint(intentReadOnly)
                // 第八轮 P1.3:注入 epoch,Provider/MemoryStore 校验
                .epoch(capturedEpoch)
                // V0.5.4 评审 P1-2:注入显式记忆许可——memory.semantic.save handler 在 false 时
                // 直接 POLICY_REJECTED。默认 false(保守),命中关键词(记住/保存/以后默认/...)才 true。
                .memorySaveAllowed(memorySaveAllowed)
                .build();
        AgentOutcome outcome;
        long started = System.nanoTime();
        Future<AgentOutcome> future = null;
        // 第七轮 P1.3:注册到 activeTokens——clearUserData 时可统一 cancel(),
        // 配合第六轮 P1 收敛窗口,保证在途写操作不会在 clear 之后才完成写回。
        activeTokens.add(token);
        try {
            // V0.5.1 Stage 6 P1.1:waitMillis 减去 SAFETY_MARGIN_MILLIS,让 Repository 必先于
            // Engine budget 到期超时——根治 Stage 2 flaky(见 SAFETY_MARGIN_MILLIS 注释)。
            long remaining = request.remainingMillis();
            long waitMillis = Math.max(1L, remaining - SAFETY_MARGIN_MILLIS);
            future = scheduler.submit(request, agentEngine::execute);
            outcome = future.get(waitMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            // V0.5.0 第六轮评审 P1:不立刻 future.cancel(true) 覆盖结果。
            //
            // <p>读操作(intentReadOnly=true):无副作用,直接 V0.4.x fallback TIMED_OUT
            // (跳过 grace window,避免 token.cancel 触发 Engine abortHook → CANCELLED
            // 掩盖 Repository 的 TIMED_OUT 语义)。
            //
            // <p>写操作(intentReadOnly=false):命令可能已下发——先 token.cancel() 协作取消
            // (触发 ToolExecutor.tryAbort → EXECUTION_UNKNOWN),再用 GRACE_WINDOW_MILLIS 收敛
            // Engine/Tool 的真实 outcome(可能含 EXECUTION_UNKNOWN observation)。窗口后仍无果
            // → fallback EXECUTION_UNKNOWN(命令已下发,绝不能宣称 TIMED_OUT)。
            Log.w(TAG, "[Repo] scheduler future timeout req=" + request.getRequestId()
                    + " msg=" + timeout.getMessage()
                    + " — cooperative cancel (intentReadOnly=" + intentReadOnly + ")");
            token.cancel();
            if (intentReadOnly) {
                if (future != null) future.cancel(true);
                outcome = terminalOutcome(request, TaskState.TIMED_OUT, StopReason.TIMEOUT,
                        "scheduler future timeout", started);
            } else {
                outcome = awaitWriteDispatchConvergence(future, request, started,
                        "scheduler future timeout");
            }
        } catch (ExecutionException executionError) {
            // 第八轮 P2.3:ExecutionException 不再直接抛 RuntimeException——
            // (1) cause.getMessage() 可能含 PII / 敏感调试信息,直接进 logcat 会泄露;
            // (2) 直接抛会让 Repository 失去 outcome + audit 落库机会,Engine 内部异常
            //     无法被结构化追踪,UI 也只能拿到崩溃而不是终态。
            // 改为构造 PROTOCOL_ERROR Outcome + Audit 持久化 + log 仅记 cause 类名。
            //
            // 触发条件:Engine.execute 抛未捕获 RuntimeException。当前 ModelCallExecutor
            // (L114-120) + ToolExecutor (L97-104) 已包装各组件异常为 POLICY_HALT /
            // EXECUTION_FAILED terminal,Engine 主路径只 catch InterruptedException;
            // 本 catch 块主要兜底未来引入的新代码路径(新组件 / 新 hook)抛未预期异常,
            // 以及 auditRepository/SessionManager.appendTurn 等 Engine 末端未 catch 路径故障。
            Throwable cause = executionError.getCause() == null ? executionError : executionError.getCause();
            Log.e(TAG, "[Repo] scheduler execution failed req=" + request.getRequestId()
                    + " cause=" + cause.getClass().getSimpleName()
                    + " — fallback PROTOCOL_ERROR outcome (cause message suppressed for PII safety)");
            token.cancel();
            if (future != null) future.cancel(true);
            outcome = terminalOutcome(request, TaskState.FAILED, StopReason.PROTOCOL_ERROR,
                    "scheduler execution failed: " + cause.getClass().getSimpleName(), started);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            // V0.5.0 第六轮评审 P1:外部线程已被中断,不能用 future.get(graceMillis) 阻塞
            // (InterruptedException 立即重抛)。直接 fallback:写操作 EXECUTION_UNKNOWN(命令可能
            // 已被 Engine 分发到 Provider),读操作 CANCELLED(无副作用,可安全宣称未发生)。
            Log.w(TAG, "[Repo] scheduler future interrupted req=" + request.getRequestId()
                    + " — cooperative cancel + immediate writeAware fallback (intentReadOnly="
                    + intentReadOnly + ")");
            token.cancel();
            if (future != null) future.cancel(true);
            TaskState state = intentReadOnly ? TaskState.CANCELLED : TaskState.EXECUTION_UNKNOWN;
            StopReason reason = intentReadOnly ? StopReason.CANCELLED : StopReason.EXECUTION_UNKNOWN;
            outcome = terminalOutcome(request, state, reason, "scheduler future interrupted", started);
        } finally {
            // 第七轮 P1.3:无论成功 / 失败 / cancel,token 都退出 active 集合。
            // clearUserData 的等待循环靠 activeTokens.isEmpty() 收敛。
            activeTokens.remove(token);
        }
        Log.i(TAG, "[Repo] <- outcome state=" + outcome.getFinalState()
                + " stop=" + outcome.getStopReason()
                + " durationMs=" + outcome.getDurationMillis());
        // V0.5.0 第三轮评审 P1:Repository 层兜底 Audit——保证所有终态(含 catch 分支构造的
        // TIMED_OUT / CANCELLED + Scheduler 内部生成的 TIMED_OUT / CANCELLED + Engine 正常返回)
        // 都进 Audit。requestId 作幂等键,TrajectoryDao `@Insert(REPLACE)` 让 Repository 后写
        // 覆盖 Engine 内部已写的版本(Engine 5 个出口点 audit 短期并存,最终以 Repository 版本为准)。
        // fail-open:auditRepository.persist 内部 try/catch,失败仅 log,不抛(评审 P1.3 已对齐)。
        auditRepository.persist(outcome, request);
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

    /**
     * 第七轮 P1.3:Repository 必须能 clearAll SteerMailbox——AppContainer 显式注入。
     *
     * <p>为兼容现有测试(289 个 V0.4.3 测试不依赖 mailbox 清理),setter 为可选;
     * {@link #clearUserData()} 调用时 {@code mailbox == null} 仅记 warn 不 NPE。
     */
    public void setSteerMailbox(SteerMailbox mailbox) {
        this.steerMailbox = mailbox;
    }

    /**
     * V0.5.2 评审 P1-5:替换意图分类器——AppContainer 在 setModelGateway 切换 LLM 网关时,
     * 用新 ModelConfig 重新构造 FallbackIntentClassifier(LlmIntentClassifier, Keyword) 并注入。
     *
     * <p>volatile 替换,{@link #execute} 路径下次读取即生效。null 输入退化为
     * {@link KeywordIntentClassifier#INSTANCE}(防御)。
     */
    public void setIntentClassifier(IntentClassifier classifier) {
        this.intentClassifier = classifier == null ? KeywordIntentClassifier.INSTANCE : classifier;
    }

    /**
     * V0.5.4 评审 P1-2:注入显式语义记忆意图检测器——AppContainer 装配 KeywordMemoryIntentDetector。
     *
     * <p>volatile 替换,{@link #execute} 路径下次读取即生效。null 输入退化为
     * {@link MemoryIntentDetector#NOOP}(始终返回 false,保守拒绝所有 semantic.save)。
     *
     * <p>语义对偶:{@link #setIntentClassifier} 输出 readOnly 用于 TaskScheduler 抢占;
     * 本 setter 输出 explicitMemorySave 用于 capability handler gate。两接口关注点不同,不混。
     */
    public void setMemoryIntentDetector(MemoryIntentDetector detector) {
        this.memoryIntentDetector = detector == null ? MemoryIntentDetector.NOOP : detector;
    }

    /**
     * V0.5.2 评审 P1-3:注入增量 audit_event recorder(可选,默认 NOOP)。
     *
     * <p>AppContainer 装配 auditEventRecorder 后调用此 setter。clearUserDataDetailed 会:
     * <ol>
     *   <li>memoryStore.clearUserDataAndBump 拿到 newEpoch 后调 advanceEpoch(newEpoch),
     *       让 recorder 内 stale gate 拒绝旧 epoch 事件入队;</li>
     *   <li>清空 audit 表前调 dropByUserZone(userId, zone, newEpoch),
     *       drain pending 队列中匹配 user+zone 的事件,同时把它们加进 stale gate。</li>
     * </ol>
     */
    public void setAuditEventRecorder(AuditEventRecorder recorder) {
        this.auditEventRecorder = recorder == null ? AuditEventRecorder.NOOP : recorder;
    }

    /**
     * 第七轮 P1.3:重写为"取消—等待—清空"序列,保证原子性。
     *
     * <p>评审场景:旧实现仅清 MemoryStore + SessionManager,与在途写操作非原子——
     * <ul>
     *   <li>{@code clearUserData()} 之前 dispatch 的 preference.save / climate.set
     *       可能在 clear 之后完成,把刚清的数据写回 MemoryStore;</li>
     *   <li>{@link SteerMailbox} 没清,旧 FORCE_TOOL / REPROMPT / DEFER 留在队列里,
     *       被下一次同 sessionId 任务消费到陈旧指令。</li>
     * </ul>
     *
     * <p>新实现:
     * <ol>
     *   <li><b>取消</b>:统一 cancel 所有在途 token,触发 Engine abort hooks
     *       + 让 write dispatch 进入第六轮 P1 的 GRACE_WINDOW_MILLIS=500ms 收敛窗口;</li>
     *   <li><b>等待</b>:最多 1000ms(2× grace window) 等 activeTokens 收敛——
     *       让在途写操作要么在 clear 之前完成,要么被 token.cancel 后的
     *       EXECUTION_UNKNOWN 路径放弃 observation 写回;</li>
     *   <li><b>清空</b>:SteerMailbox + MemoryStore(driver + passenger,原子 clearUserDataAndBump)
     *       + SessionManager。</li>
     * </ol>
     *
     * <p>第九轮 P1.1 已落地 MemoryStore 单一权威 epoch(见 §16),第十轮 P1 已落地"epoch 自增 + clear×2"
     * 原子操作(MemoryStore.clearUserDataAndBump,见 §17)——杜绝 check-then-act race。
     * Steer / Tool 回写 epoch gate(让旧 epoch 的 Steer / 异步结果被拒绝)仍推迟到 V0.5.1,
     * 见 MatrixAgent-V0.5.0-Code-Review.md §16 / §17 跟进点。
     */
    public void clearUserData() {
        clearUserDataDetailed();
    }

    /**
     * V0.5.1 Stage 6 P1.2:结构化返回版 clearUserData——透传 driver/passenger 两 zone 的
     * {@link ClearOutcome},让 ViewModel 据此选择"已清空" vs "上下文已清,审计删除失败"文案。
     *
     * <p>主流程(MemoryStore / SteerMailbox / Session)失败仍抛 RuntimeException,
     * ViewModel 走"清空失败 [...]"路径——失败时不构造 ClearUserDataOutcome。
     *
     * <p>审计维度失败封装进 {@link ClearUserDataOutcome},Repository 不抛——主流程已成功
     * 清空偏好 + 会话,不让 audit 失败回滚主流程;但 UI 必须显式提示"审计删除失败,请重试",
     * 避免"audit 仍残留"伪装成"完整成功"。
     */
    public ClearUserDataOutcome clearUserDataDetailed() {
        Log.i(TAG, "[Repo] clearUserData (cancel in-flight -> atomic epoch++ + clear -> wait -> clear mailbox/session)"
                + " activeTokens=" + activeTokens.size()
                + " mailbox=" + (steerMailbox == null ? "off" : "on")
                + " currentEpoch=" + memoryStore.currentEpoch());
        // 1. 取消所有在途任务——cancel() 幂等,token 内部已吞 abort hook 异常
        for (com.matrix.agent.core.identity.CancellationToken token : activeTokens) {
            try {
                token.cancel();
            } catch (Throwable ignored) {
                // 兜底:cancel() 内部已 try/catch,这里再兜一层防止异常 iteration
            }
        }
        // 2. 第十轮 P1:原子"epoch 自增 + 清空 driver/passenger"——单次 MemoryStore.clearUserDataAndBump
        //    内部用同一把锁(InMemoryMemoryStore synchronized / SharedPreferencesMemoryStore lock +
        //    commit 同步落盘),杜绝 check-then-act race(旧 putPreferenceChecked 通过 epoch 校验后,
        //    bumpEpoch + clear 异步执行,旧 putPreference 仍在 SP 队列 → 偏好"复活")。
        //    必须在 in-flight cancel 之后、wait 之前自增,让 wait 期间任何 Provider 完成时 MemoryStore
        //    已切到新 epoch,陈旧写入被 putPreferenceChecked 拒绝。
        long newEpoch;
        try {
            newEpoch = memoryStore.clearUserDataAndBump("demo-driver", "demo-passenger");
        } catch (RuntimeException commitError) {
            // SharedPreferencesMemoryStore.commit 失败时抛 IllegalStateException——epoch 已回滚,
            // memory 数据未清。继续清理 SteerMailbox / Session 没有意义(用户数据可能仍可被回灌),
            // 直接反馈给上层(不吞异常)。
            Log.e(TAG, "[Repo] clearUserData: memoryStore.clearUserDataAndBump failed"
                    + " cause=" + commitError.getClass().getSimpleName()
                    + " — abort clearUserData, feedback to caller");
            throw commitError;
        }
        Log.i(TAG, "[Repo] clearUserData epoch -> " + newEpoch
                + " (MemoryStore.putPreferenceChecked now rejects old epoch writes atomically)");
        // V0.5.2 评审 P1-3:同步推进 audit_event recorder 的 stale epoch gate——
        // 让已经在 pending 队列里的旧 epoch 事件在 scheduler flush 前 / 后被 drop,
        // 避免 clearByUserZone 后异步 flush 把旧事件写回 audit_event 表。
        // 必须在 clearAuditSafe 之前调用,与 steerMailbox.advanceEpoch 同模式。
        try {
            auditEventRecorder.advanceEpoch(newEpoch);
        } catch (Throwable t) {
            Log.w(TAG, "[Repo] clearUserData: auditEventRecorder.advanceEpoch threw"
                    + " cause=" + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        // V0.5.2 Stage 5:同步推进 SteerMailbox epoch——epoch gate 让 stamped.epoch < newEpoch
        // 的 Steer 在 drain 时 drop + audit(STEER_DROPPED_STALE)。必须在 SteerMailbox.clearAll
        // 之前调用(虽然 clearAll 全清,但 advanceEpoch 是为 clearUserData 之后新 offer 的
        // session 准备的——确保下次任务 drain 时仍能识别本批 stale)。
        if (steerMailbox != null) {
            steerMailbox.advanceEpoch(newEpoch);
        }
        // 3. 等 activeTokens 收敛(第六轮 P1 GRACE_WINDOW_MILLIS=500ms 的 2 倍冗余)
        long deadline = System.currentTimeMillis() + 1_000L;
        while (!activeTokens.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "[Repo] clearUserData wait interrupted, proceeding to clear");
                break;
            }
        }
        if (!activeTokens.isEmpty()) {
            Log.w(TAG, "[Repo] clearUserData: " + activeTokens.size()
                    + " token(s) still active after 1000ms grace — their writes will be"
                    + " rejected by epoch gate");
        }
        // 4. 清空 SteerMailbox(陈旧 FORCE_TOOL / REPROMPT / DEFER)+ Session(Memory 已在步骤 2 原子清)
        if (steerMailbox != null) {
            steerMailbox.clearAll();
        } else {
            Log.w(TAG, "[Repo] clearUserData: steerMailbox not injected, skip mailbox clear");
        }
        sessionManager.clear();
        // 5. V0.5.1 Stage 6 + P1.2:清空 Room Audit 落库数据——trajectory / session_history /
        //    memory_record 3 表跨表 transaction 删除。失败封装进 ClearOutcome,不抛(主流程
        //    已清,audit 失败由 ViewModel 文案提示用户重试)。
        // V0.5.2 评审 P1-3:先 drain pending 队列 + 加 stale gate,再 clearByUserZone——
        // 这样即使 scheduler tick 在 clearByUserZone 后触发,旧 epoch 事件已被 gate drop,
        // 不会被重新 insert 回 audit_event 表。失败仅 log,不阻塞 clearByUserZone。
        try {
            auditEventRecorder.dropByUserZone("demo-driver", "DRIVER", newEpoch);
            auditEventRecorder.dropByUserZone("demo-passenger", "PASSENGER", newEpoch);
        } catch (Throwable t) {
            Log.w(TAG, "[Repo] clearUserData: auditEventRecorder.dropByUserZone threw"
                    + " cause=" + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        ClearOutcome driverAudit = clearAuditSafe("demo-driver", "DRIVER");
        ClearOutcome passengerAudit = clearAuditSafe("demo-passenger", "PASSENGER");
        return new ClearUserDataOutcome(driverAudit, passengerAudit);
    }

    /**
     * V0.5.1 Stage 6 P1.2:auditRepository.clearByUserZone 已 try/catch 不抛,
     * 这里再加一层 Throwable 防御 fake / 异常实现抛错。
     */
    private ClearOutcome clearAuditSafe(String userId, String zone) {
        try {
            ClearOutcome outcome = auditRepository.clearByUserZone(userId, zone);
            return outcome == null ? ClearOutcome.failure("clearByUserZone returned null") : outcome;
        } catch (Throwable t) {
            Log.w(TAG, "[Repo] clearUserData: auditRepository.clearByUserZone threw"
                    + " user=" + userId + " zone=" + zone
                    + " cause=" + t.getClass().getSimpleName() + ": " + t.getMessage());
            return ClearOutcome.failure(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** V0.4.3 Stage A:scheduler shutdown 钩子,MainActivity.onDestroy 调。 */
    public void shutdown() {
        Log.i(TAG, "[Repo] shutdown scheduler");
        scheduler.shutdown();
    }

    private static AgentOutcome terminalOutcome(AgentRequest request, TaskState state,
            StopReason reason, String message, long startedNanos) {
        Trajectory trajectory = new Trajectory();
        long elapsed = (System.nanoTime() - startedNanos) / 1_000_000L;
        trajectory.finish(reason, elapsed, 0);
        return new AgentOutcome(request.getRequestId(), state, reason, trajectory, elapsed);
    }

    /**
     * V0.5.0 第六轮评审 P1:Repository 外层 deadline 触发后,<b>仅对写操作</b>启用的收敛窗口。
     *
     * <p>调用方已先 token.cancel()——这是协作取消信号,会让 ToolExecutor 进入 tryAbort 分支
     * (写操作返回 EXECUTION_UNKNOWN,可中断 Provider 调 abortIfSupported)。本方法用
     * {@link #GRACE_WINDOW_MILLIS} 再等一次 future,给 Engine 一个机会把含 Tool Observation 的
     * 真实 outcome 写入 Future,而不是被 Repository 用空 trajectory 覆盖。
     *
     * <p>收敛成功 → 用 Engine 的真实 outcome(可能含 ToolExecutor 的 EXECUTION_UNKNOWN observation)。
     * 窗口后仍无果 → 强制 future.cancel(true) + fallback {@link TaskState#EXECUTION_UNKNOWN} /
     * {@link StopReason#EXECUTION_UNKNOWN},与 ToolExecutor 的 resolveTerminal(写分支)语义对齐
     * ——命令可能已下发,绝不能宣称 TIMED_OUT/CANCELLED。
     *
     * <p>读操作走 V0.4.x 路径(直接 TIMED_OUT fallback),不进入本方法——读无副作用,
     * 不需要 EXECUTION_UNKNOWN 保护,也避免 token.cancel 触发 Engine abortHook CANCELLED
     * 掩盖 TIMED_OUT 语义。
     */
    private static AgentOutcome awaitWriteDispatchConvergence(
            Future<AgentOutcome> future, AgentRequest request, long startedNanos, String message) {
        if (future != null) {
            try {
                AgentOutcome converged = future.get(GRACE_WINDOW_MILLIS, TimeUnit.MILLISECONDS);
                Log.i(TAG, "[Repo] write-dispatch grace convergence resolved req=" + request.getRequestId()
                        + " state=" + converged.getFinalState()
                        + " stop=" + converged.getStopReason()
                        + " iterations=" + converged.getTrajectory().getIterations().size());
                return converged;
            } catch (TimeoutException secondTimeout) {
                Log.w(TAG, "[Repo] write-dispatch grace window expired req=" + request.getRequestId()
                        + " — force-cancel + EXECUTION_UNKNOWN fallback");
                future.cancel(true);
            } catch (CancellationException cancelled) {
                Log.w(TAG, "[Repo] write-dispatch grace convergence cancelled req=" + request.getRequestId()
                        + " — EXECUTION_UNKNOWN fallback");
            } catch (ExecutionException executionError) {
                Throwable cause = executionError.getCause() == null
                        ? executionError : executionError.getCause();
                Log.e(TAG, "[Repo] write-dispatch grace convergence failed req=" + request.getRequestId()
                        + " cause=" + cause.getClass().getSimpleName()
                        + " — EXECUTION_UNKNOWN fallback");
                future.cancel(true);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "[Repo] write-dispatch grace window interrupted req=" + request.getRequestId()
                        + " — EXECUTION_UNKNOWN fallback");
                future.cancel(true);
            }
        }
        return terminalOutcome(request, TaskState.EXECUTION_UNKNOWN, StopReason.EXECUTION_UNKNOWN,
                message, startedNanos);
    }

    @FunctionalInterface
    public interface AgentEngineFactory {
        AgentEngine create(ModelGateway gateway);
    }
}
