# MatrixAgent V0.4.2 Schema 治理 实现评审

> 评审日期:2026-08-02
> 评审方式:TDD(Stage 测试先写) + 单元测试 + APK 构建
> 评审对象:MatrixAgent V0.4.2 Schema 治理 实现(5 个 Stage)
> 评审结论:**5 个 Stage 全部落地,235 个测试全绿,APK 构建通过,V0.4.2 Schema 治理闭环成立。**

## 一、评审目标

V0.4.1 完成 Runtime Control 后,V0.4.2 聚焦"Schema 治理":把 V0.4.0 简化的 `ToolParameterDefinition`
升级为完整 JSON Schema 2020-12 子集,引入车辆前置状态校验,区分 verify 策略,并提供按 zone 投影
工具列表的能力。本评审检查 V0.4.2 plan 的 5 个 Stage 是否按设计落地、不引入回归、测试覆盖到位。

5 个 Stage 的设计目标:

1. **Stage A:CanonicalSchema 类层次**——不可变 JSON Schema 2020-12 子集 + $ref eager inline + 循环检测
2. **Stage B:SchemaValidator + PolicyEngine 重写**——递归 walker 取代 V0.4.0 strict schema 双方法,保留 quirks
3. **Stage C:SchemaJsonWriter + ModelApiClient 重写**——OpenAI strict 兼容降级 + Anthropic 完整 schema
4. **Stage D:requiredVehicleStates + VehicleState**——车辆物理事实前置检查,归 CAPABILITY 拒绝
5. **Stage E:VerifyMethod + readOnlyHint 派生 + Zone 过滤**——verify 策略区分 + 主驾/副驾 tool 集隔离

## 二、评审范围

V0.4.2 新增 / 修改的代码:

- 新增 `core/capability/schema/CanonicalSchema.java`(不可变 JSON Schema 2020-12 子集,单一类递归 + Builder)
- 新增 `core/capability/schema/SchemaType.java`(7 种 type 枚举)
- 新增 `core/capability/schema/SchemaException.java`(build 期 / 投影期异常)
- 新增 `core/capability/schema/SchemaErrorCode.java`(13 种内部诊断码,不破坏 RejectionType 二分)
- 新增 `core/capability/schema/SchemaError.java` / `ValidationResult.java`(不可变)
- 新增 `core/capability/schema/SchemaValidator.java`(递归 walker)
- 新增 `core/capability/schema/SchemaProjectionConfig.java`(`OPENAI_STRICT / ANTHROPIC_FULL / CANONICAL_DEBUG`)
- 新增 `core/capability/schema/SchemaProjectionException.java`(降级失败)
- 新增 `core/capability/schema/SchemaJsonWriter.java`(Provider 投影器)
- 新增 `core/identity/VehicleState.java`(不可变 value class + `Gear` 枚举)
- 新增 `core/identity/VehicleStatePredicate.java`(简单谓词枚举,不引 DSL)
- 新增 `core/capability/VerifyMethod.java`(5 种 verify 策略枚举,USER_CONFIRM/TIMEOUT 预留)
- 修改 `core/capability/CapabilityDefinition.java`(新增 `parameterSchema` / `requiredVehicleStates` / `verifyMethod` 主字段;`toolParameters` / `verificationRequired` 标 `@Deprecated` + bridge)
- 修改 `core/capability/CapabilityRegistry.java`(`createDemoRegistry()` 全量切到 `parameterSchema`;新增 `deriveReadOnlyHint` / `toToolDefinitions(VehicleZone)` / per-zone 投影)
- 修改 `core/capability/ToolDefinition.java`(新增 `parametersSchema` 字段)
- 修改 `core/identity/AgentRequest.java`(新增 `currentVehicleState` 字段,默认 `satisfyAllPredicates` mock state)
- 修改 `core/policy/PolicyEngine.java`(`checkStrictSchema → checkCanonicalSchema` 委托 SchemaValidator;删除 `checkTypeAndRangeAndEnum`;新增 `checkVehicleState` 在 schema 之前)
- 修改 `platform/ModelApiClient.java`(`toOpenAiTool / toAnthropicTool` 重写,走 `SchemaJsonWriter`;保留 legacy fallback)

测试增量:

- 新增 `CanonicalSchemaBuilderTest.java`(11 个测试)
- 新增 `SchemaValidatorTest.java`(21 个测试)
- 新增 `SchemaJsonWriterOpenAiStrictTest.java`(10 个测试)
- 新增 `SchemaJsonWriterAnthropicTest.java`(7 个测试)
- 新增 `VehicleStatePredicateTest.java`(7 个测试)
- 新增 `PolicyEngineVehicleStateTest.java`(5 个测试)
- 新增 `VerifyMethodMigrationTest.java`(7 个测试)
- 新增 `CapabilityRegistryStageETest.java`(9 个测试)

## 三、测试结果

执行命令:`./gradlew testDebugUnitTest --rerun-tasks`

- 测试总数:**235 个**(V0.4.1 157 → V0.4.2 235,新增 78 个)
- 失败 / 错误:0
- 跳过:0
- APK 构建:`./gradlew assembleDebug` 通过,产物 `app/build/outputs/apk/debug/app-debug.apk`

## 四、Stage A:CanonicalSchema 类层次

**结论:通过**——不可变类落地,build 期 $ref eager 解析 + DFS 循环检测,$ref 与 sibling 互斥。

### 设计要点

- **单一类递归设计**——`CanonicalSchema` 自身 hold `items` / `properties` / `allOf/oneOf/anyOf` / `$refTarget`,不引入 `ArraySchema / ObjectSchema` 子类型,与 V0.4.0 `ToolParameterDefinition` 风格最小迁移
- **完整 JSON Schema 2020-12 子集**:type/enum/const/minimum/maximum/exclusiveMinimum/exclusiveMaximum/minLength/maxLength/pattern/items/properties/required/additionalProperties/allOf/oneOf/anyOf/$ref/$defs/sensitive/sensitivePlaceholder
- **$ref eager inline 策略**——build 时立即 resolve,build 后 `CanonicalSchema` 内部存"已内联"的 target;`$refString` 被 discard,`$defs` 在 $ref 节点上 clear;避免并发可见性问题
- **$ref + sibling 互斥**——JSON Schema 2020-12 允许 `$ref + type + properties` 同级,V0.4.2 拒绝(匹配 OpenAI strict 限制,简化语义)
- **cyclic $ref 防御**——build 时 DFS 检测,eager + immutable 设计下结构上无法构造,`detectCycle` 是防御性逻辑

### 测试验证(11 个)

- object/array 嵌套 schema build
- $ref 从顶层 $defs eager 解析
- cyclic $ref 检测(自引用场景)
- $ref + type / sibling 关键字互斥
- enum + const 互斥
- ARRAY schema 必须有 items
- sensitive annotation 保留
- composition allOf/oneOf/anyOf 按序

## 五、Stage B:SchemaValidator + PolicyEngine 重写

**结论:通过**——风险最高 Stage,递归 walker 完整覆盖 13 种 SchemaErrorCode,V0.4.0 quirks 全部保留。

### 改动要点

- **SchemaValidator 递归 walker**——`validateArguments(CanonicalSchema, Map<String,Object>) -> ValidationResult`
- **校验顺序**:additionalProperties → required → const → type → enum → 数值 range/exclusive → 字符串 length/pattern → 数组 items → 对象 properties → composition(allOf/oneOf/anyOf)
- **V0.4.0 quirks 必须保留**:
  - enum 字符串大小写不敏感匹配(`equalsIgnoreCase`)
  - integer enum 用 `String.valueOf((int)v)` 比对(兼容 enum 是字符串、instance 是 Integer 的场景)
  - `String.trim().isEmpty()` 拒空
  - integer 严格整数校验(`iv != Math.rint(iv)` 拒)
- **SchemaErrorCode 内部诊断**——13 种 code,仅作诊断使用;`RejectionType` 二分契约不变,所有 schema 失败仍归 PARAMETER(可重试)
- **PolicyEngine.evaluate**:
  - `checkStrictSchema` 重命名 `checkCanonicalSchema`,委托 `SchemaValidator.validateArguments`
  - 删除 `checkTypeAndRangeAndEnum`(整个方法体被 validator 取代)
  - `buildReasonFromError` 把 SchemaError path/message 转模型可读 deny reason,保 V0.4.0 文案兼容
- **CapabilityDefinition 升级**:
  - 新增 `CanonicalSchema parameterSchema` 主字段 + `Builder.parameterSchema(CanonicalSchema)`
  - `Builder.parameter(ToolParameterDefinition)` 标 `@Deprecated`,内部 bridge 时由 `projectSchemaFromToolParameters` 投影出 schema
  - `getParameterSchema()` 主 getter,null 时由 toolParameters 投影;`getToolParameters()` 兼容 getter,空时由 schema 反向投影(ModelApiClient V0.4.0 路径仍能用)

### 测试验证(21 个 SchemaValidatorTest)

- 各 SchemaErrorCode 单独 case(MISSING_REQUIRED / TYPE_MISMATCH / OUT_OF_RANGE / EXCLUSIVE_RANGE_VIOLATION / ENUM_VIOLATION / CONST_MISMATCH / LENGTH_VIOLATION / PATTERN_MISMATCH / EMPTY_STRING / COMPOSITION_FAILED / NOT_NULL / ADDITIONAL_PROPERTY)
- 嵌套 array items 递归(`zones[1]` 嵌套 path)
- 嵌套 object properties 递归(`destination.zip` 嵌套 path)
- `allOf / oneOf / anyOf` 行为(全过 / 恰好 1 过 / 至少 1 过)
- **V0.4.0 quirks 保留**:`enumStringMatchIsCaseInsensitive` / `integerEnumUsesStringValueOfComparison` / `emptyStringReturnsEmptyString`
- `$ref` eager inline 后递归 walk target

### PolicyEngineTest 回归(10 个全过)

9 个 V0.4.0 schema case(`extraSchemaFieldIsRejectedBeforeProvider` / `missingRequiredParameterIsRejected` / `outOfRangeIntegerIsRejected` / `nonIntegerValueIsRejectedForIntegerParam` / `enumValueOutsideAllowedIsRejected` / `legalSchemaCallIsAllowed` / `extraFieldOnReadOnlyCapabilityIsRejected` / `extraFieldOnNavigationIsRejected` / `multiTargetIntentBlocksBeforeSchemaCheck`)+ 1 个 version-number-leak 防御 case,全部无回归。

## 六、Stage C:SchemaJsonWriter + ModelApiClient 重写

**结论:通过**——OpenAI strict 降级策略集中实现,Anthropic 完整 schema 保留,contract test 全部原样通过。

### 设计要点

- **SchemaProjectionConfig 枚举**:`OPENAI_STRICT` / `ANTHROPIC_FULL` / `CANONICAL_DEBUG`
- **OPENAI_STRICT 兼容降级**:
  - $ref——build 时已 eager inline,write 时永不出现
  - required 自动补全:未显式 required 的 property 自动加入 required 数组
  - additionalProperties 强制写 `false`(所有 OBJECT,包括 empty OBJECT)
  - anyOf 仅允许简单类型并集(STRING/INTEGER/NUMBER/BOOLEAN),复杂类型抛 `SchemaProjectionException`
  - oneOf——OpenAI strict 不支持,直接抛
  - const → single-element enum
  - pattern 保留(OpenAI strict 2024-08+ 支持)
  - sensitive annotation 剥离(Provider schema 不识别)
- **ANTHROPIC_FULL**:完整 JSON Schema 2020-12,无降级;allOf/oneOf/anyOf 原样输出;const 作为 `const` 字段保留(不转 enum);required 跟随 schema 显式声明(不自动补全);additionalProperties 跟随 schema 自身声明
- **ToolDefinition 升级**:新增 `parametersSchema` 字段;`getParameters()` 保留(V0.4.0 contract test 兼容)
- **ModelApiClient 重写**:`toOpenAiTool / toAnthropicTool` 优先走 `SchemaJsonWriter.write(view.getSchema(), config)`;若 `parametersSchema == null`,fallback 到 `legacyOpenAiParameters / legacyAnthropicInputSchema`(V0.4.0 路径)

### Contract test 兼容关键

climate `set_temperature` 两个 property(zone + temperature)在 schema 中:
- `required = [zone, temperature]`——OPENAI_STRICT 自动补全 = 同 2 个,V0.4.0 contract 断言 `required.length() == 2` 仍成立
- `additionalProperties = false`——OPENAI_STRICT 强制写 false
- `temperature.type = integer` + `minimum=16 / maximum=30`——SchemaJsonWriter 原样输出

所有 9 个 schema 相关 contract case 全过(无新增,只验证回归)。

### 测试验证(17 个)

- `SchemaJsonWriterOpenAiStrictTest`(10 个):required 自动补全 / additionalProperties 强制 false / const → enum / $ref inline / anyOf 简单类型 / anyOf 复杂类型抛错 / oneOf 抛错 / pattern + range 保留 / array items 投影 / sensitive 剥离
- `SchemaJsonWriterAnthropicTest`(7 个):allOf/oneOf/anyOf 保留 / const 作为 const 字段 / pattern + length 保留 / required 不自动补全 / additionalProperties 跟随 schema / enum array 保留 / 嵌套 object 递归

## 七、Stage D:requiredVehicleStates + VehicleState

**结论:通过**——车辆物理事实前置校验,不满足归 CAPABILITY(模型换参数解决不了)。

### 设计要点

- **VehicleState 不可变 value class**——`Gear{P,R,N,D}` + `speedKmh` + `engineRunning` + `charging` + `batteryPercent`,Builder 范式
- **VehicleStatePredicate 简单枚举**(不引 DSL):`PARKED_ONLY`(gear=P && speedKmh<2) / `STOPPED_OR_PARKED`(speedKmh<2) / `ENGINE_RUNNING` / `NOT_CHARGING`
- **AgentRequest.currentVehicleState** 默认 `satisfyAllPredicates()` mock state——满足所有 predicate,保 V0.4.0 测试不退绿
- **CapabilityDefinition.requiredVehicleStates** 空 set 默认(无约束),AND 语义判定
- **PolicyEngine.checkVehicleState** 插入位置:`checkExplicitIntentBlocked` 之后、`checkCanonicalSchema` 之前——vehicle state 是物理事实,与参数无关,先于 schema 校验
- **失败归 CAPABILITY**——`denyCapability("vehicle state 不满足:" + predicate + "(当前 ...)")`,不可上诉

### Demo registry 改造

`climate.set_temperature / seat.set_heating_level / navigation.start_route` 标记 `requiredVehicleStates(PARKED_ONLY)`——车控写在行驶中不安全,用户停车后重新下达。

### 测试验证(12 个)

- `VehicleStatePredicateTest`(7 个):每个 predicate 满足 / 不满足情况 + `satisfyAllPredicates` 默认值
- `PolicyEngineVehicleStateTest`(5 个):
  - `vehicleStateAllowsWhenParked`——PARKED + 合法参数通过
  - `vehicleStateBlocksWriteWhenMoving`——gear=D 行驶中 CAPABILITY 拒绝
  - `vehicleStateCheckHappensBeforeSchemaCheck`——同时 vehicle state 不满足 + 参数非法,CAPABILITY 优先于 PARAMETER
  - `noRequiredStatesMeansNoCheck`——读操作(knowledge.answer)无约束
  - `navigationRequiresParked`——navigation 同样 PARKED_ONLY

## 八、Stage E:VerifyMethod + readOnlyHint 派生 + Zone 过滤

**结论:通过**——verify 策略区分 + 主驾/副驾 tool 集隔离 + readOnlyHint 显式派生 helper。

### 设计要点

**VerifyMethod 枚举**(`core/capability/VerifyMethod.java`):
- `NONE` / `READBACK_FIELD` / `READBACK_GET` / `USER_CONFIRM`(预留,V0.6.0 接 ASR) / `TIMEOUT`(预留,V0.6.0 接 Provider 超时)

**CapabilityDefinition 迁移**:
- `Builder.verifyMethod(VerifyMethod)` 新增主入口
- `Builder.verificationRequired(boolean)` 标 `@Deprecated`,内部 bridge:`true` → `READBACK_FIELD`(V0.4.0 mock 默认),`false` → `NONE`
- `isVerificationRequired()` 改为 `verifyMethod != NONE`(V0.4.0 `AgentEngine.java:416` 行为不变)
- `getVerifyMethod()` 新增

**CapabilityRegistry.deriveReadOnlyHint**:
- 输入 `Collection<String> capabilityNames`,输出 boolean
- 规则:集合内所有 capability `writeOperation == false` → true(纯读,允许被主驾抢占);否则 false(含写,不抢占)
- 调用方(Repository / RuntimeApi)在 `AgentRequest.Builder` 显式 `.readOnlyHint(registry.deriveReadOnlyHint(...))` 注入
- **不修改 `AgentRequest` 构造、不修改 `TaskScheduler.submit` 签名**——保 V0.4.1 TaskSchedulerTest 现有断言不断

**CapabilityRegistry.toToolDefinitions(VehicleZone zone)**:
- 过滤 `definition.getAllowedTargetZones().contains(zone)`
- R3_PROHIBITED 仍排除
- `zone == null` 等价于 V0.4.0 默认(不过滤,保 contract test 不退绿)

### 测试验证(16 个)

- `VerifyMethodMigrationTest`(7 个):NONE / READBACK_FIELD / READBACK_GET 三种 verifyMethod 入口 + verificationRequired(true/false) bridge + 默认 NONE + demo registry climate 验证
- `CapabilityRegistryStageETest`(9 个):
  - `toToolDefinitionsNoZoneReturnsAll` / `toToolDefinitionsWithNullZoneReturnsAll`——8 个 capability 全暴露
  - `toToolDefinitionsDriverIncludesClimate` / `toToolDefinitionsPassengerIncludesClimate`——DRIVER/PASSENGER 都看到 climate
  - `r3ProhibitedAlwaysFiltered`——R3 永不暴露
  - `deriveReadOnlyHintReturnsTrueForReadOnlyCapabilities`——纯读 → true
  - `deriveReadOnlyHintReturnsFalseForWriteCapability`——含写 → false
  - `deriveReadOnlyHintReturnsFalseForEmptyOrNull`——保守 false
  - `deriveReadOnlyHintIgnoresUnknownCapability`——未知 capability 不影响 hint

## 九、兼容契约表

| 兼容入口 | 主入口 | Bridge 行为 | 删除时机 |
|---|---|---|---|
| `Builder.parameter(ToolParameterDefinition)` | `Builder.parameterSchema(CanonicalSchema)` | ToolParameterDefinition → Schema 投影 | V0.5.0 |
| `Builder.verificationRequired(boolean)` | `Builder.verifyMethod(VerifyMethod)` | true→READBACK_FIELD / false→NONE | V0.5.0 |
| `CapabilityDefinition.getToolParameters()` | `getParameterSchema()` | schema → ToolParameterDefinition 反向投影(Stage C 后 ModelApiClient 改用 schema) | V0.5.0 |
| `CapabilityDefinition.isVerificationRequired()` | `getVerifyMethod()` | `verifyMethod != NONE` | V0.5.0 |
| `ToolParameterDefinition` 本身 | `CanonicalSchema` | 仅 V0.4.0 测试用 | V0.5.0 |
| `PolicyEngine.checkStrictSchema` | `checkCanonicalSchema` | (已删除,直接重命名) | V0.5.0 |
| `ModelApiClient.toOpenAiTool/toAnthropicTool` 手写 JSON | `SchemaJsonWriter.write(schema, config)` | legacy fallback 仍可用 | V0.5.0 |

## 十、潜在风险与遗留项

### 10.1 MockCapabilityProvider 路由未重构

V0.4.2 计划提到 `VerifyStrategy` 接口 + `ReadbackFieldStrategy / ReadbackGetStrategy / NoVerifyStrategy`
重构 `MockCapabilityProvider.execute` 内 if-else 硬编码——**实际未落地**。原因:

- Mock 现状 if-else 已能正确处理 V0.4.2 测试场景(无回归)
- `verifyMethod` 字段已加入 CapabilityDefinition 但 Provider 暂未消费
- V0.6.0 接真实 Provider 时一并重构(VerifyStrategy + ProviderContext + MemoryStore 注入)

V0.4.2 范围内 `verifyMethod` 仅作 capability 元数据存在,运行时 Provider 仍按 V0.4.0 二值 verify 行为
执行。Stage F 验证 `isVerificationRequired() == (verifyMethod != NONE)` 行为不变,AgentEngine 调用路径无回归。

### 10.2 VehicleState mock 数据单一来源

`AgentRequest.Builder` 默认 `currentVehicleState = VehicleState.satisfyAllPredicates()`——满足所有
predicate 的 mock。V0.6.0 接真实 Car API 后,VHAL 读取的真实 state 必须在 AgentRequest 提交时
显式注入(`.vehicleState(vehiclePropertyService.snapshot())`)。

### 10.3 SchemaJsonWriter 输出敏感字段处理

V0.4.2 SchemaJsonWriter 在 OPENAI_STRICT / ANTHROPIC_FULL 模式下都剥离 `sensitive` /
`sensitivePlaceholder` annotation(Provider 不识别)。但 schema 内 property 的 `description`
文本本身若包含敏感内容(如 "用户的家地址"),仍会暴露给模型——这是 capability 元数据自身的
安全责任,不由投影器兜底。

### 10.4 anyOf 复杂类型 OpenAI strict 支持

V0.4.2 OPENAI_STRICT 模式遇到复杂类型 anyOf 直接抛 `SchemaProjectionException`。
V0.5.0 应实现 text fallback(anyOf 转 string + description 说明合法形态),避免 capability 配置
时直接报错中断。

### 10.5 Zone 过滤默认不启用

`LlmModelGateway` / `AgentEngine` 仍走 V0.4.0 `toToolDefinitions()` 默认路径——
不调用 `toToolDefinitions(VehicleZone)`。V0.4.2 提供 per-zone 投影能力但默认不启用,
保 V0.4.1 测试不退绿。V0.5.0 接入到 LlmModelGateway 时显式启用,需要新增 end-to-end 测试
验证主驾/副驾 tool 集隔离。

### 10.6 ToolSchemaView 字段级 mask 未实现

V0.4.2 仅做 capability 级 zone 过滤(整个 tool 在/不在 list)。字段级 mask
(主驾看全字段、副驾 mask destination 等)留 V0.5.0+,需要 schema-rewrite 能力
(CanonicalSchema 子树替换),超出 V0.4.2 范围。

## 十一、与 V0.5.0 的衔接

V0.4.2 完成后,下一版本聚焦:

- **Token 预算 + tokenizer**——V0.4.0 char-based 估算切真实 tokenizer
- **四层 Memory**(Working / Episodic / Semantic / Preference)
- **Room/WAL 持久化 + 加密**
- **Gemini 原生 Tool Calling**——V0.4.2 SchemaJsonWriter 已具备 ANTHROPIC_FULL / OPENAI_STRICT 投影能力,新增 `GEMINI_FULL` 枚举值即可
- **Prompt Builder 模块化**
- **删除 V0.4.0 兼容入口**——`ToolParameterDefinition` / `verificationRequired(boolean)` / `Builder.parameter()` / legacy ModelApiClient 手写 JSON 全部清理
- **VerifyStrategy 接口落地**——`MockCapabilityProvider` 重构,Provider 接真实 verify 实现

V0.4.2 的 `CanonicalSchema` / `SchemaValidator` / `SchemaJsonWriter` / `VehicleState` /
`VerifyMethod` / `CapabilityRegistry.toToolDefinitions(VehicleZone)` / `deriveReadOnlyHint`
接口稳定,V0.5.0 不会破坏。
