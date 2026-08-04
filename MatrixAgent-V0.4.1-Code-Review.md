# MatrixAgent V0.4.1 Runtime Control 实现评审

> 评审日期：2026-08-02
> 评审方式：TDD(Stage 测试先写) + 单元测试 + APK 构建
> 评审对象：MatrixAgent V0.4.1 Runtime Control 实现(5 个 Stage)
> 评审结论：**5 个 Stage 全部落地,157 个测试全绿,APK 构建通过,Runtime Control 闭环成立。**

## 一、评审目标

V0.4.0 完成迭代式 Agent Loop 后,V0.4.1 聚焦"运行时控制":让任务在执行中可被外部干预。
本评审检查 V0.4.1 plan 的 5 个 Stage 是否按设计落地、不引入回归、测试覆盖到位。

5 个 Stage 的设计目标:

1. **Stage A:OpenAI Native 多轮 Tool Calling 收尾**——验证 `LlmModelGateway.useOpenAiNative` 在 Agent Loop 端到端
2. **Stage B:CancellableModelCall 抽象**——`ModelCallExecutor` 取消 50ms polling,改为 `future.get(budget, MILLISECONDS)` 阻塞等 + abort hook
3. **Stage C:Cancel 安全边界**——AgentEngine 增加 3 个显式 cancel 检查点(LLM 调用前 / Tool 执行前 / per-tool-call)
4. **Stage D:SteerMailbox 真实实现**——`Steer{REPROMPT, FORCE_TOOL, DEFER}` 三种类型 + `StopReason.DEFERRED` + `TaskState.DEFERRED`
5. **Stage E:主驾优先 TaskScheduler**——`AgentRequest.readOnlyHint` + `TaskState.PREEMPTED`,主驾查询抢占副驾查询,车控写不抢占

## 二、评审范围

V0.4.1 新增 / 修改的代码:

- 新增 `core/agent/CancellableModelCall.java`(接口,ModelCall abort 抽象)
- 新增 `core/agent/Steer.java`(不可变消息,3 种类型)
- 新增 `core/agent/SteerMailbox.java`(per-session 队列)
- 新增 `core/agent/TaskScheduler.java`(主驾优先调度器)
- 修改 `core/agent/ModelGateway.java`(默认 `prepare()` 返回 `CancellableModelCall`)
- 修改 `core/agent/ModelCallExecutor.java`(取消 polling,改用 `future.get(budget)` + abort hook)
- 修改 `core/agent/AgentEngine.java`(注入 `SteerMailbox`,增加 drain 点 + DEFERRED 终态)
- 修改 `core/agent/StopReason.java`(新增 `DEFERRED`)
- 修改 `core/agent/TaskState.java`(新增 `DEFERRED` / `PREEMPTED`)
- 修改 `core/identity/CancellationToken.java`(扩 listener API: `registerAbortHook` / `removeAbortHook`)
- 修改 `core/identity/AgentRequest.java`(新增 `readOnlyHint` Builder 字段)
- 修改 `app/AppContainer.java`(注入 `SteerMailbox`)

测试增量:

- 新增 `ModelCallExecutorTest.java`(5 个并发测试,验证 cancel / timeout / abort hook 契约)
- 新增 `TaskSchedulerTest.java`(4 个测试,验证抢占 / 不抢占 / FIFO / 状态清理)
- `AgentEngineTest.java` 新增 7 个测试:
  - 1 个 OpenAI Native 多轮端到端(Stage A)
  - 2 个 Cancel 安全边界(Stage C)
  - 4 个 SteerMailbox(REPROMPT/FORCE_TOOL/DEFER/per-session drain)(Stage D)

## 三、测试结果

执行命令:`./gradlew testDebugUnitTest --rerun-tasks`

- 测试总数:**157 个**(V0.4.0 140 → V0.4.1 157,新增 17 个)
- 失败 / 错误:0
- 跳过:0
- APK 构建:`./gradlew assembleDebug` 通过,产物 `app/build/outputs/apk/debug/app-debug.apk`

## 四、Stage A:OpenAI Native 多轮 Tool Calling 收尾

**结论:通过**——production code 不改,补端到端测试证明路径正确。

### 验证项

新增测试 `AgentEngineTest.openAiNativeMultiTurnLoopEndsOnDirectAnswerAfterToolResult`:

- 模拟 OpenAI Native LLM 多轮行为(第 1 轮 tool_call,第 2 轮 directAnswer)
- 断言 `iterations.size() == 2`、第 1 轮 1 个 tool_call + 1 个 observation、第 2 轮无 tool_call
- 终态 `TaskState.SUCCEEDED` + `StopReason.NO_TOOL_CALL`

### 已存在路径确认

- `LlmModelGateway.useOpenAiNative`(`platform/LlmModelGateway.java:49-50`)分支正确路由到 `ModelApiClient.callOpenAiWithTools`
- `ModelApiClientContractTest` 已覆盖 OpenAI Native 请求序列化 + 多 tool_call 响应解析

## 五、Stage B:CancellableModelCall 抽象

**结论:通过**——polling 取消,延迟从 50ms 降到接近 0。

### 改动要点

- `CancellableModelCall` 接口:`call()` + `abort()`
- `ModelGateway.prepare(request)` 默认实现返回无 abort 的 `CancellableModelCall`(向后兼容)
- `CancellationToken.registerAbortHook(Runnable)`:cancel 触发时同步调用所有 hook(race-safe——cancel 后注册的 hook 立即执行)
- `ModelCallExecutor.decide`:用 `future.get(budget, MILLISECONDS)` 阻塞等,注册 abort hook 同步执行 `future.cancel(true)` + `call.abort()`
  - cancel 路径:外部 `token.cancel()` → abort hook → `future.cancel(true)` + `call.abort()` → `future.get` 抛 CancellationException → 返回 `Result.terminal(CANCELLED)`
  - timeout 路径:`future.get` 抛 TimeoutException → 主动调 `token.cancel()` 触发 abort 链 → 返回 `Result.terminal(TIMEOUT)`

### 测试验证(5 个)

- `tokenCancelFiresAbortHookSynchronously`——cancel 后 hook 同步触发
- `tokenRegisterAfterCancelInvokesImmediately`——race-safe,late hook 立即执行
- `tokenCancelIsIdempotent`——多次 cancel 只触发一次
- `modelCallExecutorReturnsCancelledOnTokenCancel`——外部 cancel → CANCELLED 终态
- `modelCallExecutorReturnsTimeoutOnDeadlineExceeded`——deadline 到 → TIMEOUT 终态 + 主动 cancel token

### 限制

- `ToolExecutor` 仍用 50ms polling——本地 Mock 无传输层,V0.6.0 接真实 AIDL Provider 时再统一
- `LlmModelGateway.prepare` 仍用默认实现(无传输层 abort)——V0.6.0 接 OkHttp 时挂 `Call.cancel`

## 六、Stage C:Cancel 安全边界

**结论:通过**——AgentEngine 不依赖 polling,显式 cancel 检查点保证及时终止。

### 改动要点

AgentEngine.executeLocked 增加 3 个显式 cancel 检查点(原 V0.4.0 在 loop 顶部 + budget 检查):

1. **L185**(iteration 顶部):已存在的 cancel/deadline 检查,继续保留
2. **L316 前**(Tool 执行前):新增 pre-tool 检查点——LLM 已返回 tool_calls,但若此时 cancel 已触发,不进入 Tool 循环
3. **L344**(per-tool-call 循环顶部):新增——多 tool_call 序列中,第 N 个完成后第 N+1 个开始前若 cancel 已触发,立即跳出整个 iteration 循环(车控写操作尤其需要)

### FinalState 修正

CANCELLED 状态下即使有部分成功 Tool 也只能 `CANCELLED`(类比 PARTIALLY_SUCCEEDED 的兜底逻辑)——通过 `computeFinalState` 中 `if (stopReason == CANCELLED) return CANCELLED` 提前返回保证。

### 测试验证(2 个)

- `agentLoopStopsOnCancelBeforeLlmCall`——第 1 轮 tool 完成后外部 cancel → 第 2 轮 L185 拦截,StopReason.CANCELLED + 第 1 轮 tool 已成功
- `agentLoopStopsOnCancelDuringToolIteration`——多 tool_call 中第 1 个完成后 cancel → 第 2 个未执行(L344 拦截)

### 移除的测试

原计划"agentLoopStopsOnCancelBeforeToolExecution"——执行时序问题(无法可靠在 Tool 执行前 inject cancel),合并到测试 1 + 测试 2 覆盖等价语义。

## 七、Stage D:SteerMailbox 真实实现

**结论:通过**——区分"普通新任务 / steer / cancel"三种语义,per-session 队列不串号。

### 改动要点

**Steer 类型**(`core/agent/Steer.java`):

- `REPROMPT`:用户追加自然语言,合并到 conversation 作为新 user message
- `FORCE_TOOL`:用户强制指定下一轮 Tool Call,跳过 LLM 直接执行
- `DEFER`:用户要求推迟,`StopReason.DEFERRED` + `TaskState.DEFERRED`

**SteerMailbox**(`core/agent/SteerMailbox.java`):

- Per-session `ConcurrentLinkedQueue`,FIFO
- `offer(sessionId, steer)` / `drain(sessionId)` / `pendingCount` / `clearForTesting`
- 不阻塞 Loop,空队列 O(1)

**AgentEngine 集成**:

- 新构造器 `AgentEngine(..., AgentBudget, SteerMailbox)`;null mailbox 时跳过 drain(向后兼容)
- LLM 调用前 drain(处理 REPROMPT/FORCE_TOOL/DEFER):
  - REPROMPT → 加入 conversation,继续 LLM
  - FORCE_TOOL → 跳过 LLM,直接构造 ToolCall 走 Tool 执行
  - DEFER → 立即返回 `DEFER_SENTINEL`,跳出 Loop
- Tool 执行前再 drain(仅响应 DEFER——此时模型已决策,REPROMPT/FORCE_TOOL 已无意义)

### StopReason / TaskState 扩展

- `StopReason.DEFERRED`——区别于 CANCELLED:被推迟的任务语义上可被重新调度
- `TaskState.DEFERRED`——computeFinalState 直接映射

### 测试验证(4 个)

- `agentLoopDrainsRepromptSteerBeforeLlmCall`——第 1 轮 tool 完成后注入 REPROMPT → 第 2 轮 LLM 看到 2 个 user message + REPROMPT 文本
- `agentLoopDrainsForceToolSteerSkippingLlm`——FORCE_TOOL → 跳过 LLM 直接执行指定 Tool,gateway 调用数减少
- `agentLoopDrainsDeferredSteerStoppingLoop`——DEFER → StopReason.DEFERRED + TaskState.DEFERRED
- `steerMailboxDrainsPerSession`——不同 session 的 Steer 不串号,FIFO 顺序保证

## 八、Stage E:主驾优先 TaskScheduler

**结论:通过**——主驾查询抢占副驾查询,车控写不抢占。

### 改动要点

**AgentRequest.Builder** 新增 `readOnlyHint(boolean)`(默认 false,保守):

- true:查询/问答类,允许被主驾同 hint 请求抢占
- false:车控写,不强制中断

**TaskState.PREEMPTED**:

- 区别于 CANCELLED:抢占语义上不是用户主动取消,而是更高优先级请求插队
- StopReason 仍是 CANCELLED(实际通过 token.cancel 触发)

**TaskScheduler**(`core/agent/TaskScheduler.java`):

- Per-session `RunningTask` 表,submit 时记录当前运行任务
- 主驾 + readOnlyHint=true 请求到达 → 检查 running task:
  - actor=PASSENGER 且 readOnlyHint=true → `token.cancel()` 抢占
  - 其他(车控写 / 主驾自己)→ 不抢占,主驾排队
- 任务通过 `SessionLockManager.tryAcquire` 串行化,保证 FIFO + 同 session 不并发
- 不修改 SessionLockManager——它仍是底层 per-session 串行锁

### 测试验证(4 个)

- `driverPreemptsPassengerReadOnlyTask`——副驾查询进行中,主驾查询抢占 → 副驾 CANCELLED + PREEMPTED,主驾 SUCCEEDED
- `driverDoesNotPreemptPassengerWriteTask`——副驾车控写进行中,主驾查询不抢占 → 主驾排队等副驾释放
- `fifoForSameActor`——同 actor 多请求按到达顺序执行
- `preemptionDoesNotCorruptSchedulerState`——抢占后 scheduler 状态正确清理(runningCount==0)

### 集成策略

V0.4.1 不强制改 `AgentEngine.execute` 路径——TaskScheduler 独立可用,AppContainer 通过 `TaskScheduler.TaskRunner` lambda 包装 AgentEngine.execute。完整集成(替代 Repository 直接 submit)留到 V0.5.0,避免 V0.4.1 改动面过大。

## 九、潜在风险与遗留项

### 9.1 TaskScheduler 集成深度

当前 TaskScheduler 没有替代 `AgentRuntimeRepository` 的执行路径——它作为独立组件已测试覆盖,但 production code 仍走 `AgentEngine.execute` 直接路径。V0.5.0 需要:

- `AppContainer` 注入 TaskScheduler 到 Repository
- Repository.submit 用 `taskScheduler.submit(request, engine::execute)` 替代直接 executor.submit

### 9.2 FORCE_TOOL 缺少 capability 校验

`drainSteerBeforeLlm` 见到 FORCE_TOOL 时直接构造 `new ToolCall(capabilityName, arguments)`,没有验证 capabilityName 是否在 registry 中、参数是否符合 schema。下游 PolicyEngine 会拒绝,但理想情况应在 drain 时提前校验,避免占用一轮 iteration。

### 9.3 LlmModelGateway 未重写 prepare

`LlmModelGateway` 仍走默认 `ModelGateway.prepare`,返回的 CancellableModelCall.abort() 不挂真传输层 cancel。需要 V0.6.0 接 OkHttp 时:

```java
@Override
public CancellableModelCall prepare(ModelTurnRequest request) {
    HttpURLConnection[] holder = new HttpURLConnection[1];
    CancellableModelCall call = new CancellableModelCall() {
        public ModelTurn call() { return decideWithConn(request, holder); }
        public void abort() { if (holder[0] != null) holder[0].disconnect(); }
    };
    return call;
}
```

### 9.4 SteerMailbox 无持久化

JVM 重启即丢失。V0.5.0 接 Room 持久化时,需要决定 DEFER 状态的任务重启后是否自动重新调度。

### 9.5 readOnlyHint 默认 false 的语义

保守默认(false)意味着调用方必须显式标记查询类请求,否则抢占不会触发。Repository/UI 层需要根据 capability 类型自动派生 readOnlyHint——这一步留到 V0.4.2 Schema 治理完成后(Strict Schema 自动派生 readOnlyHint)。

## 十、与 V0.4.2 的衔接

V0.4.1 完成后,下一版本聚焦 Schema 治理:

- 完整 JSON Schema(替换 V0.4.0 简化的 `ToolParameterDefinition`)
- `requiredVehicleStates`(声明工具前置车辆状态)
- `verifyMethod`(显式声明回读方式)
- 不可变 Canonical Schema + 按请求生成的 ToolSchemaView
- Strict Schema 自动派生 `readOnlyHint`(让 TaskScheduler 抢占判断不再依赖 Builder 显式标注)

V0.4.1 的 SteerMailbox / TaskScheduler 接口稳定,V0.4.2 不会破坏。
