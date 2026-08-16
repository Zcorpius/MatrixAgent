# MatrixAgent 语音助手实施设计与进度计划

> 文档状态：当前实施基线，按现有代码重写。
> 更新时间：2026-08-12。
> 当前阶段：阶段 2 代码基本落地（2A–2E + P1-1~P1-11 修复，2F 指标待实现），真机验收待补；阶段 1 真机闭环验收仍需补齐。
> 架构决策：MatrixAgent Runtime 保持单应用、单进程、进程内直连，不实施独立无头 Agent Service、AIDL Runtime、Settings 跨进程客户端或 Binder 身份体系。

---

## 1. 结论与路线

### 1.1 核心结论

后续语音能力直接建立在当前单进程代码之上，不再等待或依赖无头服务化：

1. `MatrixAgentApplication` 继续持有 `AppContainer`，语音层进程内调用 `AgentRuntimeRepository`。
2. 当前阶段固定使用 `Actor.DRIVER`，只声明“单用户、主驾、默认音区”的产品范围。
3. 语音入口、采音、VAD、ASR、TTS 和 Agent 之间继续通过现有端口隔离，不把 Android 音频或模型实现塞入 `AgentEngine`。
4. 真正免按键唤醒优先接 OEM DSP / SoundTrigger / OEM 语音前端；必要时接系统 `VoiceInteractionService`，但它只是语音入口组件，不引入独立 Agent Runtime 进程或通用 AIDL 调用面。
5. Vosk 保留为阶段 1/2 的可运行基线和降级路径；后续端侧极致版通过同一组端口评测、替换 KWS/ASR/VAD/TTS 引擎。

### 1.2 明确不做

当前路线不包含以下内容：

- 不创建 `:agent` 进程，不迁移到 `AgentRuntimeHost`。
- 不新增 `MatrixAgentService`、Runtime AIDL、Provider 或 Settings 客户端。
- 不引入 `IdentityScope`、`principalUserId` 或 Binder UID 身份解析。
- 不对外导出任意文本执行接口，不允许第三方 App 触发语音 Agent。
- 不在没有 OEM/系统授权的情况下，以普通 App 后台永久 `AudioRecord` 冒充量产常驻唤醒。
- 在完成真正的执行前确认机制前，不向语音开放需要用户确认的高风险能力。
- 在完成座舱输入归属和音频路由验证前，不声明主副驾识别、多音区或多人并发能力。

### 1.3 版本路线

| 阶段 | 定位 | 核心交付 | 当前状态 |
|---|---|---|---|
| 阶段 1：Debug 语音闭环 | 验证可行性 | Vosk 唤醒、中文 ASR、Agent、规则播报、播报期打断 | **代码已完成并合入；真机验收待补** |
| 阶段 2：前台稳定版 | 把 Demo 变成可靠的单进程语音基础设施 | 正确语音元数据、真实端点、超时/重试、AudioFocus、稳定打断、指标和模型完整性 | **代码基本落地（2A–2E），真机验收/2F 指标待补** |
| 阶段 3：真实免按键唤醒 | 接入系统/OEM 唤醒入口 | OEM/System Wake 适配、应用级 `VoiceRuntime`、会话期采音、发布开关 | 待阶段 2 |
| 阶段 4：安全确认与可发布车控 | 支持需要确认的写操作 | 执行前冻结、确认 token、受限语法、拒绝/超时闭环 | 待阶段 2，可与阶段 3 后半并行 |
| 阶段 5：端侧极致体验 | 优化准确率、延迟、功耗和离线能力 | 专用 KWS/VAD、本地流式 ASR、本地 TTS、模型治理、流式打断 | 待阶段 2 指标基线 |
| 阶段 6：AAOS 产品化 | 目标车型发布 | 真车音频策略、默认音区、噪声测试、灰度、回滚和长期稳定性 | 依赖 OEM 条件 |

阶段 3 在阶段 4 完成前只能开放只读能力和产品批准的低风险能力；需要确认的车控必须保持关闭。

---

## 2. 当前代码基线

### 2.1 工程基线

| 项目 | 当前值 |
|---|---|
| 分支 | `main` |
| 语音合入提交 | `5b3d414`（合并 `voice-demo-v1`） |
| App 版本 | `0.5.5` |
| `minSdk` / `targetSdk` / `compileSdk` | 28 / 36 / 36 |
| ABI | `arm64-v8a` |
| Vosk | `com.alphacephei:vosk-android:0.3.75` |
| 唤醒模型 | `vosk-model-small-en-us-0.15`，grammar：`hey matrix` |
| 中文 ASR 模型 | `vosk-model-small-cn-0.22` |
| TTS | Android `TextToSpeech`，中文 |
| 运行边界 | 默认应用进程，`AppContainer` 进程内直连 |
| 当前入口 | `VoiceDebugFragment`，仅通过 `BuildConfig.DEBUG` 展示 |

### 2.2 已实现模块

| 层 | 已有实现 | 当前责任 |
|---|---|---|
| `core/voice` | `VoiceSessionState`、`VoiceEvent` | 定义会话状态和事件迁移 |
| `core/voice` | `WakeWordPort`、`AsrPort`、`VadPort`、`TtsPort`、`AgentRunner` | 隔离唤醒、识别、端点、播报和 Agent 调用 |
| `core/voice` | `ResponsePresenter`、`SpeakableResponse` | 将 Agent 终态转换为白名单、短文本、可播报结果 |
| `data/voice` | `VoiceSessionController` | 在串行 `stateExecutor` 上驱动状态和副作用 |
| `data/voice` | `VoskModelDownloader`、`VoskModelSpec` | 下载、断点续传、解压 Vosk 模型并记录进度 |
| `platform/voice` | `VoskWakeAdapter` | 通过英文 Vosk grammar 检测 `hey matrix` |
| `platform/voice` | `VoskAsrEngine`、`VoskAsrAdapter` | 中文流式 partial/final 识别 |
| `platform/voice` | `VoskEndpointAdapter` | 将 Vosk endpoint 封装成 `VadPort`；已接入 VoiceSessionController（setListener/reset） |
| `platform/voice` | `VoiceCaptureController` | 16 kHz 单声道采音、AEC、PCM 路由和 RMS 打断检测 |
| `platform/voice` | `AndroidTtsAdapter` | 播报、停止、完成/错误回调 |
| `presentation` | `VoiceDebugViewModel`、`VoiceDebugFragment`、`VoiceUiState` | 权限、模型装配、生命周期和 Debug 状态展示 |

### 2.3 当前实际调用链

~~~text
VoiceDebugFragment（页面可见）
  → 请求 RECORD_AUDIO
  → VoiceDebugViewModel
      → 下载/加载英文唤醒模型和中文 ASR 模型
      → 创建 VoskWakeAdapter / VoskAsrAdapter / AndroidTtsAdapter
      → 创建 VoiceSessionController / VoiceCaptureController

VoiceCaptureController（AudioRecord，16 kHz mono PCM）
  ├─ IDLE       → VoskWakeAdapter
  ├─ LISTENING  → VoskAsrAdapter
  ├─ SPEAKING   → RMS 能量检测 → onBargeIn()
  └─ 其它状态   → 不分发 PCM

hey matrix
  → WAKE_ACCEPTED → LISTENING
  → Vosk final
  → RECOGNIZING → THINKING
  → AgentRunner
  → repository.execute(VoiceAgentRequest)  // 贯通 InputSource.VOICE / language / asrConfidence / audioZoneId
  → AgentOutcome
  → ResponsePresenter → SpeakableResponse
  → Android TextToSpeech
  → IDLE
~~~

### 2.4 当前已验证能力

- 语音相关 JVM 测试 86+ 个 `@Test`（VoiceSessionControllerTest / VoiceSessionStateTest / ResponsePresenterTest / VoicePolicyConfigTest / ModelPathResolverTest / BargeInDetectorTest 等），覆盖状态机、Controller、播报隐私、模型治理、置信度解析和取消路径。
- `VoiceSessionController` 已处理 Agent 迟到结果、销毁后迟到回调和 executor 关闭竞态。
- `VoskAsrEngine` 与 `VoskWakeAdapter` 已用锁保护 native recognizer 的 `feed/close` 竞争。
- `VoiceCaptureController` 已实现按 Session 隔离的 `AudioRecord/AEC` 生命周期、幂等释放和启动失败回滚。
- Fragment 退后台会停止采音，回前台恢复；ViewModel 销毁会按 Recognizer、TTS、Model 的依赖顺序回收。
- `ResponsePresenter` 只读取终态、能力名和聚合计数，不读取 Tool 参数、Provider message、Memory 原文或异常堆栈。
- Debug/Release 编译在阶段 1 评审中通过；仓库还提供两项 Vosk native/识别仪器测试。

### 2.5 现状与旧文档不一致的地方

以下为**阶段 1 评审时**的真实缺口快照（历史参照）；其中绝大多数已在后续轮次（见 §5.6 第九轮已实施项）闭合，本表仅保留作演进记录：

| 优先级 | 缺口 | 当前证据 | 影响 |
|---|---|---|---|
| P0 | Agent 请求没有标记为语音 | `VoiceDebugViewModel` 调用旧 `repository.execute(text, Actor.DRIVER, token)`；Repository 构造 `AgentRequest` 时未设置 `InputSource.VOICE` | Policy、Prompt、审计无法区分语音和触摸输入 |
| P0 | `VadPort` 没有驱动状态机 | `VoskEndpointAdapter` 只在装配时创建，Controller 不持有它，也未注册 listener | 目前靠同一个 final 事件连续迁移两次，端点、final 和超时边界不清晰 |
| P0 | 没有会话超时 | 唤醒后无语音、最长说话时长、等待 final、Agent/TTS 阶段没有语音层独立 watchdog | 会话可能长时间占用麦克风或卡态 |
| P0 | 没有 AudioFocus | `AndroidTtsAdapter` 直接调用 TTS，没有 request/release/loss 处理 | 与媒体、导航、电话冲突，AAOS 上不可发布 |
| P1 | ASR 置信度是假值 | Controller 对 final 固定传 `1f`，且未传入 Repository | 无法对低置信度命令 fail closed |
| P1 | 打断仅是播报期能量阈值 | `VoiceCaptureController` 只在 `SPEAKING` 做 RMS 判断 | 容易被 TTS 回声/噪声误触发；THINKING 期间不能靠语音说“停止” |
| P1 | TTS 初始化没有显式就绪状态 | `AndroidTtsAdapter` 异步初始化，未就绪时直接报错回 IDLE | 冷启动第一次播报可能丢失 |
| P1 | 模型只校验 marker | 下载器没有 SHA-256、版本清单或完整目录原子切换 | 下载源变化或损坏时可能加载错误模型 |
| P1 | Release 只是隐藏 UI | Vosk 依赖、语音类和 `RECORD_AUDIO` 仍在 main source/main manifest | 不能表述为“release 不包含语音代码/权限” |
| P2 | 没有无敏感指标 | 没有 `VoiceMetricsPort` | 无法用数据调端点、误唤醒和打断阈值 |
| P2 | 每帧分配 byte 数组 | `copyExact()` 对每个 50 ms PCM 帧分配 | 长时间前台唤醒增加 GC 压力 |

阶段 1 的准确结论是“代码闭环已完成”，不是“车机语音能力已经完成”。真机端到端、噪声、AudioFocus 和长期运行仍未验收。

---

## 3. 单进程目标架构

### 3.1 总体架构

~~~text
┌────────────────────── MatrixAgent 单应用进程 ──────────────────────┐
│                                                                    │
│ MatrixAgentApplication                                             │
│   └─ AppContainer                                                  │
│       ├─ AgentRuntimeRepository                                    │
│       ├─ 模型 / Memory / Audit / Capability Runtime                │
│       └─ Lazy VoiceRuntime（阶段 2/3 引入，进程内对象，不是 Service）│
│           ├─ VoiceEntryCoordinator                                 │
│           │   ├─ DebugVoskWakeEntry（阶段 1/2）                    │
│           │   ├─ OemWakeEntry（阶段 3，优先）                      │
│           │   └─ SystemVoiceEntry（阶段 3，按系统能力选择）        │
│           ├─ VoiceSessionController                                │
│           ├─ VoiceCaptureController                                │
│           ├─ VadPort / AsrPort / TtsPort                           │
│           ├─ AudioFocusPort                                        │
│           ├─ TranscriptValidator / ConfirmationCoordinator         │
│           ├─ ResponsePresenter                                     │
│           └─ VoiceMetricsPort                                      │
│                    │                                               │
│                    └─ AgentRunner → AgentRuntimeRepository（直连）  │
│                                                                    │
│ VoiceDebugFragment / 产品语音 UI                                   │
│   └─ 只观察状态、发 start/stop，不持有 native Model 和 AudioRecord  │
└────────────────────────────────────────────────────────────────────┘
~~~

### 3.2 `VoiceRuntime` 的定位

`VoiceRuntime` 是阶段 2 后建议增加的应用内组合对象，不是无头服务，也不对外暴露：

- 由 `AppContainer` 懒创建；不进入语音页面、不收到系统/OEM Wake 时不加载语音模型、不申请麦克风。
- 统一拥有 Controller、音频端口、模型和 executor，避免这些资源继续绑定 Fragment/ViewModel 生命周期。
- 同一进程只允许一个活动 VoiceSession；UI 只是 observer。
- Debug 页面销毁只解除 observer；是否结束语音由明确的 Runtime 模式决定。
- 进程退出即结束所有会话，不恢复待确认动作，也不持久化 PCM。

阶段 2 可以先保留 ViewModel 所有权完成正确性改造，再在阶段 3 接入真实唤醒前迁移到 `VoiceRuntime`；但最终不能让系统/OEM 唤醒入口依赖某个 Fragment 已创建。

### 3.3 端口边界

保留现有端口，并只增加后续确有消费者的接口：

| 端口/组件 | 输入/输出 | 责任 |
|---|---|---|
| `WakeWordPort` | PCM 或 OEM Wake → wake/error | 仅检测/接收唤醒，不操作 Agent |
| `VoiceCapturePort` | start/stop → PCM/error | 统一 AudioRecord、输入设备、AEC/NS 生命周期；**已抽最小接口**（start/stop/TerminationListener，follow-up14）：`VoiceRuntimeTest` 假件消费者驱动，Runtime 依赖接口；AudioRecord/AEC/会话隔离细节仍在 `VoiceCaptureController`，量产 OEM 采音链路（DSP/无头服务）替换实现 |
| `VadPort` | PCM → speech/quiet/endpoint | 语音起点、静音和端点；不能同时承担 ASR final |
| `AsrPort` | PCM → partial/final/error | 返回 `FinalTranscript`，包括文本、语言和真实/可用的置信度 |
| `AudioFocusPort` | request/release/loss | 使用与 TTS 相同的 AudioAttributes 请求焦点并处理丢失 |
| `TtsPort` | `SpeakableResponse` → ready/start/done/error | 播报、停止、就绪和错误，不读取 Agent 内部对象 |
| `AgentRunner` | `VoiceAgentRequest` → `AgentOutcome` | 进程内适配 Repository，便于 Controller JVM 测试 |
| `VoiceMetricsPort` | 结构化无敏感事件 | 记录耗时、状态、错误码和计数，不记录音频或 transcript |

不新增通用 `AgentVoiceFacade`。当前没有跨进程不可信客户端，`AgentRunner` 这个窄接口已经足够隔离 Controller 与 Repository。

### 3.4 语音请求模型

阶段 2 新增不可变值对象，避免继续传散乱参数：

~~~text
FinalTranscript {
  text
  languageTag
  confidence
  confidenceAvailable
  speechStartedAtElapsedRealtime
  speechEndedAtElapsedRealtime
}

VoiceAgentRequest {
  transcript
  actor = DRIVER
  audioZoneId = DEFAULT_DRIVER_ZONE
  inputSource = VOICE
  cancellationToken
}
~~~

Repository 增加进程内重载，例如 `execute(AgentInvocation)`；旧文本入口继续委托为 `InputSource.TOUCH`，保持已有 UI 和测试兼容。语音路径构造 `AgentRequest` 时必须显式写入：

- `InputSource.VOICE`
- `languageTag`
- `asrConfidence`
- `audioZoneId`
- 当前 `CancellationToken`

未知置信度不能伪装成 `1.0`。若引擎无法提供置信度，应保留 `confidenceAvailable=false` 并按引擎策略保守处理；Vosk 路径应开启 word result，按有效 word 的置信度聚合，空结果按 0 处理。

### 3.5 线程与并发模型

| 线程/执行器 | 允许做 | 禁止做 |
|---|---|---|
| 采音线程 | `AudioRecord.read`、轻量 PCM 路由、无分配或池化帧 | 状态迁移、下载、Agent、阻塞 UI |
| ASR/KWS native 临界区 | `acceptWaveForm/getResult/close` | JSON 解析、listener 回调、长耗时日志 |
| `stateExecutor` | 唯一状态迁移、端口 start/stop 编排、timeout generation 校验 | 阻塞 Agent、模型下载、长时 native 推理 |
| `agentExecutor` | 阻塞执行 Agent，等待终态 | 操作 UI、直接改变语音状态 |
| 模型 I/O executor | 下载、校验、解压、模型准备 | 持有生命周期锁做耗时工作 |
| 主线程 | UI render、权限结果 | AudioRecord、模型加载、Agent 调用 |

所有异步回调都携带 `voiceSessionId/generation`。Controller 只接受当前 generation 的 ASR、TTS、Agent 和 timeout 回调，防止旧会话结果污染新会话。

### 3.6 Runtime 模式与会话状态分离

“语音功能是否运行”和“当前会话处于哪一步”是两个不同维度：

| Runtime 模式 | 麦克风 | 用途 |
|---|---|---|
| `STOPPED` | 关闭 | 页面不可见、用户关闭语音、权限被撤销 |
| `FOREGROUND_SOFTWARE_WAKE` | 前台持续采音，仅喂 KWS | 阶段 1/2 的 Vosk 调试/前台体验 |
| `SYSTEM_WAKE_READY` | MatrixAgent 不常驻采音 | 阶段 3 等待 OEM/System WakeEvent |
| `ACTIVE_SESSION` | 会话期采音 | Wake 后 VAD/ASR/确认/打断 |

会话内部继续使用 `VoiceSessionState`。阶段 2 将状态流收敛为：

~~~text
IDLE
  --WAKE--> WAKE_ACCEPTED
  --CAPTURE_STARTED--> LISTENING
  --VAD_ENDPOINT--> ENDPOINTING
  --ASR_FINAL--> RECOGNIZING
  --VALID--> THINKING
  --AGENT_TERMINAL--> SPEAKING
  --TTS_DONE--> IDLE

任意活动态 --CANCEL/ERROR/TIMEOUT--> 清理 --> IDLE
SPEAKING --BARGE_IN--> BARGE_IN_LISTENING --CAPTURE_STARTED--> LISTENING
~~~

不再用同一个 `FINAL` 事件连续投递两次来跨过 `ENDPOINTING`。VAD endpoint 和 ASR final 是两个独立事件；final 先于 endpoint 到达时进入短暂缓存，endpoint 后消费，避免依赖回调顺序。

### 3.7 统一超时策略

初始默认值作为代码配置集中管理，真机数据出来后调整：

| 超时 | 初始值 | 超时行为 |
|---|---:|---|
| Wake 后等待开始说话 | 5 s | 无声回 IDLE，不提交 Agent |
| 单次最大说话时长 | 15 s | 强制 endpoint，等待 final |
| endpoint 后等待 final | 2 s | 本轮 ASR 失败；最多一次复述 |
| 低置信度/空文本复述 | 1 次 | 第二次失败结束会话 |
| TTS 初始化 | 3 s | 不丢终态；显示/播报不可用错误并回 IDLE |
| TTS 单次播报 | 按文本长度估算并设硬上限 | stop、回收焦点、回 IDLE |
| 确认窗口（阶段 4） | 8 s | 拒绝待执行动作 |

timeout 必须使用 `elapsedRealtime` 语义和 generation 校验，不能依赖可被用户修改的 wall clock。

---

## 4. 阶段 1：Debug 语音闭环

### 4.1 目标

证明现有 MatrixAgent 可以在不改 Agent Engine 主链的情况下完成：

~~~text
软件唤醒 → 中文 ASR → Agent → 安全播报 → 播报期打断
~~~

### 4.2 已完成内容

1. Vosk 英文 grammar 软件唤醒词 `hey matrix`。
2. Vosk 中文流式 partial/final ASR。
3. 16 kHz、单声道、16-bit PCM 采音和 AEC 尝试启用。
4. `VoiceSessionController` 串行状态迁移与 Agent 独立 executor。
5. 固定 `Actor.DRIVER` 进程内调用 Repository。
6. `ResponsePresenter` 白名单生成 `SpeakableResponse`。
7. Android TTS 播报与 SPEAKING 状态 RMS 打断。
8. 模型自动下载、Range 续传、取消保留断点和解压路径逃逸防护。
9. Debug UI、麦克风权限、前后台采音暂停/恢复和完整销毁顺序。
10. 状态机、Controller、播报隐私、模型下载的 JVM 测试，以及 Vosk 仪器测试入口。

### 4.3 阶段 1 剩余验收

代码合入不等于真机完成。进入阶段 2 开发时，先用目标 arm64 设备补齐以下基线：

- [ ] 冷启动首次下载、解压和模型加载成功。
- [ ] 连续说出 `hey matrix` 能稳定进入 LISTENING。
- [ ] 中文命令能完成 ASR → Agent → TTS。
- [ ] TTS 期间讲话能停止播报并进入下一轮识别。
- [ ] 页面退后台后麦克风释放，回前台可恢复。
- [ ] 连续 30 次会话无 native crash、双 AudioRecord、TTS 卡死和明显内存增长。
- [ ] 记录初始唤醒命中率、误触发、端点延迟、打断时延和峰值内存，作为阶段 2 对比基线。

如果上述真机闭环尚未通过，阶段 2 可以并行做纯 Java/结构改造，但不能宣布阶段 1 完成验收。

---

## 5. 阶段 2：前台稳定版

### 5.1 阶段目标

把“能跑的 Debug Demo”升级为“页面前台期间可靠、行为可测、语义正确”的单进程语音基础设施。阶段 2 不解决后台常驻真实唤醒，也不开放高风险确认能力。

### 5.2 工作包 2A：语音请求语义贯通（P0）

实现内容：

1. 新增 `FinalTranscript` 和 `VoiceAgentRequest`。
2. 修改 `AsrPort.Listener.onFinal`，返回文本、语言、置信度和置信度可用性。
3. 修改 `AgentRunner` 接受 `VoiceAgentRequest`，而不是裸 `String`。
4. 为 `AgentRuntimeRepository` 新增请求对象重载，旧接口保持兼容。
5. 构造 `AgentRequest` 时显式写入 `VOICE/language/confidence/audioZoneId/token`。
6. `VoiceSessionController` 不再硬编码 `LANG="zh-CN"` 和 confidence `1f`。

验收：

- 单测截获最终 `AgentRequest`，断言 `InputSource.VOICE`。
- 语言、置信度、audio zone 和 token 与 ASR/VoiceSession 输入完全一致。
- AgentTest 文本入口仍为 `InputSource.TOUCH`，全部既有测试不退绿。

### 5.3 工作包 2B：VAD、端点和转写校验（P0）

实现内容：

1. Controller 正式依赖 `VadPort` 并注册 listener。
2. `VoskEndpointAdapter` 的 endpoint 驱动 `LISTENING → ENDPOINTING`。
3. ASR final 只负责 `ENDPOINTING → RECOGNIZING`，删除当前双投 `FINAL` 的兼容代码。
4. 增加 speech-start、最大说话时长、final 等待和复述次数 watchdog。
5. 增加 `TranscriptValidator`：空文本、过短噪声、置信度、语言检查。(注:不做自然语言身份/座位黑名单——身份始终来自可信上下文 `Actor.DRIVER`/默认音区,命令提及目标座位是合法能力参数,由 capability/Policy 裁决,见 §15。)
6. Vosk 开启 word result，计算可解释的聚合置信度；无有效 word 时为 0。
7. partial 只用于 UI，不进入 Agent、Audit 或日志。

建议先采用两级策略：普通查询低置信度请求一次复述；车控写意图使用更严格阈值或暂时拒绝。阈值不写死在 Controller，放入 `VoicePolicyConfig`，以真机语料调优。

验收：

- endpoint 和 final 任意先后顺序都只提交一次 Agent。
- 无语音、空 final、低置信度、ASR error、超时均稳定回 IDLE。
- 任一会话最多复述一次，不形成无限循环。
- 迟到 final 不得进入新 generation。

### 5.4 工作包 2C：音频焦点和 TTS 生命周期（P0）

实现内容：

1. 新增 `AudioFocusPort` 和 Android 实现。
2. 使用 `AudioAttributes.USAGE_ASSISTANT` + `CONTENT_TYPE_SPEECH`；TTS 和焦点请求必须使用相同属性。
3. 播报前 request focus；拒绝时不播报并返回稳定错误。
4. TTS done/error/cancel 后 release focus。
5. focus loss 时立即 stop TTS，取消过期播报并回 IDLE；默认不自动恢复。
6. `TtsPort` 增加 ready/init-error，Controller 等待就绪或超时，不丢第一次播报。
7. 每次 speak 使用唯一 utterance/session id，迟到的旧 utterance callback 被 generation 丢弃。

验收：

- 与媒体同时播放时焦点行为符合 Android/AAOS 配置。
- 来电、导航或模拟 focus loss 后 TTS 在目标时延内停止。
- TTS 冷初始化不再把第一次 Agent 结果直接丢掉。
- 100 次 speak/stop 不残留 focus owner 或旧 callback。

### 5.5 工作包 2D：可靠打断和取消（P1）

当前 RMS 方案保留为 fallback，但升级为策略化实现：

1. `BargeInDetector` 独立于 `VoiceCaptureController`，输入 PCM、TTS 播放状态和 AEC 可用性。
2. 优先用 VAD speech + 连续帧判定，RMS 只做前置门限。
3. AEC 不可用或真机回声过大时自动降级为半双工：TTS 播报期间不开放免唤醒打断，用户可说固定停止词前先由 OEM 前端检测，或使用明确的 UI 停止操作。
4. 触发顺序为：立即 `tts.stop()` 降低用户感知延迟 → `token.cancel()` → 清空待播队列 → 开启新一轮 ASR。
5. THINKING 期间语音“停止”需要独立监听策略；在没有可靠并行 KWS 前，保持 UI cancel，不虚假声明全双工。
6. 工具写操作已经下发时遵循现有 `EXECUTION_UNKNOWN` 语义，不能因为 cancel 就播报“肯定没执行”。

验收：

- 播报自身回声不应稳定触发打断。
- 有效插话到设备静音的 p95 初始目标不高于 300 ms，最终以目标硬件评测确定。
- cancel 后任何旧 Agent/TTS 回调不改变新会话状态。
- 写操作取消结果不谎报。

### 5.6 工作包 2E：生命周期、模型治理和 Release 边界（P1）

> 实施进度（多轮评审至 2026-08-13，第九轮）：模型治理（版本目录 + active/previous + 原子切换 + SHA-256 + 版本匹配 + 旧布局事务性迁移）、VoiceRuntime 资源收敛 + 生命周期（lifecycleLock 互斥 + start 幂等 + 前后台 pendingBuild）、并发隔离（Agent generation / ASR sessionId / TTS utteranceId / Capture sid + starting CAS）、失败恢复（wake 有限重建 + WAKE_UNAVAILABLE 上报 / capture 时间窗口熔断 / pipeline 异常捕获 + recognizer 重建）、Release 无麦克风权限。第九轮已闭合的并发/正确性深化项见下。

**阶段 2 并发治理：第九轮已实施项**（原列“量产前深化”，现已代码完成 + 全量验证）：
- timeout 子阶段 epoch：speechStart/maxSpeech/finalWait 改用 `scheduleAsrTimeout(expectedAsrSid)`，speakTimeout 改用 `scheduleSpeakTimeout(expectedUtteranceId)`，fire 时校验捕获的子阶段号——旧 ASR 轮/旧播报的迟到 timeout 不误杀新轮。
- promote 原子事务：`atomicWriteVersion` 用 tmp + `getFD().sync()` + rename（同 FS 原子，**抗普通进程死亡**；未 fsync 目录，不保证掉电 durable）；promote 两步均 tmp+fsync+rename，崩溃在 active 切换前则 previous 已持久可回退；启动侧 `cleanTmpResidue` 清 active.tmp/previous.tmp 残留。
- 并发安装：download 对 per-spec `.lock_<name>` 文件 `FileChannel.tryLock()` + 持锁复查 isDownloaded（双检）；`tmpVersionDir` 用 `.tmp_<version>_<uid>` 唯一名；`unzipFlatten` 加 `cancelled` 逐 entry 检查。
- 批 B P2 小项：manualWake 去多余 runInState（onWake 内已串行）；buildAndStart 从锁内读最新 pendingStateListener（start in-flight 换 listener 立即生效）；下载 COMPLETED 用递归目录大小（非 `finalVersionDir.length()`）+ createdAt 保留旧 DAO 记录；`WakeWordPort.start()` 返回结构化 `WakeStartResult`，重建成功立即清零 wakeRetryCount（不等用户说唤醒词）。
- 批 C P2 接口/线程改造：AsrPort/WakeWordPort/VadPort 的 `feed` 统一为 `feed(byte[], int len)`，captureLoop 直传 pcm+n（删 copyExact 每帧 byte[] 分配；Vosk `acceptWaveForm` 改用传入 len，正确处理短帧尾部残留）；VoiceRuntime pause/resume 的 capture.start/stop 投 `lifecycleExecutor`（单线程 FIFO，与 pause 的 stop / resume 的 start 串行保序），主线程只设意图标志、不 join（cap.stop 的 join 移出主线程）。shutdown(onCleared) 的 cap.stop 也投 lifecycleExecutor 异步（follow-up 评审已改,与 pause/resume 对齐）。
- 批 D Release 拆分 Vosk（#8）：main 经 `VoiceEntryProvider` 接口 + `VoiceEntryProviders`（反射加载 debug 实现 `DebugVoiceEntryProvider`）解耦 voice Demo 引用（MainActivity / MatrixViewModelFactory 不再直接引用 VoiceDebugFragment/ViewModel）；引用 `org.vosk` 的实现链（VoskAsrEngine/VoskAsrAdapter/VoskWakeAdapter/VoskEndpointAdapter/VoskModelHolder）+ VoiceRuntime + VoiceDebugFragment/ViewModel/UiState 移 `src/debug`（包名不变，仅源集目录迁移）；Vosk 依赖 `implementation` → `debugImplementation`。core/voice 纯端口 + 契约（FinalTranscript/VoiceAgentRequest 被 AgentRuntimeRepository 引用）及不引用 org.vosk 的实现（VoiceSessionController/VoskModelDownloader/VoskResultParser/AndroidTts/AndroidAudioFocus/VoiceCaptureController）留 main。验证：Release APK 不含 libvosk.so（~10MB）、Debug 含；main manifest 无 RECORD_AUDIO（debug manifest 专有）；debug+release 编译 + 全量单测 + lintVitalRelease 通过。

**阶段 2 待开发功能（非第九轮修复范畴——独立功能/测试工作线，需时单独立项）**：
- 2F 指标（plan §5.7 工作包 2F，新功能）：仍 NoopVoiceMetrics。收集点、字段、采样策略需先定义，非 bug 修复。
- VAD speech 主路径（功能增强）：仍纯 RMS。能量 VAD 的 speech start/stop 阈值需真机座舱噪声调优，桌面/模拟器盲调无意义。
- instrumented 测试（测试补充）：VoiceCaptureController/AndroidTts/AndroidAudioFocus androidTest + VoskModelDownloader JVM 增量；无 arm64 设备只能编译验证跑通。
- 100 次真机会话 / 资源回基线：需 arm64 设备，仅 checklist。

> 第九轮评审的 8 P1 + 6 P2 缺陷已在批 A/B/C/D 全部修复（正确性 / 并发 / 性能 / 发布质量），全量验证通过。

**全量评审修复（2026-08-14，5 P1 + P2 + P3）**：① AndroidTest feed 签名（`feed(pcm, pcm.length)`）修复编译;② 下载锁竞争重构——进程内 per-path lock(消除同 JVM OverlappingFileLockException)+ 跨进程 FileLock,锁竞争(`LockBusyException`)/取消/真实失败三分支,**只有持锁的文件所有者在真实失败时清理共享 tmp**;③ TTS watchdog 误杀修复——`start()` 不再排 ready 超时,加 INITIALIZING/READY/FAILED 状态机,只在 `onTerminal` 产生 pendingSpeak(确实要播报)时才等,冷启动 LISTENING/THINKING 不再被 TTS 超时打断;④ 未知置信度 fail-closed——`confidenceAvailable=false` → REJECT(复述一次仍未知则结束),依赖 Vosk 正常转写产出 word result(`confidenceAvailable=true`);⑤ 模型 fallback 回滚指针——`ModelPathResolver.rollbackActive`(previous 成功加载后原子 active←previous + 删坏 active),防"v2 坏→临时 v1→装 v3 把 v2 留作 previous→v1 被删→无回滚"链路;⑥ `AndroidTtsAdapter` closed 状态(shutdown 设 closed,迟到 onInit/speak/stop 全 no-op);⑦ `VoiceRuntime.shutdown` cap.stop 投 lifecycleExecutor(异步,不阻塞主线程);⑧ unzip 内层读循环取消(每 1MB)+ 存储预检(`installSizeBytes`/`getUsableSpace`)+ 解压上限(zip bomb);⑨ 断点 zip 命名含 version(跨版本不共享断点);⑩ 日志去绝对路径(稳定 code 前缀);⑪ atomicWriteVersion durable 承诺收紧(抗进程死亡,未 fsync 目录,不保证掉电)。回归测试:ModelPathResolver.rollbackActive 3 例(含完整链路)+ VoskModelDownloader 磁盘预检/取消 + TranscriptValidator fail-closed + VoskResultParser 已有覆盖。全量验证通过(testDebugUnitTest + assembleDebugAndroidTest + assembleDebug/Release + lintDebug)。

**follow-up 评审修复（2026-08-14，5 P1 并发深水 + P2）**：① TTS pending 泄漏——加 `clearPendingTts()`(focus loss/cleanup/barge-in/onWake/shutdown/initError 调用)+ `cancelAllTimeouts` 补 ttsReadyFuture + `onTtsReady` 校验 SPEAKING(generation 由 ready timeout 绑定校验) + ready timeout 绑 generation,防迟到 onTtsReady 在 IDLE 无焦点播报 / 旧 ready timeout 跨会话误杀;② DAO 顺序——download 持锁后 `isDownloaded` 先于 `setStatus DOWNLOADING`(第二等锁者不覆盖 COMPLETED);③ LockBusy 恢复——`VoiceRuntime` catch 后有界轮询 `isDownloaded`(500ms/1s/2s,~60s),完成续装配;④ rollback 事务顺序——`rollbackActive` 改先写 active 指针后删坏目录(崩溃留 active 可加载 + 坏目录残留,无"指针指已删目录");⑤ 统一模型锁 `ModelInstallLock`(`withTryLock` install/migrate + `withLock` 阻塞 rollback)——promote/migrateLegacy/rollbackActive 全经同一 per-langDir 锁 + 跨进程 FileLock,migrateLegacy 移入 download 锁内;⑥ TTS init 解耦——`onTtsInitError` 仅 SPEAKING 收敛,LISTENING/THINKING 的 Agent 不被异步 TTS init 取消;⑦ `AndroidTtsAdapter` `synchronized(lock)` 串行化 onInit/speak/stop/shutdown + shutdown 清 listener + progress 查 closed;⑧ 网络中断保断点——瞬态网络错误(SocketTimeout/Connect/UnknownHost/InterruptedIO)PAUSED 保 zip 重试,真实损坏(HASH/ZIP_SLIP/UNZIP_TOO_LARGE)FAILED 删;⑨ `checkStorage` 预算改 sizeBytes+installSize 峰值+10% 余量。回归测试:pending TTS focus loss/cancel。全量验证通过。

**follow-up2 评审修复（2026-08-14，2 P1 + 3 P2 + 2 P3）**：① LockBusy 改退避重试 download(`retryDownloadWithBackoff`,自己接管缺失模型,不要求另一实例下完两个);② downloadZip 先判 HTTP 状态码(仅 200/206 写,429/408/5xx 抛可重试 `HTTP_<code>` 保断点);③ `ModelInstallLock` 改 `ReentrantLock`(withTryLock `tryLock()` 立即返回,rollback `lockInterruptibly` 可中断);④ focus 移 `speakNow`(TTS ready 后真正 speak 前抢,不在未就绪时压低音乐);⑤ 补 TTS ready timeout fire 测试(SPEAKING+pendingSpeak→fire→IDLE);⑥ public `migrateLegacy` 走统一锁(download 内直调避免重入);⑦ 文档对齐(shutdown 异步/onTtsReady 校验 SPEAKING)。全量验证通过。

**follow-up3 评审修复（2026-08-14，3 P2 + 1 P3）**：① `retryDownloadWithBackoff` 改 60s 有界退避(while+deadline,非 7.5s,84MB 慢网络足够预算);② `isTransientNetwork` 递归 cause + SocketException(Connection reset/Broken pipe)+EOFException(覆盖包装异常);③ 回归测试——ServerSocket mini HTTP server 验证 503/429 保 tmpZip + 404 删 tmpZip + `ModelInstallLockTest` 验证 withTryLock 同进程立即 LockBusy(<200ms 真 try);④ 删旧假阳性 ttsReadyTimeout 测试。全量验证通过。

**follow-up4 评审修复（2026-08-14，2 P2 测试强度 + 1 P3 注释）**：① 503/429 测试加 `assertEquals(50L, tmpZip.length())` 断点字节不变(防截断误过)+ 补 SocketException(disconnect server)路径验证保断点;② TTS ready timeout 测试补副作用断言(metrics TTS_READY/tts.stop/KWS 重启/迟到 onReady 不播报)+ `FakeAudioFocusPort.requestCount` 验证未 ready 时不抢 focus(0)/speakNow 后(1);③ Controller 类注释"ready timeout 不绑 generation"更正为"绑 generation"。全量验证通过。

**follow-up5 评审修复（2026-08-14，2 P2 契约/可测 + 1 P3 断连）**：① TTS 同步回调契约——`TtsPort.speak(response, utteranceId)` 改由 Controller 先生成 id 并在 speak 前设 `expectedUtteranceId`(防 speak() 内同步 onError/onDone 因 ID 未设被丢,单线程 executor 掩盖该竞态,换 direct/其他 executor 会暴露);AndroidTtsAdapter/FakeTtsPort 适配 + direct-executor 同步回调回归测试;② retry 可测——抽纯 Java `RetryPolicy`(注入 Clock/Sleeper),retryDownloadWithBackoff 经它,`RetryPolicyTest` 覆盖成功接管/持续忙超时/取消/真实失败 4 路径;③ 断连测试加固——disconnect server 加 CountDownLatch 断言 accept + 校验异常链含 SocketException/EOFException/ConnectException/SocketTimeout。全量验证通过。

**follow-up6 评审修复（2026-08-14，2 P2 + 1 P3）**：① RetryPolicy sleep 截断为剩余时间 + sleep 后复查 deadline(防越界——剩余 0.5s 不 sleep 2s,过 deadline 不跑 body),补 sleep 后过 deadline body 不执行测试;② disconnect server 连续 accept + setSoLinger(true,0) RST 多次 + 关 ServerSocket(快速稳定 Socket 异常,消除 HttpURLConnection 重试卡 30s read timeout);③ speakNow 在 speak() 返回后校验仍 SPEAKING+utteranceId 匹配才排 watchdog(防同步 onError 收敛后挂无效 timeout),同步失败测试断言无遗留 watchdog。全量验证通过。

**follow-up7 评审修复（2026-08-14，3 P3）**：① 取消/收敛/shutdown 作废 `expectedAsrSid=Long.MIN_VALUE`(旧采音线程已取出的 partial/onError 回调全部丢弃,不在 IDLE 更新 UI/重复清理重启 KWS)+ cancel 后旧回调无副作用测试;② `speakNow` 在 `focusPort.request()` 后校验仍 SPEAKING(防 request 重入触发 focus loss → IDLE 后仍播的窄竞态,不播则 release 返回)+ 同步 focus-loss fake 测试;③ `ModelInstallLockTest` 改无耗时硬阈值(contender 在 holder 持锁期间经 CountDownLatch 完成+得到 LockBusy,上界 2s,CI 抖动不偶发失败)+ `FinalTranscript` javadoc 更新(word confidence 已由 VoskResultParser 解析,与实现一致)。全量验证通过。至此连续 8 轮评审(全量+follow-up 1-7)收敛为 0 P0/P1/P2,余 P3 全清。

**follow-up8 评审修复（2026-08-14，1 P2 + 1 P3）**：① P2——正常成功路径的 `consumeFinal()` 在 `asrPort.stop()` 后同样作废 `expectedAsrSid=Long.MIN_VALUE`(此前只覆盖 cancel/shutdown/cleanup;旧 sid 迟到 onAsrError 会误杀已进 THINKING/SPEAKING 的正常会话),复述路径由 `startAsr()` 覆盖新 sid,补"成功 final 进 SPEAKING 后旧 sid onError 不杀 SPEAKING/不 stop TTS/不 restart KWS"测试;② P3——FakeWakeWordPort 加 `startCount`,旧回调前后断言计数不变(旧的仅断言 KWS 在跑,即使错误触发 cleanup+restart 也通过),删恒真的 sid 装箱非空断言。全量验证通过(981 JVM 单测)。

**follow-up9 评审修复（2026-08-14，1 P1 + 2 P3）**：① P1——采音线程误报设备故障:stop() 置 running=false 后 AudioRecord.stop() 促阻塞 read() 返回负码,循环先报 `CAPTURE_READ_ERROR_*` 才查 running,正常 pause→resume 计入故障预算;且采音线程直连 `controller.onCaptureError`(无 sid 校验),迟到清理任务可影响恢复后的新会话。修复:采音线程不再直连 Controller,错误(含精确码)只经 `onTerminated(sid, preciseReason)` 上送(顺带修掉旧代码把 read 错误折叠成 `CAPTURE_TERMINATED` 的信息丢失),`VoiceRuntime.onCaptureTerminated` sid 校验通过后才转交 Controller;`classifyReadError(n, running)` seam + JVM 回归测试(线程级端到端待 instrumented);② P3——提炼 `invalidateAndStopAsr()`(先作废 `expectedAsrSid` 再 `asrPort.stop()`),用于 `consumeFinal`/`onCaptureError` pipeline 分支/`cleanupAndIdle`(follow-up8 的"stop 后作废"描述自此被取代);FakeAsrPort 加 `syncErrorOnStop`,补"stop() 内同步 onError 不误杀 SPEAKING"测试;③ P3——`releaseUnpublished` 对未创建的 AudioRecord 判空,权限撤销等早期失败不再留伪 release 错误日志。全量验证通过。

**follow-up10 评审修复（2026-08-15，1 P2）**：采音循环核心抽成可 JVM 测试的 seam `driveFrames(reader, dispatcher, running)`(纯决策,零 android 依赖;captureLoop 退化为 AudioRecord reader + 端口路由 dispatcher 的薄包装,detector 跨帧 prev 状态用数组闭包保持 loop 局部维持多会话隔离)。P2:read() 返回后立刻复查 running——stop() 恰好发生在 read 返回正帧之后时该帧不再送入 KWS/ASR(Controller 取消和端口 stop 可能已并发执行);分发 catch 先查 `!running`,停止中端口抛异常按 STOPPED 不计 `CAPTURE_PIPELINE_ERROR`。`decideFrameAction` 把 `!running` 提为第一判定(负码/空读/正帧统一)。测试重写为直接驱动真实循环核心(7 用例):stop 后正帧不分发、停止中分发异常计 STOPPED 等。全量验证通过。

**follow-up11 评审修复（2026-08-15，2 P3）**：① 正常停止留下误导性异常日志——reader/dispatcher lambda 在抛出点预判打"权限被撤销/PCM 分发异常",停止竞态下后续被正确归类 STOPPED 但日志已错打;修复:删两处预判日志,异常暂存(`thrown` 数组闭包),driveFrames 返回后仅非空 errorReason 才统一打(带暂存异常堆栈,保留可诊断性);② `read()` 的非 SecurityException RuntimeException 原先跳出循环被吞成 STOPPED(错误统计和自动恢复失真)——driveFrames 补 RuntimeException catch:运行中报 `CAPTURE_READ_FAILED`,停止竞态仍归 STOPPED;补两条单测覆盖。全量验证通过。

**follow-up12 评审修复（2026-08-15，1 P2）**：采音异常后可能在端口清理完成前重启采音——`onCaptureTerminated` 里 `c.onCaptureError(reason)` 只是投递到 stateExecutor,同方法已排 500ms 固定延迟重试;stateExecutor 积压时新 AudioRecord 先向旧(刚发生 CAPTURE_PIPELINE_ERROR)端口喂音频,恢复再次失败误触熔断。修复:`VoiceSessionController.onCaptureError(code, onCleanedUp)` 带清理完成回调(任务体 finally 回调,closed/提交被拒同步回调防悬挂);Runtime 故障预算先算(熔断语义不变),重试只挂回调,回调内 `lifecycleLock` 复查前台/未销毁/未恢复(`captureStarted`/`captureSessionId != sid`);熔断与后台路径只收敛状态不排重试。回归测试:单线程 stateExecutor 用 gate 任务制造积压,断言积压期间回调不触发(wake.startCount=0),放行后回调触发时 KWS 已重建(startCount=2,即 capture.start() 不会被排在清理前)。全量验证通过。

**follow-up13 评审修复（2026-08-16，1 P1(git) + 3 P2 + 2 P3）**：① P1 暂存区不可提交——29 个语音核心/测试文件未跟踪、暂存版 MainActivity 仍引用已移入 debug 的 VoiceDebugFragment;`git add` 全量后经 `git diff --cached --name-status` 与全量构建校验暂存内容,提交前列清单确认。② P2 恢复屏障——异常清理排队期间 pause→resume 仍可 `cap.start()`:引入 `captureRecoveryPending/Failed` + 统一判定 `captureStartAllowed`(resume/busy 重试/清理回调/STOPPED 恢复共用,JVM 表驱动测试),提交清理前置 pending,仅清理成功回调清除并复查后排重试;清理失败置 failed 停自动恢复提示重进页面。③ P2 清理回调成败——`onCaptureError(code, Consumer<Boolean>)`:`handleCaptureError` 正常返回才 success=true(清理路径抛异常捕获记类型,回调 false),FakeAsrPort `failOnStop` 测试端口 stop 抛异常回调 false 且状态不收敛。④ P2 busy 无重试——初次装配与 resume 的 `capture.start()==-1` 安排 `retryCapture(0)` 有界退避(页面保持前台也能恢复);`SecurityException` 单独 catch 提示授权后重进、不盲目重试,回滚逻辑提炼 `rollbackPublish`。⑤ P2 仪器测试——VoskRecognizeTest 英文唤醒加计数器断言静音不唤醒;新增 `VoskModelProvisionedGateTest`(模型缺失专项门禁,失败不 skip)、`VoiceCaptureInstrumentedTest`(启停循环/sid 递增/IDLE 真实 PCM 路由/权限撤销 fail-fast)、`VoiceLifecycleInstrumentedTest`(TTS 初始化契约/迟到回调不上抛/AudioFocus 抢占通知);均编译通过,待真机执行。⑥ P3 日志脱敏——Runtime 启动/装配/恢复采音失败的 UI 状态只输出稳定文案+异常类型(原始 message 可能含模型绝对路径;Logcat 带堆栈仅 debug 源集=受控环境)。⑦ P3 文档一致——`VoiceCapturePort` 抽接口明确标注"阶段 2 未实施(延期),量产随 OEM 链路再抽";2F 指标维持"待实现"标注。全量验证通过。

**follow-up14 评审修复（2026-08-16，3 P2）**：① P2 KWS 旧音频迟到唤醒竞态——`VoskWakeAdapter.feed` 在 native 识别后释放锁再解析回调,期间 Controller 因采音错误 stop+start 重建,旧 recognizer 的唤醒仍触发新会话且无 sid 可丢弃。修复:`WakeWordPort` 引入唤醒代次(epoch)——`start()` 递增并经 `WakeStartResult` 返回,`stop()` 作废递增,`onWake(epoch)/onError(code,epoch)` 携带产出代次;Adapter 锁内快照代次、派发前校验未变(锁外解析窗口覆盖),Controller 校验 `epoch == expectedWakeEpoch` 双重丢弃;`manualWake` 提炼 `acceptWake` 绕过代次(用户触发无 recognizer)。JVM 回归:旧代次唤醒不进 LISTENING(含阳性对照)、旧代次 onError 不排重建/不打指标;VoskWakeAdapter 锁外派发窗口的代次校验需真机(native)验证。② P2 仪器测试与常规任务隔离——门禁/硬件依赖测试移入 `com.matrix.agent.voicecert` 包,常规 `connectedDebugAndroidTest` 中经 `VoiceCertification.enabled()`(instrumentation 参数 voiceCert)Assume 跳过保持可靠;新增 Gradle task `connectedVoiceCertificationAndroidTest`(包过滤 + voiceCert=true,失败即红);模型预置文档改为应用内下载(Demo 下载器)或 run-as 导入(非 root 可行),废弃直推 /data/data。③ P2 Runtime 恢复路径测试——抽最小接口 `core/voice/VoiceCapturePort`(start/stop/TerminationListener,测试假件消费者驱动),Runtime 依赖接口 + `publishForTest` 注入钩子;`VoiceRuntimeTest` 端到端四+一条:pending 期间 pause→resume 不得 start(放行清理后恢复)、清理成功后恰好重启一次、清理失败(failOnStop)不自动重试且 resume 被屏障、busy 重试 3 次有界耗尽报失败、pause 取消 busy 重试链;真 executor/scheduler,真实退避延迟。全量验证通过。

**follow-up15 评审修复（2026-08-16，1 P0 + 1 P2 + 1 P3）**：① P0(follow-up14 引入)——真实 KWS 永远不被接受:`VoskWakeAdapter.start()` 返回 `++epochSeq` 但未写回 `epoch` 字段,feed 快照的 entryEpoch 恒旧值,onWake 携带旧代次被 Controller 当旧 recognizer 丢弃(fake 写对了故 JVM 未暴露,静音测试不触发 onWake 也发现不了)。修复:start() 锁内 `epoch = ++epochSeq` 后返回。补认证级正向 KWS 测试 `englishWake_positiveSample_firesWithStartEpoch`:assets 放置 "hey matrix" 16k mono pcm 样本即激活(缺样本 skip),断言真实样本触发唤醒且携带 epoch == start() 返回值——能暴露此类 P0。② P2 认证任务缺实际 Vosk 验证——`VoskNativeLoadTest`(.so 加载)与 `VoskRecognizeTest`(Model/Recognizer smoke)原留在默认包,认证任务包过滤不含它们;移入 voicecert 并接 `VoiceCertification.enabled()`,VoskRecognizeTest 文档统一引用 VoiceCertification 预置流程(删 /data/data 直推残留)。③ P3 VoiceRuntimeTest 夹具线程泄漏——stateExec(非 daemon)与 controllerScheduler 由夹具自建,runtime.shutdown 只关 Runtime 自己的 executor;close() 改为 shutdown 后 graceful awaitTermination(2s) 超时强停,controllerScheduler shutdownNow。全量验证通过。

**follow-up16 评审修复（2026-08-16，1 P2）**：正向 KWS 门禁样本缺位即跳过、防不住回归。① 样本入库——`app/src/androidTest/assets/hey-matrix-16k-mono.pcm`(16kHz/mono/16bit LE,2.8s):macOS `say` TTS(Samantha)合成 "hey matrix" 常速+慢速两段、400ms 静音间隔拼接,来源与再生成命令见 `assets/README.md`(可追溯,替换真人声样本保持同名同格式即可);build.gradle 显式声明 androidTest `assets.srcDirs` 并验证样本进入测试 APK。② 认证模式零跳过——正向用例只保留首个 `VoiceCertification.enabled()` Assume 作常规任务隔离,其后模型未预置/样本缺失/样本为空一律 assert 失败;样本读取改 instrumentation context(测试 APK assets,非被测应用 context)。至此认证任务完整覆盖:.so 加载 → 模型预置门禁 → Model/Recognizer smoke → 静音不唤醒(负向)→ 真实语音唤醒 + epoch 一致(正向)。全量验证通过。

实现内容：

1. 将语音资源装配从 `VoiceDebugViewModel` 收敛到可懒创建的 `VoiceRuntime`(已实施:ViewModel 降为 observer + 生命周期触发,VoiceRuntime 统一持有资源)。
2. 模型清单增加 model id、版本、URL、大小和 SHA-256。
3. 下载到版本目录，校验完成后原子切换 active 版本；旧版本至少保留一个用于回滚。
4. marker 校验只作结构检查，不能替代哈希。
5. 模型加载失败时回退到上一个有效版本，不删除正在使用的模型。
6. 权限撤销、低存储、下载中断、Activity 重建和进程重启都有稳定状态。

验收：

- 篡改 zip 或 hash 不匹配时绝不加载。
- 模型切换失败仍能使用旧版本。
- `assembleRelease` 中只包含产品选择的语音入口和权限。
- 无可达入口时 release 不主动加载模型或打开麦克风。

### 5.7 工作包 2F：指标与测试（P1）

新增 `VoiceMetricsPort`，仅记录：

- wake 计数、拒绝原因和 source；
- 状态迁移和每段耗时；
- endpoint、ASR final、Agent、TTS、barge-in 延迟；
- error code、timeout 类型、cancel 阶段；
- 模型版本、引擎类型、设备档位；
- CPU/RAM/线程数/连续会话资源差值。

阶段 2 测试增量：

- `VoiceSessionControllerTest`：endpoint/final 乱序、所有 timeout、generation、focus loss、TTS ready。
- `AgentRuntimeRepository`：VOICE 元数据贯通和 TOUCH 兼容。
- `VoiceCaptureController` 仪器测试：start/stop/pause/resume、权限撤销、AudioRecord 初始化失败。
- `AndroidTtsAdapter` 仪器测试：init、focus、stop、迟到 callback。
- 模型下载测试：hash 错误、版本回滚、Range 不支持、磁盘不足和原子切换。

### 5.8 阶段 2 完成定义

- [ ] 阶段 1 真机基线已记录。
- [x] 语音请求在 Agent 中可被准确识别为 `InputSource.VOICE`。
- [x] endpoint、final、超时和一次复述形成确定状态机。
- [x] AudioFocus、TTS ready 和 focus loss 路径完整。
- [ ] 播报期打断达到目标时延且不会被自身 TTS 高频误触发。
- [x] 模型有 SHA-256、版本和可回滚切换。
- [ ] 连续 100 次前台会话无资源泄漏、native crash 和卡态。
- [ ] JVM、instrumented、Debug/Release 构建全部通过。

---

## 6. 阶段 3：真实免按键唤醒

### 6.1 目标与边界

阶段 3 解决“用户不按按钮也能唤醒”的入口问题，但不改变 Agent Runtime 的单进程架构。

优先级如下：

1. **OEM DSP / OEM 语音前端（首选）**：低功耗、回声和座舱适配最好，MatrixAgent 只接可信 WakeEvent。
2. **系统 VoiceInteractionService**：目标镜像允许 MatrixAgent 成为当前语音助手时使用；Service 与 Agent 保持同一应用进程，调用同一个 `AppContainer`。
3. **前台 microphone FGS + 软件 KWS**：只用于 eng/debug 设备验证，不作为普通应用量产常驻方案。

`VoiceInteractionService` 是 Android 的语音入口生命周期组件，不等于被取消的无头 Agent Service。当前方案不提供 Runtime AIDL，也不创建 `:agent`；如果系统组件被选用，只把 WakeEvent 转交应用内 `VoiceRuntime`。

### 6.2 入口适配架构

~~~text
OEM DSP callback / System VoiceInteraction callback
  → VoiceEntryAdapter
  → WakeEvent {
       source,
       eventId,
       elapsedRealtime,
       optional audioZoneId,
       optional preRollHandle
     }
  → VoiceEntryCoordinator
       ├─ 来源校验
       ├─ 去重与冷却
       ├─ 当前会话仲裁
       └─ 固定 Actor.DRIVER / 默认驾驶音区
  → Lazy VoiceRuntime
  → VoiceSessionController
  → 会话期打开 AudioRecord → VAD/ASR → Agent → TTS
~~~

### 6.3 实现要求

1. OEM 接口必须是签名权限、系统绑定或厂商 SDK 的可信回调，不接收普通隐式广播。
2. `eventId + source + elapsedRealtime` 去重，防止同一 wake 多次创建会话。
3. 已在 LISTENING/THINKING/CONFIRMING/SPEAKING 时，根据产品策略忽略、打断或排队，首版采用“单活跃会话，重复 wake 忽略”。
4. 系统/OEM Wake 模式下，MatrixAgent 在 IDLE 不常驻 `AudioRecord`；收到 wake 后才采集会话音频。
5. 若 OEM 提供 pre-roll，只在当前会话内消费并立即释放，不持久化。
6. Application/Container/VoiceRuntime 必须懒加载或分层加载，避免系统常驻入口启动时立即加载 LLM、Room、Vosk 全部重资源。
7. 失去默认语音助手身份、权限被撤销或 OEM 服务死亡时进入 `STOPPED`，不回退成后台软件录音。
8. 阶段 3 仍固定 `Actor.DRIVER`。OEM 未提供可靠输入座位归属时，忽略外部声称的 passenger 字段。

### 6.4 前台服务约束

如果目标设备需要会话期 microphone FGS：

- Manifest 声明 `FOREGROUND_SERVICE_MICROPHONE`、`RECORD_AUDIO` 和 `foregroundServiceType="microphone"`。
- 只在已接受的 WakeEvent/可见页面触发后启动，并立即显示通知。
- IDLE、ASR 完成、取消、超时和错误后立即停止采音与 FGS。
- targetSdk 36 下必须在目标 Android/AAOS 镜像验证 while-in-use 权限和后台启动限制。
- 不允许从 `BOOT_COMPLETED` 直接开始常驻录音。

### 6.5 发布范围

阶段 4 未完成前，阶段 3 的产品语音能力只允许：

- 查询类能力；
- 知识问答；
- 经产品与安全评审批准、无需确认的低风险能力。

导航、记忆写入、影响驾驶体验的车控等能力按 Capability allowlist 控制。任何标记为“需要确认”的操作直接拒绝，不允许先执行后补问。

### 6.6 阶段 3 完成定义

- [ ] 选定并实现至少一个 OEM/System 真实 wake 入口。
- [ ] 不打开 UI、不按按钮也能在目标镜像进入 LISTENING。
- [ ] IDLE 时 MatrixAgent 不通过普通 `AudioRecord` 常驻监听。
- [ ] 入口重复、服务重连、权限撤销和进程重建不创建双会话。
- [ ] 冷/热启动各连续 100 次 wake，会话均能回收。
- [ ] Release 中无 Debug 入口、测试广播和手动唤醒后门。
- [ ] 未完成确认能力时，高风险 capability 无法从语音触达 Provider。

---

## 7. 阶段 4：执行前语音确认

### 7.1 为什么必须改 Agent 执行边界

当前 `VerifyStrategy` 的调用时机是 Provider 已执行之后，用于回读验证。`VerifyMethod.USER_CONFIRM` 不能直接复用为执行前确认，否则会出现“操作已经下发，才询问用户是否确认”的严重语义错误。

阶段 4 必须新增独立的执行前审批概念，例如：

~~~text
ConfirmationRequirement: NONE | USER_CONFIRM
~~~

它与执行后的 `VerifyMethod.NONE/READBACK_FIELD/READBACK_GET` 正交：

~~~text
Policy ALLOW
  → ConfirmationRequirement 检查
  → 若需确认：冻结 ToolCall，等待确认
  → 确认成功后才调用 ToolExecutor/Provider
  → Provider 完成后继续 VerifyMethod 回读
~~~

### 7.2 确认数据模型

~~~text
PendingConfirmation {
  requestId
  voiceSessionId
  confirmationToken       // 随机、单次、不可预测
  capabilityName
  frozenArgumentsHash     // 防止确认后参数被换
  speakableSummary        // 白名单生成，不含敏感原始参数
  expiresAtElapsedRealtime
}
~~~

确认摘要由 capability 专属模板生成，禁止让 LLM 自由改写关键参数。示例：“要将主驾温度调整为二十四度吗？”

### 7.3 状态与线程设计

1. Agent 在 policy 通过、Provider 执行前调用 `ConfirmationGate.awaitDecision()`。
2. Gate 发布 `PendingConfirmation` 给 `VoiceSessionController`；Agent 继续占用独立 `agentExecutor` 等待，不能阻塞 `stateExecutor`。
3. Controller 从 THINKING 进入 CONFIRMING，先播报摘要。
4. TTS 完成后启动确认 ASR，使用受限 grammar：确认/是/继续、取消/否/不要。
5. `confirmationToken + voiceSessionId + frozenArgumentsHash` 全部匹配且未过期，才放行原 ToolCall。
6. 否定、空文本、低置信度、其它表达、focus loss、cancel、进程退出或 8 秒超时均拒绝。
7. token 使用一次后立即失效；迟到“确认”不能放行下一次请求。

### 7.4 确认期间采音

`VoiceCaptureController` 在 CONFIRMING 状态不能继续走普通自由 ASR：

- TTS 播摘要时仍按打断策略处理；
- TTS done 后切换到独立 `ConfirmationAsrPort` 或同一 ASR 的受限 grammar 模式；
- 只接受固定确认/否定意图，不把任意句子提交给 Agent；
- 用户说了新任务时，首版按“取消本次待确认操作，再重新唤醒”处理，避免语义混合。

### 7.5 阶段 4 完成定义

- [ ] Provider 在确认成功前调用次数严格为 0。
- [ ] 参数、capability、session 或 token 任一变化都拒绝。
- [ ] 否定、超时、低置信度、ASR/TTS error、focus loss、进程退出全部 fail closed。
- [ ] 同一 token 重放无效，旧会话确认不能影响新会话。
- [ ] 写操作下发后的取消仍按回读/`EXECUTION_UNKNOWN` 处理。
- [ ] 高风险 capability 只有通过确认测试矩阵后才能加入语音 allowlist。

---

## 8. 阶段 5：端侧极致体验

### 8.1 目标

在阶段 2 已有指标基线和阶段 3 真实入口之上，逐项优化：

- 更低功耗、更低误唤醒的专用 KWS；
- 对车内噪声更鲁棒的 VAD/AEC/NS；
- 更低 endpoint-to-final 延迟的本地流式 ASR；
- 无网络可用的中文本地 TTS；
- 更可靠的全双工打断；
- 在安全过滤后的 Agent 流式短句播报。

### 8.2 引擎策略

不直接把 Vosk 全部推倒，也不提前锁死某个引擎。采用端口级 A/B：

| 能力 | 当前基线 | 候选方向 | 决策指标 |
|---|---|---|---|
| KWS | Vosk 英文 grammar | OEM DSP；或 sherpa-onnx 等专用 KWS | FAR、FRR、常驻 CPU/RAM/功耗、唤醒延迟 |
| VAD | Vosk endpoint | OEM VAD；Silero/sherpa-onnx 等模型 VAD | 起止点准确率、噪声鲁棒性、算力 |
| ASR | Vosk 中文 small | OEM 离线 ASR；sherpa-onnx 流式模型等 | CER/WER、RTF、内存、首 partial/final 延迟 |
| TTS | Android TTS | OEM TTS；sherpa-onnx 支持的本地中文模型等 | 首包延迟、RTF、音质、包体、内存 |

Vosk 继续作为可运行 fallback，直到新引擎在目标 SoC 和真实座舱语料上全量胜出。引擎替换不能改变 Controller、AgentRunner 或 ResponsePresenter 契约。

### 8.3 模型运行时

新增统一 `VoiceModelManager`：

- model manifest：id、engine、version、ABI、语言、量化、size、SHA-256、最低内存；
- 下载、校验、版本目录、active 指针、上一个版本回滚；
- KWS/VAD/ASR/TTS 分模型独立启停，不要求一次全部加载；
- 内存压力下先卸载空闲 TTS/ASR，不能卸载正在使用的 session model；
- 记录模型版本和性能指标，但不记录语音内容；
- 升级失败不影响 Agent Runtime 和旧语音引擎。

### 8.4 流式 Agent 与 TTS

当前 `ModelGateway`/`AgentOutcome` 以终态播报为主。流式播报只能在以下约束满足后开启：

1. ModelGateway 输出片段绑定当前 `CancellationToken` 和 generation。
2. `SentenceBoundaryBuffer` 只在完整短句边界提交。
3. 每个片段经过与 `ResponsePresenter` 同等级的字段白名单和隐私过滤。
4. 工具是否执行、执行结果和确认状态不能从模型自然语言推断。
5. 用户打断时清空 TTS 队列、模型流和句子缓冲，不继续播旧内容。
6. 对车控操作，最终播报仍以 Provider/Verify 终态为准。

如果安全过滤或取消语义没有完成，继续使用当前“终态一次播报”，不为追求快而播报未经确认的内容。

### 8.5 阶段 5 完成定义

- [ ] 在固定测试语料上完成 KWS/VAD/ASR/TTS 基线和候选引擎对比。
- [ ] 选型由目标 SoC 的实测数据决定，不以桌面或手机结果替代。
- [ ] 无网络时按产品声明完成 wake、ASR、Agent、本地能力和 TTS，或给出精确降级。
- [ ] 全双工打断在回声、音乐和多人说话场景达到门槛。
- [ ] 模型升级、失败和回滚不破坏已有 Vosk fallback。
- [ ] CPU、RAM、温升和功耗满足 OEM 预算。

---

## 9. 阶段 6：AAOS 产品化

### 9.1 当前可承诺范围

在不做无头服务和可信多用户身份体系的前提下，首个产品范围应明确为：

- 单应用进程；
- 当前 Android user；
- 固定 `Actor.DRIVER`；
- 默认主驾驶音区；
- 单活跃 VoiceSession；
- OEM/System 提供真实唤醒入口；
- 经过 allowlist 和确认策略开放的能力。

### 9.2 音区和多用户边界

AAOS 音频焦点和输出路由可按 audio zone 管理，但当前身份模型仍使用 demo driver/passenger 映射。只接 AudioFocus 并不能自动获得可信座位身份。

因此：

- 首版只使用 OEM 验证过的默认驾驶音区。
- 不从 ASR 文本、输出音区或模型猜测说话人座位。
- 未有麦克风阵列/波束形成归属时，不开放副驾确认或跨区控制。
- 若未来必须支持 Settings 跨应用、多 Android user、多音区身份隔离或外部调用方，再单独立项重新评估进程与信任边界；不能在当前单进程方案上悄悄补字段冒充安全设计。

### 9.3 真车测试矩阵

| 维度 | 必测场景 |
|---|---|
| 噪声 | 怠速、高速、空调最大、雨刮、开窗、音乐、导航、电话 |
| 人员 | 主驾单人、主副驾交谈、多人同时说话、儿童声音 |
| 唤醒 | 正常、远场、快速连续、相似词、媒体中包含唤醒词 |
| ASR | 查询、车控、数字、地点、连续长句、口音、方言、含糊表达 |
| 打断 | TTS 开头/中间/结尾插话、回声、音乐突变、咳嗽和碰撞声 |
| 系统 | 锁屏、用户切换、权限撤销、网络断开、低存储、模型升级、进程 kill |
| 音频 | media/navigation/call/focus loss/ducking/default zone 路由 |
| 稳定性 | 100 次连续会话、8 小时待机、长时间热态、低电量 |

### 9.4 发布与回滚

1. 总开关控制所有产品语音入口；关闭后不持有 AudioRecord、focus、TTS 和活动 session。
2. KWS、ASR、TTS、barge-in、确认和流式播报分别有 feature flag。
3. 新模型按灰度比例启用，错误率/延迟/资源超阈值自动回退旧模型。
4. OEM wake 不可用时显示“语音唤醒不可用”，不回退后台软件常驻录音。
5. 本地 TTS 不可用时可回退系统 TTS；ASR 不可用时结束会话，不提交 partial。
6. 回滚不恢复已经过期的会话、待确认动作或待播报文本。

---

## 10. 文件级改造清单

### 10.1 阶段 2 重点文件

| 文件/模块 | 改造方向 |
|---|---|
| `core/voice/AsrPort.java` | final 改为结构化 `FinalTranscript` |
| `core/voice/AgentRunner.java` | 接受 `VoiceAgentRequest` |
| `core/voice/VoiceEvent.java` | 拆分 endpoint/final/timeout/ready/focus 事件，携带 session id |
| `core/voice/VoiceSessionState.java` | 删除双 FINAL 兼容，加入确定的 timeout/重试迁移 |
| `core/identity/AgentRequest.java` | 如需支持“置信度不可用”，增加显式标志；保持 Builder 校验 |
| `data/AgentRuntimeRepository.java` | 新增请求对象重载，贯通 VOICE 元数据，旧入口兼容 |
| `data/voice/VoiceSessionController.java` | 接入 Vad/Focus/Timer/Validator，generation 丢弃迟到回调 |
| `platform/voice/VoskAsrEngine.java` | word confidence、endpoint/final 顺序契约、结构化结果 |
| `platform/voice/VoskEndpointAdapter.java` | 正式接入 Controller 或在新 VAD 上线后删除占位实现 |
| `platform/voice/VoiceCaptureController.java` | 抽采音端口、帧复用、NS、BargeInDetector |
| `platform/voice/AndroidTtsAdapter.java` | ready、AudioAttributes、唯一 utterance id、focus 生命周期 |
| `core/voice/ModelPathResolver.java` | 版本目录 + active/previous 指针 + 原子 promote + 旧布局迁移（P1-10） |
| `data/voice/VoskModelDownloader.java` | SHA-256、版本目录、原子切换、回滚、旧布局迁移 |
| `presentation/viewmodel/VoiceDebugViewModel.java` | 逐步降为 observer；资源装配迁入 lazy VoiceRuntime |
| `app/build.gradle` / Manifest | debug/product source set、权限和依赖边界 |

### 10.2 阶段 3/4 新增模块

~~~text
core/voice/
  FinalTranscript
  VoiceAgentRequest
  WakeEvent
  VoicePolicyConfig
  PendingConfirmation

data/voice/
  VoiceRuntime
  VoiceEntryCoordinator
  TranscriptValidator
  ConfirmationCoordinator
  VoiceMetrics

platform/voice/
  AndroidAudioFocusAdapter
  OemWakeAdapter              // 按目标 OEM SDK 实现
  SystemVoiceEntryAdapter     // 仅在选择系统语音入口时实现
  ModelVadAdapter             // 阶段 5
  LocalTtsAdapter             // 阶段 5
~~~

按实际入口选择新增 Android Service 组件；不提前创建空壳 Service，也不建立通用 Binder API。

---

## 11. 测试与质量门禁

### 11.1 JVM 单测

- 状态机每个合法/非法迁移和所有 timeout。
- endpoint/final/Agent/TTS 回调乱序及 generation 隔离。
- 空文本、低置信度、未知置信度、语言不支持和一次复述。
- cancel 在 LISTENING/THINKING/CONFIRMING/SPEAKING 的语义。
- focus loss、TTS not-ready、TTS error 和 Agent error 收敛。
- ResponsePresenter 所有终态、最大长度和敏感数据不泄漏。
- VOICE 元数据到 `AgentRequest` 的完整映射。
- Confirmation token、参数冻结、超时、重放和跨会话拒绝。
- 模型 hash、版本切换和回滚。

### 11.2 Android 仪器测试

- Vosk native 加载、Recognizer start/feed/stop/close。
- AudioRecord 权限、初始化失败、重复 start、stop 超时和前后台恢复。
- TTS 初始化、语言不可用、unique utterance callback、stop/shutdown。
- AudioFocus gain/loss/reject，与媒体/导航模拟交互。
- Activity 重建、ViewModel 销毁、进程重启和权限撤销。
- 若使用 FGS：通知、service type、后台启动限制和 stop 路径。
- 若使用 VoiceInteractionService：选中/取消默认助手、系统重连和 session 生命周期。

### 11.3 静态与构建门禁

- `git diff --check`。
- `testDebugUnitTest`。
- `connectedDebugAndroidTest` 或目标设备等价测试。
- `assembleDebug`、`assembleRelease`。
- release 反编译/清单检查：无 Debug Activity、测试 receiver、非预期 exported 组件。
- Logcat 扫描：无 PCM、transcript、Tool 参数、目的地、联系人、API key 和绝对模型路径。
- native/Java 线程、AudioRecord、TTS、focus owner 在会话结束后回到基线。

---

## 12. 指标与验收目标

### 12.1 指标定义

| 指标 | 起点 | 终点 |
|---|---|---|
| wake latency | OEM/KWS 产生 WakeEvent | Controller 进入 LISTENING |
| endpoint latency | 用户实际停说 | VAD endpoint |
| ASR final latency | VAD endpoint | `FinalTranscript` |
| Agent latency | submit | Agent terminal |
| TTS start latency | `SpeakableResponse` ready | 设备开始发声 |
| barge-in latency | detector 确认有效插话 | 设备输出静音 |
| session cleanup | 进入 terminal/cancel/error | mic/focus/TTS/临时缓冲全部释放 |

### 12.2 初始工程目标

以下用于阶段 2/3 工程门禁，最终值由目标车型和 OEM SLA 替换：

- wake → LISTENING：热态 p95 ≤ 300 ms。
- endpoint → ASR final：本地 ASR p95 ≤ 800 ms。
- 有效 barge-in → 静音：p95 ≤ 300 ms。
- 连续 100 次会话：0 次 native crash、0 次双 AudioRecord、0 次永久非 IDLE 卡态。
- 会话结束后 10 秒：线程、focus 和采音对象回到基线。
- KWS FAR/FRR、ASR CER/WER 不在文档中凭空设值；先建立固定座舱语料，再由产品/OEM批准阈值。

---

## 13. 实施排期与依赖

以下估算以一名熟悉当前代码的 Android 开发者、可用 arm64 设备为前提，不包含等待 OEM SDK、签名、系统镜像和真车资源的时间。

| 里程碑 | 工作内容 | 建议工期 | 依赖 |
|---|---|---:|---|
| M1：阶段 1 真机收口 | 下载/模型/完整闭环、30 次稳定性、初始指标 | 2～3 人日 | 真实设备 |
| M2：请求与状态正确性 | 2A + 2B：VOICE 元数据、VAD、timeout、confidence | 4～6 人日 | M1 可部分并行 |
| M3：音频可靠性 | 2C + 2D：Focus、TTS ready、barge-in | 4～7 人日 | M2 状态契约 |
| M4：模型/生命周期/指标 | 2E + 2F、100 次稳定性和 Release 门禁 | 4～6 人日 | M2/M3 |
| M5：真实唤醒 | OEM/System Adapter、VoiceRuntime、会话期采音 | 5～10 人日 | OEM 接口、阶段 2 |
| M6：执行前确认 | Gate、状态机、受限 ASR、Agent 执行点改造 | 5～8 人日 | M2；可与 M5 后半并行 |
| M7：端侧引擎评测 | KWS/VAD/ASR/TTS 基准、接入胜出方案 | 10～20 人日 | 阶段 2 指标、目标 SoC |
| M8：AAOS 发布收口 | 真车矩阵、功耗、灰度、回滚 | 5～10 人日以上 | OEM/真车资源 |

推荐执行顺序：

~~~text
M1
 └─ M2
     └─ M3
         └─ M4（阶段 2 完成）
             ├─ M5（真实唤醒）
             ├─ M6（安全确认）
             └─ M7（端侧极致，可先做模型离线 benchmark）
                    └─ M8（目标车型发布）
~~~

OEM 接口迟迟不可用时，不阻塞 M2/M3/M4/M6/M7 的进程内实现和测试；但 M5 与最终免按键发布不能用普通后台录音“替代完成”。

---

## 14. 风险清单

| 风险 | 触发条件 | 应对 |
|---|---|---|
| 误唤醒高 | 媒体中出现相似词、Vosk grammar 过宽 | 专用 KWS/OEM DSP、阈值和冷却、座舱负样本测试 |
| 回声导致自打断 | AEC 不可用或参考链错误 | OEM AEC、VAD+RMS 联合、半双工降级 |
| ASR 低置信度误车控 | 小模型、噪声、方言 | 真实 confidence、分级阈值、复述、确认、allowlist |
| TTS 与导航/电话冲突 | 无焦点或错误 AudioAttributes | Stage 2 AudioFocus、focus loss fail closed |
| 模型损坏 | 下载中断、源文件变化、磁盘错误 | SHA-256、版本目录、原子切换、旧版本回滚 |
| native 崩溃 | recognizer 与 close 竞争、模型生命周期错误 | 现有 native lock + generation + 关闭顺序 + 压测 |
| 取消后命令仍执行 | Provider 命令已下发不可撤销 | abort 能力、回读、`EXECUTION_UNKNOWN` 保守播报 |
| 系统入口不可用 | 未选中默认助手、OEM 无接口 | 清晰提示，不后台录音降级；保留前台 Debug 模式 |
| 单进程启动过重 | 系统语音入口拉起 Application 即构造全容器 | lazy VoiceRuntime/模型；必要时再优化 AppContainer 懒加载 |
| 产品范围膨胀 | 未改身份模型就要求副驾/多用户 | 明确阻断，单独立项设计可信身份和音区归属 |

---

## 15. 数据安全与隐私

| 数据 | 默认处理 |
|---|---|
| 唤醒前音频 | OEM/DSP 路径由系统策略处理；软件 KWS 仅 Debug/前台内存消费，不持久化 |
| 会话 PCM | 仅当前 Session 内存处理，结束立即释放，不写文件/数据库/Logcat |
| ASR partial | 只用于当前 UI，不写 Audit，不提交 Agent |
| ASR final | 作为本次 Agent 输入，遵守现有 SafeLog/Audit 脱敏和用户数据规则 |
| TTS 文本 | 只来自 `SpeakableResponse`；常规指标只记字符数和状态码 |
| 指标 | 只记聚合耗时、状态、错误码和引擎/模型版本 |
| 诊断音频 | 默认禁止；如未来开启，需独立同意、加密、保留期限和删除机制 |

任何 ASR 文本都不能声明 actor、audio zone、capability 名或强制工具参数。当前身份由应用内产品配置固定为 DRIVER；未来接 OEM zone 时也必须来自可信系统/OEM上下文，而不是转写内容。

---

## 16. 外部约束与技术参考

- Vosk Android 提供离线语音识别 Demo；当前 grammar 唤醒是软件关键词基线，不等价于低功耗 DSP KWS：[Vosk Android](https://alphacephei.com/vosk/android)。
- Android `VoiceInteractionService` 是当前全局语音交互器入口，系统会保持它运行，因此实现必须轻量；本项目只有在目标镜像允许并选中该入口时才使用：[VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService.html)。
- microphone FGS 需要 `FOREGROUND_SERVICE_MICROPHONE` 和 `RECORD_AUDIO`，并受 while-in-use 与后台启动限制：[Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types)、[后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)。
- AAOS 播放前应使用与实际流相同的 AudioAttributes 请求焦点，并正确处理 focus loss：[AAOS Audio focus](https://source.android.com/docs/automotive/audio/audio-focus)。
- AAOS 多音区的输出、焦点和音量相互独立；当前方案未完成可信输入座位归属，因此首版只承诺默认驾驶音区：[AAOS Multi-zone audio routing](https://source.android.com/docs/automotive/audio/audio-multizone-routing)。
- sherpa-onnx 提供 Android、本地实时 ASR、开放词表 KWS 和 TTS 能力，可作为阶段 5 候选，但必须在目标 SoC 上与 Vosk/OEM 方案实测后决定：[sherpa-onnx Android](https://k2-fsa.github.io/sherpa/onnx/android/index.html)、[KWS](https://k2-fsa.github.io/sherpa/onnx/kws/index.html)、[TTS](https://k2-fsa.github.io/sherpa/onnx/tts/index.html)。

---

## 17. 当前下一步

当前不应直接跳到“换更大的 ASR/TTS 模型”。正确顺序是：

1. 用真机完成阶段 1 闭环和基线数据。
2. 优先实施阶段 2A/2B，修正 `VOICE` 元数据、confidence、VAD 和 timeout。
3. 接着实施阶段 2C/2D，补 AudioFocus、TTS ready 和可靠打断。
4. 完成模型校验、指标和 100 次稳定性后关闭阶段 2。
5. 同时向 OEM/系统确认真实 wake 接口；接口确定后进入阶段 3。
6. 阶段 4 完成前，真实语音入口只开放只读和批准的低风险能力。
7. 有指标基线后再进行阶段 5 引擎选型，避免只凭模型宣传做架构决策。

这一顺序保持现有代码投资有效：阶段 1 的状态机、端口、取消、播报和 Vosk fallback 都继续复用；取消无头服务不会阻塞语音后续，只是把产品范围明确收敛为单进程、单用户、默认驾驶音区，并把真实免按键入口交给 OEM/System 能力。
