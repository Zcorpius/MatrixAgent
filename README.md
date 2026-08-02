# MatrixAgent

MatrixAgent 是面向 AAOS 的厂商级 AI Agent Runtime 原型。当前版本为 `V0.4.0`,已经升级为真正的迭代式 Agent Runtime(`LLM → Tool Call → Policy → Execute → Observation → LLM` 循环),使用纯 Java 和 MVVM 实现,可在普通 Android 模拟器运行。既可使用离线 DemoModelGateway,也可连接云端或本地大模型(支持 OpenAI-Compatible / Anthropic 原生 Tool Calling)。

## 当前版本目标

V0.4.0 验证真正的 Agent Loop 全链路:

```text
用户文本与请求来源
→ ModelGateway(LLM 单轮决策)
→ Tool Call(Provider 原生 Tool Calling 或 JSON Compatibility)
→ Policy Engine(能力级 / 参数级二分硬拒绝)
→ Capability Provider
→ Observation(回传模型)
→ 下一轮 LLM(直到模型选择直接答复或预算耗尽)
→ AgentOutcome + Trajectory(分层:模型视图 / 审计视图 / 内部可信域)
```

它不是量产车控 APK,所有车辆状态和导航执行均为本地 Mock。

## 已实现能力

- 纯 Java Agent Core(已按职责拆为 identity / capability / policy / session / tool / memory / agent 七个子包);
- 真正的迭代式 Agent Loop(替代 V0.3.x 的一次性 TaskPlan);
- ModelGateway 抽象 + DemoModelGateway(离线)+ LlmModelGateway(在线,Anthropic / OpenAI-Compatible 原生 Tool Calling);
- 结构化 AgentMessage / ToolObservation / AgentIteration / Trajectory;
- Policy 二分:能力级拒绝(不可上诉,加入禁用集合)vs 参数级拒绝(可换参数重试);
- 显式用户意图约束(ExplicitIntentConstraints):关键词识别主驾/副驾 + 多目标 / 否定冲突 fail-closed;
- Strict Schema 校验:执行边界拒绝未声明字段 + required / type / range / enum;
- 三层预算:最大迭代数 + 最大 Tool Call 数 + 总 deadline + 单条消息字符上限 + 总输入字符上限 + 消息条数上限;
- Audit Schema 投影:成功 / 失败 message 模板、sensitiveObservedFields 占位符、observedState allowlist fail-closed;
- 字段级脱敏:AuditRedactor 覆盖凭据正则 + capability schema + memory preference 全脱敏 + 未注册 / R3 / 额外字段 fail-closed;
- Provider 协议一致性:OpenAI `tool_calls` ↔ `finish_reason=tool_calls`、Anthropic `tool_use` ↔ `stop_reason=tool_use`、LENGTH 截断短路、PROTOCOL_ERROR 终止;
- 结构化 Tool Call(ToolCall ID 由 Provider 透传,Runtime 不自造);
- 静态 Capability Registry + R0/R1/R3 风险边界 + R3 禁止能力拦截;
- 温度与座椅参数校验 + 主驾/副驾请求来源和区域权限检查;
- 有状态 Mock Vehicle Provider + Tool 执行后状态回读 + 强制 verification;
- 即时多步任务与部分成功;
- 最近 12 轮 Session Context + per-session 引用计数锁 + TTL/LRU;
- 基于 SharedPreferences 的主驾/副驾持久偏好(已脱敏);
- 离线问答占位 Provider;
- **140 个 Java Core / 模型协议单元测试**(含 33 个第七~九轮 P1/P2 反例回归);
- 抽屉式侧边栏 + 模型 API 配置、连接测试和 Gateway 切换;
- Android Keystore 加密保存 API Key;
- OpenAI Chat Completions 兼容协议(原生 Tool Calling + JSON Compatibility);
- Anthropic Messages(原生 Tool Calling + JSON Compatibility)、Gemini generateContent 和 Ollama Chat 协议(JSON Compatibility)。

## MVVM 架构

```text
MainActivity（应用外壳、抽屉导航）
└── Fragment（XML 页面、用户事件、观察 LiveData）
    └── ViewModel（操作 ID、取消、UI 状态）
        ├── AgentRuntimeRepository
        │   └── Agent Core（Planner、Policy、Tools、Session、Memory）
        └── ModelGatewayRepository
            └── Platform（模型协议、Keystore）
```

主要代码边界：

- `app/`：`Application` 和手工依赖容器，统一创建进程级 Repository；
- `data/`：拆分为 `AgentRuntimeRepository` 和 `ModelGatewayRepository`；
- `presentation/state/`：不可变 UI State；
- `presentation/viewmodel/`：Agent 测试和模型 API 两个 ViewModel；
- `presentation/ui/`：两个 Fragment，只绑定控件、转发事件和观察状态；
- `MainActivity`：只负责 DrawerLayout 和页面切换；
- `core/`：纯 Java Agent 领域逻辑；
- `platform/`：Android 存储和外部模型协议适配。

V0.3 安全核心调整(已在 V0.4.0 沿用):

- Capability 风险等级、描述、参数规则、区域、幂等性、超时和验证要求集中定义;
- Policy 不再维护第二份 R3 字符串集合,也不再按能力名硬编码参数判断;
- `VehicleZone` 类型化并统一解析大小写,缺失和非法 Zone 默认拒绝;
- AgentRequest 增加 session、occupant/display/audio zone、输入源、语言、ASR 置信度、截止时间和取消令牌;
- 主驾与副驾上下文按 session 隔离,同一 session 串行、不同 session 可并行;
- AgentEngine 中的温度上下文业务迁入 `ContextUpdater`;
- ViewModel 使用 operation ID 防止旧请求覆盖新请求,并向 Core 传递取消;
- Mock 写入状态与回读状态分离,可注入回读不一致;
- 模型能力 Prompt 由 CapabilityRegistry 自动生成,模型失败不再静默执行 DemoPlanner;
- 模型连接超时缩短,所有连接异常路径都会释放资源。

V0.3.1 稳定性调整(已在 V0.4.0 沿用):

- per-session Lock 使用引用计数安全回收,避免旧 Lock 被移除后同一会话并发执行;
- ToolExecutor 按 Capability timeout 与请求剩余预算执行强制超时;
- ToolResult 增加 TIMED_OUT/CANCELLED 状态;
- Capability 显式声明 targetZoneRequired,不再用 GLOBAL 区域隐式推断必填参数;
- 数值校验拒绝 30.9 这类被 intValue 截断的小数;
- SessionManager 增加 30 分钟空闲过期和最多 32 个会话的容量限制;
- Mock Provider 改用并发状态容器,不再全局串行所有 Tool。

V0.3.2 Tool Calling(已被 V0.4.0 升级):

- provider-neutral `ToolDefinition` 与 `ToolParameterDefinition`;
- CapabilityRegistry 自动生成 ToolDefinition,R3 能力不会暴露给模型;
- 内部点号 Capability 自动映射为模型安全函数名,响应后映射回 canonical name;
- 新增 `PlannerMode`,Native Tool Calling 与 Structured JSON Compatibility 必须显式选择;
- PlannerMode 使用现有 AndroidKeyStore 配置存储持久化。

V0.4.0 Agent Loop + 核心拆包(当前版本):

- `core/` 按职责拆为 identity / capability / policy / session / tool / memory / agent 七个子包,演进边界清晰;
- 真正的迭代式 Agent Loop:`LLM → Tool Call → Policy → Execute → Observation → LLM`;
- ModelGateway 抽象取代 Planner;DemoModelGateway 取代 DemoPlanner;LlmModelGateway 走 Anthropic / OpenAI-Compatible 原生 Tool Calling;
- 结构化 Trajectory:iteration / toolCallId / PolicyDecision / Observation(脱敏后)/ 耗时 / 终止原因;
- PolicyDecision 拒绝二分:CAPABILITY(不可上诉,累计禁用)vs PARAMETER(可换参数重试);
- 第七轮 P1/P2 闭环:ExplicitIntent 状态枚举(MULTI_TARGET / CONFLICT fail-closed)、Strict Schema 执行边界拒绝、失败 ToolResult 与未知回读字段 fail-closed、ToolCall 与 FinishReason 一致性校验;
- 三层预算完全生效:最大迭代 / 最大 Tool Call / 总 deadline / 单条消息字符上限 / 总输入字符上限 / 消息条数上限;
- ToolCall ID 由 Provider 透传(Runtime 不自造),OpenAI / Anthropic 均走原生协议;
- 字段级脱敏:凭据正则 + capability schema + memory preference 全脱敏 + 未注册 / R3 / schema 外字段 fail-closed;
- **日志治理**(`SafeLog`):用户输入 / Tool 参数 / ToolResult message / Provider raw response / HTTP 错误 body 一律占位符,业务字段(destination / home_address / preferred_*)绝不进 logcat;
- 测试扩展到 140 个,含 33 个第七~九轮反例新增。

## Hermes Agent 核心能力借鉴与演进路线

MatrixAgent 不直接移植 Hermes Agent 的 Python 代码，也不照搬面向个人电脑的 Shell、浏览器和文件操作能力，而是借鉴其成熟的 Agent Runtime 设计，再按照 AAOS 的用户隔离、行车安全、权限模型和厂商接口重新实现。模型始终是不可信的决策建议者；能力暴露、参数校验、权限判断、执行和结果验证必须由本地 Runtime 控制。

### 应当集成的核心能力

按目标版本排列,优先级隐含在版本号里。当前统一版本基线为 `V0.4.0`,Agent Loop 主体已落地;下一里程碑为 `V0.4.1 Runtime Control`。每条都标注当前状态和落地范围,避免把后续能力全部堆进一个版本。

| Hermes 的优点 | MatrixAgent 当前状态 | AAOS 中的集成方式 | 目标版本 |
| --- | --- | --- | --- |
| **迭代式 Agent Loop**：模型调用工具后读取 Observation，再决定继续调用还是结束 | **V0.4.0 已落地**——`LLM → Tool Call → Policy → Execute → Observation → LLM` 循环 + 三层预算(消息条数 + 单条消息字符上限 + 总输入字符上限) | V0.5.0 引入 tokenizer 后切 Token | V0.5.0(token budget) |
| **工具结果驱动的纠错**：工具失败、参数错误或状态不一致时可重新规划 | **V0.4.0 已落地**——Policy 二分(CAPABILITY 不可上诉 / PARAMETER 可重试),Observation 回传模型 | 不允许通过换参数绕过能力级拒绝,也不允许擅自改变用户明确指定的区域 | 已闭环 |
| **完整运行轨迹(Trajectory)**：保留模型决策、工具调用和执行结果，便于回放和评估 | **V0.4.0 已落地**——结构化内存 Trajectory(iteration / toolCallId / PolicyDecision / Observation / 耗时 / 终止原因) + 默认字段级脱敏(凭据 / 业务字段 / 失败 message / schema 外字段) | V0.5.0 加 prompt 版本号 + Room/WAL 持久化 + 加密 + 厂商保留期策略 | V0.5.0(persistence) |
| **同 session 内 steer 与 cancel**：长任务运行中可取消或追加用户指令 | 已有 Deadline、CancellationToken 和同 session 串行锁,但没有循环内的 steer/cancel 语义 | 区分三种语义:**普通新任务**进入同 session 队列,等当前任务结束;**steer**进入当前任务独立的 SteerMailbox,在下次 LLM 迭代前消费(不强制中断正在执行的原子车控 Tool);**cancel**请求终止当前任务,在安全边界检查点(LLM 调用前、Tool 调用前)生效；与同版本的主驾优先 TaskScheduler 共用任务控制模型 | V0.4.1 |
| **统一 Tool Registry 与 Toolset**：Schema、处理器、可用性和工具分组统一管理 | V0.3.2 已有静态 CapabilityRegistry + ToolDefinition,但扩展性不足 | V0.4.2 先完成完整 JSON Schema、车辆前置状态、验证方式和不可变 `ToolSchemaView`;V0.7.0 再增加 Toolset 分组、版本、Provider 可用性和声明式 Skills。每次请求只暴露当前车型、用户、区域和驾驶状态允许的工具及参数视图，不修改 Canonical Schema | V0.4.2 / V0.7.0 |
| **主驾优先 TaskScheduler**：跨 session 优先级调度 | 当前是公平线程池 + per-session lock | V0.4.1 在纯 Java Core 中引入全局调度器,主驾请求优先级 ≥ 副驾;**仅查询/问答类任务可被抢占**,已开始的原子车控写操作不强制中断。V0.6.0 接入真实 Provider 时再补齐 HTTP/AIDL 的传输级取消契约 | V0.4.1 / V0.6.0 |
| **上下文管理与自动压缩**：长会话达到阈值后压缩，而不是简单截断 | 当前仅保留最近 12 条字符串 | 结构化 Message/Observation 历史;按模型 Context Window 估算 Token(无 tokenizer 时用 char-based 近似);保留系统约束、未完成任务和关键结果,对旧对话生成可追溯摘要 | V0.5.0 |
| **分层、可插拔记忆**：会话记忆、用户画像和外部记忆 Provider 分离，按需召回 | 当前只有最近上下文和 SharedPreferences 偏好 | Working/Episodic/Semantic/Preference 四层;**双维度隔离**(Android User 系统级 + OccupantZone 物理区域,两者不一一对应);支持检索、写入、更新、遗忘、TTL、来源和置信度,相关记忆在规划前预取 | V0.5.0 |
| **会话历史持久化(V0.5.0)+ 未完成任务恢复(V0.8+)** | 当前 SessionContext 只存在内存,重启丢失 | **V0.5.0 持久化的是已结束会话**:历史对话、上下文、记忆和已结束任务记录,主要用于审计/回放/"上次说到哪";**V0.8+ 才考虑未完成任务恢复**:未完成的车控写操作**不能自动重放**,必须由用户重新确认并重新规划,R3 永不恢复;Deadline 重新计算而非继承 | V0.5.0 / V0.8+ |
| **Prompt Builder**：按能力、上下文、记忆和运行环境组合系统提示词 | 当前 LlmPlanner 拼接固定 Prompt | 安全规则、用户/区域、可用工具、记忆、会话摘要和车型能力模块化组装并版本化;不同模型走 V0.3.2 已实现的 `PlannerMode` 协议适配层,安全约束保持一致 | V0.5.0 |
| **多模型 Provider 适配和错误分类**：统一不同模型协议，并对限流、上下文溢出等错误分类 | V0.4.0 已支持多家协议,Anthropic 与 OpenAI-Compatible 均走原生 Tool Calling | V0.5.0 补齐 Gemini 原生 Tool Calling;流式响应、错误分类、受控重试(按 deadline/错误类型/幂等性约束,不写死次数)、厂商网关路由;**模型切换不得改变安全策略** | V0.5.0 |
| **Skills 作为过程性知识**：把复杂任务步骤沉淀为可复用说明 | 当前未开放 Skill | 只读、声明式、厂商签名的 Automotive Skill;Skill 只能引用已注册 Capability,不能携带任意可执行代码或获得新权限 | V0.7.0 |
| **插件化扩展与 MCP 接入**：外部能力可以通过统一协议接入 | 当前明确不开放动态插件和 MCP | 量产仅允许厂商白名单、签名校验、固定网关和最小权限的 MCP/插件,仍需经过 Capability Registry 与 Policy Engine;开发/测试环境同样不放开,统一推迟到 V0.8+ 评估 | V0.8+ |
| **子任务分解与并行执行**：复杂任务可委派给隔离的子 Agent | 当前为单 Agent Runtime | 仅在持续服务或复杂信息任务中考虑;车控写操作保持单一 Orchestrator 串行仲裁,子 Agent 默认只能查询或生成候选方案 | V0.8+ |

### 不能直接照搬的 Hermes 能力

下面是车端功能安全的硬约束。即使是"参考实现思路"也必须先经过这些边界检查,否则容易出现"看着合理但量产审计过不了"的设计。

- **不接受 fire-and-forget 车控调用**。所有写操作必须有完成回执和可信状态回读。需要厘清的是:Hermes 实际上**会等 Tool 返回**(其车控 Skill 第三轮明确写"所有工具返回后"才总结),并不是"只发不等";真正的问题在于**通用 ToolResult 不一定代表车辆物理状态已经完成改变** —— Tool 函数返回 success 可能只是"命令已发",而不是"ECU 已执行 + 总线信号已生效"。MatrixAgent 对车控写操作要求显式区分 `accepted / completed / trusted state readback` 三个阶段,不能只凭 Tool 返回成功就认定车辆状态已生效。envelope 本身没问题 —— AAOS 跨进程 AIDL/Binder、跨 SoC 座舱域调用本质上都需要命令信封。可接受的模式必须包含:`commandId → accepted → executing → completed/failed → trusted state readback`,同时具备幂等键、超时、回调、审计关联。安全责任可以分层(Agent Runtime / 厂商 Service / ECU),但**不能消失**。
- **不引入任意外部 memory provider**。Hermes 的 `MemoryManager` 允许任意外部 provider 编排,车端必须限制为厂商白名单 memory provider + 设备本地存储,否则有用户对话数据泄露风险。
- 不向量产车机模型暴露通用 Shell、任意文件访问、代码执行、浏览器自动化或远程终端；
- 不允许模型自行决定跳过确认、Policy、参数验证、状态回读或审计;
- 不把"执行成功"的自然语言当作车辆状态,真实结果必须来自 Car API、AIDL、应用标准接口或可信 Provider 的回读;
- 不允许 Agent 自动创建并立即执行拥有新权限的 Skill;Skill 必须声明式、可审计、签名,且只能引用已有 Capability;
- 不允许从互联网动态下载插件后直接注册 Tool;所有扩展必须经过厂商白名单、签名、权限和版本兼容检查;
- 不在首期引入多 Agent 并行车控,避免并发写入、优先级反转和责任边界不清。

### 分阶段落地

以下 8 个阶段是 MatrixAgent 的统一版本路线；`MatrixAgent-Code-Review.md` 和 `方案讨论待确认事项.md` 使用同一编号。

1. **V0.4.0 Agent Loop** — 模型—工具—Observation 迭代、Tool Call ID、Policy 二分、错误回传、三层消息/字符预算、可回放内存 Trajectory、默认脱敏和 Anthropic 原生 Tool Calling。**这是从一次性 LLM Planner 升级为真正 Agent Runtime 的架构里程碑**。
2. **V0.4.1 Runtime Control** — 同 session 普通任务队列、SteerMailbox、cancel 安全边界、纯 Java 主驾优先 TaskScheduler、可取消 ModelCall 抽象及相应并发测试。车控写操作不做不安全的中途抢占。
3. **V0.4.2 Capability Safety** — 完整 JSON Schema、`requiredVehicleStates`、`verifyMethod`、不可变 Canonical Schema/按请求生成的 `ToolSchemaView`，以及结构化失败分类、失败传播和受幂等性约束的纠错。
4. **V0.5.0 Context、Memory 与持久化** — 结构化 Message、Token/字符预算、上下文压缩、四层记忆、相关记忆召回、双维度隔离、已结束会话历史持久化、Trajectory 落 Room/WAL、加密与保留期、Gemini 原生 Tool Calling和 Prompt Builder。
5. **V0.5.1 Runtime Hardening** — 在 MockCapabilityProvider 上加入 sleep/jitter、ECU 回读耗时、CAN 丢帧和总线排队模拟，验证本地查询、单次车控、导航/问答、Agent 总预算和 Tool timeout 的分层 Deadline。
6. **V0.6.0 AAOS Integration** — 严格按 `MatrixAgent → CapabilityProvider → Car API / CarPropertyManager / 厂商 AIDL → CarService / Vendor Service → VHAL` 分层；接入 OccupantZone、真实 Provider、真实状态回读和传输级取消契约。Agent Runtime 不直接依赖 VHAL 或 Vehicle Property ID。
7. **V0.7.0 Toolsets & Skills** — 运行时能力裁剪、Toolset 分组、Provider 可用性和声明式签名 Automotive Skills；安全模型经过真实 AAOS 链路验证后才开放。
8. **V0.8+ Controlled Extensions** — 厂商白名单 MCP/插件、未完成任务恢复、只读子 Agent和订阅任务；未完成车控写操作必须重新确认、重新规划，不能自动重放。

每个阶段都应同时补充离线评估用例,至少覆盖:

- 正常执行、工具失败、模型产生未知工具、参数越界
- Policy 拒绝分两类(能力级不可上诉 / 参数级可修正重试)、状态回读不一致
- 用户中断、超时、迭代耗尽、上下文压缩后继续执行
- **并发场景:主副驾同时调用、跨 session 竞争、同 session 锁回收、steer 排队**

页面旋转或 Fragment 重建时，正在使用的 ViewModel 状态不会依赖 Activity 字段保存；网络和 Agent 执行也不会阻塞主线程。后续替换 AAOS Car API、AIDL 或车辆 Provider 时，UI 层无需感知具体实现。

## 模型 API 页面

内置可编辑预设：

- 智谱 GLM；
- DeepSeek；
- 阿里通义千问；
- Moonshot Kimi；
- 火山方舟/豆包；
- Anthropic Claude；
- Google Gemini；
- 本地 Ollama；
- 本地 LM Studio；
- 本地 vLLM；
- 自定义 OpenAI-Compatible 服务。

每个预设都允许修改完整 Endpoint 和 Model，因此模型升级或私有网关不需要修改代码。模拟器访问电脑上的 Ollama、LM Studio 或 vLLM 时使用 `10.0.2.2`；真机使用电脑局域网 IP。

点击“保存并应用”后，Agent 使用 `LlmPlanner` 生成结构化 TaskPlan。OpenAI-Compatible 可选择 Native Tool Calling；其他协议或不支持 tools 的本地模型需要显式选择 Structured JSON Compatibility。模型异常、协议不支持或响应不合法时任务明确失败，不会自动执行 DemoPlanner。无论使用哪种规划模式，计划仍必须经过本地 Policy Engine，模型不能绕过 R3 禁止能力、区域权限和参数校验。

> 当前 APK 内保存 API Key 的方式只适用于开发验证。Keystore 可以保护静态存储，但不能阻止拥有设备控制权的人在运行时提取请求。量产 AAOS 应通过厂商 Model Gateway、短期令牌或设备身份认证访问云端，不能把厂商长期主密钥下发到 APK。

## 模拟器演示

应用内置以下快速测试：

- `把主驾温度调到24度，然后导航回家`
- `副驾也一样`
- `记住我喜欢24度`
- `我喜欢多少度`
- `打开ADAS`
- `把空调调到80度`
- `空调调到23度，然后导航到失败测试点`

分别用于验证多步任务、上下文、持久记忆、安全拒绝、参数拒绝和部分失败。

## 构建

项目要求：

- JDK 17 或更新版本；
- Android SDK 36；
- 首次构建可以访问 Gradle 和 Google Maven 仓库。

在项目目录执行：

```bash
./gradlew testDebugUnitTest assembleDebug
```

APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 安装运行

启动 Android 模拟器后执行：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.matrix.agent/.MainActivity
```

## 代码边界

### Core 边界(V0.4.0 起会明显演进)

`app/src/main/java/com/matrix/agent/core/` 是纯 Java 领域逻辑,不依赖 Android API,可独立单测。但**"不依赖 Android"不等于"API 稳定"** —— 当前各模块都还会随 V0.4.0—V0.6.0 演进,不能当成"冻结 API 带 AAOS"。当前各模块的演进分级:

- **相对稳定(语义不变,实现细节会优化)**:`PolicyEngine`、`VehicleZone`、`CapabilityDefinition`、`RiskLevel`、`CancellationToken`
- **V0.4.0 会重构**:`AgentEngine`(改成 Agent Loop)、`Planner` + `PlannerExecutor`(改成 ModelGateway,后者可能被 `ModelCallExecutor` 取代)、`TaskPlan`(改成单步迭代)、`TraceEvent`(升级为 Trajectory)
- **V0.4.1 会受影响**:`SessionLockManager`(配合 SteerMailbox 调整并发模型)
- **V0.4.2 会重构**:`CapabilityRegistry`(完整 Schema + 动态能力视图)
- **V0.5.0 会重构**:`SessionContext`(加结构化 Message)、`MemoryStore`(分四层)、`ContextUpdater`(配合 Prompt Builder)
- **V0.6.0 会重构**:`ToolExecutor`(适配 AIDL 异步取消和远端执行状态)
- **替换**:`MockCapabilityProvider` → 真实 Car API Provider(见下节)

简单说:**当前为纯 Java、可作 AAOS 实现基础,但 API 仍会随 V0.4.0—V0.6.0 演进**,不要把现在的接口签名当成稳定契约。

### 当前 Android 适配层

`app/src/main/java/com/matrix/agent/platform/`

- `SharedPreferencesMemoryStore`

后续可替换为当前 Android User 的 CE 数据存储。

### 回到公司后需要替换的实现

- 一次性 Planner(当前 `DemoPlanner` / `LlmPlanner`) → 迭代式 Agent ModelGateway(V0.4.0 重构,可接收工具结果并继续推理);
- `MockCapabilityProvider` → `AndroidCarVehicleProvider`、导航 Provider 和问答 Provider；
- Activity 文本入口 → CarVoiceAssistant AIDL 入口；
- Demo 主副驾枚举 → Android User、Occupant Zone、Display 和 Audio Zone；
- SharedPreferences 简单偏好 → 加密、可审计的用户记忆存储；
- 本地 Trace 展示 → 厂商日志、指标和离线评估系统。

## 当前版本明确不做

- 不调用真实车辆；
- 不访问 VHAL；
- 不修改 Framework 或 CarService；
- 不使用多 Agent；
- V0.4.0 不开放 Skill、MCP、Shell 或第三方动态工具；后续只按上述安全路线引入声明式 Skill 和受控扩展；
- 不实现订阅任务、跨重启恢复和多 Android User 并行；
- 不接入摄像头、麦克风或真实账号数据。

## 下一步建议

V0.4.0 主体(迭代式 Agent Loop + Policy 二分 + Trajectory + 字段级脱敏 + Anthropic/OpenAI 原生 Tool Calling)已经落地。下一版本进入 `V0.4.1 Runtime Control`,实施顺序为:

1. 实现 `SteerMailbox` 与 cancel 安全边界(目前 `drainPendingSteer()` 留空,V0.4.1 接通);
2. 引入主驾优先 `TaskScheduler`,仅查询/问答类任务可被抢占,车控写操作不强制中断;
3. 把 `ModelCallExecutor` 改造为可取消 ModelCall 抽象,支持传输级 cancel;
4. V0.4.2 完成完整 JSON Schema、`requiredVehicleStates`、`verifyMethod`、不可变 Canonical Schema + 按请求生成的 ToolSchemaView;
5. V0.5.0 切换到 Token 预算 + tokenizer,引入四层 Memory、Room/WAL 持久化 + 加密、Gemini 原生 Tool Calling、Prompt Builder 模块化。

总体方案见 [方案讨论待确认事项.md](./方案讨论待确认事项.md)。
