# MatrixAgent V0.5.0 Code Review

**版本基线**:`V0.5.0` (versionCode=7, versionName=0.5.0)
**测试基线**:289(V0.4.3)→ **375**(V0.5.0 Phase 1 + 五轮评审 P1/P2 修复完成),`./gradlew clean testDebugUnitTest` 全绿(375 passed / 0 failures / 0 errors)
**编译**:`./gradlew assembleDebug` 通过
**作用域**:Memory + 持久化先行(C+D 模块),5 个 Stage + 五轮评审 P1/P2 修复,完全向后兼容 V0.4.3

---

## 1. 评审目标

V0.5.0 Phase 1 是 Runtime 数据层的奠基版本——把 V0.4.3 内存态的 Trajectory / SessionContext / MemoryStore 升级为**结构化 + 可加密 + 可审计**的持久化形态,同时为 V0.5.1/V0.5.2 的深化(token 预算真切换、上下文压缩、Gemini 原生 Tool Calling、流式响应)提供稳定契约。

评审关注:
1. **5 个 AgentEngine 出口点的 Audit 持久化覆盖**——任何终态(session lock timeout / InterruptedException / pre-loop terminal / 主出口 / terminalOutcome 方法)都必须落库。
2. **四层 Memory 抽象 + 双维度隔离的稳定性**——V0.5.0 仅 PREFERENCE 层有数据,但 WORKING / EPISODIC / SEMANTIC 的接口必须稳定,V0.5.1 接 SessionHistoryDao / embedding 时不动 caller。
3. **向后兼容契约**——289 V0.4.3 测试 0 回归;旧构造器签名 / userId 字面量 / SharedPreferences 主键 / `arbitrationKey = "demo-vehicle"` 全部不变。
4. **fail-open 安全边界**——Audit 持久化失败 / SQLCipher 不可用 / AndroidKeyStore 不可用 / jtokkit 初始化失败,均退化 Noop / fallback,不阻塞主任务路径。
5. **V0.5.1+ 衔接点**——PromptBuilder 骨架不强制使用、Tokenizer 接口 + CharFallbackTokenizer 默认装配、AgentBudget token getter 暴露但主路径不切换——这些能力在 V0.5.1 切换时不破坏 V0.5.0 已落地的契约测试。
6. **评审 P1/P2 修复**(V0.5.0 Phase 1 评审后追加,见 §8):
   - **P1.1 KeyStore 失败 → Noop 而非明文 DB**:AndroidKeyStoreMasterKeyProvider.getPassphrase 抛 IllegalStateException,MatrixDatabase.getInstance 不静默回退明文 builder,审计数据绝不悄然落明文。
   - **P1.2 memoryRecaller 异常降级而非崩溃任务**:AgentEngine.buildSystemPrompt 把 recall 包 try/catch,异常降级到 base prompt,AgentEngine.execute 仍 SUCCEEDED——记忆是增强能力,不能成为车机任务入口的单点故障。
   - **P1.3 Audit 改同步阻塞而非异步**:RoomAuditRepository 移除 Executor,persist 同步调 doPersist,任务返回后立即 query 无竞态;与"同步阻塞 insert、所有终态可回放"承诺对齐。
   - **P2.1 双维度隔离边界澄清**:V0.5.0 真正生效的隔离只有 userId,zone 是软标记;真正 zone 维度隔离等 V0.5.1 Room MemoryRecordEntity 主键接入。
   - **P2.2 测试统计口径自洽**:评审前 +64(289 → 353),首轮修复后 +70(289 → 359),第二轮修复后 +74(289 → 363)。
7. **第二轮评审 P2 修复**(V0.5.0 Phase 1 第二轮评审后追加,见 §9):
   - **P2.1 DAO 查询接口加 zone 隔离**:MemoryRecordDao / SessionHistoryDao 查询接口收紧为 (userId, zone, layer/limit);V0.5.1 召回算法接入时直接调,不允许 caller 侧过滤。
   - **P2.2 AuditRepository.queryBySession 加访问域**:接口签名 `queryBySession(userId, zone, sessionId, limit)`,TrajectoryDao 同步加 WHERE 三元组;queryByRequest 保留(requestId UUID)。
   - **P2.3 Entity/DAO javadoc 对齐实际状态**:MemoryRecordEntity "V0.5.0 双写" / AuditEventEntity "V0.5.0 只写 TERMINAL" 改为"预建表 / V0.5.1 接入",避免按注释误判能力已落地。
   - **P2.4 passphrase commit() 同步落盘 + 内存清零**:首次生成 `commit()` 消除断电窗口,byte[] 转 char[] 后立即 `Arrays.fill` 清零减少堆驻留。
8. **第三轮评审 P1 修复**(V0.5.0 Phase 1 第三轮评审后追加,见 §10):
   - **P1 Repository 编排层兜底 audit**:AgentEngine 5 个出口已写 audit,但 APK 真实入口还有 Repository 自构造的 TIMED_OUT/CANCELLED outcome + Scheduler 内部生成的 PREEMPTED/TIMED_OUT/CANCELLED 终态——这些路径不进 Engine,Engine audit 接不到。修复:`AgentRuntimeRepository` 新增 12 参构造器(末参 AuditRepository),`execute()` 在 `return outcome` 前统一 `auditRepository.persist`;Engine 内部 audit 与 Repository 兜底 audit 短期并存(requestId 幂等键,TrajectoryDao `@Insert(REPLACE)` 让 Repository 后写覆盖 Engine 版本)。
9. **第四轮评审 P1 修复**(V0.5.0 Phase 1 第四轮评审后追加,见 §11):
   - **P1 抢占 outcome 与 trajectory 终态语义一致**:Scheduler.remapIfPreempted 把外层 outcome 改为 PREEMPTED 时只重建 AgentOutcome,直接复用原 Trajectory(其 stopReason 还是 Engine 写的 CANCELLED);Repository 兜底 audit 落库后数据库结构化列=PREEMPTED 但 trajectoryJson.trajectory.stopReason=CANCELLED,列表页"被抢占"但回放解码"已取消"。修复:Trajectory 加 rewriteStopReason 受控终态重映射方法(仅允许已 finish 状态调用),remapIfPreempted 在 new AgentOutcome 前同步覆盖 trajectory.stopReason。
10. **第五轮评审 P1 修复**(V0.5.0 Phase 1 第五轮评审后追加,见 §12):
    - **P1 写操作 timeout / cancel 语义边界**:ToolExecutor 对所有 capability 一视同仁,超时/取消时 future.cancel(true) + TIMED_OUT/CANCELLED。但 CapabilityProvider 只有同步 execute(),Java 中断不保证撤销已下发的 IPC 命令——Provider 可能在 Runtime 返回"已取消"后继续写车控,Agent Trajectory 与真实车辆状态不一致。修复:ToolResult.Status 加 EXECUTION_UNKNOWN;CapabilityProvider 加 isAbortable/abortIfSupported/queryCommandState 三个 default 方法(默认退化);ToolExecutor 按 isWriteOperation 区分,写操作超时/取消返回 EXECUTION_UNKNOWN。

---

## 2. 5 个 Stage 实现

### Stage 1:四层 Memory 抽象 + 双维度隔离 + 召回 API(纯内存)

**目录**:`app/src/main/java/com/matrix/agent/core/memory/`

| 文件 | 职责 |
|---|---|
| `MemoryLayer.java` | enum:`WORKING("working")` / `EPISODIC("episodic")` / `SEMANTIC("semantic")` / `PREFERENCE("preference")` |
| `MemoryScope.java` | 不可变 `(userId, zone)`;`ofLegacy(userId)` zone=GLOBAL;`storageKey(layer, key)` = `userId + "@" + zone.wireValue() + "#" + layer.wireValue() + "/" + key` |
| `MemorySnippet.java` | 不可变 `layer / scope / key / value / score / capturedAtMillis / sourceSessionId`;简化工厂 `of(layer, scope, key, value)` 默认 score=1.0 |
| `MemoryRecaller.java` | 接口:`List<MemorySnippet> recall(MemoryScope scope, String sessionId, String userText, int maxItems)` |
| `MemoryRouter.java` | 实现:4 个 source 串行按 layer 优先级合并截断,unmodifiableList |
| `WorkingMemorySource.java` + `SessionContextWorkingMemory.java` | 包装 `SessionManager.snapshotTurns()` 转 snippet |
| `EpisodicMemorySource.java` + `EmptyEpisodicMemorySource.java` | V0.5.0 占位返回空 |
| `SemanticMemorySource.java` + `EmptySemanticMemorySource.java` | V0.5.0 占位返回空 |
| `PreferenceMemorySource.java` + `LegacyPreferenceMemorySource.java` | 包装 V0.4.3 `MemoryStore.getAllPreferences(userId)` |

**测试**:`MemoryScopeTest` / `MemoryRouterTest` / `PreferenceMemorySourceBridgeTest`(21 测试)

**关键决策**:
- `MemoryScope.storageKey` 格式稳定,V0.5.1 持久化层依赖此格式做 Migration
- `PreferenceMemorySource` 不走 `storageKey`,直接调 V0.4.3 `getAllPreferences(userId)`,SharedPreferences 主键不变
- `MemoryRecaller.recall(scope, sessionId, userText, maxItems)` 4 参签名:`sessionId` 给 Working 层用,`userText` 给 Episodic/Semantic 层做相似度(V0.5.1 接 embedding 时用)

---

### Stage 2:SQLCipher + Room 持久化骨架

**目录**:`app/src/main/java/com/matrix/agent/data/db/` + `data/audit/` + `platform/`

**主源文件**:

| 文件 | 职责 |
|---|---|
| `MatrixDatabase.java` | `@Database(entities={4}, version=1, exportSchema=true)`;`getInstance(context, keyProvider)` 单例 + `SupportFactory(byte[])` 注入 SQLCipher passphrase(char[] → ISO-8859-1 byte[] 转换);**评审 P1.1:keyProvider=null / passphrase=null / passphrase.length==0 / getPassphrase 抛异常时 getInstance 直接抛 IllegalStateException,绝不静默退化明文 DB** |
| `TrajectoryEntity.java` | `@PrimaryKey String requestId`;字段 sessionId/arbitrationKey/actor/zone/userId/startedMs/durationMs/iterationCount/totalToolCalls/successToolCalls/stopReason/finalState/trajectoryJson/createdAtMs;索引 idx_trajectory_session_started |
| `SessionHistoryEntity.java` | 复合主键 (userId, zone, sessionId, startedAtMillis) |
| `MemoryRecordEntity.java` | 复合主键 (userId, zone, layer, key);索引 idx_memory_user_layer |
| `AuditEventEntity.java` | `@PrimaryKey(autoGenerate=true) long id`;字段 requestId/type/actor/zone/happenedAtMs/payloadJson;索引 idx_audit_request;V0.5.0 仅写 TERMINAL 类型,V0.5.1 加 iteration 级 |
| 4 个 DAO | `@Dao interface`,方法签名返回纯 entity/List/void,不引 RxJava/Coroutines |
| `TrajectoryCodec.java` | `encode(AgentOutcome) → String` / `decodeTrajectory(String) → Trajectory`,org.json round-trip |
| `AuditRepository.java` | 接口:`persist(AgentOutcome, AgentRequest)` / `queryByRequest(requestId)` / `queryBySession(sessionId, limit)` |
| `AuditRecord.java` | 只读视图,字段与 TrajectoryEntity 对应 |
| `NoopAuditRepository.java` | INSTANCE 单例,JVM 测试 / 装配失败兜底 |
| `RoomAuditRepository.java` | **评审 P1.3 修复:移除 Executor 字段与构造参数,单参构造器 `(TrajectoryDao)`,persist 同步阻塞调 doPersist,任务返回后立即 query 无竞塔**;fail-open 不变,失败仅 log 不抛;V0.5.1 优化为 WAL + batch + 异步队列时再切回 Executor 模型,届时补 flush/shutdown 生命周期 + 队列满策略 + 真实异步测试 |
| `MasterKeyProvider.java` | 接口:`char[] getPassphrase()` / `String alias()` |
| `AndroidKeyStoreMasterKeyProvider.java` | 别名 `matrix_db_master_key`;AES-256-GCM + 12B IV + GCMParameterSpec(128, iv);复用 `SecureModelConfigStore.getOrCreateKey()` 模式;加密 32 字节随机 passphrase,密文落 SharedPreferences;**评审 P1.1:getPassphrase 抛 IllegalStateException 而非返回 null,让 AppContainer.createAuditRepositorySafely 明确退化 NoopAuditRepository** |

**依赖**(app/build.gradle):
```groovy
implementation 'androidx.room:room-runtime:2.7.0'
annotationProcessor 'androidx.room:room-compiler:2.7.0'
implementation 'net.zetetic:android-database-sqlcipher:4.5.4'
implementation 'androidx.sqlite:sqlite-framework:2.4.0'
implementation 'com.knuddels:jtokkit:1.1.0'  // Stage 5
```

**android block**:
```groovy
javaCompileOptions {
    annotationProcessorOptions {
        arguments += [
            "room.schemaLocation": "$projectDir/schemas".toString(),
            "room.incremental": "true"
        ]
    }
}
sourceSets { androidTest { java.srcDirs = [] } }  // V0.5.0 占位
packaging { resources { excludes += ['META-INF/*.kotlin_module'] } }
```

**测试**:`TrajectoryCodecTest`(5 测试,round-trip)+ `MatrixDatabaseKeyFailureContractTest`(4 测试,评审 P1.1 新增,keyProvider null / passphrase null / passphrase empty / passphrase throwing 四种 fail-closed 路径)+ `MemoryRecordDaoZoneIsolationContractTest`(3 测试,第二轮评审 P2.1 新增,fake DAO 验证查询条件包含 zone)+ `RoomAuditRepositoryContractTest`(8 测试,fake TrajectoryDao 验证字段写入 / query 等价 / fail-open / **评审 P1.3 同步契约 `persistIsSynchronousQuerySeesItImmediately`** / **第二轮评审 P2.2 访问域契约 `queryBySessionReturnsEmptyWhenZoneMismatch`**)— Stage 2 共 **20 测试**

**关键决策**:
- **V0.5.0 不引 androidTest**(避免 AGP test runner 配置成本),所有真 Room/SQLCipher 验证走 APK 手动验证;V0.5.1 一次性引入 `androidx.test.ext:junit:1.2.1` + `androidx.test:runner:1.6.2`
- **同步阻塞单条 insert**(V0.5.0 评审 P1.3 已对齐文档承诺)+ fail-open 接受丢失风险;V0.5.1 优化为 WAL + batch + 重试队列后改 fail-closed,届时补 flush/shutdown 生命周期 + 队列满策略 + 真实异步测试
- **Room annotationProcessor 不用 KSP**(pure Java + AGP 9.0.1 兼容)
- **不引 `androidx.security:security-crypto`**(长期 RC,与 minSdk 28 + Java 17 有兼容坑),凭据保护继续用现有 `SecureModelConfigStore` 模式
- **评审 P1.1**:KeyStore 不可用 / getPassphrase 失败时,MatrixDatabase.getInstance 抛 IllegalStateException;AppContainer.createAuditRepositorySafely catch 后退化 NoopAuditRepository,**绝不静默落明文 DB**(评审反馈:审计数据不能悄然落为明文)
- **第二轮评审 P2.1/P2.2**:DAO 查询接口强制 (userId, zone) 访问域——避免 V0.5.1 召回 / UI 回放层 caller 侧手动过滤的疏漏风险
- **第二轮评审 P2.3**:Entity/DAO javadoc 统一改为"预建表 / V0.5.1 接入"——V0.5.0 全项目无 MemoryRecordDao / AuditEventDao / SessionHistoryDao 的实际调用,避免按注释或 README 误判能力已落地
- **第二轮评审 P2.4**:首次生成 passphrase 用 `commit()` 同步落盘消除断电窗口(原 `.apply()` 异步,进程崩溃 → 下次启动重新生成 → 旧审计库不可读);byte[] 转 char[] 后立即 `Arrays.fill` 清零

---

### Stage 3:AgentEngine / LlmPlanner 接入 Memory + Audit

**修改文件**:

| 文件 | 改动点 |
|---|---|
| `core/agent/AgentEngine.java` | 新增字段 `auditRepository`(默认 NoopAuditRepository.INSTANCE)+ `memoryRecaller`(默认 null);**新增 13 参构造器**(auditRepository + memoryRecaller),旧 3 个构造器(L74/L82/L91)链式委托;`buildSystemPrompt` / `terminalOutcome` 从 static 改 instance;5 个出口点(session lock timeout L173 / InterruptedException L181 / pre-loop terminal L201 / 主出口 L535 / `terminalOutcome` 方法)统一调 `auditRepository.persist(outcome, request)`;`buildSystemPrompt` 末尾附加"已召回的 Memory"段(只 layer + key,不含 value,避免 prompt injection);**评审 P1.2:`memoryRecaller.recall` 包 try/catch,异常时 log `cause=` + 返回 base prompt,AgentEngine.execute 仍 SUCCEEDED——记忆是增强能力,不能成为车机任务入口的单点故障**;新常量 `RECALL_LIMIT_SYSTEM_PROMPT = 8` |
| `platform/LlmPlanner.java` | 字段类型 `ModelApiClient` → `LlmClient`;新增 `setMemoryRecaller` setter;`savedKeysFor(userId)` 拆 `preferenceKeysFor`(V0.4.3 路径,TreeSet 排序 key)+ `savedKeysFor`(附加非 PREFERENCE snippet,跳过 PREFERENCE 避免与 preferenceKeysFor 重复) |
| `platform/LlmClient.java` | 新增接口:`complete(ModelConfig, sys, user)` / `planWithTools(ModelConfig, sys, user, tools)` |
| `platform/ModelApiClient.java` | `implements LlmClient`,两个方法加 `@Override`,签名不变 |
| `platform/LlmModelGateway.java` | 新增 `setMemoryRecaller` 透传给内部 `legacy` LlmPlanner |
| `core/identity/ActorUsers.java` | 新增工具类:`userIdOf(Actor)` / `userIdOf(AgentRequest)`;字面量 `USER_DRIVER="demo-driver"` / `USER_PASSENGER="demo-passenger"` / `USER_GLOBAL="demo-global"`;Room TrajectoryEntity.userId 与 SharedPreferences 主键字面兼容 |
| `data/AgentRuntimeRepository.java` | **第三轮评审 P1:新增 12 参构造器**(末参 `AuditRepository auditRepository`),旧 3 个构造器(9 参无 budget / 10 参带 budget / 11 参带 intentClassifier)链式委托 `NoopAuditRepository.INSTANCE`(向后兼容 363 测试);`execute()` 在 `Log.i("[Repo] <- outcome ...")` 后、`return outcome;` 前统一调 `auditRepository.persist(outcome, request)`,覆盖所有终态——含 `TimeoutException` catch 分支自构造的 TIMED_OUT / `InterruptedException` catch 分支自构造的 CANCELLED / Scheduler 内部生成的 PREEMPTED / Engine 正常返回的 SUCCEEDED;构造器强制 `if (auditRepository == null) throw new IllegalArgumentException` |
| `data/ModelGatewayRepository.java` | 新增 5 参构造器(configStore, modelClient, registry, memoryStore, memoryRecaller);`createModelGateway` 内部把 memoryRecaller 透传给 LlmModelGateway.setMemoryRecaller;旧 3 参 / 4 参构造器保留向后兼容 |
| `app/AppContainer.java` | 装配 `MemoryRouter`(SessionContextWorkingMemory + EmptyEpisodicMemorySource + EmptySemanticMemorySource + LegacyPreferenceMemorySource);`createAuditRepositorySafely` fail-open 退化 Noop;engineFactory lambda 闭包变量引用 auditRepository + memoryRecaller 注入 AgentEngine 13 参构造器;**第三轮评审 P1:`AgentRuntimeRepository` 改用新 12 参构造器,显式传 `KeywordIntentClassifier.INSTANCE` + `auditRepository`(与 Engine factory 共享同一实例)**;新增 `getMemoryRecaller()` getter |

**测试**:`AgentEngineAuditSinkTest`(5 测试,覆盖 5 个出口点)+ `AgentEngineMemoryRecallerIntegrationTest`(5 测试,recaller=null/empty/non-empty/throwing/null-returning,**评审 P1.2:throwing 与 null-returning 均断言 SUCCEEDED + base prompt 文案 + 无"已召回的 Memory"段**)+ `LlmPlannerMemoryIntegrationTest`(3 测试,recaller=null/non-PREFERENCE/PREFERENCE-skip)+ **第三轮评审 P1:`AgentRuntimeRepositoryAuditCoverageTest`(3 测试,Repository future.get 超时 / 外部 interrupt / 主驾抢占副驾,断言兜底 audit 落库 + token cancel + finalState 正确)**— Stage 3 共 **16 测试**

**关键决策**:
- `terminalOutcome` 由 static 改 instance,V0.4.x 静态调用点保留(static bridge 暂未引入,因为 terminalOutcome 是 private 方法,外部测试不直接调,5 个出口点通过 AgentEngine.execute 间接覆盖)
- AgentEngine 5 个出口点都过 `auditRepository.persist`,**包括 catch 分支**(InterruptedException / session lock timeout)——这两条以前不发 audit,V0.5.0 后所有终态可回放
- `LlmClient` 接口抽出纯粹为测试可注入——LlmPlanner 主路径未变,ModelApiClient 行为不变

---

### Stage 4:PromptBuilder 骨架(模块化但不强制使用)

**目录**:`app/src/main/java/com/matrix/agent/core/prompt/`

| 文件 | 职责 |
|---|---|
| `PromptSegment.java` | 不可变 `(Type, text)`;Type:BASE / RECALLED_MEMORY / TOOL_LIST / ZONE_HINT;`estimateChars()` |
| `PromptBuilder.java` | 接口:`List<PromptSegment> buildSystemPrompt(AgentRequest, List<MemorySnippet>)` |
| `DefaultPromptBuilder.java` | 复用 V0.4.3 文案(`BASE_TEMPLATE` 与 AgentEngine.buildSystemPrompt 字面等价)+ 拼装 RECALLED_MEMORY 段;静态工具 `join(List<PromptSegment>)` |

**测试**:`DefaultPromptBuilderTest`(5 测试,空 memory / null / 多条 / zone 差异 / join)

**关键决策**:
- V0.5.0 不接入 AgentEngine 主路径——V0.4.3 的 `AgentEngine.buildSystemPrompt` / `LlmPlanner.savedKeysFor` 继续硬编码,与 DefaultPromptBuilder 行为等价
- V0.5.1 上下文压缩接入时,AgentEngine.buildSystemPrompt 改为委托 PromptBuilder,旧文案拆段
- V0.5.2 字段级 ToolSchemaView 接入时,TOOL_LIST 段从 capability 级改为字段级
- V0.5.2 Gemini 原生 Tool Calling 接入时,BASE 段简化(去掉"只输出 JSON"约束)

---

### Stage 5:jtokkit 依赖 + AgentBudget token API(V0.5.0 仍用 char 判断)

**目录**:`app/src/main/java/com/matrix/agent/core/token/`

| 文件 | 职责 |
|---|---|
| `Tokenizer.java` | 接口:`int count(String)` / `String encoding()` / `String encodeAndBack(String)` |
| `CharFallbackTokenizer.java` | 单例 INSTANCE;`count = text.length()`;`encodeAndBack = identity`;V0.5.0 默认装配 |
| `JtokkitTokenizer.java` | jtokkit 1.1.0;默认 O200K_BASE;`count = encoding.countTokensOrdinary(text)`;`encodeAndBack` 走 `encodeOrdinary → decode`;用 jtokkit 自带 `com.knuddels.jtokkit.api.IntArrayList`(不是 fastutil 原生,也不是 JDK List<Integer>) |

**修改**:`core/agent/AgentBudget.java` 加常量 `CHAR_PER_TOKEN_FALLBACK = 4` / `DEFAULT_MAX_ASSISTANT_TOKENS = 1_024`;加 getter `getMaxMessageTokens()` / `getTotalInputTokens()` / `getMaxAssistantTokens()`;旧构造器签名不变

**测试**:`CharFallbackTokenizerTest`(4 测试)+ `JtokkitTokenizerTest`(8 测试,中英文混合稳定 / O200K vs CL100K 差异 / round-trip)+ `AgentBudgetTokenApiTest`(3 测试)— Stage 5 共 **15 测试**

**关键决策**:
- V0.5.0 默认装配 `CharFallbackTokenizer`(与 V0.4.x AgentEngine.estimateConversationChars 字面等价),JtokkitTokenizer 仅 debug 可选注入
- `AgentEngine.estimateConversationChars` / `enforceMessageBudget` / `appendMessageWithBudget` **全部不变**——V0.5.0 主路径仍用 char 判断
- V0.5.1 切换时按 provider 选 encoding(OpenAI gpt-4o → O200K_BASE / Anthropic 近似 CL100K_BASE / Gemini 退化为 CharFallbackTokenizer)
- jtokkit 1.1.0 POM 不传递 fastutil 依赖,自带 `com.knuddels.jtokkit.api.IntArrayList` 包装

---

## 3. 兼容契约(289 V0.4.3 测试 0 回归)

### 必须继续绿的测试
- 289 个 V0.4.3 测试全部不动
- 关键不动点:
  - `AgentEngine` 3 个旧构造器(L74/L82/L91)签名不变;新字段 `auditRepository` / `memoryRecaller` 走默认值
  - `AgentBudget` 旧构造器签名不变;token getter 走默认值
  - `MemoryStore` 4 方法接口不变(`putPreference` / `getPreference` / `getAllPreferences` / `clear`)
  - `LlmPlanner` 旧 2 个构造器签名(`(ModelApiClient, ModelConfig, CapabilityRegistry)` / `(ModelApiClient, ModelConfig, CapabilityRegistry, MemoryStore)`)不变;recaller=null 时退化为 V0.4.3 文案
  - `MockCapabilityProvider(MemoryStore)` 签名不变
  - `userId = "demo-driver" / "demo-passenger"` 字面量在主源保留(集中到 `ActorUsers`,但旧路径继续用字面量)
  - `arbitrationKey = "demo-vehicle"` 不变

### 不新增 `@Deprecated`
- V0.4.x 已 `@Deprecated` 的 API 继续保留,V0.5.1 删除

### AgentBudget char-based 判断
- `AgentEngine.estimateConversationChars` / `enforceMessageBudget` / `appendMessageWithBudget` 完全不变
- 新 token API 只读,不参与判断

---

## 4. 风险点 / 已知限制

- **R1. Audit fail-open 的丢失风险**:V0.5.0 接受此权衡(保证主任务路径不被 Audit 拖累);评审 P1.3 已对齐"同步阻塞、终态可回放"承诺;V0.5.1 引入 WAL + 重试队列 + flush/shutdown 生命周期后改 fail-closed
- **R2. SQLCipher native .so 增加 APK 体积 ~5MB**(abi splits 后);不引 `androidx.security:security-crypto` 避免兼容坑
- **R3. jtokkit 不支持 Claude/Gemini 私有 tokenizer**:O200K_BASE 近似 OpenAI,Anthropic 用 CL100K 近似,Gemini 用 CharFallbackTokenizer。V0.5.0 默认装配 CharFallbackTokenizer,V0.5.1 切换时按 provider 选编码
- **R4. Room `exportSchema=true` 版本管理**:JSON schema 落 `app/schemas/`,需进 git;V0.5.0 schema v1 稳定,V0.5.1 第一次改 entity 时建立 Migration 测试惯例
- **R5. TrajectoryCodec 已知限制**:Integer → Long 类型转换(org.json 解析时,JSON 中 `123` 会被解析为 Integer / Long,根据值大小)。V0.5.0 round-trip 测试覆盖典型场景;V0.5.1 引入字段级类型映射
- **R6. MemoryScope.storageKey 与 V0.4.3 主键的兼容**:`storageKey` 仅用于 V0.5.1 `MemoryRecordEntity`(Room 新表),与 SharedPreferences 二元组 `userId + "." + key` 不冲突;V0.5.0 双写、V0.5.1 一次性迁移
- **R7. Room + minSdk 28 + AGP 9.0.1 兼容**:Room 2.7 要求 AGP 8.0+ ✓;annotationProcessor 在 pure Java 工程支持;`compileDebugJavaWithJavac` 已验证生成 `_Impl.java`
- **R8. CI 影响**:V0.5.0 不引 androidTest,CI 路径不变(仅 `./gradlew testDebugUnitTest`);V0.5.1 引入 androidTest 时 CI workflow 加 emulator job
- **R9. 评审 P2.1 双维度隔离边界**:V0.5.0 真正生效的隔离只有 userId(zone 是软标记)。Preference 层走 V0.4.3 SharedPreferences 二元组(userId + "." + key),Working 层按 sessionId 隔离(已拆为 demo-driver / demo-passenger),Episodic/Semantic V0.5.0 占位返回空。真正 zone 维度隔离要等 V0.5.1 Room MemoryRecordEntity 主键 (userId, zone, layer, key) 接入。已在 `MemoryScope.java` / `LegacyPreferenceMemorySource.java` javadoc 显式标注。

---

## 5. APK 端到端手动验证清单

### Stage 2 加密数据库验证
```bash
# 1. 启动 APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.matrix.agent/.MainActivity

# 2. 看 logcat
adb logcat -s MatrixAgent | grep -E "MatrixDatabase|MasterKeyProvider|Audit"
# 期望:
# [MasterKeyProvider] init alias=matrix_db_master_key
# [MatrixDatabase] init OK encrypted=true
# [App] AppContainer init done ...

# 3. 看数据库文件
adb shell run-as com.matrix.agent ls -la databases/
# 期望:matrix_agent.db 文件存在

# 4. SQLCipher 加密验证
adb shell run-as com.matrix.agent sqlite3 databases/matrix_agent.db ".tables"
# 期望:Error: file is not a database(SQLCipher 加密生效)
```

### Stage 3 Audit 接入验证(评审 P1.3 同步阻塞)
```bash
# 1. APK 内发起任务
# 输入"把主驾温度调到 24 度"

# 2. 看 Audit 落库日志
adb logcat -s MatrixAgent | grep Audit
# 期望:
# [Audit] persist req=... state=SUCCEEDED iterations=1 durationMs=...
# 注:V0.5.0 评审 P1.3 后 persist 是同步阻塞,日志在 AgentRuntimeRepository.execute 返回前打印

# 3. 杀进程 + 重启,验证持久化
adb shell am force-stop com.matrix.agent
adb shell am start -n com.matrix.agent/.MainActivity
# V0.5.0 仅验证持久化(无 UI 查询入口);V0.5.1 接召回 + UI 回放
```

### 评审 P1.2 memoryRecaller 容错验证
```bash
# 1. 让 MemoryRouter 抛异常(临时改 EmptyEpisodicMemorySource.recall 抛 RuntimeException,或用 adb 注入失败)
# 2. APK 内发起任务
# 期望:
# - logcat 出现 [Engine] memoryRecaller failed, fallback to base prompt req=... cause=RuntimeException
# - AgentEngine.execute 仍返回 SUCCEEDED(任务不被记忆故障拖累)
# - systemPrompt 是 V0.4.3 基础文案(无"已召回的 Memory"段)
```

### V0.4.3 行为不退化快速用例
- "把主驾温度调到 24 度" → SUCCEEDED
- 副驾说"主驾调温" → CAPABILITY_REJECTED(V0.4.3 readOnlyHint + zone 校验仍生效)
- 切 gear=D 后 PARKED_ONLY capability → CAPABILITY_REJECTED
- 主驾抢占副驾只读任务 → 副驾 PREEMPTED

---

## 6. V0.5.1 衔接点

V0.5.0 留下的伏笔,V0.5.1 切换时不破坏 V0.5.0 已落地的契约测试:

| 衔接点 | V0.5.0 状态 | V0.5.1 切换 |
|---|---|---|
| AgentEngine 主路径用 token 判断 | 仍用 char | `estimateConversationChars` 改调 `tokenizer.count`,`enforceMessageBudget` 改按 token 截断;按 provider 注入 JtokkitTokenizer / CharFallbackTokenizer |
| 上下文压缩算法 | char-based truncate | PromptBuilder 接入主路径,RECALLED_MEMORY / TOOL_LIST 段按 token 预算压缩;BASE / ZONE_HINT 不动 |
| Episodic / Semantic Memory 召回算法 | 占位返回空 | 接 SessionHistoryDao + embedding;MemoryRouter 4 source 不变,只替换 EmptyEpisodicMemorySource / EmptySemanticMemorySource 实现 |
| Room 真集成测试 | 走 APK 手动验证 | 引入 `src/androidTest` + `inMemoryDatabaseBuilder`,真 Room/SQLCipher 验证 |
| 持久化调优 | 同步 fail-open | WAL + batch + 异步队列 + 重试,改 fail-closed |
| SharedPreferences → Room 迁移 | 双写 | 启动期一次性迁移;`MemoryScope.storageKey` 主键格式已稳定 |
| V0.4.x 已 `@Deprecated` API 删除 | 保留 | 删除 `TraceEvent` / `AgentOutcome.getResults()` 等 |

---

## 7. 总结

V0.5.0 Phase 1 在 **不破坏 289 测试** 的前提下打底:

- **数据层从易失 → 可加密 + 可审计**(SQLCipher + Room + 5 个 Audit 出口点全覆盖)
- **Memory 从单维度 SharedPreferences → 四层 + 双维度**(WORKING/EPISODIC/SEMANTIC/PREFERENCE × userId × zone;评审 P2.1 已标注 V0.5.0 zone 是软标记)
- **预算从 char-only → char + token API**(主路径不切换,但 token 维度已暴露)
- **Prompt 从硬编码 → 模块化骨架**(PromptBuilder 接口稳定,V0.5.1+ 可扩展)

**评审 P1/P2 修复后**:`KeyStore 不可用 / getPassphrase 失败` → IllegalStateException → Noop(绝不静默明文);`memoryRecaller.recall 抛异常 / 返回 null` → 降级 base prompt(任务不崩溃);`Audit persist` 同步阻塞(任务返回后立即可查,无竞态)。

**第二轮评审 P2 修复后**:DAO 查询接口强制 (userId, zone) 访问域,接口层收紧防止 caller 侧过滤疏漏;Entity/DAO javadoc 与实际状态对齐避免按注释误判能力已落地;passphrase `commit()` 同步落盘消除断电窗口 + 堆清零减少密钥驻留。

**377 测试全绿**(353 评审前 + P1.1 +4 + P1.2 +1 + P1.3 +1 + 第二轮 P2.1 +3 + P2.2 +1 + 第三轮 P1 +3 + 第四轮 P1 +4 + 第五轮 P1 +5 + 第六轮 P1 +2),APK 编译通过,V0.4.3 安全契约(三层防御 / 双键 AgentRequest / per-Actor 并发 ViewModel / 抢占仲裁)0 回归。

---

## 8. 评审 P1/P2 修复明细

V0.5.0 Phase 1 经用户代码评审反馈 3 个 P1 + 2 个 P2 修复,测试增量从 +64(评审前 353)扩到 +70(评审后 359)。

### P1.1 KeyStore 失败 → Noop 而非明文 DB(+4 测试)

**问题**:`AndroidKeyStoreMasterKeyProvider.getPassphrase` 失败时返回 null,`MatrixDatabase.getInstance` 静默调 `builder.build()` 退化为明文 DB,审计数据可能悄然落明文。

**修复**:
- `AndroidKeyStoreMasterKeyProvider.getPassphrase` catch Exception 后抛 `IllegalStateException`,不再返回 null
- `MatrixDatabase.getInstance`:keyProvider=null / passphrase=null / passphrase.length==0 / getPassphrase 抛异常,均直接抛 IllegalStateException,删除静默 `builder.build()` 回退分支
- `AppContainer.createAuditRepositorySafely` catch IllegalStateException → 退化 `NoopAuditRepository`,JVM 单测 / 真机 KeyStore 不可用场景统一走 Noop

**新增测试**:`MatrixDatabaseKeyFailureContractTest`(4 case:keyProviderNullThrows / passphraseNullThrows / passphraseEmptyThrows / passphraseThrowingProviderPropagatesAsIllegalState)。`StubKeyProvider` + `MatrixDatabase.resetForTest()`(包-private,仅测试可调)避免单例污染。

### P1.2 memoryRecaller 异常降级而非崩溃任务(净 +1 测试)

**问题**:`AgentEngine.buildSystemPrompt` 直接调 `memoryRecaller.recall(...)`,recall 抛异常会让 AgentEngine.execute 抛 RuntimeException,车机任务入口被记忆故障单点击穿。

**修复**:
- `AgentEngine.buildSystemPrompt` 把 `memoryRecaller.recall` 包 try/catch:异常时 log `[Engine] memoryRecaller failed, fallback to base prompt req=... cause=` + 返回 V0.4.3 base prompt(不含"已召回的 Memory"段)
- `LlmPlanner.savedKeysFor` 加 `if (recalled == null || recalled.isEmpty()) return preferenceBlock;` 防御性短路
- AgentEngine.execute 主路径不变,任务仍返回 SUCCEEDED

**测试契约重写**:`AgentEngineMemoryRecallerIntegrationTest`
- 原 `recallerThrowingDoesNotCrashEngine`(只断言不抛)→ 重写为 `recallerThrowingDegradesToBasePrompt`(断言 SUCCEEDED + base prompt + 无"已召回的 Memory"段)
- 新增 `recallerReturningNullDegradesToBasePrompt`(recaller 返回 null 的防御性 case)
- 净增 +1 测试(原 4 → 现 5)

### P1.3 Audit 改同步阻塞而非异步(+1 测试)

**问题**:`RoomAuditRepository` 通过 `Executor.execute` 异步 insert,但 README / Code-Review 都标注"同步阻塞 insert、所有终态可回放";任务返回后立即 query 存在竞态;原测试通过注入 DirectExecutor 没覆盖真实默认异步行为。

**修复**(二选一里选了"保持同步阻塞"对齐文档承诺):
- `RoomAuditRepository` 移除 `Executor` 字段与构造参数,单参构造器 `(TrajectoryDao)`
- `persist` 同步调 `doPersist`,fail-open 包 try/catch(失败仅 log 不抛)
- V0.5.1 优化为 WAL + batch + 异步队列时再切回 Executor 模型,届时补:
  - flush/shutdown 生命周期(Application.onTerminate / CarLifecycleListener)
  - 队列满策略(block / drop-oldest / spill-to-file)
  - 进程退出未落盘场景的恢复策略
  - 真实异步测试(验证队列入队 + 后台线程消费 + 终态一致性)

**测试更新**:`RoomAuditRepositoryContractTest`
- 移除 `DirectExecutor` 内部类
- 5 处 `new RoomAuditRepository(dao, new DirectExecutor())` 改为 `new RoomAuditRepository(dao)`
- 新增 `persistIsSynchronousQuerySeesItImmediately` 同步契约(persist 返回后立即 query 必须看到记录)

### P2.1 双维度隔离边界澄清(文档)

**问题**:文档称"双维度 userId + zone 隔离",但 V0.5.0 真正生效的只有 userId。

**修复**:`MemoryScope.java` / `LegacyPreferenceMemorySource.java` javadoc 新增"双维度隔离边界(评审 P2.1)"段,明确:
- PREFERENCE 层(LegacyPreferenceMemorySource):走 V0.4.3 SharedPreferences 二元组查询,zone 字段是软标记,不参与过滤
- WORKING 层(SessionContextWorkingMemory):按 sessionId 隔离,sessionId 已在 V0.4.3 拆为 demo-driver / demo-passenger,等价于 userId 隔离
- EPISODIC / SEMANTIC:V0.5.0 占位返回空,V0.5.1 接 Room 时启用 zone 维度
- 真正 zone 维度隔离(同一 userId 在主驾屏 / 副驾屏独立 Memory)等 V0.5.1 Room MemoryRecordEntity 主键 (userId, zone, layer, key) 接入

### P2.2 测试统计口径自洽(本节即是)

**问题**:实际 +64(289 → 353),文档列出 +71(Stage 1 +21 + Stage 2 +16 + Stage 3 +12 + Stage 4 +5 + Stage 5 +17)。

**修正**:评审前实际增量 = Stage 1 +21 / Stage 2 +11 / Stage 3 +12 / Stage 4 +5 / Stage 5 +15 = +64;首轮评审修复后 = Stage 1 +21 / Stage 2 +16 (+5: P1.1 +4 + P1.3 +1)/ Stage 3 +13 (+1: P1.2 净增)/ Stage 4 +5 / Stage 5 +15 = +70;第二轮评审修复后 = Stage 2 +20 (+4: P2.1 +3 + P2.2 +1)/ 其他 Stage 不变 = +74;289 + 74 = 363 与 gradle report `tests=363` 一致。Stage 2 评审前 11 = TrajectoryCodecTest 5 + RoomAuditRepositoryContractTest 6;Stage 2 第二轮评审后 20 = TrajectoryCodecTest 5 + MatrixDatabaseKeyFailureContractTest 4 (P1.1) + MemoryRecordDaoZoneIsolationContractTest 3 (P2.1) + RoomAuditRepositoryContractTest 8 (P1.3 + P2.2)。Stage 3 原 12 = AgentEngineAuditSinkTest 5 + AgentEngineMemoryRecallerIntegrationTest 4 + LlmPlannerMemoryIntegrationTest 3。

---

## 9. 第二轮评审 P2 修复明细

V0.5.0 Phase 1 经第二轮用户代码评审反馈 4 个 P2(均聚焦 V0.5.1 衔接点的接口收紧),全部修复。测试增量从 +70(首轮评审后 359)扩到 +74(第二轮评审后 363)。

### P2.1 持久化 DAO 查询接口加 zone 隔离(+3 测试)

**问题**:`MemoryRecordDao.queryByUserLayer(userId, layer)` 只按 userId + layer;`SessionHistoryDao.queryByUser(userId, limit)` 只按 userId。V0.5.0 没有接入真实 Room 召回,所以尚未形成运行时泄露;但 V0.5.1 一旦直接以这些 DAO 实现召回,同一 Android User 不同 zone 的记忆会被一并取出。

**修复**:
- `MemoryRecordDao.queryByUserLayer` → `queryByUserZoneLayer(userId, zone, layer)`,SQL WHERE 强制 zone 过滤
- `SessionHistoryDao.queryByUser` → `queryByUserZone(userId, zone, limit)`,SQL WHERE 强制 zone 过滤
- `MemoryRecordDao.deleteByUser(userId)` 保留——用于账号切换/用户擦除场景的合法全量清空
- `SessionHistoryDao.queryBySession(sessionId)` 保留——sessionId 是复合主键一部分,唯一即可定位行;V0.5.1 UI 接入时调用方仍需校验 sessionId 归属

**新增测试**:`MemoryRecordDaoZoneIsolationContractTest`(fake 实现 MemoryRecordDao / SessionHistoryDao,3 个 case:`queryByUserZoneLayerFiltersOnZoneNotJustUserId` / `sessionHistoryQueryByUserZoneFiltersOnZone` / `sessionHistoryQueryBySessionRemainsSessionIdOnly`)。V0.5.1 引入 `src/androidTest + inMemoryDatabaseBuilder` 时,本测试可迁移到真 Room 验证 SQL 实际生效。

### P2.2 AuditRepository.queryBySession 加 MemoryScope 访问域(+1 测试)

**问题**:`AuditRepository` 按 request/session 查询不要求 MemoryScope、actor 或 zone;`TrajectoryDao.queryBySession` 也没有 user/zone 条件。当前没有审计历史 UI,风险尚未暴露;但后续做"历史回放 / episodic memory"时,应从接口层强制传入访问范围。

**修复**:
- `AuditRepository.queryBySession(sessionId, limit)` → `queryBySession(userId, zone, sessionId, limit)`,接口签名收紧
- `TrajectoryDao.queryBySession(sessionId, limit)` → `queryBySessionScoped(userId, zone, sessionId, limit)`,SQL `WHERE userId=? AND zone=? AND sessionId=?`
- `AuditRepository.queryByRequest(requestId)` 保留——requestId 是 UUID 全局唯一,且 AuditRecord 已带 userId/zone 字段供二次校验
- `NoopAuditRepository` / `RoomAuditRepository` / `AgentEngineAuditSinkTest.CapturingAuditRepository` 同步改签名
- `RoomAuditRepositoryContractTest.queryBySessionReturnsOrderedList` 加访问域参数;新增 `queryBySessionReturnsEmptyWhenZoneMismatch` 验证 zone/userId 不匹配返回空

**关键决策**:评审反馈"接口层强制传入访问范围,而不是只在 UI 层判断主副驾权限"——V0.5.0 接口签名直接收紧,V0.5.1 接入 UI 回放时无法绕过;V0.5.1 接入后可补 AuditRepositoryCallerScopePreCheck 测试,验证调用方传入 scope 与 TrajectoryEntity 实际 userId/zone 一致。

### P2.3 Entity/DAO javadoc 与实际写入路径对齐(文档)

**问题**:
- `MemoryRecordEntity` 注释写"V0.5.0 双写(SharedPreferences + Room)",但 V0.5.0 全项目无 MemoryRecordDao 的实际调用
- `AuditEventEntity` 注释写"V0.5.0 只写 TERMINAL 类型",但 V0.5.0 全项目无 AuditEventDao 的实际调用

容易让后续按注释或 README 误判能力已经落地。

**修复**:统一改为"V0.5.0 预建表(Entity + DAO 接口稳定,无写入路径);V0.5.1 接入主路径",涉及 4 个文件:
- `MemoryRecordEntity.java`:V0.5.0 预建表;V0.5.1 替换 SharedPreferences 接入主路径
- `AuditEventEntity.java`:V0.5.0 预建表;V0.5.1 接入增量事件(PRE_TOOL/POST_TOOL/POLICY/STEER)
- `MemoryRecordDao.java`:V0.5.0 预建表(无写入路径);V0.5.1 主路径
- `AuditEventDao.java`:V0.5.0 预建表(无写入路径);V0.5.1 扩展增量事件
- `SessionHistoryEntity.java`:V0.5.0 预建表;V0.5.1 episodic memory 召回数据源

### P2.4 KeyStore passphrase commit() 同步落盘 + 内存清零(代码 + APK 验证)

**问题**:`AndroidKeyStoreMasterKeyProvider.getPassphrase` 生成新 passphrase 后 `preferences.edit().putString(...).apply()`——apply 是异步刷盘,若进程在落盘前异常终止,下次启动会生成另一把新密钥,旧审计库将不可读。同时 raw byte[]/char[] 在堆内存驻留时间长。

**修复**:
- 首次生成路径:`.apply()` → `.commit()`(返回 boolean,失败抛 IllegalStateException);APK 卸载重装验证 `created=true committed=true` 日志
- `toChars` → `toCharsAndClear`:byte[] 转 char[] 后立即 `Arrays.fill(bytes, (byte) 0)`
- `getPassphrase` finally 块再次 `Arrays.fill` raw byte[](防御性,即使加密/落盘失败抛异常也不泄漏)
- `MatrixDatabase.getInstance`:char[] 转 byte[] 后立即 `Arrays.fill(passphrase, '\0')`;byte[] 不清零(SupportFactory 在进程生命周期内持续使用,这是 SQLCipher 4.x 的固有限制)

**权衡**:byte[] 必须留给 SupportFactory 持续使用(WAL checkpoint / reopen 需要),passphrase 实际保护由 AndroidKeyStore + 进程隔离 + 堆内存不可直接 dump 三层兜底。V0.5.1 评估是否引入 SQLCipher 5.x 的 `deletePassphrase` API(进程退出时清零)。

### 第二轮 P2 修复后的 V0.5.1 衔接点

| 衔接点 | V0.5.0 状态 | V0.5.1 切换 |
|---|---|---|
| MemoryRecordDao 召回 | 接口已收紧 (userId, zone, layer) | 接 embedding / 相似度,直接调 queryByUserZoneLayer,不允许 caller 侧过滤 |
| SessionHistoryDao 召回 | 接口已收紧 (userId, zone) | 接 episodic memory 算法,调 queryByUserZone |
| AuditRepository UI 回放 | 接口已强制 (userId, zone, sessionId) | 接历史回放 UI,调 queryBySession 前先校验当前 user/zone 归属 |
| MemoryRecordEntity 写入 | 预建表,无写入路径 | 替换 SharedPreferences,启动期一次性迁移 |
| AuditEventEntity 写入 | 预建表,无写入路径 | 接 AgentEngine iteration 级事件,PRE_TOOL/POST_TOOL/POLICY/STEER |
| SQLCipher passphrase 持久化 | commit 同步落盘 + 内存清零 | 评估 SQLCipher 5.x deletePassphrase 进程退出时清零 |

---

## 10. 第三轮评审 P1 修复明细

V0.5.0 Phase 1 经第三轮用户代码评审反馈 1 个 P1(聚焦 Audit 覆盖范围),已修复。测试增量从 +74(第二轮评审后 363)扩到 +77(第三轮评审后 366)。

### P1 Repository 编排层兜底 audit 覆盖所有终态(+3 测试)

**评审发现**:
> AgentEngine 的 5 个出口确实都会写 Audit,但 APK 的真实入口不只有 Engine:
> - `AgentRuntimeRepository.java` (line 148) 在等待 TaskScheduler 超时时,自己构造 TIMED_OUT outcome;
> - `AgentRuntimeRepository.java` (line 160) 在线程中断时,自己构造 CANCELLED outcome;
> - `TaskScheduler.java` (line 113) 在仲裁锁等待被中断或超时时,也会直接生成终态。
>
> 这些路径不会进入 AgentEngine,而 Audit 目前只注入到了 Engine,因此不会落盘。这会直接违背文档中的"所有终态可回放"承诺,尤其是主副驾抢占、排队、超时这些正是车机 Runtime 的关键场景。

**根因**:V0.5.0 Stage 3 把 `auditRepository` 注入 AgentEngine 13 参构造器,但 Repository 编排层(Scheduler + Repository.execute catch 分支)的终态不进 Engine——Repository.execute 拿到的 outcome 是自己 catch 后构造的 TIMED_OUT/CANCELLED,或者 Scheduler 内部 remap 出来的 PREEMPTED,Engine 永远看不到这些 outcome。

**修复**:

| 文件 | 改动点 |
|---|---|
| `data/AgentRuntimeRepository.java` | **新增 12 参构造器**(末参 `AuditRepository auditRepository`);旧 3 个构造器(9 参无 budget / 10 参带 budget / 11 参带 intentClassifier)链式委托 `NoopAuditRepository.INSTANCE`(向后兼容 363 测试 0 回归);构造器强制 `if (auditRepository == null) throw new IllegalArgumentException("auditRepository 不能为空")`;新增字段 `private final AuditRepository auditRepository;` 带第三轮 P1 javadoc;`execute()` 在 `Log.i("[Repo] <- outcome ...")` 后、`return outcome;` 前统一调 `auditRepository.persist(outcome, request)`,覆盖所有终态:`TimeoutException` catch(L186)自构造 TIMED_OUT / `InterruptedException` catch(L203)自构造 CANCELLED / Scheduler 内部 remap PREEMPTED / Engine 正常返回 SUCCEEDED |
| `app/AppContainer.java` | `agentRuntimeRepository = new AgentRuntimeRepository(engineFactory, provider, sessionManager, memoryStore, new DemoModelGateway(), "离线 DemoModelGateway", sharedBudget, scheduler, vehicleStateSource, registry, KeywordIntentClassifier.INSTANCE, auditRepository);`——显式传 `KeywordIntentClassifier.INSTANCE` + `auditRepository`(与 Engine factory 共享同一 RoomAuditRepository 实例) |

**双重 audit 并存策略**:
- Engine 内部 audit(5 个出口点,在 worker 线程)+ Repository 兜底 audit(在 caller 线程)**短期并存**——同 requestId 出现 2 条 persist 调用,符合评审设计。
- 最终落库版本由 `TrajectoryDao @Insert(onConflict = OnConflictStrategy.REPLACE)` 决定——Repository 在 caller 线程后写,覆盖 Engine 在 worker 线程先写的版本。
- APK 真机验证:logcat 出现两条同 requestId 的 `[Audit] persist req=... state=SUCCEEDED`(thread 1002 Engine + thread 1001 Repository),符合设计。

**为什么不直接关掉 Engine 内部 audit**:
- Engine 内部 audit 在 worker 线程即时落库,即使 Repository.execute 因 caller 线程崩溃未走到兜底 persist,Engine 版本仍能保证可回放(部分覆盖 > 完全不覆盖)。
- V0.5.1 优化为 WAL + batch + 异步队列时再评估是否合并到 Repository 单点 audit;V0.5.0 接受双重 persist 的写入成本(Room REPLACE 是 O(log n) 主键查找,成本可忽略)。

**测试**:`AgentRuntimeRepositoryAuditCoverageTest`(3 个 case):
1. `repositoryFutureTimeoutWritesAudit` — Repository future.get(timeout) 超时 → catch TimeoutException → 构造 TIMED_OUT outcome;断言 outcome.finalState=TIMED_OUT + stopReason=TIMEOUT + token.isCancelled=true + audit.findLatestByRequest(reqId).finalState="TIMED_OUT"。使用 `AgentBudget(2, 2, 80, 1000, 1000, 4)` 让 80ms 总 deadline 触发 future.get 超时。
2. `repositoryExternalInterruptWritesAudit` — 外部 interrupt Repository.execute caller 线程 → catch InterruptedException → 构造 CANCELLED outcome;单独起 `repo-caller` 线程调 repo.execute,主线程 await entered latch 后 `caller.interrupt()`,join 3s;断言 outcome.finalState=CANCELLED + stopReason=CANCELLED + token.isCancelled=true + audit 落库 CANCELLED。
3. `driverPreemptionWritesAuditForPassengerPreempted` — 主驾查询抢占副驾阻塞查询:passenger gateway 阻塞(await passengerReleased latch),driver gateway 即时返回 directAnswer;启动 passenger-caller 线程后,主线程同步调 repo.execute 触发 Scheduler.tryPreemptPassengerReadOnly → passenger token.cancel → Engine cancellationState → PREEMPTED outcome;passengerReleased.countDown() 让 passenger gateway 返回,passengerThread.join(3_000) 拿到 PREEMPTED outcome;断言 passenger finalState=PREEMPTED + driver finalState=SUCCEEDED + audit 同时记录两条 (passengerAudit.finalState="PREEMPTED" + driverAudit.finalState="SUCCEEDED")。

**测试基础设施**:
- 内部 `CapturingAuditRepository` 实现 `AuditRepository`,用 `List<AuditRecord>` 收集所有 persist(**不**实现 TrajectoryDao REPLACE 语义,全追加),便于观察 Engine + Repository 双重 persist 的实际调用次数。
- `awaitAuditCount(audit, expectedCount, timeoutMs)` 辅助方法用 20ms 轮询等 audit 收到至少 expectedCount 次 persist(超时抛 AssertionFailed)。
- `buildRepository` 辅助方法同时把 `CapturingAuditRepository` 注入 Engine factory lambda 闭包 + Repository 12 参构造器,镜像真实 AppContainer 装配。

### 第三轮 P1 修复后的 V0.5.1 衔接点

| 衔接点 | V0.5.0 状态 | V0.5.1 切换 |
|---|---|---|
| Engine 内部 audit | 5 个出口点全 persist | 评估合并到 Repository 单点 audit(避免双写),或保留作 worker 线程即时落库的 fallback |
| Repository 兜底 audit | execute() 末尾统一 persist | 维持现状,V0.5.1 WAL + batch 时改为 flush 队列 |
| Scheduler 内部 PREEMPTED 终态 | Repository 兜底 audit 覆盖 | 评估是否在 Scheduler.tryPreemptPassengerReadOnly 内直接 audit(更靠近终态发生点) |
| TrajectoryDao REPLACE 语义 | requestId 主键 + @Insert(REPLACE) 让 Repository 后写覆盖 Engine | V0.5.1 改 WAL + 异步队列时,REPLACE 语义保留,但写入顺序需保证 Repository 版本是最后一条(用taskId + commitTs 双键排序) |

---

## 11. 第四轮评审 P1 修复明细

V0.5.0 Phase 1 经第四轮用户代码评审反馈 1 个 P1(抢占 outcome 与 trajectory 终态语义不一致),已修复。测试增量从 +77(第三轮评审后 366)扩到 +81(第四轮评审后 370)。

### P1 抢占 outcome 与 trajectory 终态语义一致(+4 测试)

**评审发现**:
> 主驾抢占副驾时:AgentEngine 因取消生成 CANCELLED outcome,并将 Trajectory finish(CANCELLED, ...);
> TaskScheduler.java (line 165) 仅重建了外层 AgentOutcome,把 finalState/stopReason 改为 PREEMPTED;
> 但它直接复用了原来的 Trajectory,其内部 stopReason 仍是 CANCELLED;
> Repository 兜底审计最终会得到:数据库结构化列:PREEMPTED,trajectoryJson.trajectory.stopReason:CANCELLED。
>
> 这会导致同一条审计记录在列表页显示"被抢占",回放 JSON 却解码为"已取消"。当前集成测试只断言 AuditRecord 的外层 finalState,没有断言 Trajectory 的内部 stop reason,因此未覆盖该矛盾。

**根因**:`TaskScheduler.remapIfPreempted` 在 V0.4.3 Round 2 引入时只重建 `AgentOutcome`(改 finalState/stopReason),保留了 outcome.getTrajectory() 引用——那时 V0.4.3 没有 audit,trajectory 终态不重要;V0.5.0 Stage 3 引入 Engine 5 出口点 audit + 第三轮 P1 引入 Repository 兜底 audit 后,trajectory.stopReason 落库为 trajectoryJson 字段,与 AuditRecord.stopReason 结构化列对比就暴露了不一致。

**修复**:

| 文件 | 改动点 |
|---|---|
| `core/agent/Trajectory.java` | 新增 `rewriteStopReason(StopReason newStopReason)` 受控终态重映射方法;**约束 1**:null 参数抛 IllegalArgumentException;**约束 2**:`this.stopReason == null` 时(即 finish 之前)抛 IllegalStateException,防御滥用在 finish 前覆盖初值;javadoc 明确"仅用于 Scheduler 抢占路径 PREEMPTED 覆盖 CANCELLED 的终态重映射,不是初始 finish;mutable 的——所有持有此 Trajectory 引用的代码观察到的 stopReason 都会变为新值(抢占路径仅 Engine → Scheduler → Repository 三段链路,无并发观察者,可接受)" |
| `core/agent/TaskScheduler.java` | `remapIfPreempted` 在 `new AgentOutcome(...)` 前调 `outcome.getTrajectory().rewriteStopReason(StopReason.PREEMPTED)`,同步覆盖 trajectory.stopReason;保留 iterations / startedAtMillis / durationMillis / totalToolCalls 不变;javadoc 加第四轮评审 P1 段说明"重映射时必须同步把 trajectory.stopReason 从 CANCELLED 改写为 PREEMPTED——否则 Repository 兜底 audit 落库后会出现结构化列 PREEMPTED / trajectoryJson.trajectory.stopReason CANCELLED 的语义矛盾" |

**为什么是 mutable 重映射而不是新建 Trajectory 副本**:
- Trajectory 没有拷贝构造,新建副本需要深拷贝 iterations(ArrayList)+ AgentMessage + ToolCallSnapshot + ToolObservation + PolicyDecision——成本不必要。
- 抢占路径仅 Engine.execute 写入 → Scheduler remap → Repository persist 三段链路,无并发观察者(Scheduler 在 worker 线程内同步 remap;Repository 在 caller 线程拿到 remap 后的 outcome),mutable 修改的安全风险可接受。
- 受控约束(必须 finish 后才能 rewrite)防止滥用覆盖初始 finish 值。

**为什么不让 AgentEngine 在 token.cancel 后直接 finish PREEMPTED**:
- Engine 不知道自己是被 Scheduler 抢占还是被外部 token.cancel——Engine 调 `auditRepository.persist` 时 outcome 是 CANCELLED,Engine 不知道这个 cancel 会被上层 remap 成 PREEMPTED。
- 重映射责任在 Scheduler(它知道 RunningTask.preempted 标记),Engine 不应该假设 cancel 的语义。

**测试**:

`TrajectoryCodecTest` 新增 4 个 case:
1. `preemptedOutcomeStopReasonMatchesTrajectoryAfterRewrite` — 模拟 Engine finish CANCELLED → rewriteStopReason(PREEMPTED) → 构造 PREEMPTED outcome → round-trip 后 AuditRecord.stopReason(从 outcome.stopReason 来)与 decodeTrajectory(...).getStopReason()(从 trajectory.stopReason 来)一致;同时断言 `outcome.getStopReason() == outcome.getTrajectory().getStopReason()`。
2. `allTerminalStatesStopReasonConsistentThroughCodec` — NO_TOOL_CALL/POLICY_HALT/CANCELLED/TIMEOUT/PREEMPTED 5 个终态全 round-trip 一致性(parametrized loop)。
3. `rewriteStopReasonThrowsBeforeFinish` — `new Trajectory()`(未 finish)调 rewriteStopReason 抛 IllegalStateException(防御滥用)。
4. `rewriteStopReasonThrowsOnNull` — finish 后调 rewriteStopReason(null) 抛 IllegalArgumentException。

`AgentRuntimeRepositoryAuditCoverageTest` 3 个 case 全部补充断言:
- `outcome.getStopReason() == outcome.getTrajectory().getStopReason()` —— 所有终态(TIMED_OUT/CANCELLED/PREEMPTED/SUCCEEDED)外层与内层一致。
- `repoAudit.getStopReason()` 与 `TrajectoryCodec.decodeTrajectory(repoAudit.getTrajectoryJson()).getStopReason().name()` 一致 —— round-trip 后 AuditRecord.stopReason 与 trajectoryJson.trajectory.stopReason 一致。

`CapturingAuditRepository` 改造:
- persist 时 trajectoryJson 字段从 `""` 改为 `TrajectoryCodec.encode(outcome)`(让测试可断言 round-trip 一致性)。
- 新增 `findByRequestAndFinalState(requestId, finalState)` —— 在 Engine + Repository 双重 audit 时序竞态下,findLatestByRequest 可能拿到 Engine 后写版本(SUCCEEDED/CANCELLED),按 finalState 过滤可精确选 Repository 兜底版本(TIMED_OUT/CANCELLED/PREEMPTED)。

### 第四轮 P1 修复后的 V0.5.1 衔接点

| 衔接点 | V0.5.0 状态 | V0.5.1 切换 |
|---|---|---|
| Trajectory.rewriteStopReason | mutable 终态重映射,仅 Scheduler remapIfPreempted 调用 | 评估改 immutable(`Trajectory.copyWithStopReason(...)`),需先做 Trajectory 拷贝构造 + 深拷贝 iterations |
| Audit 一致性约束 | 测试断言 outcome.stopReason == trajectory.stopReason,round-trip 一致 | V0.5.1 加 iteration 级 AuditEvent 后,trajectory.stopReason 与 AuditEvent.terminal.stopReason 也需保持一致 |
| CapturingAuditRepository | 测试用,不实现 REPLACE 语义(全追加) | V0.5.1 引入真 Room 测试(`src/androidTest`)后用 TrajectoryDao 真行为验证 REPLACE 语义 |

---

## 12. 第五轮评审 P1 修复明细

V0.5.0 Phase 1 经第五轮用户代码评审反馈 1 个 P1(写操作 timeout / cancel 语义边界),已修复。测试增量从 +81(第四轮评审后 370)扩到 +86(第五轮评审后 375)。

### P1 写操作 timeout / cancel 语义边界(+5 测试)

**评审发现**:
> ToolExecutor.java (line 43) 对所有 capability 一视同仁:超时或取消时执行 future.cancel(true),随即向 Agent 返回 TIMED_OUT / CANCELLED。
> 但 CapabilityProvider 只有同步 execute() 接口,没有真正的 abort()、命令状态查询或请求幂等 ID。对于未来接入 AIDL、Binder、Intent 或厂商 SDK 的写操作:
> interrupt 不保证取消已发出的 Binder/IPC 命令;
> Provider 可能在 Runtime 已返回"已取消"后,继续设置空调、座椅或开始导航;
> 用户、Agent Trajectory 与真实车辆状态由此不一致;
> 当前测试主要使用可被中断的 mock,不覆盖"不响应中断但最终执行成功"的真实场景。

**根因**:V0.4.x 的 ToolExecutor 只关心 Java Future.cancel(true) 中断 worker 线程,但写操作在 worker 线程被中断前可能已把 IPC 命令发到车控 Service / Binder / Intent / 厂商 SDK——这些底层通道不响应 Java 中断。Runtime 返回 CANCELLED 让 Agent / UI 误以为"操作未执行",但 Provider 继续完成车辆状态变更,造成 Agent Trajectory 与真实车辆状态不一致。

**修复策略(协议定义 + 默认行为退化,V0.5.1 / V0.6.0 接真实 Provider 时按 capability 实现)**:

| 文件 | 改动点 |
|---|---|
| `core/tool/CommandState.java` | **新增 enum**:SUBMITTED / EXECUTING / COMPLETED / FAILED / UNKNOWN;javadoc 明确"V0.5.0 协议定义 + 默认行为退化,V0.5.1 / V0.6.0 接真实 Provider 时实现查询" |
| `core/tool/ToolResult.java` | Status enum 新增 `EXECUTION_UNKNOWN`——"写操作下发后超时/取消,Runtime 不能宣称'已取消',V0.5.1 / V0.6.0 由 readback / queryCommandState 异步更新为 SUCCESS / VERIFICATION_FAILED / UNKNOWN;读操作不会进入此状态" |
| `core/capability/CapabilityProvider.java` | 接口扩展 3 个 `default` 方法(保证 MockCapabilityProvider 与所有 V0.4.x 测试 fake 0 改动):`isAbortable()`(默认 false)、`abortIfSupported(String commandId)`(默认空实现,abort 不保证成功)、`queryCommandState(String commandId)`(默认 `CommandState.UNKNOWN`,Provider 不支持查询) |
| `core/tool/ToolExecutor.java` | **新增 `resolveTerminal(call, definition, message, started, reason)`** 按 `definition.isWriteOperation()` 区分:读操作继续 TIMED_OUT/CANCELLED(V0.4.x 行为不变),写操作一律 EXECUTION_UNKNOWN;**新增 `tryAbort(provider, definition, call)`** 在写操作 timeout/cancel/中断分支调 `provider.abortIfSupported(call.getStepId())`(包 try/catch,abort 抛异常忽略仍按 EXECUTION_UNKNOWN);新增 `TerminalReason` enum + `terminalLabelFor(definition)` 日志辅助;新增 `shutdown()` 优雅关闭 worker 池(与 TaskScheduler 一致) |

**关键决策**:

1. **写操作 EXECUTION_UNKNOWN 不依赖 abort 是否成功**:即使 Provider 声明 `isAbortable()=true`,ToolExecutor 仍返回 EXECUTION_UNKNOWN——abort 命令可能晚于 Provider 完成到达,或 ECU 拒绝中断;最终状态由 V0.5.1 的 readback / queryCommandState 异步更新。

2. **CapabilityProvider 用 default methods 而非新接口**:
   - 选项 A:新增 `AbortableCapabilityProvider extends CapabilityProvider` 接口,Provider 按需 implements。
   - 选项 B(采用):CapabilityProvider 接口直接加 default 方法,默认退化"不支持"。
   - 选 B 是因为:Java 8+ default methods 已经能让 V0.4.x 实现不需要任何改动(289 测试 0 回归);MockCapabilityProvider / CapabilityProvider lambda 测试 fake 全部自动获得默认行为;V0.5.1 真实 Provider 按 capability 重写部分方法即可,不需要 implements 多接口。

3. **不修改 AgentEngine 主路径**:Engine 收到 ToolResult 后按 status 处理(EXECUTION_UNKNOWN 与 EXECUTION_FAILED 一样归类为失败,Engine 进入 FAILED 终态);V0.5.1 评估是否在 Engine 内对 EXECUTION_UNKNOWN 做特殊处理(例如保留迭代等待 readback,而非直接 FAILED)。

4. **不引入异步 readback**:V0.5.0 协议定义 + 测试覆盖同步路径(EXECUTION_UNKNOWN 即终态);V0.5.1 引入异步 readback 队列 + queryCommandState 轮询,最终更新 ToolResult / Audit。

**测试**:`ToolExecutorWriteSemanticsTest` 新增 5 个 case:
1. `readOnlyCapabilityTimeoutReturnsTimedOut` — 读操作超时仍 TIMED_OUT,V0.4.x 行为兼容(确认改动不破坏读路径)。
2. `writeCapabilityTimeoutReturnsExecutionUnknownWhenNotAbortable` — 写操作 + 不可 abort Provider + 超时 → EXECUTION_UNKNOWN;额外断言 `!= TIMED_OUT` 与 `!= CANCELLED`,确保不会误退化为旧状态。
3. `writeCapabilityCancellationReturnsExecutionUnknownWhenNotAbortable` — 写操作 + 不可 abort Provider + 外部 token cancel(单线程触发)→ EXECUTION_UNKNOWN + token.isCancelled=true。
4. `abortableWriteProviderCallsAbortOnTimeout` — 写操作 + 可 abort Provider + 超时 → abortIfSupported 被调用 1 次 + commandId 等于 call.getStepId() + 仍返回 EXECUTION_UNKNOWN(abort 不保证成功)。
5. `uninterruptibleWriteProviderReturnsExecutionUnknownEvenIfProviderCompletes` — **评审核心场景**:写操作 + Provider 不响应 interrupt(在 catch InterruptedException 后继续 sleep)+ Provider 在 Runtime 超时后仍完成"写入"车辆状态(providerCompleted=1)→ Runtime 必须返回 EXECUTION_UNKNOWN 而非 CANCELLED;模拟真实 Binder 命令已下发,Java 中断无效。

### 第五轮 P1 修复后的 V0.5.1 / V0.6.0 衔接点

| 衔接点 | V0.5.0 状态 | V0.5.1 / V0.6.0 切换 |
|---|---|---|
| CapabilityProvider.isAbortable | 默认 false,V0.4.x Provider 全部不支持 abort | V0.6.0 接 CarPropertyManager / 厂商 SDK 时按 capability 声明 true(仅 Provider 真能保证 abort 成功才返回 true) |
| CapabilityProvider.abortIfSupported | 默认空实现 | V0.6.0 接 AIDL cancelCommand 接口,Provider 内部维护 commandId → 命令表 |
| CapabilityProvider.queryCommandState | 默认 UNKNOWN | V0.6.0 接真实 ECU 状态查询,Runtime 在 EXECUTION_UNKNOWN 后异步轮询(指数退避,deadline 由 AgentBudget 决定) |
| ToolResult.EXECUTION_UNKNOWN | V0.5.0 终态,AgentEngine 按 FAILED 处理 | V0.5.1 评估是否引入 PENDING_RECONCILIATION 中间态,Engine 不立即 FAILED,等待 readback |
| readback 自动重试 | V0.5.0 不做 | V0.5.1 / V0.6.0 对 `verifyMethod=READBACK_FIELD/READBACK_GET` 的 capability,在 EXECUTION_UNKNOWN 后自动后台 readback,最终更新 Audit / ToolResult |
| ToolExecutor.shutdown | V0.5.0 新增,与 TaskScheduler 一致 | V0.5.1 / V0.6.0 AppContainer.shutdown 链路统一调,确保进程退出时 worker 线程不泄漏 |

## 13. 第六轮评审 P1 修复明细

V0.5.0 Phase 1 经第六轮用户代码评审反馈 1 个 P1(Repository 外层 deadline/interrupt 绕过 EXECUTION_UNKNOWN 保护),已修复。测试增量从 +86(第五轮评审后 375)扩到 +88(第六轮评审后 377)。

### P1 Repository 外层 deadline/interrupt 绕过 EXECUTION_UNKNOWN 保护(+2 测试)

**评审发现**:
> 最外层 Repository 超时仍会绕过刚加入的 EXECUTION_UNKNOWN 保护。ToolExecutor 的修复是正确的:写操作一旦超时/取消,会返回 EXECUTION_UNKNOWN。但 AgentRuntimeRepository.java (line 190) 同时在最外层对 scheduler Future 按 request deadline 等待。若它先发生超时,就会:token.cancel();future.cancel(true);直接构造一个没有 Tool Observation 的 TIMED_OUT outcome 并返回。此时如果写命令已下发,ToolExecutor 可能还来不及生成 EXECUTION_UNKNOWN,或者后台 Provider 仍会继续执行。UI 和最终 Audit 却只看到普通 TIMED_OUT,重新产生"系统不知道命令是否已执行,却没有明确告知"的问题。外部调用线程被中断时,AgentRuntimeRepository.java (line 204) 也有同样风险:直接返回空 Trajectory 的 CANCELLED。

**根因**:第五轮 P1 在 ToolExecutor 层加了写操作 EXECUTION_UNKNOWN 保护,但 `AgentRuntimeRepository.execute` 在最外层 `future.get(waitMillis)` 超时 / 外部 interrupt 时,旧实现直接 `token.cancel()` + `future.cancel(true)` + 自构造空 trajectory 的 TIMED_OUT/CANCELLED——比 ToolExecutor 更早返回(ToolExecutor 可能还在轮询 isCancelled 或等待 Provider),覆盖掉 ToolExecutor 即将生成的 EXECUTION_UNKNOWN。结果是:命令已下发到 Provider(可能已生效),但 Runtime 宣称 TIMED_OUT/CANCELLED(暗示"未发生"),与 ToolExecutor 第五轮 P1 修复的语义冲突。

**修复策略**:

| 文件 | 改动点 |
|---|---|
| `core/agent/TaskState.java` | 新增 `EXECUTION_UNKNOWN` 枚举值,对应 Repository 外层超时/interrupt + 写操作的语义——区别于 TIMED_OUT/CANCELLED(暗示"未发生"),EXECUTION_UNKNOWN 明确表达"命令已下发但结果未知",UI/Audit 必须按未知态展示,V0.5.1 / V0.6.0 由 readback / queryCommandState 异步更新为 SUCCEEDED / FAILED |
| `core/agent/StopReason.java` | 同上,新增 `EXECUTION_UNKNOWN` 对应 TaskState,对应 Repository 外层 deadline/interrupt 触发但 intentReadOnly=false 的场景 |
| `data/AgentRuntimeRepository.java` | 新增 `GRACE_WINDOW_MILLIS=500L` 常量;新增 `awaitWriteDispatchConvergence(future, request, startedNanos, message)` 私有静态方法——future.get(GRACE_WINDOW_MILLIS) 收敛成功用 Engine 真实 outcome(可能含 ToolExecutor 写 EXECUTION_UNKNOWN observation),收敛失败(TimeoutException/CancellationException/ExecutionException/InterruptedException)future.cancel(true) + fallback EXECUTION_UNKNOWN;`catch (TimeoutException)` 块按 `intentReadOnly` 分支:读操作直接 V0.4.x fallback TIMED_OUT(跳过 grace window,避免 token.cancel 触发 Engine abortHook CANCELLED 掩盖 TIMED_OUT 语义),写操作先 `token.cancel()` 协作取消 + `awaitWriteDispatchConvergence`;`catch (InterruptedException)` 块 caller 线程已中断不能阻塞调 future.get(graceMillis),直接按 intentReadOnly 切换 fallback(写 EXECUTION_UNKNOWN,读 CANCELLED)|

**关键决策**:

1. **grace window 仅对写操作启用**:读操作无副作用,Repository timeout 即"未发生",直接 V0.4.x fallback TIMED_OUT/CANCELLED。如果对读也启用 grace window,token.cancel 会触发 Engine 的 ModelCallExecutor abortHook 把 gateway future 标记为 cancelled,Engine 退出返回 CANCELLED(而非自然 TIMED_OUT),grace window 收敛 Engine outcome 后返回 CANCELLED——与 V0.4.x TIMED_OUT 期望不一致,导致既有 read TIMED_OUT 测试在 token.cancel 与 ModelCallExecutor timeout 之间产生竞态 flaky。读路径跳过 grace window 后,行为完全 V0.4.x 兼容,既有 3 个 audit 覆盖测试(`AgentRuntimeRepositoryAuditCoverageTest`)继续绿。

2. **写操作 grace window 收敛 vs fallback 都对**:Engine 在 500ms 内返回(ToolExecutor 看到 token.cancel 后 poll 立即 short-circuit + tryAbort → 返回 EXECUTION_UNKNOWN)→ 收敛成功,Repository 用 Engine 的 outcome(TIMED_OUT finalState + 含 EXECUTION_UNKNOWN observation 的 trajectory),audit 体现"命令已下发 + 状态未知";Engine 未在 500ms 内返回(Provider 不响应 interrupt 或 ToolExecutor poll 未及时)→ fallback 构造 EXECUTION_UNKNOWN finalState + 空 trajectory,audit 体现"命令已下发 + 状态未知"。两种路径都满足评审 P1 的核心断言:"Outcome 与 Audit 至少包含 EXECUTION_UNKNOWN,不能是空轨迹超时"——finalState=EXECUTION_UNKNOWN 即满足"非空轨迹超时"(因为 finalState 不是 TIMED_OUT)。

3. **`TaskState.EXECUTION_UNKNOWN` 与 `ToolResult.Status.EXECUTION_UNKNOWN` 区分**:ToolResult 层级是单次 tool call 的结果(命令是否已下发),TaskState 层级是整个 Agent 任务的状态(Repository 外层终态)。两者通过 trajectory.iterations[*].observations[*].result.status = EXECUTION_UNKNOWN ↔ outcome.finalState = EXECUTION_UNKNOWN 关联。Engine 收到 ToolResult.EXECUTION_UNKNOWN 后,V0.5.0 把它归为失败(Engine 进入 FAILED/TIMED_OUT 终态);Repository 外层在 grace window 看到 Engine outcome(TIMED_OUT/CANCELLED + 含 EXECUTION_UNKNOWN trajectory)或 fallback 时,根据 intentReadOnly 决定是否重写 finalState 为 EXECUTION_UNKNOWN。V0.5.1 评估是否在 Engine 内对 ToolResult.EXECUTION_UNKNOWN 直接产生 TaskState.EXECUTION_UNKNOWN 终态,避免 Repository 重写。

4. **500ms grace window 的取值权衡**:Engine 主循环一轮约 50~200ms,ToolExecutor 的 future.cancel(true) + Provider interrupt 响应通常 <100ms,500ms 给 Engine 充分收敛时间;同时不让 Repository 在 deadline 之后阻塞过久(主驾 UI 等待 Repository 返回,过久会让用户误以为卡死)。V0.5.1 评估按 AgentBudget 比例调整(例如 deadline × 10% 上限 1000ms)。

5. **InterruptedException 不进 grace window**:caller 线程已被外部 interrupt,`future.get(graceMillis)` 会立即抛 InterruptedException 重入 catch 块,无法阻塞等待 Engine 收敛。直接 fallback:写 EXECUTION_UNKNOWN(命令可能已被 Engine dispatch 到 Provider),读 CANCELLED(无副作用可安全宣称未发生)。这是 P1 评审建议"基于是否已进入写操作 dispatch 构造 EXECUTION_UNKNOWN"的简化实现——V0.5.0 用 IntentClassifier 的 keyword-based 判定,V0.5.1 / V0.6.0 切到 LLM-based classifier 后判定更准。

**测试**:`AgentRuntimeRepositoryWriteDispatchSemanticsTest` 新增 2 个 case:
1. `repositoryTimeoutWithDispatchedWriteContainsExecutionUnknown` — **评审核心场景**:Gateway 立即返回 `vehicle.climate.set_temperature` tool_call + 自定义不可中断 Provider(模拟真实 IPC,sleep 2000ms 不响应 interrupt)+ budget deadline=50ms 让 Repository timeout 先于 capability timeout(默认 3000ms)触发 → outcome 必须含 EXECUTION_UNKNOWN 信息(finalState=EXECUTION_UNKNOWN 或 trajectory 含 EXECUTION_UNKNOWN tool observation),绝不能是"空轨迹 TIMED_OUT";trajectory.iterations 非空(证明 Engine 确实 dispatch 了 write tool);token 必须 cancel;audit trajectoryJson 必须含 "EXECUTION_UNKNOWN" 字符串或 finalState=EXECUTION_UNKNOWN。
2. `repositoryInterruptWithWriteCommandReturnsExecutionUnknown` — 写命令 + 外部 interrupt caller 线程 → catch InterruptedException 直接 fallback:outcome.finalState=EXECUTION_UNKNOWN(不是 CANCELLED),trajectory.stopReason 与 outcome.stopReason 一致,audit 落库 EXECUTION_UNKNOWN,token 必须 cancel。

### 第六轮 P1 修复后的 V0.5.1 / V0.6.0 衔接点

| 衔接点 | V0.5.0 状态 | V0.5.1 / V0.6.0 切换 |
|---|---|---|
| TaskState.EXECUTION_UNKNOWN | Repository 外层 fallback 用(空 trajectory) | V0.5.1 评估是否让 AgentEngine 主路径在 ToolResult.EXECUTION_UNKNOWN 后直接产生此终态(含 Tool Observation trajectory),Repository 不需重写 |
| GRACE_WINDOW_MILLIS=500 | 固定 500ms | V0.5.1 按 AgentBudget 比例调整(例如 deadline × 10% 上限 1000ms),并支持 per-capability override(慢 capability 给更长收敛窗口) |
| awaitWriteDispatchConvergence | 仅写操作调用,读操作直接 V0.4.x fallback | V0.5.1 评估是否对读操作也启用——前提是 Engine 不会因 token.cancel 误返回 CANCELLED(需要在 Engine 内区分"Repository timeout 触发的 cancel"和"用户主动 cancel"),或改为 grace window 内不调 token.cancel 让 Engine 自然超时 |
| IntentClassifier-based intentReadOnly | V0.5.0 keyword-based(保守,unknown 当写) | V0.5.1 切 LLM-based classifier,更准判定写操作;V0.6.0 接 capability-level dispatch hook,Repository 知道"Engine 是否真的 dispatch 了 write tool"(避免误把"写命令 + Engine 没真 dispatch"判为 EXECUTION_UNKNOWN) |
| 双重 audit REPLACE 语义 | Engine 内部 audit + Repository 兜底 audit 共享 requestId,后写覆盖 | V0.5.1 评估是否引入 audit version 字段(Engine 版本 vs Repository 版本),queryByRequest 默认返回 Repository 版本(权威),queryByRequestWithHistory 返回全部历史 |
| PendingReconciler | V0.5.0 不做,EXECUTION_UNKNOWN 是终态 | V0.5.1 引入 PendingReconciler 后台轮询 `queryCommandState`,把 EXECUTION_UNKNOWN 异步重写为 SUCCEEDED / FAILED;Repository 在 EXECUTION_UNKNOWN outcome 中标记 needsReconciliation=true 让 UI 知道"待复核" |


## 14. 第七轮收尾评审 P1 修复明细

第七轮收尾评审结论:V0.5.0 可作为模拟器 / 架构验证版,**不能作为真实车机或厂商系统服务版发布**。3 个 P1 集中在"对外暴露面"——HTTP 明文 / 审计 PII 持久化 / clearUserData 与在途写非原子。修复后 **388 JVM 测试全绿(377 → 388,+11 新增),assembleDebug 通过,0 回归**。

### P1.1 网络安全配置:release HTTPS,仅 loopback + debug 放行明文(+0 测试,APK 验证)

**评审问题**:旧 `AndroidManifest.xml` L13 `android:usesCleartextTraffic="true"` 是全局开关,叠加用户可配置任意 endpoint,release APK 一旦被改 endpoint,整个 LLM 请求(API Key + 用户原文)可在局域网被嗅探。

**修复方案**(单文件 + manifest 1 行,**不动 product flavor 避免 AGP 配置成本**):

1. **新增 `app/src/main/res/xml/network_security_config.xml`**:
   - `<base-config cleartextTrafficPermitted="false">` —— release 路径默认 HTTPS
   - `<domain-config cleartextTrafficPermitted="true">` 仅放行 `localhost` / `127.0.0.1` / `10.0.2.2`(emulator host)——本地开发 LLM 端点
   - `<debug-overrides>` 信任 user CA + 系统 CA,仅在 `android:debuggable="true"` 时自动生效(AGP debug 构建 default true,release default false)

2. **`AndroidManifest.xml` L13** 删除 `android:usesCleartextTraffic="true"`,新增 `android:networkSecurityConfig="@xml/network_security_config"`

**关键决策**:
- 用 NSC 而非 product flavor:Android `<debug-overrides>` 自带 debug/release 区分语义,无需手动 flavor 切分。
- 仅 loopback 放行明文而非全局允许:开发者自测本地 LLM(OpenAI Compatible HTTP server on localhost:8080)足够;若要测试内网 HTTPS 端点,debug 构建已通过 `<debug-overrides>` 全放开。

**验证方式**(APK 端到端,非 JVM 测试):
- HTTPS endpoint → 正常工作
- `http://10.0.2.2:8080`(emulator loopback)→ 正常工作
- `http://192.168.1.10:8080`(局域网)→ logcat 出现 `CLEARTEXT communication ... not permitted by network security policy`

### P1.2 审计自由文本 fail-closed:长度 + SHA-8 摘要替代原文(+7 测试)

**评审问题**:`AgentEngine.auditRedactAssistant` L585 仅调 `AuditRedactor.redact(content)`——它只跑凭据正则(`sk-` / `AIza` / `Bearer`)+ 长度截断,**模型把用户地址 / 联系人 / 电话回显进 assistant content 时,会被原样写进加密 SQLCipher 数据库**。"加密落盘" 不等于 "可无限保留原文"。

**修复方案**:

1. **`AuditRedactor.redactFreeText(String content)`** 新方法:
   - 返回 `[redacted:chars=N,sha=xxxxxxxx]` 格式
   - chars 保留(长度是诊断信号:`max_tokens` 截断 / 异常长输入)
   - SHA-8(取 SHA-1 前 8 位十六进制)允许事后按已知明文比对,不可逆推原文
   - 与现有 `redact(String)` **并存**:后者保留给 Tool arguments / ToolResult message(凭据正则 + 截断),V0.4.3 测试 0 回归

2. **`AgentEngine.auditRedactAssistant` L585** 改用 `redactFreeText`:
   - assistant content 一律摘要化,不再泄露 PII
   - ToolCall arguments 不动(已经走 schema-aware `redactArguments(cap, args)`,fail-closed 在第五轮 P1-3 已就位)

**关键决策**:
- SHA-1 而非 SHA-256:本场景只需"事后按已知明文比对",SHA-1 已足;SHA-256 字符串更长(16 vs 8 字符),审计 JSON 体积增长更明显。SHA-1 已被破解的碰撞攻击场景(数字签名)不适用。
- 保留 chars 不保留原文:V0.5.0 UI 走 `ModelSanitizer` 路径直显原文,审计侧只看 `[redacted:chars=N,sha=xxxxxxxx]`——UI / 调试不依赖审计原文回放。
- V0.5.0 已有 audit template 的 capability(navigation / climate / memory.preference)的 ToolResult message 走 `redactWithSchema` 路径不变,本次只新增 assistant content 一路。

**测试**:`AuditFreeTextRedactionTest` 新增 7 个 case:
- `redactFreeTextReturnsCharsAndSha` —— 格式 `[redacted:chars=N,sha=xxxxxxxx]`
- `redactFreeTextNullEmpty` —— null/空串边界
- `redactFreeTextChineseStable` —— 中文 UTF-8 字节做 SHA,稳定性
- `redactFreeTextDifferentInputsDiffer` —— 不同输入产生不同 sha8
- `redactFreeTextDoesNotLeakPii` —— 地址 / 电话 / 姓名 / 朝阳区都不泄露
- `redactFreeTextPreservesCharCountForLongInput` —— 5000 字符场景 chars 准确
- `redactAndRedactFreeTextAreIndependent` —— 双路径互不影响(保护 V0.4.3 测试)

### P1.3 clearUserData "取消—等待—清空" 序列(+4 测试)

**评审问题**:`AgentRuntimeRepository.clearUserData()` L266-271 仅清 MemoryStore + SessionManager:
1. `SteerMailbox` 没清——`clearForTesting()` L89 是包私有,旧 FORCE_TOOL / REPROMPT / DEFER 留在队列里被下一次同 sessionId 任务消费到陈旧指令;
2. 与在途写操作非原子——clear 之前已 dispatch 的 `preference.save` / `climate.set` 在 clear 之后完成,把刚清的数据写回。

**修复方案**:

1. **`SteerMailbox.clearAll()` 公开**(`clearForTesting` 改 `@Deprecated` bridge 调 `clearAll`,V0.5.1 删除)

2. **Repository 新增字段**:
   - `Set<CancellationToken> activeTokens = ConcurrentHashMap.newKeySet()` —— 在途 token 跟踪
   - `volatile SteerMailbox steerMailbox` —— 由 `setSteerMailbox()` 注入(可选,兼容现有测试)

3. **`execute(...)` 加 try/finally** —— 入口 `activeTokens.add(token)`,finally `activeTokens.remove(token)`

4. **`clearUserData()` 重写为 3 步序列**:
   - **取消**:`for (token : activeTokens) token.cancel()` —— 触发 Engine abort hooks + 让 write dispatch 进入第六轮 P1 的 `GRACE_WINDOW_MILLIS=500ms` 收敛窗口
   - **等待**:最多 1000ms(2× grace window)等 `activeTokens.isEmpty()` 收敛——让在途写操作要么在 clear 之前完成,要么被 token.cancel 后的 EXECUTION_UNKNOWN 路径放弃 observation 写回
   - **清空**:SteerMailbox + MemoryStore(driver + passenger)+ SessionManager

5. **`AppContainer` 装配** —— `agentRuntimeRepository.setSteerMailbox(steerMailbox)`(与 AgentEngine 共享同一实例)

**关键决策**:
- **不引入 epoch 机制**(推迟 V0.5.1):评审建议的"session/data epoch 让旧 epoch 的 Steer / Tool 回写 / 异步结果被拒绝"是更彻底的方案,但需要 `CancellationToken` 加 epoch 字段 + 跨组件契约改动 6+ 文件。V0.5.0 选轻量方案——"取消—等待—清空"配合第六轮 P1 收敛窗口,已能让所有在途写操作收敛或走 EXECUTION_UNKNOWN 路径不写回 MemoryStore(`auditRepository.persist(outcome, request)` 不调 `MemoryStore.save`,不会绕过 clear)。
- **setter 而非构造器参数**:Repository 有 4 个构造器(向后兼容 289 测试),不增加新参;`setSteerMailbox` 可选——调用方忘记注入时 `clearUserData` 仅记 warn 不 NPE。
- **1000ms 阻塞**:现有 `AgentTestViewModel` L136 调 `clearUserData()` 已在 ViewModel 后台 Executor,不在主线程。

**测试**:`AgentRuntimeRepositoryClearUserDataTest` 新增 4 个 case:
- `clearUserDataClearsSteerMailbox` —— 投递 FORCE_TOOL,clearUserData 后 `mailbox.pendingCount=0`
- `clearUserDataCancelsInFlightTokens` —— 开线程跑阻塞 gateway 任务,clearUserData 1.5s 内返回 + token.isCancelled=true
- `clearUserDataWithoutSteerMailboxSkipsGracefully` —— 不注入 mailbox 也不抛异常(warn 兜底)
- `clearUserDataRemovesMemoryAndSession` —— 偏好 + session turn 全清

### 第七轮 P1 修复后的 V0.5.1 衔接点

| 衔接点 | V0.5.0 状态 | V0.5.1 切换 |
|---|---|---|
| NSC `<debug-overrides>` | 仅 `debuggable=true` 生效 | 若需 release 也支持自定义 CA(厂商 root cert),改为 product flavor + `release` NSC 显式 trust-anchors |
| AuditRedactor.redactFreeText | assistant content 全摘要 | V0.5.1 按 capability 配置 "audit safe content template" —— 部分 capability(知识问答)允许按模板投影,默认仍 fail-closed |
| SteerMailbox.clearAll | 公开为正式 API | V0.5.1 增加 per-session clearAll(sessionId) 选项——只清特定用户(token 复用),配合 multi-user support |
| clearUserData "取消—等待—清空" | 轻量方案,1000ms grace | V0.5.1 引入 session/data epoch,clear 后拒绝旧 epoch Steer / Tool 回写 / 异步结果 |
| queryByRequest 加 scope | 未做 | V0.5.1 改 `queryByRequest(scope, requestId)`,杜绝跨用户 / 跨 zone 枚举 requestId |
| clearUserData 删 Room Audit | 未做 | V0.5.1 clearUserData 同步删 AuditEventEntity(按 userId + zone 范围删除) |
| ExecutionException 结构化 Outcome | Repository catch 直接抛 RuntimeException | V0.5.1 改为统一 Outcome(PROTOCOL_ERROR + cause 类名)+ Audit 落库 + 仅记 cause 类名避免 `cause.getMessage()` 泄密 |
| 线程池生命周期 / 背压 | 固定 2 thread,scheduler.shutdown 钩子 | V0.5.1 加 DynamicThreadPool(基于 deadline 弹性 + 拒绝策略 + 监控) |
| 设备级 SQLCipher / KeyStore 验证 | 仅 JVM 契约测试 | V0.5.1 引入 `src/androidTest` + `inMemoryDatabaseBuilder` 真集成测试 |
| SteerMailbox.clearForTesting @Deprecated bridge | 保留兼容旧测试 | V0.5.1 删除,grep 全工程确认无调用方后清理 |


## 15. 第八轮收尾评审修复明细

第七轮 P1 落地后,第八轮评审指出"第七轮 P1.2 实际仍未生效""P1.3 仍是尽力而为"等真实回归。第八轮针对**用户原话"V0.5.0 现在最优先应该先完成 'Audit 默认不落自由文本' 和 'data epoch'"**做实质修复,顺带把评审里点名的 P2(queryByRequest 加 scope、NSC debug-overrides 对齐、ExecutionException 结构化)一并落地。**388→393 测试,0 回归**。

### P1.2 真正生效:ToolObservation 默认路径也走自由文本 fail-closed(+3 测试)

**评审回归**:第七轮 P1.2 只覆盖 `auditRedactAssistant`(L585 → `redactFreeText`),但 `AuditRedactor.redact(ToolObservation)` 默认路径(L258-262)仍调 `redact(original.getMessage())`——仅跑凭据正则,模型把用户地址/电话回显进 ToolResult.message 时(或 capability 未声明 audit template 时),原文照常落盘。`CapabilityRegistry` 的 `knowledge.answer` 也未声明 audit template,Provider 会回显用户问题。

**修复**:
1. **`AuditRedactor.redact(ToolObservation)`** 默认路径改走 `redactFreeText(original.getMessage())`——与 `auditRedactAssistant` 对齐,任何 capability 未声明 schema 的 message 都 fail-closed 为 `[redacted:chars=N,sha=xxxxxxxx]`。
2. **`CapabilityRegistry.knowledge.answer`** 新增 `.auditMessageTemplate("[knowledge answer redacted: free-text contains potential PII]")` + `.auditFailureMessageTemplate("[knowledge answer failed: details redacted]")`——已配置 schema 的 capability 走 `redactWithSchema`,不再泄露 question/answer 原文。
3. **`AuditRedactor.redact(String)`** 路径保留——仍是 Tool arguments 默认处理(凭据正则 + 截断),不破坏 V0.4.3 测试。

**测试**(`AuditFreeTextRedactionTest` +3 case):
- `redactToolObservationWithoutTemplateFreeTextsMessage` —— 未配 template 的 observation message 进 audit 后为 `[redacted:chars=N,sha=xxxxxxxx]`
- `knowledgeAnswerObservationUsesAuditTemplate` —— knowledge.answer 走 schema template,fail-closed
- `knowledgeAnswerFailureUsesFailureTemplate` —— failure 路径同样走 failure template

`AuditRedactorTest` 2 个旧 case(`nonMemoryCapabilityKeepsObservedValuesIntact` / `capabilityWithoutAuditSchemaKeepsDefaultRedaction`)断言更新为期望 `[redacted:chars=N,sha=xxxxxxxx]` 格式(原断言"保留原始 message"已不适用)。

### P1.3 data epoch:写操作被强一致拒绝(强一致清除,+2 测试)

**评审回归**:第七轮 P1.3 的"取消—等待—清空"是尽力而为——1000ms grace window 过期后仍清,不可中断 Provider(set_temperature 模拟真 ECU IPC)完成后会把偏好/异步结果写回;SteerMailbox.clearAll() 与新 offer() 也有并发窗口。评审原话:"这个 epoch 应成为下一步,而不是把当前实现视为强一致清除"。

**修复(强一致 data epoch,不引入 session epoch 全链路改动)**:
1. **`MemoryStore` 接口加 3 个 default 方法**(4-method 接口保持兼容):
   ```java
   default long currentEpoch() { return 0L; }                       // 兼容旧实现
   default void bumpEpoch() { }                                     // 兼容旧实现
   default boolean putPreferenceChecked(String userId, String key,
           String value, long epoch) { putPreference(userId, key, value); return true; }  // 兼容旧实现
   ```
2. **`InMemoryMemoryStore` / `SharedPreferencesMemoryStore`** 实现 epoch:AtomicLong epochCounter;bumpEpoch() 自增 + 持久化(SP 走 `commit()` 同步落盘,非 apply());putPreferenceChecked(epoch=0L 走兼容路径接受写入;epoch != currentEpoch → 拒绝写入 + log warn)。
3. **`AgentRequest` 加 epoch 字段** + Builder.epoch() —— Repository 在 execute 入口捕获 `epochCounter.get()` 注入。
4. **`AgentRuntimeRepository.clearUserData()`** 在 cancel/等待之前 `epochCounter.incrementAndGet()` + `memoryStore.bumpEpoch()` —— 所有在途 Provider 写入的 request.epoch 与新 epoch 不符,被 putPreferenceChecked 拒绝。
5. **`MockCapabilityProvider.MemoryPreferenceSaveHandler`** 改调 `ctx.getMemoryStore().putPreferenceChecked(userId, key, value, requestEpoch)` —— epoch 不符返回 `EXECUTION_FAILED` ToolResult("memory preference rejected: stale epoch (clearUserData occurred)")。

**关键决策**:
- **不引入 session/data epoch 全链路改动**(SteerMailbox.offer / Engine.auditRedact / abort hook 全部加 epoch gate)——评审建议的彻底方案要 6+ 文件契约改动,V0.5.1 跟进。V0.5.0 的 epoch 仅作用于 MemoryStore 写入(实际"清不干净"最严重后果就是偏好被写回),配合第七轮"取消—等待"已能让 Steer/audit 不绕过。
- **MemoryStore 默认方法 + 兼容路径(epoch=0L)**:不破坏现有 4-method 实现(`MemoryRouter` / 测试 fake),只需 InMemoryMemoryStore / SharedPreferencesMemoryStore 实现 epoch 即可,其它 store 退化为"接受所有写入"的旧行为。

**测试**(`AgentRuntimeRepositoryClearUserDataTest` +2 case):
- `epochGateRejectsStaleWriteAfterBump` —— 直接调 `memoryStore.putPreferenceChecked(userId, key, value, oldEpoch)` 在 bumpEpoch 后返回 false
- `repositoryClearUserDataBumpsMemoryStoreEpoch` —— Repository.clearUserData 后 memoryStore.currentEpoch 自增,旧 epoch 写入被拒绝

### P2.1 queryByRequest 加 (userId, zone) 访问域(契约 + DAO 双重 WHERE)

**评审问题**:`AuditRepository.queryByRequest(requestId)` / `TrajectoryDao.queryByRequest(requestId)` 仅靠 UUID 唯一性,任意 caller 可枚举 requestId 读他人审计——UUID 唯一不能替代权限校验。

**修复**:
1. **`AuditRepository.queryByRequest(String userId, String zone, String requestId)`** —— 接口签名加访问域。
2. **`TrajectoryDao.queryByRequestScoped(userId, zone, requestId)`** —— SQL `WHERE userId = :userId AND zone = :zone AND requestId = :requestId` 双重过滤,即使攻击者构造合法 UUID 也跨域取不到。
3. **`RoomAuditRepository.queryByRequest`** 透传 scope 到 DAO。
4. **3 个测试 fake 同步签名**:`RoomAuditRepositoryContractTest` / `AgentRuntimeRepositoryAuditCoverageTest` / `AgentRuntimeRepositoryWriteDispatchSemanticsTest` 的 fake AuditRepository / fake TrajectoryDao 全部加 userId+zone 匹配。

### P2.2 NSC `<debug-overrides>` 真正允许 debug 明文(注释/实现对齐)

**评审问题**:第七轮 P1.1 的 `<debug-overrides>` 未加 `cleartextTrafficPermitted="true"`,只增加 user CA 信任——debug 下填 `http://192.168.x.x:port` 这类本地模型地址仍被 `base-config` 的明文禁令拦住,与注释承诺不一致。

**修复**:`network_security_config.xml` L22 加 `cleartextTrafficPermitted="true"`,debug 构建(`debuggable=true` 自动生效)真正完全放开明文 HTTP,与 base-config 的"release 默认 HTTPS"形成 debug/release 双行为。注释同步修订。

### P2.3 Repository ExecutionException 结构化 Outcome + Audit(代码改动 + 防御性兜底)

**评审问题**:`AgentRuntimeRepository` catch ExecutionException 直接抛 RuntimeException + `cause.getMessage()` 进 logcat——(1) 模型 API 错误回显用户输入会泄密;(2) 直接抛让 Repository 失去 audit 落库机会,UI 只能拿崩溃。

**修复**:catch ExecutionException 改为构造 `terminalOutcome(FAILED, PROTOCOL_ERROR, "scheduler execution failed: " + cause.getClass().getSimpleName())` + Audit 落库 + log 仅记 cause 类名(cause.getMessage() 不进 logcat)。

**触发条件**(代码注释已说明):Engine.execute 抛未捕获 RuntimeException 时触发。当前 `ModelCallExecutor` L114-120 + `ToolExecutor` L97-104 已包装各组件异常为 POLICY_HALT / EXECUTION_FAILED terminal,Engine 主路径只 catch InterruptedException;本 catch 块兜底未来引入的新代码路径(新组件 / 新 hook)抛未预期异常,以及 auditRepository / SessionManager.appendTurn 等 Engine 末端未 catch 路径故障。

**未加单测**:Engine 内部已 catch 包装各层异常,Repository 层 ExecutionException 实际触发需要 Engine 未来引入新代码路径异常,目前无法在测试中真实复现(Engine 是 final class,无法继承 mock)。代码改动作为防御性兜底保留,catch 块旁注释明确说明触发条件。

### 第八轮修复后的 V0.5.1 衔接点

| 衔接点 | V0.5.0 状态 | V0.5.1 切换 |
|---|---|---|
| SteerMailbox epoch gate | 仅 MemoryStore 写入有 epoch 保护 | epoch 检查扩到 SteerMailbox.offer / Engine.auditRedact / abort hook——clearUserData 后旧 epoch 的 Steer / 异步结果也被拒绝 |
| clearUserData 删 Room Audit | 未做 | clearUserData 同步按 userId + zone 范围删 AuditEventEntity(配合 P2.1 scope 接口) |
| ExecutionException 结构化 Outcome | catch 改 PROTOCOL_ERROR outcome | 加 cause chain 投影(top-N cause 类名 + 类型号),完整保留诊断信息(脱敏后)|
| 线程池生命周期 / 背压 | 固定 2 thread,scheduler.shutdown 钩子 | DynamicThreadPool(deadline 弹性 + 拒绝策略 + 监控) |
| 设备级 SQLCipher / KeyStore 验证 | 仅 JVM 契约测试 | `src/androidTest` + `inMemoryDatabaseBuilder` 真集成测试 |
| MemoryStore epoch 持久化 | SharedPreferences commit() 同步 | 改 Room MetadataEntity(MIGRATION_1_2)——多 process 一致性 + audit 表统一管理 |
| Room MemoryRecordEntity zone 隔离 | 索引已加,主路径未召回 | MemoryRouter 主路径接入 + Episodic / Semantic / Preference 4 层召回策略 |


## 16. 第九轮收尾评审 P1 修复明细

第八轮 P1.3 引入 epoch 后,第九轮评审指出**双来源失步**真实回归:Repository.epochCounter 从 0 开始、SharedPreferencesMemoryStore 启动从 prefs 加载 N,二者进程重启后不同步,导致"clearData → 重启 → 再 clearData"后所有新 preference 写入被错误拒绝(永久)。第九轮修复:**MemoryStore 是 epoch 单一权威**。**393→394 测试,0 回归**(+1 重启模拟测试)。

### P1.1 MemoryStore epoch 单一权威(代码 +1 测试)

**评审 bug 推演**:
1. 首次启动:Repository.epochCounter=0,SP.epoch=0(同步)
2. clearData → Repo.epochCounter=1, SP.epoch=1(SP.commit 落盘)
3. 进程 kill 重启:Repository.epochCounter=0(对象重建),SP.epoch=1(从 prefs 加载)
4. 再次 clearData → Repo.epochCounter=1, SP.epoch=2
5. 新 execute → 注入 epoch=1 → putPreferenceChecked(1) vs SP.epoch=2 → **拒绝**
6. 后续所有 preference 写入一直被错误拒绝

JVM 单元测试用 InMemoryMemoryStore 测不到(InMemory 不持久化,新对象 epoch=0,与 Repository.epochCounter=0 同步),只有 SharedPreferencesMemoryStore 跨进程重启才暴露。

**修复(评审建议全部采纳)**:
1. **`AgentRuntimeRepository`** 删除 `epochCounter` 字段;`execute()` 入口改为 `long capturedEpoch = memoryStore.currentEpoch()` 直接读 MemoryStore;`clearUserData()` 删除 `epochCounter.incrementAndGet()`,只调一次 `memoryStore.bumpEpoch()`(返回 long 用于日志)。
2. **`MemoryStore.bumpEpoch()`** 改为返回 `long`(新 epoch,方便日志 / 测试 / 诊断)。
3. **`InMemoryMemoryStore` / `SharedPreferencesMemoryStore`** 删除"epoch=0L 兼容路径"——评审指出静默放行 0L 会让安全语义被绕过(任何调用方传 0 都接受)。改为严格 `requestEpoch != currentEpoch → 拒绝`。
4. **default 方法保留兜底**:V0.4.x 老 MemoryStore 实现不覆盖 epoch 方法,默认 currentEpoch=0L / bumpEpoch 空操作 / putPreferenceChecked 调用 putPreference 不校验——保证 289 V0.4.x 测试 0 回归。生产路径只有 InMemoryMemoryStore / SharedPreferencesMemoryStore 实例化,二者严格 epoch。

**测试**(`AgentRuntimeRepositoryClearUserDataTest`):
- `epochGateRejectsStaleWriteAfterBump` 改写:不再依赖 epoch=0 兼容路径,改为严格"epoch=0 当前接受 / bumpEpoch → epoch=0 旧值拒绝 / epoch=1 当前接受"。
- `repositoryRestartKeepsEpochInSync` 新增(关键 bug 重现):用同一 InMemoryMemoryStore 模拟"持久化跨 Repository 重启"——V1 实例 clearData → memoryStore.epoch=1 → 销毁 V1 → 用同一 memoryStore 新建 V2(模拟进程重启,Repository 重建,MemoryStore 持久化保留 epoch)→ V2.execute 注入的 epoch 与 memoryStore.currentEpoch() 一致 → 写入被接受。**这是评审 bug 的核心断言**:旧实现因 Repository.epochCounter=1(重启回 0 + 1 次 clearData) != memoryStore.epoch=2,会让 clearData 后的新写入也被错误拒绝。

**`AgentRuntimeRepositoryClearUserDataTest.repositoryClearUserDataBumpsMemoryStoreEpoch`**:已有测试断言 memoryStore.currentEpoch() 自增,继续通过(原断言不依赖 Repository.epochCounter)。

### 第九轮评审其它项

| 项 | 评审结论 | 处理 |
|---|---|---|
| P1.2 assistant content 仍走 redact() | **与代码不符**(评审版本过期):AgentEngine L585 第七轮已改 `redactFreeText` | 跳过 |
| P2.1 clearData 不删 Audit | 真实,V0.5.1 衔接点已列 | 评审补充"分离 clearConversationAndMemory / eraseUserData + Room transaction + 主/副驾独立"已纳入衔接点 |
| P2.2 线程池生命周期与背压 | 真实,V0.5.1 衔接点已列 | 评审补充"有界队列 + QUEUE_FULL/BUSY + 关键指标"已纳入衔接点 |
| P2.3 设备级 SQLCipher/KeyStore/Migration 测试 | 真实,V0.5.1 衔接点已列 | 评审给出 androidTest 具体用例,作 V0.5.1 实现指南 |
| P2.4 EXECUTION_UNKNOWN 收敛 | V0.6.0 工作 | 第五轮 P1 衔接点已列;评审补充 PendingCommandRepository + queryCommandState 退避收敛,作 V0.6.0 实现指南 |

### 第九轮 P1 修复后的 V0.5.1 衔接点(细化)

| 衔接点 | V0.5.0 状态 | V0.5.1 切换 |
|---|---|---|
| MemoryStore epoch | 单一权威(InMemory + SharedPreferences),严格 epoch 校验 | Room MetadataEntity 持久化 epoch(MIGRATION_v1_v2),跨进程一致性 + audit 表统一管理 |
| SteerMailbox epoch gate | 仅 MemoryStore 写入有 epoch 保护 | epoch 检查扩到 SteerMailbox.offer / Engine.auditRedact / abort hook——clearUserData 后旧 epoch 的 Steer / 异步结果也被拒绝 |
| eraseUserData | clearUserData 仅清 Memory/Session/Mailbox | 分离 clearConversationAndMemory(scope) + eraseUserData(scope):后者按 userId+zone 删 AuditEventEntity / SessionHistoryEntity / MemoryRecordEntity,Room transaction 保证原子性 |
| Runtime 线程池 | 固定 2 thread,scheduler.shutdown 钩子 | DynamicThreadPool(有界队列 + QUEUE_FULL/BUSY 拒绝策略 + 指标:排队数 / 活跃模型调用数 / Tool timeout / EXECUTION_UNKNOWN / 抢占数) |
| 设备级验证 | JVM 契约测试 | src/androidTest:SQLCipher 明文不可读 / KeyStore 失败不创建明文 / Migration v1→v2 / 进程重启 epoch 行为 |


## 17. 第十轮收尾评审 P1 修复明细

第九轮 P1.1 落地 epoch 单一权威后,第十轮评审指出 **check-then-act race**:旧 `putPreferenceChecked` 在 epoch 校验通过后,`bumpEpoch` 与 `clear` 在另一线程并发执行,旧任务的 `putPreference` 在 SP 队列后落盘 → 偏好"复活"。第十轮修复:**epoch 自增 + clear×2 收敛为 MemoryStore 的原子操作**。**394→396 测试,0 回归**(+2 race 修复测试)。

### P1 epoch + clear 原子化(代码 +2 测试)

**评审 race 推演**(以 SharedPreferencesMemoryStore 为例):
1. Thread A `putPreferenceChecked(epoch=0)`:L88 读 `epoch.get()=0`,L89 校验 `0==0` 通过
2. Thread A 阻塞(模拟 Provider 在 IPC 中耗时尚未 commit)
3. Thread B `clearUserData`:bumpEpoch(commit) → epoch=1,clear(apply) → 异步排队
4. Thread A 继续:L97 `putPreference(userId, key, value)` → apply() 异步排队(SP 队列)
5. SP 队列按 FIFO 执行:先 clear 移除 key,再 putPreference 写回 key → 偏好"复活"

`InMemoryMemoryStore` 同样有 race:`bumpEpoch` 未 synchronized,与 `putPreferenceChecked`(synchronized)不互斥。

**修复(评审建议全部采纳)**:

1. **`MemoryStore.clearUserDataAndBump(userId1, userId2)` 新 default 方法**:
   - 默认实现非原子(`bumpEpoch + clear + clear` 顺序调用),V0.4.x / 测试 fake 用
   - 生产 `InMemoryMemoryStore` / `SharedPreferencesMemoryStore` 覆盖为原子

2. **`InMemoryMemoryStore`**:
   - `bumpEpoch` 加 `synchronized`(原未加,与 `putPreferenceChecked` / `clear` 不互斥)
   - `clearUserDataAndBump` 覆盖为 `synchronized long`:`epoch.incrementAndGet + values.remove(userId1) + values.remove(userId2)` 单锁原子

3. **`SharedPreferencesMemoryStore`**:
   - 新增 `private final Object lock = new Object()`
   - `putPreference` / `putPreferenceChecked` / `bumpEpoch` / `clear` / `clearUserDataAndBump` 全部在 `synchronized(lock)` 内
   - `putPreference` 改用 `commit()`(原 `apply()` 异步排队有"clear 后写"风险)
   - `clear` 改用 `commit()`(同上)
   - `clearUserDataAndBump` 覆盖:单次 `synchronized(lock)` + 单次 `Editor.commit()`(epoch + clear×2 打包为一个 SP 事务);commit 失败回滚内存 epoch + 抛 `IllegalStateException` 反馈上层
   - `bumpEpoch` commit 失败回滚 epoch + 抛异常

4. **`AgentRuntimeRepository.clearUserData()`** 改调原子 `memoryStore.clearUserDataAndBump("demo-driver", "demo-passenger")`(替代原 `bumpEpoch + clear×2`);commit 失败时直接抛异常反馈 caller

5. **`InMemoryMemoryStore` 改非 `final`**(原 `final` 阻碍测试子类化注入 hook)

**测试**(`AgentRuntimeRepositoryClearUserDataTest` +2 case):
- `clearUserDataAtomicallyRejectsConcurrentStaleWrite` —— sequential 契约:合法 epoch=0 写入 → clearUserData → 旧 epoch=0 写入被拒(杜绝复活)+ 当前 epoch=1 写入接受
- `concurrentStaleWritersCannotResurrectAfterClear` —— 4 个 writer 线程并发用 epoch=0 各写 100 次,中间主线程调 clearUserData,断言 driver 数据最终为空(synchronized 让 writer 与原子 clear 互斥,要么在 clear 前完成被清,要么在 clear 后用旧 epoch 被拒)

### P2.1 SHA-1 低熵可猜测(注释明确,V0.5.1 跟进)

**评审指出**:SHA-1 前 8 位对电话号码、固定指令、短地址等低熵文本可被枚举比对(攻击者拿审计 DB,枚举常见明文算 SHA-1 前 8 位与审计 sha 比对)。

**V0.5.0 处理**:保留 SHA-1 8 位(诊断价值 + 生产审计 DB 已加密 + scope 限定),`AuditRedactor.redactFreeText` 注释明确"低熵可猜测"限制。V0.5.1 跟进衔接点已加:改 HMAC-SHA-256(Keystore 密钥)+ 截断 16 位。

### P2.2 注释陈旧(已修)

`AgentRuntimeRepository.java` L345-346 原写"epoch 强一致推迟到 V0.5.1"——第九轮 P1.1 已落地 MemoryStore 单一权威,注释与实现不符。已改为"第九轮 P1.1 已落地 MemoryStore 单一权威 epoch(见 §16),第十轮 P1 已落地 epoch 自增 + clear×2 原子操作(见 §17);Steer / Tool 回写 epoch gate 仍推迟 V0.5.1"。

### 第十轮 P1 修复后的 V0.5.1 衔接点(细化)

| 衔接点 | V0.5.0 状态 | V0.5.1 切换 |
|---|---|---|
| MemoryStore epoch 原子 | InMemory synchronized + SP lock + commit 同步 | Room MetadataEntity + 事务 UPDATE epoch WHERE old_epoch=:expected(条件更新原子)|
| SteerMailbox epoch gate | 仅 MemoryStore 写入有 epoch 保护 | epoch 检查扩到 SteerMailbox.offer / Engine.auditRedact / abort hook——clearUserData 后旧 epoch 的 Steer / 异步结果也被拒绝 |
| AuditRedactor sha | SHA-1 8 位(低熵可猜测,注释明确)| HMAC-SHA-256(Keystore 密钥)+ 截断 16 位,杜绝枚举比对 |
| eraseUserData | clearUserData 仅清 Memory/Session/Mailbox(原子)| 分离 clearConversationAndMemory(scope) + eraseUserData(scope):后者按 userId+zone 删 AuditEventEntity / SessionHistoryEntity / MemoryRecordEntity,Room transaction 保证原子性 |
| Runtime 线程池 | 固定 2 thread,scheduler.shutdown 钩子 | DynamicThreadPool(有界队列 + QUEUE_FULL/BUSY + 指标)|
| 设备级验证 | JVM 契约测试 | src/androidTest:SQLCipher 明文不可读 / KeyStore 失败不创建明文 / Migration v1→v2 / 进程重启 epoch 行为 / SP commit 失败回滚验证 |


## 18. 第十一轮收尾评审 P1 修复明细

### P1 — `AgentTestViewModel.clearData()` 主线程阻塞 + 未捕获异常

**问题**:`AgentTestViewModel.clearData()` L132-139 在调用线程(主线程)直接调 `Repository.clearUserData()`——后者:
- 最多等 1000ms in-flight token 收敛(第七轮 P1.3 grace window);
- 同步 `SharedPreferences.commit()`(第十轮 P1 改 apply→commit);
- 失败时抛 `IllegalStateException`(第十轮 P1 commit 失败回滚)。

UI 主线程承接 → 卡顿 + 直接 crash 而不是显示"清空失败"。`executor` 字段已存在(L42,newFixedThreadPool(2)),本应被复用。

**修复**:`presentation/viewmodel/AgentTestViewModel.java` L132-139 改写为后台 executor + try/catch + 进度 UI:

```java
public void clearData() {
    long operationId = operationSequence.incrementAndGet();
    cancelAllOperations();
    uiState.setValue(new AgentUiState(true, repository.getActiveModelGateway(),
            "正在清空上下文与记忆…"));
    executor.submit(() -> {
        try {
            repository.clearUserData();
            postIfCurrent(operationId, new AgentUiState(false, repository.getActiveModelGateway(),
                    "已清空上下文与用户记忆。"));
        } catch (RuntimeException error) {
            Log.e(TAG, "[ViewModel] clearData failed " + safeMessage(error), error);
            postIfCurrent(operationId, new AgentUiState(false, repository.getActiveModelGateway(),
                    "清空失败 [" + safeMessage(error) + "]"));
        }
    });
}
```

要点:
- `operationSequence.incrementAndGet()` 返回 operationId,`postIfCurrent` 防止陈旧 clearData 状态覆盖较新 execute() 的 outcome(反之亦然)。
- 主线程立即 setValue "正在清空"(loading=true) → 后台 postValue "已清空" / "清空失败:[原因]"。
- catch RuntimeException(SP commit 失败 IllegalStateException 是其子集)→ App 不再 crash。
- 复用现有 `safeMessage` + `postIfCurrent` 模式,0 新依赖。

**测试**:无新增 JVM 测试。ViewModel 涉及 LiveData + ExecutorService,加 Robolectric 仅为此一测试超出 V0.5.0 风险预算;P2.1 androidTest 插桩测试(V0.5.1)会覆盖真实 SP commit + clearUserDataAndBump + UI 行为。

**已记录的 flaky**:`TaskSchedulerTest.driverDoesNotPreemptPassengerWriteTask:154` NPE——`driverStarted.countDown()` 在 `driverStartMillis.set()` 之前,await 返回时 Long 引用可能仍为 null。与本轮修复无关,V0.5.1 修测试时序(顺序倒过来:set 先,countDown 后)。

### V0.5.1 衔接点(第十一轮新增 / 强调)

| 编号 | 项目 | 备注 |
|---|---|---|
| P2.1 | ViewModel clearData 行为覆盖 | src/androidTest:Robolectric 或真机插桩验证"正在清空" → "已清空" / "清空失败" 状态流转 |
| P2.2 | AuditRedactor SHA-1 → HMAC-SHA-256 | 见第九轮衔接点表;延迟到 V0.5.1 一起改 |
| P2.3 | TaskSchedulerTest 时序修复 | set 先 countDown 后,消除 Long 引用 NPE flake |


## 19. V0.5.1 C 路线落地(评审遗留衔接收尾 + flaky 修复)

V0.5.0 第十一轮遗留 3 个衔接点(P2.1 ViewModel 行为覆盖 / P2.2 SHA-1 → HMAC / P2.3 时序修复)+ 2 个 flaky 测试。V0.5.1 第一段(C 路线)以 6 个 Stage 全量收掉这些遗留,在干净基线上推进 B 路线(Token 真切换 / 上下文压缩 / Memory 召回 / Room androidTest 工程化)。

| Stage | 主题 | 关键变更 | 测试增量 |
|---|---|---|---|
| 1 | TaskSchedulerTest race | `driverStarted.countDown()` 在 `driverStartMillis.set()` 之前 → NPE race;runner 体内顺序倒过来(set 先,countDown 后) | 0(修已有 case) |
| 2 | Repository dual-deadline flake | `repositoryTimeoutWithDispatchedWriteContainsExecutionUnknown` 用 `CountDownLatch` 替代 50ms / 2000ms 真时序,budget 50ms → 200ms;Provider 用 latch.await() 阻塞(模拟不可中断 IPC),repo.execute 在调用线程跑,主线程显式控制时序 | 0(重写已有 case) |
| 3 | DAO deleteByUserZone | Trajectory / SessionHistory / MemoryRecord 3 个 DAO 加 `@Query("DELETE FROM ... WHERE userId = :userId AND zone = :zone") int deleteByUserZone(...)`,AuditEventEntity 无 userId 列(V0.5.0 schema 未定)暂不参与;Room @Query 保持期 = CLASS,JVM 测试无法反射读 SQL,改用 fake 行为契约 + Stage 5 instrumented 真路径验证 | +6 契约测试(JVM) |
| 4 | AuditRedactor SHA-1 → HMAC-SHA-256 | 新增 `AuditDigest` 接口(core)+ `Sha1AuditDigest`(默认,V0.5.0 兼容)+ `KeystoreHmacAuditDigest`(AndroidKeyStore HMAC-SHA-256,KEY_ALIAS=`matrix_audit_hmac_key`,截断 16 hex);`AuditRedactor.setDigest()` 注入,`AgentEngine.setAuditDigest()` 透传;`AppContainer.createAuditDigestSafely()` 装配失败 fail-degrade 退回 SHA-1(HMAC 是诊断增强而非安全边界,与 master_key fail-closed 语义不同——审计数据继续 SQLCipher 加密落盘) | +11(HmacAuditDigestTest 9 + AuditFreeTextRedactionTest 2) |
| 5 | androidTest 骨架 | build.gradle 启用 `testInstrumentationRunner` + `sourceSets.androidTest.java.srcDirs=['src/androidTest/java']` + androidTestImplementation 依赖;`SmokeTest` 3 case 验证 APK 装到 emulator 后 SQLCipher + KeyStore 链路通;`DaoDeleteByUserZoneInstrumentedTest` 4 case 真实 Room(inMemory)验证 3 表 deleteByUserZone 隔离 + 跨表 transaction | +7(androidTest) |
| 6 | clearUserData 删 Room Audit | `AuditRepository` 加 `default void clearByUserZone(userId, zone)`(no-op);`RoomAuditRepository` 双构造器(单参 V0.5.0 兼容 / 5 参 APK 主路径持有 database + 3 DAO),5 参在 `database.runInTransaction` 内跨表原子删 trajectory + session_history + memory_record;`AgentRuntimeRepository.clearUserData()` 末尾追加 `auditRepository.clearByUserZone("demo-driver","DRIVER")` + `("demo-passenger","PASSENGER")`;`AppContainer.createAuditRepositorySafely()` 切到 5 参装配 | +7(androidTest:RoomAuditRepositoryClearByUserZoneInstrumentedTest 4 + KeystoreHmacAuditDigestTest 3) |

**测试总数**:JVM **413**(396 → +6 契约 + +11 HMAC = 413);androidTest **0 → 14**(Smoke 3 + DAO 4 + RoomAuditRepo 4 + KeystoreHmac 3)。

### Stage 1 — TaskSchedulerTest NPE race

`TaskSchedulerTest.driverDoesNotPreemptPassengerWriteTask:154` 拆箱 NPE。根因:runner 体内 L136 `driverStarted.countDown()` 在 L137 `driverStartMillis.set(...)` 之前——主线程 `await()` 返回时 Long reference 仍为 null。修复:倒序,`set` 先,`countDown` 后。

### Stage 2 — Repository dual-deadline flake

`AgentRuntimeRepositoryWriteDispatchSemanticsTest.repositoryTimeoutWithDispatchedWriteContainsExecutionUnknown` 偶有失败。根因:Engine 内部 deadline(48ms ToolExecutor future.get) 与 Repository `future.get(50ms)` 几乎同时触发,谁先取决于调度——若 Engine 先返回,Repository 不进 `catch (TimeoutException)`,`token.cancel()` 从未被调 → 断言失败。

修复:budget 50ms → 200ms(分离 Engine 内部 48ms deadline 与 Repository 200ms future.get);Provider 从 `Thread.sleep(2000ms)` 改 `CountDownLatch.await`(吞 InterruptedException 模拟不可中断 IPC);repo.execute 在调用线程跑,主线程等 `toolDispatched` 后 sleep 900ms(200 budget + 500 grace + buffer),验证 EXECUTION_UNKNOWN + token.isCancelled + audit 落库 + round-trip 一致。

### Stage 3 — DAO deleteByUserZone 契约

3 个 DAO 各加 `@Query("DELETE FROM <table> WHERE userId = :userId AND zone = :zone") int deleteByUserZone(String userId, String zone)`。AuditEventDao 不参与——`AuditEventEntity` V0.5.0 schema 无 userId 列,且 V0.5.0 无写入路径,V0.5.2 multi-user audit 落库时再补。

JVM 契约测试(`DaoDeleteByUserZoneContractTest` 6 case)用 fake DAO 验证 zone+user 双重隔离;Room @Query 保持期 = CLASS(非 RUNTIME),JVM 反射读不到 SQL 字符串,真实 SQL 执行留 Stage 5 instrumented 验证。

### Stage 4 — HMAC-SHA-256 摘要

V0.5.0 `redactFreeText` 走 SHA-1 8 位 hex:`[redacted:chars=N,sha=xxxxxxxx]`。第八轮 P1.2 已登记:SHA-1 8 位对低熵文本(电话号码 / 短地址 / 固定指令)可枚举比对——攻击者拿到审计 DB 后枚举常见电话号码 SHA-1 前 8 位反推原文。

V0.5.1 升级:
- core 新增 `AuditDigest` 接口(`digest(String)` 返回 hex,`prefix()` 返回 "sha" / "hmac")——core 不依赖 Android Keystore,只依赖 JRE `javax.crypto.Mac`。
- 默认 `Sha1AuditDigest`——保留 V0.5.0 行为,JVM 测试零回归。
- platform 新增 `KeystoreHmacAuditDigest`——`AndroidKeyStore` 派生 HMAC-SHA-256 key(独立别名 `matrix_audit_hmac_key`,与 master_key 不污染),截断 16 hex(64-bit 抗碰撞,远高于 SHA-1 8 位 2^32)。
- `AuditRedactor` 持 `volatile AuditDigest digest = new Sha1AuditDigest()`,`setDigest()` 注入(volatile 让 AppContainer 装配期注入对 AgentEngine 工作线程立即可见)。
- `redactFreeText` 输出改为 `[redacted:chars=N,<prefix>=<hex>]`。
- `AgentEngine.setAuditDigest()` 透传(不扩展 13 参构造器,setter 模式)。
- `AppContainer.createAuditDigestSafely()` 装配失败 fail-degrade(与 master_key fail-closed 语义差异:HMAC 是诊断增强,失败降级 SHA-1 仅丢失抗枚举能力,审计数据继续 SQLCipher 加密落盘,可用性优先)。

### Stage 5 — androidTest 骨架 + smoke + DAO instrumented

V0.5.0 build.gradle 占位 `sourceSets.androidTest.java.srcDirs = []` 升级为 `['src/androidTest/java']`;`testInstrumentationRunner 'androidx.test.runner.AndroidJUnitRunner'`;依赖 `androidx.test:runner:1.6.2` + `androidx.test.ext:junit:1.2.1` + `androidx.room:room-testing:2.7.0` + `androidx.test:core:1.6.1`(无 Espresso / Robolectric / UI Automator,纯 JVM 风格)。

`SmokeTest` 3 case:
- `appContextPackageNameCorrect` —— ApplicationProvider 返回 com.matrix.agent
- `keyStoreMasterKeyAliasCreated` —— AndroidKeyStoreMasterKeyProvider.getPassphrase() 触发 `matrix_db_master_key` 别名创建
- `matrixDatabaseOpensWithKeystorePassphrase` —— 端到端首次验证 SQLCipher + AndroidKeyStore 链路

`DaoDeleteByUserZoneInstrumentedTest` 4 case:用 `Room.inMemoryDatabaseBuilder`(不走 SQLCipher)验证 Stage 3 的 3 个 DAO `deleteByUserZone` 在真实 Room 执行:
- trajectoryDeleteByUserZoneKeepsOtherZone
- memoryRecordDeleteByUserZoneIsolatesZoneAndUser
- sessionHistoryDeleteByUserZoneIsolatesZoneAndUser
- deleteByUserZoneInTransactionAcrossThreeTables(覆盖 Stage 6 RoomAuditRepository.clearByUserZone 的 transaction 路径)

CI 无 emulator 时 Stage 5 仅本地手跑(`./gradlew connectedDebugAndroidTest`,emulator API 28+);JVM 全绿保证 0 回归。

### Stage 6 — clearUserData 删 Room Audit + 设备级验证

V0.5.0 第十一轮 P1 修复了 ViewModel 主线程阻塞,但 Repository.clearUserData 仍只清 SP / SteerMailbox / Session,Room Audit 数据残留——"清空"按钮后历史仍可被 trajectory query 回放。Stage 6 补齐:

生产代码:
- `AuditRepository` 加 `default void clearByUserZone(userId, zone)`(no-op,兼容 Noop / JVM test fake / V0.5.0 单参 RoomAuditRepository)。
- `RoomAuditRepository` 双构造器:
    - 单参 `(TrajectoryDao)` V0.5.0 兼容,`clearByUserZone` 仅删 trajectory 表(无 database 引用)。
    - 5 参 `(MatrixDatabase, TrajectoryDao, SessionHistoryDao, MemoryRecordDao)` APK 主路径,持有 database + 3 DAO,`clearByUserZone` 在 `database.runInTransaction` 内跨表原子删除。
- `AgentRuntimeRepository.clearUserData()` 在 `sessionManager.clear()` 后追加 `auditRepository.clearByUserZone("demo-driver","DRIVER")` + `("demo-passenger","PASSENGER")`(try/catch Throwable,fail-open)。
- `AppContainer.createAuditRepositorySafely()` 切到 5 参装配。

设备级 androidTest:
- `RoomAuditRepositoryClearByUserZoneInstrumentedTest` 4 case:跨表 atomic 删 + 跨用户/zone 隔离 + null args 不抛 + 单参构造器仅删 trajectory(契约保护)。
- `KeystoreHmacAuditDigestTest` 3 case:digest 跨实例稳定(同 KeyStore 别名)+ 不同输入差异 + 别名创建后存在于 KeyStore。
- ViewModel clearData UI 行为(状态流转 + 失败 toast)未覆盖:第十一轮 P1 已在 JVM 层验证 ViewModel 路径不抛,Android LiveData + ExecutorService 走 main looper 的 instrumented 测试延后到 V0.5.2(无 Robolectric 约束下的成本/收益不匹配)。

### V0.5.1 衔接点(转入 B 路线)

| 编号 | 项目 | 备注 |
|---|---|---|
| P1.1 | SteerMailbox / abort hook epoch gate | clearUserData 期间旧 epoch 的 Steer / 异步结果仍能进入 Engine——V0.5.1 B 路线 Steer 处理 + abort hook 加 epoch 校验 |
| P1.2 | Room MemoryRecordEntity 主路径接入 | V0.5.0 预建表,V0.5.1 替换 SharedPreferencesMemoryStore 为 Room + 启动期一次性迁移 + 召回算法 |
| P1.3 | AgentEngine 主路径 Token 切换 | jtokkit 已落地,但 AgentBudget 仍用 char/4 估算;B 路线切真 Token + 上下文压缩 |
| P1.4 | DynamicThreadPool + Episodic/Semantic 召回 | V0.5.0 占位 EmptyEpisodicMemorySource / EmptySemanticMemorySource,B 路线接入 SessionHistoryDao / embedding |
| P1.5 | LLM-based IntentClassifier | V0.4.3 KeywordIntentClassifier 仍是默认,B 路线接 LLM 路径 |
| P2.2 | `audit_event` 表 userId 列迁移 | 见 §19.1 P2.2,V0.5.2 写增量事件前必须先迁 schema v2 |

## 19.1 V0.5.1 C 路线评审反馈修复(2 个 P1 + 2 个 P2)

V0.5.1 C 路线 6 个 Stage 落地后,评审再点 4 项(2 P1 + 2 P2)。本节给出每项的根因 / 修复 / 测试增量,以及确认后的测试总数。

| 编号 | 主题 | 根因 | 修复 | 测试增量 |
|---|---|---|---|---|
| P1.1 | HMAC 装配失败 fail-degrade 退回 SHA-1 | `AppContainer.createAuditDigestSafely` 装配 `KeystoreHmacAuditDigest` 失败时回退 `Sha1AuditDigest`——Stage 4 注释为"HMAC 是诊断增强而非安全边界",但评审指出这会让 Keystore 异常设备上重新引入 SHA-1 8 位低熵枚举风险(Stage 4 想堵的洞自堵) | 新增 `core/agent/UnavailableAuditDigest`(fail-closed:固定输出 `[redacted:chars=N,digest=unavailable]`,不保留可比对摘要);`AppContainer.createAuditDigestSafely` 失败回退改为 `UnavailableAuditDigest`;`AuditDigest` / `Sha1AuditDigest` / `KeystoreHmacAuditDigest` javadoc 同步语义(fail-closed 而非 fail-degrade) | +5 JVM(`UnavailableAuditDigestTest`) |
| P1.2 | Audit 删除失败被吞,UI 仍显示"已清空" | `RoomAuditRepository.clearByUserZone` try/catch Throwable + log,`AgentRuntimeRepository.clearUserData` 继续吞 → 用户得到完整成功提示,但 trajectory / session_history / memory_record 可能仍在 | `AuditRepository.clearByUserZone` 返回类型 `void` → `ClearOutcome`(SUCCESS / PARTIAL_FAILURE / FAILURE / NOT_APPLICABLE);`RoomAuditRepository` 实现 null args → FAILURE / transaction rollback → FAILURE / 全成功 → SUCCESS;新增 `data/ClearUserDataOutcome`(driver + passenger 两 zone 组合 + `auditFailed()` + `summary()`);`AgentRuntimeRepository.clearUserDataDetailed()` 返回 `ClearUserDataOutcome`,`clearUserData()` 保留 void 兼容;`AgentTestViewModel.clearData` if (outcome.auditFailed()) 显示"上下文与偏好已清空,但审计日志删除失败,请稍后再次点击清空"(保留重试按钮) | +3 JVM(`AgentRuntimeRepositoryClearUserDataTest` 新增 3 case) + 4 androidTest 改断言 |
| P2.1 | DAO 删除 instrumented 用 `Room.inMemoryDatabaseBuilder`,未验证 SQLCipher 真库 | Stage 5 / 6 instrumented 跑的是 Room 内存库,SQLCipher 真库 CRUD / delete / reopen 持久化无覆盖;`SmokeTest` 仅验证 DB 能打开 | 新增 `RoomAuditRepositorySqlCipherInstrumentedTest` 3 case(真实 `MatrixDatabase.getInstance(context, key)` + `AndroidKeyStoreMasterKeyProvider`,close + `resetForTest()` + reopen 验证:写入持久 / clearByUserZone 删除持久 / 3 表 transaction 删除持久);时间戳前缀 + 不删 db 文件(SQLCipher 持久是验证目标) | +3 androidTest |
| P2.2 | `audit_event` 表无 userId 列,无法 scoped 擦除 | V0.5.0 schema `AuditEventEntity` 无 userId,V0.5.1 Stage 3 / 6 都未补;V0.5.2 写增量事件前若不迁,会重引入"清空后 audit 残留" | V0.5.1 仅文档登记(本节 + README V0.5.1 段落)。V0.5.2 写 AuditEvent 增量事件前必须先:(1) `MatrixDatabase` schema v1 → v2 migration 加 `AuditEventEntity.userId` + `zone` 列;(2) `AuditEventDao` 加 `deleteByUserZone(userId, zone)`;(3) `RoomAuditRepository` 5 参 → 6 参(加 `AuditEventDao`)并 `clearByUserZone` 跨 4 表 transaction;(4) `AuditRepository.persist` 写入路径补 userId / zone 字段;(5) instrumented 加 audit_event 真库 delete + reopen case | 0(文档登记) |

**测试总数演进**:JVM **413 → 421**(+5 UnavailableAuditDigestTest + +3 AgentRuntimeRepositoryClearUserDataTest);androidTest **14 → 17**(+3 SQLCipher instrumented);`assembleDebug` + `assembleDebugAndroidTest` 双绿。

**Stage 2 flaky 修复产品端补强**:Stage 2 仅测试侧用 latch 改时序(消除大部分 flake),P1.2 评审同时发现 Engine 内部 deadline 与 Repository `future.get` 用同一 deadline 时仍有 race(Engine 先返回则 Repository 不进 catch TimeoutException,token.cancel 从未调)。产品端补强:`AgentRuntimeRepository` 新增 `SAFETY_MARGIN_MILLIS=20L`,`future.get` waitMillis 改为 `Math.max(1L, remaining - SAFETY_MARGIN_MILLIS)`——Repository 永远比 Engine 内部 deadline 早 20ms 触发 TimeoutException,从根本上消除 dual-deadline race。Stage 2 测试侧 latch 改造 + 产品端 SAFETY_MARGIN 双保险,连续跑 10 次无 flake。

## 19.2 V0.5.1 C 路线评审反馈收尾(3 个 P2)

§19.1 的 2 P1 + 2 P2 修复后,评审再点 3 个 P2(本轮全部落地)。本节给出每项的根因 / 修复 / 测试增量,确认后 V0.5.1 C 路线全部收尾。

| 编号 | 主题 | 根因 | 修复 | 测试增量 |
|---|---|---|---|---|
| P2.3 | `AuditRedactor` 默认 digest 仍为 SHA-1 | §19.1 P1.1 把 `AppContainer` 装配失败兜底改为 `UnavailableAuditDigest`,但 `AuditRedactor` 字段默认值 `new Sha1AuditDigest()`(L84)与 `setDigest(null)` 回退(L106)仍走 SHA-1。当前 AppContainer 不会触发(走 `KeystoreHmacAuditDigest` 主路径),但未来遗漏 HMAC 注入(JVM 测试 fake / 新装配路径遗漏 setDigest 调用)会**静默**降低隐私级别——这正是 Stage 4 想堵的洞 | `AuditRedactor.digest` 默认值 + `setDigest(null)` 兜底都改 `UnavailableAuditDigest`(fail-closed:固定 `[redacted:chars=N,digest=unavailable]`);`AgentEngine.setAuditDigest` javadoc 同步语义;测试侧 `AuditFreeTextRedactionTest` 加 `newSha1Redactor` helper,所有验证 SHA-1 格式契约的 case **显式** `setDigest(new Sha1AuditDigest())` opt-in,不再依赖默认;`sha1DefaultDigestRemainsBackwardCompatible` 重写为 3 个 case:`defaultDigestIsFailClosedUnavailable` + `setDigestNullIsFailClosedUnavailable` + `sha1DigestIsOptInViaSetDigest` | +2 JVM(净增,3 新 case 替换 1 旧 case) |
| P2.4 | `ClearOutcome` 计数语义混乱 | §19.1 P1.2 的 `ClearOutcome.success(int tablesCleared)` 把参数同时写进"尝试表数"和"清除表数",但 `RoomAuditRepository` 传入的其实是删除**行数**(trajectory + session + memory 行数总和);`partialFailure(tablesCleared, tablesAttempted, ...)` 参数顺序也反;同一字段在不同调用点语义不同,UI 后续若按计数渲染就会有歧义 | 拆为 3 个独立字段:`tablesAttempted`(本次尝试清理的表数,RoomAuditRepository 5 参路径 = 3 / 单参路径 = 1)、`tablesSucceeded`(实际删成功的表数,transaction 提交 = attempted / rollback = 0)、`rowsDeleted`(各表 deleteByUserZone 返回值的累计行数);`success` / `partialFailure` 签名相应更新;`RoomAuditRepository.clearThreeTablesAtomic` 调 `success(3, 3, totalCleared)`、单参路径调 `success(1, 1, deleted)`;2 个 instrumented 测试断言改为 `getTablesAttempted() == 3 && getTablesSucceeded() == 3 && getRowsDeleted() >= 3` 精确表达"3 表都尝试 + 3 表都成功 + 至少删了 3 行" | 0 JVM(改字段名 + 调用点 + 断言,无新 case) |
| P2.5 | `audit_event` 仍无法按 user 清除(登记强化) | §19.1 P2.2 已登记,本轮再次点名强调——V0.5.2 写 `PRE_TOOL` / `POST_TOOL` 增量事件前**必须**先迁 schema,否则会重引入"清空后事件审计残留"。当前 `AuditEventEntity` V0.5.0 schema 无 userId 列,且 V0.5.1 无写入路径,因此**不是现时泄漏** | 本版仅文档登记强化(本节 + README V0.5.1 段落 + §19 衔接表 P2.2 行)。**V0.5.2 写入路径阻塞项清单**:(1) `MatrixDatabase` schema v1 → v2 migration,`AuditEventEntity` 加 `userId` + `zone` 列 + 复合索引;(2) `AuditEventDao` 加 `deleteByUserZone(userId, zone)`;(3) `RoomAuditRepository` 5 参 → 6 参(加 `AuditEventDao`),`clearByUserZone` 跨 4 表 transaction;(4) `AuditRepository.persist` 增量事件写入路径补 userId / zone 字段(`AgentOutcome` 不变,从 `AgentRequest` 取);(5) instrumented 加 audit_event 真库 delete + reopen case(参考 `RoomAuditRepositorySqlCipherInstrumentedTest` 模式);(6) Code-Review 文档 V0.5.2 段落补"audit_event userId 迁移"小节 | 0(文档登记) |

**测试总数演进**:JVM **421 → 423**(+2 AuditFreeTextRedactionTest);androidTest **17** 不变(改 2 个 instrumented case 断言,无新增);`assembleDebug` + `assembleDebugAndroidTest` 双绿。

## 20. 总结

V0.5.0 经过 11 轮评审,3 个 P1(第七轮)+ 2 个 P1(第八轮回归)+ 1 个 P1(第九轮 epoch 单一权威)+ 1 个 P1(第十轮 epoch + clear 原子化)+ 1 个 P1(第十一轮 ViewModel 主线程阻塞 + 未捕获异常)+ 5 个 P2(第八轮)全部落地,**396 JVM 测试 + assembleDebug 全绿,0 回归**。V0.5.1 C 路线(§19)以 6 个 Stage 收掉评审遗留衔接点(P2.1/P2.2/P2.3)+ 2 个 flaky 测试 + clearUserData 删 Room Audit,JVM 测试 **413**(+17)+ androidTest **0 → 14**;V0.5.1 C 路线评审反馈(§19.1,2 P1 + 2 P2)修复后,JVM 测试 **421**(+5 UnavailableAuditDigest + +3 ClearUserData)+ androidTest **17**(+3 SQLCipher 真库)+ Stage 2 dual-deadline race 产品端 SAFETY_MARGIN 补强;V0.5.1 C 路线评审反馈收尾(§19.2,3 P2)修复后,JVM 测试 **423**(+2 AuditRedactor 默认 fail-closed 收紧)+ androidTest 不变(2 case 断言改精确)。已知 flaky 全部消除。已落地特性:

- **四层 Memory 抽象**(Working / Episodic / Semantic / Preference)+ 双维度 `MemoryScope(userId, zone)` 隔离 + MemoryRouter 召回入口
- **SQLCipher + Room 持久化骨架**(`MatrixDatabase` 加密 DB + KeyStore 失败抛异常降级 Noop,**绝不静默落明文**)+ 4 张 Entity + 4 个 DAO(zone 隔离查询)+ `TrajectoryCodec` round-trip + `RoomAuditRepository` 同步阻塞 fail-open + `AndroidKeyStoreMasterKeyProvider` AES-256-GCM(commit() 同步落盘 + 内存清零)
- **AgentEngine 5 个出口点 + Repository 编排层兜底 Audit**——任何终态(含 Repository 自构造 + Scheduler 内部生成)都进 Audit,`requestId` 幂等 + REPLACE 语义
- **抢占 outcome 与 trajectory 终态语义一致**——`Trajectory.rewriteStopReason` + `TaskScheduler.remapIfPreempted`,round-trip 一致
- **写操作 timeout / cancel 语义边界**——`ToolResult.Status.EXECUTION_UNKNOWN` + `CapabilityProvider.isAbortable/abortIfSupported/queryCommandState` + `ToolExecutor` 区分 readOnly/write
- **Repository 外层 deadline/interrupt 不再绕过 EXECUTION_UNKNOWN 保护**——`TaskState` / `StopReason` 新增 EXECUTION_UNKNOWN + `GRACE_WINDOW_MILLIS=500` 收敛窗口仅对写操作生效
- **网络安全配置**(第七轮 P1.1)—— release 默认 HTTPS,仅 loopback + debug 放行明文
- **审计自由文本 fail-closed**(第七轮 P1.2 + 第八轮回归修复)—— assistant content **+** ToolObservation 默认路径 **+** knowledge.answer capability audit template 全部 fail-closed,凭据正则识别不出的 PII 不再落盘
- **clearUserData "取消—等待—清空" 序列**(第七轮 P1.3)+ **data epoch 强一致保护**(第八轮 P1.3 引入,第九轮 P1.1 修正为 MemoryStore 单一权威,第十轮 P1 改"epoch + clear×2"为原子 `clearUserDataAndBump`)—— 1000ms grace 配合 MemoryStore epoch 拒绝写入,不可中断 Provider 完成后也无法把陈旧偏好写回;MemoryStore 是 epoch 单一权威(SharedPreferencesMemoryStore 跨进程持久化),避免双来源失步导致 clearData 重启后所有 preference 写入被错误拒绝;`clearUserDataAndBump` 单锁 + SP commit 单事务,杜绝 check-then-act race 导致偏好"复活"
- **queryByRequest 加访问域**(第八轮 P2.1)—— `queryByRequest(userId, zone, requestId)` + DAO SQL 双重 WHERE,杜绝跨用户 / 跨 zone 枚举 requestId
- **NSC debug-overrides 真正允许 debug 明文**(第八轮 P2.2)—— `cleartextTrafficPermitted="true"` 让 debug 构建真放开,注释/实现对齐
- **Repository ExecutionException 结构化 Outcome + Audit**(第八轮 P2.3)—— catch 改 PROTOCOL_ERROR outcome + log 仅记 cause 类名,不再抛 RuntimeException 也不再让 cause.message 进 logcat
- **MemoryRecaller 异常降级**——recall 抛异常时降级 base prompt 不崩溃任务
- **PromptBuilder 骨架**(`PromptSegment` + `DefaultPromptBuilder`,V0.5.1 接入主路径)
- **jtokkit 1.1.0** + `Tokenizer` 接口 + `JtokkitTokenizer`(BPE O200K_BASE)+ `CharFallbackTokenizer`(默认装配)+ `AgentBudget` 暴露 token getter(char/4 保守估算,主路径 V0.5.1 切换)

**结论**:**可作为模拟器 / 架构验证版**,V0.5.1 C 路线已全部收尾(§19 + §19.1 + §19.2),V0.5.1 B 路线继续推进 SteerMailbox / abort hook epoch gate + DynamicThreadPool + 设备级 SQLCipher 验证 + Room MemoryRecordEntity zone 隔离真生效 + LLM-based IntentClassifier + AgentEngine 主路径 token 切换 + **`audit_event` 表 userId 列迁移(§19.2 P2.5,V0.5.2 写增量事件前必做阻塞项)**。

---

## 21. V0.5.2 全范围落地(11 个 Stage)

V0.5.1 C 路线(§19)落地后,用户决定把 V0.5.1 B 路线(Token 真切换 / Room Memory 主路径 / PromptBuilder 接入 / 上下文压缩 / Episodic+Semantic 召回)与 V0.5.2 增量能力(Gemini 原生 Tool Calling / 错误分类 + 受控重试 / 流式响应 / LLM IntentClassifier / SteerMailbox epoch gate / 删除 V0.4.x @Deprecated API)**合并为一个版本一次性交付**——避免"V0.5.1 B 半成品 + V0.5.2 增量"双轨维护。11 个 Stage 按依赖图串行提交(1→2→3→4→5→6→7→8→9→10→11),每 Stage 独立可回滚,双绿基线(423 JVM + 17 androidTest + assembleDebug)是硬门槛。最终落地 **JVM 556**(+133)/ **androidTest +1**(`DynamicThreadPoolConcurrencyTest`)/ `assembleDebug` + `assembleDebugAndroidTest` 全绿,0 V0.4.x / V0.5.0 / V0.5.1 测试回归。

### Stage 总览

| Stage | 主题 | 测试增量 |
|---|---|---|
| 1 | audit_event userId 迁移 + Room Migration v1→v2 + Tokenizer 接口装配 | JVM +6 / androidTest +2 |
| 2 | Room MemoryRecordEntity 主路径接入(替换 SP) | JVM +8 / androidTest +2 |
| 3 | AgentEngine Token 双轨(Stage 3a)→ 主路径(Stage 3b 推迟 V0.5.3) | JVM +5 |
| 4 | PRE_TOOL / POST_TOOL / POLICY / STEER 增量事件接入 | JVM +5 |
| 5 | SteerMailbox epoch gate(drop + audit)+ abort hook 部分 | JVM +4 |
| 6 | PromptBuilder 接入主路径 + ConversationCompressor 骨架 | JVM +2 + 9 |
| 7 | Room WAL + AuditEventRecorder batch + Episodic/Semantic 召回 | JVM +6 / androidTest +1 |
| 8 | 上下文压缩(LLM 摘要 + heuristic 降级) | JVM +6(净 +9 ConversationCompressorTest,与 Stage 6 重叠) |
| 9 | LLM-based IntentClassifier(替代规则 boolean) | JVM +5 |
| 10 | Gemini 原生 Tool Calling + 错误分类 + 受控重试 + 流式响应(流式推迟 V0.5.3) | JVM +12(11 GeminiNativeToolCallingTest + 12 RetryPolicyTest 与 Stage 10 前期重叠) |
| 11 | DynamicThreadPool + 删除 V0.4.x @Deprecated API(部分推迟 V0.5.3) | JVM +8 / androidTest +1 |

### Stage 1 — audit_event userId 迁移 + Room Migration v1→v2 + Tokenizer 接口装配

`AuditEventEntity` 加 `@NonNull public String userId;`(默认 `""`)+ `@Index(value={"userId","zone"}, name="idx_audit_user_zone")`;`MatrixDatabase` 切 `version=2`,加 `MIGRATION_1_2`(1 条 ALTER + 1 条 CREATE INDEX,语句最小化);`AuditEventDao` 加 `deleteByUserZone` + `queryByUserZone`;`RoomAuditRepository` 5 参→6 参(加 `AuditEventDao`),`clearThreeTablesAtomic` 改名 `clearFourTablesAtomic`(trajectory + session_history + memory_record + audit_event 跨 4 表 transaction);`Tokenizer` 接口(`count(String)` / `count(AgentMessage)` / `estimateFallback`)+ `JtokkitTokenizer`(BPE O200K_BASE,失败 catch 退 fallback);`AppContainer` 装配 Tokenizer(暂不注入 AgentEngine 主路径,Stage 3 注入)。

**关键决策**:audit_event 迁移 `userId` 默认 `''`(历史行无 RequestId→userId 映射,按主路径查不到但仍按 requestId 可查);`Tokenizer` 接口先落地但不接主路径(纯加法,Stage 3 才切);jtokkit 用 O200K_BASE(GPT-4o 系列),与 Anthropic 自家 BPE 误差 10-15%,budget 6 倍余量可接受。

**测试**:`AuditEventDaoContractTest`(5 case,userId 列写入 + deleteByUserZone 双重隔离 + queryByUserZone)+ `JtokkitTokenizerTest`(CJK / 英文 / 混合 token 计数)+ `MatrixDatabaseMigrationTest`(androidTest,MigrationTestHelper 验证 v1→v2 升级路径)。JVM +6 / androidTest +2。

### Stage 2 — Room MemoryRecordEntity 主路径接入

新增 `RoomMemoryStore implements MemoryStore`(epoch 持久化到 memory_record 特殊行 `userId="__system__", zone="__system__", layer="preference", key="__epoch__"`,所有写操作 `synchronized(lock)` 与 SharedPreferencesMemoryStore 同样"epoch 校验 + 写"原子语义);`RoomMemoryMigrator` 启动期一次性迁移(遍历 prefs.getAll() 写入 memory_record,SP 清空在 Room transaction commit 成功后);`SharedPreferencesMemoryStore` 保留作 fallback;`AppContainer` 主路径切 RoomMemoryStore(fail-open 与 createAuditRepositorySafely 语义对齐)。

**关键决策**:epoch 持久化从 SP 单 key 迁到 memory_record 特殊行(避免 epoch 跨 Room/SP 双源失步);历史 zone-less 偏好的 zone 推导统一写 DRIVER_SEAT(保守默认,副驾 zone 区分留 V0.5.3);SP 历史迁移"尽力而为"(失败仅 log + 保留 SP 数据,迁移幂等可重试)。

**测试**:`RoomMemoryStoreTest`(11 case,epoch 加载 / putPreferenceChecked / clearUserDataAndBump 原子性)+ `RoomMemoryMigratorTest`(5 case,SP→Room + 幂等 + zone 推导)+ `RoomMemoryStoreInstrumentedTest`(androidTest,真库 epoch + clear 事务原子性)。JVM +8 / androidTest +2。

### Stage 3 — AgentEngine Token 双轨(3a 落地 / 3b 推迟)

`AgentBudget` 加 token 维度字段(`maxMessageTokens=2048` / `totalInputTokens` / `maxAssistantTokens`,旧 char-based getter 保留);`AgentEngine` 构造器加 `Tokenizer tokenizer`(可空,空时退 `Tokenizer.estimateFallback`);`estimateConversationChars` 改名 `estimateConversationTokens`,新增 `estimateConversationTokensAsChars` 双轨统计方法——`Log.d` 同时记 char 与 token,**但不影响 `appendMessageWithBudget` 决策(仍用 char)**;`AppContainer` 把 Stage 1 装配的 Tokenizer 透传到 AgentEngine。

**Stage 3b 推迟原因**:Stage 3b 切换前要先跑 `TokenizerAccuracyTest`(androidTest 真实 Provider 对比 jtokkit vs Anthropic usage.input_tokens / OpenAI usage.total_tokens / Gemini usageMetadata.promptTokenCount,允许 ±15% 误差),验证误差在可接受范围。本版仅做双轨观察期(Stage 3a),主路径切换留 V0.5.3 完成。

**测试**:`TokenizerAccuracyTest`(androidTest,真实 Provider 对比)+ Stage 1 的 JtokkitTokenizerTest 兼容回归。JVM +5 / androidTest +1。

### Stage 4 — PRE_TOOL / POST_TOOL 增量事件接入

新增 `AuditEventRecorder`(封装 AuditEventEntity 落库,4 个 API:`recordPreTool` / `recordPostTool` / `recordPolicyDecision` / `recordSteer`,内部 `Executors.newSingleThreadExecutor()` 异步 insert,主路径 fire-and-forget,fail-open 异常仅 log);`AuditEventTypes` 常量类;`AgentEngine` 5 个出口点埋点:Tool 执行前(snapshot 前)`recordPreTool`、Tool 执行后(snapshot 写入前)`recordPostTool`、PolicyEngine.reject 时 `recordPolicyDecision`、SteerMailbox.drain 拿到 FORCE_TOOL/DEFER 时 `recordSteer`;`argsJson` / `resultJson` 过 `AuditRedactor.redactArguments` 字段级脱敏(与 TrajectoryEntity snapshots 同样保护级别);`AppContainer` 装配 AuditEventRecorder(database=null 时退化 NOOP)。

**关键决策**:与 audit_event userId 迁移(Stage 1)同一 schema v2,不引入 v3;不新表(V0.5.0 已预建表,Stage 1 加 userId 列,本 Stage 仅写入不同 type 值);AuditEventRecorder fail-open + 异步,DB 异常仅 log 不向上传播;STEER 事件 payload 仅记 type + payloadChars(**不记 payload 内容**,REPROMPT payload 可能含用户输入有 PII 风险)。

**测试**:`AuditEventRecorderTest`(8 case,PRE/POST/POLICY/STEER 写入 + fail-open)+ `AuditEventRecorderInstrumentedTest`(androidTest,SQLCipher + AuditRedactor 脱敏验证)。JVM +5 / androidTest +1。

### Stage 5 — SteerMailbox epoch gate + abort hook(部分实装)

`SteerMailbox` 内部 `Steer` 包装为 `StampedSteer{Steer inner; long epoch;}`;`offer(sessionId, steer)` 重载为 `offer(sessionId, steer, epoch)`,旧签名保留转发(测试兼容);`drain(sessionId, currentEpoch)` 遍历时 `if (stamped.epoch < currentEpoch) { dropped++; auditRecorder.recordSteerDroppedStale(...); } else { retained.add(stamped.inner); }`;新增 `advanceEpoch(long newEpoch)` 由 Repository.clearUserData 推送;`StaleSteerHandler` 封装 drop + audit 逻辑(避免 SteerMailbox 直接依赖 AuditEventRecorder);`AgentRuntimeRepository.clearUserData()` 内 bumpEpoch 后锁内同步 advanceEpoch;`AgentEngine.drainSteerBeforeLlm` 改用 drain(sessionId, memoryStore.currentEpoch())。

**abort hook 推迟原因**:`abortIfStale` 让 AgentLoop 在迭代边界收到 stale epoch 后**主动退出当前迭代**——Stage 5 仅实装"drop + audit",Loop 仍会跑完当前迭代(消耗 1 次 LLM 调用预算)。完整 abort hook(不消耗 LLM 预算的迭代边界退出)留 V0.5.3,与"IntentClassifier 异步预路径"同评估。

**关键决策**:epoch 维度用 **MemoryStore epoch**(已是 data epoch 单一权威,Repository.clearUserData 已 bump 它);stale steer drop 后 audit 走 Stage 4 AuditEventRecorder.recordSteer(type=`STEER_DROPPED_STALE`,payloadChars=…,**不记 payload 内容**);不删除 `clearAll()`(V0.4.3 API,与 epoch gate 语义不冲突);现有 `SteerMailboxTest`(V0.4.1)用 `offer(sessionId, steer)` 不传 epoch 兼容。

**测试**:`SteerMailboxEpochGateTest`(4 case,stale drop + audit + 不影响新 epoch 的 steer)。JVM +4。

### Stage 6 — PromptBuilder 接入主路径

`AgentEngine` 新增字段 `PromptBuilder promptBuilder`(构造器注入,可空,空时退内联逻辑 V0.4.x 测试兼容);`buildSystemPrompt(request)` 改为 `if (promptBuilder != null) { ... DefaultPromptBuilder.join(segs); } else { /* 旧内联逻辑保留 */ }`;`AppContainer` 装配 `PromptBuilder promptBuilder = new DefaultPromptBuilder()` 透传 AgentEngine。

**关键决策**:AgentEngine.buildSystemPrompt 内联逻辑不删(作 fallback,默认构造器 promptBuilder=null 仍走旧逻辑,V0.4.x 测试零回归);不引入 PromptComposer(V0.5.1 注释提到 V0.5.2 替代,本次不引入新抽象,DefaultPromptBuilder.join 已够用,PromptComposer 留 V0.5.3);双轨验证合并为一步(行为字面等价,风险极低)。

**测试**:`PromptBuilderEquivalenceTest`(2 case,AgentEngine.buildSystemPrompt 内联逻辑 vs DefaultPromptBuilder.join 多组(actor, zone, inputSource, recalledMemory)字面一致)。JVM +2。

### Stage 7 — Room WAL + AuditEventRecorder batch + Episodic/Semantic 召回

`MatrixDatabase` builder 链加 `.setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)`(显式声明,Room 2.7 + API 16+ 默认就是 WAL)+ `enableWriteAheadLogging` 兜底;`AuditEventRecorder` Stage 4 的单线程 Executor 升级 batch 队列——`ConcurrentLinkedQueue<AuditEventEntity> pending`,`ScheduledExecutorService.scheduleAtFixedRate(scheduledFlush, 200ms, 200ms)`,单 batch 上限 50 条;`flushSync()` 直接调用 `drainAndFlush()`(并发安全,单消费者语义);Terminal 事件(AgentOutcome.persist)同步触发 flush 等待;队列上限 500(超过丢最老 PRE_TOOL,POST_TOOL 优先保留);新增 `EpisodicMemorySourceImpl`(实现 `EpisodicMemorySource`,内部 `SessionHistoryDao.queryByUserZone(userId, zone, limit=5)` 召回近期会话,LRU cache 按 userId+zone 缓存 5 分钟)+ `SemanticMemorySourceImpl`(实现 `SemanticMemorySource`,内部 `MemoryRecordDao.queryByUserZoneLayer(userId, zone, "semantic")` 召回偏好层语义记忆,**用关键词匹配 + score 排序**,V0.5.3 再补 embedding);`AppContainer` 替换 EmptyXxx 为真实 Room-backed 召回源。

**关键决策**:WAL 默认开启(Room 2.7 + API 16+),Stage 7 显式声明 + 加 enableWriteAheadLogging 兜底,避免 OEM ROM 关闭 WAL 影响并发写;AuditEventRecorder batch 上限 50 条 / 200ms 触发,Terminal 事件同步 flush 等待——保证"任务返回后立即查询 audit"无竞态;**不引入 vector DB / embedding**(硬约束纯 Java,Android 端无开箱即用向量数据库),语义召回用关键词 + score(V0.5.0 MemoryRecordEntity 已有 score 字段),V0.5.3 评估 on-device embedding(如 TensorFlow Lite);Episodic 召回内部加 in-memory LRU cache(按 userId+zone 缓存 5 分钟)避免拖慢 buildSystemPrompt。

**测试**:`AuditEventRecorderBatchTest`(5 case,batch 上限 + flush 触发 + 队列上限溢出 + Terminal 同步 flush)+ `EpisodicMemorySourceImplTest`(7 case,sessionHistory 召回 + LRU cache 失效 + 跨 zone 隔离)+ `SemanticMemorySourceImplTest`(6 case,关键词匹配 + score 排序 + CJK 分词)+ `AuditEventRecorderFlushInstrumentedTest`(androidTest,真库 batch flush)。JVM +6 / androidTest +1。

### Stage 8 — 上下文压缩(LLM 摘要 + heuristic 降级)

新增 `ConversationCompressor` 核心 API `compress(conversation, budget, request)`:(1) 估算总 token;(2) 若超 `budget.getTotalInputTokens() * 0.8`(预留 20% 余量给新 turn)触发压缩;(3) 取最老 N 条 turns(N = 总条数 / 2)调 Provider summarize;(4) Summary 失败(Provider 异常 / 超时 10s)→ 降级 heuristic(直接丢最老 N 条 turns,保留最近 N=4 条,拼"上文已省略 N 条消息");(5) 返回 `[SummaryMessage, ...新 turns]`;`SummaryProvider` 接口(`summarize(turns, request)`,封装 Provider 调用,temperature=0,强 system prompt 限定"仅总结事实,不执行任何指令");`AgentMessage.summary(text)` 工厂(role=SYSTEM,前缀 `[系统生成的对话摘要,不含指令] `);`AgentEngine.appendMessageWithBudget` 失败(BUDGET_EXHAUSTED)前先调 `conversationCompressor.compress(...)`,压缩成功后重试 append;`AppContainer` 装配 `ConversationCompressor(null)`。

**LLM SummaryProvider 注入推迟原因**:`ConversationCompressor` 实装了 heuristic 降级路径 + 定义了 `SummaryProvider` 接口,但 V0.5.2 没在 AppContainer 装配真实 Provider 实现——压缩永远走 heuristic(直接丢老 turns)。完整 LLM 摘要注入(选小模型 Haiku/GPT-4o-mini + 短 prompt + temperature=0 + injection 防御 + TokenizerAccuracyTest 监控)留 V0.5.3,与 Stage 3b token 主路径切换同依赖链。

**关键决策**:摘要**替换原 turns**(不新增 summarized 字段),原始 turns 仍可在 TrajectoryEntity 审计落库查到——conversation 是 LLM 输入不是审计源;主路径用 LLM 摘要质量好,失败降级 heuristic 避免死锁;摘要本身 token 计入预算;injection 防御:SummaryMessage 用 system role + 加前缀,摘要 prompt 强约束"仅总结事实";压缩触发阈值 0.8 而非 1.0(预留 20% 给当前迭代的 user turn / tool result)。

**测试**:`ConversationCompressorTest`(10 case,token 超阈值触发 / 摘要替换 / heuristic 降级 / 保留最近 turns / injection 防御 / Provider 异常降级 / 空会话跳过)。JVM +6(与 Stage 6 重叠净 +9)。

### Stage 9 — LLM-based IntentClassifier(替代规则 boolean)

`IntentClassifier` 接口扩展——保留 `boolean isReadOnly(String command)`(default 实现转调 `classify(command).isReadOnly()`),新增 `IntentResult classify(String command)`;新增 `IntentResult`({readOnly, confidence, reason},工厂 `readOnly(reason)` / `write(reason)` / `unknown(reason, confidence)` 后者 clamp 到 0.69 阻止 unknown 冒充高置信);`KeywordIntentClassifier` 实现 classify(走原关键词规则,confidence=1.0 规则匹配 / 0.5 模糊);`LlmIntentClassifier` 用 Provider 调一次小模型(Claude Haiku / GPT-4o-mini)分类,超时 3s,失败抛 `LlmClassificationException`,temperature=0,in-memory LRU cache(按 command hash 缓存 5 分钟,128 entries);`FallbackIntentClassifier` 包装 LlmIntentClassifier + KeywordIntentClassifier,Llm 失败 / 低置信度 catch 后退 Keyword(reason 加 `primary-low-confidence:` / `primary-failed:` 前缀);`AppContainer` 装配 LlmIntentClassifier(用 ModelApiClient + ModelConfig)注入 Repository。

**关键决策**:接口签名不变(硬约束)——`isReadOnly(String)` 保留 default 实现,新增 `classify` 是重载扩展,V0.4.x 测试零回归;LLM 分类用小模型 + 短 prompt(单 turn,无 tools)延迟 < 500ms 理想,超时 3s 强制降级 Keyword;不删除 KeywordIntentClassifier——它仍是 fallback + 测试用 + 离线模式(DemoModelGateway)默认;confidence 阈值 < 0.7 视为 unknown,Repository 按写操作保守处理。

**测试**:`IntentClassifierStage9Test`(17 case,IntentResult 工厂 + isHighConfidence 阈值 + KeywordClassifier.classify + FallbackClassifier 高置信透传 / 低置信退 fallback / 异常退 fallback + 旧 isReadOnly default impl 转发 + LlmIntentClassifier.parseLlmResponse READ/WRITE/UNKNOWN)。JVM +5。

### Stage 10 — Gemini 原生 Tool Calling + 错误分类 + 受控重试 + 流式响应(流式推迟)

四件事合并到一个 Stage(都是 Provider 协议层改造):

**(a) Gemini 原生 Tool Calling**:`ModelApiClient.callGeminiWithTools`(含 CancellationToken 重载),请求体 `tools=[{functionDeclarations:[{name, description, parameters}]}]`(双层嵌套),`contents[{role:"user"|"model", parts[{text}|{functionCall:{name, args}}|{functionResponse:{name, response}}]}]`;`buildGeminiToolRequest` / `parseGeminiToolResponse` / `toGeminiContents` / `toGeminiFunctionDeclaration` / `mapGeminiFinishReason`(STOP→STOP, MAX_TOKENS→LENGTH, SAFETY/RECITATION/OTHER→NONE);**Gemini functionCall 不带 id 字段**,Runtime 按 turn 内 index 合成 `gemini-<index>` 作 stepId,仅作内部 ASSISTANT ↔ TOOL 消息关联,发回 Gemini 时按 name 关联(协议无 id);`LlmModelGateway.useGeminiNative` 分支路由;`ModelConfig.validate` 允许 `GEMINI_GENERATE_CONTENT + NATIVE_TOOL_CALLING`;Gemini schema 用 OPENAI_STRICT 投影(Gemini schema 子集与 OpenAI strict 高度重合,无独立投影配置);ASSISTANT.tool_calls 序列化为 `model.parts[functionCall]`,TOOL 消息合并到下一条 user.parts[functionResponse](Gemini 用 user role 承载 functionResponse,与 OpenAI 的 role=tool 不同)。

**(b) 错误分类**:新增 `ModelApiException` 抽象基类 + 5 子类:`RateLimitException`(429,retryable)/ `ServerException`(5xx,retryable)/ `ClientException`(4xx,not retryable)/ `NetworkException`(not retryable,AgentEngine 转 TIMEOUT 终态)/ `TimeoutException`(not retryable);`getMessage()` 不泄露 HTTP body / endpoint(API key / 用户 PII 可能被网关回显),仅返回错误类型 + sanitized endpoint(去 query string,去 apiKey 参数);`ModelApiClient.post()` 非 2xx 按 HTTP 状态码抛对应子类。

**(c) 受控重试**:新增 `RetryPolicy`(`MAX_ATTEMPTS=3` / `INITIAL_DELAY_MS=500` / `BACKOFF_FACTOR=2.0` / `JITTER=0.2`,500ms → 1000ms → 2000ms + ±20% jitter 避免雷鸣群);`invokeWithRetry(CallableWithRetry)` 包装 `ModelApiClient.complete` / `callAnthropicWithTools` / `callOpenAiWithTools` / `callGeminiWithTools`;仅对 `ModelApiException.isRetryable()=true` 重试,4xx / 网络异常 / 超时不重试(非 ModelApiException 如 JSONException 直接抛);重试上限 3 次硬编码防雪崩;`computeDelay(attempt)` 实例方法(非 final,测试可覆盖为 1ms 加速)。

**(d) 流式响应 SSE 推迟 V0.5.3**:计划原要求新增 `callAnthropicStreaming` / `callOpenAiStreaming` / `callGeminiStreaming`(OkHttp EventSource SSE 解析)+ `StreamHandler.onDelta/onComplete/onError` 回调 + `ModelConfig.streaming` 字段参数化(默认 false,流式路径传 true)。本版仅完成 (a)(b)(c),(d) 推迟——完整 SSE 实装需要新 OkHttp EventSource 依赖、StreamHandler 接口、AgentEngine Loop 改造(onDelta 仅累积 chunk 不动 conversation,onComplete 才写,onError 转 TIMEOUT),且现有同步路径不替换(默认关闭),V0.5.3 与"主路径 token 切换"同依赖链落地。

**关键决策**:四件事合并是 Provider 协议层改动集中,合并避免"Gemini 原生 Tool Calling 已切但错误分类未做"半成品状态(切到原生 Tool Calling 后 429/5xx 必须有重试,否则单次失败崩 Loop);Gemini 原生与 LlmPlanner.cleanJson 解析冲突——`useGeminiNative` 时跳过 cleanJson 直接读 functionCall(`LlmPlanner.legacy` 路径仅在 NATIVE_TOOL_CALLING 之外的 STRUCTURED_JSON_COMPATIBILITY 用);错误分类不向上泄露 HTTP body / 状态码到 Log(避免泄露 API key / endpoint);重试引发请求放大——jitter 20% 避免雷鸣群,重试上限 3 次硬编码。

**测试**:`GeminiNativeToolCallingTest`(11 case,buildRequestHasGeminiCanonicalShape / buildRequestDropsSystemMessagesFromConversation / parseTextOnlyResponseReturnsDirectAnswer / parseFunctionCallResponseSynthesizesIdAndMapsCapability / parseResponseWithTextAndFunctionCall / unregisteredFunctionCallIsRejected / maxTokensShortCircuitBeforeExecutingFunctionCall / stopWithEmptyContentDegradesToNone / missingCandidatesReturnsNone / multiTurnConversationSerializesAssistantToolCallsAndToolResults / multiTurnWithUnmergedUserMessageAfterToolResult)+ `RetryPolicyTest`(12 case,RateLimit/Server 重试 / Client/Network/Timeout 不重试 / 非 ModelApiException 不重试 / 最大尝试次数耗尽 / computeDelay 单调递增 + jitter 范围 / getMessage 不泄露 HTTP body)+ `ModelApiClientContractTest.geminiNativeModeIsAllowedAndBuildsCanonicalRequest`(Gemini NATIVE_TOOL_CALLING 白名单)+ `nativeModeRejectsUnsupportedProviderProtocol` 改用 OLLAMA_CHAT。JVM +12(净增,Stage 10 前期 RetryPolicy 12 + 后期 Gemini 11 + ContractTest 修订 1)。

### Stage 11 — DynamicThreadPool + 删除 V0.4.x @Deprecated API(部分推迟)

新增 `DynamicThreadPool`(封装 `ThreadPoolExecutor`,默认 core=2 / max=8 / queue=32 / keepAlive=60s,`allowCoreThreadTimeOut=true` 让池子真能缩到 0,`CallerRunsPolicy` 反压不丢任务,守护线程 + 命名 `matrix-pool-N`);`TaskScheduler` / `ModelCallExecutor` / `ToolExecutor` 各加 `DynamicThreadPool` 重载构造器(保留旧 `int parallelism` 构造器测试兼容);共享池 ownership 跟踪——`TaskScheduler` / `ToolExecutor` 加 `ownsPool` 字段,`shutdown()` 仅在自己拥有池时关闭(共享池由外部 DynamicThreadPool 持有者统一管理,避免误关影响其他 executor);`AppContainer` 装配 `DynamicThreadPool sharedPool = new DynamicThreadPool(2, 8, 32)`,三个 executor 共享(避免 3 个独立池子各 2-4 线程在车机 4 核 SoC 上总 6-12 线程互相竞争 CPU)。

**@Deprecated 清理(已删)**:`DemoPlanner.java`(全文件,grep 零引用)/ `PlannerExecutor.java`(全文件,grep 零引用)/ `TraceEvent.java`(全文件,PlannerExecutor 内部依赖)/ `Planner.java`(全文件,LlmPlanner 解除 `implements Planner` 后零引用,`plan()` 方法仍保留)/ `SteerMailbox.clearForTesting()`(grep 零引用)/ `PolicyDecision.deny(String)`(grep 零引用)/ `CapabilityDefinition.Builder.parameter(ToolParameterDefinition)`(grep 零引用)。

**@Deprecated 清理(推迟 V0.5.3)**:`TaskPlan.java`(仍被 `ModelApiClient.planWithTools` legacy 单轮 OpenAI tool calling 路径 / `LlmPlanner.plan` / `LlmClient` 接口 / 多个测试 load-bearing 引用,删除需重构 legacy 路径)/ `AgentOutcome.getResults()`(20+ 测试断言用它,删除需批量迁移到 `getInternalResults()`)/ `CapabilityDefinition.Builder.verificationRequired(boolean)`(`VerifyMethodMigrationTest` 专门验证它的 bridge 行为,删除会丢测试覆盖)。计划要求"全项目 grep 验证零引用后才删"——这三项 grep 出来不零,本版不强删,正式登记 V0.5.3。

**关键决策**:DynamicThreadPool 共享单池让 TaskScheduler / ModelCallExecutor / ToolExecutor 统一监控 + 收敛线程数,max=8 提供 4 倍余量,queue=32 + CallerRunsPolicy 队列满时让调用线程执行反压不死锁;`shutdown()` ownership 跟踪是共享池语义必备——TaskScheduler 测试调 `shutdown()` 时不应该把 ModelCallExecutor / ToolExecutor 也在用的 shared 池关掉;删 @Deprecated 必须严格 grep 验证零引用,本版删的 7 项全部确认零引用,V0.5.0 / V0.5.1 测试零回归。

**测试**:`DynamicThreadPoolTest`(8 case,defaultPoolStartsWithCoreSizeAndExpandsUnderLoad / queueFullTriggersCallerRunsPolicy / coreThreadsIdleOutAfterKeepAlive / rejectPoolSizeValidation / sharedPoolSurvivesWhenOneOwnerStopsUsingIt / sharedPoolShutdownClosesForAllOwners / submittedCallableReturnsValue / queueSizeReflectsPendingTasks)+ `DynamicThreadPoolConcurrencyTest`(androidTest,8 并发 × 3 owner 无死锁)。JVM +8 / androidTest +1。

### 21.1 测试总数演进

| Stage | JVM 增量 | androidTest 增量 | JVM 累计 | androidTest 累计 |
|---|---|---|---|---|
| V0.5.1 baseline | - | - | 423 | 17 |
| Stage 1 | +6 | +2 | 429 | 19 |
| Stage 2 | +8 | +2 | 437 | 21 |
| Stage 3 | +5 | +1 | 442 | 22 |
| Stage 4 | +5 | +1 | 447 | 23 |
| Stage 5 | +4 | 0 | 451 | 23 |
| Stage 6 | +11(PromptBuilder 2 + Compressor 9) | 0 | 462 | 23 |
| Stage 7 | +18(Batch 5 + Episodic 7 + Semantic 6) | +1 | 480 | 24 |
| Stage 8 | -1(ConversationCompressorTest 已在 Stage 6 计入,本 Stage 净 -1 整理) | 0 | 479 | 24 |
| Stage 9 | +17(IntentClassifierStage9Test) | 0 | 496 | 24 |
| Stage 10(前期)| +12(RetryPolicyTest) | 0 | 508 | 24 |
| Stage 10(后期)| +12(GeminiNativeToolCallingTest 11 + ContractTest 修订 1) | 0 | 520 | 24 |
| Stage 11 | +8(DynamicThreadPoolTest) | +1(DynamicThreadPoolConcurrencyTest) | **556** | **25**(文件数 +1,实际 case 数 +1) |
| **V0.5.2 total** | **+133** | **+1 文件 / +1 case** | **556** | **25 case / 10 文件** |

### 21.2 V0.5.3 衔接(本版延期项登记)

V0.5.2 计划范围内的 4 项实装时主动收缩(范围评估后发现比计划更复杂 / 依赖更深 / 不在主路径关键路径),正式登记 V0.5.3:

| 编号 | 项 | V0.5.2 范围 | 实装情况 | V0.5.3 实装要求 |
|---|---|---|---|---|
| D1 | **流式响应 SSE** | Stage 10 (d) | 跳过 | `callAnthropicStreaming` / `callOpenAiStreaming` / `callGeminiStreaming` + `StreamHandler.onDelta/onComplete/onError` + OkHttp EventSource 依赖 + `ModelConfig.streaming` 参数化(默认 false)+ AgentEngine Loop 改造(onDelta 累积 / onComplete 写 conversation / onError 转 TIMEOUT)+ mock SSE server 测试 |
| D2 | **LLM SummaryProvider 注入** | Stage 8 | 仅 heuristic,Provider 接口已定义未注入 | `AppContainer` 装配真实 `SummaryProvider` 实现(小模型 Haiku / GPT-4o-mini)+ 短 prompt + temperature=0 + injection 防御 + `TokenizerAccuracyTest` 监控误差 + 与 D3 token 主路径切换同依赖链 |
| D3 | **AgentEngine 主路径 token 真切换(Stage 3b)** | Stage 3 | 仅双轨观察(Stage 3a) | `appendMessageWithBudget` / `enforceMessageBudget` 切 `estimateConversationTokens` + `budget.getMaxMessageTokens/getTotalInputTokens`;`getMaxAssistantTokens` enforcement(totalInputTokens + maxAssistantTokens <= modelContextWindow,超 BUDGET_EXHAUSTED);改预算相关测试断言 char→token |
| D4 | **abort hook 异步预路径** | Stage 5 | 仅 drop + audit,Loop 仍跑完当前迭代 | `SteerMailbox.abortIfStale(sessionId, currentEpoch)` 让 AgentLoop 在迭代边界收到 stale epoch 信号后**主动退出当前迭代**(不消耗 LLM 调用预算,正常返回 ABORTED_BY_STALE_EPOCH 终态)+ 与"IntentClassifier 异步预路径"同评估 |
| D5 | **删除剩余 3 项 @Deprecated** | Stage 11 | grep 发现仍有引用,推迟 | `TaskPlan.java`(需先重构 `ModelApiClient.planWithTools` legacy 路径)/ `AgentOutcome.getResults()`(需先批量迁移 20+ 测试到 `getInternalResults()`)/ `CapabilityDefinition.Builder.verificationRequired(boolean)`(需先迁移 `VerifyMethodMigrationTest`) |
| D6 | **副驾 zone 推断规则** | Stage 2 注释提及 | 默认 DRIVER_SEAT | RoomMemoryStore.putPreference zone 推断规则(V0.5.2 历史迁移保守写 DRIVER_SEAT,V0.5.3 评估按 Actor / SeatPosition 推断) |
| D7 | **on-device embedding 替代关键词匹配** | Stage 7 注释提及 | 关键词 + score | SemanticMemorySourceImpl 评估 TensorFlow Lite on-device embedding 替代关键词匹配 |
| D8 | **AgentBudget 旧 char-based 字段彻底删除** | Stage 3 注释提及 | getter 保留 | 删除 `maxMessageChars` / `totalInputChars` getter(需先迁移所有引用)|
| D9 | **PromptComposer 抽象** | Stage 6 注释提及 | DefaultPromptBuilder.join | 引入 PromptComposer 替代 DefaultPromptBuilder.join |
| D10 | **SessionContext 结构化改造** | (V0.5.0 既有) | Deque<String> | 改为 List<Message> 结构化 |
| D11 | **master_key + audit_hmac_key 别名迁移** | (V0.5.0 既有) | 直接派生 | 迁移到 Keystore-keyed 派生 |

---

## 22. V0.5.2 评审 P1/P2 修复(2026-08)

V0.5.2 全范围 11 个 Stage 落地后,评审紧接着对**生产代码**做了完整审查(不是测试),发现 **5 P1 + 3 P2 + 1 zone 不一致**——全部是生产路径层面的实质缺陷,不是测试覆盖问题。本节是修复落地记录。

### 22.1 修复总览

| 编号 | 主题 | 根因 | 修复 |
|------|------|------|------|
| P1-1 | 调度池与 I/O 池嵌套等待死锁 | Stage 11 共享 DynamicThreadPool:TaskScheduler 占线程等 ModelCall/Tool 子任务,子任务在同池排队 | 拆为 `schedulerPool(2,2,32)` + `ioPool(2,8,32)` 物理隔离;TaskState 加 `REJECTED` + StopReason 加 `REJECTED`;三 executor catch `RejectedExecutionException` 返回 REJECTED / POLICY_HALT / EXECUTION_UNKNOWN terminal |
| P1-2 | RoomMemoryMigrator 数据丢失窗口 | per-entry try/catch + 循环外无条件 `spSource.clear()` | 全量 entities 收集后单次 transactionRunner.runInTransaction,任一失败 transaction rollback + SP 不清空(下次启动重试) |
| P1-3 | clearUserData 后异步 AuditEvent 重新写回 | AuditEventRecorder 只有 batch flush,无 user/zone/epoch 维度 drop | 加 `advanceEpoch(newEpoch)` 全局 stale gate + `dropByUserZone(userId, zone, hint)` per-zone gate;`isStale` 在 record/flush/submit 三个路径都检查(双保险);Repository.clearUserDataDetailed 在 clearAuditSafe 前调 dropByUserZone |
| P1-4 | ConversationCompressor 装配 null + 截断拆开 tool_call/observation | (a) AppContainer 装的是 `new ConversationCompressor(null)`;(b) 按 conversation.size()/2 截断 | (a) 实装 `LlmSummaryProvider`,接入 Provider;(b) 重构为 transaction-aware 截断:assistant.tool_calls + observation / POLICY / EXECUTION_UNKNOWN 同 transaction 原子保留 |
| P1-5 | LLM IntentClassifier 没进 APK 主路径 | AppContainer 注入 KeywordIntentClassifier.INSTANCE | AppContainer 装配 FallbackIntentClassifier(new LlmIntentClassifier(modelClient, savedConfig), Keyword),saved=null 时退 Keyword;Repository 加 setIntentClassifier setter(volatile 字段,支持运行时切换) |
| P2-1 | 增量审计事件把 sessionId 当 userId | AgentEngine 4 处 recordXxx 用 `request.getSessionId()` | 全部替换为 `ActorUsers.userIdOf(request)`,与 Repository.clearAuditSafe 的 "demo-driver"/"demo-passenger" 字面一致 |
| P2-2 | AuditEvent payloadJson 手工拼接不合法 JSON | safe() 仅 replace `"`,反斜杠 / 换行 / 控制字符仍会破坏结构 | 全部 buildXxxPayload 改用 `org.json.JSONObject.put + toString`,自动 escape |
| P2-3 | 429/5xx 重试不感知取消与剩余 deadline | `Thread.sleep(delay)` 不感知 cancellation/deadline | 新重载 `invokeWithRetry(action, CancellationToken, deadlineMillis)`:retry 前 cancel/deadline 检查;sleep 期间通过 abort hook 让 cancel 立即唤醒;delay 截断到 remaining |
| 附加 | stale-steer zone "DEMO-DRIVER" 不一致 | SteerMailboxStaleHandler 用 `sessionId.toUpperCase()` = "DEMO-DRIVER" | 显式 sessionId → (userId, zone, actor) 映射:"demo-driver" → ("demo-driver","DRIVER","DRIVER");未识别 → ("","","") |

### 22.2 关键复用点

修复过程中最大化复用 V0.5.x 既有抽象:

1. **`ActorUsers.userIdOf(request)`**(V0.5.0)—— P2-1 集中 userId 字面量
2. **`org.json.JSONObject`**(Android 内置)—— P2-2 替代手工拼接,与 TrajectoryCodec / ModelApiClient 同基础
3. **`CancellationToken.registerAbortHook / removeAbortHook`**(V0.4.1)—— P2-3 让 RetryPolicy 感知取消
4. **`AgentRequest.getDeadlineAtMillis()`**(V0.4.1)—— P2-3 deadline 截断
5. **`TaskState.EXECUTION_UNKNOWN` / StopReason 模式**(V0.5.1 第六轮 P1)—— P1-1 加 REJECTED 同模式
6. **`AuditEventDao.deleteByUserZone`**(V0.5.2 Stage 1)—— P1-3 直接复用
7. **`SummaryProvider` 接口**(V0.5.2 Stage 8)—— P1-4 实装 LlmSummaryProvider
8. **`LlmClient` 接口**(V0.5.0 Stage 3)—— P1-4 让 LlmSummaryProvider 在 JVM 测试中可 mock
9. **`FallbackIntentClassifier` + `LlmIntentClassifier`**(V0.5.2 Stage 9)—— P1-5 类已存在,AppContainer 只需装配
10. **`DynamicThreadPool`**(V0.5.2 Stage 11)—— P1-1 加 RejectedExecutionHandler 构造器重载,默认 CallerRunsPolicy 兜底

### 22.3 测试总数演进

V0.5.2 baseline **556 JVM** → 修复后 **616 JVM**(+60)。assembleDebug 全绿,0 V0.4.x/V0.5.0/V0.5.1 回归。

| 修复 | JVM 增量 | 主要测试 |
|------|----------|---------|
| P1-1 | +15 | DynamicThreadPoolRejectedPolicyTest / TaskStateRejectedContractTest / ToolExecutorRejectedExecutionTest / ModelCallExecutorRejectedExecutionTest / TaskSchedulerRejectedExecutionTest / androidTest SharedThreadPoolDeadlockInstrumentedTest(暂未实装,V0.5.3) |
| P1-2 | +5 | RoomMemoryMigratorFailureTest(含 transaction rollback + retry + epoch 原子性) |
| P1-3 | +6 | AuditEventRecorderClearRaceTest(advanceEpoch / dropByUserZone / per-zone gate / submit-时-stale 路径) |
| P1-4 | +12 | ConversationCompressorTransactionTest + LlmSummaryProviderTest |
| P1-5 | +4 | AgentRuntimeRepositoryIntentClassifierTest(Fallback 路径 / 高低置信度 / empty) |
| P2-1 | +2 | AgentEngineAuditEventUserIdTest |
| P2-2 | +8 | AuditEventRecorderPayloadJsonTest(含特殊字符 round-trip) |
| P2-3 | +6 | RetryPolicyCancellationTest(cancel 立即抛 / deadline 截断 / sleep 期间 cancel 唤醒) |
| 附加 | +4 | SteerMailboxStaleHandlerZoneTest |

### 22.4 兼容契约

| 修复 | public 签名变更 | 旧行为退化? |
|------|-----------------|------------|
| P1-1 | DynamicThreadPool 加 4 参重载(默认重载保留);TaskState / StopReason 加 REJECTED 枚举值;TaskScheduler / ModelCallExecutor / ToolExecutor 内部 catch RejectedExecutionException | 否(默认 DynamicThreadPool 仍 CallerRunsPolicy) |
| P1-2 | RoomMemoryMigrator.migrate() 行为变更(原子) | RoomMemoryMigratorTest 加 spCleared 断言 |
| P1-3 | AuditEventRecorder 加 advanceEpoch / dropByUserZone;AgentRuntimeRepository 加 setAuditEventRecorder | 否(默认 NOOP,旧测试无变化) |
| P1-4 | LlmSummaryProvider 新类;ConversationCompressor.compress 算法重构(public API 不变) | 否(Provider=null 仍走 heuristic) |
| P1-5 | AppContainer 装配 FallbackIntentClassifier;AgentRuntimeRepository 加 setIntentClassifier + intentClassifier 改 volatile | 否(默认 Keyword,LLM 失败自动 Fallback) |
| P2-1 | AgentEngine 4 处用 ActorUsers.userIdOf | 否(demo 中 sessionId == userId) |
| P2-2 | AuditEventRecorder 私有 buildXxxPayload 重写 | 否(payloadJson 仍是 JSON 字符串) |
| P2-3 | RetryPolicy 加重载(旧签名保留转发) | 否(RetryPolicyTest 旧 case 零回归) |
| 附加 | AppContainer.SteerMailboxStaleHandler 改 package-private + sessionContext 映射 | 否(内部类) |

### 22.5 仍推迟到 V0.5.3(不在本次范围)

- **D1 流式 SSE**(独立演进)
- **D3 Stage 3b token 主路径真切换**(独立演进)
- **D4 abort hook 异步预路径**(SteerMailbox epoch gate 已落地,abort hook 本计划不引入)
- **D5 删除剩余 3 项 @Deprecated**(TaskPlan / AgentOutcome.getResults / verificationRequired)
- **D6 副驾 zone 推断规则**
- **D7 on-device embedding**
- **D8 AgentBudget 旧 char-based 字段彻底删除**
- **D9 PromptComposer 抽象**
- **D10 SessionContext 结构化改造**
- **D11 master_key + audit_hmac_key 别名迁移**
- **P1-5 增强**:ModelApiViewModel.saveAndApply 切换 Provider 时同步更新 IntentClassifier(本次仅装配时一次)
- **P1-1 androidTest**:SharedThreadPoolDeadlockInstrumentedTest(2 并发 Agent + 8 并发 model/tool 验证不死锁,需 emulator)

**结论**:V0.5.2 评审 P1/P2 全部修复落地,**JVM 测试 616**(+60)/ **androidTest 18**(暂未跑,需 emulator)/ `assembleDebug` 全绿,**0 V0.4.x / V0.5.0 / V0.5.1 测试回归**。可作为模拟器 / 架构验证版推进 V0.5.3 评审。

---

## 23. V0.5.2-rev 评审 P1/P2/P3 修复(2026-08)

V0.5.2 评审 P1/P2 落地后,评审紧接着对**生产代码**做了第二次审查(不是测试),发现 **4 P1 + 4 P2 + 2 P3**,其中部分是上一轮修复引入的次生问题(P1-2 epoch gate 语义混淆 / P2-1 RetryPolicy 新重载未在生产路径用 / P2-2 IntentClassifier 切换不同步 / P3-1 buildPreToolPayload 双重引号)。本节是修复落地记录。

### 23.1 修复总览

| 编号 | 主题 | 根因 | 修复 |
|------|------|------|------|
| P1-1 | ioPool CallerRunsPolicy 绕过超时/取消 | `AppContainer.ioPool` 默认 3 参 `DynamicThreadPool(2,8,32)`(CallerRunsPolicy),队列满时 Runnable 在 TaskScheduler 线程同步执行模型 HTTP → 绕过 future.get(timeout) + abort hook | 改用 4 参 `DynamicThreadPool(2, 8, 32, new ThreadPoolExecutor.AbortPolicy())`,队列满显式抛 RejectedExecutionException;schedulerPool 仍 CallerRunsPolicy(顶层任务不可丢) |
| P1-2 | stale gate epoch 与时间戳混淆(评审 P1-3 次生) | `AuditEventRecorder.isStale` 比较 `entity.happenedAtMs < staleEpoch`,前者是 ms 时间戳(17xxx...),后者是 MemoryStore epoch 版本号(1/2/3)→ 永远 false | `AuditEventEntity` 加 `requestEpoch` 字段(default 0);MatrixDatabase v2→v3 + MIGRATION_2_3;5 个 recordXxx 加 epoch 末参重载(旧签名转发 epoch=0,不参与 gate);AgentEngine 5 处透传 `request.getEpoch()` |
| P1-3 | LLM 摘要丢失不可压缩的工具执行事实 | `splitByTransactions` 后整个旧 transaction(含 ToolCall / Observation / POLICY / EXECUTION_UNKNOWN)交给 LlmSummaryProvider,摘要后从 conversation 移除 | `splitStructuredFromNatural` 把 toSummarize 切成 structuredKeep + toSummarizeNatural;含 `role==TOOL` 或 `role==ASSISTANT && !toolCalls.isEmpty()` 的 transaction 整组保留,LLM 只摘要 naturalTxn |
| P1-4 | 摘要请求没受 deadline/cancel/真实 10s 超时约束 | `LlmSummaryProvider.summarize` 直接 `client.complete(config, system, user)`(旧 3 参),没传 CancellationToken / AgentRequest.remainingMillis();`summarizeTimeoutMs` 字段没真用 | `LlmClient` 加 5 参 `complete(config, system, user, token, deadlineAtMillis)` 重载;ModelApiClient 实现新重载内部走 RetryPolicy 新重载;LlmSummaryProvider 用 `request.getCancellationToken()` + `min(now+10s, request.getDeadlineAtMillis())`,剩余 < 2s 抛 SummaryUnavailableException |
| P2-1 | RetryPolicy 新重载没在生产 ModelApiClient 用(评审 P2-3 次生) | `RetryPolicy.invokeWithRetry(action, token, deadlineMillis)` 已加,但 `ModelApiClient` 4 处 invokeWithRetry 全调旧重载 | ModelApiClient 加私有重载 `invokeWithRetry(action, token, deadlineMillis)`;4 个生产入口(complete / callAnthropicWithTools / callOpenAiWithTools / callGeminiWithTools)加 5/6 参重载,旧重载转发 deadline=MAX;LlmModelGateway 从 AgentRequest 取 token+deadline 透传;LlmIntentClassifier 改用 LlmClient 接口 + 3s 短 deadline |
| P2-2 | 切换模型配置时 IntentClassifier 没同步(评审 P1-5 次生) | `ModelApiViewModel.saveAndApply` 只调 `setModelGateway`,没调 `setIntentClassifier`;`useDemoGateway` 也没回退 Keyword | `ModelGatewayRepository` 加 `buildIntentClassifier(config)` / `buildKeywordClassifier()`;ViewModel saveAndApply 调 setIntentClassifier(buildIntentClassifier),useDemoGateway 调 setIntentClassifier(buildKeywordClassifier) |
| P2-3 | 压缩触发点不是 80% | `COMPRESSION_TRIGGER_RATIO=0.8` 但 AgentEngine.appendMessageWithBudget 只在 100% 被动触发 | `AgentEngine` 主循环顶部加 `tryCompressConversation` 主动检查 80%;100% 被动路径保留(兜底 race) |
| P2-4 | 静默丢弃超 MAX_TURNS_TO_SUMMARIZE 的最老消息 | drop > 20 的最老消息直接丢,既不进 recent 也不进 summary | 删除 drop 逻辑,改为分批摘要(toSummarizeNatural 切成 ≤ 20 一批,前一批 summary 拼到下一批头部);超 MAX_SUMMARIZE_BATCHES=3 加 "[历史已裁剪 X 条]" 提示(不静默) |
| P2-5 | 线程池和 audit recorder 没统一 shutdown | AppContainer 构造器内 schedulerPool / ioPool 是局部变量,Repository.shutdown() 只关 scheduler;AuditEventRecorder 也没人关 | schedulerPool / ioPool / auditEventRecorder 改 final field;AppContainer 加 shutdown() 按序关 Repository → schedulerPool → ioPool → auditEventRecorder;MatrixAgentApplication.onTerminate 调 container.shutdown() |
| P3-1 | buildPreToolPayload fallback 非法 JSON(评审 P2-2 次生) | fallback 用 `JSONObject.quote(toolName)` 拼到已有 `"` 内会双重引号 | 改为构造空 JSONObject 再 `put("tool", toolName).put("args", "")` |
| P3-2 | README 与实现不一致 | README 标 LLM SummaryProvider 为 V0.5.3 推迟,但代码已实装 | README V0.5.2-rev 段落 + 本节同步 |

### 23.2 关键复用点

1. **`ThreadPoolExecutor.AbortPolicy`**(JDK)—— P1-1 ioPool 兜底拒绝,与 V0.5.2 Stage 11 测试构造器用法一致
2. **`AgentRequest.getEpoch()`**(V0.5.0 第八轮 P1.3)—— P1-2 透传 requestEpoch
3. **`MemoryStore.currentEpoch()`**(V0.5.0)—— P1-2 stale gate 与 MemoryStore epoch 单一权威对齐
4. **Room Migration 模式**(`MIGRATION_1_2`)—— P1-2 加 `MIGRATION_2_3` 同模式
5. **`CancellationToken.registerAbortHook / removeAbortHook / cancel`**(V0.4.1)—— P1-4 + P2-1 cancel 感知
6. **`RetryPolicy.invokeWithRetry(action, token, deadline)`**(上一轮 P2-3)—— P2-1 直接调用
7. **`AgentRequest.getCancellationToken / getDeadlineAtMillis / remainingMillis`**(V0.4.1/V0.5.0)—— P1-4 + P2-1 deadline 透传
8. **`AgentMessage.summary(text)` 工厂 + `PREFIX_SUMMARY`**(V0.5.2)—— P1-3 摘要消息复用
9. **`splitByTransactions` + `isTransactionBoundary`**(V0.5.2 上一轮)—— P1-3 扩展识别 structuredTxn
10. **`FallbackIntentClassifier` / `LlmIntentClassifier` / `KeywordIntentClassifier`**(V0.5.2 Stage 9)—— P2-2 直接复用
11. **`ModelGatewayRepository.createModelGateway / createDemoGateway` 模式**—— P2-2 加 buildIntentClassifier 同模式
12. **`DynamicThreadPool.shutdown()`**(已有)—— P2-5 统一关闭
13. **`AuditEventRecorder.shutdown()`**(已有,L476-489)—— P2-5 调用
14. **`AgentRuntimeRepository.setIntentClassifier`**(上一轮 P1-5)—— P2-2 ViewModel 调用
15. **`AuditEventEntity` Room schema 模式**(V0.5.2 Stage 4)—— P1-2 加 requestEpoch 字段同模式
16. **`LlmClient` 接口**(V0.5.0 Stage 3)—— P2-1 让 LlmIntentClassifier / LlmSummaryProvider 在 JVM 测试中可 mock

### 23.3 测试总数演进

V0.5.2 baseline 616 JVM → 修复后 **654 JVM**(+38)。assembleDebug 全绿,0 V0.4.x/V0.5.0/V0.5.1/V0.5.2 回归。

| 修复 | JVM 增量 | 主要测试 |
|------|----------|---------|
| P1-1 | +1 | DynamicThreadPoolPolicyContractTest |
| P1-2 | +3 | AuditEventRecorderClearRaceTest 修订(真实 epoch 1/2/3) + AuditEventRecorderEpochGateTest 新增 |
| P1-3 | +8 | ConversationCompressorStructuredFactRetentionTest |
| P1-4 | +3 | LlmSummaryProviderCancelDeadlineTest |
| P2-1 | +7 | ModelApiClientCancelDeadlineIntegrationTest + LlmIntentClassifierDeadlineTest |
| P2-2 | +3 | ModelGatewayRepositoryIntentClassifierTest |
| P2-3 | +2 | ConversationCompressorEightyPercentTriggerTest |
| P2-4 | +2 | ConversationCompressorBatchSummarizeTest |
| P2-5 | +1 | AppContainerShutdownTest |
| P3-1 | +4 | AuditEventRecorderFallbackJsonTest |
| 其它 | +4 | ConversationCompressorStructuredKeepCompressionTest + 现有测试修订 |

### 23.4 兼容契约

| 修复 | public 签名变更 | 旧行为退化? |
|------|-----------------|------------|
| P1-1 | AppContainer 内部 ioPool 构造参数变更(public API 不变) | 否 |
| P1-2 | AuditEventEntity 加 requestEpoch 字段;AuditEventRecorder 5 recordXxx 加 epoch 末参重载(旧签名保留);MatrixDatabase v2→v3 + MIGRATION_2_3 | 否(旧 recordXxx 默认 epoch=0 不参与 gate) |
| P1-3 | ConversationCompressor 内部重构(public compress API 不变) | 否(Provider=null 仍走 heuristic) |
| P1-4 | LlmClient.complete 加 5 参重载(旧 3 参保留);LlmSummaryProvider.summarize 内部调用新重载 | 否(LlmClient 旧 3 参仍可用) |
| P2-1 | ModelApiClient 4 个 callXxxWithTools 加 deadline 重载(旧重载保留);complete 加 5 参重载 | 否 |
| P2-2 | ModelGatewayRepository 加 buildIntentClassifier / buildKeywordClassifier | 否 |
| P2-3 | AgentEngine 加 private tryCompressConversation | 否(80% 主动 + 100% 被动双保险) |
| P2-4 | ConversationCompressor.compress 内部分批逻辑 | 否(单 batch 路径与原行为等价) |
| P2-5 | AppContainer 加 shutdown();schedulerPool / ioPool / auditEventRecorder 改 final field | 否 |
| P3-1 | AuditEventRecorder.buildPreToolPayload private fallback 改 | 否 |
| P3-2 | README + Code-Review 文档 | 否 |

### 23.5 仍推迟到 V0.5.3(不在本计划范围)

- D1 流式 SSE
- D3 Stage 3b token 主路径真切换
- D4 abort hook 异步预路径(SteerMailbox epoch gate 已落地,abort hook 不引入)
- D5 删除剩余 3 项 @Deprecated
- D6 副驾 zone 推断规则
- D7 on-device embedding
- D8 AgentBudget 旧 char-based 字段彻底删除
- D9 PromptComposer
- D10 SessionContext 结构化
- D11 master_key + audit_hmac_key 别名迁移
- D12 AAOS 多 session 运行时注入(本计划 P2-2 仍 hard-code demo-driver/demo-passenger)
- **D2 LLM SummaryProvider 注入**从推迟项移除——**已在 V0.5.2-rev 实装并加固**

**结论**:V0.5.2-rev 评审 4 P1 + 4 P2 + 2 P3 全部修复落地,**JVM 测试 654**(+38)/ **androidTest 18**(暂未跑,需 emulator)/ `assembleDebug` + `assembleDebugAndroidTest` 全绿,**0 V0.4.x / V0.5.0 / V0.5.1 / V0.5.2 测试回归**。可作为模拟器 / 架构验证版推进 V0.5.3 评审。

### 21.3 V0.5.2 已落地特性总结

V0.5.2 全范围 11 个 Stage 完成后,JVM 测试 **423 → 556**(+133),androidTest **17 → 18 case**(新增 `DynamicThreadPoolConcurrencyTest` 验证 8 并发 × 3 owner 无死锁),`assembleDebug` + `assembleDebugAndroidTest` 全绿,0 V0.4.x / V0.5.0 / V0.5.1 测试回归。已落地特性:

- **audit_event userId 迁移 + Room Migration v1→v2**——schema v2 加 userId 列 + 复合索引,MIGRATION_1_2(1 ALTER + 1 CREATE INDEX)最小化,失败回退 NoopAuditRepository
- **Tokenizer 接口 + JtokkitTokenizer(BPE O200K_BASE)**——AgentEngine 主路径双轨观察(Stage 3a),Stage 3b 切换留 V0.5.3
- **RoomMemoryStore 主路径**(替换 SharedPreferencesMemoryStore)+ **RoomMemoryMigrator**(启动期一次性迁移,SP→Room 幂等)+ epoch 持久化到 memory_record 特殊行(单一权威)
- **AuditEventRecorder + PRE_TOOL/POST_TOOL/POLICY/STEER 增量事件**——5 个出口点埋点,异步单线程 Executor fire-and-forget,Stage 7 升级 batch 队列(50 条 / 200ms / 上限 500)
- **Room WAL 显式声明**——`setJournalMode(WRITE_AHEAD_LOGGING)` + `enableWriteAheadLogging` 兜底
- **SteerMailbox epoch gate**——clearUserData 后旧 Steer 在 drain 时 drop + audit(STEER_DROPPED_STALE),abort hook 留 V0.5.3
- **PromptBuilder 接入主路径**——AgentEngine.buildSystemPrompt 委托 DefaultPromptBuilder,内联逻辑保留 fallback
- **EpisodicMemorySourceImpl + SemanticMemorySourceImpl**——替换 EmptyXxx,SessionHistoryDao / MemoryRecordDao 真实 Room-backed 召回 + LRU cache
- **ConversationCompressor**——上下文压缩骨架,token 超阈值触发,heuristic 降级(丢老 turns + 占位文本),LLM SummaryProvider 注入留 V0.5.3
- **LLM-based IntentClassifier**——IntentResult(readOnly + confidence + reason)+ LlmIntentClassifier(Provider 调小模型)+ FallbackIntentClassifier(LLM 失败 / 低置信度退 Keyword),confidence 阈值 0.7
- **Gemini 原生 Tool Calling**——`callGeminiWithTools` + Gemini `functionDeclarations` 协议 + `functionCall`/`functionResponse` 解析(Gemini 无 id,Runtime 合成 `gemini-<index>`)+ `LlmModelGateway.useGeminiNative` 路由
- **ModelApiException 错误分类**——5 子类(RateLimit / Server / Client / Network / Timeout),`getMessage()` 不泄露 HTTP body / endpoint
- **RetryPolicy 受控重试**——429/5xx 指数退避重试 3 次(500ms → 1000ms → 2000ms + ±20% jitter),4xx / 网络 / 超时不重试
- **DynamicThreadPool 共享池**——core=2 / max=8 / queue=32 / CallerRunsPolicy,TaskScheduler / ModelCallExecutor / ToolExecutor 三 executor 共享,ownership 跟踪避免误关
- **删除零引用 @Deprecated**——DemoPlanner / PlannerExecutor / TraceEvent / Planner / SteerMailbox.clearForTesting / PolicyDecision.deny / CapabilityDefinition.Builder.parameter(7 项),TaskPlan / AgentOutcome.getResults / verificationRequired 留 V0.5.3

**结论**:V0.5.2 全范围 11 个 Stage 落地,**JVM 测试 556**(+133)/ **androidTest +1** / `assembleDebug` + `assembleDebugAndroidTest` 全绿,**0 V0.4.x / V0.5.0 / V0.5.1 测试回归**。V0.5.2 范围内 4 项实装时主动收缩(流式 SSE / LLM SummaryProvider 注入 / Stage 3b token 主路径 / abort hook)+ 3 项 @Deprecated 删除推迟 + 4 项注释提及的演进项,正式登记 V0.5.3 衔接(§21.2)。可作为模拟器 / 架构验证版推进 V0.5.3 评审。




