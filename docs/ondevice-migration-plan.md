# 把 Operit 的 MNN 端侧大模型推理迁移到 MatrixAgent（V3）

> V3 整合评审第二轮（6 个实现级 P0 + 4 个 P1）。路线与阶段 0 已放行。本版重点：把 `:ondevice` 边界、生命周期安全、取消精确性、fail-closed 语义、token 公式做实。

## 修订历史

### V2（路线层，已认可）
独立 `:ondevice` / CPU 首发 / native go/no-go / MNN structured chat template / structured 全历史 / 真实 token 计数 / 离线启动链 / 下载持久化 / native 装机验证。

### V3（实现级 P0）
| P0 | 落实 |
|---|---|
| **P0-1** `:ondevice` 不能依赖 `core.FinishReason` | `GenerationResult` 用 `:ondevice` 自有 `OnDeviceFinishReason{STOP,LENGTH,CANCELLED,FAILED}`；`OnDeviceModelGateway` 映射到 `core.agent.FinishReason` |
| **P0-2** 禁止超时强 close（native 未返回时 destroy 仍 use-after-free） | retire 流程：cancel 活动调用→等 JNI 返回→引用归零才 close；**超时只标记 stuck/retired 拒绝新请求，绝不 destroy**。定义 `RetirableModelGateway` 接口（非 AutoCloseable）；生命周期管理器由 AppContainer 注入（持 ioPool） |
| **P0-3** 取消按请求区分 | `prepare()` 建 per-call `CallState`；abort 时若该 call 未持 native 锁（排队中）→ 仅标记取消/移出队列；**仅当该 call 是当前持锁者**才触发 `nativeCancel`；禁止全局 `llm.cancel()` 误伤推理中的他人 |
| **P0-4** parser fail-closed | 未知工具/重复 ID/参数 JSON 损坏 → **整轮 PROTOCOL_ERROR(NONE)，不执行剩余有效 call**（与云端 fail-closed 一致）；缺 ID 可本地生成，重复 ID 不"保留首个" |
| **P0-5** system 不重复 | AgentEngine 已把 systemPrompt 作为 `AgentMessage.system()` 放进 conversation（AgentEngine L340）且同时传 `request.getSystemPrompt()`。codec **以 conversation 的 SYSTEM 为准、去重相同内容**，不用 `request.getSystemPrompt()` 再加；测试断言 prompt 文本只出现一次 |
| **P0-6** token 公式修正 | 先校验 `maxAllTokens > minPromptTokens`，再 `maxNewTokens ≤ maxAllTokens - minPromptTokens`；`nativeCount==0` 当模板/配置错误处理（报错/降级），不当"可激进裁剪" |

### V3 P1（阶段 1/3 前补）
- **P1-a**：MNN 实际字段是 `llm_model`/`llm_weight`/`embedding_file`/`tokenizer_file`（非 embedding/tokenizer）；所有路径归一化 + **禁止逃逸模型目录**（防 path traversal）。
- **P1-b**：字符预算（AgentEngine L369 `estimateConversationChars`/L368 压缩/L340 单条截断）是**外层安全上限**，与 gateway 内 native token 裁剪并存；"AgentEngine 零改动"修正为"gateway 内做精确 token 裁剪，Engine 字符预算作兜底"，长期可引 provider-aware budget。
- **P1-c**：阶段 0 的 16KB 验收 = `zipalign -c -P 16 -v 4 <apk>` + 检查 `.so` ELF LOAD 段 16KB 对齐（非"zipalign 或 apkanalyzer"）。
- **P1-d**：固定 Qwen 工件写死：仓库版本/commit、每个文件 SHA-256、`llm_config.json` 哈希、支持 tools 的模板、验证设备型号，保证两轮验收可复现。

## Context

MatrixAgent 的 LLM 现只走云端 HTTP，无端侧推理。目标：迁入 Operit MNN-LLM，让 Agent Loop 用本地模型离线做带 tool call 决策。**纯 Java；完整功能。** 命门是 MNN submodule 源码编译，故风险前置。环境就绪（NDK 27.0.12077973 + CMake 3.22.1 已装）。

## 架构决策（6 条）

1. **独立 `:ondevice` library module**：native 隔离在此；暴露**不依赖 `core.agent` 的纯接口 `OnDeviceLlm`**；`:app` 的 `OnDeviceModelGateway` 适配。隔离的是**代码边界与失败影响**，不是构建成本（`:app` 依赖 `:ondevice` 仍触发 native 构建）。
2. **CPU only 首发**：CMake 显式 OFF FORCE 所有 GPU 后端；UI 仅在 native 真编入时暴露对应 forwardType。
3. **模型放私有目录** `getFilesDir()/models/mnn/<name>/`。
4. **下载用 HttpURLConnection** + 大文件走 WorkManager/前台服务。
5. **tool call 走 MNN Structured 路径**（传 JSON 绕开 cpp 的 kotlin/Pair）；chat template 靠 MNN 内置 minja。
6. **arm64-only 明确告知**：启动/UI 检 ABI，非 arm64 禁选端侧。

## 核心契约设计（P0 重点）

### 3.1 请求编解码 `MnnStructuredRequestCodec`（P0-1/P0-4/P0-5）

不抽用 `ModelApiClient.toOpenAiMessages()`。新建 `platform/MnnStructuredRequestCodec.java`：
- **system 去重（P0-5）**：`request.getConversation()` 首条即 `AgentMessage.system(systemPrompt)`（AgentEngine L340）；codec **遍历 conversation 保留首条 SYSTEM、跳过后续内容相同的 SYSTEM**，**不**再用 `request.getSystemPrompt()` 单独加。测试断言：systemPrompt 文本在 messagesJson 中只出现一次（内容级，非计数）。
- **多轮历史完整映射（P0-4）**：`ASSISTANT`→`{role:assistant, content, tool_calls:[{id(stepId), type:function, function:{name(modelName), arguments(JSON string)}}]}`；`TOOL`→`{role:tool, content, tool_call_id, name?}`，`tool_call_id`↔上一条 assistant 的 `tool_calls[].id` 严格配对。MNN 每轮 reset，必须传全历史。
- **tools JSON**：复用 `ModelApiClient.toOpenAiTool`(L971)/`toModelToolName`(L958) 形状（抽共享 `OpenAiRequestJson`）。
- **round-trip 单测**：system→user→assistant(tool_calls)→tool(result)→user 序列 → 断言配对、system 文本唯一、capability↔modelName 双向可还原。

### 3.2 `GenerationResult` + `OnDeviceFinishReason`（P0-1，补 Operit 缺口）

`OnDeviceFinishReason` 是 **`:ondevice` 自有枚举**（`{STOP, LENGTH, CANCELLED, FAILED}`），不依赖 `core.agent.FinishReason`：

```java
// :ondevice
final class GenerationResult {
    String text;  OnDeviceFinishReason finishReason;
    int promptTokens, generatedTokens;  long prefillUs, decodeUs, sampleUs;
    String nativeError;  // FAILED 时非空
}
```

**finishReason 判定**（Operit 自己都没有）：生成后调 `nativeGetContextInfo` 取 `gen_seq_len`；CANCELLED（abort）→`CANCELLED`；nativeError≠null→`FAILED`；`gen_seq_len>=maxNewTokens`→`LENGTH`；否则→`STOP`。

**gateway 映射到 `core.agent.FinishReason` + AgentEngine 语义对齐**（关键）：
- `STOP` + 有 tool_calls → `ModelTurn.ofToolCalls`（FinishReason.TOOL_CALLS）
- `STOP` + 无 tool_calls → `directAnswer`（STOP）
- `LENGTH` → `ModelTurn.of(text, FinishReason.LENGTH)`（→ AgentEngine L438 `LENGTH_EXCEEDED` 终止；**不解析 tool call**）
- `CANCELLED`/`FAILED` → **不通过 finishReason 表达**：CANCELLED 走 `prepare().abort()`→ModelCallExecutor cancel 机制（→ AgentEngine `CANCELLED` 终态，L360）；FAILED 抛异常让 ModelCallExecutor 走 terminal。**绝不映射成 `FinishReason.NONE`**（否则误触 AgentEngine L454 `PROTOCOL_ERROR`）。

### 3.3 tool call 解析 `OnDeviceToolCallParser`（P0-3/P0-4，fail-closed）

输入 `GenerationResult`（先判 finishReason，**LENGTH/CANCELLED/FAILED 不解析**）。校验任一失败 → **整轮失败**（返回空 calls + 标记 PROTOCOL_ERROR），**不部分执行**：
- **arguments**：JSONObject 或 JSON 字符串 → Map；解析失败 → 整轮失败。
- **未知工具**：`function.name` 反查 `modelToCap` 未命中 → 整轮失败。
- **重复 ID**：同一 id 两次 → 整轮失败（不"保留首个"）。
- **不完整 JSON**：括号不匹配 → 整轮失败。
- **缺 ID**：本地稳定 ID（`sanitize(name)+hash(args)+index`），后续轮次复用保证配对。

### 3.4 token 预算与裁剪（P0-6 + P1-b）

用 `nativeCountTokensWithStructuredMessages`（含 tools 开销）。**公式修正**：
- 前置校验：`maxAllTokens > minPromptTokens`（否则模型不可用，报错）；`maxNewTokens = min(配置值, maxAllTokens - minPromptTokens)`；`maxPromptTokens = maxAllTokens - maxNewTokens`。
- **countTokens==0 当错误**（模板/配置问题），报错或降级，**不当"可激进裁剪"**。
- 裁剪：二分，保留 system 前缀，按 **system + 完整 assistant/tool group** 为最小单元从队首丢，**绝不裁在 group 中间**。
- **P1-b**：此裁剪在 gateway 内进行；AgentEngine 的字符预算（L369/L368/L340）作为**外层安全上限**并存兜底。

### 3.5 串行 + lease 生命周期（P0-2 + P0-3，最关键）

**(a) session 串行 + per-call cancel（P0-3）**：`OnDeviceModelGateway` 内 `ReentrantLock` 串行 native 调用。`prepare()` 每次建独立 `CallState`：
- `abort(callState)`：若该 call **未持锁**（排队中）→ 标记 `cancelled`、移出队列（不触 native）；**仅当 `callState == currentHoldingCall`**（正在 native 推理）→ 才调 `nativeCancel`。**禁止全局 `llm.cancel()`**。
- 拿锁后先检查本 call 的 `cancelled` 标志，命中则直接返回（不进 native，不算 lease 消耗）。

**(b) lease 退役（P0-2，绝不强 destroy）**：
- 定义接口 `RetirableModelGateway extends ModelGateway { void retire(); }`（不靠 AutoCloseable——它无 retire()）。
- `OnDeviceModelGateway` 维护 `AtomicInteger activeLeases`（持锁执行 +1，finally -1）。
- **`GatewayLifecycleManager`**（由 `AppContainer` 注入，持 ioPool；`AgentRuntimeRepository` 不持有 ioPool）负责退役：`setModelGateway` 检测旧 gateway `instanceof RetirableModelGateway` → 调 `retire()`（标记退役、拒新请求）→ 提交 ioPool：先 `cancel` 活动调用、**等 JNI 返回**（等 `activeLeases==0`）→ 归零后 `close()`（`MNNLlmSession.release()`）。
- **超时兜底只标记 `stuck`**（继续拒新请求、记 warning/audit），**绝不 destroy**——直到 native 真返回或进程结束。避免 use-after-free。
- 新 gateway 异步 ioPool 加载完才 `setModelGateway` 热切换；启动期 Demo 占位 + 异步 load。

### 3.6 启动期离线（P0-7）

`AppContainer`：L207-220 分类器，`saved.protocol==ON_DEVICE` → `KeywordIntentClassifier`（不建 `LlmIntentClassifier`）；L287 摘要 → heuristic 降级。切换 Provider 同步切对应模式。

## native 改造

- 改包名：21 个 JNI 前缀 → `Java_com_matrix_agent_ondevice_mnn_MNNLlmNative_`。
- 删 3 个 history(Pair) 函数 + `parseChatHistory`；保留 Structured 系列 + create/load/release/setConfig/reset/cancel/dumpConfig/getContextInfo；删音频。
- CMake：显式 `set(... OFF CACHE BOOL ... FORCE)` 所有 GPU；保留 `MNN_BUILD_FOR_ANDROID_COMMAND`/`MNN_BUILD_SHARED_LIBS`/`MNN_SEP_BUILD=OFF`/`-fno-emulated-tls`/`-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`/`LLM_USE_MINJA`/16KB 三 target/rapidjson；`add_library(MNNWrapper SHARED mnnllmnative.cpp)`。MNN submodule 钉死 `18759c83`。

## 分阶段实施

| 阶段 | 目标 | 工作量 | 关卡 |
|---|---|---|---|
| 0 | native .so + 装机 loadLibrary + release 16KB | 1-2 天 | **go/no-go** |
| 1 | 真实两轮 tool call + 5 边界 | 4-6 天 | 功能+契约 |
| 2 | 配置 UI（端侧/arm64 提示） | 2-3 天 | — |
| 3 | 下载（WorkManager + Range/ETag/原子目录） | 5-7 天 | — |
| 4 | 打磨 | 2-3 天 | — |

### 阶段 0：native 编译 PoC（go/no-go）
验收：① `:ondevice:externalNativeBuildDebug` 出 .so ② `:app:assembleDebug` ③ 装 arm64 真机 `loadLibrary` 成功 ④ release **`zipalign -c -P 16 -v 4`** + `.so` ELF LOAD 段 16KB 对齐（P1-c）⑤ CMake 显式 OFF FORCE GPU + 保留命令式/共享库/SEP=OFF/-fno-emulated-tls/flexible-page-size。

### 阶段 1：最小推理链路
交付：`:ondevice` 5 封装 + `OnDeviceLlm`/`GenerationResult`/`OnDeviceFinishReason`/`MnnOnDeviceLlm`/`MnnLoadOptions`/`OnDeviceLlmFactory`/`RetirableModelGateway`/`GatewayLifecycleManager`；`:app` 的 `MnnStructuredRequestCodec`(+round-trip 单测)/`OnDeviceToolCallParser`/`OnDeviceModelGateway`(串行锁+per-call cancel+lease)/`OpenAiRequestJson`/token 裁剪/配置层。
验收（**固定 Qwen 工件，写死版本+SHA-256+config 哈希+设备**，P1-d）：主路径 system→tool call→observation→最终答复两轮（配对不断、历史完整）+ 5 边界（取消/截断/未知工具/并发串行/切换 lease 退役）。`run-as` 或应用内导入，不只 adb root。

### 阶段 2：配置 UI
端侧隐藏 endpoint/key/test；CPU-only 不暴露 GPU forwardType；arm64 检测禁选+提示；`testOnDevice` 走本地。

### 阶段 3：下载管理（P1-a/P0-10）
WorkManager/前台服务；206/200 Range、ETag/Content-Range、磁盘预检、原子完成目录、重启恢复、校验和/受信白名单、取消清理。**模型有效性**：解析 `llm_config.json` 的 `llm_model`/`llm_weight`/`embedding_file`/`tokenizer_file` 逐个 exists（P1-a 字段名）；路径归一化 + **禁止逃逸模型目录**。

### 阶段 4：打磨
多模型、release proguard 验证（apkanalyzer 看 .so + 反射 `onToken` 命中）、`onTrimMemory`→`reset()`、UI 指标、文档。

## 风险清单

| 风险 | 等级 | 缓解 |
|---|---|---|
| MNN commit + NDK 27 编译失败 | **高（阶段0）** | 钉死 `18759c83`；先单跑 `externalNativeBuildDebug`；清 `.cxx`/`build` |
| Operit 无 LENGTH 判定 | **高** | 自补 `gen_seq_len>=maxNewTokens`→LENGTH；LENGTH 不解析 tool call |
| 切模型 use-after-free / 泄漏（P0-2/5） | **高** | lease 等 JNI 返回归零才 close；超时只标 stuck 不 destroy |
| 取消误伤（P0-3） | **高** | per-call CallState；仅持锁者触发 nativeCancel |
| parser 部分执行（P0-4） | **高** | fail-closed 整轮失败 |
| session 并发 crash | **高** | ReentrantLock 串行 |
| 模型 chat_template 不支持 tools | **高** | Qwen2.5-Instruct；降级 directAnswer |
| 内存（1-4GB） | 高 | mmap；低 precision/memory；onTrimMemory |
| AGP 9.0.1 library 坑 | 中 | 根 build.gradle 补 library plugin；consumerProguardFiles |
| proguard 裁剪 JNI/回调 | 中 | keep `MNNLlmNative`+`GenerationCallback`+`native<methods>` |
| ModelScope 限流/变更 | 中 | 缓存+重试 |
| 16KB page 对齐 | 低 | 三 target `-Wl,-z,max-page-size=16384`；`zipalign -c -P 16 -v 4` |

## 现有可复用资产

`ModelGateway`/`ModelTurn`/`ModelTurnRequest`/`ToolCall`(Map)/`AgentMessage`；`ModelGatewayRepository.createModelGateway` L77 / `createDemoGateway`；`ModelApiClient.toOpenAiTool`/`toModelToolName`（抽 `OpenAiRequestJson`）；`KeywordIntentClassifier`；Gemini L627 LENGTH-before-toolcall 模式；`AgentEngine`/`PolicyEngine`/`ToolExecutor`/`Memory`/`Audit`（契约零改动，字符预算作外层上限）。

---

## 阶段 0 已放行——待确认的唯一前置

- [ ] **P1-d 验证工件**：阶段 1 启动前需确定固定 Qwen MNN 工件（仓库/commit + 文件 SHA-256 + `llm_config.json` 哈希 + 支持 tools 的模板 + 验证设备）。阶段 0（native 编译）不依赖此，可立即开始。
