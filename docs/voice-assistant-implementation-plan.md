# MatrixAgent 车载语音助手分阶段实施设计

> 状态：实施设计。
> 范围：真实语音唤醒、ASR、TTS、语音确认、打断播报及其与 MatrixAgent Runtime 的集成。
> 前置条件：分轨道——**Demo 轨道**无前置，进程内直连可立即开工；**量产轨道**须先完成 [无头服务化与 Settings 集成设计](agent-service-settings-migration.md) 中的唯一 Runtime、可信身份、AIDL 和多用户隔离基础。

---

## 1. 目标与结论

MatrixAgent 的语音能力分两条独立轨道，目的是让可演示的闭环立刻开工，同时不为 demo 引入只有在量产才用得上的重型前置（无头服务化、跨进程可信身份、OEM 唤醒）。

### 1.1 两条轨道

| 轨道 | 进程模型 | 语音入口 | 身份 | 目标 |
|---|---|---|---|---|
| Demonstration（Demo V1） | 进程内单进程，不导出、不跨进程 | 前台 Activity 或受控 microphone FGS + Vosk 软件关键词检测 | 固定 `Actor.DRIVER`，进程内常量，非安全边界 | 在真实设备上跑通“唤醒 → ASR → Agent → 安全播报 → 打断”闭环，验证 `VoiceSessionController`、`ResponsePresenter`、`CancellationToken` 取消语义。 |
| 量产（V2 / V3） | `:voice_trigger` + `:agent` 跨进程 | OEM DSP / SoundTrigger / 系统 VoiceInteractionService 真实唤醒词 | Binder UID 解析的 IdentityScope，多用户多音区 | 可靠唤醒、识别、播报、确认和多音区打断，达到车机产品化标准。 |

两条轨道共享同一套 `VoiceSessionController` 状态机和 `ResponsePresenter` 安全播报规则；区别只在语音入口、身份边界和是否走无头服务化。Demo 轨道**不是** V2 的未完成版，它是一条独立的、进程内的可验证路径；量产轨道在它之上替换入口实现、加入服务化与可信身份。

### 1.2 目标交互（两轨共同）

~~~text
唤醒（Demo: Vosk 关键词 / 量产: DSP 唤醒词）
  → 录音与端点检测（Demo: VoskEndpointAdapter / 量产: VAD）
  → 流式 ASR（Demo: Vosk / 量产: OEM 或端侧引擎）
  → VoiceSessionController → AgentRuntimeRepository
  → ResponsePresenter 生成安全的 SpeakableResponse
  → TTS

用户插话（barge-in）：
  任意 TTS 播报中检测到有效语音
  → 立即停止 TTS
  → 先 token.cancel() 取消当前请求，再视需要中断 Future
  → 回到端点 / ASR，作为新请求处理
~~~

### 1.3 量产版本细分

在量产轨道内再分两个能力版本：

| 版本 | 语音入口 | 目标 |
|---|---|---|
| V2：车机语音助手（首个量产入口） | OEM DSP/SoundTrigger 或系统 VoiceInteractionService 的真实唤醒词 | 可靠的唤醒、识别、播报、确认和单音区打断。 |
| V3：端侧极致体验 | 在 V2 系统入口上增强，必要时接经 OEM 授权的软件 KWS | 本地 ASR、低延迟、全双工打断、多音区和故障降级达到产品化标准。 |

### 1.4 Demo 不碰的东西

Demo V1 明确不做，全部留给量产轨道：`AgentVoiceFacade`、`IdentityScope`、服务端注入 `requestId`/`principalUserId`、跨进程 Binder 身份解析、`ConfirmationGate`、多用户/多音区、OEM DSP/VoiceInteractionService 常驻唤醒。遇到 `VerifyMethod.USER_CONFIRM` 能力，Demo 直接拒绝/不开放，**绝不**“先执行再等用户回答”。

---

## 2. 当前工程基线与关键缺口

### 2.1 已有可复用能力

| 当前实现 | 可复用价值 | 语音接入方式 |
|---|---|---|
| core.identity.InputSource 已有 VOICE | 请求已能声明语音来源 | VoiceSessionController 创建请求时固定传 VOICE。 |
| AgentRequest 已有 asrConfidence、languageTag、audioZoneId | 可传递识别质量和音区元数据 | 扩展为由可信语音层写入，业务调用方不得伪造。 |
| CancellationToken、ModelGateway cancel、Tool abort | 用户打断可取消 LLM 与可中止工具 | TTS 打断与“停止”语义映射为按 requestId 取消。 |
| SteerMailbox / REPROMPT / DEFER | 支持任务运行中追加受控指令 | 仅把明确的“停止/等等/补充一句”转成 typed command；不把任意 ASR 文本当 FORCE_TOOL。 |
| PolicyEngine、VehicleState、CapabilityDefinition | 已有安全校验和驾驶状态约束 | 语音不绕过 Policy；低置信度和确认不通过均 fail closed。 |
| VerifyMethod.USER_CONFIRM 占位 | 识别到需要人确认的能力方向 | 需要补全为独立的确认状态机，不能直接把它当成执行前放行。 |
| AgentOutcome、AuditRepository | 有终态与审计数据 | 新增安全的 SpeakableResponse，不能复用完整 Trajectory。 |
| OnDeviceModelGateway 的每调用取消 | 端侧 Agent 推理可被打断 | V2/V3 的 barge-in 可以安全取消当前 LLM 调用。 |

### 2.2 当前缺失的能力

当前 app 模块没有 AudioRecord、音频焦点、VoiceInteractionService、SoundTrigger、ASR、TTS、VAD 或音频会话实现；现有 AgentTestViewModel 只是文本 Debug UI。

特别需要避免以下错误：

1. 不能把 AgentTestViewModel.render() 的完整 Trajectory 直接送给 TTS。它面向调试，含 Tool 参数、审计视图和内部迭代信息。
2. 不能让普通 App 用永久 AudioRecord 循环模拟“常驻唤醒”——这指的是**量产路径**。在 targetSdk 36 上，麦克风前台服务和 while-in-use 权限有严格后台启动限制；真正常驻入口必须由系统 VoiceInteractionService 或 OEM 音频/唤醒能力支撑。Demo 轨道允许在前台 Activity 直接 AudioRecord，或从可见页面启动 Debug-only microphone FGS 短时采音，但不得进入量产常驻路径。
3. 不能把 wake word、ASR、TTS 实现塞进 AgentEngine。AgentEngine 保持领域逻辑；Android 音频和系统权限属于 platform/voice 层。
4. 不能由 ASR 文本声明 userId、zone、actor、工具名或强制工具参数。身份和高风险动作都必须走服务端可信边界。

---

## 3. 车机级总体架构（量产轨道）

> Demo 轨道是进程内单进程，无 `:voice_trigger` / `:agent` 拆分，架构见 6.2。本节描述的是量产轨道的进程与端口设计。

### 3.1 进程与职责

系统选中的 VoiceInteractionService 应保持轻量，重的会话和 Agent 处理放到独立进程。最终进程布局如下：

~~~text
┌──────────── :voice_trigger（轻量、由系统常驻）────────────┐
│ MatrixVoiceInteractionService                              │
│   ├─ OEM Hotword Adapter / 系统触发回调                    │
│   ├─ WakeEvent 校验与最小化路由                            │
│   └─ 仅通知 :agent 启动会话；不持有 AppContainer、不跑 ASR │
└──────────────────────────┬────────────────────────────────┘
                           │ 受保护的内部 Binder / 显式组件
                           ▼
┌──────────────────── :agent（按 Android user 隔离）─────────┐
│ AgentRuntimeHost → 唯一 AppContainer                         │
│ MatrixAgentService（Settings/受信任调用方 AIDL）             │
│ VoiceInteractionSessionService / VoiceSessionController     │
│   ├─ AudioFocusController / CarAudioZoneRouter               │
│   ├─ VoiceCaptureController（活动会话才采音）                │
│   ├─ VadEngine → AsrEngine                                   │
│   ├─ AgentVoiceFacade → AgentRuntimeRepository               │
│   ├─ ConfirmationCoordinator                                 │
│   ├─ ResponsePresenter → TtsController                       │
│   └─ VoiceMetrics / RuntimeEventDispatcher                   │
│ DownloadCoordinator / 模型、配置、Memory、Audit              │
└─────────────────────────────────────────────────────────────┘
~~~

说明：

- MatrixVoiceInteractionService 只有在 MatrixAgent 被选为当前全局语音助手，并获得 OEM/系统镜像允许时才承担持续唤醒入口。它要求 android.permission.BIND_VOICE_INTERACTION，且不应加载模型或创建 AppContainer。
- VoiceInteractionSessionService 可以运行在 :agent，和 MatrixAgentService 复用 AgentRuntimeHost；这样每个 Android user 仍只有一套 Agent 状态。
- 若 OEM 已提供独立唤醒/语音前端，V2 用 OemWakeAdapter 接收其可信 wake event，而不是争抢麦克风。
- 若 VoiceInteractionService 不可用或用户未将 MatrixAgent 选为助手，Settings 仅显示“语音入口未启用”；不得退化为后台永久录音。

### 3.2 领域端口

新增接口，先定义端口后选择 OEM 或开源实现：

| 接口 | 输入/输出 | 责任 |
|---|---|---|
| WakeWordPort | WakeEvent | 接系统/DSP 唤醒事件，绝不接收任意 App 广播。 |
| VoiceCapturePort | PCM 帧、采集错误 | 仅会话期打开麦克风，选择正确输入设备/音区。 |
| VadPort | PCM 帧 → SPEECH/QUIET/ENDPOINT | 端点检测和静音超时。 |
| AsrPort | PCM 帧 → PartialTranscript/FinalTranscript | 流式识别、置信度、语言和错误。 |
| AudioFocusPort | request/release/loss 回调 | 音区内请求/释放焦点，处理 duck、loss。 |
| TtsPort | SpeakableResponse → started/done/error | 播报、停止、音区输出和队列管理。 |
| AgentVoicePort | VoiceInvocation → VoiceAgentResult | 只负责提交、取消、确认、受控转向，不暴露 AppContainer。 |
| VoiceMetricsPort | 无敏感事件 | 延迟、错误码、状态转换与性能指标。 |

VoiceSessionController 只依赖以上端口和纯 Java 的 SessionState；Android API、厂商 SDK、MNN/ASR native binding 均放在 platform/voice 实现层。

### 3.3 建议目录

~~~text
core/voice/
  VoiceSessionState, VoiceEvent, WakeEvent, Transcript,
  SpeakableResponse, ConfirmationRequest, VoicePolicy

data/voice/
  AgentVoiceFacade, ResponsePresenter, ConfirmationCoordinator,
  VoiceSessionController, VoiceMetrics

platform/voice/
  AndroidAudioFocusPort, AndroidTtsPort, AndroidVoiceCapturePort,
  OemWakeAdapter, SystemAsrAdapter, LocalAsrAdapter, LocalVadAdapter

service/voice/
  MatrixVoiceInteractionService, MatrixVoiceSessionService,
  VoiceCaptureForegroundService, VoiceSessionBinder

src/main/res/xml/
  voice_interaction_service.xml
~~~

core/voice 保持无 Android 依赖并可 JVM 单测；service/voice 只负责 Android 生命周期与 Binder；任何模型/音频线程不放入 Activity 或 ViewModel。

---

## 4. 统一会话状态机

### 4.1 状态定义

~~~text
IDLE
  → WAKE_ACCEPTED
  → LISTENING
  → ENDPOINTING
  → RECOGNIZING
  → THINKING
  → CONFIRMING
  → SPEAKING
  → IDLE

异常分支：
  任意状态 → ERROR_ANNOUNCING → IDLE
  LISTENING / RECOGNIZING / THINKING / CONFIRMING / SPEAKING
    → CANCELLED → IDLE

打断分支：
  SPEAKING + 有效用户语音
    → BARGE_IN_LISTENING
    → LISTENING
~~~

| 状态 | 进入动作 | 可接受事件 | 必须退出条件 |
|---|---|---|---|
| IDLE | 释放焦点、停止采音/TTS、清零短期音频 | WakeEvent | 不保留原始音频。 |
| WAKE_ACCEPTED | 校验 user/zone、播放可选短提示音 | 启动采音成功 | 身份或权限失败直接回 IDLE。 |
| LISTENING | 请求输入路由和会话期麦克风、启动 VAD/ASR | partial、speech、silence、stop | 连续静音超时进入 ERROR_ANNOUNCING 或 IDLE。 |
| ENDPOINTING | 停止采音，等待 ASR final | final、ASR error | 只等待固定短超时，不能无限卡住。 |
| RECOGNIZING | 校验文本、置信度、语言、敏感命令词 | accepted/rejected | 不合格时最多一次澄清，不盲目执行。 |
| THINKING | 向 AgentVoicePort 提交 VoiceInvocation | terminal、cancel、steer | 请求 ID 必须绑定当前 scope。 |
| CONFIRMING | TTS 读出操作摘要，重新采音识别固定确认词 | confirm、deny、timeout | timeout/不明确/否定一律取消未执行动作。 |
| SPEAKING | 请求目标音区焦点并播报 SpeakableResponse | done、focus loss、barge-in | 焦点丢失停止或按 OEM 策略暂停；TTS 完成回 IDLE。 |

状态机只有 VoiceSessionController 可以迁移。ASR、TTS、Agent 和 Service 回调只投递不可变 VoiceEvent；禁止多个组件直接 start/stop AudioRecord 或 TTS。

> Demo 轨道同样使用本状态机，但：CONFIRMING 状态在 Demo 不开放（遇 `USER_CONFIRM` 能力在 RECOGNIZING/THINKING 阶段直接拒绝并回 IDLE）；barge-in 与“停止”统一通过当前 VoiceSession 的 `CancellationToken`——先 `token.cancel()`，再视需要中断 Future，现有 `OnDeviceModelGateway` per-call cancel 足以支撑。多 zone / 抢占行不适用于 Demo 单会话。

### 4.2 超时、取消与抢占

| 情况 | 行为 |
|---|---|
| 唤醒后无语音 | 播放一次短提示或静默结束，不向 Agent 提交空请求。 |
| ASR final 置信度低/为空 | 请求一次澄清；连续失败后结束，不猜测车控命令。 |
| 用户说“停止/取消” | 若在 THINKING，按 requestId 取消；若在 SPEAKING，只停 TTS；若在 CONFIRMING，拒绝待确认操作。 |
| TTS 中用户说新任务 | 立即 stop TTS，开始新会话；如果当前 Agent 在运行，则按“停止”或“补充”明确语义决定 cancel/REPROMPT。 |
| 主驾请求抢占副驾只读任务 | 复用 TaskScheduler 的抢占规则，但音区和会话必须分开；副驾不得控制主驾会话。 |
| 来电、导航、安全告警导致音频焦点丢失 | 停播并记录 FOCUS_LOST；是否恢复由 OEM 音频策略决定，默认不自动重说可能包含敏感内容的结果。 |
| 进程死亡 | 所有音频立即停止；重启不恢复正在采集的音频或待确认写操作；待确认操作自动失效。 |

---

## 5. Agent 与语音的安全集成

### 5.1 VoiceInvocation（量产轨道）

> Demo V1 不引入 VoiceInvocation/AgentVoiceFacade，VoiceSessionController 直接调用 `repository.execute(text, Actor.DRIVER, token)`。本节描述量产轨道的调用边界。

VoiceSessionController 不直接调用 AgentRuntimeRepository.execute(String, Actor, token)。无头服务化后使用 AgentVoiceFacade：

~~~text
VoiceInvocation {
  requestId: 服务端生成
  text: ASR 最终文本
  inputSource: VOICE
  asrConfidence: [0, 1]
  languageTag: ASR 已识别语言
  identityScope: 由可信 WakeEvent / Binder 解析
  audioZoneId: OEM 映射结果
  cancellationToken: 当前 VoiceSession 所有
}
~~~

客户端、ASR 文本和模型都不能填写 principalUserId、occupant zone、policy actor、arbitration key 或 tool 参数。它们由 IdentityScope 注入 AgentRequest，并贯通 Session、Memory、Audit、Steer 与清数据。

### 5.2 可播报结果

新增 ResponsePresenter，将 AgentOutcome 转为两个不同的产品视图：

| 视图 | 使用者 | 内容 |
|---|---|---|
| DisplayResponse | Settings/Debug | 结构化终态、摘要、可查看错误码。 |
| SpeakableResponse | TTS | 极短、自然语言、脱敏、按当前 zone 合法播报的文本。 |

禁止把以下内容送给 TTS：完整 Trajectory、Tool 参数、内部 ToolResult、API/模型错误堆栈、记忆原文、审计数据、其他 user/zone 的结果。

V2 只在 Agent 终态后生成一次 SpeakableResponse，避免现有非流式 ModelGateway 在中途输出错误或被取消时“说半句”。V3 才可增加流式播报，但前提是 ModelGateway 增加可取消的 token 级输出接口，且 ResponsePresenter 能逐段安全判定。

建议终态文案规则：

| Agent 状态 | 可播报示例 | 不可播报行为 |
|---|---|---|
| SUCCEEDED | “已将主驾温度设为二十四度。” | 不朗读调试细节。 |
| PARTIALLY_SUCCEEDED | “空调已调整，导航未能启动。” | 不把失败原因中的内部 URI/堆栈读出。 |
| POLICY_REJECTED/FAILED | “为安全起见，我现在不能执行这项操作。” | 不假装执行成功。 |
| EXECUTION_UNKNOWN | “指令可能已发出，建议查看车辆当前状态。” | 不说“已完成”或“失败”。 |
| CANCELLED/PREEMPTED | 通常静默；必要时“已停止。” | 不在用户主动打断后继续播长提示。 |

Demo V1 的 ResponsePresenter 用规则模板生成：按 `AgentOutcome.finalState`（TaskState）映射到上表固定话术，从 `internalResults` 摘要拼接（PARTIALLY_SUCCEEDED 时拼接成功项与失败项的脱敏摘要），不引入 LLM 改写，避免多一轮推理延迟和二次脱敏负担。V2/V3 可选 LLM 改写，但须额外过安全过滤，且不违背“终态一次播报”。

### 5.3 语音确认（量产轨道）

> Demo V1 不实现 ConfirmationGate；遇 `USER_CONFIRM` 能力直接拒绝/不开放，绝不“先执行再等回答”。以下 ConfirmationGate 设计属量产轨道。

当前 VerifyMethod.USER_CONFIRM 只是未实现的 VerifyStrategy 占位，不能直接作为高风险操作的执行前授权。实施时必须新增独立的 ConfirmationGate：

1. Policy/CapabilityDefinition 明确哪些操作需要 pre-execution confirmation，例如安全敏感、不可逆或涉及另一个音区的操作。
2. Agent 在工具下发前返回 PendingConfirmation，而不是先调用 Provider 再等待用户回答。
3. ConfirmationCoordinator 保存 requestId、IdentityScope、能力摘要、随机 confirmationToken、过期时间和目标参数的不可变快照。
4. TTS 只播报已脱敏的操作摘要，例如“要关闭主驾座椅加热吗？”。
5. ASR 使用受限确认语法识别“确认/是/继续”和“取消/否”；低置信度、其它文本、超时都不执行。
6. confirm/deny 必须带 confirmationToken 且验证同一 IdentityScope；确认后才将被冻结的工具调用交给 Agent/Provider。

若语音确认不可用，系统必须拒绝该操作或转移到受信任屏幕确认，不能把“没听清”解释成确认。

---

## 6. Demo V1：进程内最小闭环（仅 Debug/eng，不对用户发布）

### 6.1 目标

在真实 Android 设备上跑通完整语音会话闭环：唤醒 → ASR → Agent → 安全播报 → barge-in 打断。验证 `VoiceSessionController` 状态机、`ResponsePresenter` 安全播报、`CancellationToken` 取消语义。不接入 OEM DSP，不声称具备车机常驻监听能力，不做高风险语音确认。

### 6.2 架构（进程内单进程）

~~~text
前台 Activity（页面可见时直接 AudioRecord）
  或 受控 microphone FGS（Debug-only，从可见页面启动、带通知）
    → Vosk 软件关键词检测（grammar-based 软件“唤醒”）
    → Vosk 流式 ASR → VoskEndpointAdapter（静音端点，封装为 VadPort）
    → VoiceSessionController（状态机）
    → repository.execute(text, Actor.DRIVER, token)   ← 直连，不经 Facade
    → ResponsePresenter → SpeakableResponse
    → Android TextToSpeech
~~~

进程内直连，**不引入** `AgentVoiceFacade` / `VoiceInvocation` / `IdentityScope`：进程内没有不可信跨进程客户端，多一层 Facade 是纯抽象、零当下收益。身份复用现有 `Actor.DRIVER`（demo-driver），`AgentRuntimeRepository` / `AgentRequest` / `ActorUsers` **一行不改**。这层 Facade 留给量产服务化阶段，当出现 Binder 边界时再抽。

### 6.3 固定 demo 身份是限制，不是漏洞

固定 `Actor.DRIVER` 是进程内 Demo 的明确限制，不是安全边界漏洞，前提是：

1. Demo 组件不 exported、不跨进程；
2. 不让 ASR 文本或任何外部调用方填写身份；
3. release 变体不含 Demo 语音入口。

### 6.4 Vosk 的边界

Vosk 只作为 Demo 的 ASR 与软件“唤醒”实现，**不要写成车机级专用 KWS/VAD**。它的官方 Android 路径核心是离线 ASR 与 grammar（[Vosk Android 文档](https://alphacephei.com/vosk/android)）：

- 软件“唤醒”：用 Vosk `Recognizer` 的 grammar（受限关键词表）做关键词检测，不是低功耗 DSP KWS。
- ASR：Vosk 流式 partial/final。
- 端点：把 Vosk 的 `_final`/静音信号封装为 `VoskEndpointAdapter`，实现 `VadPort` 接口；以后可整体替换为真正的 VAD，不影响 `VoiceSessionController`。

### 6.5 采音方式

- 页面可见时：直接 `AudioRecord`，无需 FGS。
- 页面退后台后仍要测软件唤醒：microphone FGS 必须从可见页面启动、带前台通知，且只存在于 Debug/eng 变体；不能进入量产常驻路径（[前台服务后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)）。

### 6.6 功能实现

| 子系统 | Demo V1 实现 | 必须新增/修改 |
|---|---|---|
| 唤醒 | Vosk grammar 关键词检测 | 新建 `WakeWordPort` + `VoskWakeAdapter`（仅 Debug）。 |
| ASR | Vosk 流式 partial/final | 新建 `AsrPort` + `VoskAsrAdapter`。 |
| 端点 | `VoskEndpointAdapter` 封装 Vosk final/静音 | 新建 `VadPort`，实现为 `VoskEndpointAdapter`，可替换。 |
| Agent | `repository.execute(text, Actor.DRIVER, token)` 直连 | **不改** Repository/AgentRequest/ActorUsers。 |
| 播报 | `ResponsePresenter` → `SpeakableResponse` → Android TextToSpeech | 新建 ResponsePresenter/SpeakableResponse；不得复用 Debug render()。 |
| 取消 | `token.cancel()` 先行，再视需要中断 Future | 复用现有 CancellationToken + OnDeviceModelGateway per-call cancel。 |
| 高风险确认 | 遇 `USER_CONFIRM` 能力直接拒绝/不开放 | 不实现 ConfirmationGate。 |
| 观测 | 状态迁移、端到端耗时、错误码 | VoiceMetricsPort，禁止记录 transcript。 |

### 6.7 Demo V1 验收

1. 连续唤醒不会创建两个 VoiceSession，进程内只有一个 AppContainer。
2. 空文本、低置信度、ASR/TTS 错误、Agent 超时、取消、进程重启都能回到 IDLE。
3. barge-in 在 THINKING 取消 Agent、在 SPEAKING 停止播报，均通过 `token.cancel()` 先行。
4. 遇 `USER_CONFIRM` 能力不触达 Provider，直接拒绝并播报保守话术。
5. `SpeakableResponse` 单测证明不含 Trajectory、Tool 参数、密钥、路径、原始记忆或异常堆栈。
6. release 变体不含 Vosk Demo 入口、microphone FGS 或可触发唤醒的导出组件。

---

## 7. V2：真实唤醒词与车机语音助手（首个用户版本，量产轨道）

> 本节为量产轨道。Demo 见第 6 节。V2 的系统入口、OEM 唤醒、跨进程身份都不适用于 Demo。

### 7.1 产品范围

V2 上线即提供真实唤醒词，用户体验为“唤醒 → 说话 → 听结果”。它至少覆盖：

- 由 OEM DSP/SoundTrigger 或选中的 VoiceInteractionService 触发唤醒。
- 单一活跃座舱音区的 VAD、ASR、Agent、TTS。
- 清晰的启动/结束提示音，短句 TTS，用户语音打断播报。
- 安全的高风险操作确认、取消和超时。
- 不联网时的明确降级提示；是否本地 ASR 由 OEM 能力决定，V3 才将“全部端侧”作为硬目标。

### 7.2 系统集成前提

| 前提 | 说明 | 未满足时的行为 |
|---|---|---|
| MatrixAgent 作为预装/被选中的语音助手 | VoiceInteractionService 是系统当前全局语音交互器；服务必须由系统以 BIND_VOICE_INTERACTION 绑定。 | 不启用常驻唤醒；Settings 显示需要系统集成。 |
| OEM wake 事件 | 事件应来自 DSP、SoundTrigger 或受信任语音前端，并带可验证的 user/zone。 | 不以后台 AudioRecord 轮询替代。 |
| 话筒路由 | OEM 提供正确麦克风数组、AEC/NS 参考路径和座舱关联。 | 仅支持经验证的默认驾驶舱，不能虚假宣称分区识别。 |
| 音频策略 | car_audio_configuration 和 OEM focus matrix 配置 ASSISTANT/VOICE_COMMAND。 | TTS 不上线，避免和导航/安全音冲突。 |
| 权限/前台服务 | RECORD_AUDIO、FOREGROUND_SERVICE_MICROPHONE 及目标系统权限已审核。 | 不能启动采集，返回明确错误。 |

### 7.3 Android 与 AAOS 清单要求

至少新增并审核：

~~~xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

<service
    android:name=".service.voice.MatrixVoiceInteractionService"
    android:process=":voice_trigger"
    android:permission="android.permission.BIND_VOICE_INTERACTION"
    android:exported="true">
    <!-- VoiceInteractionService action 和 metadata 依 Android 契约声明 -->
</service>

<service
    android:name=".service.voice.VoiceCaptureForegroundService"
    android:process=":agent"
    android:foregroundServiceType="microphone"
    android:exported="false" />
~~~

以上只是最小骨架。最终实现必须在目标 AAOS 镜像中验证：

- Android 14 及以上会校验 microphone 前台服务的 RECORD_AUDIO 权限和 while-in-use 状态。
- 后台启动 microphone FGS 并非一般 App 可以任意执行；VoiceInteractionService 属于官方列出的特殊情形之一，但实际 OEM 镜像和触发路径仍必须实测。
- 采音服务只能在一次已接受的 VoiceSession 内启动；IDLE 时不运行 microphone FGS。

### 7.4 V2 音频链路

~~~text
可信 DSP/System WakeEvent
  → WakeEventValidator（UID、user、zone、冷却时间）
  → VoiceSessionController
  → 请求目标 zone 的输入/输出资源
  → 短提示音（可选）
  → AudioRecord/厂商采音端口
  → AEC / NS（优先 OEM DSP）→ VAD → 流式 ASR
  → FinalTranscriptValidator
  → AgentVoiceFacade
  → ResponsePresenter
  → AudioFocus + TTS 到同一 audio zone
~~~

V2 优先使用 OEM 已认证 ASR/TTS 或系统预装语音引擎。不要在没有真车性能数据前承诺某个通用开源模型能满足座舱噪声、方言、回声和冷启动要求。

### 7.5 音区与焦点

1. WakeEventValidator 将唤醒事件绑定到 Android user、occupant zone 和 car audio zone；映射失败直接拒绝会话。
2. TTS 使用 ASSISTANT 类 AudioAttributes，并以 OEM 配置的 car audio context 路由。一个会话只请求其目标 zone 的焦点。
3. 多音区车辆中，其他乘员的媒体播放不应因主驾语音交互被无端停止；需要多 zone 时分别请求各自焦点。
4. 收到 audio focus loss 时，立即停止或暂停 TTS；V2 默认不自动恢复可能过期或敏感的播报。
5. 输入麦克风的座舱归属不能从输出音区反推。没有 OEM 麦克风阵列/波束形成支持时，V2 只声明“主驾驶舱单会话”，不宣称可区分主副驾。

### 7.6 V2 语音 UX 与语法

| 场景 | 处理 |
|---|---|
| 正常提问 | 识别 final 后提交 Agent，完成后一次短播报。 |
| 用户话未说完 | VAD 延长 endpoint；达到最大语音时长时礼貌提示并结束。 |
| 听不清 | 只请求一次复述，例如“我没听清，请再说一遍。” |
| 高风险操作 | 进入 CONFIRMING，读操作摘要，接受有限确认词。 |
| “停止” | 正在播报只停播；正在执行取消任务；等待确认则拒绝。 |
| “等等/补充” | 仅对当前同 scope 请求生成 typed REPROMPT/DEFER；内容先过长度与策略校验。 |
| 唤醒词误触发 | 无有效语音即无声回 IDLE，避免频繁打扰。 |
| ASR/网络不可用 | “语音识别暂不可用。”；不将不完整 partial 提交给 Agent。 |

### 7.7 V2 验收

- 冷启动、热启动、连续 100 次唤醒的会话状态均可回收，没有麦克风、TTS、AudioFocus 或 binder 泄漏。
- 设备锁屏/切换 user/切换 occupant zone/失去 VoiceInteractionService 身份时，不采集或误路由音频。
- 真车实测在 OEM 指定的速度、空调、音乐、导航和电话干扰场景下，唤醒、ASR、TTS 和取消符合产品门槛。
- TTS 焦点丢失、ASR 失败、模型失败、Provider EXECUTION_UNKNOWN 均采用保守话术，不谎报执行成功。
- 通过安全评审：无常驻原始录音；transcript 默认不写入 Logcat/Audit；任何可选诊断采样需独立用户同意和保留期限。

---

## 8. V3：端侧极致语音体验（量产轨道）

### 8.1 V3 目标

V3 不改变 V2 的系统边界，而是在其上把“唤醒后高质量语音交互”尽可能端侧化、低延迟化和多音区化：

- 本地/离线流式 ASR 与离线 TTS。
- 在 OEM 明确授权的前提下支持本地 KWS 作为 DSP 方案的补充，而不是偷用永久麦克风。
- 车内噪声鲁棒的 VAD、AEC、NS、波束形成和 barge-in。
- 主副驾分区会话、音区输出和身份一致性。
- Agent/LLM 流式可播报输出，以及取消时的逐段停止。
- 可量化的端侧 CPU、内存、功耗、延迟、误唤醒和识别质量预算。

### 8.2 端侧模型架构

~~~text
DSP KWS（优先）
  └─ 无法覆盖的 OEM 机型，且有特权输入权限时：
       低功耗 LocalKwsEngine

Wake 后短时间高性能音频链路：
  Mic Array → OEM AEC/Beamforming → NS → LocalVAD
            → Streaming LocalAsrEngine → partial/final transcript

播报：
  Safe streaming Agent output → SentenceBoundaryBuffer
    → SpeakableResponseFilter → LocalTtsEngine → zone output
~~~

端侧 ASR/KWS/TTS 的具体引擎不在本设计中硬编码。选型必须以目标 SoC、NNAPI/DSP/NPU、ABI、可用 RAM、中文/方言、模型许可证、首 token 延迟和 OEM 音频前端兼容性为准。引擎须实现 AsrPort/TtsPort/WakeWordPort，才能替换而不影响 Agent。

### 8.3 流式与打断

V3 可以增强 ModelGateway 为可取消的输出流：

1. ModelGateway 暴露 token/句子片段 listener，必须绑定现有 CancellationToken。
2. ResponsePresenter 仅在句子边界、通过敏感字段过滤并达到最低可理解长度后交给 TTS。
3. 用户 barge-in 时，VoiceSessionController 先停止 TTS，再取消未完成的模型流和当前请求；不得让已缓冲的 TTS 句子继续播放。
4. 对车控写操作，流式播报不能替代真实 Provider 回读或 ConfirmationGate；执行状态仍以 Policy/Provider 终态为准。

在没有流式模型能力时，V3 继续使用 V2 的终态播报，不为了“边生成边说”牺牲取消和安全语义。

### 8.4 多音区与多人

| 能力 | V3 设计要求 |
|---|---|
| 音区输出 | 每个 VoiceSession 绑定一个 car audio zone；TTS、提示音、音量和焦点均按该 zone 处理。 |
| 输入归属 | 仅使用 OEM 提供的麦克风阵列/波束形成/座位归因结果；不以语言内容猜测座位。 |
| 并发会话 | 默认单车单活跃唤醒；只有硬件支持独立麦克风和 OEM 策略许可后，才能开放各 zone 并发。 |
| 用户识别 | 不把声纹识别作为 V3 的默认认证机制。Android user/occupant mapping 仍是数据权限权威。 |
| 交叉干扰 | 非本 zone 的语音不得取消、确认或接管本 zone 任务；任何归因不确定都拒绝高风险动作。 |

### 8.5 性能预算与监控

产品必须为目标硬件定义而非猜测以下预算：

| 指标 | 测量点 | 要求 |
|---|---|---|
| 唤醒延迟 | DSP/KWS event 到开始采集 | 以 OEM SLA 为准，分冷/热态记录。 |
| 端点延迟 | 用户停说到 ASR final | 分噪声等级、语言、音区统计。 |
| 识别延迟 | 音频 final 到 VoiceInvocation | 不得在主线程或 Binder 线程解码。 |
| Agent 首响应/终态 | 提交到可播报结果 | 分本地/云端模型、取消和失败状态统计。 |
| 打断时延 | 有效语音到 TTS 静音 | 端到端测量，不只测 TTS.stop() 调用。 |
| 资源 | 常驻 trigger、活动会话、ASR/TTS 的 CPU/RAM/功耗 | 按驾驶、停车、充电等工况测量。 |
| 准确性 | FAR、FRR、WER、确认误接受/误拒绝 | 按车型、噪声、方言和音区分桶。 |

指标只记录聚合数值与错误码。音频、原始 transcript、用户内容不得进入常规埋点。

### 8.6 V3 验收

1. 在无网络条件下，核心唤醒、识别、车控、确认与播报均按产品声明工作，或提供精确降级提示。
2. CPU/RAM/功耗不破坏 OEM 对待机、驾驶、热稳定性的预算。
3. 用户打断在模型生成、TTS、确认和工具执行不同阶段均有一致且安全的结果。
4. 两个音区同时播放媒体、导航和语音时，焦点、路由、ducking 与安全音符合 OEM 配置。
5. 真实座舱测试覆盖窗户打开、空调、音乐、导航、电话、多人说话和网络抖动。

---

## 9. 需要修改的当前代码

### 9.1 必改项

> Demo V1 只需其中一部分：`ResponsePresenter`/`SpeakableResponse`、`VoiceSessionController`、`VoskWakeAdapter`/`VoskAsrAdapter`/`VoskEndpointAdapter`、AndroidManifest 加 `RECORD_AUDIO`（Debug）、build.gradle 加 Vosk 依赖。表中 `MatrixAgentApplication`/`AppContainer`/`AgentRuntimeHost`、`AgentRequest` 的 principalUserId、`ActorUsers`、`execute` 签名重构、`ConfirmationGate`、AndroidManifest 的 VoiceInteractionService/AIDL/Provider **全部属量产轨道，Demo 不需要**。

| 当前类/模块 | 现状 | 改造 |
|---|---|---|
| MatrixAgentApplication | 当前直接创建 AppContainer | 按无头服务化设计改为轻量 Application；Voice Trigger 进程也不得创建容器。 |
| AppContainer | 组合 Agent/下载/模型能力 | 通过 AgentRuntimeHost 只在 :agent 创建；注入 AgentVoiceFacade、ConfirmationCoordinator、VoiceMetrics。 |
| AgentRequest | requestId 自生成，user 由 ActorUsers demo 映射 | 增加服务端注入 requestId/principalUserId；VOICE 元数据仅允许 VoiceSessionController 写入。 |
| ActorUsers | 映射 demo-driver/demo-passenger | 改为读取 AgentRequest.principalUserId；生产路径删除 demo 常量。 |
| AgentRuntimeRepository | execute(command, Actor, token)；clear 同时影响 demo 域 | 新增 execute(RuntimeInvocation) 与 scoped clear；按 requestId 管理取消/终态。 |
| AgentOutcome | 有轨迹和内部结果，无产品播报模型 | 保持内部对象不外泄；新增 ResponsePresenter 和 SpeakableResponse。 |
| VerifyMethod.USER_CONFIRM | 仅占位 | 新增 ConfirmationGate/PendingConfirmation，区分执行前确认和执行后验证。 |
| AgentTestViewModel / Fragment | 直接调用 Repository 并渲染完整轨迹 | 只保留 debug 客户端；改调 AIDL/VoiceSession 测试端口。 |
| AndroidManifest | 只有 Activity 和 DownloadService | 添加 VoiceInteractionService、Session/麦克风 FGS、权限和 XML metadata；按产品签名/系统权限审核。 |
| build.gradle | 无音频、语音依赖 | V1 先加最小 Android TTS/音频 API；V2/V3 的 ASR/KWS runtime 以单独模块引入，禁止直接污染 core。 |

### 9.2 不应修改的边界

- AgentEngine、PolicyEngine、CapabilityProvider 不依赖 Android 音频 API。
- ASR 不能直接执行车控能力；它只生成文本和置信度。
- TTS 不读取 MemoryStore、Audit、ModelConfig 或内部 ToolResult。
- VoiceInteractionService 不承载模型推理、Room、下载或长时间音频处理。
- Settings 不获得麦克风权限，也不承担唤醒服务。

---

## 10. 安全与隐私

### 10.1 原始音频策略

| 数据 | 默认策略 |
|---|---|
| 唤醒前音频 | 仅由 DSP/OEM 前端按系统策略处理；MatrixAgent 不持久化。 |
| 会话 PCM | 环形缓冲只在内存中用于当前 ASR；final、取消、错误或超时后立即释放。 |
| ASR partial | 仅内存传递；不写数据库、不写 Logcat。 |
| ASR final | 作为 Agent 用户输入进入必要的会话/审计路径，沿用现有脱敏和用户数据清理规则。 |
| TTS 文本 | 从 SpeakableResponse 生成；不记录原文，只记录结果码和字符数。 |
| 诊断样本 | 默认关闭；若 OEM/用户同意开启，独立加密、最短保留期、严格访问控制和删除能力。 |

### 10.2 误唤醒与误识别

- 唤醒词后没有有效语音时不提交 Agent。
- 低置信度车控、目标 zone 冲突、多个座位目标或涉及敏感动作时必须澄清/确认/拒绝。
- 不允许模型把“你听到我说确认了吗”之类文本当作 ConfirmationGate 的确认。
- 语音确认必须来自当前活跃 VoiceSession、当前 IdentityScope、短时有效 token 和受限语法。
- 说话人身份不是权限权威。即使未来加声纹，Android user + zone 仍是访问控制依据。

---

## 11. 测试计划

> Demo V1 的测试范围：11.1 的 JVM 单测（VoiceSessionState / ResponsePresenter / ConfirmationCoordinator 的 Demo 拒绝路径）、6.7 的 Demo 验收、14.1 的完成定义。11.2 Instrumented 与 11.3 OEM 真车属量产轨道。Demo 阶段不依赖真车。

### 11.1 JVM 单测

- VoiceSessionState 的所有迁移、超时、重复事件和非法事件。
- ResponsePresenter 的状态文案、隐私过滤、最大长度、敏感字段和 EXECUTION_UNKNOWN 话术。
- ConfirmationCoordinator 的 token、过期、scope 不匹配、低置信度、deny、重复 confirm。
- VoiceInvocation 到 AgentRequest 的 identity/VOICE/asrConfidence 映射。
- Barge-in 规则：TTS stop 在 cancel 前调用，跨 zone 事件被拒绝。

### 11.2 Instrumented 测试

- TTS 初始化/失败/stop 后状态收敛。
- AudioFocus gain/loss、不同 zone 路由和服务重建。
- VoiceInteractionService active/inactive、session service 进程边界和 Binder death。
- microphone FGS 的权限、启动路径、错误码和在 IDLE 时未运行。
- Provider/AIDL 与 VoiceSession 同时启动时只有一个 :agent AppContainer。

### 11.3 OEM 真车测试

| 维度 | 样例 |
|---|---|
| 噪声 | 怠速、高速、空调最大、雨刮、开窗、音乐、导航、电话。 |
| 人员 | 主驾/副驾、多人交谈、儿童声音、相邻音区媒体。 |
| 语义 | 查询、普通车控、禁止操作、确认/否定/听不清、停止、补充、连续唤醒。 |
| 系统 | 用户切换、锁屏、网络断开、模型切换、Agent/ASR/TTS/音频服务重启。 |
| 资源 | 长时间待机、连续会话、热态、低存储和低电量策略。 |

---

## 12. 发布门控与回滚

1. Demo V1 仅 debug/eng 变体，release 不带可触发唤醒的入口（Vosk 关键词、microphone FGS 或测试广播）。
2. V2 先验证系统镜像、专用签名、默认语音助手选择和 OEM 音频策略，再开启用户可见唤醒设置。
3. Voice Wake 开关应由系统 Settings 的受保护配置控制；关闭时注销/停用唤醒适配器并确认没有活动 microphone FGS。
4. 若 ASR/TTS 模块加载失败，回退到“语音暂不可用”，不能回退为后台录音或把不完整 transcript 发给 Agent。
5. 若 V3 本地模型性能不达标，保留 V2 的 OEM 语音前端路径；端侧模型按独立配置/下载版本回滚，不能影响 Agent Runtime 本身。

---

## 13. 外部系统约束与参考

- Android VoiceInteractionService 是当前全局语音交互器的顶层服务，系统会保持其运行；官方建议该服务保持轻量，将重会话工作放进关联的 session 服务和独立进程：[VoiceInteractionService API](https://developer.android.com/reference/android/service/voice/VoiceInteractionService.html)。
- target Android 14 及以上对 microphone 类型前台服务和 while-in-use 麦克风权限有额外限制；后台启动并非通用能力，VoiceInteractionService 相关启动属于需要在目标镜像验证的特殊路径：[前台服务后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)。
- AAOS 的音频焦点按 AudioAttributes/CarAudioContext 管理，多音区焦点独立，应用必须正确响应焦点丢失：[AAOS Audio focus](https://source.android.com/docs/automotive/audio/audio-focus)。
- AAOS 的 audio zone 关联输出、焦点和音量；多用户/多区并发不能靠普通应用自行猜测路由：[AAOS Multi-zone audio routing](https://source.android.com/docs/automotive/audio/audio-multizone-routing)。

---

## 14. 完成定义

### 14.1 Demo V1 完成定义

Demo V1 可宣布“语音闭环可演示”必须同时满足（实现状态截至 2026-08-11）：

1. ⏳ **待真机**：在真实 Android 设备上，通过 Vosk 软件“唤醒”进入会话，不依赖 OEM DSP，也不声明常驻监听能力。（代码就绪：`VoskWakeAdapter` hey matrix + `VoiceCaptureController` IDLE 采音；真机验证待）
2. ✅ **已实现**：唤醒、ASR、Agent、TTS、barge-in 打断均由进程内 VoiceSessionController 管理，Agent 侧零改动（直连 `repository.execute(text, Actor.DRIVER, token)`）。（`VoiceSessionControllerTest` JVM 测全绿）
3. ✅ **已实现**：barge-in 与“停止”通过 `token.cancel()` 先行，再视需要中断 Future。（`cancel_duringThinking` 测验证 token.cancel 触发 + 迟到 outcome 被丢弃）
4. ⚠️ **配置层满足**：遇 `USER_CONFIRM` 能力直接拒绝，不触达 Provider。（Demo registry 不注册 USER_CONFIRM 能力 + 状态机 NEEDS_CONFIRM→IDLE 兜底；主动预判需“文本→能力”分类器，Demo 暂不开放即安全）
5. ✅ **已实现**：TTS 仅播报 ResponsePresenter 生成的 SpeakableResponse，单测证明不含 Trajectory、Tool 参数、密钥、路径、原始记忆或异常堆栈。（`ResponsePresenterTest` 的 `privacy_*` 断言）
6. ✅ **已实现**：release 变体不含 Vosk Demo 入口、microphone FGS 或可触发唤醒的导出组件。（`nav_voice` 仅 `BuildConfig.DEBUG` 可见;`VoiceDebugFragment` 双保险检查 `BuildConfig.DEBUG`,release 不装配/不采音）

**实施进度（2026-08-11）**：四步代码全部完成、`assembleDebug` 编译过、JVM 单测全绿；真机闭环验证待。

- `core/voice`：`VoiceSessionState`（状态机）+ `ResponsePresenter`/`SpeakableResponse`（安全播报）+ 端口 `AsrPort`/`WakeWordPort`/`VadPort`/`TtsPort`/`AgentRunner`/`VoiceSessionListener`/`VadEvent`/`VoiceEvent`。
- `platform/voice`：`VoskAsrEngine`（共享内核）+ `VoskAsrAdapter`/`VoskEndpointAdapter`/`VoskWakeAdapter` + `VoskModelHolder` + `AndroidTtsAdapter` + `VoiceCaptureController`（AudioRecord `VOICE_COMMUNICATION` + `AcousticEchoCanceler` + barge-in RMS 能量检测）。
- `data/voice`：`VoiceSessionController`（stateExecutor 序列化 + agentExecutor 跑 execute；`onTerminal` 检查 THINKING 防迟到 outcome）+ `VoskModelDownloader`（下载 en/cn zip + Range 续传 + 解压 flatten + 复用 `ModelDownloadDao` 进度）+ `VoskModelSpec`。
- `presentation`：`VoiceDebugFragment`/`VoiceDebugViewModel` + `VoiceUiState` + `fragment_voice_debug.xml` + `MainActivity`/`MatrixViewModelFactory` 接入（抽屉 `nav_voice`）。
- 双模型：`vosk-model-small-en-us-0.15`（grammar 唤醒 "hey matrix"）+ `vosk-model-small-cn-0.22`（ASR），首次自动下载到 `filesDir/vosk-model/{en,cn}`。Vosk `com.alphacephei:vosk-android:0.3.75`，ABI `arm64-v8a`。

**已知缺口（Demo 可演示，量产前补）**：

- `VoiceMetricsPort` 未实现（观测/埋点）。
- microphone FGS 未实现（页面可见 AudioRecord 已满足 Demo；退后台采音留后续）。
- USER_CONFIRM 主动预判未实现（靠 registry 不开放 + 状态机兜底）。
- 真机参数待调：`BARGE_IN_RMS_THRESHOLD` / `BARGE_IN_HOLD_MS`、AEC 效果、英文唤醒词识别率。

### 14.2 量产 V2 完成定义

V2 可以宣布“MatrixAgent 支持语音唤醒”必须同时满足：

1. 用户通过真实 OEM/系统唤醒词进入会话，不依赖按键或后台 AudioRecord 轮询。
2. 唤醒、ASR、Agent、TTS、停止、确认、超时和进程恢复均由统一 VoiceSessionController 管理。
3. Voice 输入严格带有可信 IdentityScope，且不破坏 MatrixAgent 的 user/zone、Policy、Audit 和取消语义。
4. TTS 仅播报 ResponsePresenter 生成的安全结果，不泄露内部轨迹、密钥、记忆或其他用户数据。
5. 麦克风、前台服务、音频焦点和音区路由在目标 AAOS 镜像及真车场景通过验证。
6. release 无调试唤醒入口、无默认常驻录音、无 transcript/PCM 日志泄漏。

V3 则在 V2 的安全底线之上，完成经过性能、功耗、误唤醒、识别质量和多音区实测验证的端侧极致体验。
