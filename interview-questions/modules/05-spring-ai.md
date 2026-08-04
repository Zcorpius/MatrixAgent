# Spring AI 框架

> 来源：[面试鸭 · 最全 AI 大模型面试题库（含详细答案）](https://www.mianshiya.com/bank/1906189461556076546)
> 本模块共 8 题。完整索引见 [bank-llm-by-topic.md](../bank-llm-by-topic.md)。

---



> 共 8 题

### [什么是查询重写？它有什么作用？如何基于 Spring AI 实现查询重写？](https://www.mianshiya.com/bank/1906189461556076546/question/1929742304764088322)

> **答案**：
>
> **查询重写（Query Rewriting）** 是 RAG 系统中预处理用户原始查询的技术——在拿用户 query 去检索之前，先用 LLM 把它改写成更适合检索的形式。
>
> **作用**：
>
> 1. **消除歧义**：用户问"它怎么实现的"，重写成"OpenClaw 多渠道网关架构原理"
> 2. **拆分多意图**：用户问"A2A 和 MCP 的区别和联系"，拆成两个查询分别检索
> 3. **添加关键词**：把口语化表达补成包含专业术语的查询
> 4. **跨语言对齐**：用户中文问，知识库英文，重写成英文查询
> 5. **指代消解**：把"他/它/那个"等代词替换成具体实体
>
> **典型策略**：
>
> - **Rewrite**：单查询改写
> - **Sub-query decomposition**：拆成多个子查询
> - **Step-back prompting**：先问更抽象的问题（"X 的原理是什么"→"X 属于哪类技术，原理是什么"）
> - **HyDE（Hypothetical Document Embeddings）**：先让 LLM 假设一个答案，用假设答案的 embedding 检索（论文证实有效）
>
> **Spring AI 实现（基于 QueryTransformer / Advisor 模式）**：
>
> Spring AI 1.0+ 提供 `QueryTransformer` 接口和 `RewriteQueryTransformer` 实现。配置类示例（伪代码风格，省略 Java text block 语法以避免文档嵌套问题）：
>
> ```java
> @Configuration
> public class RagConfig {
>
>     @Bean
>     public QueryTransformer queryTransformer(ChatClient chatClient) {
>         // 重写提示词模板（实际 Java 用 text block ""\u0022\u0022\u0022 包裹多行字符串，
>         // 此处用单行表示以方便文档展示）
>         String rewritePrompt =
>             "重写以下用户查询，使其更适合向量检索。" +
>             "要求：消除指代和歧义、补充专业术语、保留原意、" +
>             "输出单一改写后的查询，不要解释。" +
>             "原始查询: {query}";
>
>         return RewriteQueryTransformer.builder()
>             .chatClientBuilder(chatClient.builder())
>             .promptTemplate(new PromptTemplate(rewritePrompt))
>             .build();
>     }
> }
> ```
>
> 集成到 RAG 链：
>
> ```java
> @Service
> public class RagService {
>
>     private final ChatClient chatClient;
>     private final VectorStore vectorStore;
>     private final QueryTransformer queryTransformer;
>
>     public RagService(ChatClient.Builder builder,
>                       VectorStore vectorStore,
>                       QueryTransformer queryTransformer) {
>         this.chatClient = builder.build();
>         this.vectorStore = vectorStore;
>         this.queryTransformer = queryTransformer;
>     }
>
>     public String ask(String originalQuery) {
>         // Step 1: 改写
>         String rewritten = queryTransformer.transform(
>             new Query(originalQuery)
>         ).content();
>
>         // Step 2: 检索
>         List<Document> docs = vectorStore.similaritySearch(
>             SearchRequest.query(rewritten).withTopK(5)
>         );
>
>         // Step 3: 生成
>         return chatClient.prompt()
>             .user(rewritten)
>             .advisors(new QuestionAnswerAdvisor(vectorStore))
>             .call()
>             .content();
>     }
> }
> ```
>
> **更优雅的做法（用 Advisor 链）**：
>
> ```java
> @Bean
> public Advisor rewriteAdvisor(ChatClient chatClient) {
>     return new RewriteQueryTransformerAdvisor(
>         chatClient.builder(),
>         rewritePromptTemplate
>     );
> }
>
> // 业务侧
> String answer = chatClient.prompt()
>     .user(userQuery)
>     .advisors(rewriteAdvisor, qaAdvisor)  // 自动重写 + 检索
>     .call()
>     .content();
> ```

### [什么是上下文查询增强？它有什么作用？如何基于 Spring AI 实现上下文查询增强来处理无关问题？](https://www.mianshiya.com/bank/1906189461556076546/question/1929742305481314305)

> **答案**：
>
> **上下文查询增强（Contextual Query Enhancement / Contextual Enrichment）** 是在用户原始查询基础上，结合**短期对话历史**或**长期用户上下文**生成增强查询，让检索更精准。
>
> **它解决什么问题**：
>
> 用户问"那它怎么用？"——孤立看，无法检索。但如果知道：
> - 上一轮对话：用户在问"什么是 RAG"
> - 用户偏好：用户是 Java 工程师
> - 用户身份：用户在研究 Spring AI 框架
>
> 增强后的查询应该是："如何在 Spring AI 框架中使用 RAG（Java 实现）"
>
> **典型场景**：
>
> | 场景 | 原始 query | 上下文 | 增强 query |
> |------|----------|--------|----------|
> | 指代消解 | "他怎么实现的" | 上文讨论 OpenClaw | "OpenClaw 怎么实现的" |
> | 历史延续 | "继续" | 上文在写代码 | "继续写上一段代码的下一部分" |
> | 个性化 | "推荐几本书" | 用户偏好 Java、初级 | "Java 入门到中级推荐书" |
> | 任务上下文 | "出错怎么办" | 用户在跑 Spring Boot | "Spring Boot 启动报错如何排查" |
>
> **和查询重写的区别**：
> - **查询重写**：基于 query 自身改写（语义同义、消除歧义）
> - **上下文增强**：基于 query 之外的上下文（对话历史、用户画像）增强
>
> **和"无关问题"处理的关系**：
>
> 上下文增强的一个关键应用是**判断用户问题是否与上下文相关**：
> - 如果用户问"今天天气怎么样"，而对话上下文都是技术问题——可能不是想问技术
> - 但也可能是把"今天"用作示例，问"今天"在 Spring AI 中怎么取
> - 上下文增强用 LLM 判断：真的无关 → 让 query 保持原样走通用检索；看似无关实则相关 → 增强后检索
>
> **Spring AI 实现**：
>
> ```java
> @Component
> public class ContextualQueryEnhancer {
>
>     private final ChatClient chatClient;
>     private final ChatMemory chatMemory;  // 短期对话记忆
>
>     public ContextualQueryEnhancer(ChatClient.Builder builder, ChatMemory chatMemory) {
>         this.chatClient = builder.build();
>         this.chatMemory = chatMemory;
>     }
>
>     public String enhance(String conversationId, String userQuery) {
>         // 1. 取最近对话历史
>         List<Message> history = chatMemory.get(conversationId, 10);
>
>         // 2. 用 LLM 增强（system prompt 用字符串拼接表示，实际可用 Java text block）
>         String systemPrompt =
>             "你是查询增强助手。根据对话历史和用户偏好，" +
>             "把用户最新查询改写成更适合知识库检索的形式。" +
>             "如果用户查询与上下文无关（比如突然问天气），" +
>             "保持原样不要强行关联。" +
>             "输出唯一一行改写后的查询，不要解释。";
>
>         String enhanced = chatClient.prompt()
>             .system(systemPrompt)
>             .user(u -> u.text("对话历史:\n{history}\n\n用户最新查询: {query}")
>                        .param("history", formatHistory(history))
>                        .param("query", userQuery))
>             .call()
>             .content();
>
>         return enhanced != null && !enhanced.isBlank() ? enhanced : userQuery;
>     }
> }
> ```
>
> 集成到 RAG：
>
> ```java
> public String ask(String conversationId, String userQuery) {
>     // 1. 上下文增强
>     String enhanced = contextualQueryEnhancer.enhance(conversationId, userQuery);
>
>     // 2. 检索
>     List<Document> docs = vectorStore.similaritySearch(
>         SearchRequest.query(enhanced).withTopK(5)
>     );
>
>     // 3. 生成（用原始 query 回答，避免 query 改动影响答案）
>     return chatClient.prompt()
>         .user(userQuery)
>         .advisors(new QuestionAnswerAdvisor(vectorStore))
>         .call()
>         .content();
> }
> ```
>
> **关键设计点**：
>
> 1. **改写用于检索，原 query 用于回答**——避免改写改变了用户意图
> 2. **判断无关问题**：增强器内部判断是否真无关，避免硬扯
> 3. **回退兜底**：增强失败或为空时，用原 query
> 4. **可观测**：记录"原 query → 增强 query"的对照，便于调试检索质量

### [什么是 Spring AI 提出的模块化 RAG 架构？预检索、检索和后检索阶段各自负责什么？](https://www.mianshiya.com/bank/1906189461556076546/question/1929742305821052929)

> **答案**：
>
> Spring AI 提出的模块化 RAG 架构把 RAG 流水线拆成三个清晰阶段：**预检索（Pre-retrieval）→ 检索（Retrieval）→ 后检索（Post-retrieval）**。每个阶段是独立可替换的模块。
>
> **整体架构**：
>
> ```
> 用户 query
>    ↓
> ┌──────────────────────────────────────────┐
> │  Pre-retrieval (预检索)                  │
> │  - 查询重写 QueryTransformer            │
> │  - 查询增强 ContextualQueryEnhancer     │
> │  - 查询路由 QueryRouter                  │
> │  - 多查询扩展 MultiQueryExpander        │
> └──────────────────────────────────────────┘
>    ↓ 增强后的 query（一个或多个）
> ┌──────────────────────────────────────────┐
> │  Retrieval (检索)                        │
> │  - VectorStore.similaritySearch()       │
> │  - 多源检索（多 VectorStore 聚合）       │
> │  - 关键词 + 向量混合检索                 │
> │  - 过滤（metadata filter）              │
> └──────────────────────────────────────────┘
>    ↓ 候选文档（Top-K）
> ┌──────────────────────────────────────────┐
> │  Post-retrieval (后检索)                 │
> │  - Rerank 重排序                         │
> │  - 去重                                  │
> │  - 截断 / 压缩                           │
> │  - 上下文拼接 ContextualPromptBuilder   │
> └──────────────────────────────────────────┘
>    ↓ 最终的 prompt
>    → LLM 生成答案
> ```
>
> **各阶段职责**：
>
> **预检索（Pre-retrieval）**：让 query 更适合检索
> - **QueryTransformer**：查询重写，消除歧义、补全术语
> - **ContextualQueryEnhancer**：结合对话历史增强 query
> - **QueryRouter**：根据 query 类型路由到不同知识库（技术文档走 VectorStore A，FAQ 走 VectorStore B）
> - **MultiQueryExpander**：从一个 query 生成多个变体（不同角度），并行检索后合并
>
> **检索（Retrieval）**：从知识库取出候选
> - **VectorStore**：核心抽象，支持多种实现（Milvus、Pinecone、Chroma、Pgvector、Redis...）
> - **SimilaritySearch**：向量相似度搜索
> - **Filter**：metadata 过滤（如"只检索 2025 年的文档"）
> - **Hybrid Search**：向量 + 关键词（BM25）混合
>
> **后检索（Post-retrieval）**：精炼检索结果
> - **Rerank**：用交叉编码器（Cross-Encoder）对 Top-K 重新排序，精度比向量相似度高
> - **Deduplication**：去重（同一内容可能被多个 chunk 检索到）
> - **ContextualCompression**：压缩冗余内容
> - **PromptBuilder**：把检索结果格式化成 LLM 友好的上下文
>
> **Spring AI 的模块化实现（基于 Advisor）**：
>
> ```java
> @Bean
> public Advisor modularRagAdvisor(
>         VectorStore vectorStore,
>         QueryTransformer queryTransformer,
>         QueryRouter queryRouter) {
>
>     return BaseAdvisor.builder()
>         // 预检索
>         .beforeWrite(queryTransformer::transform)
>         .beforeWrite(queryRouter::route)
>         .build()
>         .withExistingAdvisor(new QuestionAnswerAdvisor(vectorStore));
> }
>
> // 业务侧调用
> String answer = chatClient.prompt()
>     .user(query)
>     .advisors(modularRagAdvisor)
>     .call()
>     .content();
> ```
>
> **为什么模块化**：
>
> 1. **可替换**：换 Rerank 算法不影响其他阶段
> 2. **可观测**：每阶段单独打点，定位"是 query 改写差还是 rerank 差"
> 3. **可组合**：不同业务场景配不同 pipeline（客服 vs 技术问答）
> 4. **可演进**：加新阶段（如 HyDE、Self-RAG）不需要改架构
>
> **和其他 RAG 架构对比**：
>
> - **Naive RAG**：单阶段，query → vector → LLM
> - **Modular RAG**（Spring AI / LlamaIndex 提出）：三阶段模块化
> - **Advanced RAG**：在 Modular 基础上加更多优化（HyDE、Rerank、Self-Reflection）
> - **Agentic RAG**：用 Agent 决策"是否检索、检索什么、检索几次"
>
> Spring AI 的模块化 RAG 是工程实践友好的中间方案——足够灵活但不过度复杂。

### [什么是工具调用 Tool Calling？如何利用 Spring AI 实现工具调用？](https://www.mianshiya.com/bank/1906189461556076546/question/1929742306206928897)

> **答案**：
>
> **Tool Calling（工具调用）** 让 LLM 能调用外部函数完成它本身做不了的事——查询数据库、调用 API、执行代码、读写文件。Spring AI 提供完整的工具调用支持。
>
> **Spring AI 工具调用三种实现方式**：
>
> **方式 1：@Tool 注解（最简单）**
>
> ```java
> @Component
> public class WeatherTools {
>
>     @Tool(description = "Get current weather for a city. Use when user asks about weather, temperature, or forecast.")
>     public String getWeather(String city) {
>         return weatherClient.fetch(city);
>     }
>
>     @Tool(description = "Get weather forecast for next N days (max 7).")
>     public String getForecast(String city, int days) {
>         return weatherClient.forecast(city, days);
>     }
> }
> ```
>
> **方式 2：MethodToolCallback（编程式）**
>
> ```java
> ToolCallback weatherTool = MethodToolCallback.builder()
>     .toolDefinition(ToolDefinition.builder()
>         .name("get_weather")
>         .description("Get current weather")
>         // 实际 Java 用 text block，此处以拼接形式展示 schema
>         .inputSchema("{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}")
>         .build())
>     .toolMethod(this::getWeather)
>     .build();
> ```
>
> **方式 3：FunctionCallback（兼容 OpenAI Function Calling 风格）**
>
> ```java
> @Bean
> public FunctionCallback weatherFunction() {
>     return FunctionCallbackWrapper.builder((Function<WeatherRequest, String>) req -> fetchWeather(req.city()))
>         .withName("get_weather")
>         .withDescription("Get current weather")
>         .withInputType(WeatherRequest.class)
>         .build();
> }
> ```
>
> **集成到 ChatClient**：
>
> ```java
> @Service
> public class AgentService {
>
>     private final ChatClient chatClient;
>
>     public AgentService(ChatClient.Builder builder, WeatherTools weatherTools) {
>         this.chatClient = builder
>             .defaultTools(weatherTools)
>             .build();
>     }
>
>     public String ask(String userMsg) {
>         return chatClient.prompt()
>             .user(userMsg)
>             .call()
>             .content();
>         // Spring AI 自动处理 LLM 的 tool_call 决策，
>         // 调用对应方法，结果回传 LLM，循环直到最终回复
>     }
> }
> ```
>
> **Spring AI 处理工具调用的内部流程**：
>
> 1. 启动时扫描 `@Tool` 注解的方法，转成 JSON Schema 注入请求
> 2. LLM 收到 query + 工具定义，决策调哪个工具
> 3. LLM 输出 `tool_calls`
> 4. Spring AI 拦截 `tool_calls`，反射调用对应 Java 方法
> 5. 结果序列化成 LLM 友好格式回传
> 6. LLM 看到结果后继续推理或返回最终答案
> 7. 多轮循环（直到 LLM 不再调用工具）
>
> **动态工具（按需注入）**：
>
> ```java
> public String askWithDynamicTools(String userMsg, List<ToolCallback> tools) {
>     return chatClient.prompt()
>         .user(userMsg)
>         .tools(tools)
>         .call()
>         .content();
> }
> ```
>
> **高级特性**：
>
> 1. **返回 Java 对象自动转 JSON**：工具方法返回 `Order` 对象，自动序列化
> 2. **异常处理**：异常自动转 `is_error: true` 的结构化 result
> 3. **Streaming 兼容**：流式调用时工具结果作为中间 chunk 推送
> 4. **MCP 集成**：MCP server 暴露的工具自动转 ToolCallback
> 5. **Spring 事务/安全**：工具方法可加 `@Transactional` `@Secured` 等 Spring 注解
>
> **多 LLM Provider 兼容**：
>
> ```yaml
> spring:
>   ai:
>     openai:
>       api-key: ${OPENAI_API_KEY}
>     # 或 anthropic, azure openai, ollama, zhipuai, minimax...
> ```
>
> 工具定义在框架层抹平不同 LLM 的 Function Calling 格式差异——同一份工具代码可跨 OpenAI、Anthropic、Ollama 等使用。
>
> **最佳实践**：
>
> 1. **description 写"什么时候用"**，不只是"能做什么"
> 2. **参数带例子**：`city: 城市名，例如"北京"、"上海"`
> 3. **副作用工具描述警告**："仅在用户明确确认后调用"
> 4. **复杂工具拆细**：单工具职责单一
> 5. **工具内做参数校验**：不要假设 LLM 一定传对

### [你在 AI 超级智能体项目中如何利用 Spring AI 开发应用？用到了哪些特性？](https://www.mianshiya.com/bank/1906189461556076546/question/1929742299907084290)

> **答案**：
>
> （这道题偏个人项目经验。给一个"如果做"的范例回答模板，结合 Spring AI 的核心特性。）
>
> **项目背景**：基于 Spring AI 开发一个企业级"超级智能体"——能处理客户支持、内部知识问答、自动化工作流等多类任务。
>
> **整体架构**：
>
> ```
> ┌──────────────────────────────────────────┐
> │  Web/Mobile/API 客户端                   │
> └──────────────────────────────────────────┘
>                   ↓
> ┌──────────────────────────────────────────┐
> │  Spring Boot + Spring AI 编排层           │
> │  ┌────────────────────────────────────┐  │
> │  │  ChatClient（多模型路由）          │  │
> │  │  ├─ OpenAI GPT-4o（默认）          │  │
> │  │  ├─ Anthropic Claude（长上下文）   │  │
> │  │  └─ 通义千问（国产）              │  │
> │  └────────────────────────────────────┘  │
> │  ┌────────────────────────────────────┐  │
> │  │  Advisor 链（AOP 式增强）          │  │
> │  │  ├─ LoggingAdvisor                 │  │
> │  │  ├─ SafetyAdvisor                  │  │
> │  │  ├─ RagAdvisor                     │  │
> │  │  ├─ MemoryAdvisor                  │  │
> │  │  └─ RetryAdvisor                   │  │
> │  └────────────────────────────────────┘  │
> │  ┌────────────────────────────────────┐  │
> │  │  Tools（Spring AI @Tool）          │  │
> │  │  ├─ OrderTools                     │  │
> │  │  ├─ CrmTools                       │  │
> │  │  ├─ EmailTools                     │  │
> │  │  └─ CalendarTools                  │  │
> │  └────────────────────────────────────┘  │
> │  ┌────────────────────────────────────┐  │
> │  │  VectorStore（多源）               │  │
> │  │  ├─ Milvus（业务知识）             │  │
> │  │  ├─ Redis（用户画像）             │  │
> │  │  └─ Pgvector（FAQ）                │  │
> │  └────────────────────────────────────┘  │
> └──────────────────────────────────────────┘
> ```
>
> **用到的 Spring AI 特性**：
>
> **1. ChatClient 统一抽象**
> - 一套 API 调用任意模型，切换模型只改配置
> - 多模型路由：简单问题用便宜模型，复杂问题用贵模型
> - Streaming 支持：流式返回，UX 好
>
> **2. Advisor 链（AOP 式增强）**
> - 自定义 Advisor 实现横切关注点：日志、安全、限流、RAG、Memory
> - 不修改业务代码就能加新能力
> - 顺序可控（priority）
>
> **3. Tool Calling**
> - 用 `@Tool` 把现有 Spring Service 暴露给 LLM
> - 工具调用与 Spring 事务/安全自动集成
> - 工具描述可审计
>
> **4. 结构化输出**
> - `BeanOutputConverter` 自动把 LLM 输出转 Java 对象
> - 用 `Map<String, Object>` `List<Entity>` `record User(...)` 等
> - 配合 JSON Schema 校验
>
> **5. RAG（模块化）**
> - `VectorStore` 抽象屏蔽底层向量库差异
> - 预检索 / 检索 / 后检索三阶段独立可替换
> - 支持 metadata 过滤、混合检索
>
> **6. Memory（短期对话记忆）**
> - `ChatMemory` 接口，支持 InMemory / Redis / JDBC
> - 多会话隔离
> - 滑动窗口策略
>
> **7. Evaluation（评估）**
> - `RelevancyEvaluator` 检测答案是否相关
> - `FactCheckingEvaluator` 检测事实正确性
> - 自动化测试集评估
>
> **8. Observation（可观测）**
> - 集成 Micrometer，每个 LLM 调用 / 工具调用 / 检索自动埋点
> - 接 Prometheus + Grafana + Jaeger
> - token 消耗、延迟、错误率一目了然
>
> **9. MCP 集成**
> - 启动外部 MCP server，工具自动注入
> - 自己业务能力也能暴露成 MCP server 给外部用
>
> **10. Spring Boot Actuator 集成**
> - 健康检查、metrics、配置刷新全部原生支持
>
> **典型工作流举例**：
>
> 用户："上周的订单 #1234 怎么还没发货？"
>
> 1. LoggingAdvisor 记录请求
> 2. SafetyAdvisor 检查合规
> 3. RagAdvisor 检索公司物流政策
> 4. MemoryAdvisor 注入用户历史
> 5. ChatClient 把 query + tools 给 LLM
> 6. LLM 决策调用 `getOrderStatus(orderId=1234)`
> 7. Spring AI 调 OrderTools.getOrderStatus()
> 8. 结果回传 LLM
> 9. LLM 看到订单被卡在仓库，决策调用 `createSupportTicket()`
> 10. 工单创建后 LLM 综合知识库政策回复用户
> 11. RetryAdvisor 处理可能的失败重试
> 12. LoggingAdvisor 记录响应
>
> **收益**：
> - 开发效率：相比裸调 LLM API，代码量减少 60%
> - 可维护：Advisor 解耦，新需求易加
> - 可观测：每个环节有指标
> - 可演进：换模型、加向量库、加 MCP 工具都不动业务代码

### [什么是结构化输出？Spring AI 是怎么实现结构化输出的？](https://www.mianshiya.com/bank/1906189461556076546/question/1929742301668691969)

> **答案**：
>
> **结构化输出**（Structured Output）指让 LLM 按预定义的 JSON Schema 输出，而不是自由文本。这是把 LLM 从"聊天助手"变成"应用组件"的关键能力——只有结构化才能被下游代码可靠消费。
>
> **为什么重要**：
>
> - 自由文本无法直接 parse
> - 即使 prompt 要求"输出 JSON"，LLM 可能加 markdown 围栏、自然语言注释、字段顺序乱
> - 不同 LLM 输出风格不同，应用层难统一处理
>
> **Spring AI 的实现机制**：
>
> Spring AI 用 `BeanOutputConverter` / `MapOutputConverter` 等转换器，把"让 LLM 输出 X 类型的对象"这套流程标准化：
>
> **机制三层**：
>
> 1. **Schema 注入**：转换器根据 Java 类型生成 JSON Schema，自动拼到 prompt 末尾，告诉 LLM "请按这个 schema 输出"
> 2. **解析**：LLM 返回 JSON 字符串，转换器 parse 成 Java 对象
> 3. **校验**：可选地校验字段类型、必填项、enum 值
>
> **用法 1：返回 Java 对象**
>
> ```java
> public record OrderInfo(String orderId, String status, BigDecimal amount, List<String> items) {}
>
> @Service
> public class OrderService {
>
>     private final ChatClient chatClient;
>
>     public OrderInfo extractOrder(String userMessage) {
>         return chatClient.prompt()
>             .user(u -> u.text("从用户消息中提取订单信息：{msg}").param("msg", userMessage))
>             .call()
>             .entity(OrderInfo.class);  // 关键：直接返回 Java 对象
>     }
> }
> ```
>
> 底层 Spring AI 自动：
> - 给 OrderInfo 生成 JSON Schema
> - 把 schema 注入 prompt 的 `{format}` 占位符（或追加到末尾）
> - 调用 LLM
> - 把返回的 JSON 字符串 parse 成 `OrderInfo`
>
> **用法 2：返回 List / Map**
>
> ```java
> List<Product> products = chatClient.prompt()
>     .user("列出 5 款适合程序员的笔记本电脑")
>     .call()
>     .entity(new ParameterizedTypeReference<List<Product>>() {});
>
> Map<String, Object> result = chatClient.prompt()
>     .user("分析这段代码：" + code)
>     .call()
>     .entity(new ParameterizedTypeReference<Map<String, Object>>() {});
> ```
>
> **用法 3：Streaming 模式下的结构化输出**
>
> ```java
> Flux<OrderInfo> stream = chatClient.prompt()
>     .user(userMessage)
>     .stream()
>     .entity(OrderInfo.class);  // 累积完整 JSON 后再 parse
> ```
>
> **用法 4：配合 Advisor 链**
>
> ```java
> String answer = chatClient.prompt()
>     .user("总结这份 PDF")
>     .advisors(ragAdvisor)
>     .call()
>     .entity(String.class);  // 结构化输出与 Advisor 兼容
> ```
>
> **Schema 自定义与提示**：
>
> ```java
> @Bean
> public OutputConverter<OrderInfo> orderConverter() {
>     return BeanOutputConverter.builder()
>         .targetType(OrderInfo.class)
>         .validator(customValidator())
>         .build();
> }
> ```
>
> **实际生成的 prompt（举例）**：
>
> LLM 收到的完整 prompt 大致长这样（Spring AI 自动注入 schema）：
>
> ```
> 从用户消息中提取订单信息：
> "我昨天买了本书订单号 ORD-2026-001，状态显示已发货，金额 89.5"
>
> Your response should be in JSON format.
> Do not include any explanations, only provide a RFC8259 compliant JSON response
> following this format without deviation.
> Do not include markdown code blocks in your response.
> Here is the JSON Schema instance your output must adhere to:
> {
>   "type": "object",
>   "properties": {
>     "orderId": {"type": "string"},
>     "status": {"type": "string", "enum": ["pending", "shipped", "delivered"]},
>     "amount": {"type": "number"},
>     "items": {"type": "array", "items": {"type": "string"}}
>   },
>   "required": ["orderId", "status", "amount"]
> }
> ```
>
> **优势**：
>
> 1. **类型安全**：编译期就知道返回类型
> 2. **零样板**：不需要手写 prompt schema、JSON parser
> 3. **多模型兼容**：转换器适配 OpenAI / Anthropic / 通义等不同模型的结构化输出格式（OpenAI 有 `response_format: json_object`，Anthropic 用 prompt 引导）
> 4. **校验集成**：可挂 Bean Validation
> 5. **错误处理**：parse 失败有清晰异常
>
> **实际应用**：
> - 信息抽取（简历→结构化数据）
> - 分类（评论→情感分类）
> - 决策（用户输入→路由决策）
> - 数据增强（已知字段→补全缺失字段）
> - 工作流触发（自然语言→step 列表）

### [什么是 Re-Reading？如何基于 Spring AI 实现 Re-Reading Advisor？](https://www.mianshiya.com/bank/1906189461556076546/question/1929742301949710338)

> **答案**：
>
> **Re-Reading** 是一种推理增强策略——让 LLM 在生成最终答案前，对输入做一次或多次"重新阅读"和"自我重述"，本质是用多轮 prompt 处理同一输入提升理解深度。
>
> **原理**：
>
> 类似人类做阅读理解——读一遍只能抓大意，重读才能抓细节、做推理。LLM 单次处理容易"看快了漏信息"，强制让它先复述/分析，再回答，能显著减少细节遗漏和逻辑错误。
>
> 论文显示 Re-Reading / Self-Ask / Chain-of-Thought 这类"慢思考"策略在数学、推理、复杂指令任务上提升明显。
>
> **典型应用形式**：
>
> 1. **理解复述型**："先复述用户问题，然后回答"
> 2. **多视角重读**：从不同角度（用户意图、技术细节、潜在风险）多次重读
> 3. **疑问消解型**：先列出"这个问题可能的歧义点"，再选定一个解读回答
> 4. **回声型（Echo）**：在 prompt 中重复关键约束（如"记住：金额必须 > 0；金额必须 > 0"）
>
> **和 Self-Consistency 的区别**：
> - Re-Reading 是单链多步骤
> - Self-Consistency 是多次独立采样后投票
>
> **Spring AI 实现（基于 Advisor 模式）**：
>
> ```java
> @Component
> public class ReReadingAdvisor implements BaseAdvisor {
>
>     private final ChatClient chatClient;
>     private final int reReadTimes;
>
>     public ReReadingAdvisor(ChatClient.Builder builder) {
>         this(builder, 1);
>     }
>
>     public ReReadingAdvisor(ChatClient.Builder builder, int reReadTimes) {
>         this.chatClient = builder.build();
>         this.reReadTimes = reReadTimes;
>     }
>
>     @Override
>     public String getName() { return "ReReadingAdvisor"; }
>
>     @Override
>     public int getOrder() { return 50; }  // 在 RAG/Memory 之后，LLM 调用之前
>
>     @Override
>     public AdvisedResponse aroundCall(AdvisedRequest request, CallAdvisorChain next) {
>         // 1. 第一次 LLM 调用：让模型复述并分析问题
>         // system prompt 用字符串拼接表示，实际 Java 可用 text block ""\u0022\u0022\u0022
>         String analysisSystemPrompt =
>             "你是查询分析助手。请仔细阅读用户问题，做以下处理：" +
>             "1. 用你自己的话复述问题；" +
>             "2. 列出问题中的关键约束；" +
>             "3. 指出可能的歧义或遗漏信息；" +
>             "4. 推断用户的真实意图。" +
>             "不要直接回答问题。";
>
>         String analysis = chatClient.prompt()
>             .system(analysisSystemPrompt)
>             .user(request.userText())
>             .call()
>             .content();
>
>         // 2. 把分析结果作为 context 加到原始 prompt
>         String enhancedPrompt =
>             "用户原始问题: " + request.userText() + "\n\n" +
>             "问题分析:\n" + analysis + "\n\n" +
>             "请基于上述分析，回答用户问题。";
>
>         AdvisedRequest enhanced = AdvisedRequest.from(request)
>             .withUserText(enhancedPrompt)
>             .build();
>
>         // 3. 继续后续 advisor 链（最终调 LLM 生成答案）
>         return next.aroundCall(enhanced);
>     }
> }
> ```
>
> **集成**：
>
> ```java
> @Bean
> public ChatClient chatClient(ChatClient.Builder builder,
>                               ReReadingAdvisor reReadingAdvisor,
>                               RagAdvisor ragAdvisor) {
>     return builder
>         .defaultAdvisors(reReadingAdvisor, ragAdvisor)
>         .build();
> }
>
> // 业务侧无感知
> String answer = chatClient.prompt()
>     .user("我的订单 1234 还没到，要退款，但客服说不能退")
>     .call()
>     .content();
> ```
>
> LLM 会先看到"分析"步骤，然后基于分析回答——对复杂客服场景特别有用。
>
> **变体实现：Re-Reading 多次迭代**
>
> ```java
> for (int i = 0; i < reReadTimes; i++) {
>     analysis = chatClient.prompt()
>         .system("Re-read and refine the analysis. Round " + (i + 1))
>         .user(analysis)
>         .call()
>         .content();
> }
> ```
>
> **变体实现：Spring AI 内置的 SelfConsistencyAdvisor**
>
> Spring AI 1.x 起在 sandbox 模块提供了一些现成的 reasoning advisor：
>
> ```java
> @Bean
> public SelfConsistencyAdvisor selfConsistency(ChatClient.Builder builder) {
>     return SelfConsistencyAdvisor.builder(builder)
>         .numSamples(3)
>         .build();
> }
> ```
>
> **何时用 Re-Reading**：
>
> ✅ 复杂指令（多约束、需要消解）
> ✅ 多步推理任务（数学、逻辑题）
> ✅ 客服 / 工单分类（避免误判意图）
> ✅ 关键事实抽取（避免遗漏）
>
> ❌ 简单闲聊（白白增加 token 成本）
> ❌ 实时性要求极高的场景（多一次 LLM 调用增加延迟）
>
> **Trade-off**：
> - 优点：准确性显著提升（论文数据 +5%~20%）
> - 缺点：多一次 LLM 调用，延迟翻倍、成本翻倍
> - 适用：准确性 > 成本/延迟 的场景（医疗、法律、金融）

### [什么是 Spring AI 框架？它有哪些核心特性？](https://www.mianshiya.com/bank/1906189461556076546/question/1929742299558957057)

> **答案**：
>
> **Spring AI 是 Spring 官方推出的 AI 应用开发框架，目标是把 Spring 生态的工程能力（依赖注入、AOP、配置管理、安全、可观测性）带入 AI 应用开发**——让 Java/Spring 工程师能用熟悉的 Spring 风格构建生产级 AI 应用。
>
> **核心定位**：
> - Spring 生态的 AI 一站式框架（类似 Spring Data 之于数据库、Spring Security 之于安全）
> - 屏蔽不同 AI 提供商的差异（OpenAI、Anthropic、Azure、Ollama、通义、智谱等）
> - 把 AI 能力（Chat、Embedding、Vector、Tool、RAG、Memory）做成 Spring 一等公民
>
> **核心特性**：
>
> **1. ChatClient —— 统一的对话客户端**
>
> ```java
> ChatClient client = ChatClient.create(chatModel);
> String answer = client.prompt()
>     .system("你是客服助手")
>     .user("订单 1234 状态？")
>     .call()
>     .content();
> ```
>
> 通过 `spring.ai.openai.chat.options.model` 等配置切换模型，业务代码不变。
>
> **2. 多模型抽象（Model API）**
>
> - `ChatModel`：对话
> - `EmbeddingModel`：向量化
> - `ImageModel`：图像生成
> - `AudioModel`：语音
> - `ModerationModel`：内容审核
>
> 每个接口有多种实现（OpenAI、Anthropic、Ollama、Stability AI、Azure 等），切换只需改 starter 依赖。
>
> **3. Tool Calling**
>
> - `@Tool` 注解：把 Spring Bean 方法暴露给 LLM
> - 自动 Schema 生成、调用分发、结果序列化
> - 兼容 OpenAI / Anthropic / Gemini 的工具调用格式
>
> **4. RAG 模块化流水线**
>
> - `VectorStore` 抽象：支持 20+ 向量库（Milvus、Pinecone、Chroma、Pgvector、Redis、Weaviate 等）
> - `DocumentReader` / `DocumentSplitter` / `DocumentTransformer`：文档处理流水线
> - `QuestionAnswerAdvisor`：开箱即用的 RAG Advisor
>
> **5. Advisor（AOP 式增强）**
>
> - 拦截 `ChatClient` 调用，类似 Servlet Filter
> - 实现 RAG、Memory、Safety、Logging、Retry 等横切关注点
> - 顺序可控、链式组合
>
> **6. 结构化输出**
>
> - `entity(Class<T>)` 直接返回 Java 对象
> - `BeanOutputConverter` 自动处理 Schema 注入和 JSON 解析
> - 支持泛型集合、嵌套对象
>
> **7. Memory（对话记忆）**
>
> - `ChatMemory` 接口：InMemory、JDBC、Redis 实现
> - 多会话隔离
> - 滑动窗口、摘要压缩策略
>
> **8. Evaluation（评估）**
>
> - `RelevancyEvaluator`：相关性评估
> - `FactCheckingEvaluator`：事实核查
> - 自动化测试集评估流水线
>
> **9. Observation（可观测）**
>
> - 集成 Micrometer：每个 LLM 调用、工具调用、检索自动埋点
> - 暴露 metrics、traces 到 Prometheus / Jaeger / Zipkin
> - token 消耗、延迟、错误率可视化
>
> **10. MCP 集成**
>
> - 作为 MCP Client 消费外部 MCP server 工具
> - 作为 MCP Server 把自己能力暴露给外部
> - Spring Boot starter 一行配置接入
>
> **11. Spring 生态原生集成**
>
> - Spring Boot auto-config：开箱即用
> - Spring Security：API 鉴权、方法级权限
> - Spring Data：向量库与业务库共用事务
> - Spring Cloud：分布式部署
> - Actuator：健康检查、配置刷新
>
> **典型架构（基于 Spring Boot）**：
>
> ```
> ┌─────────────────────────────────┐
> │  Spring Boot Application        │
> │  ┌───────────────────────────┐  │
> │  │  Controller (REST API)    │  │
> │  └───────────────────────────┘  │
> │  ┌───────────────────────────┐  │
> │  │  Service                  │  │
> │  │  ├─ ChatClient            │  │
> │  │  ├─ VectorStore           │  │
> │  │  └─ Tools (@Tool)         │  │
> │  └───────────────────────────┘  │
> │  ┌───────────────────────────┐  │
> │  │  Advisors                 │  │
> │  │  ├─ RAG                   │  │
> │  │  ├─ Memory                │  │
> │  │  └─ Safety                │  │
> │  └───────────────────────────┘  │
> └─────────────────────────────────┘
>         ↓ Spring AI
> ┌─────────────────────────────────┐
> │  AI Providers                  │
> │  ├─ OpenAI / Anthropic / ...   │
> │  └─ Vector DBs                 │
> └─────────────────────────────────┘
> ```
>
> **和其他 AI 框架对比**：
>
> | 框架 | 语言 | 优势 |
> |------|------|------|
> | LangChain | Python | 生态最丰富、社区活跃 |
> | LlamaIndex | Python | RAG 专精 |
> | Semantic Kernel | C#/.NET | 微软生态原生 |
> | Spring AI | Java | Spring 生态原生、企业级 |
> | LangChain4j | Java | Java 生态，独立于 Spring |
>
> Spring AI 的差异化优势是**让企业级 Java 应用无缝集成 AI**——所有现有 Spring 投资（事务、安全、监控、配置中心、消息队列）都可直接复用，不需要为 AI 重造轮子。
