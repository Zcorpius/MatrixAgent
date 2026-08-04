package com.matrix.agent.app;

import android.content.Context;
import android.util.Log;

import com.matrix.agent.core.agent.AgentBudget;
import com.matrix.agent.core.agent.AgentEngine;
import com.matrix.agent.core.agent.AuditDigest;
import com.matrix.agent.core.agent.ConversationCompressor;
import com.matrix.agent.core.agent.DemoModelGateway;
import com.matrix.agent.core.agent.DefaultContextUpdater;
import com.matrix.agent.core.agent.LlmSummaryProvider;
import com.matrix.agent.core.agent.ModelCallExecutor;
import com.matrix.agent.core.agent.Sha1AuditDigest;
import com.matrix.agent.core.agent.Steer;
import com.matrix.agent.core.agent.SteerMailbox;
import com.matrix.agent.core.agent.StaleSteerHandler;
import com.matrix.agent.core.agent.TaskScheduler;
import com.matrix.agent.core.agent.UnavailableAuditDigest;
import com.matrix.agent.core.token.CharFallbackTokenizer;
import com.matrix.agent.core.token.JtokkitTokenizer;
import com.matrix.agent.core.token.Tokenizer;
import com.matrix.agent.core.capability.CapabilityRegistry;
import com.matrix.agent.core.identity.DefaultVehicleStateSource;
import com.matrix.agent.core.identity.FallbackIntentClassifier;
import com.matrix.agent.core.identity.IntentClassifier;
import com.matrix.agent.core.identity.KeywordIntentClassifier;
import com.matrix.agent.core.identity.KeywordMemoryIntentDetector;
import com.matrix.agent.core.identity.LlmIntentClassifier;
import com.matrix.agent.core.memory.EmptyEpisodicMemorySource;
import com.matrix.agent.core.memory.EmptySemanticMemorySource;
import com.matrix.agent.core.memory.EpisodicMemorySource;
import com.matrix.agent.core.memory.LegacyPreferenceMemorySource;
import com.matrix.agent.core.memory.MemoryRecaller;
import com.matrix.agent.core.memory.MemoryRouter;
import com.matrix.agent.core.memory.SemanticMemorySource;
import com.matrix.agent.core.memory.SessionContextWorkingMemory;
import com.matrix.agent.core.policy.PolicyEngine;
import com.matrix.agent.core.prompt.DefaultPromptBuilder;
import com.matrix.agent.core.prompt.PromptBuilder;
import com.matrix.agent.core.session.SessionLockManager;
import com.matrix.agent.core.session.SessionManager;
import com.matrix.agent.core.tool.MockCapabilityProvider;
import com.matrix.agent.core.tool.ToolExecutor;
import com.matrix.agent.core.memory.MemoryStore;
import com.matrix.agent.data.AgentRuntimeRepository;
import com.matrix.agent.data.ModelGatewayRepository;
import com.matrix.agent.data.audit.AuditEventRecorder;
import com.matrix.agent.data.audit.AuditRepository;
import com.matrix.agent.data.audit.NoopAuditRepository;
import com.matrix.agent.data.audit.RoomAuditRepository;
import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.memory.EpisodicMemorySourceImpl;
import com.matrix.agent.data.memory.RoomMemoryMigrator;
import com.matrix.agent.data.memory.RoomMemoryStore;
import com.matrix.agent.data.memory.SemanticMemorySourceImpl;
import com.matrix.agent.platform.AndroidKeyStoreMasterKeyProvider;
import com.matrix.agent.platform.KeystoreHmacAuditDigest;
import com.matrix.agent.platform.MasterKeyProvider;
import com.matrix.agent.platform.ModelApiClient;
import com.matrix.agent.platform.ModelConfig;
import com.matrix.agent.platform.SecureModelConfigStore;
import com.matrix.agent.platform.SharedPreferencesMemoryStore;

/** Explicit composition root for replaceable runtime and platform dependencies. */
public final class AppContainer {
    private static final String TAG = "MatrixAgent";
    private final AgentRuntimeRepository agentRuntimeRepository;
    private final ModelGatewayRepository modelGatewayRepository;
    private final SteerMailbox steerMailbox;

    private final DefaultVehicleStateSource vehicleStateSource;
    /**
     * V0.5.0 Stage 2:Room/SQLCipher Audit 落库入口——Stage 3 注入 AgentEngine。
     *
     * <p>装配失败(初始化异常 / SQLCipher 不可用)时退化为 {@link NoopAuditRepository},
     * 保证启动不被 Audit 阻塞;fail-open 与 Stage 3 的 persist fail-open 一致。
     */
    private final AuditRepository auditRepository;
    /**
     * V0.5.0 Stage 3:四层 Memory 召回器——buildSystemPrompt + LlmPlanner.savedKeysFor 用。
     *
     * <p>包装 Stage 1 4 个 source:Working(SessionContext)/ Episodic(空)/ Semantic(空)/
     * Preference(V0.4.3 SharedPreferencesMemoryStore)。
     */
    private final MemoryRecaller memoryRecaller;
    /**
     * V0.5.2 Stage 1:jtokkit O200K_BASE Tokenizer——Stage 3 接入 AgentEngine 主路径。
     *
     * <p>Stage 1 仅装配骨架,AgentEngine 主路径仍走 char-based(零行为变更);
     * Stage 3a 双轨并行(同时记 char/token),Stage 3b 切主路径(token 决策)。
     */
    private final Tokenizer tokenizer;
    /**
     * V0.5.2-rev 评审 P2-5:统一 shutdown 入口持有的资源。
     *
     * <p>旧实现 schedulerPool / ioPool 是构造器局部变量,Application.onTerminate 只关 scheduler;
     * ioPool / auditEventRecorder 没人关,daemon Thread 在进程死亡时虽被回收,但模拟器/集成测试
     * /AAOS Service 重建场景下残留 worker 不可控。改为 final field + {@link #shutdown()} 统一关闭。
     */
    private final com.matrix.agent.core.agent.DynamicThreadPool schedulerPool;
    private final com.matrix.agent.core.agent.DynamicThreadPool ioPool;
    private final com.matrix.agent.data.audit.AuditEventRecorder auditEventRecorderField;
    /**
     * V0.5.3 评审 P1-3:MemoryStore 装配降级标志——database=null 或 RoomMemoryStore 构造抛时
     * set true,UI 显示"记忆已降级"banner。volatile 保证 UI 线程读到最新值。
     *
     * <p>旧路径(V0.5.2-rev 之前)静默退到 SharedPreferencesMemoryStore(明文 XML),违反
     * "不静默降级"硬约束;V0.5.3 改用 InMemoryMemoryStore(非持久化)+ 显式标志。
     */
    private volatile boolean memoryDegraded;

    public AppContainer(Context context) {
        long started = System.nanoTime();
        Log.i(TAG, "[App] AppContainer init start");
        Context appContext = context.getApplicationContext();
        CapabilityRegistry registry = CapabilityRegistry.createDemoRegistry();
        PolicyEngine policyEngine = new PolicyEngine(registry);
        SessionManager sessionManager = new SessionManager();
        SessionLockManager sessionLockManager = new SessionLockManager();
        // V0.5.2 评审 P1-1:调度池与 I/O 池物理隔离——杜绝 Stage 11 共享池嵌套等待死锁。
        // 旧实现 TaskScheduler / ModelCallExecutor / ToolExecutor 共享一个 DynamicThreadPool,
        // Scheduler 占住线程跑 Agent 任务,Agent 内部把 model/tool call submit 到同一池并
        // future.get() 阻塞等待;队列未满时 ThreadPoolExecutor 不扩容,2 个并发 Agent 任务
        // 就足以把内层子任务永久堵队列直到超时。
        //
        // 改为两个独立池:schedulerPool(core=2/max=2,仅 Scheduler 占用,不嵌套 I/O);
        // ioPool(core=2/max=8,ModelCallExecutor + ToolExecutor 共享,串行使用不会嵌套)。
        // 死锁不可能:schedulerPool 线程等 ioPool 结果时,ioPool 内无 schedulerPool 线程占位。
        //
        // V0.5.3 评审 P1-4:schedulerPool 也改 AbortPolicy(与 ioPool 一致)。
        // 旧 3 参默认 CallerRunsPolicy——队列满时不抛 RejectedExecutionException,而是让 caller 线程
        // (Binder / system-service)同步执行 60s Agent 任务,future.get(timeout) 失效,
        // 反而触发 system-server ANR 监控。TaskScheduler.submit 的 catch (RejectedExecutionException)
        // 是死代码,V0.5.2-rev 加的 TaskState.REJECTED / StopReason.REJECTED 永远走不到。
        // 改 AbortPolicy 后队列满时显式抛,TaskScheduler catch 转 REJECTED 终态,UI 可观测"系统繁忙"。
        com.matrix.agent.core.agent.DynamicThreadPool localSchedulerPool =
                new com.matrix.agent.core.agent.DynamicThreadPool(2, 2, 32,
                        new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        // V0.5.2-rev 评审 P1-1:ioPool 改 AbortPolicy——队列满时显式抛 RejectedExecutionException,
        // 让 ModelCallExecutor / ToolExecutor catch 后返回 POLICY_HALT / EXECUTION_UNKNOWN 终态。
        // 旧默认 CallerRunsPolicy 会在 TaskScheduler 线程同步执行模型 HTTP 请求,绕过 future.get(timeout)
        // + abort hook 控制链路,可能让单次模型调用占住 Agent 调度线程超出任务 deadline。
        com.matrix.agent.core.agent.DynamicThreadPool localIoPool =
                new com.matrix.agent.core.agent.DynamicThreadPool(2, 8, 32,
                        new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        ToolExecutor toolExecutor = new ToolExecutor(4, localIoPool);
        ModelCallExecutor modelCallExecutor = new ModelCallExecutor(2, localIoPool);
        steerMailbox = new SteerMailbox();
        // V0.4.3 Stage B + Round 2:车辆运动状态源——保留为字段,通过 getVehicleStateSource()
        // 暴露给 demo / debug UI,让"切 gear=D 触发 PARKED_ONLY 拒绝"在 APK 中可手动验证。
        // 后续版本接 CarPropertyManager 时本字段类型变为真实实现类,getVehicleStateSource() 不变。
        vehicleStateSource = new DefaultVehicleStateSource();
        // V0.5.2 Stage 2:MatrixDatabase 提前装配——audit + memory 共用同一 SQLCipher 实例
        // (getInstance 单例,keyProvider 失败 → database=null → audit/memory 双双退化)。
        MatrixDatabase database = createDatabaseSafely(appContext);
        // V0.5.3 评审 P1-3:createMemoryStoreSafely 3 参重载,失败时 set memoryDegraded=true
        // 并退到 InMemoryMemoryStore(非持久化),不再用 SharedPreferencesMemoryStore(明文 XML)。
        java.util.concurrent.atomic.AtomicBoolean degradedRef = new java.util.concurrent.atomic.AtomicBoolean(false);
        MemoryStore memoryStore = createMemoryStoreSafely(appContext, database, degradedRef);
        this.memoryDegraded = degradedRef.get();
        // V0.5.0 Stage 3:Memory 装配——Working / Episodic / Semantic / Preference 4 层 Router。
        // 提前到 modelGatewayRepository 之前:LlmPlanner.savedKeysFor 需要它。
        // V0.5.2 Stage 7:Episodic / Semantic 升级真实 Room-backed 召回源——
        // database=null 时退 EmptyXxx(与 auditRepository fail-open 语义对齐)。
        // V0.5.3 评审 P1-1:保留 EpisodicMemorySourceImpl 引用,供 RoomMemoryWriter 调
        // invalidateCache(失效 5min LRU)。database=null 路径仍用 EmptyEpisodicMemorySource。
        EpisodicMemorySourceImpl episodicImpl = database == null
                ? null
                : new EpisodicMemorySourceImpl(database.sessionHistoryDao());
        EpisodicMemorySource episodic = episodicImpl != null
                ? episodicImpl
                : new EmptyEpisodicMemorySource();
        SemanticMemorySource semantic = database == null
                ? new EmptySemanticMemorySource()
                : new SemanticMemorySourceImpl(database.memoryRecordDao());
        // V0.5.3 评审 P1-1:MemoryWriter 装配提前到 provider 之前——
        // MockCapabilityProvider(memoryStore, memoryWriter) 2 参重载,semantic handler 持真实 writer。
        // database=null 时退 NOOP(与 auditRepository fail-open 语义一致)。
        // database != null 时注入 RoomMemoryWriter,AgentEngine 终态出口点写 session_history 表,
        // memory.semantic.save capability handler 写 memory_record 表。
        com.matrix.agent.core.agent.MemoryWriter memoryWriter = database == null
                ? com.matrix.agent.core.agent.MemoryWriter.NOOP
                : new com.matrix.agent.data.memory.RoomMemoryWriter(
                        database.sessionHistoryDao(), database.memoryRecordDao(),
                        episodicImpl, database::runInTransaction);
        MockCapabilityProvider provider = new MockCapabilityProvider(memoryStore, memoryWriter);
        ModelApiClient modelClient = new ModelApiClient();
        SecureModelConfigStore configStore = new SecureModelConfigStore(appContext);

        memoryRecaller = new MemoryRouter(
                new SessionContextWorkingMemory(sessionManager),
                episodic,
                semantic,
                new LegacyPreferenceMemorySource(memoryStore));

        // V0.5.0 Stage 3:5 参构造器——把 memoryRecaller 透传给 LlmPlanner。
        modelGatewayRepository = new ModelGatewayRepository(configStore, modelClient, registry,
                memoryStore, memoryRecaller);

        // V0.5.2 评审 P1-5:装配 FallbackIntentClassifier(LlmIntentClassifier, Keyword)。
        // saved config 存在 → LLM 主路径,失败 / 低置信度退 Keyword;
        // saved config 不存在(首次启动用 DemoModelGateway)→ 直接 Keyword,避免 LLM NPE。
        // 切换 Provider 时 ModelApiViewModel.saveAndApply 暂不更新 IntentClassifier——
        // LLM 用旧 config 失败时 Fallback 自动退 Keyword,语义安全(V0.5.3 增强)。
        IntentClassifier appClassifier = KeywordIntentClassifier.INSTANCE;
        ModelConfig savedForClassifier = configStore.load();
        if (savedForClassifier != null) {
            try {
                LlmIntentClassifier llm = new LlmIntentClassifier(modelClient, savedForClassifier);
                appClassifier = new FallbackIntentClassifier(llm, KeywordIntentClassifier.INSTANCE);
                Log.i(TAG, "[App] IntentClassifier = Fallback(LLM, Keyword)");
            } catch (Exception ex) {
                Log.w(TAG, "[App] LlmIntentClassifier init FAILED, fallback to Keyword cause="
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        } else {
            Log.i(TAG, "[App] IntentClassifier = Keyword (no saved config)");
        }

        // V0.5.0 Stage 2:SQLCipher + Room Audit 装配——fail-open,失败退化 NoopAuditRepository。
        // V0.5.2 Stage 2:database 由 AppContainer 顶层装配,与 MemoryStore 共用同一实例。
        // Stage 3 把此 auditRepository 注入 AgentEngine 5 个出口点。
        auditRepository = createAuditRepositorySafely(database);

        // V0.5.2 Stage 1:Tokenizer 装配——jtokkit O200K_BASE,Stage 3 接入 AgentEngine 主路径。
        // 失败时(Jtokkit 注册异常 / 词表缺失)退 CharFallbackTokenizer——
        // V0.5.0 已有 fallback 设计,JtokkitTokenizer 仅在测试/debug 用,V0.5.2 切主路径。
        Tokenizer localTokenizer;
        try {
            localTokenizer = new JtokkitTokenizer();
            Log.i(TAG, "[App] Tokenizer init jtokkit=O200K_BASE available=true");
        } catch (Exception ex) {
            Log.w(TAG, "[App] Jtokkit init FAILED, fallback to CharFallback cause="
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
            localTokenizer = CharFallbackTokenizer.INSTANCE;
        }
        tokenizer = localTokenizer;

        // 第七轮 P2-2:同一个 AgentBudget 同时驱动 AgentEngine(字符/迭代/Tool 上限)
        // 和 AgentRequest.timeoutMillis(总 deadline)。Repository 不再硬编码 60_000L。
        // V0.4.1 Stage D:注入 SteerMailbox,启用运行时追加指令(REPROMPT/FORCE_TOOL/DEFER)。
        AgentBudget sharedBudget = new AgentBudget();
        // V0.4.3 Stage A:V0.4.1 主驾优先调度器正式接入 APK runtime。parallelism=2
        // 让主驾抢占 + 副驾排队可并行,SessionLockManager 仍保证同 session 串行。
        // V0.5.2 评审 P1-1:TaskScheduler 用独立 schedulerPool(物理隔离,避免嵌套等待死锁)。
        TaskScheduler scheduler = new TaskScheduler(2, sessionLockManager, localSchedulerPool);
        // V0.5.1 Stage 4 + Stage 6 P1.1:审计侧 HMAC-SHA-256 digest 装配——fail-closed,
        // 失败时退回 UnavailableAuditDigest(输出固定 [redacted:chars=N,digest=unavailable])。
        // 不退回 V0.5.0 SHA-1 路径——SHA-1 8 位对低熵文本可枚举比对,这正是 Stage 4 升级
        // 要修的隐私风险,不能在 Keystore 异常设备上重暴露。
        AuditDigest auditDigest = createAuditDigestSafely(appContext);
        // V0.5.2 Stage 4:audit_event 增量事件落库——PRE_TOOL/POST_TOOL/POLICY/STEER。
        // database=null 时退化为 NOOP(与 auditRepository fail-open 语义一致)。
        AuditEventRecorder auditEventRecorder = createAuditEventRecorderSafely(database);
        // V0.5.2-rev 评审 P2-5:字段化持有,供 shutdown() 统一关闭。
        this.schedulerPool = localSchedulerPool;
        this.ioPool = localIoPool;
        this.auditEventRecorderField = auditEventRecorder;
        // V0.5.2 Stage 5:SteerMailbox epoch gate——clearUserData 后,旧 Steer 在 drain 时
        // drop + audit(STEER_DROPPED_STALE)。staleHandler 在 auditEventRecorder 装配之后注入。
        steerMailbox.setStaleHandler(new SteerMailboxStaleHandler(auditEventRecorder));
        // V0.5.0 Stage 3:13 参构造器——传入 auditRepository + memoryRecaller。
        AgentRuntimeRepository.AgentEngineFactory engineFactory = gateway -> {
            AgentEngine engine = new AgentEngine(
                    gateway, modelCallExecutor, policyEngine, registry, provider, sessionManager,
                    new DefaultContextUpdater(), sessionLockManager, toolExecutor, sharedBudget,
                    steerMailbox, auditRepository, memoryRecaller);
            // V0.5.1 Stage 4:装配期注入 digest(AgentEngine 默认构造时已退化为 SHA-1,
            // 这里覆盖为 HMAC-SHA-256 / 或装配失败时仍为 SHA-1)。
            engine.setAuditDigest(auditDigest);
            // V0.5.2 Stage 3a:注入 Tokenizer——主路径双轨统计用,不影响 budget 决策。
            // V0.5.3 切换主路径到 token 时,AgentBudget 改用真实 jtokkit 估算。
            engine.setTokenizer(tokenizer);
            // V0.5.2 Stage 4:注入 AuditEventRecorder——5 个出口点写入 audit_event 表。
            engine.setAuditEventRecorder(auditEventRecorder);
            // V0.5.2 Stage 6:注入 PromptBuilder——buildSystemPrompt 委托 segmented 拼装,
            // 字面与内联路径等价(PromptBuilderEquivalenceTest 验证),V0.5.3 PromptComposer
            // 在此基础上加 token 截断。
            engine.setPromptBuilder(new DefaultPromptBuilder());
            // V0.5.2 Stage 8:注入 ConversationCompressor——heuristic 降级(provider=null),
            // 长任务 BUDGET_EXHAUSTED 前先丢老 turns 腾预算。LLM 摘要路径留 V0.5.3
            // V0.5.2 评审 P1-4:装 LlmSummaryProvider——基于 Provider 的对话摘要,
            // 替换旧 heuristic(直接丢老 turns)。Provider 失败 / configStore 为 null 时
            // 自动降级 heuristic(ConversationCompressor.summarizeWithFallback catch 全部异常)。
            engine.setConversationCompressor(new ConversationCompressor(
                    new LlmSummaryProvider(modelClient, configStore)));
            // V0.5.3 评审 P1-1:注入 MemoryWriter——主路径 L678 + terminalOutcome L783 写入
            // session_history 表;memory.semantic.save capability 写 memory_record 表。
            engine.setMemoryWriter(memoryWriter);
            return engine;
        };
        agentRuntimeRepository = new AgentRuntimeRepository(engineFactory, provider, sessionManager,
                memoryStore, new DemoModelGateway(), "离线 DemoModelGateway", sharedBudget,
                scheduler, vehicleStateSource, registry, appClassifier,
                auditRepository);
        // 第七轮 P1.3:Repository 清理用户数据时同步清空 SteerMailbox(陈旧 FORCE_TOOL/REPROMPT/DEFER)。
        // mailbox 与 AgentEngine 共享同一实例,不重复创建。
        agentRuntimeRepository.setSteerMailbox(steerMailbox);
        // V0.5.2 评审 P1-3:注入增量 audit_event recorder,clearUserDataDetailed 时
        // 调 advanceEpoch + dropByUserZone,杜绝"clearByUserZone 后异步 flush 把旧事件写回"。
        agentRuntimeRepository.setAuditEventRecorder(auditEventRecorder);
        // V0.5.4 评审 P1-2:用户硬约束——"长期记忆仅由用户决定"。装配 KeywordMemoryIntentDetector,
        // Repository.execute 入口判定用户原文是否含"记住/保存/以后默认/不要忘记"等显式动词;
        // memory.semantic.save handler 在未命中时直接 POLICY_REJECTED(硬 gate,prompt 约定双重防线)。
        agentRuntimeRepository.setMemoryIntentDetector(KeywordMemoryIntentDetector.INSTANCE);

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
    public SteerMailbox getSteerMailbox() { return steerMailbox; }
    /** V0.4.3 Round 2:暴露给 demo / debug UI 切换 gear/speed 等,验证 PARKED_ONLY 等约束生效。 */
    public DefaultVehicleStateSource getVehicleStateSource() { return vehicleStateSource; }
    /** V0.5.0 Stage 2:Room/SQLCipher Audit 入口;Stage 3 注入 AgentEngine。 */
    public AuditRepository getAuditRepository() { return auditRepository; }
    /** V0.5.0 Stage 3:Memory 召回器入口;注入 AgentEngine + LlmPlanner。 */
    public MemoryRecaller getMemoryRecaller() { return memoryRecaller; }
    /** V0.5.2 Stage 1:Tokenizer 入口;Stage 3 接入 AgentEngine 主路径。 */
    public Tokenizer getTokenizer() { return tokenizer; }
    /**
     * V0.5.3 评审 P1-3:MemoryStore 降级标志——database=null 或 RoomMemoryStore 构造抛时为 true。
     * UI 读取本标志显示"记忆已降级"banner,提醒用户重启后偏好丢失。
     */
    public boolean isMemoryDegraded() { return memoryDegraded; }

    /**
     * V0.5.2-rev 评审 P2-5:统一关闭——Repository(取消在途)→ schedulerPool → ioPool → auditEventRecorder。
     *
     * <p>旧实现只有 Repository.shutdown()(关 scheduler),ioPool + auditEventRecorder 没人关,
     * 模拟器/集成测试/AAOS Service 重建场景下残留 worker。改为统一入口,由
     * {@link MatrixAgentApplication#onTerminate()} 调用,真机依赖进程级回收兜底。
     */
    public void shutdown() {
        Log.i(TAG, "[App] shutdown begin");
        try {
            agentRuntimeRepository.shutdown();
        } catch (Throwable t) {
            Log.w(TAG, "[App] shutdown: repository shutdown threw cause=" + t.getClass().getSimpleName());
        }
        try {
            schedulerPool.shutdown();
        } catch (Throwable t) {
            Log.w(TAG, "[App] shutdown: schedulerPool threw cause=" + t.getClass().getSimpleName());
        }
        try {
            ioPool.shutdown();
        } catch (Throwable t) {
            Log.w(TAG, "[App] shutdown: ioPool threw cause=" + t.getClass().getSimpleName());
        }
        try {
            auditEventRecorderField.shutdown();
        } catch (Throwable t) {
            Log.w(TAG, "[App] shutdown: auditEventRecorder threw cause=" + t.getClass().getSimpleName());
        }
        Log.i(TAG, "[App] shutdown done");
    }

    /**
     * V0.5.2 Stage 2:MatrixDatabase 装配——audit + memory 共用单一 SQLCipher 实例。
     *
     * <p>失败(AndroidKeyStore 不可用 / SQLCipher 不可用 / Migration v1→v2 损坏)→ 返回 null,
     * auditRepository / memoryStore 双双退化(NoopAuditRepository / SharedPreferencesMemoryStore)。
     * 这种退化不阻塞 AppContainer 启动,与 V0.5.0 Stage 2 fail-open 语义一致。
     */
    private static MatrixDatabase createDatabaseSafely(Context appContext) {
        try {
            MasterKeyProvider keyProvider = new AndroidKeyStoreMasterKeyProvider(appContext);
            return MatrixDatabase.getInstance(appContext, keyProvider);
        } catch (Exception ex) {
            Log.e(TAG, "[App] MatrixDatabase init FAILED, audit+memory will degrade cause="
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
            return null;
        }
    }

    /**
     * V0.5.2 Stage 2:RoomMemoryStore 主路径装配——SP 历史 → Room 一次性迁移 + Room-backed store。
     *
     * <p>失败(database=null / Room 不可用 / Migrator 异常)→ fallback 到 SharedPreferencesMemoryStore,
     * 与 V0.5.0/V0.5.1 行为对齐(SPACE 文件保留,不下次启动重试)。
     *
     * <p><b>epoch 加载顺序</b>:Migrator 先跑(把 SP EPOCH_KEY 写入 Room __system__ 行)→
     * RoomMemoryStore 构造时 queryByKey 加载 epoch——跨进程重启不丢。
     *
     * @deprecated V0.5.3 评审 P1-3:静默退到 SharedPreferencesMemoryStore(明文 XML)违反
     *     "不静默降级"硬约束。新代码改用 {@link #createMemoryStoreSafely(Context, MatrixDatabase, AtomicBoolean)}
     *     传 AtomicBoolean 接收降级标志,失败时退到 InMemoryMemoryStore(非持久化)。
     */
    @Deprecated
    static MemoryStore createMemoryStoreSafely(Context appContext, MatrixDatabase database) {
        return createMemoryStoreSafely(appContext, database, new java.util.concurrent.atomic.AtomicBoolean(false));
    }

    /**
     * V0.5.3 评审 P1-3:RoomMemoryStore 主路径装配 + 降级标志透传。
     *
     * <p>失败路径(database=null / Room 不可用 / Migrator 异常):
     * <ul>
     *   <li>退到 {@link com.matrix.agent.data.memory.InMemoryMemoryStore}(非持久化,重启回到 epoch=0);</li>
     *   <li>{@code memoryDegradedRef.set(true)},UI 显示"记忆已降级"banner。</li>
     * </ul>
     *
     * <p>不再退到 SharedPreferencesMemoryStore——SQLCipher 加密的目的不能被静默绕过。
     * InMemoryMemoryStore 不持久化,但语义诚实(用户重启后丢失,但知道是降级模式)。
     * auditRepository 也走 database=null 路径退 NoopAuditRepository,二者语义一致。
     *
     * <p><b>epoch 加载顺序</b>:Migrator 先跑(把 SP EPOCH_KEY 写入 Room __system__ 行)→
     * RoomMemoryStore 构造时 queryByKey 加载 epoch——跨进程重启不丢。
     */
    static MemoryStore createMemoryStoreSafely(Context appContext, MatrixDatabase database,
            java.util.concurrent.atomic.AtomicBoolean memoryDegradedRef) {
        if (database == null) {
            Log.w(TAG, "[App] MemoryStore fallback to InMemoryMemoryStore (database=null) — memoryDegraded=true");
            memoryDegradedRef.set(true);
            return new com.matrix.agent.core.memory.InMemoryMemoryStore();
        }
        try {
            MemoryRecordDao dao = database.memoryRecordDao();
            RoomMemoryStore.TransactionRunner txRunner = database::runInTransaction;
            RoomMemoryMigrator.fromSharedPreferences(appContext, dao, txRunner).migrate();
            return new RoomMemoryStore(dao, txRunner);
        } catch (Exception ex) {
            Log.w(TAG, "[App] RoomMemoryStore init FAILED, fallback to InMemoryMemoryStore cause="
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage()
                    + " — memoryDegraded=true", ex);
            memoryDegradedRef.set(true);
            return new com.matrix.agent.core.memory.InMemoryMemoryStore();
        }
    }

    /**
     * V0.5.0 Stage 2:Room + SQLCipher Audit 装配——fail-open。
     *
     * <p>V0.5.2 Stage 2:database 由 {@link #createDatabaseSafely} 装配(database=null 时退 Noop)。
     * MasterKeyProvider 已在装配期实例化过(同 AndroidKeyStore),这里不再重建。
     * 任何环节失败(Room DAO 异常 / Migration 损坏)→ 退化 Noop。
     */
    private static AuditRepository createAuditRepositorySafely(MatrixDatabase database) {
        if (database == null) {
            return NoopAuditRepository.INSTANCE;
        }
        try {
            // V0.5.2 Stage 1:5 参构造器——持有 database + 4 DAO(加 auditEventDao),
            // 让 clearByUserZone 在 database.runInTransaction 内跨 4 表原子删除
            // (audit_event userId 列已就位,MIGRATION_1_2 已加索引)。
            return new RoomAuditRepository(database,
                    database.trajectoryDao(), database.sessionHistoryDao(),
                    database.memoryRecordDao(), database.auditEventDao());
        } catch (Exception ex) {
            Log.e(TAG, "[App] Audit init FAILED, fallback to Noop cause="
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
            return NoopAuditRepository.INSTANCE;
        }
    }

    /**
     * V0.5.2 Stage 4:audit_event 增量事件 Recorder 装配——fail-open。
     *
     * <p>database=null 时退化为 {@link AuditEventRecorder#NOOP}(不写 audit_event 表,
     * AgentEngine 5 个出口点直接 return);Dao 异常 / Executor 拒绝时仅 log,不阻塞主路径。
     */
    private static AuditEventRecorder createAuditEventRecorderSafely(MatrixDatabase database) {
        if (database == null) {
            Log.w(TAG, "[App] AuditEventRecorder fallback to NOOP (database=null)");
            return AuditEventRecorder.NOOP;
        }
        try {
            return new AuditEventRecorder(database.auditEventDao());
        } catch (Exception ex) {
            Log.w(TAG, "[App] AuditEventRecorder init FAILED, fallback to NOOP cause="
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
            return AuditEventRecorder.NOOP;
        }
    }

    /**
     * V0.5.2 Stage 5:SteerMailbox stale Steer 处理器——把 drop 事件转给 AuditEventRecorder。
     *
     * <p>仅记 type + payloadChars,**不记 payload 内容**(REPROMPT 可能含 PII)。
     * sessionId 不在 audit_event 字段中(只有 requestId/userId/zone/actor),用 requestId 占位
     * "stale-drop"(没有 AgentRequest 上下文,Repository.clearUserData 触发,不在请求路径)。
     *
     * <p><b>V0.5.2 评审附加</b>:sessionId → (userId, zone, actor) 显式映射——
     * 旧实现 {@code sessionId.toUpperCase()} 得到 "DEMO-DRIVER",与 Repository.clearAuditSafe
     * 用的 "DRIVER"/"PASSENGER" 字面不一致,clearByUserZone 清不掉这些 stale-steer 行。
     * V0.5.x 范围内 sessionId 只有 "demo-driver" / "demo-passenger" 两个(V0.5.0 ActorUsers
     * 集中定义),hard-code 不丢扩展性;V0.5.3 接入真实多 session 时,改为运行时注入。
     */
    static final class SteerMailboxStaleHandler implements StaleSteerHandler {
        private final AuditEventRecorder recorder;

        SteerMailboxStaleHandler(AuditEventRecorder recorder) {
            this.recorder = recorder == null ? AuditEventRecorder.NOOP : recorder;
        }

        @Override
        public void onStaleSteerDropped(String sessionId, Steer steer, long stampedEpoch,
                long currentEpoch) {
            String userId;
            String zone;
            String actor;
            if ("demo-driver".equals(sessionId)) {
                userId = "demo-driver";
                zone = "DRIVER";
                actor = "DRIVER";
            } else if ("demo-passenger".equals(sessionId)) {
                userId = "demo-passenger";
                zone = "PASSENGER";
                actor = "PASSENGER";
            } else {
                userId = "";
                zone = "";
                actor = "";
            }
            recorder.recordSteerDroppedStale(
                    "stale-drop:" + sessionId,
                    userId,
                    zone,
                    actor,
                    steer.getType().name(),
                    steer.getPayload() == null ? 0 : steer.getPayload().length(),
                    currentEpoch);
        }
    }

    /**
     * V0.5.1 Stage 4 + Stage 6 P1.1:装配审计侧 HMAC-SHA-256 digest,失败 fail-closed。
     *
     * <p><b>fail-closed vs master_key fail-closed / vs Stage 4 初版 fail-degrade</b>:
     * <ul>
     *   <li>master_key 失败 → SQLCipher 不可用 → 数据落不下去 → fail-closed
     *       ({@code createAuditRepositorySafely} 退化 NoopAuditRepository)。</li>
     *   <li>HMAC 失败 → 审计数据继续 SQLCipher 加密落盘(可用性优先),
     *       但 Stage 4 初版退回 {@link Sha1AuditDigest}——这反而重暴露了 Stage 4 要修的
     *       "SHA-1 8 位对低熵文本可枚举比对"风险。Stage 6 P1.1 改 fail-closed:
     *       退回 {@link UnavailableAuditDigest},输出固定
     *       {@code [redacted:chars=N,digest=unavailable]},不暴露"同输入同输出"稳定性
     *       信号,杜绝枚举反推。</li>
     * </ul>
     *
     * <p>构造失败场景:AndroidKeyStore 不可用 / 别名已存在但类型不匹配 / HMAC_SHA256
     * Provider 缺失(理论不应发生,JRE 内置)。
     */
    private static AuditDigest createAuditDigestSafely(Context appContext) {
        try {
            return new KeystoreHmacAuditDigest(appContext);
        } catch (Exception ex) {
            Log.w(TAG, "[App] Audit HMAC init FAILED, fail-closed to UnavailableAuditDigest cause="
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
            return new UnavailableAuditDigest();
        }
    }
}
