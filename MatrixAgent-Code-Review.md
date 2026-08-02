# MatrixAgent 代码审查与演进记录

> **审查范围**：`~/Desktop/Learn/AI/MatrixAgent`
> **当前版本**：V0.4.0（含 MTPLX/本地模型联调）
> **审查历史**：
> - 2026-08-02 V0.2 审查 → 列出 16 条 P0/P1/P2 问题
> - 2026-08-02 V0.3 重构完成 → 7 条完成、5 条部分完成、1 条待办、3 条不采用
> - 2026-08-02 V0.3.1 稳定性复核 → 修复 session 锁竞态、强制超时、Session 回收和参数精度
> - 2026-08-02 V0.3.2 → 完成通用 ToolDefinition 与 OpenAI-Compatible 原生 Tool Calling
> - 2026-08-02 V0.4.0 Agent Loop + Core 拆包 → 7 子包、Loop 主体、Policy 二分、Trajectory+Redactor、三层预算、Anthropic 原生 Tool Calling、ModelGateway 抽象
> - 2026-08-02 V0.4.0 联调增量 → 全量日志、API Key 眼睛切换、Provider 草稿缓存、超时三层放宽、MemoryStore 注入 LlmPlanner、Capability description 改写
>
> 本文档同时作为审查观点存档和后续量产准入项跟踪。

---

## 一、V0.2 → V0.3 处理结果

状态分为「已修改 / 部分修改 / 暂未修改 / 不同意」。

| # | 状态 | 实际处理 |
|---|---|---|
| 1 | 已修改 | 删除 `PolicyEngine.PROHIBITED`；R3、参数规则、区域规则统一来自 `CapabilityRegistry/CapabilityDefinition`；新增 `VehicleZone` enum,大小写统一解析,缺失或非法 Zone 默认拒绝。 |
| 2 | 部分修改 | CapabilityDefinition 已加 description、idempotent、timeout、maxRetries、verificationRequired、targetZoneRequired、allowedTargetZones、validator。timeout 和 verificationRequired 已进入执行链；数值 Validator 会拒绝小数截断。仍缺完整 JSON Schema、required vehicle states 和 VerifyMethod。 |
| 3 | 已修改 | 删除 AgentEngine 全局 `synchronized`，使用引用计数的 `SessionLockManager`。等待者和持锁者都计入引用，只有引用归零才移除 Lock，避免旧 Lock 删除竞态；不同 session 可并行。调温上下文更新迁入 `DefaultContextUpdater`。主驾优先调度器仍未实现。 |
| 4 | 已修改 | AgentRequest 已加 sessionId、occupantZone、displayId、audioZoneId、inputSource、languageTag、asrConfidence、deadline 和 CancellationToken，并校验 ID、语言和置信度。`PlannerExecutor` 按请求 Deadline 限制等待；保留兼容 Demo 的简化构造器。 |
| 5 | 已修改 | MockCapabilityProvider 使用 `commandedState` 与 `observedState` 两份状态，并增加回读故障注入；状态容器改为并发结构，不再用方法级 synchronized 串行全部 Tool。它仍然只是流程 Mock，不代表真实 VHAL 回读。 |
| 6 | 部分修改 | 已完成 provider-neutral ToolDefinition、Registry 自动 Schema、OpenAI-Compatible tools 请求和 tool_calls 解析。Structured JSON 只作为显式兼容模式；Anthropic/Gemini/Ollama 原生工具格式与本地约束生成仍待实现。 |
| 7 | 部分修改 | connect/read timeout 改为 3s/8s，连接放入 finally 释放；请求体写出后、等待响应前检查线程取消。当前仍是 best-effort cancellation，阻塞中的网络 IO 主要依靠 read timeout，不宣称已经实现主动中断。暂不做固定 3 次重试。 |
| 8 | 已修改 | 删除 MatrixAgentRepository,拆为 `AgentRuntimeRepository` 与 `ModelGatewayRepository`。Memory/Session 保持 Runtime 内部能力,不单独暴露成 UI Repository。 |
| 9 | 部分修改 | Agent ViewModel 使用双线程、Future + CancellationToken + operationId；新请求取消旧请求，旧结果不能覆盖新结果。Core 的 Planner/Tool 均有有界等待，不同 session 可并行。HTTP 阻塞阶段仍依赖 8s timeout，主驾优先队列留待 Java TaskScheduler。 |
| 10 | 已修改 | AppContainer 现在是明确 Composition Root,负责 Registry、Policy、SessionManager、Provider、Store、Client、两个 Repository 和 AgentEngineFactory 的组装。当前无需引入 Hilt。 |
| 11 | 已修改 | showState 会保留 loading;clear/new execute 会取消并使旧 operation 失效;所有异步结果按 operationId 提交。未采用 MediatorLiveData,因为 reducer 本身不能解决旧任务回写问题。 |
| 12 | 暂未修改 | 同意量产需要结构化审计持久化,但保存范围、脱敏、用户权限和保留期限需按厂商合规要求确定,不能直接写死 30 天。 |
| 13 | 不同意原描述 | CapabilityRegistry 原本就有 runtime `register()`;硬编码的是 Demo 默认集合。普通 APK 通过 AIDL 动态注册车控能力存在更大安全面,必须先设计签名、白名单和风险等级治理。 |
| 14 | 不同意 | 当前实现明确使用 AndroidKeyStore 生成 AES/GCM Key,不存在硬编码 Master Key(`SecureModelConfigStore.getOrCreateKey()`)。后续仍需处理 Key 失效、短期 Token 和厂商 Gateway。 |
| 15 | 部分修改 | 测试由 6 个增加到 24 个；除 Core 并发/安全测试外，新增 tools 请求 Schema、安全函数名映射、tool_calls 解析、未知工具拒绝、无 tool_calls 拒绝和协议限制契约测试。真实网络断开测试仍待补充。 |
| 16 | 不同意大部分描述 | DemoPlanner 文件头原本已经注明只用于模拟器,温度也通过 `NUMBER_PATTERN` 数字正则解析,并非只硬编码 24;`resolveZone` 也会解析"主驾/副驾"。确实仍不支持"二十四"等中文数字,但属于 Demo 能力边界。 |

---

## 二、本次额外修复(超出原清单)

- 原来全进程只有一个 SessionContext,会导致主驾和副驾上下文混用;现改为 `SessionManager` 按 sessionId 隔离。
- LLM 网络失败或 JSON 错误原来会静默降级并执行 DemoPlanner;现改为明确规划失败,用户需要主动选择离线 Planner。
- AgentEngine 现在捕获 Planner/Provider 异常并生成可追踪的失败结果,不再让运行时异常直接穿透 UI。
- README、应用版本和架构说明已同步到 V0.3.1。

### V0.3.1 稳定性复核新增修改

- 修复 `AgentEngine` 原 Lock 删除竞态：旧实现可能在任务 B 已取得旧 Lock 后被任务 A 从 Map 删除，使任务 C 为同一 session 创建新 Lock。现使用引用计数 `SessionLockManager`，等待、持锁和到达任务共享同一 LockEntry。
- 新增 `PlannerExecutor`：调用方最多等待请求剩余 Deadline；超时后 Outcome 返回 TIMED_OUT。
- 新增 `ToolExecutor`：以 `min(capability.timeoutMillis, request.remainingMillis)` 为预算，超时取消 Future 并返回 ToolResult.TIMED_OUT。
- `targetZoneRequired` 成为显式 Capability 元数据，不再通过“allowed zones 是否含 GLOBAL”间接推断参数是否必填。
- 数值 Validator 不再直接 `intValue()`；NaN、Infinity 和非整数会被拒绝，避免 30.9 被截断成 30。
- SessionManager 改为访问顺序存储，默认空闲 TTL 30 分钟、最多 32 个 session，防止长期运行进程无限增长。
- Mock Provider 使用 ConcurrentHashMap 与 AtomicBoolean，去掉全方法 synchronized；是否并行最终仍由具体 Provider 实现决定。
- 新增同 session 40 任务竞争、锁引用清理、Tool timeout、数值精度、Session TTL/容量测试，总计 18 个 Core 测试。
- 版本更新为 V0.3.1。

### V0.3.2 Tool Calling 新增修改

- 新增纯 Java `ToolDefinition` / `ToolParameterDefinition`，表示与模型厂商无关的函数名、描述、类型、required、enum 和数值范围。
- CapabilityDefinition 的参数 Schema 与 Policy 定义放在同一个 Registry 装配点；`toToolDefinitions()` 只导出非 R3 能力。
- canonical capability 含点号，不一定满足模型函数命名约束，因此生成 provider-safe modelName；模型响应后必须通过已发送定义映射回来，不能信任任意函数名。
- ModelApiClient 对 OpenAI-Compatible 发送 `tools` 和 `tool_choice=auto`，解析 `message.tool_calls`。
- 模型返回未知 Tool 或没有 tool_calls 时明确失败；不会静默执行 Structured JSON 或 DemoPlanner。
- 新增 PlannerMode：`NATIVE_TOOL_CALLING` 与 `STRUCTURED_JSON_COMPATIBILITY`。模式由用户显式选择并加密持久化。
- V0.3.2 的 Native 模式只允许 OpenAI-Compatible；Anthropic、Gemini、Ollama 选择 Native 时在配置校验阶段拒绝。
- 新增 6 个离线协议契约测试，总测试数 24。

### V0.4.0 Agent Loop + Core 拆包新增修改

**Core 按职责拆 7 子包**（identity / capability / policy / session / tool / memory / agent），机械移动 + import 改写，无逻辑变化，作为后续版本演进的边界。

**Agent Loop 主体**（`core/agent/AgentEngine.executeLocked`）：
- 单次 `execute` 内多轮 `LLM → Tool Call → Policy → Execute → Observation → LLM`，取代 V0.3.2 的「一次性 Planner → TaskPlan → 顺序执行」
- 每轮检查 5 个终止条件：cancel / deadline / 输入字符预算 / 模型无 tool_call / tool call 累计上限
- 能力级拒绝累计：同一 capability 被 `denyCapability` 拒后，本轮后续 + 后续迭代都不能再用（防模型绕）
- 参数级拒绝：observation 回传，模型可换参数重试，权限不放宽
- Tool 异常 → ToolExecutor catch 成 `EXECUTION_FAILED`，直接当 Observation 回传，不抛循环

**Policy 二分**（`core/policy/PolicyDecision`）：
- 新增 `RejectionType { CAPABILITY, PARAMETER }`
- `denyCapability(reason)`：R3/zone/capability 未注册，不可上诉
- `denyParameter(reason)`：参数缺失/越界/格式错，模型可重试
- AgentEngine 按 `RejectionType` 决定是否累计到 `blockedCapabilities`

**ModelGateway 抽象（取代 Planner 进入循环）**：
- `ModelGateway.decide(ModelTurnRequest) → ModelTurn`
- `ModelTurn`：`{assistantMessage, toolCalls, finishReason, tokenEstimateChars}`
- `LlmModelGateway` 路由：`NATIVE_TOOL_CALLING + ANTHROPIC_MESSAGES → callAnthropicWithTools`，其余走 `legacyDecide`（保留 V0.3.2 的 `LlmPlanner` 单轮）
- `DemoModelGateway` 取代 `DemoPlanner`：每轮返回 1 个 tool_call，跑完返回 `FinishReason.STOP`
- `ModelCallExecutor`：包 deadline + cancel 的 polling 模式，沿用 V0.3.1 设计

**Anthropic 原生 Tool Calling**（参考 Hermes `anthropic_adapter.py`）：
- `ModelApiClient.callAnthropicWithTools`：tool_use / tool_result 块协议
- assistant message 带 `content: [{type: "tool_use", id, name, input}]`
- tool 结果用 `{role: "user", content: [{type: "tool_result", tool_use_id, content}]}`
- 连续 tool_result 合并到同一 user message
- Tool Call ID 由模型生成、Runtime 透传

**三层预算**（`core/agent/AgentBudget`）：
- `maxIterations=8` / `maxToolCalls=8` / `totalDeadlineMillis=60_000`（V0.4.0 默认 8s，联调时调到 60s）
- `maxMessageChars=8_000` / `totalInputChars=32_000`
- 单 Observation 超 `maxMessageChars` 自动截断 + `[truncated N chars]`

**Trajectory + 默认脱敏**：
- `Trajectory { iterations, stopReason, startedAtMillis, durationMillis, totalToolCalls }`
- `AgentIteration`：iteration 序号 / assistantMsg / toolCalls / observations / policyDecisions / 耗时
- `Redactor` 在落 `AgentOutcome.trajectory` 前过：API key / Bearer / `sk-*` / `sk-ant-*` / `AIza*` / `ghp_*` 正则；`preferred_*` value → `<memory>`；超长截断
- UI 直接渲染脱敏后版本

**StopReason 枚举**：DONE / NO_TOOL_CALL / MAX_ITERATIONS / MAX_TOOL_CALLS / TIMEOUT / CANCELLED / POLICY_HALT / BUDGET_EXHAUSTED

**TraceEvent / TaskPlan / Planner / PlannerExecutor 标 `@Deprecated`**：保留以兼容过渡，V0.4.0 完成后逐步删除。

**测试**：新增 9 个 V0.4.0 专属测试（Loop 终止、Policy 二分、Trajectory 结构、Redactor 脱敏、Anthropic 契约、Tool 异常回传），总测试数 24 → 42。

### V0.4.0 联调期增量改动

V0.4.0 主体完成后，本地模型（MTPLX 跑 Qwen3.36-35B-A3B）联调期间发现并修复的实战问题：

**全量日志系统**：17 个核心类统一 TAG `"MatrixAgent"`，分级 `Log.i/d/w/e`，前缀 `[Component]`（如 `[Engine]`、`[LlmPlanner]`、`[Http]`、`[Provider]`、`[ConfigStore]`、`[Lock]`、`[Session]`、`[Policy]`、`[Tool]`、`[ModelCall]`、`[LlmGateway]`、`[Context]`、`[Redact]`、`[App]`、`[Repo]`、`[ViewModel]`、`[LlmPlanner]`）。HTTP 层记录 `POST payloadBytes/auth/costMs/respBytes/HTTP code`，ModelCall 层记录 `budgetMs/costMs/terminal`，Tool 层记录 `budgetMs/status/verified/durationMs`。`app/build.gradle` 加 `testOptions.unitTests.returnDefaultValues = true` 让 JUnit 不因 `android.util.Log` 崩溃。

**API Key 输入栏眼睛切换**（`ModelApiFragment`）：
- 新增 `ic_eye_visible.xml` / `ic_eye_hidden.xml` 两个 vector drawable
- `key_input` EditText 与 `key_visibility_toggle` ImageButton 并排
- `toggleKeyVisibility()`：判断 `InputType.TYPE_TEXT_VARIATION_PASSWORD`，切到 `VISIBLE_PASSWORD` 反之亦然，同步换图标，保留光标位置

**Provider 切换草稿缓存**（`ModelApiViewModel.drafts: Map<String, ModelConfig>`）：
- 切 provider 前把当前表单存为草稿
- 切回时优先用草稿，没草稿才用 preset 默认值
- 草稿只 Fragment 作用域内存（不持久化敏感 API Key）
- ViewModel 构造时从已保存 config 播种草稿

**超时三层一起放宽**：联调发现 `AgentRequest.Builder.timeoutMillis` 默认 8s 被 `AgentRuntimeRepository` 显式覆盖回 8s；同时 `AgentBudget.DEFAULT_TOTAL_DEADLINE_MILLIS` 也是 8s；`ModelApiClient.READ_TIMEOUT_MS = 8_000` 与 agent deadline 相等会互相抢断。统一改为 `AgentRequest 60s + AgentBudget 60s + ModelApiClient CONNECT 10s / READ 90s`，让 HTTP 比 agent deadline 长 30s 避免 agent 砍 future 时 HTTP 还没自己超时。

**MemoryStore 注入 LlmPlanner 解决 key 命名漂移**：
- 现象：模型 save 时脑补 `key=preferred_temperature`，get 时脑补 `key=温度`，KV lookup 必然 not found
- 修复：`AppContainer → ModelGatewayRepository → LlmModelGateway → LlmPlanner` 注入 `MemoryStore`
- `LlmPlanner.plan` 调 `memoryStore.getAllPreferences(userId).keySet()`，把已保存 key 列表拼进 userPrompt：`已保存的偏好 key 列表=查询时必须使用这些精确字符串之一:[temperature]`
- 只暴露 key，不暴露 value（避免 `preferred_*` 敏感值进 prompt）
- 让 save 行为成为事实来源，get 强制对齐——模型不需要遵守 description 里的命名约定

**Capability description 改写**（`CapabilityRegistry`）：
- `memory.preference.save/get`：从「保存/读取用户偏好」改写为「保存/读取用户之前**明确告诉系统要记住**的偏好或个人设置（例：『记住我喜欢24度』『我家在哪』）」+ 列出常用 key 约定
- `knowledge.answer`：从「回答不需要执行车辆操作的问题」改写为「**与车辆状态、用户个人偏好都无关**的常识性问题（例：『今天几号』『水的沸点』）。**不要**用此能力查询用户偏好或车辆状态——那些必须用 memory.preference.get 或 vehicle.info.\* 系列」
- 直接根因：原 description 太宽泛，模型把「我喜欢多少度」误判成通用问答调了 `knowledge.answer`（mock 占位）而非 `memory.preference.get`

### V0.4.0 自审 Bug 修复（代码 review 发现）

V0.4.0 实现完成后做了一轮代码自审，发现 2 个会影响正确性的真实 Bug，已修复并补回归测试。

**Bug 1：PolicyEngine 把「副驾越权写操作」归错类（CAPABILITY → PARAMETER）**

- 位置：`core/policy/PolicyEngine.java:61-66`
- 现象：副驾选错 `zone=DRIVER` 调写操作 capability 时，原代码返回 `denyCapability`。但 `AgentEngine.blockedCapabilities` 按 capability **名**累积——同 capability 名（如 `vehicle.climate.set_temperature`）后续在副驾自己的 `zone=passenger` 上合法调用也会被一并 ban，导致副驾一旦选错 zone 就再也不能调自己 zone 的同能力。
- 修复：归 `denyParameter`。副驾用 `zone=passenger` 重试后该能力合法——按 capability 名 ban 会误杀合法调用。注释里写清楚归类依据，避免后人改回 CAPABILITY。
- 回归测试：`AgentEngineTest.passengerCanRetryWithCorrectZoneAfterParameterRejection`——副驾第一次 `zone=DRIVER` 拒绝、第二次 `zone=passenger` 成功，并断言第一个 observation 的 `isCapabilityBlocked() == false`。

**Bug 2：Redactor `preferred_*` 正则在真实模型输出中几乎匹配不到**

- 位置：`core/agent/Redactor.java`
- 现象：早期 `MEMORY_VALUE_PATTERN` 假设 memory observation 的 key 带 `preferred_` 前缀（如 `preferred_temperature=24 → preferred_temperature=<memory>`）。但模型自由命名 key（联调实测有 `temperature` / `home` / `preferred_temperature` 等），前缀假设不成立——脱敏实际没生效。
- 修复：改为按 **capability 名**做全脱敏：
  - `FULL_REDACT_CAPABILITIES = {memory.preference.save, memory.preference.get}`
  - `redactMemoryObservation()`：把 observedMap 所有 value 替换为 `<memory>`，message 替换为 `MEMORY_MESSAGE_PLACEHOLDER`（公开常量，UI 渲染也复用）
  - 不再依赖 key 命名前缀
- 同步分离 Trajectory 与 conversation 的 observation 处理：
  - `AgentEngine` 第 263-274 行原代码对 `redactedForOutput` 既喂回 conversation 又写入 Trajectory
  - 修复后 Trajectory 持有**原始** observation（UI 给用户看自己的偏好/memory 不是隐私），conversation 用**脱敏**版本（防 API Key / Bearer / memory value 漂移到 LLM 上下文）
  - 注释明确「Redactor 只用于喂回 LLM 的 conversation」
- 回归测试：
  - `RedactorTest.redactsAllValuesWhenCapabilityIsMemoryPreference`——preferred_temperature / temperature / home 三个不同 key 命名都被脱敏
  - `RedactorTest.nonMemoryCapabilityKeepsObservedValuesIntact`——vehicle.climate 的 `driver.temperature=24` 是车辆状态不是用户偏好，保持原值
  - `RedactorTest.redactsRejectionReasonPreservingCapabilityBlockedFlag`——Policy 拒绝路径也走 secret mask + 截断
  - `AgentEngineTest.trajectoryHoldsRawObservationWhileConversationIsRedacted`——通过 AtomicReference 捕获 ModelGateway 第二轮 decide 收到的 tool message content，验证 Trajectory 有原始 "24℃" 而 conversation 看到的是 `[user memory preference redacted]` + `<memory>`

**测试套件**：本次修复后 `./gradlew testDebugUnitTest` 46 个测试全绿（AgentEngineTest 27 + RedactorTest 9 + ModelApiClientContractTest 10）。

### V0.4.0 第二轮复审 P1 修复（数据边界 + 状态正确性）

第二轮只读复审（`MatrixAgent-V0.4.0-Code-Review.md`）发现 4 个 P1 仍存在，且其中 P1-2 的修复方向被指反了——「Trajectory 持原始 observation、conversation 用脱敏版本」会让模型答不出「我喜欢多少度」，且「用户看自己的偏好不是隐私」在 AAOS 不是通用假设（主驾副驾可能共用 Android User）。本轮按正确方向重做：

**P1-2 反转数据边界：拆 ModelSanitizer + AuditRedactor**

- 删除 `Redactor.java`，按数据边界拆两个独立组件：
  - `ModelSanitizer`（喂 conversation）：**只脱凭据**（API Key / Bearer / `sk-*` / `sk-ant-*` / `AIza*` / `ghp_*`），保留 `memory.preference.*` 真实 value——模型需要这些语义才能回答「我喜欢多少度」或继续后续 Tool
  - `AuditRedactor`（喂 Trajectory/UI/Log）：**字段级脱敏**——memory preference value → `<memory>`，message → `MEMORY_MESSAGE_PLACEHOLDER`
- `AgentEngine.executeLocked` 反转：conversation 拿 `modelSanitizer.sanitize(observation)` 输出；Trajectory 拿 `auditRedactor.redact(observation)` 输出
- assistant message 也过 `auditRedactAssistant` 后写入 Trajectory（V0.4.0 first pass，P1-4 范围）
- 重写回归测试：`conversationKeepsSemanticValueForModelWhileTrajectoryIsRedacted` 验证 conversation 含真实 "24" + 不含 placeholder，Trajectory 含 `<memory>` placeholder

**P1-3 computeFinalState 按 StopReason 先判**

- 异常终止（`MAX_ITERATIONS / MAX_TOOL_CALLS / BUDGET_EXHAUSTED / POLICY_HALT`）即使有部分成功 Tool 也**不能 SUCCEEDED**，最多 `PARTIALLY_SUCCEEDED`
- 修复前：模型一直调成功查询 Tool 直到耗尽预算，会被错判 SUCCEEDED
- 新增回归测试：`loopTerminatedByMaxIterationsDoesNotReturnSucceeded`、`loopTerminatedByMaxToolCallsDoesNotReturnSucceeded`

**P1-4 ToolCall 加 ArgumentProvenance，Policy 按意图来源路由**

- `ToolCall.ArgumentProvenance` 四值：`USER_EXPLICIT / MODEL_INFERRED / SYSTEM_DEFAULT / CONTEXT_FILLED`，默认 `MODEL_INFERRED`（向后兼容）
- `PolicyEngine` 副驾越权 `zone=DRIVER` 写操作按来源分流：
  - `USER_EXPLICIT`（用户原话明确要求改主驾）：`denyCapability` 不可上诉——不能擅自改 zone=passenger 帮用户执行没要求的动作
  - `MODEL_INFERRED / SYSTEM_DEFAULT / CONTEXT_FILLED`（模型脑补/默认填充）：`denyParameter` 让模型重新推断
- `DemoModelGateway.resolveZone` 重写：检测到「主驾/副驾」关键词 → `USER_EXPLICIT`；按 actor 默认 → `MODEL_INFERRED`
- `ModelApiClient` / `LlmPlanner` 模型 adapter 返回的 ToolCall 默认 `MODEL_INFERRED`（不显式标 provenance）
- 新增回归测试：`passengerExplicitDriverZoneIsCapabilityRejected`（USER_EXPLICIT → CAPABILITY 拒绝）；原 `passengerCanRetryWithCorrectZoneAfterParameterRejection` 改为 MODEL_INFERRED 默认场景

**P1-1 OpenAI-compat 多轮 Tool Calling**：本轮**未修**，保留 V0.4.1 Stage H/I。当前 `LlmModelGateway.legacyDecide` 看到 tool_result 仍 `directAnswer("LLM 兼容路径:单轮规划已执行,任务结束")`，本地模型跨轮依赖场景（「查电量低于 50% 就导航去充电站」）仍跑不动。

**P2 暂未修**：`totalDeadlineMillis` 仍未参与计算；`FinishReason` 仍未接入；`ModelCallExecutor` 异常仍统一 `POLICY_HALT`；`core/` 仍直接 `android.util.Log`。这些进 V0.4.1 范围。

**测试套件**：本次修复后 `./gradlew testDebugUnitTest` **56 个测试全绿**（AgentEngineTest 30 + ModelSanitizerTest 9 + AuditRedactorTest 7 + ModelApiClientContractTest 10）。

**版本同步**：`app/build.gradle` `versionName` 从 `0.3.2` → `0.4.0`，`versionCode` 5 → 6。

---

## 三、量产前准入项

按真实车辆接入前的优先级排序。完成这些项才能离开模拟器。

### A. 模型与规划层

**A1. 原生 Tool Calling 或服务端约束生成**(`platform/LlmPlanner.java`)
OpenAI-Compatible 原生 Tool Calling 已完成；`cleanJson()` 只保留在用户显式选择的兼容模式中。后续：
- Anthropic / Gemini 分别对接 tools / functionDeclarations
- 本地模型(llama.cpp)走 GBNF 或 outlines 风格约束生成
- 对各协议增加录制响应、错误响应和多 Tool 契约测试

**A2. 完整 Capability Schema**(`core/CapabilityDefinition.java`)
当前用 `CapabilityValidator` lambda 做参数验证,已比硬编码 if-else 强,但仍缺:
- `requiredVehicleStates`:前置状态约束(P 挡 / 静止 / 时速 < 5km/h 等)
- `verifyMethod`:写后验证方式枚举(回读信号 / 等待 CAN ACK / 不验证)
- `inputSchema`:可选的 JSON Schema,便于原生 tool calling 直接使用

### B. 调度与执行层

**B1. 主驾高优先级 TaskScheduler**
当前已经具备 per-session 串行、Planner/Tool 有界等待和协作式取消。仍需要:
- 主驾请求优先级 ≥ 副驾
- 系统级 Java TaskScheduler，而不是把调度策略放在页面 ViewModel 线程池
- 可中断的 AIDL/HTTP 调用链；Future.cancel 只能请求中断，不能强杀不响应中断的底层调用
- 超时后迟到的底层调用必须禁止提交状态，真实 Provider 需要 operation/token 校验

**B2. 多步任务依赖与失败传播**
当前 plan 是顺序执行,失败后 continue 而非 abort。需要:
- 显式 DAG / step dependencies
- `continue-on-failure` vs `abort-on-failure` 策略(per-capability)
- 补偿事务(rollback)语义

**B3. 真实 Car API Provider 的独立状态回读**
当前 `MockCapabilityProvider` 把 commandedState / observedState 都放在自己进程内,真实车辆必须:
- 走 VHAL(`CarPropertyManager`)或自有 AIDL 服务
- 读回来源是 CAN 总线信号而非自身缓存
- 回读超时与命令超时分离

### C. 安全与审计

**C1. 结构化、脱敏、可配置保留期的审计存储**
`AgentOutcome.getTrace()` 当前是 in-memory,app 杀进程就丢。量产需要:
- SQLite / Room + WAL 落地
- 保留期由厂商合规策略决定(不写死 30 天)
- 用户隐私数据脱敏(对话内容、位置、偏好)
- 导出权限管控

**C2. HTTPS / 可信本地地址策略**
`ModelApiClient` 当前用 `HttpURLConnection`,不强制 HTTPS。需要:
- 量产默认拒绝明文 HTTP；不能因为属于整个 `192.168.* / 10.*` 网段就自动信任
- 只在显式开发模式放行白名单本地地址，并区分“无密钥本地模型”和“携带凭据的请求”
- 证书 pinning 选项(对接厂商 Gateway)
- 短期 Token 刷新机制(API Key 不直接放在车机)

---

## 四、勘误:之前给错或被反驳的建议

为了避免后续混淆,这里明确记录原审查中"被反驳/给错"的建议。

| 原建议 | 问题 | 修正 |
|---|---|---|
| #9 用 `pending.put(actor.name(), null)` 取消前一个 Future | `ConcurrentHashMap` 禁止 null value,会 NPE | 用 `operationSequence + postIfCurrent(opId)` 拒绝过期回调,加 `activeFuture.cancel(true)` |
| #7 强制 OkHttp + 连接池 + 3 次指数退避 | 重试策略必须受 deadline、错误类型、幂等性约束，不能写死次数 | 当前 3s/8s 只缩短最坏等待，不等于已实现主动取消；后续抽象 CancellableHttpCall，再决定 HttpURLConnection 或其他 Java 客户端 |
| #10 引入 Hilt | 当前 Composition Root 已经清晰,引入 DI 框架属于过度工程 | AppContainer 手动装配,等依赖数量超过阈值再考虑 |
| #11 用 MediatorLiveData + reducer | reducer 不能解决"旧任务回写新状态"的根因问题 | 用 operationId 序列号 + postIfCurrent 拒绝过期回调 |
| #13 CapabilityRegistry 走 AIDL IPC 动态注册 | 普通 APK 动态注册车控能力存在安全面 | CapabilityRegistry 已支持 runtime `register()`;AIDL 开放前必须先做签名/白名单/风险等级治理 |
| #14 加密 key 可能硬编码 | 实际已经 AndroidKeyStore + AES/GCM,无 master key | 后续工作在 Key 失效、短期 Token、厂商 Gateway 方向 |

---

## 五、当前架构(V0.4.0)

```
┌────────────────────────────────────────────────────────────────┐
│ presentation/                                                   │
│   ui/         Fragment (XML)                                    │
│              - ModelApiFragment: provider 切换草稿缓存          │
│              - API Key 眼睛切换 (visible/hidden password)       │
│   viewmodel/  ViewModel + LiveData                              │
│              - ExecutorService(2 或 1)                          │
│              - operationSequence + postIfCurrent 防竞态         │
│              - activeFuture.cancel(true) + CancellationToken    │
│              - drafts: Map<providerId, ModelConfig>             │
│   state/      UiState (immutable)                               │
└──────────────────────┬─────────────────────────────────────────┘
                       │ depends on
┌──────────────────────▼─────────────────────────────────────────┐
│ data/                                                            │
│   AgentRuntimeRepository    (execute / gateway 切换 / 状态查询) │
│      ↳ AgentEngine (Factory 注入 ModelGateway)                  │
│      - timeoutMillis(60_000L) 覆盖默认值                        │
│      - clearUserData 全清 (driver+passenger memory + sessions)  │
│   ModelGatewayRepository    (config CRUD / 连接测试)            │
│      ↳ createModelGateway(config, memoryStore)                  │
│      ↳ ModelApiClient / SecureModelConfigStore                  │
└──────────────────────┬─────────────────────────────────────────┘
                       │ composes
┌──────────────────────▼─────────────────────────────────────────┐
│ core/ (V0.4.0 按 7 子包拆分)                                     │
│                                                                  │
│ core/agent/                                                      │
│   AgentEngine (Loop 主体:5 个终止条件 + blockedCapabilities)    │
│     - 编排:decide → policy → exec → observation → decide …      │
│   ModelGateway / ModelTurn / ModelTurnRequest                   │
│   ModelCallExecutor (deadline + cancel polling)                  │
│   AgentBudget (maxIter=8 / maxCalls=8 / totalDeadline=60s /     │
│                maxMsgChars=8K / totalInputChars=32K)             │
│   Trajectory + AgentIteration + AgentMessage + ToolObservation  │
│   StopReason (DONE/NO_TOOL_CALL/MAX_ITERATIONS/                 │
│               MAX_TOOL_CALLS/TIMEOUT/CANCELLED/                 │
│               POLICY_HALT/BUDGET_EXHAUSTED)                     │
│   FinishReason (NONE/STOP/LENGTH/TOOL_CALLS)                    │
│   Redactor (API key/Bearer/preferred_* 脱敏 + 超长截断)         │
│   DemoModelGateway (取代 DemoPlanner,模拟单步)                  │
│   LlmModelGateway 走 platform/                                   │
│   @Deprecated: Planner/PlannerExecutor/TaskPlan/TraceEvent      │
│                                                                  │
│ core/identity/  AgentRequest/Actor/InputSource/VehicleZone/      │
│                 CancellationToken (timeoutMillis 默认 60s)       │
│ core/capability/ CapabilityRegistry/Definition/Provider/         │
│                  Validator/ToolDefinition/ToolParameterDefinition│
│                  RiskLevel                                       │
│   - description 改写:memory.preference.* vs knowledge.answer    │
│ core/policy/    PolicyEngine + PolicyDecision                    │
│                  RejectionType {CAPABILITY, PARAMETER}           │
│                  denyCapability / denyParameter                  │
│ core/session/   SessionManager + SessionLockManager +            │
│                  SessionContext (引用计数 LockEntry)             │
│ core/tool/      ToolCall / ToolResult / ToolExecutor /           │
│                  MockCapabilityProvider                          │
│ core/memory/    MemoryStore + InMemoryMemoryStore                │
│                                                                  │
└──────────────────────┬─────────────────────────────────────────┘
                       │
┌──────────────────────▼─────────────────────────────────────────┐
│ platform/                                                        │
│   ModelApiClient (HttpURLConnection, connect 10s / read 90s)    │
│     OpenAI:    planWithTools + parseOpenAiToolResponse           │
│     Anthropic: callAnthropicWithTools (tool_use/tool_result 块)  │
│                 + 旧 callAnthropic (Structured JSON)             │
│     Gemini:    callGemini (Structured JSON)                      │
│     Ollama:    callOllama (Structured JSON)                      │
│   LlmModelGateway                                                │
│     - useAnthropicNative = (NATIVE + ANTHROPIC_MESSAGES)         │
│     - legacyDecide: 单轮,看到 tool_result 直接答复(短路径)      │
│   LlmPlanner (legacy,接受 MemoryStore 注入 saved keys)          │
│   PlannerMode (NATIVE_TOOL_CALLING / STRUCTURED_JSON_COMPATIBILITY) │
│   SecureModelConfigStore (AndroidKeyStore + AES/GCM,详细日志)    │
│   SharedPreferencesMemoryStore                                   │
└────────────────────────────────────────────────────────────────┘
```

---

## 六、目标架构(V0.5.0+)

```
presentation/
   XML + Fragment（继续纯 Java）
   Java ViewModel + LiveData
   - per-actor 独立队列 + 主驾优先
   - 取消信号穿透到网络层

data/
   AgentRuntimeRepository    (execute / gateway 切换)
   ModelGatewayRepository    (config CRUD / Token 刷新)
   AuditRepository           (Trajectory 持久化,合规可配置)

core/ (V0.4.0 已拆 7 子包)
   agent/      AgentEngine (per-session lock + deadline budget)
               Java TaskScheduler (PriorityBlockingQueue + priority + preemption)  ← V0.4.1
               SteerMailbox (drainPendingSteer 真实实现)                            ← V0.4.1
   policy/     PolicyEngine (schema-driven + vehicle state precondition)           ← V0.4.2
   capability/ CapabilityRegistry (仅允许可信系统组件受控注册；不向普通 APK 开放)
               CapabilityDefinition (full schema + verifyMethod)                    ← V0.4.2
   tool/       CarApiCapabilityProvider (Car API/厂商 AIDL,真实回读)                ← V0.6.0
   memory/     四层 Memory (会话/偏好/车况/全局)                                     ← V0.5.0
   identity/ session/ (V0.4.0 已稳定)

platform/
   CancellableModelClient (Java Call abstraction + 短期 Token)                       ← V0.4.1
   LlmModelGateway (legacyDecide 单轮 → 多轮 OpenAI/Gemini Tool Calling)              ← V0.4.1
   SecureModelConfigStore (Key 失效恢复)
   TraceLogger (Room/WAL, 脱敏, 厂商保留期)                                          ← V0.5.0
```

---

## 七、设计思考与明确不采用的方案

这一节记录选择背后的工程理由，避免后续再次把已经否决的建议写回路线图。

### 1. 坚持纯 Java，不改 Compose / Coroutine / Flow

项目已经明确要求 Java。Compose 与 Coroutine 会把 UI、异步模型和构建依赖重新带回 Kotlin 生态，与当前约束冲突。后续继续使用 XML、Fragment、Java ViewModel、LiveData、ExecutorService、Future 和 `PriorityBlockingQueue`。如果未来团队主动修改语言约束，再单独评估迁移，不把它伪装成普通架构升级。

### 2. 不用“Lock 永不删除”掩盖竞态

永久保存 Lock 虽然简单安全，但长期运行的车机进程会积累 session key。本次选择引用计数 LockEntry：它比直接 `remove()` 安全，又能在最后一个使用者离开时回收。并发正确性由同 session 40 个任务的竞争测试保护。

### 3. 超时分为“上层按时返回”和“底层真正停止”

PlannerExecutor/ToolExecutor 已保证 Agent Outcome 不无限等待；但 Java 不能安全强杀一个忽略中断的线程。若底层 AIDL、HTTP 或 Car API 不响应取消，它仍可能在后台迟到完成。因此量产 Provider 必须接收 operationId/CancellationToken，并在提交车辆状态前再次确认任务仍有效。文档不把 Future.cancel 描述成真正的底层中断。

### 4. 不固定使用 OkHttp，也不固定重试三次

HTTP 实现是可替换细节，关键接口应是可取消 Call、总 Deadline、响应体上限和错误分类。重试只允许用于明确的暂态错误，并受请求剩余时间和幂等性约束。`idempotent/maxRetries` 目前仍是声明元数据，尚未启用自动重试，避免在策略未完成前误重放写操作。

### 5. 不引入 Hilt

当前 AppContainer 已经是可读、可测试的手工 Composition Root。依赖数量和作用域还没有复杂到需要额外 DI 框架；AAOS Framework、系统 APK 和多进程边界未来也未必适合统一使用 Hilt。

### 6. 不向普通 APK 开放 Capability 动态注册

Registry 支持进程内 register，不代表应该开放无条件 AIDL 注册。后续若需要 OTA 能力扩展，注册者必须是可信系统组件，并校验签名、白名单、Schema 版本和不可降低的风险等级。

### 7. 不自动信任整个私有网段的明文 HTTP

`10.*` 或 `192.168.*` 只表示地址范围，不表示服务可信。开发模式可显式放行 `10.0.2.2` 或指定局域网地址；量产默认 HTTPS，携带厂商凭据的请求尤其不能因为目标是内网就降级为明文。

### 8. Tool Calling 采用逐协议演进

不在一个版本同时重写 OpenAI、Anthropic、Gemini 和所有本地后端。先建立与厂商无关的 ToolDefinition/ToolCallResult 模型，再接 OpenAI-Compatible；随后按相同契约逐个扩展，保留现有文本 JSON 路径作为显式兼容模式，而不是静默 fallback。

---

## 八、原始问题清单(归档,2026-08-02 V0.2 状态)

保留原始问题描述,作为审查观点追溯。「修复方向」已经被第三章准入项取代,这里不再重复。

### P0 — V0.2 核心安全/正确性

1. **PolicyEngine 双数据源 + 硬编码 if-else** — `PROHIBITED` set 与 `riskLevel` 双份维护;zone 用 `String.valueOf` 大小写敏感。
2. **CapabilityDefinition 太薄** — 只有 name/riskLevel/writeOperation 三字段。
3. **AgentEngine synchronized 全局锁 + 业务逻辑泄漏** — 主驾/副驾互阻;调温记忆硬编码在 orchestrator。
4. **AgentRequest 缺关键字段** — 无 sessionId/occupantZone/displayId/audioZone/inputSource/deadline/cancellationToken。
5. **MockCapabilityProvider 自写自读** — 写后立刻读自己的 map,`verified=true` 是谎言。
6. **LlmPlanner JSON 解析代替原生工具调用** — `cleanJson()` 正则剥 markdown,模型少一个 `}` 就崩。
7. **ModelApiClient HttpURLConnection + 60s 超时** — 无连接池/重试/中断,READ_TIMEOUT_MS=60_000 对车机太长。

### P0 — V0.2 MVVM 升级引入的新问题

8. **MatrixAgentRepository 上帝类** — 98 行管 7 个对象,职责横跨 execute / planner / config / 测试 / 状态查询 / 清空。
9. **ViewModel 单线程 Executor + 无取消** — `Executors.newSingleThreadExecutor()`,主副驾排队;连续点击有竞态。
10. **AppContainer 太轻** — 只是 Repository 工厂,没有真正 DI。
11. **LiveData 状态切换丢中间态** — showState/clearData 用 setValue 同步覆盖 loading 状态,可能造成重复提交。

### P1 — 已纳入 V0.4.0—V0.5.0 统一路线

12. **TraceEvent 不持久化** — in-memory,杀进程即丢。
13. **CapabilityRegistry 加载是 static** — 启动时硬编码,OTA 新能力要发版。
14. **SecureModelConfigStore 加密 key 来源** — 是否 Keystore-backed?

### P2 — 体验/工程化

15. **测试主要是 happy path** — 缺并发/取消/超时/边界/网络断开。
16. **DemoPlanner 硬编码关键词** — 中文同义词识别不了。

---

## 九、统一推进路线

以下版本表与 `README.md`、`方案讨论待确认事项.md` 一致。当前版本 `V0.4.0`（含 MTPLX/本地模型联调）。下一架构里程碑 `V0.4.1`：补 OpenAI 多轮 Tool Calling、SteerMailbox、主驾优先调度。

| 阶段 | 时长 | 任务 | 准入项编号 |
|---|---|---|---|
| **V0.3.1** | 已完成 | Lock 竞态、Planner/Tool timeout、Session TTL、精度和并发测试 | B1 基础 |
| **V0.3.2** | 已完成 | 通用 ToolDefinition + OpenAI-Compatible 原生 tool calling | A1 第一阶段 |
| **V0.4.0** | 已完成 | Core 拆 7 子包、Agent Loop、Observation 回传、三层预算、内存 Trajectory/默认脱敏、Anthropic 原生 Tool Calling、Policy 二分、ModelGateway 抽象、42 个测试 | Agent Runtime 基础 |
| **V0.4.0 联调** | 已完成 | 全量日志、API Key 眼睛切换、Provider 草稿缓存、超时三层放宽、MemoryStore 注入 LlmPlanner、Capability description 改写 | 调试可见性 |
| **V0.4.1** | 1-2 周 | OpenAI/Gemini 多轮 Tool Calling（删除 legacyDecide 单轮短路径）、SteerMailbox、cancel 安全边界、主驾优先 TaskScheduler、CancellableModelCall | B1 |
| **V0.4.2** | 1-2 周 | 完整 JSON Schema、requiredVehicleStates、verifyMethod、ToolSchemaView、结构化失败传播 | A2/B2 |
| **V0.5.0** | 2-3 周 | Context 压缩、四层 Memory、Prompt Builder、会话/Trajectory 持久化与加密、Gemini 原生 Tool Calling | C1/Agent Memory |
| **V0.5.1** | 1 周 | 延迟/故障 Mock、分层 Deadline、运行时稳定性验证 | B3 前置 |
| **V0.6.0** | 依赖公司环境 | AAOS CapabilityProvider、OccupantZone、Car API/厂商 AIDL、真实回读与传输级取消 | B3 |
| **V0.7.0** | 待评估 | Toolsets、Provider 可用性、声明式签名 Automotive Skills | 受控扩展 |
| **V0.8+** | 待评估 | 白名单 MCP/插件、未完成任务恢复、只读子 Agent、订阅任务 | 后续能力 |

下一站统一为 `V0.4.1`。先补 OpenAI 多轮 Tool Calling 解决跨轮依赖场景；主驾调度同步推进；真实 AAOS Provider 保留到 V0.6.0。当前 42 个测试作为回归基线。

---

## 十、V0.4.0 已知限制（联调期间暴露）

记录 V0.4.0 范围外、在 MTPLX/Qwen3.36/GLM 等本地或国产模型联调时发现的实战限制。这些问题不会影响 happy path，但会卡住特定场景，下一版本必须处理。

### 1. OpenAI-compat 单轮短路径（V0.4.1 Stage H/I）

**位置**：`platform/LlmModelGateway.legacyDecide:74-78`

```java
if (hasToolResult(request.getConversation())) {
    return ModelTurn.directAnswer("LLM 兼容路径:单轮规划已执行,任务结束");
}
```

**现象**：OpenAI-compat（包括 GLM / DeepSeek / Kimi / Qwen / MTPLX / vLLM / LM Studio / Ollama-OpenAI 桥）永远只跑 1 轮 LLM 调用。第 1 轮返回的 tool_calls（不管 1 个还是多个）执行完进 conversation，第 2 轮看到 tool_result 就直接 `directAnswer`，根本不再问 LLM。

**影响场景**：
- ✓ 「调温度 + 导航回家」（并行任务清单）：第 1 轮一次性出 2 个 tool_call，跑通
- ✗ 「查电量，低于 50% 就导航去充电站」（跨轮依赖）：第 2 步要看第 1 步结果决定，单轮规划不出来
- ✗ 「导航回家。失败的话提醒我打电话」（条件分支）：需要看 observation 决定下一步

**修复路径**：参考 V0.4.0 已实现的 `callAnthropicWithTools`，给 OpenAI 协议补 `callOpenAiWithTools`，删除 `legacyDecide` 短路径，让 conversation 始终回 LLM。

### 2. NATIVE_TOOL_CALLING 对国产/本地模型不稳定

**位置**：`platform/ModelApiClient.parseOpenAiToolResponse`

**现象**：用户选 `NATIVE_TOOL_CALLING` 时，模型即使收到 `tools` 参数，也不一定按 OpenAI 协议返回 `tool_calls` 字段。GLM-5.2、Qwen3.36 都被观察到把 JSON 塞进 markdown code block 写在 `content` 字段里，`finish_reason=stop`，导致 `parseOpenAiToolResponse` 抛「模型未返回 tool_calls」。

**当前处置**：联调时**强制建议选 `STRUCTURED_JSON_COMPATIBILITY`**，由 `LlmPlanner.cleanJson()` 兜底剥 markdown fence 后 JSON 解析。

**长期修复**：要么各 provider 在客户端做 markdown fallback，要么在 NATIVE 路径检测到 `finish_reason=stop && content contains JSON` 时自动转走兼容解析。倾向后者（保协议纯洁性 vs 容错性，实战优先）。

### 3. `clearUserData` 是全清按钮

**位置**：`data/AgentRuntimeRepository.clearUserData:65-70`

```java
public void clearUserData() {
    memoryStore.clear("demo-driver");
    memoryStore.clear("demo-passenger");
    sessionManager.clear();
}
```

**现象**：UI 上的「清空数据」按钮会同时清掉 driver + passenger 的 memory 和所有 session。用户测试时容易误点，把刚保存的偏好也清掉，然后以为「保存功能坏了」。

**待修方案**：拆两个按钮（清会话 / 清偏好），或加二次确认对话框。

### 4. Capability name 归一化（部分缓解）

**位置**：`core/capability/CapabilityRegistry`

**现象**：模型在 NATIVE 路径返回的 tool function name 是 `_` 风格（如 `memory_preference_get`），但项目注册的是 `.` 风格（`memory.preference.get`）。`toModelToolName` 已经做了 `.` → `_` 转换（发给模型时统一），但模型如果偏离约定（如返回 `memory_preference.get` 这种混合），lookup 会失败。

**当前处置**：`LlmPlanner` 走 STRUCTURED_JSON_COMPATIBILITY 时由模型自由发挥，靠 prompt + savedKeys 注入保证一致性（key 命名漂移已修）。NATIVE 路径暂未观察到名字错乱，但 V0.4.1 加 OpenAI 多轮时要重点关注。

### 5. 超时默认值与文档不一致

**位置**：`AgentRequest.Builder.timeoutMillis`（默认 60s）和 `AgentBudget.DEFAULT_TOTAL_DEADLINE_MILLIS`（60s）和 `AgentRuntimeRepository.execute` 显式覆盖 60s

**现象**：V0.4.0 默认是 8s（适合车机交互响应），联调本地 35B 大模型时被改到 60s 适配推理慢的本地服务。这是**调试期临时放宽**，量产前应回到 8s 或按厂商 SLA 配置。

**待修方案**：把超时参数化（`AgentRequest.Builder.timeoutMillis(long)` 已经存在，调用方按场景传），区分「车机语音交互（8s）」和「LLM 长流程（60s+）」。

### 6. SessionContext / Trajectory 不持久化

**位置**：`core/session/SessionManager`、`core/agent/Trajectory`

**现象**：会话历史和 Trajectory 都是 in-memory，app 杀进程即丢。V0.4.0 是设计如此（持久化是 V0.5.0 的活），但联调时如果用户重启 app 想看历史，会落空。

**已缓解**：`memoryStore` 走 SharedPreferences 已经持久化（`SharedPreferencesMemoryStore`），所以「记住我喜欢 24 度」跨重启不丢。但「上次问了什么」会丢。

### 7.legacyDecide 不读 SessionContext

**位置**：`platform/LlmModelGateway.legacyDecide`

**现象**：`legacyDecide` 调 `legacy.plan(agentRequest, context)`，`LlmPlanner.plan` 把 `context.getRecentTurns()` 拼进 userPrompt。但 `LlmModelGateway` 不主动注入上下文到 conversation——AgentEngine 维护的 conversation 才是 Loop 上下文。

**当前状态**：实际上没造成问题，因为 AgentEngine 的 Loop 自己维护了 conversation，每轮都喂给 ModelGateway。但 `recentTurns` 重复信息会浪费 token。V0.4.1 重写 LlmModelGateway 时一并清理。

### 8. 草稿缓存只 Fragment 作用域

**位置**：`presentation/viewmodel/ModelApiViewModel.drafts`

**现象**：切换 provider 时保存的草稿（endpoint/model/key 输入）只在 Fragment 还活着时有效。Fragment 销毁（如切到其他 Tab）后草稿丢失。

**当前处置**：用户视角下「切到别的 provider 试一下再切回来」的核心场景已经满足（Fragment 不销毁）。但如果用户切 Tab 出去再回来，输入会丢。

**待修方案**：把草稿也走 `SecureModelConfigStore`（API Key 加密、其他明文）。这是 V0.5.0 持久化方向的一部分。
