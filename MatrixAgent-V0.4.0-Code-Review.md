# MatrixAgent V0.4.0 Agent Loop 实现评审

> 评审日期：2026-08-02  
> 评审方式：只读代码审查 + 单元测试  
> 评审对象：MatrixAgent V0.4.0 Agent Loop 实现  
> 参考实现：Hermes Agent `agent/conversation_loop.py` 及其 Tool/Observation 多轮交互方式  
> 评审结论：**Agent Loop 骨架成立，但存在 4 个 P1 问题，当前不建议将 V0.4.0 标记为完成。**

## 一、评审目标

本次评审检查 MatrixAgent 是否已经从 V0.3.2 的一次性规划模式：

```text
User Request
→ Planner
→ 完整 TaskPlan
→ 顺序执行所有 Tool
→ Task Outcome
```

升级为参考 Hermes Agent 设计思想的迭代式 Runtime：

```text
User Request
→ Model
→ Tool Call
→ Capability Registry
→ Policy
→ Tool Executor
→ Observation
→ Model 再决策
→ Final Answer / Failure / Budget Exhausted
```

参考 Hermes 仅表示借鉴 Agent Loop、Tool Result 回传、迭代预算和运行轨迹等设计思想。MatrixAgent 不直接移植 Hermes 的 Python 代码，也不引入 Shell、任意文件访问、动态插件、任意 MCP 或自动生成可执行 Skill。

## 二、评审范围

重点审查以下实现：

- `core/agent/AgentEngine.java`
- `core/agent/ModelGateway.java`
- `core/agent/ModelTurn.java`
- `core/agent/ModelTurnRequest.java`
- `core/agent/AgentMessage.java`
- `core/agent/AgentBudget.java`
- `core/agent/ModelCallExecutor.java`
- `core/agent/ToolObservation.java`
- `core/agent/AgentIteration.java`
- `core/agent/Trajectory.java`
- `core/agent/Redactor.java`
- `platform/LlmModelGateway.java`
- `platform/ModelApiClient.java`
- Agent Loop、Redactor 和模型协议相关单元测试

V0.4.1 计划中的 SteerMailbox、同 Session 新任务队列、主驾优先 TaskScheduler 和可取消 ModelCall 不属于本次 V0.4.0 完成度判断，但本次会记录与其相关的接口风险。

## 三、测试结果

执行命令：

```bash
./gradlew testDebugUnitTest
```

结果：

```text
BUILD SUCCESSFUL
42 tests, 0 failures, 0 errors, 0 skipped
```

测试分布：

| 测试类 | 数量 | 结果 |
| --- | ---: | --- |
| `AgentEngineTest` | 25 | 通过 |
| `ModelApiClientContractTest` | 10 | 通过 |
| `RedactorTest` | 7 | 通过 |

现有测试证明基础循环、Policy 二分、Tool Call 数量限制、总字符限制、Anthropic Tool ID 透传、Trajectory 基础字段和 Observation 回传可以运行。但现有测试没有覆盖本次发现的异常终止状态、模型用 Observation 与审计用 Observation 分离、完整 Trajectory 脱敏和 OpenAI-Compatible 多轮协议。

## 四、已经正确实现的部分

### 4.1 Agent Loop 主体成立

`AgentEngine` 已形成：

```text
ModelGateway.decide()
→ ModelTurn
→ PolicyEngine.evaluate()
→ ToolExecutor.execute()
→ ToolObservation
→ Conversation
→ 下一轮 ModelGateway.decide()
```

这不再是简单地把一个 `while` 套在旧 Planner 外面，而是已经建立 Provider-neutral 的 `AgentMessage`、`ModelTurn`、`ToolObservation` 和 `Trajectory` 数据模型。

### 4.2 Policy 每次调用都重新生效

每个 Tool Call 都会经过 Registry 和 Policy。模型无法因为进入第二轮或修改参数而绕过 R3、未注册能力和区域权限。

### 4.3 Policy 拒绝已经二分

- `CAPABILITY`：R3、未注册和越权等硬拒绝，后续迭代不再放行；
- `PARAMETER`：格式、范围和可补全参数错误，允许模型在预算内修正。

这符合车端“模型可以纠错，但不能申请放宽权限”的原则。

### 4.4 Tool Call 与 Observation 可关联

Anthropic `tool_use.id` 可以透传为 MatrixAgent 的 Tool Call ID，再由 `tool_result` 原样关联。多个 Tool Result 也能合并成符合 Anthropic Messages 协议的单个 User Message。

### 4.5 已有基础预算和终止机制

当前包含：

- 最大迭代次数；
- 最大累计 Tool Call 数；
- 请求 Deadline；
- CancellationToken；
- 总输入字符上限；
- Tool 独立 Timeout。

### 4.6 Trajectory 已结构化

Trajectory 已记录：

- Iteration；
- Assistant Message；
- Tool Call ID、Capability 和 Arguments；
- PolicyDecision；
- ToolObservation；
- 单轮耗时；
- StopReason；
- 总 Tool Call 数与总耗时。

## 五、P1 问题：V0.4.0 完成前必须修复

### P1-1 OpenAI-Compatible 尚未进入真正的多轮 Agent Loop

位置：

- `platform/LlmModelGateway.java:23`
- `platform/LlmModelGateway.java:81`
- `platform/ModelApiClient.java:50`

当前只有 `NATIVE_TOOL_CALLING + ANTHROPIC_MESSAGES` 会把完整 Conversation 发送给模型。

OpenAI-Compatible、本地模型及 Structured JSON Compatibility 仍走旧路径：

```text
LlmPlanner 一次生成 TaskPlan
→ AgentEngine 执行 Tool
→ LlmModelGateway 看到任意 Tool Result
→ 固定返回“单轮规划已执行，任务结束”
```

这意味着之前接入的本地 OpenAI-Compatible 模型不能：

- 根据 Tool 执行失败调整方案；
- 根据参数拒绝重新生成参数；
- 读取状态回读后决定下一步；
- 在多步任务中根据上一步真实结果动态规划；
- 生成基于真实 Observation 的最终答复。

#### 修复要求

为 OpenAI-Compatible 增加完整的多轮 Chat Completions 请求：

```text
system
user
assistant(tool_calls)
tool(tool_call_id)
assistant(tool_calls / final answer)
```

同时必须：

- 保留响应中的原始 `tool_calls[].id`，不能重新生成 UUID；
- 将 Canonical Capability Name 与模型安全函数名双向映射；
- 支持一次 Assistant Message 中的多个 Tool Call；
- 无 Tool Call 时返回正常 Final Answer；
- 非法 Tool ID、未知 Tool、非法 Arguments 和缺失 Message 明确失败；
- 增加 OpenAI-Compatible 多轮协议契约测试。

### P1-2 脱敏后的 Observation 被送回模型，导致信息丢失

位置：

- `core/agent/AgentEngine.java:263`
- `core/agent/Redactor.java:79`

当前代码先对 Observation 脱敏，然后将同一个脱敏副本同时放入：

- 下一轮模型 Conversation；
- Trajectory；
- UI 输出。

例如：

```text
preferred_temperature=24
```

会被替换为：

```text
preferred_temperature=<memory>
```

模型因此无法正确回答“我喜欢多少度”。同样的问题可能影响导航地址、联系人、账号相关结果或未来的个性化数据。

#### 修复要求

Observation 必须拆成两个视图：

```text
Model Observation
  → 保留完成任务所需的语义
  → 只移除绝对不能进入模型的密钥、凭据和内部安全字段

Audit Observation
  → 字段级脱敏
  → 用于 Trajectory、UI、日志和未来持久化
```

不能使用一个 `Redactor` 同时承担“发给模型的数据边界”和“审计展示的数据边界”。建议分别定义 `ModelObservationSanitizer` 与 `AuditRedactor`。

### P1-3 异常终止可能被计算为成功

位置：

- `core/agent/AgentEngine.java:294`

当前 `computeFinalState()` 主要根据成功 Tool 数量和硬失败数量计算状态，没有首先判断 Agent Loop 是否正常结束。

例如模型一直调用一个成功的查询 Tool，直到达到：

- `MAX_ITERATIONS`；
- `MAX_TOOL_CALLS`；
- `BUDGET_EXHAUSTED`；
- `POLICY_HALT`。

只要之前存在成功 Tool 且没有硬失败，最终状态仍可能为 `SUCCEEDED`。这会造成“Runtime 因失控或耗尽预算停止，但对外声称任务成功”。

#### 修复要求

首先按 StopReason 判断终止性质：

| StopReason | 建议状态 |
| --- | --- |
| `DONE` / `NO_TOOL_CALL` | 根据 Tool 结果计算 `SUCCEEDED` / `PARTIALLY_SUCCEEDED` / `FAILED` |
| `CANCELLED` | `CANCELLED` |
| `TIMEOUT` | `TIMED_OUT` |
| `MAX_ITERATIONS` | 无成功结果为 `FAILED`，有部分结果为 `PARTIALLY_SUCCEEDED` |
| `MAX_TOOL_CALLS` | 无成功结果为 `FAILED`，有部分结果为 `PARTIALLY_SUCCEEDED` |
| `BUDGET_EXHAUSTED` | 无成功结果为 `FAILED`，有部分结果为 `PARTIALLY_SUCCEEDED` |
| `POLICY_HALT` | 无成功结果为 `FAILED`，有部分结果为 `PARTIALLY_SUCCEEDED` |

必须增加“Tool 每轮成功但模型永不结束”的单元测试，并断言最终状态不能为 `SUCCEEDED`。

### P1-4 Trajectory、UI 与日志脱敏不完整

位置：

- `core/agent/AgentEngine.java:182`
- `core/agent/AgentEngine.java:272`
- `core/agent/AgentEngine.java:362`
- `presentation/viewmodel/AgentTestViewModel.java:117`
- `presentation/viewmodel/AgentTestViewModel.java:128`

当前只有 Observation 副本经过 Redactor。以下字段仍保留或展示原始内容：

- Assistant Message；
- Tool Call Arguments；
- PolicyDecision Reason；
- 用户输入日志；
- Provider/Policy/Engine 的 Arguments 日志；
- HTTP 非 2xx 响应体；
- UI 中的 Assistant、Arguments 和 Policy Reason。

`memory.preference.save` 的 value、导航地址或未来账号类参数可能直接出现在 UI Trace 和 Logcat。

#### 修复要求

- Trajectory 中保存脱敏后的 Assistant Message、Tool Call Snapshot、PolicyDecision 和 Observation；
- Runtime 内部 Conversation 可保留完成任务所需信息，但不能直接作为审计对象暴露；
- UI 只能渲染 Audit Trajectory；
- 日志默认只记录 requestId、sessionId、capability、状态、耗时和长度，不记录原始用户文本、Arguments、模型正文或 HTTP 错误正文；
- 如开发模式确实需要详细日志，必须显式开关、字段级脱敏且量产默认关闭；
- 增加 Assistant、Tool Arguments、Policy Reason 和 UI 输出的脱敏测试。

## 六、P2 问题：建议在 V0.4.0 一并修复

### P2-1 三层预算尚未完全落实

位置：

- `core/agent/AgentBudget.java`
- `core/agent/AgentEngine.java:85`
- `core/agent/AgentEngine.java:157`

问题：

- `totalDeadlineMillis` 已定义但没有参与实际 Deadline 计算；
- `maxMessageChars` 只作为 Redactor 截断长度，没有校验 User、Assistant 和 Tool Arguments；
- 没有独立的最大消息数量字段；
- HTTP 响应在解析前由 `readAll()` 无上限读入内存，无法由消息预算保护。

建议 Agent 实际 Deadline 取：

```text
min(AgentRequest.deadline, AgentLoopStarted + AgentBudget.totalDeadlineMillis)
```

并在每次模型调用前后验证：

- 最大消息数量；
- 单条 Message 字符数；
- 总 Conversation 字符数；
- 单次模型响应体字节上限；
- Tool Arguments/Observation 字符数。

### P2-2 FinishReason 没有进入循环控制

位置：

- `core/agent/FinishReason.java`
- `core/agent/AgentEngine.java:181`
- `platform/ModelApiClient.java:163`

`ModelTurn` 定义了 `STOP`、`LENGTH` 和 `TOOL_CALLS`，但 `AgentEngine` 没有读取 `getFinishReason()`。Anthropic 解析也没有读取响应中的 `stop_reason`。

风险：

- `max_tokens` 截断被当作正常 Final Answer；
- 空文本且无 Tool Call 可能被当作成功；
- FinishReason 与实际 Tool Call 状态不一致时没有协议错误。

建议：

- Provider Adapter 正确映射原始 Finish Reason；
- `LENGTH` 返回结构化模型截断错误，不直接成功；
- `TOOL_CALLS` 必须至少包含一个合法 Tool Call；
- `STOP` 必须有有效 Final Answer；
- 空 Assistant Message 明确失败。

### P2-3 Model 错误被错误归类为 POLICY_HALT

位置：

- `core/agent/ModelCallExecutor.java:74`

所有 ModelGateway 异常都被映射为 `POLICY_HALT`。网络失败、协议解析失败、模型空响应和真正 Policy Halt 应当分别统计，否则后续评估无法判断失败来源。

建议补充：

- `MODEL_ERROR`；
- `PROTOCOL_ERROR`；
- `NETWORK_ERROR`；
- `POLICY_HALT` 只用于本地安全策略主动终止。

### P2-4 Core 已经依赖 Android API

多个 `core/` 类直接引用 `android.util.Log`，包括 AgentEngine、PolicyEngine、ToolExecutor、SessionManager 和 Redactor。

这与文档中“Core 是不依赖 Android API 的纯 Java 领域逻辑”不一致，也使 Core 单元测试依赖 Android Gradle Plugin 的 `returnDefaultValues=true`。

建议：

- Core 使用无 Android 依赖的日志接口或注入式 `AgentLogger`；
- Android `Log` 实现放在 `platform/`；
- 测试使用 No-op/Test Logger；
- 保持 Core 可以在普通 JVM 中独立运行。

### P2-5 版本和文档尚未同步

当前仍存在：

- `app/build.gradle`：`versionName '0.3.2'`；
- README 顶部：当前版本 V0.3.2；
- README 已实现能力：24 个测试；
- README 当前链路仍是 Planner → TaskPlan。

实际测试已经为 42 个，代码也已进入 Agent Loop 架构。应在 P1 修复并复审通过后统一更新为 V0.4.0，避免提前宣告完成。

## 七、Hermes Agent 对照结论

### 已经吸收的能力

- 模型与工具之间的迭代循环；
- Tool Result 作为下一轮模型输入；
- 单轮多个 Tool Call；
- 最大迭代次数；
- Tool Call 总量预算；
- 结构化运行轨迹；
- 模型结束与 Runtime 强制结束的区分基础；
- Context、Memory、Steer 等后续扩展点。

### 尚未达到的部分

- 主力 OpenAI-Compatible 模型没有完整多轮 Conversation；
- Finish Reason 和协议异常处理不足；
- 模型输入视图与审计视图没有正确分离；
- 异常结束状态不够可靠；
- 预算控制还没有覆盖所有消息和原始响应；
- 模型调用取消目前主要是 Future/Thread interrupt，真正可取消 Call 留待 V0.4.1。

### MatrixAgent 必须保留的车端差异

- 每轮 Tool Call 必须重新经过 Policy；
- R3、未注册和越权能力不能通过后续迭代绕过；
- 参数纠错不能改变用户明确指定的区域或意图；
- 车控写操作必须区分 accepted、completed 与 trusted state readback；
- 模型输出不等于车辆真实状态；
- Runtime 必须有确定的 Deadline、Tool Timeout、取消和审计边界；
- 不引入 Hermes 的通用 Shell、任意动态工具或非白名单外部 Memory Provider。

## 八、建议修复顺序

1. 修复 `computeFinalState()`，避免异常停止被判为成功；
2. 拆分 Model Observation 与 Audit Observation；
3. 完整脱敏 Trajectory、UI 和 Logcat；
4. 实现 OpenAI-Compatible 多轮 Agent Loop；
5. 让 AgentBudget 的 Deadline、消息数、单条字符和总字符真正生效；
6. 解析并执行 FinishReason；
7. 拆分 Model/Protocol/Network/Policy 错误类型；
8. 清理 Core 对 `android.util.Log` 的直接依赖；
9. 增加缺失测试；
10. 复审通过后更新版本号和 README。

## 九、V0.4.0 复审准入条件

满足以下条件后再申请 V0.4.0 复审：

- [ ] OpenAI-Compatible 能执行真实的多轮 Tool/Observation 循环；
- [ ] OpenAI Tool Call ID 原样保留并正确关联 Tool Result；
- [ ] Model Observation 与 Audit Observation 分离；
- [ ] Trajectory、UI 和日志不泄漏原始记忆或敏感 Tool Arguments；
- [ ] `MAX_ITERATIONS`、`MAX_TOOL_CALLS` 和 `BUDGET_EXHAUSTED` 不会返回 `SUCCEEDED`；
- [ ] `AgentBudget.totalDeadlineMillis` 和单条消息上限实际生效；
- [ ] `LENGTH`、空响应和协议不一致不会被当作正常完成；
- [ ] 新增 OpenAI 多轮、异常终止、预算、完整脱敏和 FinishReason 测试；
- [ ] 全部单元测试通过；
- [ ] README、版本号、架构链路和测试数量在复审通过后同步更新。

## 十、最终评价

V0.4.0 已经完成了真正有价值的 Agent Runtime 重构基础，而不是简单把旧 Planner 放进循环。Provider-neutral 消息模型、Observation、Policy 二分、Trajectory 和 Anthropic 原生 Tool Calling 都是正确方向。

当前完成度约为 **75%—80%**。主要缺口集中在主力 OpenAI-Compatible 模型仍走单轮兼容路径、模型数据与审计数据边界混用、异常终止状态错误以及脱敏覆盖不完整。修复 4 个 P1 后，MatrixAgent 才能被认为完成了参考 Hermes 核心思想、同时符合 AAOS 安全边界的 V0.4.0 Agent Loop。

---

## 十一、第二轮复审（2026-08-02）

### 11.1 复审结论

第二轮复审继续采用只读代码检查并强制重新执行全部单元测试。

结论：**V0.4.0 仍不能通过复审。** 本轮新增了副驾区域重试和 Memory Observation 脱敏相关测试，测试总数从 42 增加到 46，但第一轮提出的 4 个 P1 问题基本仍然存在。其中，Observation 与脱敏策略被修改到了与评审建议相反的方向，新增测试固化了这一错误的数据边界。

强制执行：

```bash
./gradlew testDebugUnitTest --rerun-tasks
```

结果：

```text
BUILD SUCCESSFUL
46 tests, 0 failures, 0 errors, 0 skipped
```

测试分布：

| 测试类 | 第一轮 | 第二轮 | 结果 |
| --- | ---: | ---: | --- |
| `AgentEngineTest` | 25 | 27 | 通过 |
| `ModelApiClientContractTest` | 10 | 10 | 通过 |
| `RedactorTest` | 7 | 9 | 通过 |

测试通过只能证明代码符合当前测试断言，不能证明断言所代表的隐私、安全和任务语义是正确的。`trajectoryHoldsRawObservationWhileConversationIsRedacted` 明确要求“模型读取脱敏内容、Trajectory 保存原始偏好”，该测试本身需要重新设计。

### 11.2 本轮确认的改动

#### 已改动但需要继续调整

1. `PolicyEngine` 将“副驾请求 Driver Zone”由能力级拒绝改为参数级拒绝，允许模型改成 Passenger Zone 后重试；
2. `Redactor` 不再依赖 `preferred_*` Key 前缀，而是按 `memory.preference.save/get` Capability 对所有 Observation Value 脱敏；
3. 新增副驾改区重试测试；
4. 新增 Memory Capability 全字段脱敏测试；
5. 测试数量增加到 46。

按 Capability 而不是自由 Key 名判断敏感数据，方向比依赖 `preferred_*` 前缀可靠。但该 Redactor 当前被用于模型 Conversation，而不是审计输出，使用位置仍然错误。

### 11.3 仍未修复的 P1

#### P1-1 OpenAI-Compatible 仍不是多轮 Agent Loop

位置：

- `platform/LlmModelGateway.java:20-24`
- `platform/LlmModelGateway.java:81-94`

代码仍明确说明：

```text
Anthropic Native → 完整多轮 Tool Calling
其他协议/模式 → Legacy 一次性 Planner
```

Legacy 路径看到任意 Tool Result 后仍直接返回固定文本：

```text
LLM 兼容路径:单轮规划已执行,任务结束
```

因此 OpenAI-Compatible 和之前使用的本地模型仍不能读取真实 Observation、修正参数、处理失败或继续规划。第一轮 P1-1 未修复。

#### P1-2 Model Observation 与 Audit Observation 使用方向错误

位置：

- `core/agent/AgentEngine.java:263-274`
- `core/agent/Redactor.java:16-28`
- `core/agent/Redactor.java:86-126`

当前实现为：

```text
脱敏 Observation → LLM Conversation
原始 Observation → Trajectory / UI
```

这与正确的数据边界相反。

**模型侧问题：**

- `memory.preference.get` 返回的真实偏好被替换为 `<memory>`；
- 模型无法回答“我喜欢多少度”；
- 模型无法根据偏好继续执行后续 Tool；
- 后续联系人、地址或账号相关 Memory 也会丢失完成任务所需语义。

**审计侧问题：**

- Trajectory 保存完整偏好；
- UI 直接展示 Tool Arguments 和原始 Observation；
- 当前主驾和副驾共用 Android User，不能假设 UI 查看者一定是数据所有者；
- V0.5.0 持久化 Trajectory 后会进一步扩大数据暴露范围。

正确设计仍应拆成两个独立组件：

```text
ModelObservationSanitizer
  → 保留完成任务所需语义
  → 删除 API Key、Authorization、设备凭据和明确禁止出端字段

AuditRedactor
  → 对 Assistant、Tool Arguments、Policy Reason、Observation 和日志字段脱敏
  → 输出给 Trajectory、UI、Logcat 和未来持久化
```

“用户看自己的偏好不是隐私”不能作为 AAOS 的通用安全假设。乘员区、Display、Android User、日志读取权限和数据持久化边界必须分别判断。

#### P1-3 异常停止仍可能返回 SUCCEEDED

位置：

- `core/agent/AgentEngine.java:294-309`

`computeFinalState()` 没有变化。只要存在成功 Tool 且没有硬失败，以下 StopReason 仍可能得到 `SUCCEEDED`：

- `MAX_ITERATIONS`；
- `MAX_TOOL_CALLS`；
- `BUDGET_EXHAUSTED`；
- `POLICY_HALT`。

这会导致 Runtime 因失控、预算耗尽或安全中止退出，却对外声称任务成功。

仍需遵循：

```text
DONE / NO_TOOL_CALL
  → 才允许根据 Tool 结果计算 SUCCEEDED

MAX_ITERATIONS / MAX_TOOL_CALLS / BUDGET_EXHAUSTED / POLICY_HALT
  → 没有成功结果为 FAILED
  → 有已完成结果最多为 PARTIALLY_SUCCEEDED
```

#### P1-4 Trajectory、UI 和日志仍未完整脱敏

位置：

- `core/agent/AgentEngine.java:182`
- `core/agent/AgentEngine.java:212-217`
- `core/agent/AgentEngine.java:272-274`
- `core/agent/AgentEngine.java:362-368`
- `presentation/viewmodel/AgentTestViewModel.java:117-136`

仍可能明文进入 Trajectory、UI 或 Logcat 的字段包括：

- User Request；
- Assistant Message；
- Tool Arguments；
- 原始 Tool Observation；
- PolicyDecision Reason；
- Provider/Policy/Engine Arguments 日志；
- HTTP 非 2xx 响应正文。

新增测试要求 Trajectory 保存原始 Memory Value，因此不是对 P1-4 的修复，而是把风险写进了回归契约。

### 11.4 第二轮新增的 P1：区域修正缺少意图来源

位置：

- `core/policy/PolicyEngine.java:58-65`
- `core/agent/ToolCall.java`

当前将“副驾写主驾区域”归为可修正参数错误，并明确提示模型：

```text
请改用 zone=passenger
```

这种修正在一种情况下合理：模型面对“副驾调温”时错误推断为 `zone=driver`。

但在另一种情况下不合理：用户明确说“把主驾温度调到 24 度”。此时自动把 Zone 改成 Passenger 会执行用户没有要求的动作。

当前 `ToolCall` 只包含最终 Arguments，没有保存：

- 参数来自用户明确表达；
- 参数来自系统默认；
- 参数由模型推断；
- 参数由上下文补全。

Policy 因此无法区分“可修正的模型推断错误”和“不能擅自改变的用户明确意图”。

修复方向：

1. 为关键参数增加来源或约束信息，例如 `ArgumentProvenance`、`ExplicitIntentConstraints`；
2. 用户明确指定的 Zone 一旦越权，只能拒绝或澄清；
3. 模型推断且与 OccupantZone 冲突的 Zone，可以在不改变用户意图的前提下补全或重试；
4. 在来源机制完成前，采用更保守的拒绝/澄清策略，不能提示模型直接换区执行；
5. 分别增加“模型误推断 Zone”和“用户明确指定越权 Zone”测试。

### 11.5 仍未修复的 P2

#### P2-1 三层预算没有完全生效

- `AgentBudget.totalDeadlineMillis` 仍只定义不使用；
- `maxMessageChars` 仍主要作为 Redactor 截断长度；
- User、Assistant 和 Tool Arguments 没有统一单条上限；
- 没有独立最大消息数；
- HTTP Response Body 仍可能在解析前无上限读入内存。

#### P2-2 FinishReason 仍未进入循环控制

- `AgentEngine` 未读取 `ModelTurn.getFinishReason()`；
- Anthropic `stop_reason` 未映射；
- `LENGTH`、空 Assistant 和协议不一致仍缺少明确处理。

#### P2-3 Model 错误仍被归类为 POLICY_HALT

网络、协议解析和模型错误仍没有独立 StopReason，不利于评估、重试和线上诊断。

#### P2-4 Core 仍直接依赖 Android Log

`core/` 下多个类继续导入 `android.util.Log`，纯 Java Core 边界尚未恢复。

#### P2-5 版本与 README 仍未同步

- `app/build.gradle` 仍为 `versionName '0.3.2'`；
- README 顶部仍为 V0.3.2；
- README 当前链路仍是 Planner → TaskPlan；
- README 测试数量仍停留在 24。

在 P1 修复并通过复审前继续保持 V0.3.2 是合理的，但文档应明确“V0.4.0 开发中”，避免代码状态与路线描述混淆。

### 11.6 更新后的修复顺序

1. 恢复正确的数据边界：Model Observation 保留任务语义，Audit Trajectory 默认脱敏；
2. 修复异常 StopReason 的最终状态计算；
3. 实现 OpenAI-Compatible 多轮 Tool/Observation 协议并保留 Tool Call ID；
4. 为 Zone 等关键参数增加显式意图/推断来源，阻止擅自改变用户目标；
5. 完整脱敏 Assistant、Tool Arguments、Policy、UI 和日志；
6. 让 AgentBudget 的总 Deadline、消息数、单条字符、总字符和响应体上限全部生效；
7. 处理 FinishReason 与空/截断响应；
8. 拆分 Model、Network、Protocol 和 Policy 错误；
9. 清理 Core 对 Android Log 的依赖；
10. 重写错误方向的测试并补齐缺失场景；
11. 全部测试通过后再次复审；
12. 复审通过后再更新版本号和 README。

### 11.7 第二轮复审状态

| 项目 | 状态 |
| --- | --- |
| Hermes 式 AgentEngine 核心循环 | 已具备 |
| Anthropic 原生多轮 Tool Calling | 已具备 |
| OpenAI-Compatible 多轮 Tool Calling | 未完成 |
| Observation 回传 | 已具备，但模型/审计视图边界错误 |
| Policy 每轮执行 | 已具备 |
| 异常终止状态 | 未修复 |
| Trajectory 结构化 | 已具备 |
| Trajectory/UI/日志默认脱敏 | 未完成 |
| 三层预算 | 部分完成 |
| FinishReason | 未接入 |
| Core 纯 Java 边界 | 未满足 |
| 单元测试 | 46/46 通过，但部分断言方向错误 |
| V0.4.0 总体状态 | **继续开发，不通过复审** |

---

## 十二、第三轮复审（2026-08-02）

### 12.1 复审结论

第三轮复审继续采用只读代码检查，并使用 `--rerun-tasks` 强制重新执行全部单元测试。

结论：**本轮修复质量有明显提升，但 V0.4.0 仍不能通过复审。** 第二轮指出的“模型视图与审计视图方向错误”和“异常终止可能返回成功”已经修复；区域参数来源也建立了基础数据结构。不过，OpenAI-Compatible 仍未进入真正的多轮 Agent Loop，参数来源在真实 Provider Adapter 路径中没有可靠建立，Trajectory、UI 和 Logcat 的脱敏仍不完整。

当前完成度可评估为约 **85%**。现阶段剩余问题已经从 Agent Loop 主体结构问题，收敛为模型协议、安全意图约束、数据边界和运行时完整性问题。

强制执行：

```bash
./gradlew testDebugUnitTest --rerun-tasks
```

结果：

```text
BUILD SUCCESSFUL
56 tests, 0 failures, 0 errors, 0 skipped
```

测试分布：

| 测试类 | 第二轮 | 第三轮 | 结果 |
| --- | ---: | ---: | --- |
| `AgentEngineTest` | 27 | 30 | 通过 |
| `ModelApiClientContractTest` | 10 | 10 | 通过 |
| `ModelSanitizerTest` | 0 | 9 | 通过 |
| `AuditRedactorTest` | 9（原 `RedactorTest`） | 7 | 通过 |
| 合计 | 46 | 56 | 通过 |

测试全部通过证明新增实现与当前断言一致，但真实 Provider 路径、完整审计脱敏、FinishReason 和完整预算边界仍缺少对应回归测试。

### 12.2 本轮确认已经修复的内容

#### 12.2.1 Model Observation 与 Audit Observation 的方向已经纠正

位置：

- `core/agent/ModelSanitizer.java`
- `core/agent/AuditRedactor.java`
- `core/agent/AgentEngine.java:267-283`

当前已经拆成两个明确的数据视图：

```text
ModelSanitizer
  → 用于下一轮模型 Conversation
  → 保留完成任务所需的 Memory 真实值
  → 对文本中的常见凭据进行遮盖

AuditRedactor
  → 用于 Trajectory、UI 和未来审计持久化
  → memory.preference.save/get 的值替换为 <memory>
```

新增测试 `conversationKeepsSemanticValueForModelWhileTrajectoryIsRedacted` 也验证了正确方向：模型能读取真实偏好，而 Trajectory 不保存完整偏好值。

第二轮 P1-2 的核心方向问题已修复。后续仍需继续补齐 Map、Tool Arguments、Policy 和日志等字段的脱敏覆盖，见 12.4。

#### 12.2.2 异常终止不再返回 SUCCEEDED

位置：

- `core/agent/AgentEngine.java:305-332`
- `core/agent/AgentEngineTest.java:556-596`

`computeFinalState()` 现在先判断 StopReason：

- `DONE` / `NO_TOOL_CALL` 才允许根据 Tool 结果计算 `SUCCEEDED`；
- `CANCELLED` 对应 `CANCELLED`；
- `TIMEOUT` 对应 `TIMED_OUT`；
- `MAX_ITERATIONS`、`MAX_TOOL_CALLS`、`BUDGET_EXHAUSTED`、`POLICY_HALT` 等异常结束，有部分成功结果最多为 `PARTIALLY_SUCCEEDED`，不能为 `SUCCEEDED`。

新增了最大迭代数和最大 Tool Call 数的回归测试。第二轮 P1-3 已修复。

#### 12.2.3 参数来源模型已经建立基础

位置：

- `core/tool/ToolCall.java`
- `core/policy/PolicyEngine.java:58-74`
- `core/agent/DemoModelGateway.java:151-173`

`ToolCall` 新增：

- `USER_EXPLICIT`；
- `MODEL_INFERRED`；
- `SYSTEM_DEFAULT`；
- `CONTEXT_FILLED`。

Policy 已能区分：

- 副驾用户明确要求修改主驾区域：能力级拒绝，不允许擅自换区；
- 模型推断或默认得到错误区域：参数级拒绝，允许模型在预算内重新推断。

对应测试覆盖了“模型误推断 Driver Zone”和“用户明确指定 Driver Zone”两个场景。该设计方向正确，但目前只在 Demo 或手工构造 ToolCall 时可靠，真实 Provider Adapter 仍会丢失来源，见 12.3.2。

#### 12.2.4 Assistant 文本开始进入审计脱敏

`AgentEngine.auditRedactAssistant()` 会在写入 Trajectory 前对 Assistant Content 使用 `AuditRedactor`。这比前两轮直接保存原始 Assistant 文本更安全。

目前只处理了 Content，Assistant Message 中携带的 ToolCall 及其 Arguments 仍保持原样，因此该项属于部分完成。

#### 12.2.5 应用版本号已经更新

`app/build.gradle` 中 `versionName` 已由 `0.3.2` 更新为 `0.4.0`。

README 顶部、架构链路、测试数量和部分路线描述仍未同步，见 12.6.5。

### 12.3 第三轮仍未修复的 P1

#### P1-1 OpenAI-Compatible 仍不是多轮 Agent Loop

位置：

- `platform/LlmModelGateway.java:20-24`
- `platform/LlmModelGateway.java:81-94`
- `platform/ModelApiClient.java:50-121`

当前仍只有：

```text
NATIVE_TOOL_CALLING + ANTHROPIC_MESSAGES
```

会把完整 Conversation 发送给模型。OpenAI-Compatible、本地模型及其他组合继续进入 Legacy Planner 路径。Legacy 路径一旦发现 Conversation 中存在 Tool Result，就固定返回：

```text
LLM 兼容路径:单轮规划已执行,任务结束
```

因此当前已经接通的本地 OpenAI-Compatible 模型仍不能：

- 读取 Tool Observation 后继续推理；
- 根据参数拒绝修正参数；
- 根据执行失败选择替代方案；
- 根据车辆查询结果决定下一步；
- 生成基于真实 Observation 的最终答复。

旧 OpenAI Tool Calling 解析还会丢弃服务端返回的 `tool_calls[].id`，通过 `new ToolCall(...)` 重新生成 UUID。这不满足多轮协议中 Assistant Tool Call 与后续 Tool Message 的关联要求。

修复要求仍为：

1. 增加 OpenAI-Compatible Conversation 序列化；
2. 支持 `assistant.tool_calls` 与 `tool.tool_call_id`；
3. 保留模型返回的原始 Tool Call ID；
4. 支持同一 Assistant Message 的多个 Tool Call；
5. 无 Tool Call 时生成正常 Final Answer；
6. 增加至少两轮 Tool/Observation 的协议契约测试。

#### P1-2 ArgumentProvenance 在真实模型路径中不可靠

位置：

- `core/tool/ToolCall.java:22-23`
- `core/tool/ToolCall.java:89-91`
- `platform/ModelApiClient.java:187-191`

未标注来源的参数默认 `MODEL_INFERRED`。Anthropic Adapter 当前通过：

```java
ToolCall.withId(id, capabilityName, input)
```

构造 ToolCall，没有提供任何受信任的参数来源。OpenAI 旧解析同样没有来源信息。

这会导致真实模型场景出现错误判断：

```text
副驾明确说“把主驾温度调到 24 度”
→ 模型返回 zone=driver
→ Adapter 未标注来源
→ ToolCall 默认 MODEL_INFERRED
→ Policy 将其当成模型脑补
→ 允许模型改成 zone=passenger 后重试
```

现有测试之所以能区分，是因为测试代码或 DemoModelGateway 主动构造了 `USER_EXPLICIT`，尚未覆盖真实模型 Adapter。

不建议允许模型自己声明 `USER_EXPLICIT`，模型输出不是安全可信来源。建议在 Runtime 受信任边界内引入：

```text
ExplicitIntentConstraints
  → 从原始 AgentRequest / 受信任 NLU 得到
  → 保存用户明确指定的 zone、目标、动作和不可替换约束
  → Policy 同时校验 ToolCall Arguments 与 ExplicitIntentConstraints
```

在受信任约束机制完成前，真实 Provider 路径不能认为已经解决了“不得擅自改变用户明确区域”的要求。

#### P1-3 Trajectory、UI 和 Logcat 脱敏仍不完整

位置：

- `core/agent/AgentEngine.java:94-100`
- `core/agent/AgentEngine.java:216-221`
- `core/agent/AgentEngine.java:334-342`
- `core/agent/AgentEngine.java:395-401`
- `core/agent/AuditRedactor.java:80-86`
- `core/policy/PolicyEngine.java:18-23`
- `presentation/viewmodel/AgentTestViewModel.java:124-136`

当前仍可能明文保存或展示：

- User Request 文本；
- ToolCall Arguments；
- Assistant Message 内携带的原始 ToolCall；
- PolicyDecision Reason；
- 普通 Capability 的 observedState Value；
- Engine、Policy、DemoModelGateway 和 MockCapabilityProvider 的 Arguments 日志；
- HTTP 异常响应中的正文片段。

`AgentEngine.snapshots()` 仍直接复制 `call.getArguments()`，而 UI 又直接输出：

```text
args={...}
```

导航地址、联系人、Memory 写入参数和未来账号参数因此仍可能进入 Trajectory/UI。

另外，`AuditRedactor.redactMap()` 与 `ModelSanitizer.sanitizeMap()` 当前都只是浅复制 Map，没有清洗 Value 或嵌套结构。如果凭据位于：

```text
observedState.token = sk-...
observedState.authorization = Bearer ...
```

而不是 ToolResult Message 中，现有正则不会处理它，数据仍会进入模型 Conversation、Trajectory 和 UI。

修复要求：

1. 对 Tool Arguments、observedState 和嵌套 Map/List 做递归字段级处理；
2. 建立 Capability Schema 级敏感字段元数据，避免只靠自由文本正则；
3. Trajectory 保存 Audit ToolCallSnapshot，不保存原始 Arguments；
4. Assistant Message 中的 ToolCall 也必须使用审计副本；
5. 日志默认只记录 requestId、capability、状态、耗时、数量和长度；
6. UI 只渲染完整脱敏后的 Audit Trajectory；
7. 增加 Tool Arguments、普通 observedState、嵌套数据和 Log/UI 输出测试。

### 12.4 第三轮新增的架构问题

#### P2-1 AgentOutcome 的业务结果从 Audit Trajectory 派生

位置：

- `core/agent/AgentOutcome.java:41-57`
- `core/agent/AgentEngine.java:276-283`

当前 `AgentEngine` 写入 Trajectory 的是 AuditRedactor 处理后的 Observation，而 `AgentOutcome.getResults()` 又从 Trajectory 展开 ToolResult。

因此：

```text
Tool 实际结果 preferred_temperature=24
→ Audit Trajectory 保存 preferred_temperature=<memory>
→ AgentOutcome.getResults() 只能返回 <memory>
```

这虽然避免 UI 暴露 Memory，但也让兼容业务调用方失去真实执行结果。后续如果 Orchestrator、Task API 或厂商业务层需要读取 AgentOutcome 中的结构化结果，将只能获得审计占位符。

建议明确拆分：

```text
Internal Execution Results
  → Runtime 内部可信域使用
  → 保留真实 ToolResult

Audit Trajectory
  → UI、日志、评估、未来持久化
  → 字段级脱敏

Public/Safe Outcome View
  → 根据调用方权限和使用场景生成
```

至少应避免让 Audit Trajectory 成为真实 ToolResult 的唯一数据源。若决定 `getResults()` 本身就是安全审计视图，则需要更名或更新 API 文档，避免旧调用方误认为它仍返回原始业务结果。

### 12.5 仍未修复的 P2

#### P2-2 三层预算仍未完全生效

- `AgentBudget.totalDeadlineMillis` 仍没有参与 Agent Loop 的实际截止时间；
- 没有独立最大 Conversation 消息数量；
- `maxMessageChars` 主要用于两个 Redactor，User、System、Assistant、Tool Arguments 没有统一限制；
- Redactor 截取 `maxChars` 后又追加 `[truncated ...]`，最终字符串长度仍可能大于 `maxChars`；
- HTTP Response Body 在 JSON 解析前仍缺少明确字节上限。

Agent Loop 的实际截止时间仍建议取：

```text
min(AgentRequest.deadlineAt, loopStartedAt + AgentBudget.totalDeadlineMillis)
```

#### P2-3 FinishReason 仍未进入循环控制

- `AgentEngine` 没有读取 `ModelTurn.getFinishReason()`；
- Anthropic 响应中的 `stop_reason` 没有映射；
- `LENGTH`、空 Assistant、FinishReason 与 ToolCall 不一致仍可能被错误处理；
- 当前主要通过 `hasToolCalls()` 判断继续或结束，`FinishReason` 数据模型实际没有发挥作用。

#### P2-4 模型错误仍错误归类为 POLICY_HALT

`ModelCallExecutor` 继续把所有 ModelGateway 异常映射为 `POLICY_HALT`。网络、认证、限流、协议解析、模型空响应和本地 Policy 主动终止仍无法区分。

#### P2-5 Core 仍直接依赖 Android Log

`AgentEngine`、`PolicyEngine`、`ToolExecutor`、`ModelSanitizer`、`AuditRedactor`、Session 等 Core 类仍导入 `android.util.Log`，与 README 声明的纯 Java Core 不一致。

#### P2-6 README 尚未同步 V0.4.0 实际状态

虽然 `versionName` 已更新为 `0.4.0`，README 仍存在：

- 顶部当前版本为 V0.3.2；
- 当前链路仍描述一次性 Planner / TaskPlan；
- 单元测试仍写 24 个；
- 路线表仍把 V0.4.0 Agent Loop 描述为下一版本；
- Core 边界说明仍宣称不依赖 Android API。

在剩余 P1 修复前，可以将 README 标记为“V0.4.0 开发中”；正式复审通过后再标记为 V0.4.0 完成。

### 12.6 更新后的修复顺序

1. 实现 OpenAI-Compatible 原生多轮 Tool/Observation 协议，并保留 Tool Call ID；
2. 将用户明确意图建模为 Runtime 可信约束，不依赖模型声明 ArgumentProvenance；
3. 完整脱敏 Tool Arguments、普通/嵌套 observedState、Policy、UI 和 Logcat；
4. 将 Internal ToolResult、Audit Trajectory 和 Public Outcome View 分离；
5. 让总 Deadline、最大消息数、单条字符、总字符和响应体上限全部生效；
6. 解析并执行 FinishReason，处理 LENGTH、空响应和协议不一致；
7. 拆分 Model、Network、Authentication、RateLimit、Protocol 和 Policy 错误；
8. 清理 Core 对 Android Log 的依赖；
9. 补齐真实 Provider、完整脱敏、预算和 FinishReason 测试；
10. 同步 README、版本状态、架构链路和测试数量；
11. 再次执行强制全量测试并申请 V0.4.0 复审。

### 12.7 第三轮复审状态

| 项目 | 状态 |
| --- | --- |
| Hermes 式 AgentEngine 核心循环 | 已具备 |
| Anthropic 原生多轮 Tool Calling | 已具备 |
| OpenAI-Compatible 多轮 Tool Calling | 未完成 |
| Model/Audit Observation 双视图 | 核心方向已修复，Map/嵌套字段仍需补齐 |
| Policy 每轮执行 | 已具备 |
| 异常终止状态 | 已修复 |
| ArgumentProvenance 数据结构 | 已具备 |
| 真实 Provider 下的可信用户意图约束 | 未完成 |
| Assistant 审计脱敏 | 部分完成 |
| Tool Arguments/UI/日志默认脱敏 | 未完成 |
| Internal Result 与 Audit Result 分离 | 未完成 |
| 三层预算 | 部分完成 |
| FinishReason | 未接入 |
| 错误分类 | 未完成 |
| Core 纯 Java 边界 | 未满足 |
| 单元测试 | 56/56 通过 |
| README 与版本状态 | 部分同步 |
| V0.4.0 总体状态 | **继续开发，不通过第三轮复审** |

### 12.8 第三轮最终评价

本轮改动解决了第二轮中最重要的两个实现错误：模型与审计数据视图已经走向正确方向，异常终止状态也不再虚报成功。`ArgumentProvenance` 表明项目已经开始处理 Agent 安全中非常关键的“模型参数不等于用户意图”问题，这个方向值得保留。

当前阻止 V0.4.0 通过的主要问题已收敛为三个：

1. 主力 OpenAI-Compatible/本地模型仍不具备真正多轮 Agent Loop；
2. 用户明确意图的来源没有在真实 Provider 路径中形成可信约束；
3. Trajectory、UI 和 Logcat 的 Tool Arguments、Map Value 等数据仍可能泄漏。

这三项完成后，再处理预算、FinishReason、错误分类和 Core 纯 Java 边界，V0.4.0 就可以进入最终验收阶段。

---

## 十三、第四轮复审（2026-08-02）

### 13.1 复审结论

第四轮复审继续采用只读代码检查，并使用 `--rerun-tasks` 强制重新执行全部单元测试。

结论：**本轮已经补齐 OpenAI-Compatible 原生多轮 Tool Calling，第三轮最大的功能缺口已经关闭；但 V0.4.0 仍不能通过最终复审。** 当前主要阻塞项已经收敛为显式用户目标的一致性校验，以及 Audit Trajectory、UI、Logcat 中仍存在的原始业务敏感参数。

当前完成度可评估为约 **90%**。Agent Loop 主体和主力模型协议已经成立，剩余工作主要属于车端安全边界、协议异常处理和 Runtime 完整性收尾。

强制执行：

```bash
./gradlew testDebugUnitTest --rerun-tasks
```

结果：

```text
BUILD SUCCESSFUL
66 tests, 0 failures, 0 errors, 0 skipped
```

测试分布：

| 测试类 | 第三轮 | 第四轮 | 结果 |
| --- | ---: | ---: | --- |
| `AgentEngineTest` | 30 | 33 | 通过 |
| `ModelApiClientContractTest` | 10 | 16 | 通过 |
| `ModelSanitizerTest` | 9 | 10 | 通过 |
| `AuditRedactorTest` | 7 | 7 | 通过 |
| 合计 | 56 | 66 | 通过 |

新增测试重点覆盖 OpenAI 多轮消息序列化、Tool Call ID 透传、同轮多个 Tool Call、无 Tool Call Final Answer、Object Arguments 兼容、真实模型路径下显式区域约束、Internal Result/Audit Result 分离，以及 Tool Arguments 中常见凭据的脱敏。

### 13.2 本轮确认已经完成的内容

#### 13.2.1 OpenAI-Compatible 已进入原生多轮 Agent Loop

位置：

- `platform/LlmModelGateway.java:20-26`
- `platform/LlmModelGateway.java:47-80`
- `platform/ModelApiClient.java:147-318`
- `platform/ModelApiClientContractTest.java:191-347`

`NATIVE_TOOL_CALLING + OPENAI_CHAT` 现在会进入：

```text
LlmModelGateway
→ ModelApiClient.callOpenAiWithTools()
→ 完整 Conversation
→ ModelTurn
→ AgentEngine
→ Tool Observation
→ 下一轮 OpenAI 请求
```

已具备：

- `system`、`user`、`assistant`、`tool` 四种消息角色；
- `assistant.tool_calls[]` 序列化；
- `tool.tool_call_id` 与 Tool Call ID 关联；
- 模型返回的 `tool_calls[].id` 原样透传；
- 一轮多个 Tool Call；
- `function.arguments` JSON String 格式；
- 部分本地兼容服务返回 JSONObject Arguments 时的兼容解析；
- 无 Tool Call 时返回 Final Answer；
- Canonical Capability Name 与模型工具名映射；
- 未注册工具拒绝。

第三轮 P1-1 的主体功能已经修复。

需要注意：只有模型配置选择 `Native Tool Calling` 才会走新的多轮路径；选择 `Structured JSON Compatibility` 仍会进入旧的一次性 Planner 兼容路径。这是当前明确保留的降级模式，不应与真正 Agent Loop 混淆。

#### 13.2.2 真实模型路径开始使用受信任的 ExplicitIntentConstraints

位置：

- `core/identity/ExplicitIntentConstraints.java`
- `core/identity/AgentRequest.java:12-53`
- `core/policy/PolicyEngine.java:58-81`
- `core/agent/AgentEngineTest.java:556-583`

`AgentRequest.Builder` 会在 Runtime 边界从用户原始文本提取显式 Zone。即使 Anthropic/OpenAI Adapter 构造的 ToolCall 没有标记 `USER_EXPLICIT`，Policy 也可以通过 `AgentRequest.getExplicitIntent()` 识别“副驾明确要求修改主驾”这一场景。

这比依赖模型自己声明 ArgumentProvenance 更可靠，第三轮提出的真实 Provider 来源缺失问题已经得到基础修复。但 Policy 当前只在一个特定越权分支中使用该约束，尚未形成完整的“模型目标必须与用户目标一致”校验，见 13.3.1。

#### 13.2.3 Map/List 中的常见凭据开始递归清洗

位置：

- `core/agent/ModelSanitizer.java:88-116`
- `core/agent/AuditRedactor.java:81-114`

`ModelSanitizer` 与 `AuditRedactor` 都会递归处理 Map 和 List，并对 String Value 应用凭据遮盖。这修复了第三轮指出的“凭据位于 observedState Map 或嵌套结构时绕过文本 Redactor”的问题。

当前主要仍依赖字符串正则，只覆盖部分常见 Key 格式；普通地址、联系人、Memory Value 等业务敏感字段仍需要 Capability Schema 级元数据，见 13.3.2。

#### 13.2.4 ToolCallSnapshot 已使用 Audit Arguments

位置：

- `core/agent/AgentEngine.java:407-420`

写入 Trajectory 的 `ToolCallSnapshot.arguments` 已经过 `AuditRedactor.redactArguments()`，常见 API Key/Bearer Token 不再直接进入 Snapshot 和当前 UI Arguments 输出。

该修复只覆盖 Snapshot。Assistant Message 中仍保留原始 ToolCall，而且 `redactArguments()` 尚不识别普通业务隐私，因此该项仍属于部分完成。

#### 13.2.5 Internal Result 与 Audit Result 已拆分

位置：

- `core/agent/AgentEngine.java:145`
- `core/agent/AgentEngine.java:277-294`
- `core/agent/AgentOutcome.java:9-87`

`AgentOutcome` 现在区分：

```text
getInternalResults()
  → Runtime 内部真实 ToolResult

getTrajectory() / getResults()
  → AuditRedactor 处理后的审计结果
```

第三轮 P2-1 中“Audit Trajectory 成为真实业务结果唯一数据源”的问题已经修复。`getResults()` 已标记为 Deprecated，并明确说明返回的是审计视图。

后续仍需为 `getInternalResults()` 建立真正的调用边界。目前它是 public API，“内部可信域”主要依赖注释约定，见 13.5.5。

#### 13.2.6 常规截断结果已限制在 maxChars 内

`ModelSanitizer.truncateWithSuffix()` 会为 `[truncated N chars]` 预留空间，常规配置下最终字符串不再因为追加后缀而超过 `maxChars`。新增测试也验证了正常长度配置下的上限。

当 `maxChars` 小于截断后缀本身长度时仍存在边界问题，见 13.5.4。

### 13.3 第四轮仍未修复的 P1

#### P1-1 显式用户目标仍可能被模型第一次 ToolCall 直接替换

位置：

- `core/policy/PolicyEngine.java:65-82`
- `core/identity/ExplicitIntentConstraints.java:15-48`

当前仅在以下条件全部成立时读取 ExplicitIntentConstraints：

```text
request.occupantZone == PASSENGER
&& targetZone == DRIVER
&& capability 是写操作
```

这只能覆盖“副驾请求主驾，并且模型仍返回 zone=driver”的情况，不能统一保证模型 ToolCall Target 与用户显式 Target 一致。

遗漏场景一：

```text
副驾说：把主驾温度调到 24 度
模型第一次直接返回：zone=passenger
→ targetZone != DRIVER，不进入显式约束检查
→ 可能执行副驾调温
```

遗漏场景二：

```text
主驾说：把副驾温度调到 24 度
模型错误返回：zone=driver
→ request.occupantZone != PASSENGER
→ 不进入显式约束检查
→ 可能执行主驾调温
```

这两种情况都执行了用户没有要求的区域。安全检查不能依赖模型先生成正确的显式目标。

建议将 Policy 顺序调整为：

```text
1. 从 AgentRequest 读取受信任的 explicitZone
2. explicitZone 存在且 targetZone != explicitZone
   → PARAMETER_REJECTED，要求模型保持用户原目标重试
3. 独立判断 explicitZone 本身是否越权
   → 越权则 CAPABILITY_REJECTED，即使模型已经主动换成合法区域也不能执行
4. 检查模型推断目标与 OccupantZone/Capability 权限
5. 全部通过后才执行 Tool
```

必须补充以下测试：

- 副驾明确要求主驾，但模型第一次返回 Passenger；
- 主驾明确要求副驾，但模型返回 Driver；
- 用户未指定区域，模型误推断 Driver 后按 OccupantZone 纠正；
- 模型后续重试仍不得改变用户明确区域。

此外，当前 `ExplicitIntentConstraints` 只有一个全局 Zone，不能准确表达“主驾和副驾都打开座椅加热”等多目标请求。后续应按 Action/Capability/Step 保存目标约束，或至少使用目标集合，而不是一个请求只有一个 `explicitZone`。

#### P1-2 Audit Arguments 仍然保留业务敏感值

位置：

- `core/agent/AuditRedactor.java:81-114`
- `core/agent/AgentEngine.java:407-420`
- `core/agent/AgentEngineTest.java:603-621`

`redactArguments()` 当前只递归调用凭据字符串正则，不知道参数属于哪个 Capability，也没有字段级敏感元数据。以下普通值不会被遮盖：

```text
destination=北京市某小区
contact=张三
phone=138xxxxxxxx
home=公司地址
memory.preference.save(value=24)
```

现有 `trajectorySnapshotRedactsToolArguments` 测试使用“地址中包含 `sk-...`”作为输入，只能证明 API Key 格式能被遮盖，不能证明普通导航地址或 Memory Value 已经脱敏。

尤其是 Memory：Observation 会按 Capability 全量脱敏，但 `memory.preference.save` 的 Tool Arguments 仍会在 Snapshot 中保留 `key/value` 原值。

建议将 API 改为：

```java
redactArguments(String capabilityName, Map<String, Object> arguments)
```

并结合 Capability/Tool Schema 的敏感字段元数据处理：

- `memory.preference.*`：Value 全量遮盖；
- `navigation.start_route`：destination 遮盖、摘要化或按权限展示；
- 联系人工具：姓名、电话等字段遮盖；
- 普通车控：zone、温度、座椅档位可按策略保留；
- `apiKey`、`authorization`、`token`、设备凭据：按字段名和类型无条件遮盖；
- 嵌套 Map/List：继续递归处理。

#### P1-3 Audit Assistant Message 仍携带原始 ToolCall

位置：

- `core/agent/AgentEngine.java:346-355`
- `core/agent/AgentIteration.java:33-37`

`auditRedactAssistant()` 只处理 Assistant Content，随后仍将原始 ToolCall 列表复制到 Audit Assistant Message。Trajectory 因此同时包含：

```text
ToolCallSnapshot.arguments
  → 已经过 AuditRedactor

AssistantMessage.toolCalls.arguments
  → 原始值
```

当前 UI 主要读取 ToolCallSnapshot，所以页面上未必直接显示这份原始数据；但调用 `iteration.getAssistantMessage().getToolCalls()` 仍能读取未脱敏的地址、Memory Value 或其他参数。

修复要求：

- Audit Assistant 使用重新构造的 Audit ToolCall；或
- Audit Trajectory 中的 Assistant Message 不保存 ToolCall Arguments，只保存 Content 与 Tool Call ID；或
- 统一只保留一种脱敏后的 ToolCall 结构，避免同一 Trajectory 中存在原始与审计两份参数。

### 13.4 OpenAI-Compatible 协议仍需补齐的边界

#### P2-1 缺失或重复 Tool Call ID 没有明确失败

位置：

- `platform/ModelApiClient.java:231-250`
- `core/tool/ToolCall.java:61-70`

OpenAI Parser 使用：

```java
String id = toolCall.optString("id", "");
ToolCall.withId(id, ...)
```

而 ToolCall 构造器会为空 ID 自动生成 UUID。Runtime 因此无法区分 ID 是模型返回还是本地伪造。缺失 ID、空 ID和同一 Assistant Message 内重复 ID 都应视为 Provider 协议错误，不能静默补值。

建议：

- OpenAI/Anthropic Adapter 要求非空 ID；
- 同一 Assistant Message 中 ID 必须唯一；
- Tool Result 必须能关联当前尚未完成的 Tool Call；
- 非法情况返回 `PROTOCOL_ERROR`；
- 增加缺失、空值、重复和未知 Tool Call ID 测试。

#### P2-2 OpenAI finish_reason 仍被忽略

位置：

- `platform/ModelApiClient.java:218-226`
- `core/agent/AgentEngine.java:184-195`

无 Tool Call 时虽然读取并打印了 `finish_reason`，但随后总是调用：

```java
ModelTurn.directAnswer(content)
```

这会把以下响应统一转成 `FinishReason.STOP`：

- `finish_reason=stop`；
- `finish_reason=length`；
- `content_filter` 或厂商扩展终止原因；
- 缺失 finish_reason；
- 空 Content。

模型输出因 `max_tokens` 截断时仍可能被 AgentEngine 当作正常 Final Answer，并返回 `SUCCEEDED`。

要求 Provider Adapter 正确映射 FinishReason，AgentEngine 至少处理：

- `STOP`：必须有有效 Final Answer；
- `TOOL_CALLS`：必须存在一个或多个合法 Tool Call；
- `LENGTH`：不能成功，返回结构化模型截断错误；
- 未知/缺失原因：按 `PROTOCOL_ERROR` 处理；
- 空 Assistant：不能作为正常任务完成。

### 13.5 仍未修复的 P2

#### P2-3 Logcat 仍保存原始业务数据

当前仍存在：

- AgentEngine 记录 User Request 文本；
- AgentEngine、PolicyEngine、DemoModelGateway、MockCapabilityProvider 记录原始 Arguments；
- Tool Result Message 进入日志；
- SessionContext Recent Turns 保存完整 User Request；
- HTTP 非 2xx 响应正文进入 Logcat 和异常 Message。

AuditRedactor 只修复 Trajectory 不足以满足“UI/Log/未来持久化统一审计边界”。量产默认日志仍应只记录 ID、Capability、状态、耗时、数量和长度。

#### P2-4 完整预算仍未生效

- `AgentBudget.totalDeadlineMillis` 仍未参与 Loop Deadline；
- 没有独立最大 Conversation 消息数量；
- User、System、Assistant 和 Tool Arguments 没有统一单条消息上限；
- HTTP Response Body 仍由 `readAll()` 无上限读入；
- `truncateWithSuffix()` 在 `maxChars` 小于后缀长度时仍可能返回超过上限的字符串。

#### P2-5 Internal Result 的可信域没有代码级边界

`AgentOutcome.getInternalResults()` 是公开方法。当前“不能用于 UI/Log”只写在 Javadoc 中，任何 Presentation 或其他调用方都能直接读取并展示真实 Memory/地址。

后续建议：

- Internal Outcome 与 Public/Audit Outcome 使用不同类型；
- 内部类型限制在 Runtime/Data Package；
- Binder/厂商 API 只返回按权限生成的 Public Outcome；
- UI ViewModel 的依赖中不暴露 Internal Result API。

#### P2-6 错误分类仍未完成

ModelGateway 的网络、认证、限流、协议解析和空响应异常仍主要被映射为 `POLICY_HALT`。后续评估、受控重试和线上诊断仍无法区分真正失败来源。

#### P2-7 Core 仍依赖 Android Log

Core 下 AgentEngine、PolicyEngine、ToolExecutor、Session、ModelSanitizer 和 AuditRedactor 等类继续导入 `android.util.Log`，纯 Java Core 边界尚未恢复。

#### P2-8 README 仍未同步

README 仍存在：

- 当前版本 V0.3.2；
- 24 个测试；
- 当前架构仍是 Planner → TaskPlan；
- V0.4.0 仍被描述为下一版本；
- Core 被描述为不依赖 Android API。

实际代码已经进入 V0.4.0 Agent Loop，并有 66 个测试。正式标记完成前可以写为“V0.4.0 开发中”，但架构事实和测试数量应及时同步。

### 13.6 第四轮后的建议修复顺序

1. 将 ExplicitIntentConstraints 提升为所有显式 Target 的统一 Policy 前置约束；
2. 修复模型第一次改变用户目标仍可能执行的问题，并补齐对应测试；
3. 让 AuditRedactor 按 Capability/Schema 脱敏 Memory、导航、联系人等业务字段；
4. 移除 Audit Assistant Message 中的原始 ToolCall Arguments；
5. 清理 User Request、Arguments、Tool Message 和 HTTP Error Body 的原始日志；
6. 校验 OpenAI/Anthropic Tool Call ID 非空、唯一且关联有效；
7. 解析并执行 FinishReason，拒绝 LENGTH、空响应和协议不一致；
8. 让总 Deadline、消息数、单条长度、总字符和响应体上限全部生效；
9. 拆分 Model、Network、Authentication、RateLimit、Protocol 和 Policy 错误；
10. 为 Internal/Public/Audit Outcome 建立类型和权限边界；
11. 清理 Core 对 Android Log 的依赖；
12. 同步 README 与测试数量；
13. 强制执行全量测试并进入 V0.4.0 最终复审。

### 13.7 第四轮复审状态

| 项目 | 状态 |
| --- | --- |
| Hermes 式 AgentEngine 核心循环 | 已具备 |
| Anthropic 原生多轮 Tool Calling | 已具备 |
| OpenAI-Compatible 原生多轮 Tool Calling | 主体已完成 |
| OpenAI Tool Call ID 透传 | 已完成 |
| OpenAI 多 Tool Call/Observation 序列化 | 已完成 |
| OpenAI FinishReason/协议异常 | 未完成 |
| Model/Audit Observation 双视图 | 已完成主体 |
| 嵌套 Map/List 凭据清洗 | 已完成基础 |
| ExplicitIntentConstraints | 已具备基础，尚未统一约束所有目标 |
| 模型不得改变用户显式目标 | 未完全满足 |
| ToolCallSnapshot 脱敏 | 部分完成，仅覆盖常见凭据字符串 |
| Audit Assistant ToolCall 脱敏 | 未完成 |
| Memory/导航/联系人业务字段脱敏 | 未完成 |
| Internal Result 与 Audit Result 分离 | 已完成结构拆分 |
| Internal Result 权限边界 | 未完成 |
| 异常终止状态 | 已修复 |
| 三层预算 | 部分完成 |
| 错误分类 | 未完成 |
| Core 纯 Java 边界 | 未满足 |
| 单元测试 | 66/66 通过 |
| README 与版本状态 | 部分同步 |
| V0.4.0 总体状态 | **继续收尾，不通过第四轮最终复审** |

### 13.8 第四轮最终评价

本轮完成了 V0.4.0 最重要的模型协议补齐：此前只能由 Anthropic 执行的多轮 Tool/Observation Loop，现在已经扩展到 OpenAI-Compatible 和本地模型。MatrixAgent 的 Provider-neutral AgentEngine 因此开始真正服务于当前主力模型接入，而不再只是 Anthropic 专用路径。

`ExplicitIntentConstraints`、递归 Map/List 清洗、Audit Snapshot 和 Internal/Audit Result 分离也都沿着正确方向推进。当前问题不再是“是否有 Agent Loop”，而是“这个 Agent Loop 是否能在车端严格保持用户目标、协议一致性和审计安全”。

进入最终验收前，必须优先关闭两个安全问题：

1. 无论模型第一次生成什么参数，都不能绕过或替换用户明确指定的目标区域；
2. Audit Trajectory 中不能通过 Assistant ToolCall、Memory Arguments、导航地址或日志保留原始业务敏感数据。

完成上述两项，再补齐 FinishReason、Tool Call ID 异常、预算和错误分类后，V0.4.0 才适合标记为正式完成。

---

## 十四、第五轮 P1/P2 修复记录（2026-08-02）

### 14.1 修复结论

本轮针对第四轮复审 13.3 / 13.4 中标记为未完成的 3 个 P1 与 2 个 OpenAI 协议 P2 全部闭环。

测试结果:`./gradlew testDebugUnitTest` 共 **86 个测试全部通过**(AgentEngineTest 40、ModelApiClientContractTest 24、AuditRedactorTest 12、ModelSanitizerTest 10),0 failures / 0 errors。`assembleDebug` + `install` + 启动均无崩溃。

### 14.2 已修复项

#### 已修复 P1-1:ExplicitIntent 提升为所有写操作的统一前置约束

位置:

- `core/policy/PolicyEngine.java:46-90`
- `core/agent/AgentEngineTest.java`(新增 4 个端到端回归)

第四轮 13.3.1 指出的两种遗漏场景已闭环:

```text
1) 副驾说"主驾调温",模型第一次返 zone=passenger
   → 旧 Policy 不进显式约束检查(只看 targetZone=DRIVER) → 执行了用户没要求的副驾调温
   → 新 Policy:explicitZone=DRIVER,targetZone=PASSENGER 不一致 → PARAMETER 拒绝
     模型重试用 zone=DRIVER 后,DRIVER 越权 → CAPABILITY 拒绝(不可上诉)
     最终 FAILED,从未实际调温 ✓

2) 主驾说"副驾调温",模型返 zone=driver
   → 旧 Policy 不进显式约束检查(occupantZone != PASSENGER) → 执行了用户没要求的主驾调温
   → 新 Policy:explicitZone=PASSENGER,targetZone=DRIVER 不一致 → PARAMETER 拒绝
     模型重试用 zone=PASSENGER 后,通过(driver 写 passenger 合法)
     最终 SUCCEEDED ✓
```

修复结构(评审 13.3.1 建议 5 步顺序):

```java
private PolicyDecision checkExplicitZoneConsistency(AgentRequest request, ToolCall call,
        String cap, VehicleZone targetZone) {
    ExplicitIntentConstraints constraints = request.getExplicitIntent();
    if (constraints == null || !constraints.hasExplicitZone()) {
        // 没有显式约束时,走原有兜底:副驾脑补 zone=DRIVER 让模型换值重试
        if (request.getOccupantZone() == VehicleZone.PASSENGER
                && targetZone == VehicleZone.DRIVER) {
            boolean userExplicitProvenance =
                    call.provenanceOf("zone") == ToolCall.ArgumentProvenance.USER_EXPLICIT;
            if (userExplicitProvenance) return PolicyDecision.denyCapability(...);
            return PolicyDecision.denyParameter(...);
        }
        return null;  // 交回主流程 ALLOW
    }
    VehicleZone explicitZone = constraints.getExplicitZone();
    // 1. 模型目标与用户明确目标不一致 → 让模型保持用户原目标重试
    if (targetZone != explicitZone) return PolicyDecision.denyParameter(...);
    // 2. explicitZone 本身越权 → CAPABILITY 拒绝不可上诉
    if (isCrossZoneViolation(request.getOccupantZone(), explicitZone)) {
        return PolicyDecision.denyCapability(...);
    }
    return PolicyDecision.allow();
}
```

新增回归测试:

- `passengerExplicitDriverRejectsModelReturnedPassengerFirstAttempt`(漏场景 1 第 1 轮)
- `passengerExplicitDriverEndToEndRejectsBothWrongAndViolationRetry`(漏场景 1 端到端)
- `driverExplicitPassengerRejectsModelReturnedDriverThenAllowsRetry`(漏场景 2)
- `modelStubbornlyReturnsWrongZoneIsRejectedEveryIteration`(模型固执返回错值每次都被拒)

#### 已修复 P1-2:Capability Schema 业务字段脱敏

位置:

- `core/capability/ToolParameterDefinition.java`(加 `sensitive` + `sensitivePlaceholder`)
- `core/capability/CapabilityRegistry.java`(标 memory.preference.value/key、navigation.destination 为 sensitive)
- `core/agent/AuditRedactor.java`(加 `redactArguments(cap, args)` schema-aware + builtin 凭据 key 兜底)
- `core/agent/AgentEngine.java`(注入 CapabilityRegistry 到 AuditRedactor,所有 auditArguments 调用切到 schema-aware 版本)
- `core/agent/AuditRedactorTest.java`(新增 5 个 schema 脱敏测试)

修复后行为:

| capability | 字段 | 修复前(Audit 视图) | 修复后(Audit 视图) |
| --- | --- | --- | --- |
| `memory.preference.save` | value | `24`(原值) | `<memory>` |
| `memory.preference.get` | key | `home_address`(原值) | `<memory>` |
| `navigation.start_route` | destination | `北京市某小区 5 号楼` | `<destination>` |
| 任意 capability | `api_key` / `token` / `bearer` / `authorization` | 字符串正则 mask sk-xxx / Bearer xxx | 字段名匹配直接 `***`(不依赖值正则) |
| 普通车控 | zone / temperature | 原值 | 原值(保留以利诊断) |

设计要点:

- `AuditRedactor` 新构造函数 `(maxChars, CapabilityRegistry)`,旧构造函数保持兼容(legacy 模式只跑凭据正则)
- `redactArguments(String capabilityName, Map<String, Object> args)` 按 capability 查 ToolDefinition,对每个 entry:
  - schema 标 sensitive → 替换为 `param.getSensitivePlaceholder()`
  - key 命中 builtin 凭据名(api_key/token/bearer/authorization/secret/password 等,大小写不敏感)→ `***`
  - 否则递归 `redactValue`(原有凭据正则 + 截断)
- 不在 registry 中的 capability 退化为 legacy `redactArguments(Map)`,只跑凭据正则
- **关键设计**:`key` 不在 builtin 凭据名里(否则会误伤 memory.preference 的 key 字段——`preferred_temperature` 不是凭据)。`apiKey` 等明确字段名仍然无条件 mask

新增回归测试:

- `schemaRedactsMemoryPreferenceValue`(memory preference value → `<memory>`)
- `schemaRedactsNavigationDestination`(destination → `<destination>`)
- `builtinCredentialKeysAreMaskedEvenWithoutSchema`(API_KEY/Token/Authorization 字段名大小写不敏感 mask)
- `nonSensitiveArgsAreKeptIntact`(zone/temperature 原值保留)
- `withoutRegistryFallsBackToCredentialOnly`(legacy 兼容性)
- `memoryPreferenceValueIsRedactedBySchema`(AgentEngine 端到端,snapshot.arguments.value 是 `<memory>`)

#### 已修复 P1-3:Audit Assistant ToolCall arguments 脱敏

位置:

- `core/agent/AgentEngine.java:364-390`(`auditRedactAssistant`)
- `core/agent/AgentEngineTest.java`(`auditAssistantToolCallsArgumentsAreRedacted`)

旧实现 `auditRedactAssistant` 只 `redact(content)`,ToolCall 列表原样复制;新实现每个 ToolCall 重新构造,arguments 走 `auditRedactor.redactArguments(capabilityName, arguments)`:

```java
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
                    auditRedactor.redactArguments(
                            call.getCapabilityName(), call.getArguments())));
        }
    }
    return AgentMessage.assistant(redacted, redactedCalls);
}
```

修复后 Trajectory 的两份参数一致脱敏(ToolCallSnapshot 与 AssistantMessage.toolCalls)。新增测试断言 `api_key="sk-xxx"` 在 audit assistant ToolCall 中是 `***`。

#### 已修复 P2-1:Tool Call ID 校验

位置:

- `core/tool/ToolCall.java`(`withId(...)` 拒绝空/blank id,抛 IllegalArgumentException)
- `platform/ModelApiClient.java`(OpenAI/Anthropic parser 检测空/重复 id 抛 IllegalStateException)
- `platform/ModelApiClientContractTest.java`(4 个新测试)

修复后行为:

| 场景 | 旧行为 | 新行为 |
| --- | --- | --- |
| OpenAI `tool_calls[i].id` 缺失 | `optString("id","")` → withId("") → 自动 UUID(静默补值) | `IllegalStateException("OpenAI 响应缺失 tool_calls[i].id,Provider 协议不一致")` |
| OpenAI 同一 message 重复 id | 自动 UUID 给每个重复项(运行时无法关联 tool_result) | `IllegalStateException("重复 id=xxx")` |
| Anthropic `tool_use.id` 缺失 | `getString("id")` 抛 JSONException(未结构化) | 结构化 `IllegalStateException` |
| Anthropic 重复 id | 静默接受 | `IllegalStateException` |

关键设计:Runtime 自造的 ToolCall(`new ToolCall(cap, args)` / DemoModelGateway / 测试)仍走自动 UUID;只有 Provider Adapter 路径(`ToolCall.withId(...)`)拒绝空 id,这样既不破坏 Demo 路径,又能让协议错误显式失败。

新增测试:

- `openAiNativeResponseRejectsMissingToolCallId`
- `openAiNativeResponseRejectsDuplicateToolCallIds`
- `anthropicNativeResponseRejectsMissingToolUseId`
- `anthropicNativeResponseRejectsDuplicateToolUseIds`

#### 已修复 P2-2:finish_reason / stop_reason 映射

位置:

- `core/agent/StopReason.java`(新增 `LENGTH_EXCEEDED`)
- `core/agent/ModelTurn.java`(新增 `of(content, finishReason)` 工厂方法)
- `platform/ModelApiClient.java`(`mapOpenAiFinishReason` + `mapAnthropicStopReason` 私有静态,OpenAI/Anthropic parser 调用)
- `core/agent/AgentEngine.java`(LENGTH 时走 LENGTH_EXCEEDED 异常终止)
- 5 个新测试

OpenAI finish_reason 映射:

| raw | FinishReason |
| --- | --- |
| `"stop"` | `STOP` |
| `"length"` | `LENGTH` |
| `"tool_calls"` | `TOOL_CALLS` |
| `"content_filter"` / 其他 / 缺失 | `NONE` |

Anthropic stop_reason 映射:

| raw | FinishReason |
| --- | --- |
| `"end_turn"` / `"stop_sequence"` | `STOP` |
| `"max_tokens"` | `LENGTH` |
| `"tool_use"` | `TOOL_CALLS` |
| 其他 / 缺失 | `NONE` |

AgentEngine 处理 LENGTH:

```java
ModelTurn turn = result.getTurn();
conversation.add(turn.getAssistantMessage());

// 第四轮 P2-2:模型输出因 max_tokens 截断时,Observation/答复可能不完整,
// 不能视为正常完成。无论是否伴随 tool call 都走异常终止。
if (turn.getFinishReason() == FinishReason.LENGTH) {
    stopReason = StopReason.LENGTH_EXCEEDED;
    stopMessage = "模型输出因 max_tokens 截断(finish_reason=length)";
    trajectory.addIteration(...);
    break;
}
```

`computeFinalState` 已经把 LENGTH_EXCEEDED 当异常终止(不在 `DONE/NO_TOOL_CALL` normalStop 列表),走 `successCount == 0 ? FAILED : PARTIALLY_SUCCEEDED`,**不能 SUCCEEDED**。

新增测试:

- `openAiNativeLengthFinishReasonMapsToLengthEnum`
- `openAiNativeUnknownFinishReasonMapsToNone`
- `anthropicNativeMaxTokensStopReasonMapsToLength`
- `anthropicNativeEndTurnStopReasonMapsToStop`
- `lengthFinishReasonExitsLoopAsLengthExceededNotSucceeded`(AgentEngine 端到端,验证 SUCCEEDED 不会被错误归为 LENGTH 终止)

### 14.3 本轮修复状态

| 项目 | 状态 |
| --- | --- |
| Hermes 式 AgentEngine 核心循环 | 已具备 |
| Anthropic 原生多轮 Tool Calling | 已具备 |
| OpenAI-Compatible 原生多轮 Tool Calling | 已具备 |
| OpenAI Tool Call ID 透传 | 已具备 |
| OpenAI Tool Call ID 校验(缺失/重复) | **已具备(本轮闭环)** |
| OpenAI finish_reason 映射 | **已具备(本轮闭环)** |
| OpenAI 多 Tool Call/Observation 序列化 | 已具备 |
| Model/Audit Observation 双视图 | 已具备 |
| 嵌套 Map/List 凭据清洗 | 已具备 |
| **ExplicitIntentConstraints 统一前置约束** | **已具备(本轮闭环)** |
| 模型不得改变用户显式目标 | **已满足(本轮闭环)** |
| ToolCallSnapshot 脱敏 | 已具备 |
| Audit Assistant ToolCall arguments 脱敏 | **已具备(本轮闭环)** |
| Memory/导航/联系人 Schema 业务字段脱敏 | **已具备(本轮闭环)** |
| Internal Result 与 Audit Result 分离 | 已具备 |
| 异常终止状态 | 已具备 |
| 三层预算 | 部分完成 |
| 错误分类 | 未完成 |
| Core 纯 Java 边界 | 未满足 |
| 单元测试 | **86/86 通过** |
| README 与版本状态 | 部分同步 |
| **V0.4.0 总体状态** | **所有 P1 闭环,可申请第六轮最终复审** |

### 14.4 剩余 P2（不阻塞 V0.4.0，留 V0.4.x）

- **P2-3 Logcat 原始业务数据**:User Request 文本、原始 Arguments、Tool Message、HTTP Error Body 仍进 Logcat。
- **P2-4 完整预算**:`totalDeadlineMillis` 未进 Loop Deadline;无独立最大消息数量;HTTP Response Body 仍无字节上限;`truncateWithSuffix` 在 `maxChars` 小于后缀长度时仍可能超长。
- **P2-5 Internal Result 类型边界**:`getInternalResults()` 是 public API,只靠 Javadoc 约定"不能用于 UI/Log",没有代码级限制。
- **P2-6 错误分类**:ModelGateway 异常仍统一映射为 `POLICY_HALT`,Network/Auth/RateLimit/Protocol 未拆。
- **P2-7 Core Android Log**:AgentEngine、PolicyEngine、ToolExecutor、ModelSanitizer、AuditRedactor、Session 等仍 `import android.util.Log`。
- **P2-8 README 同步**:仍写 V0.3.2 / 24 个测试 / Planner→TaskPlan / V0.4.0 下一版本 / Core 不依赖 Android API。

### 14.5 第六轮复审建议

剩余的 6 个 P2 项**不阻塞 V0.4.0 主版本号定稿**——核心 Agent Loop、协议正确性、用户目标一致性、Audit 脱敏已经全部闭环。建议:

1. **V0.4.0 正式发版**(标记 `versionCode=7` / `versionName=0.4.0`),附 README 临时标注"V0.4.0 开发已完成,Logcat/预算/错误分类 在 V0.4.1 收尾"
2. **V0.4.1** 集中收尾 P2-3 Logcat + P2-7 Core Android Log(都是日志边界,一并处理)
3. **V0.4.2** P2-4 完整预算 + P2-6 错误分类(Runtime 完整性)
4. **V0.4.3** P2-5 Internal Result 类型边界 + P2-8 README 同步
5. **V0.5.0** Token 预算切 tokenizer、四层 Memory、Trajectory 持久化、Gemini 原生 Tool Calling、Prompt Builder 模块化

第六轮复审可重点验证:

- 真实 MTPLX/Qwen 模型 + NATIVE_TOOL_CALLING + OPENAI_CHAT 跑两轮以上 Tool/Observation 复合任务;
- 副驾说"主驾调温",观察是否每次都被拒绝(无论是 PARAMETER 还是 CAPABILITY),最终 FAILED;
- Audit Trajectory 中 destination / memory value / phone 等业务敏感字段是否全部脱敏;
- 故意触发 LENGTH(把模型 max_tokens 设小),观察 finalState 是否为 FAILED/PARTIALLY_SUCCEEDED 而非 SUCCEEDED;
- OpenAI 服务器返回缺失/重复 tool_call.id 时是否显式失败而非静默补 UUID。



## 十五、第五轮复审（2026-08-02）

### 15.1 复审结论

第五轮复审继续采用只读代码检查，并使用 `--rerun-tasks` 强制重新执行全部单元测试。

结论：**第十四章记录的修复在标准输入路径上已经基本生效，但“所有 P1 已闭环、可正式发版”的判断过早，V0.4.0 仍不能通过第五轮最终复审。** 本轮发现的剩余问题集中在歧义区域解析、ToolResult/Observation 审计脱敏、未知 Schema 的 fail-open 行为，以及带 Tool Call 的 FinishReason 协议一致性。这些问题可能导致错误区域控制、截断 ToolCall 被执行或隐私数据进入 Trajectory/UI，仍属于最终验收前必须关闭的安全边界。

当前完成度约为 **92%—93%**。

强制执行：

```bash
./gradlew testDebugUnitTest --rerun-tasks
```

结果：

```text
BUILD SUCCESSFUL
86 tests, 0 failures, 0 errors, 0 skipped
```

测试分布：

| 测试类 | 第四轮 | 第五轮 | 结果 |
| --- | ---: | ---: | --- |
| `AgentEngineTest` | 33 | 40 | 通过 |
| `ModelApiClientContractTest` | 16 | 24 | 通过 |
| `ModelSanitizerTest` | 10 | 10 | 通过 |
| `AuditRedactorTest` | 7 | 12 | 通过 |
| 合计 | 66 | 86 | 通过 |

新增测试证明显式 Zone 一致性、Schema-aware Arguments 脱敏、Audit Assistant ToolCall、Tool Call ID 校验和常规 LENGTH 已经落地。但尚未覆盖“副驾驶位”歧义、导航 ToolResult 中的真实地址、未知 Capability/额外参数、ToolCall 与 LENGTH 同时出现，以及 FinishReason.NONE 的最终 TaskState。

### 15.2 本轮确认已经修复的内容

#### 15.2.1 显式 Zone 一致性已成为写操作前置约束

位置：

- `core/policy/PolicyEngine.java:58-145`
- `core/agent/AgentEngineTest.java:585-711`

Policy 现在会先比较模型 `targetZone` 与用户 `explicitZone`，不一致时返回 `PARAMETER_REJECTED`；模型对齐用户目标后，再独立判断显式目标是否越权，越权则返回 `CAPABILITY_REJECTED`。

已覆盖：

- 副驾明确要求主驾，模型第一次擅自改成 Passenger；
- 模型重试 Driver 后因越权被硬拒绝；
- 主驾明确要求副驾，模型错误返回 Driver 后修正；
- 模型持续拒绝对齐用户目标时不能成功。

第四轮 P1-1 的 Policy 主路径已经修复。剩余问题来自显式意图提取器本身的关键词歧义和单 Zone 表达能力，见 15.3.1。

#### 15.2.2 Schema-aware Tool Arguments 脱敏已接入 Engine

位置：

- `core/capability/ToolParameterDefinition.java`
- `core/capability/CapabilityRegistry.java:84-109`
- `core/agent/AuditRedactor.java:121-176`
- `core/agent/AgentEngine.java:441-454`

Tool Parameter 已支持 `sensitive` 和 `sensitivePlaceholder`。当前已标记：

- `navigation.start_route.destination` → `<destination>`；
- `memory.preference.save.value` → `<memory>`；
- `memory.preference.get.key` → `<memory>`。

ToolCallSnapshot 使用 `redactArguments(capabilityName, arguments)`，常见凭据字段名也会无条件遮盖。Arguments 主路径已修复，但 ToolResult/Observation 尚未使用同一套 Schema 投影，未知字段也仍然 fail-open。

#### 15.2.3 Audit Assistant ToolCall 已重新构造

位置：

- `core/agent/AgentEngine.java:363-389`

Audit Assistant 现在会同时脱敏 Content 和 ToolCall Arguments，并保留 Tool Call ID 与 Capability Name。同一 Trajectory 不再同时保存脱敏 Snapshot 和原始 Assistant Arguments。第四轮 P1-3 已修复。

#### 15.2.4 OpenAI/Anthropic Tool Call ID 已严格校验

Provider Tool Call ID 现在必须非空、非 blank、同一 Assistant Message 内唯一。缺失或重复 ID 会明确失败，不再静默生成 UUID。OpenAI 与 Anthropic 均有契约测试。

#### 15.2.5 无 Tool Call 的 LENGTH 已进入 AgentEngine

OpenAI `finish_reason=length` 和 Anthropic `stop_reason=max_tokens` 在无 Tool Call 时会映射为 `FinishReason.LENGTH`，AgentEngine 使用 `LENGTH_EXCEEDED` 异常终止，最终状态不能为 `SUCCEEDED`。

该修复尚未覆盖响应同时携带 Tool Call 和 LENGTH 的情况，见 15.3.4。

### 15.3 第五轮仍未修复的 P1

#### P1-1 “副驾驶位”会被误识别为 DRIVER

位置：

- `core/identity/ExplicitIntentConstraints.java:43-47`

当前先匹配“主驾”或“驾驶位”，再匹配“副驾”或“副驾驶”。由于“副驾驶位”包含“驾驶位”，以下请求会被错误提取为 `explicitZone=DRIVER`：

```text
主驾说：把副驾驶位温度调到 24 度
```

模型正确返回 Passenger 后，Policy 会因为与错误的 explicitZone 不一致而要求模型改成 Driver；模型重试 Driver 后，主驾具备该权限，最终可能执行错误区域。

修复要求：

1. 优先匹配更具体的“副驾驶位/副驾驶/副驾”；
2. 使用互斥词典或边界正则，避免子串重叠；
3. 对“主驾和副驾”等多目标请求返回集合或结构化 Step 约束；
4. 歧义或冲突表达不要生成单值权威约束；
5. 增加“副驾驶位”“主副驾同时”等测试。

#### P1-2 导航 ToolResult/Observation 仍泄漏真实目的地

位置：

- `core/tool/MockCapabilityProvider.java:85-101`
- `core/agent/AuditRedactor.java:201-218`
- `presentation/viewmodel/AgentTestViewModel.java:135-137`

导航执行结果仍包含：

```text
message = 已开始导航到“北京市某小区”
observedState = {navigation.destination=北京市某小区}
```

Snapshot 和 Audit Assistant 的 destination 已替换为 `<destination>`，但非 Memory Observation 只运行通用凭据正则，所以真实地址仍会进入 Trajectory、UI 和未来持久化。

需要为 ToolResult 建立 Schema-aware Audit Projection：定义敏感结果字段和 Audit Message Template。例如导航审计结果应只保存“已开始导航”和 `navigation.destination=<destination>`。

#### P1-3 未知 Capability 和 Schema 外参数仍然 fail-open

位置：

- `core/agent/AuditRedactor.java:139-163`
- `core/capability/CapabilityRegistry.java:43-55`

找不到 ToolDefinition 时会退回 credential-only Redactor；已知 Tool 的 Schema 外参数也会保留普通字符串。被 Policy 拒绝的未知/R3 ToolCall 仍可将地址、联系人等写入 Audit Trajectory。

审计边界应 fail-closed：

- 未注册/R3 Capability 的参数 Value 默认全部遮盖；
- Schema 未声明的额外参数在 Policy 阶段拒绝，Audit 侧仍遮盖；
- 只有 Schema 明确标记为可审计公开的字段才能保留；
- 增加未知 Tool、R3 Tool 和额外字段测试。

#### P1-4 带 Tool Call 的 LENGTH 仍可能被执行

位置：

- `platform/ModelApiClient.java:217-268`
- `platform/ModelApiClient.java:394-403`
- `core/agent/AgentEngine.java:189-202`

OpenAI Parser 只在 `tool_calls` 为空时映射 `rawFinishReason`。如果响应同时包含 `finish_reason=length` 和部分 ToolCall，当前会调用 `ModelTurn.ofToolCalls()` 并把 FinishReason 变成 `TOOL_CALLS`，AgentEngine 随后可能执行被截断的参数。Anthropic 的 `tool_use + max_tokens` 也有相同问题。

要求：

- 解析 ToolCall 前先校验 Provider FinishReason；
- `length/max_tokens` 无论是否携带 ToolCall 都禁止执行；
- 有 ToolCall 时 FinishReason 必须与 ToolCall 状态一致；
- `STOP + ToolCall`、`TOOL_CALLS + 空列表`、未知组合按 `PROTOCOL_ERROR` 处理；
- 增加相关契约测试。

### 15.4 FinishReason 仍存在的协议问题

#### P2-1 FinishReason.NONE 和空 Final Answer 仍会返回成功

位置：

- `core/agent/ModelTurn.java:43-61`
- `core/agent/AgentEngine.java:189-213`
- `platform/ModelApiClient.java:674-719`

Provider 缺失或返回未知 FinishReason 时会映射为 `NONE`。AgentEngine 只特别处理 `LENGTH`，所以 `NONE` 会继续进入 `NO_TOOL_CALL → SUCCEEDED`；空 Assistant Content 也会成功。

此外，`ModelTurn.of(content, FinishReason.TOOL_CALLS)` 会把非法组合静默降级为 `STOP`，隐藏协议错误。

建议：

- `NONE` → `PROTOCOL_ERROR`；
- `STOP` 必须携带有效非空 Final Answer；
- `TOOL_CALLS` 必须配非空 ToolCall 列表；
- 非法组合直接失败，不做静默归一化；
- 增加 AgentEngine 级测试。

### 15.5 其他仍未修复的问题

- `memory.preference.save.key` 未标记敏感，可能暴露 `home_address` 等偏好类别；Memory Observation 仍保留原 Map Key。
- User Request、原始 Arguments、导航地址、Tool Result Message、HTTP Error Body 仍进入 Logcat。
- `AgentBudget.totalDeadlineMillis` 仍未使用；没有最大消息数量、统一单条长度和 HTTP Response Body 上限。
- Model/Network/Auth/RateLimit/Protocol 错误仍主要归入 `POLICY_HALT`。
- `getInternalResults()` 的可信域只有注释，没有类型或权限隔离。
- Core 下仍有 11 处 `android.util.Log` 依赖。
- README 仍显示 V0.3.2、24 个测试和旧 Planner 架构；实际测试为 86 个。

### 15.6 第五轮后的建议修复顺序

1. 修复显式意图提取的“副驾驶位”误判和多区域歧义；
2. 为 ToolResult/Observation 增加按 Capability 的敏感结果字段与 Audit Message Projection；
3. 让未知/R3 Capability 和 Schema 外参数在审计侧 fail-closed；
4. 在 Provider Adapter 层拒绝 ToolCall + LENGTH/STOP 等不一致组合；
5. 让 NONE、空 Final Answer 和非法 ModelTurn 进入 PROTOCOL_ERROR；
6. 清理原始业务日志；
7. 让完整预算与响应体上限生效；
8. 拆分模型错误类型；
9. 建立 Internal/Public/Audit Outcome 类型边界；
10. 清理 Core Android Log 并同步 README；
11. 强制执行全量测试后申请第六轮复审。

### 15.7 第五轮复审状态

| 项目 | 状态 |
| --- | --- |
| Hermes 式 AgentEngine 核心循环 | 已具备 |
| OpenAI/Anthropic 原生多轮 Tool Calling | 已具备 |
| Tool Call ID 校验 | 已完成 |
| 显式 Zone 前置一致性 Policy | 主路径已完成 |
| 显式 Zone 关键词/歧义解析 | 未完成 |
| Tool Arguments Schema 脱敏 | 主路径已完成 |
| Audit Assistant ToolCall 脱敏 | 已完成 |
| ToolResult/Observation Schema 脱敏 | 未完成 |
| 未知 Tool/额外参数 Audit fail-closed | 未完成 |
| 无 ToolCall 的 LENGTH 处理 | 已完成 |
| ToolCall + LENGTH/协议不一致处理 | 未完成 |
| FinishReason.NONE/空答复处理 | 未完成 |
| Internal/Audit Result 分离 | 已完成结构拆分 |
| 三层预算 | 部分完成 |
| 错误分类 | 未完成 |
| Core 纯 Java 边界 | 未满足 |
| 单元测试 | 86/86 通过 |
| README 与版本状态 | 未同步 |
| V0.4.0 总体状态 | **继续修复安全边界，不通过第五轮最终复审** |

### 15.8 第五轮最终评价

本轮代码已经把上一轮主路径问题逐项落实：显式用户目标不再轻易被模型替换，Arguments 和 Assistant ToolCall 进入 Schema-aware Audit 边界，Tool Call ID 不再静默补值，常规 LENGTH 也不再虚报成功。

当前剩余问题来自安全组件只覆盖最常见形态：关键词存在子串重叠，Schema 只保护输入参数而未覆盖执行结果，未知字段默认保留，FinishReason 只在无 ToolCall 分支校验。这些边界可能产生错误区域控制、截断 ToolCall 被执行和导航地址泄漏，仍应在 V0.4.0 最终验收前关闭。

---

## 附录 A：第三轮 P1 修复记录（2026-08-02）

### A.1 修复结论

本轮针对 12.3 / 12.4 中标记为未完成且可立即闭环的 P1 与 P2 项进行修复，并通过新增回归测试覆盖修复路径。**OpenAI-Compatible 原生多轮 Tool Calling（P1-1）** 不在本轮范围内，仍作为 V0.4.0 完成前的最后一项 P1 阻塞项保留。

测试结果：`./gradlew testDebugUnitTest` 共 **60 个测试全部通过**（AgentEngineTest 33、ModelSanitizerTest 10、AuditRedactorTest 7、ModelApiClientContractTest 10），0 failures / 0 errors。

### A.2 本轮已修复项

#### 已修复 P1-2：ExplicitIntentConstraints 取代模型声明来源

新增 `core/identity/ExplicitIntentConstraints.java`：

```java
public final class ExplicitIntentConstraints {
    public static ExplicitIntentConstraints extractFrom(String text) {
        if (text == null || text.isEmpty()) return empty();
        if (text.contains("主驾") || text.contains("驾驶位")) return ofZone(VehicleZone.DRIVER);
        if (text.contains("副驾") || text.contains("副驾驶")) return ofZone(VehicleZone.PASSENGER);
        return empty();
    }
    // hasExplicitZone / getExplicitZone / empty / ofZone
}
```

接入：

- `AgentRequest` 在 Builder 默认构造与便捷构造函数中自动调用 `ExplicitIntentConstraints.extractFrom(text)`，运行时受信任边界内持有用户明确 zone；
- `PolicyEngine` 的副驾越权检查改为双源校验：

```java
ExplicitIntentConstraints constraints = request.getExplicitIntent();
boolean userExplicitZone = constraints != null
        && constraints.hasExplicitZone()
        && constraints.getExplicitZone() == VehicleZone.DRIVER;
boolean userExplicitProvenance =
        call.provenanceOf("zone") == ToolCall.ArgumentProvenance.USER_EXPLICIT;
if (userExplicitZone || userExplicitProvenance) {
    return PolicyDecision.denyCapability("副驾不能修改主驾区域：用户明确要求越权");
}
return PolicyDecision.denyParameter("副驾不能修改主驾区域，请改用 zone=passenger");
```

修复后真实 LLM 路径下的漏洞被堵上：

```text
副驾说"把主驾温度调到 24 度"
→ AgentRequest 提取 explicitZone=DRIVER（runtime trusted）
→ 模型返回 zone=driver（adapter 未标 provenance,默认 MODEL_INFERRED）
→ PolicyEngine 命中 userExplicitZone 分支
→ CAPABILITY 拒绝（不可申诉，不允许模型改 zone=passenger 重试）
```

而原本测试就能覆盖的 `MODEL_INFERRED` 单源路径仍走 PARAMETER 拒绝（允许模型换值重试），与 11.4 / 12.3 中描述的语义一致。

新增回归测试：

- `passengerExplicitZoneFromAgentRequestBlocksModelInferredCall`：构造 `MODEL_INFERRED` 的 zone=driver ToolCall，配合 `explicitZone=DRIVER` 的 AgentRequest，断言被 `CAPABILITY` 拒绝。

#### 已修复 P1-3：完整字段级脱敏 + Tool Arguments 进入审计

`AuditRedactor.redactMap` / `ModelSanitizer.sanitizeMap` 改为**递归实现**，对嵌套 Map / List 逐层处理；String value 走 secret mask + memory 占位（仅 Audit 侧）。

```java
private Object redactValue(Object value) {
    if (value == null) return null;
    if (value instanceof Map) return redactMapRecursive((Map<String, Object>) value);
    if (value instanceof List) {
        List<Object> list = new ArrayList<>(((List<?>) value).size());
        for (Object item : (List<?>) value) list.add(redactValue(item));
        return list;
    }
    return value instanceof String ? redact((String) value) : value;
}
```

`AgentEngine.snapshots()` 改为**实例方法**，构造 `ToolCallSnapshot` 时通过 `auditRedactor.redactArguments(call.getArguments())` 复制参数，避免 Trajectory 持有原始用户文本/导航地址/联系人/账号等字段：

```java
private ToolCallSnapshot snapshot(ToolCall call, PolicyDecision decision, ToolObservation observation) {
    return new ToolCallSnapshot(
            call.getCapabilityName(),
            auditRedactor.redactArguments(call.getArguments()),
            call.getId(),
            decision,
            observation);
}
```

新增 `AuditRedactor.redactArguments(args)` 公开方法作为语义化入口（与 `redactMap` 同实现，强调 Tool Arguments 是字段级脱敏对象）。

修复后凭据藏在 `observedState.token = sk-...` / `arguments.bearer = Bearer ...` 等位置时，Audit Trajectory、Conversation(模型侧仅凭据 mask)和 UI 都不会泄漏。

新增回归测试：

- `trajectorySnapshotRedactsToolArguments`：构造 `arguments = {api_key: "sk-abcd1234abcd1234"}`，断言 snapshot.getArguments().get("api_key") 返回 `"***"`。

#### 已修复 P2-1：AgentOutcome 三层视图

`AgentOutcome` 新增 `internalResults` 字段，**保留 Runtime 内部可信域使用的真实 ToolResult**：

```java
public final class AgentOutcome {
    private final List<ToolResult> internalResults;  // 真实执行结果
    private final Trajectory trajectory;             // Audit 字段级脱敏
    @Deprecated public List<ToolResult> getResults() { /* 从 trajectory 展开，已是 Audit 视图 */ }
    public List<ToolResult> getInternalResults() { /* 真实结果，仅供 Runtime 可信域使用 */ }
}
```

`AgentEngine.executeLocked` 在 Policy/Execute 循环中累积 `internalResults`，最终传入 `AgentOutcome`。修复后：

```text
Tool 实际结果 preferred_temperature=24
  → AgentOutcome.getInternalResults() 返回 24（Runtime / 业务调用方使用）
  → AgentOutcome.getResults() 返回 <memory>（兼容 API,文档明确为 Audit 视图）
  → Trajectory / UI 返回 <memory>
```

旧 `getResults()` 标记 `@Deprecated`，文档明确说明它现在返回的是 Audit 视图而非真实业务结果，避免调用方误用。

新增回归测试：

- `internalResultsHoldRealMemoryValueWhileAuditResultsRedacted`：执行 memory.preference.save + memory.preference.get，断言 `internalResults` 持有真实值 `"24"`，`getResults()` 与 Trajectory 均返回 `<memory>`。

#### 已修复 P2-2（部分）：截断总长度约束

`ModelSanitizer.truncateWithSuffix` 重写为**迭代算法**，确保 `prefix + "[truncated N chars]"` 的总长度严格 ≤ `maxChars`：

```java
static String truncateWithSuffix(String input, int maxChars) {
    int originalLen = input.length();
    int keep = Math.min(originalLen, maxChars);
    String suffix;
    while (true) {
        suffix = TRUNCATED_PREFIX + (originalLen - keep) + TRUNCATED_SUFFIX;
        if (keep + suffix.length() <= maxChars || keep <= 1) break;
        keep--;
    }
    return input.substring(0, keep) + suffix;
}
```

`AuditRedactor.redact` 也复用此方法，避免 V0.4.0 中 `maxMessageChars` 形同虚设。修复后：

```text
maxChars=30, input=40 chars
旧实现: substring(0,30) + "[truncated 10 chars]" → 总长 49 chars ❌
新实现: keep=12, "[truncated 28 chars]" → 总长 30 chars ✓
```

新增 / 改造测试（ModelSanitizerTest + AuditRedactorTest）：

- `truncatesLongContentWithinMaxChars`：断言 `result.length() <= maxChars`。
- `truncateHandlesSmallMaxCharsGracefully`：极端小 `maxChars=15` 时仍能保留 1 char + 后缀。

### A.3 本轮仍未修复的 P1 / P2

| 项目 | 状态 |
| --- | --- |
| **P1-1 OpenAI-Compatible 原生多轮 Tool Calling** | **未完成（V0.4.0 最后一项 P1 阻塞）** |
| P2-2 三层预算完整生效（除截断外） | 未完成 |
| P2-3 FinishReason 进入循环控制 | 未完成 |
| P2-4 模型错误细分（Network/Auth/RateLimit/Protocol） | 未完成 |
| P2-5 Core 仍直接依赖 `android.util.Log` | 未完成 |
| P2-6 README 同步 V0.4.0 实际状态 | 未完成 |

P1-1 是当前**唯一阻止 V0.4.0 通过复审**的硬性问题。其余 P2 项可在 V0.4.x 补丁版本中分批闭环，不阻塞 V0.4.0 主版本号定稿。

### A.4 本轮测试增量

| 测试文件 | 用例数变化 |
| --- | --- |
| AgentEngineTest | 30 → 33（+3）|
| ModelSanitizerTest | 9 → 10（+1）|
| AuditRedactorTest | 6 → 7（+1）|
| ModelApiClientContractTest | 10（不变）|
| **合计** | **56 → 60（+4）** |

新增 / 改造用例：

- `passengerExplicitZoneFromAgentRequestBlocksModelInferredCall`（P1-2 ExplicitIntentConstraints）
- `internalResultsHoldRealMemoryValueWhileAuditResultsRedacted`（P2-1 三层视图）
- `trajectorySnapshotRedactsToolArguments`（P1-3 Tool Arguments 脱敏）
- `truncatesLongContentWithinMaxChars`（ModelSanitizer + AuditRedactor 两侧均改造，P2-2 截断）

### A.5 第四轮修复顺序建议

按"硬阻塞 → 数据安全闭环 → 协议完整 → 工程化收尾"的优先级：

1. **P1-1 OpenAI-Compatible 原生多轮 Tool Calling**：增加 `callOpenAiWithTools`、保留服务端 `tool_calls[].id`、支持同一 assistant message 多 tool_call、生成基于 Observation 的 Final Answer，并加 ≥2 轮 Tool/Observation 协议契约测试；
2. **P2-2 三层预算完整生效**：让 `totalDeadlineMillis` 进入 Loop 截止时间、`maxConversationMessages` 独立计数、User/System/Assistant/Tool Arguments 统一字符上限、HTTP Response Body 字节上限；
3. **P2-3 FinishReason 进入循环控制**：解析 Anthropic `stop_reason`、处理 LENGTH / 空响应 / FinishReason 与 ToolCall 不一致；
4. **P2-4 模型错误细分**：拆 Network / Authentication / RateLimit / Protocol / Empty / Policy Halt；
5. **P2-5 Core 纯 Java 边界**：抽 `Logger` 接口，Core 不再直接 import `android.util.Log`；
6. **P2-6 README 与版本状态同步**：版本、链路描述、测试数量、Core 边界全部刷新为 V0.4.0。

P1-1 完成后即可申请 V0.4.0 第四轮复审，P2 全部可在复审通过后作为 V0.4.1 / V0.4.2 补丁版本演进。

### A.6 本轮修复状态

| 项目 | 状态 |
| --- | --- |
| Hermes 式 AgentEngine 核心循环 | 已具备 |
| Anthropic 原生多轮 Tool Calling | 已具备 |
| OpenAI-Compatible 多轮 Tool Calling | **未完成（最后一项 P1 阻塞）** |
| Model/Audit Observation 双视图 | **已修复（递归脱敏 + Arguments 入审计）** |
| Policy 每轮执行 | 已具备 |
| 异常终止状态 | 已修复 |
| ArgumentProvenance 数据结构 | 已具备 |
| 真实 Provider 下的可信用户意图约束 | **已修复（ExplicitIntentConstraints）** |
| Assistant 审计脱敏 | 部分完成（Arguments 已脱敏，assistant message 文本仍需覆盖） |
| Tool Arguments/UI/日志默认脱敏 | **Arguments 已脱敏；UI/Logcat 仍需统一审计入口** |
| Internal Result 与 Audit Result 分离 | **已修复（三层 AgentOutcome）** |
| 三层预算 | 部分完成（截断已修复，其余未完成） |
| FinishReason | 未接入 |
| 错误分类 | 未完成 |
| Core 纯 Java 边界 | 未满足 |
| 单元测试 | **60/60 通过** |
| README 与版本状态 | 部分同步 |
| V0.4.0 总体状态 | **P1-1 完成后可申请第四轮复审** |

---

## 附录 B：第四轮 P1-1 修复记录（2026-08-02）

### B.1 修复结论

本轮专门闭环 V0.4.0 最后一项 P1 阻塞:**OpenAI-Compatible 原生多轮 Tool Calling**。本地 OpenAI-Compatible 模型(项目实际接入的 MTPLX/Qwen)从本轮起进入真正的 Agent Loop,可以读取 Observation 后继续推理、根据参数拒绝修正参数、根据执行失败选择替代方案、根据车辆查询结果决定下一步、生成基于真实 Observation 的最终答复。

测试结果:`./gradlew testDebugUnitTest` 共 **66 个测试全部通过**(ModelApiClientContractTest 16、AgentEngineTest 33、ModelSanitizerTest 10、AuditRedactorTest 7),0 failures / 0 errors。`assembleDebug` + `install` + 启动均无崩溃。

### B.2 修复实现

#### ModelApiClient 新增 OpenAI 多轮入口

新增三组方法(对齐 Anthropic 路径的 API 形状):

```java
public ModelTurn callOpenAiWithTools(ModelConfig config, String system,
        List<AgentMessage> conversation, List<ToolDefinition> tools) throws Exception

static JSONObject buildOpenAiToolRequest(ModelConfig config, String system,
        List<AgentMessage> conversation, List<ToolDefinition> tools) throws Exception

static ModelTurn parseOpenAiToolResponse(JSONObject response,
        List<ToolDefinition> tools) throws Exception
```

旧的 `buildOpenAiToolRequest(config, system, user, tools)` 与 `parseOpenAiToolResponse(config, response, tools)` 保留,供 `planWithTools` 走 legacy 单轮路径时使用,不影响兼容。

#### Conversation 序列化(`toOpenAiMessages`)

OpenAI 协议与 Anthropic 的关键差异在本方法体现:

| Role | OpenAI 形态 | Anthropic 形态 |
| --- | --- | --- |
| SYSTEM | conversation 内跳过,外层单独一条 `{role:"system", content}` | top-level `system` 字段 |
| USER | `{role:"user", content}` | `{role:"user", content}` |
| ASSISTANT(带 tool_calls) | `{role:"assistant", content:""|null, tool_calls:[{id, type:"function", function:{name, arguments:**JSON string**}}]}` | `{role:"assistant", content:[{type:"text"}, {type:"tool_use", id, name, input:**JSON object**}]}` |
| TOOL | `{role:"tool", content, tool_call_id, name?}` **每个 tool_call 一条独立 message** | `{role:"user", content:[{type:"tool_result", tool_use_id, content}]}` **多个合并到同一 user** |

实现要点:

- `assistant.tool_calls[].function.arguments` 必须是 JSON **字符串**(OpenAI 协议硬性要求),通过 `toJson(call.getArguments()).toString()` 序列化;部分本地服务器返回对象形式,parse 路径做兼容(见 `openAiNativeResponseAcceptsObjectArguments` 测试)。
- `tool.tool_call_id` 必须与 `assistant.tool_calls[].id` 一一对应——`ToolCall.getStepId()` 透传模型生成的 id,**不允许 Runtime 自造**(与 Anthropic 路径的 `tool_use.id` 同样规则)。
- assistant 含 tool_calls 时 content 设为空字符串(本地服务器对 null content 容忍度不一,空字符串是最大兼容形态)。
- 多个 tool 结果**保持独立 message**(不像 Anthropic 合并到同一 user block)。

#### Response 解析(`parseOpenAiToolResponse`)

```java
JSONObject message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
JSONArray toolCalls = message.optJSONArray("tool_calls");
String content = message.optString("content", "");
if (toolCalls == null || toolCalls.length() == 0) {
    return ModelTurn.directAnswer(content);  // finishReason=STOP,Loop 终止
}
// 有 tool_calls 时,逐个映射 capabilityName,保留 id
for (...) {
    String id = toolCall.optString("id", "");
    String modelName = function.getString("name");
    ToolDefinition definition = definitionsByModelName.get(modelName);
    if (definition == null) throw new IllegalStateException("OpenAI 返回未注册 Tool:" + modelName);
    Object rawArguments = function.opt("arguments");
    Map<String, Object> arguments = /* accept JSONObject | String | empty */;
    calls.add(ToolCall.withId(id, definition.getCapabilityName(), arguments));
}
return ModelTurn.ofToolCalls(calls, content);
```

`arguments` 兼容三种返回形态:
1. JSONObject(部分本地服务器)
2. JSON 字符串(OpenAI 规范)
3. 空/null(走空 map)

#### LlmModelGateway 路由切换

新增 `useOpenAiNative` 标志,decide 三分支路由:

```java
this.useAnthropicNative = plannerMode == NATIVE_TOOL_CALLING && protocol == ANTHROPIC_MESSAGES;
this.useOpenAiNative = plannerMode == NATIVE_TOOL_CALLING && protocol == OPENAI_CHAT;

if (useAnthropicNative) return client.callAnthropicWithTools(...);
if (useOpenAiNative) return client.callOpenAiWithTools(...);
return legacyDecide(request);  // STRUCTURED_JSON_COMPATIBILITY 或其他
```

修复后真实 LLM 路径的漏洞被堵上:

```text
本地 OpenAI-Compatible 模型,用户配置 NATIVE_TOOL_CALLING + OPENAI_CHAT
→ 旧路径:legacy compat plan,看到 Observation 后硬编码"任务结束"
→ 新路径:每轮把完整 conversation 发出,模型基于 Observation 决定下一轮动作
→ Agent Loop 真正成立:LLM → Tool Call → Policy → Execute → Observation → LLM
```

### B.3 契约测试（6 个，覆盖协议正确性）

| 测试 | 覆盖点 |
| --- | --- |
| `openAiNativeRequestSerializesMultiTurnConversation` | system + user + assistant(tool_calls) + tool×2 完整多轮序列化;arguments 必须是 JSON string;tool_call_id 与 assistant.tool_calls[].id 对应 |
| `openAiNativeResponsePreservesToolCallIdAndMapsCapability` | 模型返回的 `tool_calls[].id` 原样保留,modelName 反查 capabilityName |
| `openAiNativeResponseSupportsMultipleToolCallsInOneMessage` | 同一 assistant message 含 2 个 tool_call 全部解析,各自保留独立 id |
| `openAiNativeResponseWithoutToolCallsReturnsDirectAnswer` | 无 tool_calls → ModelTurn.directAnswer,finishReason=STOP |
| `openAiNativeResponseAcceptsObjectArguments` | 本地服务器返回 arguments 为 JSONObject 时的兼容性 |
| `openAiNativeResponseRejectsUnregisteredTool` | 模型返回未注册 tool name → IllegalStateException(对齐 Anthropic 路径) |

新增测试方法集中在 `ModelApiClientContractTest`,与原有 10 个 Anthropic/单轮测试同处一文件,共 **16 个契约测试**覆盖两条原生路径。

### B.4 本轮修复状态

| 项目 | 状态 |
| --- | --- |
| Hermes 式 AgentEngine 核心循环 | 已具备 |
| **OpenAI-Compatible 多轮 Tool Calling** | **已具备(本轮闭环)** |
| Anthropic 原生多轮 Tool Calling | 已具备 |
| Model/Audit Observation 双视图 | 已修复(递归脱敏 + Arguments 入审计) |
| Policy 每轮执行 | 已具备 |
| 异常终止状态 | 已修复 |
| ArgumentProvenance 数据结构 | 已具备 |
| 真实 Provider 下的可信用户意图约束 | 已修复(ExplicitIntentConstraints) |
| Assistant 审计脱敏 | 部分完成(Arguments 已脱敏,assistant message 文本仍需覆盖) |
| Tool Arguments/UI/日志默认脱敏 | Arguments 已脱敏;UI/Logcat 仍需统一审计入口 |
| Internal Result 与 Audit Result 分离 | 已修复(三层 AgentOutcome) |
| 三层预算 | 部分完成(截断已修复,其余未完成) |
| FinishReason | 未接入 |
| 错误分类 | 未完成 |
| Core 纯 Java 边界 | 未满足 |
| 单元测试 | **66/66 通过** |
| README 与版本状态 | 部分同步 |
| **V0.4.0 总体状态** | **所有 P1 闭环,可申请第四轮复审** |

### B.5 V0.4.0 收尾建议

P1 已全部闭环。剩余 P2 项不阻塞 V0.4.0 主版本号定稿,建议按以下顺序在 V0.4.x 补丁版本分批演进:

1. **V0.4.1**:P2-3 FinishReason 进入循环控制 + P2-4 模型错误细分(决定 Loop 在 LENGTH / RateLimit / Network 下的不同恢复策略);
2. **V0.4.2**:P2-2 三层预算完整生效 + P2-5 Core 纯 Java 边界(抽 Logger 接口);
3. **V0.4.3**:P2-6 README 与版本状态同步,补完整文档;
4. **V0.5.0**:Token 预算切 tokenizer、四层 Memory、Trajectory 持久化(Room/WAL)+ 加密、Gemini 原生 Tool Calling、Prompt Builder 模块化。

第四轮复审时,可重点验证:

- 真实 MTPLX/Qwen 模型 + NATIVE_TOOL_CALLING + OPENAI_CHAT 跑两轮以上 Tool/Observation 任务(例:"空调 24 度并报电量" 这种需要串行 tool call 的复合任务);
- 模型基于 Observation 修正参数(故意发起副驾越权请求,看模型是否在 PARAMETER 拒绝后改 zone=passenger 重试);
- 模型基于 Observation 生成最终答复(看是否基于真实车辆状态而非凭空回答)。

---

## 十六、第六轮 P1/P2 修复记录（2026-08-02）

### 16.1 修复结论

本轮针对第十五章第五轮复审标记的 4 个 P1 与 1 个 P2 逐项闭环,并通过新增回归测试覆盖每条修复路径。所有安全边界(用户意图识别、协议一致性、Audit 脱敏、fail-closed 兜底)均通过强制重跑的单元测试验证。

强制执行:

```bash
./gradlew testDebugUnitTest --rerun-tasks
```

结果:

```text
BUILD SUCCESSFUL
107 tests, 0 failures, 0 errors, 0 skipped
```

测试分布:

| 测试类 | 第五轮 | 第六轮 | 增量 | 结果 |
| --- | ---: | ---: | ---: | --- |
| `AgentEngineTest` | 40 | 41 | +1 | 通过 |
| `ModelApiClientContractTest` | 24 | 26 | +2 | 通过 |
| `ModelSanitizerTest` | 10 | 10 | 0 | 通过 |
| `AuditRedactorTest` | 12 | 17 | +5 | 通过 |
| `ExplicitIntentConstraintsTest` | — | 5 | +5 | 通过(新文件) |
| `ModelTurnTest` | — | 8 | +8 | 通过(新文件) |
| **合计** | **86** | **107** | **+21** | **全绿** |

APK 构建 + 安装 + 启动:`adb install -r app-debug.apk && adb shell am start -n com.matrix.agent/.MainActivity` 通过,无 crash。

### 16.2 已修复项

#### 已修复 P1-1:副驾驶位歧义(子串重叠 bug)

**根因**:`ExplicitIntentConstraints.extractFrom` 用 `text.contains("驾驶位")` 识别主驾,但"副驾驶位"是 4 个字,后 3 字就是"驾驶位"——副驾说"把副驾驶位温度调到 24 度"会被误判为 explicitZone=DRIVER,PolicyEngine 据此把模型脑补的 zone=passenger 当成 PARAMETER 错误允许重试,实际执行了用户没要求的副驾调温。

**修复**(`core/identity/ExplicitIntentConstraints.java`):

```java
// 主驾:主驾 或 不在 "副" 之后的 "驾驶位"
private static final Pattern DRIVER_KEYWORD = Pattern.compile("主驾|(?<!副)驾驶位");
private static final Pattern PASSENGER_KEYWORD = Pattern.compile("副驾");

public static ExplicitIntentConstraints extractFrom(String text) {
    if (text == null || text.isEmpty()) return empty();
    boolean hasPassenger = PASSENGER_KEYWORD.matcher(text).find();
    boolean hasDriver = DRIVER_KEYWORD.matcher(text).find();
    if (hasPassenger && hasDriver) {
        // 歧义:同一句同时提到主驾和副驾 → 不允许 Runtime 凭一句话同时操作两个 zone
        return empty();
    }
    if (hasPassenger) return ofZone(VehicleZone.PASSENGER);
    if (hasDriver) return ofZone(VehicleZone.DRIVER);
    return empty();
}
```

**新增测试**(`ExplicitIntentConstraintsTest`,5 个):
- `passengerFullKeywordDoesNotOverlapWithDriver`(关键回归:"副驾驶位"识别为 PASSENGER)
- `passengerShortKeywordReturnsPassenger`
- `driverKeywordsReturnDriver`("驾驶位"单独出现仍识别为 DRIVER)
- `bothZonesMentionedIsAmbiguousAndReturnsEmpty`("主驾和副驾"歧义 → empty)
- `emptyTextReturnsEmpty`

#### 已修复 P1-2:导航 ToolResult/Observation 真实目的地泄漏

**根因**:`MockCapabilityProvider` 的 navigation 路径返回 `message = "已开始导航到\"北京市某小区\""` + `observedState={navigation.destination: 北京市某小区}`,而 `AuditRedactor.redact(observation)` 只对 `memory.preference.*` 全脱敏,非 memory 类 ToolResult 仅走凭据正则——真实地址原样进 Trajectory / UI / 日志。

**修复**:

1. `CapabilityDefinition` 新增 `auditMessageTemplate` 和 `sensitiveObservedFields` 两个 builder 字段;
2. `CapabilityRegistry.createDemoRegistry` 给 `navigation.start_route` 配:
   ```java
   .auditMessageTemplate("已开始导航到 <destination>")
   .sensitiveObservedField("navigation.destination", "<destination>")
   ```
3. `AuditRedactor.redact(observation)` 在非 FULL_REDACT 路径前查 schema,有 schema 时调用 `redactWithSchema`:
   - SUCCESS 状态用 `auditMessageTemplate` 替换 message(失败状态保留 Provider 诊断文本);
   - `observedState` 中 `sensitiveObservedFields` 列出的字段替换为占位符,其余字段仍走默认 secret mask。

**新增测试**(`AuditRedactorTest`,2 个):
- `schemaRedactsNavigationObservationMessage`(message + observedState 都替换)
- `capabilityWithoutAuditSchemaKeepsDefaultRedaction`(vehicle.climate 无 schema,默认 redact)

#### 已修复 P1-3:未知 Capability 与 Schema 外参数 fail-open

**根因**:`AuditRedactor.redactArguments(cap, args)` 在 `findToolDefinition(cap)` 返回 null 时 fallback 到 `redactArguments(args)`(只跑凭据正则)——未注册 / 被 R3 阻挡的 capability 的 args 中 navigation.destination 等业务字段原值进 audit。schema 外的额外字段(模型多塞的)违反 strict schema 同样不被脱敏。

**修复**(`core/agent/AuditRedactor.java`):

```java
if (tool == null) {
    // 未注册 / R3 capability → fail-closed,所有 value 一律 mask
    Map<String, Object> redacted = new LinkedHashMap<>();
    for (String key : args.keySet()) redacted.put(key, "***");
    return redacted;
}
// ...
} else if (!schemaParameterNames.contains(key)) {
    // schema 外的额外字段 fail-closed
    redacted.put(key, "***");
}
```

**新增测试**(`AuditRedactorTest`,3 个):
- `unregisteredCapabilityMasksAllArgsFailClosed`(模型脑补 capability 全脱敏)
- `r3ProhibitedCapabilityMasksAllArgsFailClosed`(vehicle.brake.apply 全脱敏)
- `extraSchemaFieldsAreMaskedFailClosed`(schema 外字段脱敏,schema 内字段保留)

#### 已修复 P1-4:LENGTH + ToolCall 仍可能被执行

**根因**:`ModelApiClient.parseOpenAiToolResponse` / `parseAnthropicToolResponse` 只在 `if (toolCalls == null)` 分支映射 finishReason/stopReason——LENGTH 信号在有 tool_calls 时丢失,被强制为 TOOL_CALLS 执行半截 destination / 半截 value,极其危险。

**修复**:在解析器最前面增加 LENGTH 短路:

```java
// parseOpenAiToolResponse
if (mapOpenAiFinishReason(rawFinishReason) == FinishReason.LENGTH) {
    Log.w(TAG, "[Http] openai-native LENGTH short-circuit ...");
    return ModelTurn.of(content, FinishReason.LENGTH);
}
// parseAnthropicToolResponse 同理
if (mapAnthropicStopReason(rawStopReason) == FinishReason.LENGTH) {
    return ModelTurn.of(text.toString(), FinishReason.LENGTH);
}
```

AgentEngine 的 `if (turn.getFinishReason() == FinishReason.LENGTH)` 检查自然生效,无论是否有 tool_calls 都走 LENGTH_EXCEEDED 终止。

**新增测试**(`ModelApiClientContractTest`,2 个):
- `openAiNativeLengthWithToolCallsShortCircuitsBeforeExecution`(半截 arguments 不被执行)
- `anthropicNativeMaxTokensWithToolUseShortCircuitsBeforeExecution`(半截 input 不被执行)

#### 已修复 P2-1:FinishReason.NONE 与空 Final Answer 仍返回成功

**根因**:`ModelTurn.of(content, finishReason)` 静默把 TOOL_CALLS 降级为 STOP;NONE 没有显式处理;STOP 不要求非空 content——Provider 协议错误被掩盖成正常完成,AgentEngine 把这种"啥也没说"的轮次当 NO_TOOL_CALL → SUCCEEDED。

**修复**(`core/agent/ModelTurn.java` + `core/agent/StopReason.java` + `AgentEngine.java` + `ModelApiClient.java`):

1. `ModelTurn.of` 严格化:
   - `TOOL_CALLS` → 抛 `IllegalArgumentException`(必须用 `ofToolCalls`);
   - `STOP + 空 content` → 抛 `IllegalArgumentException`(空答复不是 STOP);
   - `LENGTH` / `NONE` → 允许任意 content(包括空)。
2. `ModelApiClient` 解析器侧在 STOP + 空 content 时降级为 NONE(避免抛异常破坏兼容性);
3. `StopReason` 新增 `PROTOCOL_ERROR`;
4. `AgentEngine` 在 LENGTH 检查之后、NO_TOOL_CALL 之前增加 NONE 检查,终止为 PROTOCOL_ERROR。

**新增测试**:
- `ModelTurnTest`(新文件,8 个):覆盖 4 个抛异常路径 + LENGTH/NONE 允许空 + directAnswer 非空 + ofToolCalls 非空
- `AgentEngineTest.noneFinishReasonExitsLoopAsProtocolErrorNotSucceeded`:验证 NONE → PROTOCOL_ERROR → FAILED(不能 SUCCEEDED)

### 16.3 本轮修复状态

| 项 | 第五轮状态 | 第六轮状态 |
| --- | --- | --- |
| P1-1 副驾驶位歧义 | 待修复 | **已闭环**(lookbehind + 歧义返回 empty) |
| P1-2 ToolResult schema 投影 | 待修复 | **已闭环**(auditMessageTemplate + sensitiveObservedFields) |
| P1-3 fail-closed | 待修复 | **已闭环**(未注册 / R3 / 额外字段全脱敏) |
| P1-4 LENGTH + ToolCall | 待修复 | **已闭环**(LENGTH 短路,半截参数不执行) |
| P2-1 NONE / 空 content 成功 | 待修复 | **已闭环**(ModelTurn 严格化 + PROTOCOL_ERROR 终止) |

P0 / P1 安全边界 100% 闭环。剩余 P2(Logcat / Core Android Log / 完整预算 / 错误分类 / Internal Result 类型边界 / README 同步)按既定路线在 V0.4.x 补丁版本演进,不阻塞 V0.4.0 主版本号定稿。

### 16.4 第七轮复审建议

1. **V0.4.0 正式发版**(标记 `versionCode=7` / `versionName=0.4.0`),附 README 临时标注"V0.4.0 核心闭环,Logcat / 预算 / 错误分类 在 V0.4.1 收尾";
2. **V0.4.1**:P2-3 Logcat + P2-7 Core Android Log(日志边界一并处理);
3. **V0.4.2**:P2-4 完整预算 + P2-6 错误分类;
4. **V0.4.3**:P2-5 Internal Result 类型边界 + P2-8 README 同步;
5. **V0.5.0**:Token 预算切 tokenizer、四层 Memory、Trajectory 持久化、Gemini 原生 Tool Calling、Prompt Builder 模块化。

第七轮复审可重点验证:
- 真实 MTPLX/Qwen + NATIVE_TOOL_CALLING 跑两轮以上复合任务(空调 + 导航);
- 副驾说"副驾驶位调温"不再被误判为 explicitZone=DRIVER;
- 故意触发 LENGTH(把 max_tokens 设小并要求模型同时返回 tool_call),验证 finalState 不为 SUCCEEDED;
- Audit Trajectory 中 navigation.destination 与 memory preference value 不出现原值;
- 未注册 capability 进 AuditRedactor 时所有 args 显示为 `***`。

### 16.5 第六轮最终评价

V0.4.0 的安全边界与协议一致性已全部闭环:
- 用户意图识别无歧义(关键词 lookbehind + 多区域返回 empty);
- Audit 脱敏从"参数侧 schema-aware"扩展到"结果侧 schema 投影",覆盖 message 与 observedState 双通道;
- 未注册 / R3 / 额外字段全部 fail-closed,不再 leak 业务值;
- LENGTH 即使伴随 tool_calls 也短路,半截参数永不执行;
- NONE 与空 content 走 PROTOCOL_ERROR,不再虚报成功。

107 个单元测试全绿,APK 在 emulator 上启动正常。V0.4.0 主版本号定稿条件已满足。

---

## 十七、第七轮复审（2026-08-02）

### 17.1 复审结论

第十六章记录的 107 个测试全部通过属实,且第五轮发现的标准路径问题大多已经修复。但进一步检查发现,第十六章中“P0 / P1 安全边界 100% 闭环”“V0.4.0 主版本号定稿条件已满足”的结论过于乐观。

当前仍存在 3 个 P1 安全边界问题:

1. 多区域与否定表达被降级成“用户未指定区域”;
2. Schema 外 Tool 参数只在审计时遮盖,执行前没有拒绝;
3. 失败 ToolResult 与 Schema 外回读字段仍可能泄露业务隐私。

此外还存在 ToolCall 与 Provider FinishReason 不一致未拒绝等 P2 问题。因此本轮结论调整为:

> **V0.4.0 Agent Loop 主流程已经跑通,但暂不建议正式定稿或发布;应先关闭本章列出的 3 个 P1。**

本轮只做代码审查和测试,没有修改项目实现。

### 17.2 验证结果

强制执行:

```bash
./gradlew testDebugUnitTest --rerun-tasks
```

结果:

```text
BUILD SUCCESSFUL
107 tests, 0 failures, 0 errors, 0 skipped
```

测试通过说明已有回归用例全部满足,但不能证明尚未覆盖的否定表达、Schema 外执行参数、失败结果脱敏和协议组合不存在问题。本章问题均由实现路径直接推导,需要增加对应反例测试。

### 17.3 已确认修复的部分

以下第五轮问题已经在标准路径上完成修复:

- “副驾驶位”被主驾关键词子串误判的问题已通过 `(?<!副)驾驶位` 修复;
- 显式单区域目标已经进入 `PolicyEngine` 一致性与越权校验;
- OpenAI `finish_reason=length + tool_calls` 已在执行前短路;
- Anthropic `stop_reason=max_tokens + tool_use` 已在执行前短路;
- `FinishReason.NONE` 与 `STOP + 空 content` 不再被当作成功结束;
- 导航成功 Observation 的 message 和 `navigation.destination` 已按 Audit Schema 脱敏;
- 未注册、R3 Capability 以及 Schema 外参数在审计视图中已经 fail-closed 遮盖;
- `ModelTurn` 工厂方法的状态约束已经加强;
- 全量 107 个单元测试通过。

需要特别区分:上述“Schema 外参数 fail-closed”目前只发生在 **Audit 输出边界**,尚未成为 **Tool 执行边界**。

### 17.4 仍需修复的 P1

#### P1-1:多区域与否定表达被降级成无约束

位置:`core/identity/ExplicitIntentConstraints.java:66-77`、`core/policy/PolicyEngine.java:91-109`。

当前 `extractFrom` 同时匹配主驾和副驾时返回 `empty()`。这把三类不同语义混成了同一个状态:

- “主驾和副驾都调到 24 度”——明确的多目标任务;
- “不要调主驾,只调副驾”——带否定约束的单目标任务;
- “把温度调到 24 度”——真正没有指定区域。

`empty()` 在 Policy 中表示没有受信任的显式约束,随后会重新依赖模型生成的 `zone`。例如主驾说“不要调主驾,只调副驾”,模型错误返回 `zone=driver` 时,当前规则可能允许执行,造成与用户否定指令相反的物理操作。

第十六章把“主驾和副驾返回 empty”描述为安全修复并不准确。`Runtime` 暂时不支持一句话同时操作两个区域时,正确行为应是显式拒绝、要求拆分,或将其结构化为多个目标,不能降级成“未指定”。

建议让可信意图约束至少表达:

- `UNSPECIFIED`;
- `SINGLE_TARGET`;
- `MULTI_TARGET`;
- `CONFLICT/UNSUPPORTED`。

V0.4.0 的最小修复可以先对 `MULTI_TARGET/CONFLICT` fail-closed,返回明确的参数/意图拒绝;后续再由 NLU 输出结构化目标集合。

建议新增测试:

- “主驾和副驾都调到 24 度”不能退化成无约束执行;
- “不要调主驾,只调副驾”只能允许 passenger;
- “不要调副驾,只调主驾”只能允许 driver;
- 冲突或无法解析的区域表达必须拒绝或澄清。

#### P1-2:Schema 外参数没有在执行前拒绝

位置:`core/capability/CapabilityDefinition.java:77-79`、`core/policy/PolicyEngine.java:35-45`、`core/tool/ToolExecutor.java:31-46`。

模型侧 JSON Schema 设置了 `additionalProperties=false`,但模型输出属于不可信输入,不能依赖模型主动遵守 Schema。

当前执行链路为:

1. Capability 自定义 Validator 只检查已知业务参数;
2. 没有统一检查 ToolCall 是否含未声明字段;
3. Policy 允许后,原始 `ToolCall` 直接传给 `provider.execute(request, call)`;
4. 只有生成 Trajectory 快照时,`AuditRedactor` 才把 Schema 外字段替换成 `***`。

因此第十六章“Schema 外参数 fail-closed”的表述只对 Audit 视图成立,不代表执行安全。Mock Provider 目前可能忽略多余字段,但真实 AAOS/AIDL 通用 Provider 如果透传或解释这些字段,可能产生未定义行为。

建议在受信任执行边界增加统一严格校验:

- 拒绝所有未在 `ToolParameterDefinition` 中声明的字段;
- 统一验证 required、type、range、enum;
- 校验通过后生成规范化、不可变的参数副本;
- Provider 只能接收规范化后的 ToolCall,不能接收原始模型参数。

建议新增端到端测试:为合法 Capability 注入一个普通 Schema 外字段,断言 Policy 返回 `PARAMETER_REJECTED`,且 Provider 调用次数为 0。

#### P1-3:失败 ToolResult 与未知回读字段仍可能泄露隐私

位置:`core/agent/AuditRedactor.java:252-288`。

`redactWithSchema` 只在 `original.isSuccess()` 时使用 `auditMessageTemplate`;失败状态继续保留 Provider 原始诊断文本并只执行通用凭据正则。真实 Provider 很可能返回:

```text
导航到北京市某小区失败:网络不可用
```

当前通用正则只识别 API Key、Token、Bearer 等凭据,不会识别目的地、地址或联系人,因此失败文本仍可能把真实地址写入 Trajectory/UI/Log。

同时,`sensitiveObservedFields` 只遮盖精确注册的字段名。若 Provider 新增以下字段:

- `navigation.requested_destination`;
- `navigation.poi`;
- 嵌套的 `route.destination`;

这些 Schema 外回读字段会走默认递归处理并保留原值。

建议结果审计同样 fail-closed:

- 成功和失败分别配置安全 message 模板;
- Provider 详细错误改用结构化 `errorCode`,不要依赖含业务值的自由文本;
- 有 Audit Schema 的 Capability 只投影声明过的结果字段;
- Schema 外 observedState 字段默认删除或替换为 `***`;
- 对需要诊断的普通字段建立显式 allowlist。

建议新增测试:

- 导航失败 message 中含真实地址时,Audit 结果不得出现地址;
- 导航失败 observedState 中含目的地时仍需遮盖;
- 导航返回未知字段 `navigation.requested_destination` 时应删除或遮盖;
- 嵌套未知对象中含地址时应 fail-closed。

### 17.5 仍需处理的 P2

#### P2-1:ToolCall 与 Provider FinishReason 未做一致性校验

位置:`platform/ModelApiClient.java:217-285`、`platform/ModelApiClient.java:408-436`。

当前只对 `length/max_tokens` 做了优先短路。只要响应中存在 ToolCall,其余 FinishReason/StopReason 无论是正常、未知还是缺失,解析器都会返回 `ModelTurn.ofToolCalls()`。

仍可能接受并执行:

- OpenAI:`finish_reason=stop + tool_calls`;
- OpenAI:缺失或未知 `finish_reason + tool_calls`;
- Anthropic:`stop_reason=end_turn + tool_use`;
- Anthropic:缺失或未知 `stop_reason + tool_use`。

建议严格要求 OpenAI ToolCall 对应 `finish_reason=tool_calls`,Anthropic ToolUse 对应 `stop_reason=tool_use`;不一致时转为 `PROTOCOL_ERROR`,不得执行工具。若某些本地 OpenAI-Compatible 服务确实不规范,应通过显式 Provider 兼容策略处理,不能让核心解析器无条件放宽。

#### P2-2:预算配置仍未完全生效

位置:`core/agent/AgentBudget.java:11-40`、`core/agent/AgentEngine.java:136-168`。

- `AgentBudget.totalDeadlineMillis` 只有字段和 getter,没有真正参与 Request Deadline 计算;
- `maxMessageChars` 主要用于 Observation 清洗/审计截断,未统一限制 System/User/Assistant 消息;
- README 规划中的“消息条数预算”尚未实现;
- 总字符预算只在调用模型前检查当前 conversation,缺少对即将加入消息的统一入口约束。

这些问题暂列 P2,但在连接真实长上下文模型前需要完成。

#### P2-3:README 与当前实现状态不一致

README 仍把当前版本写成 `V0.3.2`,并写着 24 个测试;实际项目已经实现 V0.4.0 Agent Loop,本轮测试为 107 个。路线图中部分“V0.4.0 待实现”内容也已经落地。

建议在 P1 修复完成后统一更新 README,避免文档提前宣称发布,也避免继续把已经实现的 Agent Loop 描述成未来计划。

#### P2-4:日志治理尚未完成

`app/src/main/java` 当前仍有 21 个类直接引入 `android.util.Log`。部分日志包含用户命令、导航目的地、Provider message 或 HTTP 错误响应体。AuditRedactor 已经存在,但日志还没有统一经过安全日志入口。

建议在 V0.4.x 引入 Logger 抽象与字段化安全日志策略,默认禁止记录:

- 完整用户输入;
- Tool 原始参数;
- ToolResult 原始 message/observedState;
- HTTP 错误响应体;
- API Key、Token、地址、联系人与用户记忆。

### 17.6 与第十六章结论的差异

| 第十六章结论 | 第七轮复审结论 |
| --- | --- |
| 多区域返回 `empty()` 已闭环 | **不同意**。`empty()` 抹掉明确目标/否定约束,会回退为信任模型参数 |
| Schema 外参数已经 fail-closed | **部分同意**。Audit 输出已 fail-closed,Tool 执行前仍 fail-open |
| ToolResult Schema 投影已覆盖双通道 | **部分同意**。成功标准字段已覆盖,失败 message 与未知回读字段未覆盖 |
| FinishReason 协议一致性已全部闭环 | **部分同意**。LENGTH/NONE 已处理,ToolCall 与结束原因组合仍未校验 |
| P0/P1 100% 闭环 | **不同意**。仍有本章 3 个 P1 |
| 可以正式发布 V0.4.0 | **暂不同意**。建议先修完 3 个 P1 并补反例测试 |

### 17.7 建议修复顺序

1. **先修 P1-1**:为显式意图增加多目标/冲突状态,禁止降级为无约束;
2. **再修 P1-2**:建立统一 Tool Schema 校验和规范化执行边界;
3. **再修 P1-3**:失败结果与未知回读字段采用 Audit Schema fail-closed;
4. **补 P2-1**:严格校验 ToolCall 与 Provider FinishReason 组合;
5. 强制重跑全部测试,并新增上述反例用例;
6. 完成预算、日志与 README 同步后,再给出 V0.4.0 是否定稿的最终结论。

### 17.8 第七轮最终评价

MatrixAgent 当前已经具备真正 Agent Runtime 的核心形态:

- 多轮 `LLM → Tool → Observation → LLM` 循环;
- ToolCall ID 和 Provider 原生工具协议;
- Capability Registry 与 Policy Engine;
- 参数级/能力级拒绝与模型纠错;
- Trajectory、模型视图与审计视图分层;
- Deadline、Cancel、Tool Call 数和字符预算的基础框架。

工程主体已经比较稳定,107 个测试全绿也说明已有行为具备良好回归保护。但车控 Agent 的完成标准不能只看标准路径和测试数量,还需要保证不可信模型输入、歧义/否定用户意图、失败 Provider 输出三个边界均 fail-closed。

因此本轮综合判断为:

> **V0.4.0 完成度约 90%—95%。Agent Loop 主体完成,但还有 3 个 P1 安全边界需要关闭;暂不建议正式发版。**

## 十八、第八轮 P1/P2 修复记录(2026-08-02)

针对第七轮提出的 3 个 P1 + P2-1,本轮按建议顺序逐项闭环,并补齐反例回归。所有修复强制重跑全量测试,**129 个测试全绿(0 失败 / 0 错误)**,从第六轮的 107 个增加到 129 个(新增 22 个反例回归)。

### 18.1 P1-1:ExplicitIntent 状态枚举(已闭环)

**问题**:旧实现只有"有 zone / 无 zone"两态,把 `MULTI_TARGET`("主驾和副驾都调")和 `CONFLICT`("不要调主驾")一律降级为 `empty()`,等价于"无约束",模型脑补 zone 后放行。

**修复位置**:`core/identity/ExplicitIntentConstraints.java`。

**修复要点**:

1. 引入 4 值 `IntentType` 枚举:`UNSPECIFIED` / `SINGLE_TARGET` / `MULTI_TARGET` / `CONFLICT`,语义不可混淆。
2. `isBlocked()` 在 `MULTI_TARGET` / `CONFLICT` 时返回 true——PolicyEngine 必须 fail-closed。
3. `extractFrom` 增加否定修饰词识别(`不要` / `别` / `勿` / `禁`)和肯定修饰词识别(`只` / `只要`):
   - 两个 zone 都被肯定 → `MULTI_TARGET`
   - 仅一个被肯定 → `SINGLE_TARGET=该 zone`
   - 否定一个 + 肯定另一个("不要 X 只 Y") → `SINGLE_TARGET=Y`(肯定覆盖否定)
   - 所有提及的 zone 都被否定 → `CONFLICT`
4. `isZoneNegated` 改用 8 字符 lookback(原 4 字符漏判"不要调主驾和副驾"中"副驾"前的"不要",距离 6 字符);同时比较最后一个否定 token 与最后一个肯定 token 的位置,实现"肯定覆盖否定"。

**PolicyEngine 集成**(`core/policy/PolicyEngine.java:37-40`):

```java
if (definition.isWriteOperation()) {
    PolicyDecision blocked = checkExplicitIntentBlocked(request, cap);
    if (blocked != null) return blocked;
}
```

`MULTI_TARGET` / `CONFLICT` 在 strict schema 之前直接 `denyCapability`(模型无法通过换 zone/参数绕过),覆盖所有写操作——包括不带 zone 参数的 `navigation.start_route`。

**回归用例**(9 个,`ExplicitIntentConstraintsTest`):

| 用例 | 验证点 |
| --- | --- |
| `emptyTextReturnsUnspecified` | 无 zone 关键词 → UNSPECIFIED |
| `driverKeywordsReturnSingleTargetDriver` | "主驾"/"驾驶位" → SINGLE_TARGET=DRIVER |
| `passengerShortKeywordReturnsSingleTargetPassenger` | "副驾" → SINGLE_TARGET=PASSENGER |
| `passengerFullKeywordDoesNotOverlapWithDriver` | "副驾驶位" 不再误判 DRIVER(lookbehind) |
| `bothZonesAffirmedIsMultiTargetBlocked` | "主驾和副驾都调" → MULTI_TARGET,`isBlocked=true` |
| `negateDriverAffirmPassengerYieldsSinglePassenger` | "不要调主驾,只调副驾" → SINGLE_TARGET=PASSENGER |
| `negatePassengerAffirmDriverYieldsSingleDriver` | "别调副驾,只调主驾" → SINGLE_TARGET=DRIVER |
| `negatedOnlyZoneIsConflictBlocked` | "不要调主驾" → CONFLICT,`isBlocked=true` |
| `bothZonesNegatedIsConflictBlocked` | "不要调主驾和副驾" → CONFLICT(8 字符 lookback 修复) |

### 18.2 P1-2:Strict Schema 在执行边界拒绝未声明字段(已闭环)

**问题**:旧实现把 schema 校验委托给 `CapabilityValidator`(业务 lambda),只检查业务级 range。模型多塞字段(`priority`/`avoid_highway`/`user_id`)会直接通过 PolicyEngine 进入 Provider——`additionalProperties=false` 设在 JSON Schema 里,但模型输出是不可信输入。

**修复位置**:`core/policy/PolicyEngine.java:107-151`。

**修复要点**:

1. 新增 `checkStrictSchema(definition, call, cap)`,在 `validateArguments` 之前执行。
2. 第 1 步:遍历 `call.getArguments().keySet()`,任何不在 `definition.getToolParameters()` 声明的字段直接 `denyParameter("未声明参数:...")`。
3. 第 2 步:对每个声明参数检查 `required` / `type` / `range` / `enum`:
   - INTEGER 必须是 `Number` 且 `doubleValue() == Math.rint(iv)`(拒绝 24.5);
   - INTEGER/NUMBER 检查 `minimum` / `maximum`;
   - STRING 检查非空 + `enumValues` 大小写不敏感匹配(允许 "DRIVER" / "driver");
   - BOOLEAN 必须是 `Boolean` 实例。
4. INTEGER 的 enum 用整数值(如 "0"/"1"/"2"/"3")与 schema 配置匹配。

**PolicyEngine.evaluate 流程**(`core/policy/PolicyEngine.java:37-49`):

```java
if (definition.isWriteOperation()) {
    PolicyDecision blocked = checkExplicitIntentBlocked(request, cap);
    if (blocked != null) return blocked;
}
PolicyDecision schemaDecision = checkStrictSchema(definition, call, cap);
if (schemaDecision != null) return schemaDecision;
String violation = definition.validateArguments(call.getArguments());
```

顺序:能力注册 → R3 检查 → **显式意图 fail-closed(P1-1)** → **strict schema(P1-2)** → 业务 validator → zone 校验 → zone 一致性。每一步拒绝都不放宽权限。

**回归用例**(9 个,新增 `PolicyEngineTest`):

| 用例 | 验证点 |
| --- | --- |
| `extraSchemaFieldIsRejectedBeforeProvider` | `priority=high` 不在 schema → PARAMETER 拒绝,reason 含 "priority" |
| `missingRequiredParameterIsRejected` | 缺 `temperature` → PARAMETER 拒绝 |
| `outOfRangeIntegerIsRejected` | `temperature=31` 越界 → PARAMETER 拒绝 |
| `enumValueOutsideAllowedIsRejected` | `zone=rear_left` 不在 enum → PARAMETER 拒绝 |
| `nonIntegerValueIsRejectedForIntegerParam` | `temperature=24.5` → PARAMETER 拒绝 |
| `legalSchemaCallIsAllowed` | 合法调用不被 strict schema 误拒 |
| `extraFieldOnReadOnlyCapabilityIsRejected` | `knowledge.answer` + `user_id` → PARAMETER 拒绝(覆盖只读能力) |
| `extraFieldOnNavigationIsRejected` | `navigation.start_route` + `avoid_highway` → PARAMETER 拒绝(覆盖无 zone 写操作) |
| `multiTargetIntentBlocksBeforeSchemaCheck` | 多目标意图在 strict schema 之前 CAPABILITY 拒绝 |

### 18.3 P1-3:失败 ToolResult 与未知回读字段 fail-closed(已闭环)

**问题**:`AuditRedactor.redactWithSchema` 只在 `isSuccess()` 时用 `auditMessageTemplate`,失败状态保留 Provider 原始诊断文本——"导航到北京市某小区失败:网络不可用"中真实地址会进 Trajectory/UI/Log,凭据正则识别不出业务值。同时 `sensitiveObservedFields` 只对**精确注册**的字段名替换,Provider 新增 `navigation.poi`/`route.address` 等 schema 外字段会走默认 `redactValue` 保留原值。

**修复位置**:`core/agent/AuditRedactor.java:262-323` + `core/capability/CapabilityDefinition.java`(新增字段)+ `core/capability/CapabilityRegistry.java`(navigation 配置)。

**修复要点**:

1. `CapabilityDefinition` 新增两个字段:
   - `auditFailureMessageTemplate`:失败状态(EXECUTION_FAILED / VERIFICATION_FAILED)专用模板。
   - `auditObservedAllowlist`:observedState 中允许进 Audit 视图的诊断安全字段(其余字段一律 mask)。
2. `AuditRedactor.redactWithSchema` 改用 fail-closed 路径:
   - **message**:SUCCESS 用 `auditMessageTemplate`;FAILURE 优先用 `auditFailureMessageTemplate`,未配置但已声明 Audit Schema(`auditMessageTemplate` 非空)时用 `FAILURE_MESSAGE_FALLBACK`(`[capability failed: details redacted]`),**绝不 fall through 到 redact 原文**。
   - **observedState**:进入 `redactWithSchema` 即 fail-closed——`sensitiveObservedFields` 字段用占位符;`allowlist` 字段走 `redactValue`;**其余字段一律 `***`**。
3. `navigation.start_route` 配置:
   - `auditFailureMessageTemplate("导航失败,详情见 errorCode")`
   - `auditObservedAllowlist("navigation.status", "navigation.error_code")`
   - 原有 `auditMessageTemplate` + `sensitiveObservedField(navigation.destination, <destination>)` 保留。

**为何不让 fall through 到 redact(message)**:Provider 失败诊断文本常含目的地 / 地址 / 联系人(真实 Provider 把请求参数嵌入错误描述),凭据正则只识别 API Key/Token/Bearer,识别不出业务值。一旦声明了 Audit Schema,该 capability 就标记了"我的数据有业务敏感字段",失败状态也必须按 schema 投影。

**回归用例**(4 个,新增 `AuditRedactorTest`):

| 用例 | 验证点 |
| --- | --- |
| `schemaRedactsNavigationFailureMessageWithTemplate` | 失败 message 嵌真实地址 → 替换为 `导航失败,详情见 errorCode`,真实地址不进 Trajectory |
| `schemaFallbacksWhenFailureTemplateMissingButAuditSchemaDeclared` | 仅配 `auditMessageTemplate` 未配失败模板 → 用 `FAILURE_MESSAGE_FALLBACK`,不 fall through |
| `schemaMasksUnknownObservedFieldsFailClosed` | `navigation.requested_destination` / `route.address` schema 外字段 → `***`;`navigation.status` / `navigation.error_code` allowlist 字段保留 |
| `failureWithUnknownObservedFieldsMasksBusinessValue` | 失败状态 + 未知字段,失败模板与 allowlist mask 同时生效 |

### 18.4 P2-1:ToolCall 与 Provider FinishReason 一致性校验(已闭环)

**问题**:`parseOpenAiToolResponse` / `parseAnthropicToolResponse` 只对 `length/max_tokens` 做了优先短路,只要响应中存在 ToolCall,无论 `finish_reason` / `stop_reason` 是 stop / missing / unknown,都会返回 `ofToolCalls()` 执行工具——可能接受 `finish_reason=stop + tool_calls` 这类不一致组合。

**修复位置**:`platform/ModelApiClient.java:252-260`(OpenAI)、`platform/ModelApiClient.java:437-444`(Anthropic)。

**修复要点**:

1. OpenAI:LENGTH 短路之后,在解析 tool_calls 结构之前,先校验 `mapOpenAiFinishReason(rawFinishReason) == TOOL_CALLS`;不一致 → `ModelTurn.of(content, NONE)`,AgentEngine 据此走 `StopReason.PROTOCOL_ERROR` 终止,不执行工具。
2. Anthropic:同样在 LENGTH 短路之后、tool_use 结构校验之前,校验 `mapAnthropicStopReason(rawStopReason) == TOOL_CALLS`;不一致 → NONE。
3. 不引入 Provider 兼容策略开关——核心解析器严格要求协议一致,不规范本地服务由调用方在上层显式处理。

**为何放在结构校验之前**:PROTOCOL_ERROR 意味着整个响应不可信,没必要继续做 ID/duplicate/unregistered 校验。这样也避免后续结构校验抛 IllegalStateException 把协议错误掩盖成结构错误。

**回归用例**(5 个,新增 `ModelApiClientContractTest`):

| 用例 | 验证点 |
| --- | --- |
| `openAiNativeToolCallsWithStopFinishReasonIsProtocolError` | OpenAI `finish_reason=stop + tool_calls` → NONE,不执行 |
| `openAiNativeToolCallsWithMissingFinishReasonIsProtocolError` | OpenAI 缺失 `finish_reason + tool_calls` → NONE |
| `openAiNativeToolCallsWithUnknownFinishReasonIsProtocolError` | OpenAI `finish_reason=content_filter + tool_calls` → NONE |
| `anthropicNativeToolUseWithEndTurnStopReasonIsProtocolError` | Anthropic `stop_reason=end_turn + tool_use` → NONE |
| `anthropicNativeToolUseWithMissingStopReasonIsProtocolError` | Anthropic 缺失 `stop_reason + tool_use` → NONE |

同时把现有 OpenAI / Anthropic 协议契约测试的辅助方法(`openAiResponseWithToolCall` / 内联 response 构造)默认带上正确 `finish_reason=tool_calls` / `stop_reason=tool_use`,反映协议正确路径,只让新增的 5 个用例显式构造不一致组合。

### 18.5 测试增量统计

| 测试类 | 第六轮 | 第八轮 | 增量 |
| --- | --- | --- | --- |
| `ExplicitIntentConstraintsTest` | 4 | 9 | +5(P1-1 反例) |
| `PolicyEngineTest`(新增) | 0 | 9 | +9(P1-2 反例) |
| `AuditRedactorTest` | 17 | 21 | +4(P1-3 反例) |
| `ModelApiClientContractTest` | 26 | 31 | +5(P2-1 反例) |
| 其他测试类 | 60 | 59 | -1(原有用例适配新接口) |
| **合计** | **107** | **129** | **+22** |

全 129 测试通过(0 失败 / 0 错误),`./gradlew assembleDebug` 编译通过,`adb install -r && adb shell am start` 启动成功。

### 18.6 第八轮复审建议

针对本轮修复,建议第八轮复审重点验证:

1. **P1-1 完整性**:除了"主驾/副驾"二元,真实场景中可能有"后排左侧"/"后排右侧"/"第三排"等更复杂的多 zone 表达,V0.5.0 NLU 服务接入后,关键词识别要被 NLU 取代。本轮的关键词识别仅作 Demo/联调,真实场景由 Runtime 受信任边界处理。
2. **P1-2 严格性**:strict schema 目前只覆盖 `INTEGER` / `NUMBER` / `BOOLEAN` / `STRING`,未覆盖嵌套 object / array。V0.4.2 引入完整 JSON Schema 后,strict schema 应基于 Canonical Schema 自动派生,而非在 PolicyEngine 里手写 switch-case。
3. **P1-3 模板覆盖**:`navigation.start_route` 是唯一同时配齐 `auditMessageTemplate` + `auditFailureMessageTemplate` + `sensitiveObservedField` + `auditObservedAllowlist` 的 capability。`memory.preference.*` 走全脱敏路径(无具体字段配置)。其他能力(vehicle.* / knowledge.answer)没有 Audit Schema 配置,默认走 `redactValue`——真实 Provider 接入后需要审计这些 capability 是否也需要 schema 投影。
4. **P2-1 Provider 兼容**:某些本地 OpenAI-Compatible 服务(如早期 vLLM / Ollama)可能不规范返回 `finish_reason=stop + tool_calls`。本轮一律按 PROTOCOL_ERROR 拒绝,真实部署时如发现兼容性问题,应在 ModelConfig 增加 `lenientFinishReason` 显式策略,而非让核心解析器无条件放宽。
5. **README 与日志治理**:第七轮 P2-3(README 写 V0.3.2 实际 V0.4.0)和 P2-4(21 个类直接用 `android.util.Log`)未在本轮处理,建议作为 V0.4.1 / V0.4.2 补丁版本演进项。

### 18.7 最终评价

本轮把第七轮标记的 3 个 P1 + P2-1 全部闭环:

| 项 | 第七轮状态 | 第八轮状态 |
| --- | --- | --- |
| P1-1 多区域 / 否定意图降级 | 待修复 | **已闭环**(`IntentType` 4 值枚举 + 8 字符 lookback + 肯定覆盖否定) |
| P1-2 Schema 外参数执行前未拒绝 | 待修复 | **已闭环**(`checkStrictSchema` 在 PolicyEngine 受信任边界拒绝) |
| P1-3 失败结果与未知字段泄漏 | 待修复 | **已闭环**(失败模板 + allowlist fail-closed) |
| P2-1 ToolCall/FinishReason 一致性 | 待修复 | **已闭环**(OpenAI/Anthropic 都校验 `tool_calls` ↔ `finish_reason`) |

剩余 P2 项(P2-2 预算 / P2-3 README / P2-4 日志治理)不阻塞 V0.4.0 主版本号定稿,可作为 V0.4.x 补丁版本演进。

综合判断:

> **V0.4.0 P0/P1 安全边界已全部闭环。129 个测试全绿(含 22 个反例新增),APK 编译/安装/启动通过。建议进入发版流程,剩余 P2 项在 V0.4.1 / V0.4.2 补丁版本继续演进。**

---

## 十九、第九轮 P2 收尾修复记录(2026-08-02)

### 19.1 修复结论

本轮把第八轮遗留下的全部 P2 项闭环,**V0.4.0 全部 4 项 P2 修复完成,无遗留**。

| 项 | 第八轮状态 | 第九轮状态 |
| --- | --- | --- |
| P2-2 三层预算字段存在但未实际生效 | 待修复 | **已闭环**(`AgentBudget` 6 维全生效 + 单元测试) |
| P2-3 README 仍写 V0.3.2 / 24 测试 | 待修复 | **已闭环**(版本、测试数、特性全同步) |
| P2-4 21 个类直接 `android.util.Log` 暴露用户输入 / Tool 参数 / Provider raw | 待修复 | **已闭环**(`SafeLog` 治理入口 + 8 处迁移 + 9 个测试) |

### 19.2 P2-2:三层预算完全生效

**问题**:`AgentBudget` 已有 `maxIterations` / `maxToolCalls` / `totalDeadlineMillis` / `maxMessageChars` / `totalInputChars`,但缺 `maxMessageCount`,且 `maxMessageChars` / `totalInputChars` 在 AgentEngine 里没真正截断/拦截。Repository 还硬编码了 60 秒 deadline,绕过了 budget 配置。

**修复**:

1. `AgentBudget` 新增第 6 维 `maxMessageCount`(默认 64),所有 6 维都文档化"必须实际生效,不能仅作为字段存在"
2. `AgentEngine` 新增两个私有方法:
   - `enforceMessageBudget(AgentMessage)`:单条消息超 `maxMessageChars` 自动截断(按 role 选择 system/user/tool 静态工厂)
   - `appendMessageWithBudget(conversation, message)`:返回 boolean,触发条件 (a) 消息数达 `maxMessageCount` 或 (b) 累计字符达 `totalInputChars` 时返回 false
3. Loop 主体对系统/用户/助理/工具 4 类消息都过 budget 检查,触发时 StopReason = `BUDGET_EXHAUSTED` + FinalState = `FAILED`(或 `PARTIALLY_SUCCEEDED` 当工具已成功)
4. `AgentRuntimeRepository` 把硬编码 `60_000L` 改为 `budget.getTotalDeadlineMillis()`,AppContainer 注入同一 `AgentBudget` 实例给 Repository + AgentEngine(单一权威来源)

**测试增量**:
- `agentLoopTruncatesMessagesExceedingMaxMessageChars`:`maxMessageChars=200` + 1000 字符巨大输入,断言不触发 `BUDGET_EXHAUSTED`(被截断后照常执行)
- `agentLoopTerminatesOnMaxMessageCount`:`maxMessageCount=4`,断言 `BUDGET_EXHAUSTED` + `PARTIALLY_SUCCEEDED`(第 1 次迭代的工具已成功)

### 19.3 P2-3:README 与实现状态同步

**问题**:`README.md` 在 V0.4.0 完成后未更新,仍写"V0.3.2 基线、24 个单元测试",特性列表只到 V0.3.2 阶段,"下一步建议"还指向 V0.4.0。

**修复**:`README.md` 全量重写:

- 版本号:V0.3.2 → V0.4.0
- 测试数:24 → 131(本轮完成后 140)
- 特性列表新增:Agent Loop 主循环、三层预算、Policy 二分、Anthropic 原生 Tool Calling、Trajectory+脱敏、Strict Schema、ExplicitIntent 状态枚举、SafeLog 日志治理
- "下一步建议"从 V0.4.0 改为 V0.4.1(SteerMailbox 真实实现 / 主驾优先 TaskScheduler / cancel 安全边界完善)

### 19.4 P2-4:日志治理安全入口

**问题**:第八轮统计有 21 个类直接调 `android.util.Log`,其中关键暴露点:
- `AgentEngine.execute()` BEGIN 日志写完整 `request.getText()`
- `PolicyEngine.evaluate()` 直接打印 `call.getArguments()`(可能含 destination / home_address)
- `MockCapabilityProvider` 打印导航目的地 / memory preference key+value
- `ModelApiClient` HTTP 错误响应 body 整段写 logcat 和 exception message(body 可能含厂商网关回显的 API Key / 请求参数)
- `AgentTestViewModel.execute()` 打印完整用户输入
- `AgentRuntimeRepository.execute()` 打印完整 command

**修复**:新增 `core/agent/SafeLog.java` 作为受治理日志的**唯一入口**:

```java
public final class SafeLog {
    public static final String USER_INPUT_PLACEHOLDER = "[user-input-redacted]";
    public static final String TOOL_ARGS_PLACEHOLDER = "[tool-args-redacted]";
    public static final String TOOL_RESULT_PLACEHOLDER = "[tool-result-redacted]";
    public static final String PROVIDER_RAW_PLACEHOLDER = "[provider-raw-redacted]";

    // 通用 i/w/e/d:走 AuditRedactor(API Key / Bearer / AIza / ghp / sk-ant 正则 mask + 2000 字符截断)
    public static void i(String tag, String message);
    public static void w(String tag, String message);
    public static void e(String tag, String message);
    public static void d(String tag, String message);

    // 受治理字段专用——直接替换为占位符,只暴露元数据
    public static void iUserInput(String tag, String prefix, boolean allowReveal, String userInput);
    public static void iToolArgs(String tag, String prefix, String capabilityName, Map<String, Object> args);
    public static void iToolResult(String tag, String prefix, String message);
    public static void wProviderRaw(String tag, String prefix, int httpCode, String bodyPreview);
}
```

**治理范围**(必须过 SafeLog):
- 完整用户输入(`request.getText()`、`command`)
- Tool 原始参数(`call.getArguments()`)
- ToolResult 原始 `message` / `observedState`
- HTTP 错误响应体、模型 raw response(`contentHead`、`response.body()`)
- API Key、Token、Bearer、Authorization Header、用户记忆(value / home_address 等)

**不在治理范围**(可直接用 `android.util.Log`):仅含元数据,如 sessionId / requestId / capabilityName / iteration count / StopReason / TaskState / 耗时 / 字符数等结构性指标。

**8 处迁移**:

| 文件 | 行号 | 原日志 | 迁移后 |
| --- | --- | --- | --- |
| `AgentEngine.execute()` | BEGIN | `text="..." + truncate(text, 80)` | `text=[user-input-redacted] textChars=...` |
| `AgentEngine.execute()` | ToolCall | `args=" + call.getArguments()` | `args=[tool-args-redacted] argKeys=...` |
| `AgentEngine.execute()` | ToolResult | `msg=" + truncate(toolResult.getMessage(), 120)` | `msg=[tool-result-redacted]` |
| `AgentEngine.execute()` | sessionContext.addTurn ×2 | `request.getText()` | `[user-input-redacted]` |
| `PolicyEngine.evaluate()` | 入口 | `args=" + call.getArguments()` | `args=[tool-args-redacted]` |
| `MockCapabilityProvider` | navigation dest | `dest=" + destination` | `dest=[tool-args-redacted]` |
| `MockCapabilityProvider` | memory.save | `key=... value=...` | `key=[tool-args-redacted] value=[tool-args-redacted]` |
| `MockCapabilityProvider` | memory.get (not found) | `key=" + key` | `key=[tool-args-redacted]` |
| `ModelApiClient` | line 91 (no tool_calls) | `contentHead="..." + truncate(content, 400)` | `contentHead=[provider-raw-redacted]` |
| `ModelApiClient` | line 699 (HTTP error) | `body=" + truncate(response, 500)` | `body=[provider-raw-redacted]`(走 `wProviderRaw`) |
| `ModelApiClient` | line 700 (exception) | `throw new IllegalStateException("HTTP code:truncate(response,500)")` | `throw new IllegalStateException("HTTP code: body=[provider-raw-redacted]")` |
| `AgentRuntimeRepository.execute()` | line 54 | `command=\"" + truncate(command, 80)` | `command=[user-input-redacted] commandChars=...` |
| `AgentTestViewModel.execute()` | line 57 | `command=\"" + truncate(normalized, 80)` | `command=[user-input-redacted] commandChars=...` |

**保留原 `Log` 调用**:仅含元数据的日志(如 `[Engine] init gateway=DemoModelGateway budget=...` / `[Policy] ALLOW cap=vehicle.climate.set_temperature` / `[Http] <- HTTP 200 respBytes=... costMs=...`),不暴露用户输入或参数 value。

**测试增量**(新增 `SafeLogTest`,9 个用例):
- `placeholderConstantsAreStable`:占位符常量字符串稳定(防后续重构改名导致日志回退)
- `redactMasksOpenAiStyleApiKey`:`sk-abcdef1234567890abcdef` 必须被 mask
- `redactMasksBearerAuthorizationHeader`:`Bearer eyJabc123...` 必须被 mask
- `redactPreservesNonSensitiveMetadata`:`cap=vehicle.climate zone=driver` 等元数据保留
- `iToolArgsPlaceholderForFullyRedactedCapability`:`memory.preference.save` 即使传入真实参数,只打印占位符
- `iToolArgsPlaceholderForRegularCapability`:`navigation.start_route` 也只打印占位符(凭据正则识别不出业务字段,统一 mask)
- `iToolResultAlwaysMasksMessage`:任何 capability 的 ToolResult message 都用占位符包裹
- `wProviderRawMasksResponseBody`:HTTP 错误响应 body 一律占位符
- `iUserInputMasksByDefault`:`allowReveal=false` 时一律替换为占位符

### 19.5 测试增量统计

| 测试类 | 第八轮 | 第九轮 | 增量 |
| --- | --- | --- | --- |
| `AgentEngineTest` | 41 | 43 | +2(P2-2 预算) |
| `SafeLogTest`(新增) | 0 | 9 | +9(P2-4 日志治理) |
| 其他测试类 | 88 | 88 | 0 |
| **合计** | **129** | **140** | **+11** |

全 140 测试通过(0 失败 / 0 错误),`./gradlew assembleDebug` 编译通过,`adb install -r && adb shell am start` 启动成功。

### 19.6 第九轮复审建议

本轮 P2 全部闭环,V0.4.0 已具备发版条件。后续演进建议:

1. **V0.4.1 功能补强**(优先级最高):
   - `SteerMailbox` 真实实现(V0.4.0 留 `drainPendingSteer()` 空方法)
   - cancel 安全边界完善(目前 cancel 在 ModelCall polling 检查点生效,极端情况下仍有 ~100ms 延迟)
   - 主驾优先 `TaskScheduler`(目前 FIFO,V0.4.1 引入 driver 抢占)
   - 可取消 `ModelCall` 抽象(目前用 `Future.cancel(true)`,V0.4.1 切到 OkHttp HttpUrlConnection.cancel())

2. **V0.4.2 Schema 完善**:
   - 完整 JSON Schema(Canonical Schema)+ 按请求生成 `ToolSchemaView`(目前 strict schema 是手写 switch-case)
   - `requiredVehicleStates` / `verifyMethod` 字段接入(目前 `isVerificationRequired` 是 hardcoded)
   - `AuditRedactor` 自动从 Canonical Schema 派生 `sensitiveObservedFields`(目前手写)

3. **V0.5.0 演进**:
   - Token 预算切 tokenizer(目前 char-based,中文场景下偏差较大)
   - 四层 Memory(working / episodic / semantic / procedural)+ Room/WAL 持久化加密
   - Gemini 原生 Tool Calling(目前只支持 OpenAI / Anthropic)
   - Prompt Builder 模块化(目前是单串 systemPrompt)
   - `SafeLog` 在 release build 用 ProGuard keep + 移除 `allowReveal=true` 路径(目前留作诊断模式)

4. **持续观测**:
   - 真实 Provider 接入后,确认 `AuditRedactor` 的 `SECRET_PATTERNS` 覆盖度,如发现新的凭据格式(如 Azure `Ocp-Apim-Subscription-Key`),及时补充正则
   - Trajectory 持久化(V0.5.0)接入前,确认 `AuditRedactor.redact(ToolObservation)` 在所有 capability 上都做了字段级脱敏,不只是 navigation / memory

### 19.7 最终评价

| 项 | 第八轮状态 | 第九轮状态 |
| --- | --- | --- |
| P2-2 三层预算完全生效 | 待修复 | **已闭环**(6 维预算 + AgentEngine enforce + AppContainer 注入) |
| P2-3 README 同步 | 待修复 | **已闭环**(版本 / 测试数 / 特性全同步) |
| P2-4 日志治理 | 待修复 | **已闭环**(SafeLog 入口 + 8 处迁移 + 9 测试) |

综合判断:

> **V0.4.0 全部 P0/P1/P2 项闭环。140 个测试全绿(含 33 个反例新增),APK 编译/安装/启动通过。SafeLog 把用户输入 / Tool 参数 / Provider raw 三个最高风险字段完全治理,日志面只暴露元数据。可以正式发版,后续按 V0.4.1 / V0.4.2 / V0.5.0 路线演进。**

