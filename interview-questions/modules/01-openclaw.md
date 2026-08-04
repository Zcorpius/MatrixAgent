# OpenClaw 框架

> 来源：[面试鸭 · 最全 AI 大模型面试题库（含详细答案）](https://www.mianshiya.com/bank/1906189461556076546)
> 本模块共 26 题。完整索引见 [bank-llm-by-topic.md](../bank-llm-by-topic.md)。

---



> 共 26 题

### [最近 OpenClaw 这么火，你知道它的原理吗？](https://www.mianshiya.com/bank/1906189461556076546/question/2031643525468151809)

> **答案**：
>
> OpenClaw 本质上是一个**开源的多渠道 AI 网关（Multi-channel AI Gateway）**——它自己不做推理，而是把 LLM 的决策能力翻译成对聊天渠道（Telegram、Discord、飞书、钉钉等）、操作系统、外部 API 的实际控制权。可以把它理解成「中枢网关 + Agent Runtime」的组合。
>
> 理解它的原理，抓住两个核心：
>
> 1. **架构层面：Channel → Gateway → Agent → Tool 四层抽象**。Channel 负责把不同 IM 平台的消息协议归一成统一的 `Message` 对象；Gateway 做路由、鉴权、限流、幂等；Agent 是 LLM + 上下文 + 工具的组合体，处理实际决策；Tool 是 Agent 调用外界的接口。
>
> 2. **运行时层面：Agentic Loop + 工具生态**。Agent 收到消息后进入「LLM 推理 → 工具调用 → 结果回写上下文 → 再推理」的循环，直到产出最终回复；这套循环由 Agent Runner 驱动，并配合 Context Engine（管理上下文窗口）、Hook 系统（中间件）、Subagent（子 Agent 协作）等机制保证长任务、长对话下的稳定性。
>
> 跟普通聊天机器人最大的区别：OpenClaw 能真正"动手干活"（调用工具产生副作用），不只是回复文字；而且一个 Agent 实例可以同时挂在多个渠道上，对用户来说就像在熟悉的 IM 里直接和 AI 助手对话。

### [（OpenClaw前置知识）什么是 AI Agent？它和直接调用大模型 API 做一次问答有什么本质区别？](https://www.mianshiya.com/bank/1906189461556076546/question/2031664941567598594)

> **答案**：
>
> **AI Agent = LLM + 上下文 + 工具**。它不是单次问答，而是能自主规划执行步骤、调用工具、根据反馈调整策略的智能系统。
>
> 本质区别在三处：
>
> | 维度 | 直接调用 LLM API | AI Agent |
> |------|-----------------|----------|
> | **决策权** | 用户写完 prompt 一次性发给模型 | 模型在循环里自己决定下一步做什么 |
> | **状态** | 无状态，每次请求互相独立 | 有上下文/记忆，跨步骤累积信息 |
> | **能力边界** | 只能输出文本 | 通过工具调用能改外部世界（执行代码、发消息、调 API） |
>
> 举例：问"今天北京天气"——
> - 直接调 LLM：模型只能基于训练数据回答（可能过时或瞎编）
> - Agent：调用 `get_weather("北京")` 工具 → 拿到 `28°C 晴` → 回复用户
>
> Agent 的关键不在 LLM 本身，而在围绕 LLM 搭的「Harness」——上下文管理、工具接口、循环控制、错误处理这些工程外壳。模型越强，Harness 越关键。

### [（OpenClaw前置知识）请解释 Tool Calling（工具调用）的完整链路：工具是怎么定义的、LLM 怎么调用它、结果怎么回传](https://www.mianshiya.com/bank/1906189461556076546/question/2031934134963691521)

> **答案**：
>
> 工具调用（Tool Calling / Function Calling）的完整链路分四步：
>
> **1. 工具定义**：开发者用 JSON Schema 描述工具的名称、用途、参数。例如：
> ```json
> {
>   "name": "search_code",
>   "description": "在代码库中按关键字搜索",
>   "parameters": {
>     "type": "object",
>     "properties": { "query": {"type": "string"}, "max_results": {"type": "integer"} },
>     "required": ["query"]
>   }
> }
> ```
>
> **2. LLM 决策调用**：工具列表作为 `tools` 字段一起送进 LLM。LLM 根据用户意图自主判断：要不要调？调哪个？传什么参数？输出结构化的 `tool_calls`。
>
> **3. 框架执行工具**：Agent 框架（如 OpenClaw 的 Agent Runner）拦截 `tool_calls`，路由到对应的工具实现，执行真实操作（HTTP 调用、本地命令、SQL 查询等）。
>
> **4. 结果回传与循环**：执行结果作为 `tool` 角色消息追加到上下文，LLM 看到结果后决定下一步——继续调别的工具、给最终回复，或向用户提问。
>
> 这个「决策→执行→回写→再决策」的循环就是 **ReAct** 范式的工程实现。开发者只负责两件事：定义工具 Schema、实现工具函数；中间的「调不调、调哪个、传什么参数」完全由 LLM 自主决策。

### [（OpenClaw前置知识）System Prompt 在 Agent 系统中承载了哪些职责？如果 System Prompt 越来越长，你会怎么处理？](https://www.mianshiya.com/bank/1906189461556076546/question/2031943636563812353)

> **答案**：
>
> **System Prompt 在 Agent 系统中承担四类职责**：
>
> 1. **角色与人设**：定义 Agent 是谁、用什么语气说话、面向哪类用户
> 2. **行为策略**：什么场景用什么工具、优先调哪个、出错怎么办
> 3. **工具使用指南**：每个工具的适用场景、参数填写规范、典型用法示例（few-shot）
> 4. **安全约束**：禁止做的事、需要用户确认的高风险操作、隐私边界
>
> **System Prompt 越来越长的常见原因**：工具越加越多、规则越加越细、各种 few-shot 示例堆积。这会带来三个问题：挤占上下文窗口、模型注意力被稀释（关键规则被忽略）、维护成本上升。
>
> **处理方法**：
>
> 1. **分层加载**：核心身份和铁律放在 System Prompt；具体工具的细节指南放到工具的 `description` 字段（按需进入上下文）；场景化的策略放到可检索的 Skills 里。
> 2. **Skills 化**：把"什么时候该做什么"的领域知识写成独立的 Markdown 文件，按需通过检索或工具调用加载，而不是全堆在 System Prompt 里。
> 3. **抽象与复用**：同类工具合并（用一个代码解释器代替十个专用工具），同类规则合并。
> 4. **定期审计**：监控每条规则是否真的被触发、是否产生效果，删掉无效规则。
>
> 核心思路：**System Prompt 只放永远生效的"宪法"，可变的"细则"通过 Skills 和工具描述按需注入**。

### [（OpenClaw前置知识）什么是 Agent 的 Context Window？为什么它是 Agent 工程中最核心的约束之一？](https://www.mianshiya.com/bank/1906189461556076546/question/2031966109057441794)

> **答案**：
>
> **Context Window（上下文窗口）是 Agent 在每一步决策时能看到的所有信息的容量上限**，单位是 token。它包括：System Prompt + 用户消息历史 + 工具调用结果 + 当前用户输入。
>
> **为什么是核心约束**：
>
> 1. **决定能力上限**：Agent 只能用上下文里的信息做决策。窗口装不下，就等于看不见——再强的 LLM 也答不出来。
> 2. **长任务必然爆窗**：Coding Agent 跑半小时可能读几十个文件、跑十几次测试，工具结果累积轻松超过 100K token。
> 3. **成本与延迟**：每次推理都要重发整个上下文。窗口越大，token 成本和首 token 延迟越高。
> 4. **「Lost in the Middle」**：即便窗口够大，LLM 对中间内容的关注度也会下降——上下文工程不只是塞东西，还要排序。
>
> **与其他约束的对比**：
> - 模型能力可以通过提示工程、微调弥补
> - 工具不足可以通过新增 MCP 服务弥补
> - 但**上下文窗口是硬天花板**，超出就必须裁剪、压缩或外置存储——这是 Agent 工程区别于普通 LLM 应用的关键难点
>
> 因此现代 Agent 系统都把 Context 管理作为一等公民：滑动窗口、摘要压缩（Compaction）、记忆外置（向量库 + 长期记忆）、Skills 按需加载，本质都是围绕这个约束做工程。

### [（OpenClaw前置知识）解释「短期记忆」和「长期记忆」在 Agent 系统中的区别，分别适合怎么存储和检索？](https://www.mianshiya.com/bank/1906189461556076546/question/2031968091352621057)

> **答案**：
>
> 两者对应人脑的工作记忆和长期记忆，存储与检索方式截然不同。
>
> **短期记忆（Working Memory）**：
> - **内容**：当前任务的对话历史、最近几轮工具调用结果
> - **载体**：直接放在 LLM 上下文窗口里
> - **生命周期**：会话级，会话结束即消失（或被压缩成长期记忆）
> - **检索**：无需检索——LLM 直接"看到"
> - **容量约束**：受 Context Window 限制，必须做裁剪或压缩
>
> **长期记忆（Long-term Memory）**：
> - **内容**：用户偏好、历史会话摘要、领域知识、技能库
> - **载体**：外部存储——向量数据库（语义检索）、关系数据库（结构化属性）、知识图谱（关系）
> - **生命周期**：跨会话持久化
> - **检索**：通过 RAG（向量相似度 + Rerank）、SQL 查询或图查询按需取出，作为上下文注入
> - **容量约束**：理论上无限，但检索质量决定可用性
>
> **典型存储选型**：
>
> | 记忆类型 | 推荐存储 | 检索方式 |
> |---------|---------|---------|
> | 对话摘要 | 向量库 | 语义检索 Top-K |
> | 用户偏好（名字、时区、语言） | 关系库（KV） | 精确查找 |
> | "用户上周说过要做 X" | 时序 + 向量库 | 时间过滤 + 语义 |
> | 技能/SOP 文档 | 向量库 + 关键词索引 | 混合检索 |
>
> **工程要点**：写入要做"重要性筛选"（不是每句话都值得记），检索要做"查询重写"（用户原话往往不是最佳检索词），召回要做 Rerank（向量相似 ≠ 真正相关）。

### [OpenClaw 是什么？它要解决什么问题？它的核心能力有哪些？](https://www.mianshiya.com/bank/1906189461556076546/question/2031972227099942914)

> **答案**：
>
> **OpenClaw 是一个开源的多渠道 AI 网关（Multi-channel AI Gateway）和 Agent Runtime**，自托管，让用户能在自己熟悉的 IM（Telegram、Discord、飞书、钉钉等）里直接和 AI Agent 对话，由 Agent 真正调用工具完成任务——而不只是聊天。
>
> **要解决的问题**：
>
> 1. **渠道碎片化**：每接入一个新 IM 都要重写消息适配、登录、回调——重复造轮子
> 2. **Agent 工程基建重复**：上下文管理、工具调用、错误恢复、长对话压缩，每个团队都在重新搭
> 3. **多 Agent 协作缺失**：单一 Agent 处理复杂任务力不从心，需要 Subagent 与 Gateway 编排
> 4. **封闭生态锁定**：商业 Agent 平台能力黑盒、数据不透明
>
> **核心能力**：
>
> 1. **多渠道接入**：通过 Channel Plugin 把不同 IM 协议归一成统一消息格式
> 2. **Agent Runtime**：完整的 Agentic Loop（LLM 推理 → 工具调用 → 结果回写 → 再推理）
> 3. **Context Engine**：可插拔的上下文管理，支持滑动窗口、摘要压缩（Compaction）、外置记忆
> 4. **工具生态**：通过 MCP 协议接入工具，支持权限管道、Schema 适配
> 5. **多 Agent 编排**：Subagent spawn、Gateway 路由、跨 Agent 通信
> 6. **Hook 中间件**：在消息/工具调用的关键节点注入业务逻辑（审计、风控、转换）
> 7. **插件系统**：第三方可注册新渠道、工具、Hook，扩展生态
>
> 一句话概括：OpenClaw 之于 AI Agent，类似 API Gateway 之于微服务——它不替代业务逻辑（LLM 决策），但把所有工程基建标准化了。

### [OpenClaw 的核心组件有哪些？请描述它们之间的关系](https://www.mianshiya.com/bank/1906189461556076546/question/2031974625235197953)

> **答案**：
>
> OpenClaw 的核心组件按数据流分成四层，自外向内是：
>
> ```
> [Channel] → [Gateway] → [Agent Runner + Context Engine + Tool Hub] → [Hook/MCP/Plugin]
> ```
>
> **1. Channel（渠道插件）**：把外部 IM（Telegram、Discord、飞书）的消息协议、媒体格式、回调机制归一成统一的 `IncomingMessage` / `OutgoingMessage` 抽象。
>
> **2. Gateway（网关）**：系统统一入口，承担：
> - **路由**：把消息路由到对应的 Agent（按渠道、用户群组、优先级匹配）
> - **鉴权**：用户身份验证、Agent 访问控制
> - **限流与配额**：防止单用户打爆
> - **幂等性**：基于消息 ID 去重，避免网络重试导致副作用工具被多次执行
>
> **3. Agent Runner（Agent 运行时）**：驱动 Agentic Loop，管理一次 Agent 运行的生命周期——从消息进入、LLM 推理、工具调用、上下文更新，到产出回复、清理资源。
>
> **4. Context Engine（上下文引擎）**：可插拔的上下文管理层。负责：
> - 维护对话历史与工具结果
> - 在窗口将满时触发裁剪或 Compaction（摘要压缩）
> - 按需注入 Skills、用户记忆、外部知识（RAG）
>
> **5. Tool Hub（工具中心）**：通过 MCP 协议管理工具的注册、发现、调用；包含 Schema 适配层（兼容不同 LLM Provider 的工具描述差异）和权限管道（多层策略决定工具能否被某 Agent 在某场景调用）。
>
> **6. Hook 系统**：在消息接收前后、工具调用前后、LLM 推理前后等关键节点暴露扩展点，让插件能注入业务逻辑而不修改核心代码。
>
> **7. Plugin 系统**：第三方注册 Channel / Tool / Hook 的统一 API。
>
> **组件关系**：Channel 把消息给 Gateway，Gateway 路由到 Agent，Agent Runner 驱动 LLM 循环决策，决策中调用 Tool Hub 里的工具，整个链路各节点都可通过 Hook 拦截处理。Context Engine 贯穿 Agent 生命周期。

### [在 OpenClaw 中，一条用户消息从进入系统到收到回复，完整链路是怎样的？](https://www.mianshiya.com/bank/1906189461556076546/question/2031978073372516354)

> **答案**：
>
> 以"用户在 Telegram 里发了一句'帮我总结一下今天的邮件'"为例，完整链路：
>
> **1. 消息接入（Channel 层）**：
> - Telegram Webhook 推送到 OpenClaw 的 `/webhook/telegram` 端点
> - Telegram Channel Plugin 把原始 update 解析成统一的 `IncomingMessage{from, chat_id, text, timestamp}`
>
> **2. 网关处理（Gateway 层）**：
> - **幂等检查**：用 `message_id` 查缓存，若已处理过直接返回（防重试）
> - **鉴权**：校验用户身份与渠道绑定关系
> - **路由**：根据 (channel, user_group, agent_id 规则) 决定路由到哪个 Agent
> - **限流**：检查该用户配额
>
> **3. Hook 前置（Pre-hooks）**：
> - 敏感词过滤、消息转换（如翻译统一语种）、审计日志记录
>
> **4. Agent Runner 接管**：
> - **加载上下文**：Context Engine 取出该会话的历史 + 用户长期记忆
> - **进入 Agentic Loop**：
>   - LLM 推理 → 决定调用 `list_emails(date=today)`
>   - Tool Hub 执行 → 返回邮件列表
>   - 结果回写上下文
>   - LLM 推理 → 决定调用 `read_email(id=...)` 多次或 `summarize(...)`
>   - ...
>   - LLM 推理 → 输出最终回复
>
> **5. Context 更新**：本轮所有消息、工具调用、最终回复都写入会话存储；若窗口超阈值，触发 Compaction。
>
> **6. Hook 后置（Post-hooks）**：消息转换（Markdown → Telegram 支持的格式）、敏感信息脱敏。
>
> **7. 回复投递（Channel 层）**：Channel Plugin 调用 Telegram Bot API 把回复发回用户。
>
> **8. 异步副作用**：如果本轮产生了需要长期保留的状态（如"用户喜欢简短摘要风格"），异步写入长期记忆。

### [OpenClaw 的 Agent Runner 是如何工作的？一次 Agent 运行经历了哪些阶段？](https://www.mianshiya.com/bank/1906189461556076546/question/2031980452067172353)

> **答案**：
>
> Agent Runner 是 OpenClaw 中驱动一次 Agent 运行的核心引擎，本质是 Agentic Loop 的状态机。
>
> **一次 Agent 运行经历的关键阶段**：
>
> **1. 初始化（Setup）**
> - 加载 Agent 配置（System Prompt、工具列表、Hooks）
> - 从 Context Engine 取回当前会话的上下文（历史 + 记忆 + Skills）
> - 申请运行资源（LLM Provider 连接、超时计时器）
>
> **2. 推理循环（Loop）**
> 循环体每轮：
>   a. **构建 Prompt**：System Prompt + 上下文 + 用户消息 + 工具定义
>   b. **调用 LLM**：拿到 assistant 输出
>   c. **判断输出类型**：
>      - 若是 `tool_calls`：执行工具，结果作为 `tool` 消息追加到上下文，回到 a
>      - 若是最终回复：进入收尾阶段
>      - 若触发 Subagent 调用：spawn 子 Agent，等待结果
>   d. **每轮检查**：超时、循环次数上限、Token 预算、错误重试
>
> **3. 工具执行（Tool Execution）**
> - Tool Hub 接收调用请求
> - 权限管道逐层校验
> - Schema 适配（按目标 LLM Provider 转换参数）
> - 执行实际工具函数
> - Hook 前置/后置拦截
> - 返回结构化结果（成功/失败 + 数据）
>
> **4. 收尾（Finalize）**
> - 写回最终回复到上下文
> - 检查 Context Window，必要时触发 Compaction
> - 持久化会话状态
> - 释放资源、记录指标（latency、token、tool calls）
>
> **关键设计点**：
> - **可观测**：每个阶段都有 span/trace，方便调试长链路
> - **可中断**：超时或外部 cancel 时能优雅退出，避免悬挂
> - **可重放**：通过 trace 能回放一次运行，定位异常步骤
> - **幂等**：同一消息重复触发不会导致副作用工具被执行两次

### [LLM 的 Context Window 有上限，长对话时如何保证 Agent 仍然能正常工作？OpenClaw 是怎么做的？](https://www.mianshiya.com/bank/1906189461556076546/question/2031982168447672321)

> **答案**：
>
> LLM 的 Context Window 是硬约束（即便 200K 也会被几小时密集对话填满），OpenClaw 通过组合策略保证长对话不崩。
>
> **1. 滑动窗口（Sliding Window）**
> 最朴素的策略：保留最近 N 轮对话，老的直接丢弃。优点简单，缺点是丢失早期上下文——任务跨多轮时容易"失忆"。
>
> **2. 摘要压缩（Compaction）**
> 当窗口逼近阈值时，触发 Compaction：
> - 用 LLM 对早期对话做摘要（保留关键决策、用户偏好、未完成的子任务）
> - 摘要替换原始消息，腾出空间
> - 关键的 tool_call 结果保留（删了就找不到事实了）
>
> 典型策略：保留最近 5-10 轮原始对话 + 之前的摘要 + 关键工具结果。
>
> **3. 外置长期记忆（Long-term Memory）**
> 更老的内容不该塞进上下文。流程：
> - 对话结束时把"用户偏好 / 任务结论 / 关键事实"抽取成结构化记忆条目
> - 写入向量库（语义检索）或关系库（精确查找）
> - 下次对话开始时，根据当前 query 检索相关记忆注入上下文
>
> **4. Skills 按需加载**
> 领域知识（SOP、产品手册、API 文档）不放在 System Prompt，而是写成独立 Markdown Skills，按当前任务检索后注入——只在需要时占上下文。
>
> **5. 工具结果精简**
> 工具返回 50KB 代码搜索结果时，OpenClaw 会做结果裁剪/摘要再写入上下文，避免单次工具调用撑爆窗口。
>
> **6. Context Engine 可插拔**
> 不同 Agent 类型（Coding Agent vs 客服 Agent）需要不同策略。Context Engine 抽象成接口，让策略可替换。
>
> **核心思想**：上下文窗口不够，本质是信息密度问题——用「摘要 + 外置 + 按需检索」组合，让 LLM 在每一步都只看到最相关的信息。

### [Agent 调用工具可能返回超大结果（比如代码搜索返回 50KB），这会带来什么问题？你会怎么处理？OpenClaw 是怎么做的？](https://www.mianshiya.com/bank/1906189461556076546/question/2031985434829021186)

> **答案**：
>
> 工具返回超大结果（如代码搜索 50KB、网页抓取整页 HTML、数据库查询千行）会带来三类问题：
>
> **1. 上下文窗口被瞬间打爆**：一次工具调用占满 100K 窗口，后续 LLM 推理无空间。
> **2. Lost in the Middle**：LLM 对超长上下文中间部分关注度下降，关键信息被淹没。
> **3. 成本飙升**：每次后续推理都要重发整个上下文，token 成本和延迟线性增长。
>
> **处理策略（按优先级）**：
>
> **a. 在工具内部裁剪**：工具设计时就限制返回大小。例如：
> - 代码搜索：只返回 Top-K 命中，每条带前几行代码 + 行号
> - 网页抓取：先 Markdown 化、去广告、再做摘要
> - SQL 查询：强制 LIMIT + 分页
>
> **b. 流式处理 + 增量摘要**：超大结果分块，逐块送入一个轻量 LLM 做摘要，最后只把摘要写回主 Agent 上下文。
>
> **c. 写外置、回引用**：完整结果存到本地文件或对象存储，上下文里只放路径 + 摘要。后续 LLM 需要细节时再调"读取文件指定行"工具按段读。
>
> **d. 结构化提取**：用 LLM 或规则从原始结果里提取关键字段（如错误日志提取 stack trace），丢掉冗余。
>
> **OpenClaw 的做法（基于工程推断）**：
> - 工具 Schema 里声明 `max_output_tokens`，框架强制截断
> - Hook 在 `tool_after_call` 节点检查返回大小，超阈值自动触发摘要
> - 长结果走"外置 + 引用"模式，配合 Context Engine 的 Compaction 一起工作
> - 对固定模式的大结果（如 git diff）提供专用精简器
>
> **设计原则**：**工具结果要像 API 响应——精简、结构化、可分页**，而不是把整个文件原样塞回来。

### [当对话历史实在太长、裁剪也不够用时，还有什么办法？什么是 Compaction？OpenClaw 的 Compaction 策略是怎样的？](https://www.mianshiya.com/bank/1906189461556076546/question/2031992088660303874)

> **答案**：
>
> 当对话历史太长、滑动窗口裁剪也不够用（裁掉的内容会导致 Agent 失忆）时，**Compaction（压缩）** 是核心解法——它不是简单丢弃，而是把历史"浓缩"成等价但更短的表示。
>
> **什么是 Compaction**：用 LLM 对一段历史对话做摘要，提取关键信息（用户意图、决策依据、已完成的子任务、关键工具结果、待办事项），用摘要替换原始消息，腾出上下文空间。
>
> **OpenClaw 的典型 Compaction 策略（基于通用最佳实践推断）**：
>
> **1. 触发时机**：
> - 软触发：上下文使用量超过 70% 时
> - 硬触发：超过 90% 时强制执行
> - 主动触发：用户切换话题或会话长时间空闲后
>
> **2. 压缩范围**：
> - 保留最近 N 轮（如最近 5-10 轮）原始对话——当前任务上下文最相关
> - 中间段做摘要——历史决策与中间结果
> - 关键 tool_call 结果**不压缩**（事实性数据，删了无法还原）
> - System Prompt、用户偏好永远保留
>
> **3. 摘要 Prompt 设计要点**：
> - 明确要保留的内容：用户目标、未完成任务、关键决策依据、用户偏好
> - 明确可丢弃的内容：寒暄、重复尝试、已废弃的中间方案
> - 结构化输出：用 markdown 分"已完成 / 进行中 / 待办 / 关键事实"几段
> - 自我验证：让 LLM 自检摘要是否覆盖了原始对话的关键信息
>
> **4. 多级压缩**：
> - 第一级：单轮摘要（每轮压成几句）
> - 第二级：多轮摘要（一段对话压成一段）
> - 第三级：会话摘要（整个会话压成短摘要）
> - 不同级别对应不同保留时长
>
> **5. 可逆性**：原始消息不真删，移到冷存储；如果摘要后发现漏了关键信息，能从冷存储恢复。
>
> **与其他策略配合**：Compaction 是兜底；上层还有外置长期记忆（RAG 检索）、Skills 按需加载、工具结果裁剪——多层组合才能让 Agent 在长任务里持续可用。

### [OpenClaw 把 Context 管理抽象成了可插拔的 Context Engine，为什么要做这层抽象？这个设计能支持哪些不同的策略？](https://www.mianshiya.com/bank/1906189461556076546/question/2032003420637913090)

> **答案**：
>
> 把 Context 管理抽象成可插拔的 Context Engine，本质是把"如何组装上下文"和"如何运行 Agent"解耦。
>
> **为什么做这层抽象**：
>
> 1. **Agent 类型差异巨大**：客服 Agent（短对话为主）、Coding Agent（长任务、大量工具结果）、Deep Research Agent（大量网页抓取）——三者上下文结构完全不同，但 Agent Runner 的核心循环是一样的。
> 2. **策略会演化**：今天用滑动窗口，明天想换成 Compaction + RAG，不该为此改 Agent Runner 代码。
> 3. **跨场景复用**：同一个 Agent 配上不同 Context Engine 可以服务不同场景（如本地小窗口 vs 云端大窗口）。
> 4. **可观测与可调**：把 Context 组装独立出来，才能监控每个策略的效果（token 占用、信息命中率），做 A/B 实验。
>
> **Context Engine 抽象层应该支持的策略**：
>
> | 策略 | 适用场景 | 核心 |
> |------|---------|------|
> | **滑动窗口** | 短对话客服 | 保留最近 N 轮 |
> | **摘要压缩（Compaction）** | 长对话助手 | 老的摘要 + 新的原文 |
> | **检索增强（RAG）** | 知识密集型 | 按当前 query 检索知识库注入 |
> | **记忆外置** | 跨会话用户偏好 | 长期记忆按需召回 |
> | **Skills 加载** | 领域 SOP | 按任务阶段加载不同 Skill 文件 |
> | **优先级排序** | 信息密集场景 | 让 LLM 看到最相关的 K 条 |
> | **分层缓存** | 高频固定信息 | System Prompt + 工具定义用 Prompt Cache |
>
> **接口设计**：Context Engine 对外暴露"输入当前 query 和会话状态，输出本次推理该用的消息列表"——Agent Runner 不关心具体怎么组装。
>
> **工程价值**：当模型 Context Window 从 100K 涨到 1M 时，业务侧只需换一个 Context Engine 实现（甚至完全 pass-through），不需要改 Agent。

### [如果一个 Agent 系统要同时接入 Telegram、飞书、钉钉等渠道，你会怎么设计抽象层？OpenClaw 的 Channel Plugin 接口是怎么设计的？](https://www.mianshiya.com/bank/1906189461556076546/question/2032428158229176321)

> **答案**：
>
> 接入多个 IM 渠道，抽象层设计要解决三个核心问题：**协议归一、能力差异、安全鉴权**。
>
> **设计要点**：
>
> **1. 统一消息模型（Canonical Message Model）**
> 定义 OpenClaw 内部的标准消息结构，所有渠道的消息进出都先转换成它：
> ```
> IncomingMessage {
>   channel_id, channel_type,         // 来源渠道标识
>   sender: {user_id, display_name, scopes},  // 发送者身份
>   conversation_id,                   // 会话隔离单元
>   content: MessageContent,           // 文本/图/音/视频等
>   timestamp, raw_payload,            // 原始 webhook 数据（调试用）
>   reply_to?: message_id,             // 引用消息
> }
>
> MessageContent = TextContent | ImageContent | AudioContent |
>                  FileContent | CardContent | MixedContent
> ```
>
> **2. Channel Plugin 接口（核心抽象）**
> ```
> interface ChannelPlugin {
>   // 启动时注册：声明 webhook 路由、健康检查
>   init(config): void
>
>   // 入：把渠道原始事件转成 IncomingMessage
>   parseIncoming(rawEvent): IncomingMessage
>
>   // 出：把 OutgoingMessage 转成渠道 API 调用
>   send(outgoing: OutgoingMessage): SendResult
>
>   // 能力声明：告知框架本渠道支持哪些特性
>   capabilities(): ChannelCapabilities
>     // { supports_image, supports_voice, max_text_length,
>     //   supports_reply, supports_card, supports_button,
>     //   rate_limit_per_second, ... }
>
>   // 媒体处理：上传/下载文件到渠道 CDN
>   uploadMedia?(blob): MediaRef
>   downloadMedia?(mediaRef): Blob
>
>   // 鉴权：验证 webhook 签名
>   verifyWebhook?(request): boolean
> }
> ```
>
> **3. 能力降级（Capability Degradation）**
> 不同渠道特性差异大（Telegram 支持Markdown，飞书支持卡片；Discord 2000 字限制）。Agent 生成的 OutgoingMessage 可能含本渠道不支持的元素，框架需要：
> - 文本超长自动分段
> - 卡片不支持时降级成纯文本
> - 图片不支持链接时自动上传到渠道图床
>
> **4. 双向转换 + 元数据保留**
> 解析入消息时保留原始 payload（调试用）；发送出消息时记录渠道返回的 message_id（用于后续编辑、撤回）。
>
> **5. 安全**
> - Webhook 签名验证（每个渠道不同的密钥/算法）
> - 用户身份映射（渠道 user_id ↔ OpenClaw 内部 user_id）
> - 渠道级配额与限流
>
> **6. 配置与生命周期**
> - 渠道配置（Bot Token、API Key）独立管理，不写死在代码里
> - 支持热加载（新增渠道不重启）
> - 健康检查 + 自动重连
>
> **OpenClaw 的具体 API 长什么样**：基于工程惯例，应该是提供 `BaseChannel` 抽象类 + 各渠道的具体实现（`TelegramChannel`、`FeishuChannel`、`DingTalkChannel`），第三方通过继承 `BaseChannel` 实现 5-6 个核心方法即可注册新渠道。

### [同一个系统里可能有多个 Agent，不同渠道用户群组的消息需要路由到不同的 Agent。你会怎么设计这个路由？OpenClaw 的路由匹配优先级是怎样的？](https://www.mianshiya.com/bank/1906189461556076546/question/2036708793047400450)

> **答案**：
>
> 多 Agent 路由本质是"给定一条消息和系统当前所有 Agent 配置，决定该消息该送给哪个 Agent 处理"。设计的关键是规则可声明、可优先级、可热更新。
>
> **路由维度（按精确度从高到低）**：
>
> 1. **显式指定**：消息明确带了 `target_agent_id`（用户主动 @某个 Agent，或上游 Agent 委托）
> 2. **渠道 + 群组绑定**：某渠道的某群组绑定到某 Agent（如"Discord #tech-support 频道 → TechAgent"）
> 3. **用户-Agent 关系**：某用户订阅了某 Agent（如"VIP 用户 → PremiumAgent"）
> 4. **意图识别**：用轻量分类器（小模型或规则）识别消息意图，路由到对应专精 Agent
> 5. **兜底 Agent**：所有规则都没匹配时的默认
>
> **优先级匹配（典型设计）**：
>
> ```
> 匹配顺序（自上而下，第一个命中即返回）：
>
> 1. 显式 agent_id       (用户 @ 或 header 显式指定)
> 2. 渠道 + conversation_id 白名单
> 3. 渠道 + user_group 绑定
> 4. 用户订阅关系
> 5. 意图分类命中
> 6. 默认 Agent
> ```
>
> **实现要点**：
>
> - **声明式规则**：路由规则用配置而非代码（YAML/JSON），方便热更新
> - **匹配缓存**：高频 (channel, conversation_id) → agent_id 缓存，避免每次查 DB
> - **可观测**：每次路由决策记录"匹配了哪条规则"，便于排查"为什么发错了 Agent"
> - **冲突检测**：上线新规则前 lint 检查，避免两条规则同时命中导致行为不确定
> - **灰度切换**：换 Agent 时支持按用户百分比灰度，便于 A/B
> - **失败降级**：目标 Agent 不可用时降级到兜底 Agent 而不是直接报错
>
> **常见坑**：
> - 规则越加越多导致互相覆盖——定期审计
> - 意图分类错误导致路由错乱——保留用户手动切换能力
> - 渠道绑定与用户订阅冲突——明确定义优先级，写进文档
>
> **OpenClaw 的具体优先级（基于工程推断）**：应该是上述 6 级优先级，配 YAML 配置 + 决策日志。具体细节需查 OpenClaw 官方路由文档。

### [同一个用户在 Telegram 私聊和 Discord 群组里和 Agent 对话，应该共享会话还是隔离？OpenClaw 是怎么设计会话隔离粒度的？](https://www.mianshiya.com/bank/1906189461556076546/question/2036711024706535426)

> **答案**：
>
> 这是 Agent 工程的经典问题：用户在 Telegram 私聊和 Discord 群里跟同一个 Agent 对话，会话该共享还是隔离？答案取决于"上下文是否相关"。
>
> **会话隔离的几种粒度**：
>
> | 粒度 | key | 适用场景 |
> |------|-----|---------|
> | **全局共享** | `(user_id)` | 个人助手，跨所有渠道都用同一份记忆 |
> | **渠道级** | `(user_id, channel_type)` | 工作渠道和生活渠道分开（飞书 vs Telegram） |
> | **会话级** | `(user_id, channel_type, conversation_id)` | 群聊与私聊分开，避免群消息污染私聊记忆 |
> | **任务级** | `(user_id, task_id)` | 不同任务互不干扰（写代码任务 vs 写作任务） |
> | **完全隔离** | `(user_id, channel_type, conversation_id, message_thread_id)` | 论坛式多 thread，每个 thread 独立 |
>
> **Telegram 私聊 vs Discord 群组的最佳实践**：
>
> 应该**隔离**。原因：
> 1. **上下文混淆**：群里讨论的是公共问题，私聊是私人需求，混在一起 Agent 会把群友的话题当作用户的私人意图
> 2. **隐私边界**：群里发言是公开的，私聊是隐私的；Agent 在私聊里回忆"你昨天在群里说过 X"会破坏用户对私密的预期
> 3. **角色差异**：用户在群里和私聊里跟 Agent 的交互风格不同（群里更像协作，私聊更像助理）
>
> **隔离粒度建议**：`(user_id, channel_type, conversation_id)`——渠道 + 会话维度隔离，同一用户在不同渠道、不同群/私聊里各自独立。
>
> **例外：需要跨渠道共享的是什么**：
> - **用户偏好层**（语言、时区、个性化设置）——全局共享
> - **长期记忆层**（"用户家里有只猫"）——全局共享
> - **任务级上下文**（"正在协作的代码项目"）——任务级共享
> - **当前对话历史**——会话级隔离
>
> **OpenClaw 的设计（基于工程推断）**：用分层记忆模型——会话级短期记忆按 `(user, channel, conversation)` 隔离；用户级长期记忆按 `user_id` 共享。Context Engine 在组装上下文时，从两个层级分别取数据。具体 API 应该有 `session_scope` 参数让开发者指定隔离粒度。

### [同一个工具（比如「执行命令」）在不同场景下应该有不同的权限。你会怎么设计工具的权限控制？OpenClaw 的工具策略管道是怎么分层的？](https://www.mianshiya.com/bank/1906189461556076546/question/2036713958370525186)

> **答案**：
>
> 同一个工具在不同场景下应该有不同权限（"执行命令"在沙盒里可以任意跑，在生产环境只能跑只读命令）。OpenClaw 的工具权限管道（Policy Pipeline）是分层、可组合的中间件链。
>
> **分层架构（自上而下）**：
>
> ```
> 1. Agent 级策略     —— 这个 Agent 配置上能不能调这个工具？
> 2. 用户级策略       —— 这个用户/群组能调吗？
> 3. 渠道级策略       —— 这个渠道（如公开 Discord 群）能调吗？
> 4. 会话/场景策略    —— 当前会话类型（沙盒 vs 生产）能调吗？
> 5. 参数级策略       —— 工具能调，但具体参数是否合法？
> 6. 速率/配额策略    —— 单位时间内还能调多少次？
> 7. 审批策略         —— 是否需要用户/管理员 confirm？
> ```
>
> 每层都是一个 Policy 节点，输入 `(tool_call, context)`，输出 `allow / deny / require_approval / modify_args`。
>
> **典型策略举例**：
>
> - **Agent 级**：`CodingAgent` 允许调 `run_command`，`ChatAgent` 不允许
> - **用户级**：管理员用户可以调 `delete_user`，普通用户不行
> - **渠道级**：公开群里禁止调 `read_private_notes`
> - **场景级**：会话带 `sandbox=true` 时允许任意命令，否则只允许白名单
> - **参数级**：`run_command(cmd)` 校验 cmd 不含 `rm -rf /` 等危险模式
> - **配额级**：单用户每小时最多调 100 次 `web_search`
> - **审批级**：金额超过 100 元的 `transfer_money` 必须用户点击确认按钮才执行
>
> **管道设计要点**：
>
> 1. **短路求值**：任一层 deny 立即终止，不继续后续检查
> 2. **可组合**：每个 Policy 是独立模块，按需挂载
> 3. **可观测**：每次决策记录"哪一层 deny/allow"，便于审计
> 4. **可热更新**：策略变更不重启服务
> 5. **缓存**：参数级以下的判断可按 `(tool, args_hash)` 缓存
> 6. **降级**：策略服务不可用时默认 deny（安全优先）
>
> **Hook 与 Policy 的关系**：Hook 是更宽泛的中间件（可以改输入输出），Policy 是专注于"能不能"决策的子集。OpenClaw 通常把 Policy 实现为一种特殊 Hook（在 `tool_before_call` 节点运行）。

### [不同的 LLM Provider 对 Tool Schema 的支持不完全一致，你会怎么处理这种差异？OpenClaw 是怎么做 Schema 适配的？](https://www.mianshiya.com/bank/1906189461556076546/question/2036716799235538945)

> **答案**：
>
> 不同 LLM Provider（OpenAI、Anthropic、Gemini、Qwen 等）对 Tool Schema 的支持差异巨大：
>
> | 差异维度 | OpenAI | Anthropic | Gemini | Qwen |
> |---------|--------|-----------|--------|------|
> | Schema 标准 | OpenAPI 子集 | JSON Schema | 自己的格式 | OpenAI 兼容 |
> | 嵌套对象 | 支持 | 支持（受限） | 部分支持 | 支持 |
> | enum | 支持 | 支持 | 支持 | 支持 |
> | oneOf / anyOf | 部分支持 | 不支持 | 不支持 | 部分支持 |
> | 默认值 | 部分支持 | 不支持 | 支持 | 部分支持 |
> | 工具调用字段 | `tool_calls` | `tool_use` block | `functionCall` | `tool_calls` |
> | 结果回传 | `tool` role | `tool_result` block | `functionResponse` | `tool` role |
>
> **OpenClaw 的适配策略**：
>
> **1. 内部统一 Schema**
> OpenClaw 内部用一套规范的 Schema 描述（基于 JSON Schema 标准 + 必要扩展）。工具开发者只写一次。
>
> **2. Provider Adapter 层**
> 为每个 LLM Provider 写一个 Adapter：
> - **Schema 转换**：内部 Schema → Provider 特定格式
> - **降级处理**：Provider 不支持的特性（如 `oneOf`）转成 `enum` 或多个工具
> - **调用格式转换**：内部 `tool_call` → Provider 特定 request 字段
> - **结果解析**：Provider 特定 response → 内部 `tool_result`
>
> **3. 差异抹平的几个典型处理**：
> - 不支持嵌套对象：拍平成 `parent.child` 键
> - 不支持 `oneOf`：拆成多个独立工具
> - 不支持默认值：必填字段全部要求 LLM 提供
> - 工具结果格式：统一抽象成 `{tool_call_id, content, is_error}`
>
> **4. 能力声明**
> 每个 Provider Adapter 声明自己支持的能力（`supports_streaming_tools`、`supports_parallel_calls`、`max_tools_per_call`），上层根据能力决定调用方式。
>
> **5. 测试矩阵**
> 对每个工具 × 每个 Provider 跑回归测试，确保同一工具在不同模型下行为一致。
>
> **6. 兜底**
> 对不支持工具调用的开源模型，回退到 ReAct 文本协议（让 LLM 输出 `<tool_call>{...}</tool_call>` 文本，框架正则解析）。
>
> **价值**：业务侧写一次工具定义，能跨 5+ 个主流 LLM Provider 用；切换模型只是改配置。

### [Agent 系统中 Hook中间件模式有什么用？能举几个典型场景吗？OpenClaw 的 Hook 系统是怎么设计的？](https://www.mianshiya.com/bank/1906189461556076546/question/2036720429414039554)

> **答案**：
>
> Hook 是 Agent 系统中的中间件模式——在关键节点暴露扩展点，让插件能注入业务逻辑而不修改核心代码。
>
> **典型 Hook 节点（按 Agent 运行生命周期）**：
>
> | 节点 | 时机 | 典型用途 |
> |------|------|---------|
> | `message_before_incoming` | 消息进入网关后 | 鉴权、敏感词过滤、消息转换（如翻译） |
> | `message_after_parse` | 解析成统一消息后 | 加密/解密、内容审计 |
> | `context_before_assemble` | 组装上下文前 | 注入额外知识、修改 System Prompt |
> | `llm_before_call` | 调 LLM 前 | 改 prompt、加 safety guard、记录指标 |
> | `llm_after_call` | LLM 返回后 | 内容审查、PII 脱敏、改写回复 |
> | `tool_before_call` | 工具调用前 | 权限校验、参数改写、限流、审批拦截 |
> | `tool_after_call` | 工具返回后 | 结果裁剪、摘要、审计日志 |
> | `subagent_before_spawn` | spawn 子 Agent 前 | 资源配额校验、参数脱敏 |
> | `message_before_outgoing` | 回复发出去前 | 渠道格式适配、长度截断、签名 |
> | `session_end` | 会话结束时 | 持久化、抽取长期记忆、清理资源 |
>
> **典型场景**：
>
> 1. **审计日志**：在每个节点记录 trace，便于事后追查
> 2. **风控**：在 `tool_before_call` 拦截高危操作（删数据库、转账超阈值）
> 3. **数据合规**：在 `llm_after_call` 做敏感信息脱敏（手机号、身份证）
> 4. **业务注入**：在 `context_before_assemble` 注入实时业务数据（如用户当前余额）
> 5. **可观测**：每个节点上报指标（latency、token、cost）
> 6. **A/B 实验**：在 `llm_before_call` 按用户分桶切换 prompt 版本
>
> **OpenClaw Hook 系统设计（基于工程推断）**：
>
> ```
> interface Hook {
>   name: string
>   node: HookNode                  // 挂载节点
>   priority: number                // 同节点多 hook 的执行顺序
>   run(context: HookContext): Promise<HookResult>
> }
>
> HookResult = Continue             // 继续
>           | ShortCircuit(result)  // 短路返回，跳过后续 hook 与核心逻辑
>           | Modify(newContext)    // 修改上下文后继续
> ```
>
> **设计要点**：
> - **同步 vs 异步**：低延迟 hook 同步，慢操作（如调外部审核 API）异步但可能短路灯
> - **错误隔离**：单个 hook 抛错不该崩溃主流程，降级跳过
> - **顺序确定性**：priority + name 双重排序，避免随机
> - **可观测**：每个 hook 单独记录耗时、命中次数
> - **可热插拔**：业务上线新 hook 不重启服务
>
> **与 Filter / Interceptor 的区别**：本质都是中间件，Hook 强调"挂载到具体生命周期节点"，更细粒度。

### [什么场景下需要多 Agent 协作而不是单个 Agent 解决？OpenClaw 是怎么支持子 Agent（Subagent）的？](https://www.mianshiya.com/bank/1906189461556076546/question/2036732695857414145)

> **答案**：
>
> **单 Agent 力不从心的典型场景**：
>
> 1. **任务跨多个专业领域**：如"分析这份财报并写一篇公关稿"——需要金融分析 + 写作两套专长，单 Agent 难以兼顾
> 2. **上下文窗口不够**：单个任务（如代码审计整个仓库）超出单 Agent 处理能力
> 3. **并行加速**：把独立子任务分给多个 Agent 并行跑（如同时搜索多个数据库）
> 4. **专业工具隔离**：不同子任务需要完全不同的工具集（避免工具过多稀释 LLM 注意力）
> 5. **安全隔离**：高风险操作交给有更严约束的专用 Agent
> 6. **多视角决策**：让多个 Agent 从不同角度提出方案，主 Agent 综合
>
> **OpenClaw 支持 Subagent 的方式（基于工程推断）**：
>
> **1. 显式 Subagent 调用工具**
> 父 Agent 工具列表里有一个特殊工具 `spawn_subagent(agent_name, task, context)`。父 Agent 调用后：
> - 框架启动一个新的 Agent 实例（独立的 Context、独立的 LLM 会话）
> - 子 Agent 处理 task
> - 子 Agent 返回结构化结果给父 Agent
> - 父 Agent 看到结果后继续主任务
>
> **2. 配置式 Subagent**
> 在 Agent 配置里声明："这个 Agent 可以调用哪些子 Agent"。子 Agent 是有边界的（独立的 system prompt、工具集、资源配额）。
>
> **3. 异步 Subagent**
> 长任务（如深度研究）异步运行，父 Agent 不阻塞，子 Agent 完成后通过事件通知。
>
> **4. 多 Agent 编排（Workflow）**
> 对于固定流程，用 LangGraph 风格的状态图编排多个 Agent 协作；对于开放式任务，由主 Agent 动态决定调用哪个子 Agent。
>
> **协作模式分类**：
>
> | 模式 | 描述 | 例子 |
> |------|------|------|
> | **Orchestrator-Worker** | 主 Agent 拆任务，分给子 Agent，汇总 | Deep Research |
> | **Pipeline** | 多个 Agent 串联，前者输出是后者输入 | 翻译 → 校对 → 润色 |
> | **Parallel Voter** | 多 Agent 独立解同一题，投票 | 代码评审多视角 |
> | **Hierarchical** | 多层嵌套 Subagent | 公司组织架构式 |
>
> **关键考量**：见下一题（父子 Agent 边界问题）。

### [父 Agent spawn 子 Agent 时，有哪些边界问题需要考虑？OpenClaw 做了哪些限制和保护？](https://www.mianshiya.com/bank/1906189461556076546/question/2036734120691843073)

> **答案**：
>
> 父 Agent spawn 子 Agent 是高风险操作——边界没设计好会导致资源耗尽、信息泄漏、死锁。
>
> **需要考虑的边界问题**：
>
> **1. 上下文隔离**
> - 子 Agent 不该自动继承父 Agent 的全部上下文（可能含敏感信息）
> - 应该显式传递 task description + 必要 context（最小权限原则）
> - 子 Agent 的中间过程不该自动回灌父 Agent（爆窗风险）
>
> **2. 权限边界**
> - 子 Agent 的工具权限可能比父更窄（如父能调金融数据库，子只能用通用搜索）
> - 子 Agent 不能 spawn 更深的子 Agent（防递归爆炸），或限制最大深度
> - 子 Agent 的资源配额（最大轮次、最大 token、最长时长）独立计算
>
> **3. 资源限额**
> - 单次父 Agent 运行能 spawn 的子 Agent 总数上限（防 fork bomb）
> - 同时活跃的子 Agent 数上限
> - 子 Agent 的 LLM 调用次数预算
> - 累积成本（美元）上限——超额自动停机
>
> **4. 错误传播**
> - 子 Agent 失败时父 Agent 怎么知道？是收到 error 结果还是异常？
> - 子 Agent 超时怎么办？同步等待会阻塞父 Agent
> - 子 Agent 死锁/无限循环需要外部 watchdog 杀死
>
> **5. 结果格式**
> - 子 Agent 返回什么？纯文本？结构化 JSON？文件引用？
> - 大结果（如代码仓库审计报告）应该走文件而非塞进上下文
>
> **6. 会话状态**
> - 子 Agent 有自己的 session_id，独立维护
> - 子 Agent 的长期记忆是否共享给父 Agent？
>
> **7. 安全与审计**
> - 子 Agent 的操作也要走 Policy Pipeline（不能因为父 Agent 已被授权就跳过）
> - 子 Agent 调用要单独记录审计日志
>
> **OpenClaw 的限制和保护（基于工程推断）**：
>
> ```
> SubagentConfig {
>   max_depth: 2,                    // 最多嵌套 2 层
>   max_concurrent_subagents: 5,     // 同时 ≤ 5 个子 Agent
>   max_total_subagents: 20,         // 单次任务累计 ≤ 20 个
>   max_steps_per_subagent: 50,      // 单子 Agent 最多 50 步
>   max_token_budget: 100K,          // 单子 Agent 最多消耗 100K token
>   max_wall_time_seconds: 300,      // 单子 Agent 最多跑 5 分钟
>   allowed_tools: [...],            // 工具白名单（父工具集的子集）
>   allowed_subagents: [...],        // 允许继续 spawn 的子 Agent 类型
>   inherit_user_scope: false,       // 不自动继承父的用户身份
>   result_format: "markdown" | "json" | "file_ref",
>   on_failure: "error" | "fallback_empty",
> }
> ```
>
> 框架层面在 spawn 时强制注入这些约束；运行时 watchdog 监控，超额自动终止。

### [多 Agent 之间如何通信和协调？OpenClaw 的 Gateway 在其中扮演什么角色？](https://www.mianshiya.com/bank/1906189461556076546/question/2036736171492589570)

> **答案**：
>
> 多 Agent 之间通信有几种主流模式：
>
> **1. 直接调用（同步）**
> Agent A 通过工具调用 Agent B，等待 B 返回结果。简单但有阻塞风险。
>
> **2. 消息总线（异步）**
> Agent 之间通过事件总线（如 Kafka、Redis Stream）发布/订阅消息。解耦，可扩展。
>
> **3. 共享状态（黑板模式）**
> 多个 Agent 读写同一份共享状态（如数据库表），通过状态变化触发协作。
>
> **4. 工作流编排（DAG）**
> 用状态机（如 LangGraph）声明多 Agent 协作流程，每个 Agent 是图中的一个节点。
>
> **Gateway 在其中扮演的角色**：
>
> Gateway 是 OpenClaw 中**多 Agent 协作的中枢和守护者**，不只是路由器：
>
> | 职责 | 描述 |
> |------|------|
> | **服务发现** | Agent 注册到 Gateway，调用方通过 Gateway 找到目标 Agent |
> | **路由** | 根据 agent_id / agent_type / capability 把消息送到对的 Agent |
> | **协议转换** | 不同 Agent 可能用不同通信协议（同步 RPC / 异步消息），Gateway 做转换 |
> | **鉴权** | Agent 之间的调用也要鉴权（不是任意 Agent 都能调任意 Agent） |
> | **限流与配额** | 防止某个 Agent 把另一个 Agent 打爆 |
> | **幂等** | 跨 Agent 调用的请求去重 |
> | **观测** | 全链路 trace，记录每个 Agent 的调用关系与耗时 |
> | **熔断** | 下游 Agent 异常时熔断，防止雪崩 |
> | **重试与超时** | 自动处理临时故障 |
> | **死信队列** | 持续失败的请求进入死信，人工排查 |
>
> **典型拓扑**：
>
> ```
> 用户消息 → Gateway
>               ├── 同步路由到 MainAgent
>               ├── MainAgent spawn → Gateway → 路由到 SubAgentA
>               ├── MainAgent spawn → Gateway → 路由到 SubAgentB
>               └── SubAgent 之间通过 Gateway 互相通信
> ```
>
> Gateway 充当"邮局"：每个 Agent 只跟 Gateway 通信，不直接互连。这种星型拓扑的好处是：
> - Agent 实现可以完全独立（不同语言、不同框架）
> - 集中治理（监控、安全、限流一处实现）
> - 容易扩展（新增 Agent 不影响其他）
>
> **与 A2A 协议的关系**：Google 提出的 A2A 协议本质上就是标准化 Agent 间通信协议——Gateway 在内部实现层面可以选用 A2A 作为 Agent 间通信的标准协议。

### [OpenClaw 采用插件架构，第三方可以注册新渠道、工具、Hook。设计一个插件系统需要考虑哪些关键问题？OpenClaw 的插件 API 长什么样？](https://www.mianshiya.com/bank/1906189461556076546/question/2036789078409691137)

> **答案**：
>
> 设计一个能注册 Channel / Tool / Hook 的插件系统，关键问题集中在**隔离、生命周期、API 设计、发现机制、安全**。
>
> **关键问题**：
>
> **1. 隔离边界**
> - 进程内（同进程加载，共享内存）——简单但不安全
> - 子进程（独立进程，IPC 通信）——隔离好但开销大
> - 容器/WASM（轻量隔离）——平衡点
> - OpenClaw 大概率是进程内 + 严格 Schema 校验 + Hook 异常隔离
>
> **2. 生命周期**
> ```
> discovered → loaded → registered → enabled → running
>                                       ↓
>                             disabled / uninstalled
> ```
> - **发现**：从插件目录 / 包管理器 / Git URL 扫描
> - **加载**：动态导入代码（Python importlib / Node require）
> - **注册**：插件声明自己提供的 Channel / Tool / Hook，写入注册表
> - **启用**：通过配置或运行时 API 启用
> - **卸载**：清理资源，注销注册
>
> **3. API 设计（OpenClaw 风格，基于工程推断）**
>
> ```
> interface Plugin {
>   metadata: {
>     name: string
>     version: string
>     author: string
>     dependencies?: PluginDependency[]
>   }
>
>   // 注册时执行：声明提供的资源
>   register(registrar: PluginRegistrar): void
>
>   // 启动时执行：初始化资源（连接 DB、启动 worker 等）
>   init(context: PluginContext): Promise<void>
>
>   // 卸载时执行：清理资源
>   destroy(): Promise<void>
> }
>
> interface PluginRegistrar {
>   registerChannel(channel: ChannelPlugin): void
>   registerTool(tool: ToolDefinition, handler: ToolHandler): void
>   registerHook(hook: Hook): void
>   registerContextStrategy(strategy: ContextStrategy): void  // 进阶
>   registerSubagent(factory: SubagentFactory): void          // 进阶
> }
>
> interface PluginContext {
>   logger, metrics, config, secrets,  // 基础设施
>   llm(name?),                        // 调用 LLM
>   storage(namespace),                // 隔离的存储
>   http,                              // HTTP 客户端
> }
> ```
>
> **4. 配置与秘钥**
> - 插件配置独立目录（`plugins/<name>/config.yaml`）
> - 秘钥从环境变量或 secret manager 读，不写入插件代码
> - 配置 schema 在插件 manifest 中声明，框架校验
>
> **5. 发现机制**
> - 本地：扫描 `plugins/` 目录
> - 远程：插件市场（类似 npm registry），声明式安装
> - 内嵌：核心功能也可以用插件 API 实现自举
>
> **6. 安全**
> - 插件签名验证（防止恶意插件）
> - 权限声明：插件在 manifest 声明需要的权限（network、filesystem、subprocess）
> - 调用方配置：用户决定是否信任并授予对应权限
> - 沙盒：高风险插件走 WASM/容器隔离
>
> **7. 版本与依赖**
> - 插件依赖 OpenClaw 核心 API 的某个 semver 范围
> - 插件之间也可能依赖（A 依赖 B 提供的工具）
> - 依赖冲突时明确报错（不要静默失败）
>
> **8. 可观测**
> - 每个插件单独记录指标（调用量、错误率、耗时）
> - 单插件故障不影响其他插件（错误隔离）
>
> **典型 API 示例（伪代码）**：
> ```python
> class MyPlugin(Plugin):
>     def register(self, registrar):
>         registrar.register_channel(TelegramChannel())
>         registrar.register_tool({
>             "name": "send_notification",
>             "parameters": {...}
>         }, self.handle_notification)
>         registrar.register_hook(MessageOutgoingHook())
> ```
>
> **OpenClaw 实际 API 长什么样**：基于上述工程惯例，应该是 declarative manifest（YAML/JSON）+ 命令式 handler 代码。具体细节需查 OpenClaw Plugin 文档。

### [OpenClaw 的 Gateway 对 Agent 请求做了幂等性处理。为什么 Agent 系统特别需要幂等性？工具已经产生副作用时怎么办？](https://www.mianshiya.com/bank/1906189461556076546/question/2036789985465679873)

> **答案**：
>
> **为什么 Agent 系统特别需要幂等性**：
>
> Agent 系统的失败模式与普通 Web 服务不同，重试非常频繁：
>
> 1. **渠道 webhook 重试**：Telegram/Discord 等渠道在没收到 200 响应时会重发消息（同一条消息可能投递多次）
> 2. **网络抖动**：LLM API 调用失败时框架自动重试，但失败前可能已经触发过工具调用
> 3. **用户重复点击**：IM 端用户连点同一条消息
> 4. **超时重发**：长任务超时，上层重试
> 5. **工具副作用敏感**：Agent 调用的工具常有真实副作用（发消息、改数据库、转账），重复执行后果严重
>
> 如果 Gateway 不做幂等，单条用户消息可能被处理 N 次，触发 N 次工具调用——比如转账被转了 3 次、通知被发了 5 遍。
>
> **幂等性实现**：
>
> **1. 请求级幂等 key**
> - 每个入站请求生成唯一 id（来自渠道的 message_id，或框架生成的 uuid）
> - Gateway 收到请求 → 查 idempotency 缓存 → 命中直接返回之前的结果
> - 不命中则正常处理，处理完把结果写入缓存（带 TTL）
>
> **2. 关键节点 checkpoint**
> - Agent 运行的关键节点（LLM 调用前、工具调用前、最终回复前）持久化进度
> - 重启/重试时从最近 checkpoint 恢复，而不是从头再来
>
> **3. 工具级幂等要求**
> - 框架要求副作用工具自带幂等 key（如 `transfer_money(idempotency_key=...)`）
> - 框架在工具调用前生成 idempotency_key 传给工具，工具自己保证同 key 不重复执行
>
> **工具已经产生副作用时怎么办**：
>
> 这是最难的情况——工具调用已经发出去了，但回复没成功送达，上层重试。
>
> **策略 a：可逆工具（理想）**
> 工具支持回滚（如转账有 reverse 接口）。Gateway 在重试前先尝试回滚上一次的工具调用，再重新执行。
>
> **策略 b：状态查询（多数情况）**
> 副作用工具支持"查询这次操作的结果"（如转账有 `get_transfer_status(idempotency_key)`）。Gateway 重试时先查上次是否成功，成功则跳过，失败则重做。
>
> **策略 c：业务层补偿**
> 副作用不可逆（如已经发了消息）。Gateway 把"是否已发"的判断下推给业务层（工具自己用 dedup table 去重）。
>
> **策略 d：人工介入**
> 高价值副作用（如金融交易）走人工审批节点，避免框架自动重试。
>
> **OpenClaw 的具体做法（基于工程推断）**：
>
> ```
> Gateway 处理流程：
> 1. 收到 message，计算 idempotency_key = hash(channel, message_id)
> 2. 查 idempotency_cache：
>    - 命中且状态=completed → 直接返回缓存的回复
>    - 命中且状态=running → 等待或返回"正在处理"
>    - 命中且状态=failed → 视策略决定是否重试
>    - 不命中 → 进入处理流程
> 3. 处理时在关键节点写 checkpoint：
>    - tool_before_call: 持久化 tool_call_id + 参数
>    - tool_after_call: 持久化结果
>    - 重启时从 checkpoint 恢复
> 4. 工具调用带 idempotency_key，由工具或工具网关负责去重
> ```
>
> **核心原则**：**Gateway 保证请求级幂等，工具保证业务级幂等，两者分工**。

### [如果让你基于 OpenClaw 的设计理念从零搭建一个 Agent 框架，你会先做哪三个模块？为什么？](https://www.mianshiya.com/bank/1906189461556076546/question/2036796020414824450)

> **答案**：
>
> 如果基于 OpenClaw 的设计理念从零搭建，我会优先做这三个模块——按依赖顺序排：
>
> **1. Agent Runtime（Agentic Loop 引擎）—— 地基**
>
> **为什么先做**：这是任何 Agent 系统的核心循环。没有它，其他模块都无处附着。
>
> **最小实现**：
> - LLM 客户端封装（支持流式、工具调用）
> - ReAct 循环：LLM 推理 → 工具调用 → 结果回写 → 再推理
> - 简单的 conversation history 管理
> - 错误重试与超时
>
> **关键设计**：把 LLM 调用、工具调用、上下文更新都抽象成接口——后续可以替换实现。这一步定下整个系统的"形状"。
>
> **2. Context Engine —— 决定能力上限**
>
> **为什么第二个**：Agent Runtime 跑起来后，最先撞墙的就是上下文窗口。Context 工程做不好，Agent 跑两轮就崩。
>
> **最小实现**：
> - 滑动窗口（最近 N 轮保留）
> - 简单摘要压缩（窗口接近满时触发 LLM 摘要）
> - 工具结果裁剪（超长结果自动截断 + 提示"已截断，请用 read_more 查看"）
> - 可插拔接口（Strategy Pattern，方便后续替换）
>
> **关键设计**：把 Context 组装独立于 Agent Runner——不同 Agent 类型可以配不同 Context 策略。
>
> **3. Tool Hub + MCP 集成 —— 让 Agent 有"手脚"**
>
> **为什么第三个**：Agent 能"思考"了，但还只能输出文本。要让它真正干活，必须有工具体系。
>
> **最小实现**：
> - 工具注册与发现
> - 工具 Schema 定义（JSON Schema）
> - MCP 协议客户端（能直接接入公开的 MCP 生态，复用社区工具）
> - 简单的权限校验（白名单）
> - 工具结果处理（裁剪、错误转换）
>
> **关键设计**：直接采用 MCP 标准，不要自己造生态。MCP 已经有大量现成工具（文件系统、搜索、Git、数据库等），自己写适配层是浪费时间。
>
> **为什么这三个先于 Channel、Gateway、Hook？**
>
> - **Channel 是后期扩展**：先在 CLI/Web 一个渠道跑通，再加多渠道
> - **Gateway 是规模产物**：单 Agent 时 Gateway 简化为函数调用，等需要多 Agent 才正式搭
> - **Hook 是锦上添花**：核心循环跑通了再加 Hook，否则过早抽象
> - **Subagent 是高级能力**：单 Agent 没跑稳前不要碰多 Agent
>
> **一句话总结**：先把**循环**（Runtime）、**记忆**（Context）、**手脚**（Tool）三件事做扎实，Agent 就能稳定工作；其他模块都是规模化和工程化的延展。这与 OpenClaw 的设计哲学一致——核心稳了，扩展才不会塌。
