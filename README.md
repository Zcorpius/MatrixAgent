# MatrixAgent

MatrixAgent 是面向 AAOS 的厂商级 AI Agent Runtime 原型。当前版本 `V0.5.5`(`versionCode=10`),已落地真正的迭代式 Agent Runtime(`LLM → Tool Call → Policy → Execute → Observation → LLM` 循环)+ Runtime Control(SteerMailbox / Cancel 安全边界 / CancellableModelCall / 主驾优先 TaskScheduler / OpenAI Native 多轮收尾)+ 四层 Memory(Working / Episodic / Semantic / Preference)+ SQLCipher 加密持久化 + Room WAL + 字段级 HMAC 脱敏审计。使用纯 Java + MVVM 实现,可在普通 Android 模拟器运行。既可使用离线 DemoModelGateway,也可连接云端或本地大模型(支持 OpenAI-Compatible / Anthropic / Gemini 原生 Tool Calling)。

它不是量产车控 APK,所有车辆状态和导航执行均为本地 Mock。

## 当前能力

### Agent Loop & Runtime Control

- 真正的迭代式 Agent Loop:`LLM → Tool Call → Policy → Execute → Observation → LLM`,直到模型选择直接答复或预算耗尽
- 三层预算:最大迭代数 + 最大 Tool Call 数 + 总 deadline + 单条消息字符上限 + 总输入字符上限 + 消息条数上限
- 结构化 Trajectory:iteration / toolCallId / PolicyDecision / Observation(脱敏后)/ 耗时 / 终止原因
- Policy 二分:CAPABILITY(不可上诉,累计禁用)vs PARAMETER(可换参数重试)
- SteerMailbox:三种 steer 类型(REPROMPT / FORCE_TOOL / DEFER)在 LLM 调用前 drain,StopReason.DEFERRED + TaskState.DEFERRED
- Cancel 安全边界:LLM 调用前 / Tool 执行前 / per-tool-call 三个显式 cancel 检查点 + CancellableModelCall 同步触发传输层 abort
- 主驾优先 TaskScheduler:readOnlyHint=true 的查询/问答可被主驾抢占,已开始的原子车控写操作不强制中断;StopReason.PREEMPTED
- 写操作 timeout / cancel 语义边界:ToolResult.Status.EXECUTION_UNKNOWN 区分"未发生"与"命令可能已下发";Repository GRACE_WINDOW_MILLIS=500 收敛窗口
- SteerMailbox epoch gate + StaleSteerHandler:clearUserData bump epoch 后,旧 epoch 的 FORCE_TOOL/DEFER 被 drain 时丢弃 + audit 记录

### 模型协议适配

- DemoModelGateway(离线关键词决策,不调外部模型)
- LlmModelGateway 走 Anthropic / OpenAI-Compatible / Gemini 原生 Tool Calling 或 JSON Compatibility
- 错误分类(ModelApiException):RateLimit 429 / Server 5xx / Client 4xx / Network / Timeout;仅 retryable 触发受控重试
- RetryPolicy:MAX_ATTEMPTS=3,500ms → 1000ms → 2000ms 指数退避 + ±20% jitter
- CancellableModelCall 接 CancellationToken,abort hook 触发 HttpURLConnection.disconnect
- Provider 协议一致性:OpenAI `tool_calls` ↔ `finish_reason=tool_calls`、Anthropic `tool_use` ↔ `stop_reason=tool_use`、Gemini `functionCall`(无 id,按 turn 内 index 合成 `gemini-<index>`)、LENGTH 截断短路、PROTOCOL_ERROR 终止
- ToolCall ID 由 Provider 透传,Runtime 不自造

### Policy 与 Capability

- 静态 CapabilityRegistry + R0/R1/R3 风险等级 + R3 禁止能力拦截
- Capability 定义:风险等级 / 描述 / 参数规则 / 区域 / 幂等性 / 超时 / 验证要求 / targetZoneRequired / isWriteOperation
- JSON Schema 校验:required / type / range / enum / pattern / maxLength / minLength,执行边界拒绝未声明字段
- 完整 JSON Schema(CanonicalSchema cycle 检测 + ToolSchemaView 按 zone 投影)
- PolicyEngine 显式意图约束(ExplicitIntentConstraints):关键词识别主驾/副驾 + 多目标 / 否定冲突 fail-closed
- PolicyEngine.evaluate capability-level gates:R3 禁止 / requiredVehicleStates 不满足 / readOnlyHint+writeOperation / **memory.semantic.save + memorySaveAllowed=false**(在 schema 校验之前,CAPABILITY_REJECTED 不可上诉)
- IntentClassifier 接口(`classify(command)` 返回 `IntentResult{readOnly, confidence, reason}`);实现:Keyword / Llm / Fallback(LLM 失败 / 低置信度退 Keyword)
- MemoryIntentDetector 接口 + KeywordMemoryIntentDetector(正向 keyword + 否定短语门:NEGATIVE 优先短路,如"不要记住 X"→false)
- VerifyMethod 三种(NONE / READBACK_FIELD / READBACK_GET)+ USER_CONFIRM / TIMEOUT 留后续版本接 ASR / Provider 超时
- VerifyStrategy 路由 + ProviderContext 不可变 + CapabilityHandler 接口,MockCapabilityProvider 走 `Map<String, CapabilityHandler>` 路由表

### Memory(四层抽象 + 双维度隔离)

- MemoryLayer:WORKING / EPISODIC / SEMANTIC / PREFERENCE
- MemoryScope(userId, zone) 不可变值对象,双维度隔离;V0.5.5 真正生效的隔离:Room MemoryRecordEntity 主键 (userId, zone, layer, key) + SessionHistoryDao / TrajectoryDao / AuditRepository 查询接口全部强制 (userId, zone) 访问域
- MemoryRouter 按 layer 优先级合并截断的召回入口
- 召回源:SessionContextWorkingMemory(包装 SessionManager.snapshotTurns)/ EpisodicMemorySourceImpl(SessionHistoryDao.queryByUserZone,LRU cache 5min)/ SemanticMemorySourceImpl(MemoryRecordDao.queryByUserZoneLayer,关键词匹配 + score 排序)/ LegacyPreferenceMemorySource(包装 V0.4.3 SharedPreferences)
- MemoryWriter 接口:writeEpisodicOnTerminal(AgentEngine 终态自动调) + writeSemantic(memory.semantic.save handler 显式调) + readSemantic(memory.semantic.get handler 调);RoomMemoryWriter 实现,fail-log(仅 Log.w + 计数,不向上传播)
- **epoch 原子性**:RoomMemoryWriter 构造器接 `TransactionRunner`(生产传 `database::runInTransaction`);writeEpisodicOnTerminal / writeSemantic 在事务内"读 __system__ epoch 行 + 比较 + insert/upsert"原子序列;`requestEpoch != currentEpoch` 事务内 return 不写
- **readEpochFromSystemRow fail-closed**:DAO 异常 / parse 失败 / dao==null 返回 null,事务内 return 拒绝写入(绝不把"读取失败"伪装成 epoch=0)
- EpisodicSummary:仅记 startedAtMillis / finalState / durationMs / turnCount / successfulCapabilities(≤3,dedupe+排序);**完全不读** userText / assistantContent / tool arguments / tool result content;终态过滤(仅 SUCCEEDED / FAILED 写入,CANCELLED / TIMED_OUT / PREEMPTED / REJECTED / EXECUTION_UNKNOWN / DEFERRED / PARTIALLY_SUCCEEDED 全部 skip);summaryJson ≤ 2048 字节
- RoomMemoryStore epoch 持久化到 memory_record 特殊行(userId=__system__, zone=__system__, layer=preference, key=__epoch__);clearUserDataAndBump 原子清两 user 数据 + bump epoch
- Semantic 写入 Schema + Writer 双层 validation:key 限 `^(family|allergy|work|fact)\.[A-Za-z0-9_.]+$` + maxLength 64,value maxLength 2048(Java char/UTF-16 code unit),score [0,1],sourceSessionId maxLength 128;writer-side helper 防第三方 Provider 漏检

### 持久化与审计

- SQLCipher 4.5.4 + Room 2.7.0 + androidx.sqlite-framework 2.4.0
- MatrixDatabase(@Database v3):4 张 Entity(TrajectoryEntity / SessionHistoryEntity / MemoryRecordEntity / AuditEventEntity)+ 4 个 DAO,复合主键 + 索引;Migration v1→v2 加 audit_event userId 列 + 索引;v2→v3 加 audit_event requestEpoch 列(修复 stale gate 用时间戳 vs 版本号比较的 bug)
- WAL journal mode + enableWriteAheadLogging 兜底
- AndroidKeyStoreMasterKeyProvider:别名 `matrix_db_master_key`,AES-256-GCM + 12B IV,32 字节随机 passphrase;首次生成 `.commit()` 同步落盘;char[] → byte[] 后 `Arrays.fill` 清零;**getPassphrase 失败抛 IllegalStateException,绝不静默退化明文 DB**,由 `AppContainer.createAuditRepositorySafely` 退化 NoopAuditRepository
- AuditRepository:RoomAuditRepository(同步阻塞 persist,fail-open)+ NoopAuditRepository(兜底)
- clearByUserZone:跨 4 表 transaction 删除(trajectory + session_history + memory_record + audit_event),返回 ClearOutcome(SUCCESS / PARTIAL_FAILURE / FAILURE / NOT_APPLICABLE);UI 显示真实结果,失败保留重试按钮
- AuditEventRecorder:PRE_TOOL / POST_TOOL / POLICY / STEER 增量事件 batch 队列(ScheduledExecutorService 200ms flush,batch ≤50,队列上限 500 over drop PRE_TOOL 保 POST_TOOL);Terminal 事件同步 flush
- AuditEvent requestEpoch stale gate:clearUserData bump epoch 后,旧 requestEpoch 的事件被 isStale drop
- AuditDigest:KeystoreHmacAuditDigest(独立别名 `matrix_audit_hmac_key`,HMAC-SHA-256 截断 16 hex = 64-bit 抗碰撞);装配失败 fail-closed 退回 UnavailableAuditDigest(固定输出 `[redacted:chars=N,digest=unavailable]`,不保留可比对摘要)
- AuditRedactor:凭据正则 + capability schema + memory preference 全脱敏 + 未注册 / R3 / schema 外字段 fail-closed;free text 走 HMAC 摘要 `[redacted:chars=N,sha=xxxxxxxx]`;Assistant content / ToolResult message / capability template 三条路径全覆盖
- TrajectoryCodec:org.json round-trip,字段 audit-redact;Trajectory.rewriteStopReason 受控终态重映射(抢占时 outcome.stopReason == trajectory.stopReason 一致)
- AgentRuntimeRepository 兜底 audit:execute() return 前统一 persist,覆盖 future.get 超时 / interrupt / Scheduler 内部生成终态

### 上下文管理与 Prompt

- 最近 12 轮 SessionContext + per-session 引用计数锁 + TTL 30min / 容量 32 / LRU
- SessionLockManager:同 session 串行,不同 session 并行
- Tokenizer 接口 + JtokkitTokenizer(BPE O200K_BASE) + CharFallbackTokenizer(默认装配);AgentBudget 暴露 token getter,AgentEngine 主路径仍用 char 判断(双轨观察,真切换留后续)
- ConversationCompressor:80% 主动触发 + 100% 被动 race 兜底;LLM SummaryProvider 摘要自然语言 transaction,structured fact(含 tool_call / policy / observation 的 transaction)原样保留;LLM 失败降级 heuristic;分批摘要(MAX_TURNS_TO_SUMMARIZE,MAX_SUMMARIZE_BATCHES=3);LLM 调用接 CancellationToken + deadline,剩余 < 2s 抛 SummaryUnavailableException
- PromptBuilder + PromptSegment:BASE / RECALLED_MEMORY / TOOL_LIST / ZONE_HINT 模块化拼装;`<memory_context>` 标签 + 白名单投影(仅 `preferred_temperature` int 16-30 / `preferred_seat_level` int 0-3 / `preferred_media_volume` int 0-100 附 value,其他 PII key 整层 deny)
- SensitiveKeys 共享常量(BUILTIN_PII_KEYS denylist + isPiiKey):DefaultPromptBuilder 与 AuditRedactor 共用,denylist 作 allowlist 双重保险底线
- buildSystemPrompt 包 try/catch,recall 异常降级到 base prompt 不崩溃任务("记忆是增强能力,不能成为车机任务入口的单点故障")
- SafeLog:用户输入 / Tool 参数 / ToolResult message / Provider raw response / HTTP 错误 body 一律占位符

### 测试覆盖

- **810 JVM 测试**(`app/src/test/`)—— Memory / Audit / Policy / AgentEngine / Repository / Provider 协议 / TrajectoryCodec / Tokenizer / 全部能力单测 + 9 轮评审 P1/P2 反例回归
- **25 androidTest**(`app/src/androidTest/`)—— Smoke / DAO instrumented / RoomAuditRepo / KeystoreHmac / SQLCipher 真库 / MemorySemanticSave 真实 Room SQL / RoomMemoryWriterEpochAtomicity 真实 SQLite 写者锁

## MVVM 架构

```text
MainActivity（应用外壳、抽屉导航）
└── Fragment（XML 页面、用户事件、观察 LiveData）
    └── ViewModel（操作 ID、取消、UI 状态）
        ├── AgentRuntimeRepository
        │   └── Agent Core（AgentEngine、Policy、Tools、Session、Memory、Audit）
        └── ModelGatewayRepository
            └── Platform（模型协议、Keystore）
```

主要代码边界:

- `app/`:`Application` + 手工依赖容器 AppContainer(统一创建进程级 Repository + 装配 Memory/Audit/ThreadPool + 内存降级 banner)
- `core/`:纯 Java Agent 领域逻辑(7 个子包)
  - `identity/`:Actor / AgentRequest / CancellationToken / VehicleZone / VehicleStateSource / IntentClassifier / MemoryIntentDetector / ActorUsers
  - `capability/`:CapabilityDefinition / CapabilityRegistry / RiskLevel / ToolDefinition / VerifyMethod / CanonicalSchema
  - `policy/`:PolicyEngine / PolicyDecision
  - `session/`:SessionContext / SessionLockManager / SessionManager
  - `tool/`:ToolExecutor / ToolResult / MockCapabilityProvider / ProviderContext / CapabilityHandler / VerifyStrategy
  - `memory/`:MemoryLayer / MemoryScope / MemoryRouter / MemoryStore / MemoryRecaller / 4 个 source 实现
  - `agent/`:AgentEngine / AgentIteration / AgentOutcome / Trajectory / AgentBudget / ModelGateway / ModelCallExecutor / CancellableModelCall / SteerMailbox / TaskScheduler / DynamicThreadPool / MemoryWriter / EpisodicSummary / ConversationCompressor / AuditRedactor / AuditDigest / AuditEventTypes / SummaryProvider / LlmSummaryProvider
  - `prompt/`:PromptBuilder / PromptSegment / DefaultPromptBuilder
  - `token/`:Tokenizer / JtokkitTokenizer / CharFallbackTokenizer
- `platform/`:Android 存储与外部模型协议适配(AndroidKeyStoreMasterKeyProvider / KeystoreHmacAuditDigest / ModelApiClient / LlmModelGateway / RetryPolicy / SecureModelConfigStore / SharedPreferencesMemoryStore / ModelProviderPreset)
- `data/`:持久化层
  - `db/`:MatrixDatabase / 4 张 Entity / 4 个 DAO / TrajectoryCodec
  - `audit/`:AuditRepository / RoomAuditRepository / NoopAuditRepository / AuditEventRecorder / ClearOutcome
  - `memory/`:RoomMemoryStore / RoomMemoryWriter / RoomMemoryMigrator / EpisodicMemorySourceImpl / SemanticMemorySourceImpl
- `presentation/state`:不可变 AgentUiState / ModelUiState(含 memoryDegraded banner)
- `presentation/viewmodel`:AgentTestViewModel / ModelApiViewModel / MatrixViewModelFactory
- `presentation/ui`:AgentTestFragment / ModelApiFragment
- `MainActivity`:DrawerLayout + 页面切换

## 安全模型与硬约束

MatrixAgent 借鉴 Hermes Agent 的成熟 Agent Runtime 设计,**不直接移植** Python 代码,也不照搬面向个人电脑的 Shell、浏览器和文件操作能力。模型始终是不可信的决策建议者;能力暴露、参数校验、权限判断、执行和结果验证必须由本地 Runtime 控制。

### 不能妥协的硬约束

- **不接受 fire-and-forget 车控调用**:所有写操作必须有完成回执和可信状态回读。ToolResult 返回 success 只代表"命令已发",不保证 ECU 已执行 + 总线信号已生效。可接受的命令信封模式:`commandId → accepted → executing → completed/failed → trusted state readback`,具备幂等键、超时、回调、审计关联。安全责任可以分层(Agent Runtime / 厂商 Service / ECU),**不能消失**。
- **不引入任意外部 memory provider**:限制为厂商白名单 memory provider + 设备本地存储,否则有用户对话数据泄露风险。
- **不向量产车机模型暴露**通用 Shell、任意文件访问、代码执行、浏览器自动化或远程终端。
- **不允许模型自行决定**跳过确认、Policy、参数验证、状态回读或审计。
- **不把"执行成功"的自然语言当作车辆状态**:真实结果必须来自 Car API、AIDL、应用标准接口或可信 Provider 的回读。
- **不允许 Agent 自动创建并立即执行拥有新权限的 Skill**:Skill 必须声明式、可审计、签名,且只能引用已有 Capability。
- **不允许从互联网动态下载插件后直接注册 Tool**:所有扩展必须经过厂商白名单、签名、权限和版本兼容检查。
- **不在首期引入多 Agent 并行车控**:避免并发写入、优先级反转和责任边界不清。
- **审计数据绝不静默落明文**:SQLCipher / Keystore 装配失败 → NoopAuditRepository 显式退化,不静默回退明文 DB。
- **写入路径遵循 fail-closed**:epoch 行查询失败 / DAO 异常 / 事务异常 → 拒绝写入,绝不把"读取失败"伪装成 epoch=0 让陈旧写入通过。
- **系统 epoch 行不存在与查询失败不能用同一个 0L 表示**:row null 是合法初始 epoch=0,DAO 异常必须直接拒绝写入。
- **长期记忆仅由用户决定**:`memory.semantic.save` 在 PolicyEngine 与 Handler 双层 gate,未明确许可(memorySaveAllowed=false)→ CAPABILITY_REJECTED;否定短语("不要记住 X")短路返回 false。
- **不把用户可控内容称为 trusted_memory**:投影走 allowlist + 不可信 key 整层 deny value,标签改用中性的 `<memory_context>`。

### Hermes 借鉴路线(当前状态)

| Hermes 的能力 | MatrixAgent 当前状态 |
|---|---|
| 迭代式 Agent Loop | **已落地** |
| 工具结果驱动的纠错(Policy 二分) | **已落地** |
| 完整运行轨迹 Trajectory + 默认脱敏 | **已落地**(Room 持久化 + HMAC 摘要 + WAL) |
| 同 session 内 steer 与 cancel | **已落地**(SteerMailbox + Cancel 安全边界 + CancellableModelCall) |
| 主驾优先 TaskScheduler | **已落地**(纯 Java Core,readOnlyHint 抢占语义) |
| 上下文管理与自动压缩 | **已落地**(LLM 摘要 + heuristic 降级 + structured fact 保留) |
| 分层、可插拔记忆 | **已落地**(Working/Episodic/Semantic/Preference 四层 + 双维度隔离 + Room 持久化) |
| 会话历史持久化 | **已落地**(已结束会话 + Room/WAL + 加密) |
| Prompt Builder 模块化组装 | **已落地**(PromptSegment + 白名单投影) |
| 多模型 Provider 适配 + 错误分类 | **已落地**(Anthropic/OpenAI/Gemini 原生 + 错误分类 + 受控重试) |
| 统一 Tool Registry 与 Toolset 分组 | **部分落地**(静态 Registry + CanonicalSchema;Toolset 分组留后续) |
| Skills 作为过程性知识 | 计划中(声明式签名 Automotive Skill) |
| 插件化扩展与 MCP 接入 | 计划中(厂商白名单 + 签名 + 最小权限) |
| 子任务分解与并行执行 | 不做(车控写操作保持单一 Orchestrator 串行仲裁) |
| 未完成任务恢复 | 不做(车控写操作必须用户重新确认 + 重新规划,R3 永不恢复) |

## 模型 API 页面

内置可编辑预设:

- 智谱 GLM
- DeepSeek
- 阿里通义千问
- Moonshot Kimi
- 火山方舟 / 豆包
- Anthropic Claude
- Google Gemini
- 本地 Ollama
- 本地 LM Studio
- 本地 vLLM
- 自定义 OpenAI-Compatible 服务

每个预设都允许修改完整 Endpoint 和 Model,因此模型升级或私有网关不需要修改代码。模拟器访问电脑上的 Ollama / LM Studio / vLLM 时使用 `10.0.2.2`;真机使用电脑局域网 IP。

点击"保存并应用"后,Agent 使用 LlmModelGateway 走原生 Tool Calling 或 JSON Compatibility(由 PlannerMode 显式选择)。模型异常、协议不支持或响应不合法时任务明确失败,不会自动执行 DemoModelGateway。无论使用哪种规划模式,计划仍必须经过本地 Policy Engine,模型不能绕过 R3 禁止能力、区域权限和参数校验。

> 当前 APK 内保存 API Key 的方式只适用于开发验证。Keystore 可以保护静态存储,但不能阻止拥有设备控制权的人在运行时提取请求。量产 AAOS 应通过厂商 Model Gateway、短期令牌或设备身份认证访问云端,不能把厂商长期主密钥下发到 APK。

## 网络安全配置

- release 默认 HTTPS,`usesCleartextTraffic="false"`
- `<domain-config cleartextTrafficPermitted="true">` 仅放行 loopback(`127.0.0.1` / `localhost` / `10.0.2.2`)
- `<debug-overrides cleartextTrafficPermitted="true">` 让 debug 真正完全放开明文 HTTP(仅 debug 构建生效)

杜绝 release 局域网嗅探 API Key + 用户原文;debug 仍可用本地 Ollama / LM Studio / vLLM 调试。

## 构建

项目要求:

- JDK 17 或更新版本
- Android SDK 36
- 首次构建可以访问 Gradle 和 Google Maven 仓库

在项目目录执行:

```bash
./gradlew testDebugUnitTest assembleDebug
```

APK 输出位置:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 安装运行

启动 Android 模拟器后执行:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.matrix.agent/.MainActivity
```

## 模拟器演示

应用内置以下快速测试:

- `把主驾温度调到24度,然后导航回家`(多步任务)
- `副驾也一样`(上下文)
- `记住我喜欢24度`(持久记忆)
- `我喜欢多少度`(偏好召回)
- `打开ADAS`(安全拒绝 R3)
- `把空调调到80度`(参数拒绝)
- `空调调到23度,然后导航到失败测试点`(部分失败)
- `记住我对花生过敏`(显式语义记忆,memorySaveAllowed gate)
- `不要记住我女儿的名字`(否定短语门)
- 切 gear Spinner 到 D(触发 PARKED_ONLY 拒绝,无需 adb 改 mock state)

## 代码边界与替换计划

### Core 边界

`core/` 是纯 Java 领域逻辑,不依赖 Android API,可独立单测。**"不依赖 Android"不等于"API 稳定"**——各模块随设计演进仍在调整,不要把现在的接口签名当成稳定契约。

相对稳定(语义不变,实现细节会优化):`PolicyEngine` / `VehicleZone` / `CapabilityDefinition` / `RiskLevel` / `CancellationToken` / `MemoryScope` / `MemoryLayer`。

### 后续要替换的实现

当前形态用 Mock / 占位实现的部分,接入真实 AAOS 时需要替换:

- `MockCapabilityProvider` → 厂商 Car API Provider(AIDL/Binder/厂商 SDK)
- `DefaultVehicleStateSource`(占位)→ `CarPropertyManager` 真实车辆状态
- `MockVehicleStateSource`(demo)→ 真实车信号
- `MatrixAgentApplication` daemon Thread → CarLifecycleListener
- Activity 文本入口 → CarVoiceAssistant AIDL 入口
- Demo 主副驾枚举 → Android User / OccupantZone / Display / Audio Zone
- `DemoModelGateway` → 厂商 Model Gateway(短期令牌 / 设备身份认证)
- `ModelApiClient` HttpURLConnection → OkHttp(传输级 abort + 流式 SSE)
- Activity 文本入口 → 抽屉式侧边栏 + 模型 API 配置 + Gateway 切换(已落地)+ 抽屉顶部 gear Spinner(已落地)

## 后续计划(Backlog)

按主题归类,不再绑定具体版本号(版本号绑定的 roadmap 已被多次评审推迟打乱,改为按主题更稳定)。

### AAOS 真实集成

- `CarPropertyManager` 替换 `MockVehicleStateSource` / `DefaultVehicleStateSource`
- `OccupantZone` 与 `CarOccupantZoneManager` 映射;副驾 zone 推断规则(默认 DRIVER_SEAT)
- 真实 AIDL/Binder Provider + 传输级 cancel 契约(Provider `isAbortable()` / `abortIfSupported` / `queryCommandState` 真实现)
- `USER_CONFIRM` / `TIMEOUT` verifyMethod 真实现(接 ASR / Provider 超时)
- `CarLifecycleListener` 替换 Application daemon Thread
- AAOS 多 session 注入

### Memory 演进

- on-device embedding 替代关键词匹配(TFLite MiniLM,需独立模型文件 + ABI 适配)
- `memory.semantic.get` schema 收紧 + 复杂查询(score 排序 / 模糊匹配)
- namespace schema-driven(切 capability schema `param.allowedNamespaces`,替代硬编码 4 个 namespace)
- Schema-driven PII detection(切 capability schema `param.isSensitive()`,`SensitiveKeys` 类届时可移除)
- EpisodicSummary.resultTags 标准化标签(如 `navigation_started` / `climate_adjusted`)
- MemoryHealthMonitor 独立类(降级原因 / 恢复事件流)
- MemoryWriter.writeEpisodicOnTerminal 异步队列(与 AuditEventRecorder 同模式)

### Provider 协议

- 流式响应 SSE(OkHttp EventSource + StreamHandler + Loop 改造)
- AgentEngine 主路径 token 真切换(jtokkit 已落地,AgentBudget 仍用 char/4 估算;需先跑 TokenizerAccuracyTest 对比真实 Provider usage)
- 错误分类细化 + 受控重试真实场景验证
- Anthropic / OpenAI / Gemini 之外的 Provider 协议(视厂商需求)

### Memory 访问域 enforcement

- `CallerContext` enforcement(`MemoryWriter.writeSemantic / readSemantic` 收 `CallerContext` 入参而非 `userId/zone` 字符串,防第三方 Provider 误传串数据)
- `ProviderContext` 受控 semantic 访问方法(`saveSemantic` / `readSemantic`),`getMemoryWriter()` 收紧 package-private
- preflight LLM 预分类(LlmIntentClassifier + LlmMemoryIntentDetector)接 `CancellationToken` + 总 deadline 透传
- LlmMemoryIntentDetector 三态 ALLOW / DENY / UNCERTAIN(UNCERTAIN 退 Keyword 或要求用户确认)

### 工程化与历史包袱清理

- Room schema migration(`trajectoryJson` 列改 `summaryJson` 等正式迁移)
- 跨进程 epoch 一致性 androidTest(真实多进程场景)
- UTF-8 字节上限评估(当前 2048 是 Java char/UTF-16 code unit,中文 UTF-8 字节会更多)
- `SYSTEM_BUSY` audit event 类型(接入监控面板时新增细粒度 event)
- 删除剩余 V0.4.x `@Deprecated` API:`TaskPlan` / `AgentOutcome.getResults()` / `CapabilityDefinition.Builder.verificationRequired(boolean)`(grep 验证零引用后才删)
- AgentBudget 旧 char-based 字段彻底删除(token 主路径真切换后)
- `PromptComposer` 抽象(目前 `DefaultPromptBuilder.join` 简单实现)
- SessionContext 结构化改造(目前 `Deque<String>`)
- `master_key` + `audit_hmac_key` 别名迁移(目前直接派生)
- master_key 别名迁移路径(切 Keystore 派生 key)
- 跨进程 audit 真集成测试
