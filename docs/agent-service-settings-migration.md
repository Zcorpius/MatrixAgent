# MatrixAgent 无头服务化与 Settings 集成实施设计

> 状态：实施设计，替代旧版迁移草案。
> 目标：将 MatrixAgent 演进为由系统 Settings 配置、可由受信任入口调用的无头 Agent Runtime；保证进程启动顺序、配置切换、多用户隔离、密钥保护和下载恢复都可验证、可回退地落地。

---

## 1. 目标、边界与不可变约束

### 1.1 交付目标

量产形态由三个部分组成：

1. MatrixAgent APK 提供运行时、模型下载、加密配置存储、AIDL 操作接口和受保护的配置 Provider。
2. 系统 Settings 是量产唯一配置 UI，负责模型 API、端侧模型下载、运行状态和“清除此用户数据”的确认操作。
3. DebugActivity 仅保留在开发构建中，所有能力也必须通过 AIDL/Provider 访问，不能再直接拿 AppContainer。

完成后应满足：

- Settings 崩溃、旋转或被杀不会创建第二套 Agent 状态，也不会影响正在运行的 Agent。
- 任意组件以 Provider、Service、下载服务三种顺序之一启动，当前 Android user 的 :agent 进程中都只会创建一个 AppContainer。
- 模型配置只有在运行时成功切换后才显示为已生效；切换失败时旧 Gateway 继续工作，并且 Settings 能看见失败原因。
- API Key 从不经 QUERY、AIDL 返回值、通知、日志或审计输出。
- 所有任务、查询、取消、转向和清数据操作均按经过验证的 Android user + occupant zone 限定，客户端不能伪造该身份。
- 进程被回收后，配置和下载状态可恢复；在途任务绝不被误报为成功。

### 1.2 本次不包含

- 完整 VoiceInteractionService 产品化、车机属性标定和模型市场协议改造不在本次范围。
- 不向第三方 App 开放任何普通权限接口。
- 不承诺通过常驻进程保留纯内存状态。Android 可以随时杀进程，设计必须以持久化恢复和明确终态为准。

### 1.3 已作出的架构决策

| 决策 | 结论 | 原因 |
|---|---|---|
| 运行时进程 | 每个 Android user 一个 :agent 进程 | 私有文件、AndroidKeyStore、Room/SQLCipher、Binder UID 天然按 Android user 隔离；不引入 singleUser 跨用户特权服务。 |
| UI 与运行时 | Settings 跨进程调用；DebugActivity 也跨进程调用 | 不让默认 UI 进程持有运行时对象，消除双容器。 |
| 配置写入 | Provider 的 call(apply_model_config) 同步提交，内部异步应用 | 既保持配置面统一，又避免“已落盘、未生效”的状态分裂。 |
| 下载 | 私有 DownloadService 与 Runtime 同在 :agent；外部只经 AIDL 操作 | 保留 dataSync 前台服务语义，同时统一容器、权限和进度事件。 |
| 身份 | 服务端从 Binder UID 解析 user/zone；请求中不接受 userId、zone、actor | 任何可由客户端填写的身份字段都不能作为数据隔离依据。 |
| 对外契约 | 独立 agent-contract 模块，显式 Parcelable，不使用 Bundle | Settings 与 Runtime 独立演进；接口可版本化、可测试。 |

---

## 2. 当前工程基线与必须修正的差异

当前实现已具备 AgentEngine、Policy、Memory、Audit、SQLCipher、SecureModelConfigStore 和 ModelDownloadManager，但还不是可跨进程运行时。下表是实施前必须承认的差异。

| 当前代码 | 风险 | 本设计要求 |
|---|---|---|
| MatrixAgentApplication.onCreate() 直接 new AppContainer | 每个 Android 进程的 Application 都会执行；Service 再创建容器会双实例 | Application 不再持有容器，所有 :agent 组件经 AgentRuntimeHost 获得唯一实例。 |
| ModelApiFragment、ModelDownloadFragment、AgentTestFragment 和 ViewModel 直接 getContainer() | 默认 UI 进程保有一套运行时 | 全部替换为 AgentClient(AIDL) 和 ContentResolver(Provider)。 |
| DownloadService 从 MatrixAgentApplication 取容器，且默认进程运行 | 与 Agent Runtime 不同进程时会读到另一套 DB/Manager | DownloadService 迁入 :agent，改用 AgentRuntimeHost；外部禁止显式启动。 |
| ModelApiViewModel 先创建 Gateway，再保存并切换 | 这是避免配置分裂的正确顺序，但只存在 UI 内 | 抽为服务端 ModelConfigurationController，Provider 不直接写 SecureModelConfigStore。 |
| AgentRuntimeRepository.execute(command, Actor, token) 和 clearUserData() 使用 demo-driver/demo-passenger | 外部调用时会把所有人压扁为 demo 身份，清理还会跨域 | 引入服务端 IdentityScope；AgentRequest、存储、Session、Memory、Audit 全链路改用真实 scope。 |

因此，“AgentEngine/Policy/工具逻辑尽量不变”是目标，但“核心零改动、现有单测零影响”不是可接受的实施承诺。身份传播、请求 ID、清理范围、容器生命周期和应用层契约必须修改，并为此补齐回归测试。

---

## 3. 目标架构

~~~text
┌──────────────────── Settings（唯一量产 UI）────────────────────┐
│ Model API / 下载 / 状态 / 清除此用户数据                         │
│ AgentClient（AIDL）             ContentResolver（Provider）     │
└───────────────┬───────────────────────────────┬─────────────────┘
                │ 操作面                         │ 配置与只读状态面
                ▼                                ▼
┌──────────────── MatrixAgent :agent（每 Android user 一份）──────┐
│ AgentRuntimeHost ────── 唯一 AppContainer                        │
│   ├─ MatrixAgentService / IMatrixAgentService.Stub               │
│   ├─ MatrixSettingsProvider                                     │
│   ├─ ModelConfigurationController（revision 状态机）             │
│   ├─ RuntimeRequestController（身份、队列、取消、回调）          │
│   ├─ DownloadCoordinator + 私有 DownloadService(FGS)            │
│   └─ RuntimeEventDispatcher（AIDL 回调 + Provider notifyChange）│
│                                                                    │
│ AppContainer → AgentRuntimeRepository / AgentEngine / Memory /    │
│                Audit / SecureModelConfigStore / DownloadManager   │
└───────────────────────────────────────────────────────────────────┘

VoiceInteractionService：
  - 若属于 MatrixAgent APK，显式声明 process=:agent，可调用内部 RuntimeFacade。
  - 若属于其他 APK，则和 Settings 一样只能通过受保护 AIDL 调用。
~~~

### 3.1 进程职责

| 进程 | 可以拥有的对象 | 禁止拥有的对象 |
|---|---|---|
| 默认应用进程 | DebugActivity、AIDL 客户端、Provider/AIDL 契约 DTO | AppContainer、Room DAO、ModelGateway、下载管理器。 |
| :agent 进程 | 唯一 AppContainer、所有 Runtime Controller、Provider、Service、下载 FGS | 量产 UI。 |
| Settings 进程 | 页面状态、AgentClient、ContentResolver 缓存 | MatrixAgent 的实现类、密钥缓存、DAO。 |

### 3.2 模块边界

新增最小的 agent-contract Android library，供 MatrixAgent 与 Settings 共同依赖：

- AIDL 文件及其 Parcelable 定义。
- 服务 action、接口版本号、Provider authority、URI、列名、Provider call 方法名。
- 无业务实现、无 AppContainer、无 ModelGateway、无数据库实体、无 API Key 日志。

MatrixAgent 内部新增 service 包和 provider 包。Settings 只依赖 agent-contract，不依赖 MatrixAgent 的 platform、data、core 包，避免把内部实现变成跨 APK ABI。

---

## 4. 运行时拥有权、启动顺序与生命周期

### 4.1 唯一容器：AgentRuntimeHost

新增进程内单例 AgentRuntimeHost，伪代码语义如下：

~~~java
final class AgentRuntimeHost {
    static AppContainer get(Context context);     // 双重检查或同一把锁，懒创建
    static boolean isInitialized();
    static void shutdownForProcessExit();         // 仅显式终止/测试使用
}
~~~

约束：

1. get() 只能以 applicationContext 创建 AppContainer，必须线程安全、幂等。
2. MatrixAgentService、MatrixSettingsProvider、DownloadService、同 APK 的语音入口全部调用 get()。
3. MatrixAgentApplication 仅做轻量应用初始化，删除 container 字段和 getContainer()；不能在 onCreate() 创建 AppContainer。
4. MatrixAgentService.onDestroy() 不调用 AppContainer.shutdown()。Provider 或下载服务可能仍在同一进程内使用容器；真实进程回收由系统释放资源。shutdownForProcessExit 仅用于受控车机生命周期、集成测试或明确的整个进程退出。
5. Host 中的容器一旦 shutdown 不可再次复用；测试必须先结束进程或明确创建新的测试进程，避免返回已关闭线程池。

### 4.2 三种合法启动顺序

| 首个组件 | 正确行为 |
|---|---|
| Settings 先 QUERY Provider | Provider.onCreate() 调 Host.get()；只读状态可用，配置状态从持久化记录读取。 |
| Settings 先 bind Service | Service.onCreate() 调 Host.get()，Stub 可用。 |
| AIDL 先启动下载 | Service 通过内部 DownloadCoordinator 启动私有 DownloadService；DownloadService 再调 Host.get() 得到同一实例。 |

任何顺序下禁止服务“持有唯一容器”这种假设；真正的唯一所有者是 :agent 进程内的 Host。

### 4.3 Manifest 基线

以下为关键属性，最终类名按实际包路径替换：

~~~xml
<permission android:name="com.matrix.agent.permission.USE_AGENT"
    android:protectionLevel="signature" />
<permission android:name="com.matrix.agent.permission.READ_SETTINGS"
    android:protectionLevel="signature" />
<permission android:name="com.matrix.agent.permission.WRITE_SETTINGS"
    android:protectionLevel="signature" />

<service
    android:name=".service.MatrixAgentService"
    android:exported="true"
    android:process=":agent"
    android:permission="com.matrix.agent.permission.USE_AGENT" />

<service
    android:name=".data.download.DownloadService"
    android:exported="false"
    android:process=":agent"
    android:foregroundServiceType="dataSync" />

<provider
    android:name=".provider.MatrixSettingsProvider"
    android:authorities="com.matrix.agent.settings"
    android:exported="true"
    android:process=":agent"
    android:readPermission="com.matrix.agent.permission.READ_SETTINGS"
    android:writePermission="com.matrix.agent.permission.WRITE_SETTINGS"
    android:grantUriPermissions="false"
    android:directBootAware="false" />
~~~

还必须声明 RECEIVE_BOOT_COMPLETED（若启用预热）、FOREGROUND_SERVICE、FOREGROUND_SERVICE_DATA_SYNC 和对应系统版本要求的通知权限。Provider 与 Runtime 依赖用户加密存储和 AndroidKeyStore，因此 directBootAware 必须保持 false；用户解锁前返回明确的 USER_LOCKED，而不是创建降级的明文状态。

### 4.4 存活策略与恢复

- 默认启动路径是受信任客户端 bind；不能把 BootReceiver 当作进程永生保证。
- 产品确有“无客户端仍可响应”的需求时，必须在目标 AAOS/targetSdk 上定义合法的前台服务类型、通知文案和产品级豁免。未完成该验证前，BootReceiver 只做解锁后的预热，不应强行启动长期 FGS。
- 执行持续时间较长的请求，如产品策略要求，可在执行期提升 MatrixAgentService 为前台服务，并在终态后降级；失败时向调用端返回 FGS_NOT_ALLOWED，不能后台静默继续。
- 模型下载始终由 DownloadService 的 dataSync FGS 负责。若 startForeground 失败，下载不启动并返回 FOREGROUND_START_DENIED；不能像当前实现一样在没有前台资格时继续后台线程。
- 进程死亡后：Host 重建、读取配置状态、恢复未完成下载；在途 Agent 请求的 callback 不会恢复。调用端以 requestId 查询既有审计终态；查不到时展示“服务已重启，请重试”，不得推断成功。

---

## 5. 身份、座舱分区与数据隔离

### 5.1 采用的多用户模型

本期采用“每个 Android user 一个 Agent Runtime”，不使用 android:singleUser：

- 同一个 APK 在不同 Android user 下具有独立 :agent 进程、filesDir、SharedPreferences、Keystore alias 命名空间和 SQLCipher DB。
- Settings 与语音入口只操作其自身 Android user 的 Runtime；不存在跨 user 代理调用。
- occupant zone 不是 Android user 的替代品。它是同一 user 范围内进一步限定会话、记忆、审计和请求所有权的第二维度。

若未来产品需要 system user 代表全部座舱 user，必须另开设计：包括跨用户 bind 权限、用户切换、密钥存放、数据库 schema 和管理员审计。不能在本方案上隐式扩展。

### 5.2 CallerIdentityResolver

每个 AIDL 和 Provider 写操作都必须在 Binder 线程、clearCallingIdentity 之前执行：

1. 读取 Binder.getCallingUid()，检查权限和受信任 UID。
2. 从 UID 得到 Android user。
3. 使用 CarOccupantZoneManager 将该 user 映射到当前活动 occupant zone。
4. 构造不可变 IdentityScope：androidUserId、zoneId、policyActor、inputSource、callerUid。
5. 仅将 IdentityScope 投递给业务 executor。

映射失败、用户不活跃、zone 不唯一或调用方权限不足时，返回 USER_NOT_ACTIVE 或 ZONE_UNRESOLVED；绝不默认降级为主驾。客户端传入的 actor 只能是显示/输入来源提示，服务端不得用它决定用户、zone 或策略角色。

### 5.3 运行时需要的代码改造

现有 AgentRequest 的 requestId 由构造器随机生成，ActorUsers 由 Actor 映射到 demo-driver/demo-passenger，AgentRuntimeRepository.clearUserData() 又同时清两个 demo 域。这不能直接对外暴露。实施时必须：

1. 在 AgentRequest 中增加由服务端生成的 requestId 和 principalUserId 字段；Builder 只能接受 RuntimeRequestController 已验证的值。
2. 将 ActorUsers.userIdOf(request) 的 demo 映射替换为 request.principalUserId；生产代码中不再出现 demo-driver、demo-passenger。
3. 将 AgentRuntimeRepository.execute 改为接收 RuntimeInvocation（文本、IdentityScope、requestId、CancellationToken），由它创建完整 AgentRequest。
4. sessionId、SteerMailbox key、MemoryScope、AuditRepository 查询/删除和所有持久化写入必须使用同一个 principalUserId + zoneId。
5. 将 clearUserData() 改为 clearUserData(IdentityScope)。它只取消、推进 epoch、清空 Session/Steer、删除该 scope 的轨迹、会话、记忆和审计；管理员全量清除需要独立权限和独立 API，首期不提供。

这部分是安全正确性的前置条件，不能以“原有 core 不动”为由跳过。

---

## 6. 安全模型

### 6.1 授权原则

签名权限是第一层，不是唯一层。仅使用 platform 签名会让其他 platform-signed App 同样满足 signature 权限，不能等价为“只有 Settings”。

量产前必须满足其一：

1. MatrixAgent 与 Settings 使用仅授予这两个产品包的专用签名证书；或
2. 通过受控系统 Broker 以专用 UID 暴露接口，且该 UID 不与其他应用共享。

在此基础上，服务端仍维护只读的受信任 UID 集合并拒绝 shared UID/未知 package。若当前产品镜像无法满足该前提，Service 和 Provider 不得 exported，迁移应停止在同 APK Debug 形态。

### 6.2 权限与调用面

| 调用面 | 权限 | 额外限制 |
|---|---|---|
| IMatrixAgentService | USE_AGENT | 受信任 UID、IdentityScope 可解析、每个方法按 scope 校验 requestId 所有权。 |
| Provider QUERY | READ_SETTINGS | 仅允许公开列，不返回密钥。 |
| Provider call(apply_model_config) | WRITE_SETTINGS | 受信任 UID、CAS revision、输入字段白名单。 |
| 清用户数据 | USE_AGENT | 只允许清调用者的 IdentityScope；UI 必须二次确认。 |
| 私有 DownloadService | 不导出 | 只能由 :agent 内部显式启动。 |

Provider 在任何查询、insert、update、delete、call 前都执行相同的 UID 授权；不得在身份清除后再检查权限。

### 6.3 密钥、日志与错误

- model_config 的 QUERY 永远不返回 apiKey、密文、IV、Keystore alias 或可逆提示，只返回 api_key_configured 布尔值。
- Settings 编辑页面只显示“已配置”；修改时提交完整替换值，不支持读取回填。
- apply_model_config 的密钥只存在本次 Binder 参数和应用线程的短暂对象中；不写 Logcat、通知、审计、异常 message 或 Provider Cursor。
- SecureModelConfigStore 保持 Keystore AES-GCM 加密，但删除当前包含 API Key 长度/配置全量字段的调试日志，所有异常用稳定错误码映射给 UI。
- 端点、模型名、错误摘要也按最小披露原则返回；服务端日志只记录 revision、状态码、耗时和调用 UID，不记录 prompt、响应、密钥或完整 URL。

---

## 7. AIDL 操作面

### 7.1 契约与版本规则

agent-contract 定义 API_VERSION。客户端 bind 后先调用 getApiVersion()；主版本不兼容则 Settings 禁用操作并显示“需升级 Agent 服务”。新增字段只能追加且必须有默认值，删除/改变已有字段语义只能提升主版本。

公共接口不得使用 Bundle、Map、自由字符串 type/payload，也不得返回内部 AgentOutcome、Trajectory、AuditEvent 或 DAO 实体。

~~~aidl
interface IMatrixAgentService {
    int getApiVersion();

    SubmitResult submit(in AgentSubmitRequest request, in IAgentCallback callback);
    OperationResult cancel(String requestId);
    OperationResult steer(String requestId, in SteerCommand command);
    AgentState getState();
    OutcomeSummary getOutcome(String requestId);

    DownloadCommandResult startDownload(in DownloadRequest request);
    OperationResult cancelDownload(String modelId);
    OperationResult removeDownloadedModel(String modelId);

    ClearDataResult clearMyData();

    void registerObserver(in IAgentObserver observer);
    void unregisterObserver(in IAgentObserver observer);
}

oneway interface IAgentCallback {
    void onOutcome(in OutcomeSummary outcome);
}

oneway interface IAgentObserver {
    void onRuntimeStateChanged(in AgentState state);
    void onDownloadChanged(in DownloadStatus status);
}
~~~

### 7.2 Parcelable 语义

| 类型 | 必要字段与限制 |
|---|---|
| AgentSubmitRequest | text、inputSource、languageTag、clientRequestToken。文本长度由服务端 token/字符上限检查；不含 userId、zone、actor。 |
| SubmitResult | requestId、status、errorCode。仅表示已接受/已拒绝，不表示任务完成。 |
| SteerCommand | 有限 enum：REPROMPT、DEFER、CANCEL_TOOL；每种有独立受限字段，不允许任意工具名或 JSON payload。 |
| OutcomeSummary | requestId、terminalState、safeMessage、completedAt。消息截断并脱敏；不含轨迹、原始模型回复和审计详情。 |
| AgentState | serviceState、activeModelDisplayName、configRevision、configApplyState、memoryDegraded、activeRequestCount。 |
| DownloadRequest | modelId。服务端从受信任市场目录解析下载地址、文件大小、哈希；客户端不能提交 URL、路径或 SHA。 |
| DownloadStatus | modelId、state、bytesDownloaded、totalBytes、safeError、updatedAt。 |
| OperationResult / ClearDataResult | 状态码、稳定错误码、可显示但已脱敏的消息；清数据结果包含当前 scope 的删除统计。 |

所有同步方法必须在极短时间内完成校验或入队；Agent 执行、模型加载、下载和数据库 I/O 都不得在 Binder 线程执行。

### 7.3 请求状态机与回调

~~~text
ACCEPTED → QUEUED → RUNNING → SUCCEEDED
                          ├→ FAILED
                          ├→ CANCELLED
                          └→ EXECUTION_UNKNOWN

进程死亡：客户端回调失效；重连后从 AuditRepository 按 IdentityScope + requestId 查询终态。
查不到终态：返回 PROCESS_RESTARTED，不得返回成功。
~~~

- RuntimeRequestController 在 submit 时生成 requestId、创建 CancellationToken、记录 in-flight 所有权并投递 executor。
- cancel/steer/getOutcome 必须先检查 requestId 是否属于调用者的 IdentityScope，再执行；不能因为 UUID 猜中而越权。
- callback 用 oneway 发送，按 Binder death recipient 自动移除。callback 死亡不自动取消任务，客户端仍可通过 getOutcome 取安全摘要。
- 回调只发送终态；下载与服务状态走 IAgentObserver。下载进度仅在百分比变化、状态变化或至少 250 ms 后发送，防止 Binder 洪泛。
- getOutcome 优先用当前 in-flight 状态，终态从已有 AuditRepository 的 scope 查询构造摘要；数据库降级时返回 OUTCOME_UNAVAILABLE。

---

## 8. ContentProvider 配置与状态面

### 8.1 固定 URI

authority 固定为 com.matrix.agent.settings。

| URI | 支持操作 | 返回/作用 |
|---|---|---|
| /presets | QUERY | 内置模型 Provider 预设，只读。 |
| /model_config | QUERY | 脱敏配置视图和 apply 状态，只允许一行。 |
| /downloads | QUERY | 下载状态列表，不含本地绝对路径。 |
| /runtime_state | QUERY | 与 AIDL AgentState 一致的只读快照，供 Settings 恢复页面。 |
| Provider.call(apply_model_config) | CALL | 提交配置切换。 |
| Provider.call(retry_model_config) | CALL | 仅重试当前 FAILED revision。 |

不提供 /memory 的 UPDATE/DELETE。清数据是具有用户归属和取消语义的操作，必须经 AIDL clearMyData()。

### 8.2 model_config 列与并发控制

QUERY 只返回：

| 列 | 含义 |
|---|---|
| desired_revision | 最近一次提交的配置 revision。 |
| active_revision | 当前运行 Gateway 对应 revision；无 Gateway 时为 0。 |
| apply_state | APPLYING、APPLIED、FAILED、NO_CONFIG。 |
| provider_id、display_name、protocol、endpoint、model、planner_mode | 脱敏的非密钥配置。 |
| api_key_configured | 是否已有密钥，永不返回具体值。 |
| last_error_code、last_error_message、updated_at | 已脱敏的失败信息与更新时间。 |

apply_model_config 的参数必须包含 expected_revision 及完整替换配置。expected_revision 与 desired_revision 不一致时返回 CONFLICT，Settings 刷新后让用户确认；禁止“最后写入者无条件覆盖”。

### 8.3 配置应用状态机

ModelConfigurationController 是唯一能读写 SecureModelConfigStore 和切换 Gateway 的类。Provider.call 直接调用它的内部方法，不调用公共 AIDL，也不依赖 ContentObserver 轮询。

~~~text
Settings call(apply, expectedRevision, config)
  ├─ 授权、字段校验、revision CAS 失败 → CONFLICT / INVALID_ARGUMENT
  ├─ 原子保存 desired config（API Key 仅保存密文）+ state=APPLYING
  ├─ notifyChange(/model_config, /runtime_state)
  └─ 将 revision 投递到单线程配置 executor，立即返回 ACCEPTED

配置 executor
  ├─ 创建 Gateway、构建 IntentClassifier、校验模型文件/能力
  ├─ revision 已过期 → 释放新 Gateway，不改状态
  ├─ 成功 → RuntimeRepository 原子切 Gateway + classifier
  │          → 保存 active_revision=revision, state=APPLIED
  └─ 失败 → 保留旧 Gateway
             → 保存 state=FAILED + 脱敏错误码

每次状态变化 → Provider notifyChange + IAgentObserver 状态事件
~~~

持久化顺序必须是“先保存 desired/APPLYING，再成功切换 Runtime，最后标记 APPLIED”。进程在任意一步死亡都不会把未运行的配置标记为已生效：重启后如果是 APPLYING，使用该 revision 重新应用；如果是 FAILED，保留 Demo/旧 Gateway，等待用户 retry 或新提交。

端侧模型加载不可中断时，新的 revision 只能使旧加载结果失效，不能强杀 native 线程。过期加载完成后必须关闭/退役其 Gateway，绝不能覆盖最新 revision。

### 8.4 存储迁移

SecureModelConfigStore 保留当前加密字段，扩展以下元数据并在一次 SharedPreferences commit 中写入：schema_version、desired_revision、active_revision、apply_state、last_error_code、last_error_message、updated_at。

首次升级时：

1. 检测旧的平铺配置字段。
2. 将它们迁为 desired_revision=1、apply_state=APPLYING，不标记 APPLIED。
3. Runtime 首次启动尝试加载；成功后标记 APPLIED，失败标记 FAILED 并保留旧密文，供用户修正。

旧字段至少保留一个稳定版本，便于同版本回滚读取；不得在迁移中删除密钥或覆盖用户配置。每次写入必须检查 commit 返回值，失败返回 STORAGE_UNAVAILABLE。

---

## 9. 下载实现

### 9.1 责任划分

| 组件 | 责任 |
|---|---|
| MatrixAgentService Stub | 授权请求、验证 modelId、返回操作结果。 |
| DownloadCoordinator | 对同一 modelId 去重、管理任务、启动/停止私有 FGS、发布状态。 |
| DownloadService | 仅负责 dataSync 前台通知和调用 Coordinator；不接收外部 Intent。 |
| ModelDownloadManager | 保持现有断点续传、校验、文件操作能力。 |
| RuntimeEventDispatcher | DAO 状态改变时发送 IAgentObserver，并对 /downloads 调用 notifyChange。 |
| Settings | QUERY /downloads 展示列表，使用 Observer 或 ContentObserver 刷新；不直接使用 DAO。 |

### 9.2 下载规则

- startDownload 只接受 catalog 中的 modelId；服务端决定 repo、文件名、哈希和目标目录。
- 同一 modelId 已下载返回 ALREADY_COMPLETED；下载中返回 ALREADY_RUNNING；失败/暂停可显式重试。
- removeDownloadedModel 先验证 modelId，无在途下载，且该模型不是 active_revision 所引用模型；否则拒绝。文件路径由服务端拼装并校验目录边界，绝不采信客户端路径。
- 每次 DAO 终态/进度变化都经 DownloadCoordinator 发布；Room 写入本身不会自动触发 Provider 通知。
- 进程重启时 Coordinator 根据 DAO 和临时文件恢复可恢复下载。校验失败必须标记 FAILED，不得把残缺文件列为已安装。
- DownloadService.onDestroy 不得留下脱离生命周期的 daemon executor。下载任务由 Coordinator 明确取消、标记为可恢复或由系统批准的重启路径续跑。

---

## 10. Settings 与 Debug 客户端实现

### 10.1 Settings 页面流程

| 页面 | 读取 | 写入/操作 | 错误处理 |
|---|---|---|---|
| 模型 API | QUERY /presets、/model_config、/runtime_state | call(apply_model_config) | 显示 APPLYING/FAILED；revision 冲突时刷新后再确认；密钥只显示已配置。 |
| 模型下载 | QUERY /downloads | AIDL start/cancel/remove | 注册 ContentObserver 与 IAgentObserver；服务断开时保留最后快照并可重连。 |
| 运行状态 | QUERY /runtime_state 或 AIDL getState | 无 | 区分未配置、应用中、数据库降级、服务不可用。 |
| 数据管理 | AIDL getState | AIDL clearMyData | 二次确认，明确“仅当前 Android user 和当前座舱 zone”；展示部分审计清理失败。 |

Settings 的 AgentClient 必须使用显式 ComponentName bind，生命周期内注册/注销 observer，处理 binder death，并在重新连接后重新 QUERY Provider。不得把 AIDL binder、API Key 或 Activity 引用放进静态单例。

### 10.2 DebugActivity

DebugActivity 采用与 Settings 相同 AgentClient/Provider 客户端路径。迁移完成的验收条件是量产代码中不存在 MatrixAgentApplication.getContainer()、new AppContainer() 或直接 DAO/Manager 注入到 UI 的调用点。

---

## 11. 分阶段实施计划

每一步必须在上一步验收通过后进入下一步；不要并行把 UI、权限和运行时一起替换。

| 阶段 | 实现内容 | 完成标准 |
|---|---|---|
| 0. 契约与签名决策 | 建 agent-contract；确认专用签名/UID 方案、每 user 运行时模型、Settings 包名。 | 安全前提有镜像构建验证；接口版本和 URI 固化。 |
| 1. 容器收敛 | 实现 AgentRuntimeHost；Application 去掉容器；Service、Provider 骨架、DownloadService 均迁 :agent。 | Provider-first、Service-first、Download-first 测试证明单进程单容器。 |
| 2. 身份贯通 | 实现 CallerIdentityResolver、IdentityScope；改 AgentRequest、ActorUsers、RuntimeRepository 和 clearUserData 的 scope 参数。 | 生产代码不含 demo 身份常量；跨 user/zone 请求和清理被拒绝或严格隔离。 |
| 3. AIDL RuntimeFacade | 实现队列、requestId、取消、typed steer、终态摘要、callback death、AgentClient；先让 DebugActivity 切换到新接口。 | DebugActivity 可 submit/cancel/steer/重连查询，且没有直接容器访问。 |
| 4. 配置事务 | 实现 ModelConfigurationController、Store metadata 迁移、Provider QUERY/CALL、revision 状态机和 notifyChange。 | 成功、失败、冲突、过期端侧加载和进程重启全部有测试。 |
| 5. 下载收敛 | 引入 DownloadCoordinator，私有 FGS 迁 :agent，AIDL 下载命令、Provider /downloads 事件。 | FGS 被拒绝不后台下载；断点恢复、去重、删除 active 模型拒绝。 |
| 6. Settings UI | 迁移四个页面，移除对 MatrixAgent 实现包的依赖。 | 真机 Settings 完成配置、应用、下载、清数据；密钥不可读回。 |
| 7. 发布收尾 | DebugActivity 仅 debug 变体；加 feature gate、指标、回滚说明；删除旧 UI 依赖。 | release APK 无 launcher Agent UI、无默认进程 AppContainer。 |

### 11.1 变更顺序中的兼容要求

- 阶段 1 和阶段 3 必须同一变更集完成，否则旧 UI 调 getContainer() 会崩溃。
- 阶段 2 在任何 exported 接口开放前完成；不能先暴露 AIDL 再补 user/zone。
- 配置 Provider 初期可以只读；写操作必须等阶段 4 状态机完成后才开放。
- 数据库 schema 如需新增字段/表，必须提供向前迁移和经过产品批准的 OTA 回滚策略。首期 getOutcome 优先复用现有 AuditRepository，避免为了 UI 状态仓促引入不可回退的 DB 升级。

---

## 12. 测试与验收矩阵

| 层级 | 必测场景 | 验收 |
|---|---|---|
| JVM | Host 并发 get、配置 revision 状态机、过期加载丢弃、IdentityScope 授权、typed steer、下载去重。 | 无重复容器、无未授权 scope。 |
| 既有回归 | AgentEngine、Policy、Memory、Audit、下载现有测试。 | 修改身份传播后所有既有测试更新为显式测试 identity，整体通过。 |
| Instrumented 生命周期 | Provider-first、Service-first、Download-first；Service 重建；进程 kill 后重新 bind。 | 单实例、配置/下载恢复、在途请求不误报成功。 |
| AIDL 契约 | 版本不匹配、submit/cancel/steer、callback death、binder death、requestId 越权。 | 只返回安全错误码；跨 scope 均拒绝。 |
| Provider 契约 | 无权限、未知 UID、密钥脱敏、CAS 冲突、apply 成功/失败、notifyChange。 | 无 Cursor/日志泄漏 API Key；配置状态不分裂。 |
| 多用户/座舱 | 两个 Android user、同 user 多 zone、用户未解锁、zone 解析失败、clearMyData。 | 数据、会话、审计、记忆和清理范围严格隔离。 |
| 下载 | FGS 启动失败、重复下载、暂停/恢复、哈希失败、删除 active 模型、重启续传。 | 不产生后台失控线程；状态一致。 |
| 真机/系统镜像 | 专用签名、Settings bind/Provider、通知权限、Boot 预热和目标 targetSdk 限制。 | 在目标 AAOS 镜像实测通过，不以模拟器结果替代。 |

发布前额外执行：

1. 搜索 release 源码，确认不存在默认 UI 进程直接 new AppContainer、getContainer()、demo-driver 或 demo-passenger。
2. 抓取 Provider Cursor、AIDL 返回和 Logcat，确认 API Key、密文、完整 prompt、模型响应和绝对模型路径均不存在。
3. 用无权限测试 APK 验证 Service bind、Provider query/call 和显式 DownloadService 启动均失败。
4. 连续执行“配置 A 应用中 → 配置 B → A 的端侧加载晚到”场景，最终 Gateway 必须是 B。

---

## 13. 观测、发布与回滚

### 13.1 最小观测

只记录以下无敏感指标：Host 初始化次数、服务 bind 次数、配置 revision/状态/耗时、下载状态转换、Binder 回调丢失数、数据库降级状态、错误码计数。日志统一使用 requestId 哈希或截短值，不记录用户输入和密钥。

### 13.2 发布门控

- 先在 debug/eng 镜像上启用新路径，再在受控产品配置中开启 Settings UI。
- 门控只决定客户端是否展示新入口；一旦某 Android user 使用新 Host，旧 UI 不得同时创建 AppContainer。
- 旧平铺配置字段保留一版以支持配置读取回退。若后续引入不可逆 Room schema，必须在 OTA 方案中明确禁止旧二进制回滚或提供正式 downgrade/数据迁移策略。

### 13.3 故障降级

| 故障 | 用户可见结果 | 服务端行为 |
|---|---|---|
| KeyStore/SQLCipher 不可用 | “本地存储不可用，暂不能应用/下载” | 不回退到明文存储；不接受依赖持久化的写操作。 |
| 模型 Gateway 加载失败 | “配置未生效，可修正或重试” | 保留旧 Gateway；状态 FAILED。 |
| Provider/Service 不可用 | “Agent 服务正在恢复” | 客户端重连并刷新 Provider，不缓存成功结论。 |
| FGS 被系统拒绝 | “下载未开始” | 不启动后台下载任务。 |
| zone 无法解析 | “当前用户未关联有效座舱” | 拒绝请求和清数据，不回退主驾。 |

---

## 14. 实施完成定义

以下条件全部满足才可将 MatrixAgent 标记为“无头服务化完成”：

1. 所有运行时组件在 :agent 进程通过同一 AgentRuntimeHost 使用唯一 AppContainer。
2. Settings 和 DebugActivity 均不再直接引用 AppContainer、DAO、ModelGateway 或下载管理器。
3. AIDL/Provider 都使用专用签名或等效专用 UID 信任链，并完成无权限/共享 UID 拒绝测试。
4. 身份由 Binder 解析并贯通 AgentRequest、Session、Memory、Audit、取消、steer、清数据和结果查询。
5. model_config 具有 revision、APPLYING/APPLIED/FAILED 状态机，API Key 永不可读回。
6. 下载在同一进程由受控 Coordinator/FGS 执行，Provider 和 AIDL 状态实时一致。
7. 生命周期、配置并发、进程恢复、多用户/多 zone、安全和真实 AAOS 系统镜像测试全部通过。
