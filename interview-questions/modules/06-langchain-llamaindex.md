# LangChain 与 LlamaIndex

> 来源：[面试鸭 · 最全 AI 大模型面试题库（含详细答案）](https://www.mianshiya.com/bank/1906189461556076546)
> 本模块共 43 题。完整索引见 [bank-llm-by-topic.md](../bank-llm-by-topic.md)。

---



> 共 43 题

### [什么是 LangChain？](https://www.mianshiya.com/bank/1906189461556076546/question/1915335173711634433)

> **答案**：
>
> **LangChain** 是一个用于开发由大语言模型（LLM）驱动的应用程序的开源框架（Python / JS 双语言，由 Harrison Chase 于 2022 年 10 月创建）。它的核心定位是 **LLM 应用的「胶水层」**：把模型调用、提示词、记忆、工具、检索、链式编排等能力封装成可组合的抽象，让开发者能像搭积木一样快速构建聊天机器人、RAG 问答、Agent、文档摘要等应用，而不必每次都从零写 prompt 模板和 API 调用。
>
> **核心价值**：
> 1. **统一抽象**：屏蔽 OpenAI、Anthropic、本地模型等不同厂商 API 差异，切换模型只需改一行。
> 2. **可组合性（Composition）**：通过 LCEL（LangChain Expression Language）用 `|` 把 Prompt | Model | OutputParser 串成链。
> 3. **生态丰富**：集成 100+ 向量库、20+ Document Loader、数百个 Tool，是当前生态最大的 LLM 框架。
> 4. **LangGraph 升级**：从「链式」走向「图式」，支持循环、状态、人在回路，适合复杂 Agent。
>
> **典型用途**：RAG 文档问答、客服 Bot、个人助理、代码助手、自动化工作流。

### [LangChain 的核心组件有哪些？](https://www.mianshiya.com/bank/1906189461556076546/question/1916425041235525634)

> **答案**：
>
> LangChain 的核心组件可以分成以下几大块（以 0.1+ 版本为准）：
>
> | 模块 | 作用 | 关键类/概念 |
> |------|------|------------|
> | **Models** | 统一封装 LLM（纯文本）、ChatModel（对话）、Embedding 三类模型接口 | `ChatOpenAI`, `OpenAIEmbeddings` |
> | **Prompts** | 模板化提示词，支持变量插值、Few-shot、消息角色 | `PromptTemplate`, `ChatPromptTemplate`, `FewShotPromptTemplate` |
> | **Output Parsers** | 把模型输出的字符串解析成结构化数据（JSON、Pydantic、列表等） | `PydanticOutputParser`, `JsonOutputParser` |
> | **Chains / LCEL** | 把上述组件编排成流水线，LCEL 用 `|` 表达 | `RunnableSequence`, `LLMChain`(已弱化) |
> | **Memory** | 维护多轮对话历史 | `ConversationBufferMemory`, `ConversationSummaryMemory`, `BufferWindowMemory` |
> | **Agents & Tools** | 让 LLM 自主选择调用哪个工具 | `create_tool_calling_agent`, `@tool`, `Tool` |
> | **Retrieval** | RAG 的检索层 | `VectorStoreRetriever`, `MultiQueryRetriever`, `ContextualCompressionRetriever` |
> | **Document Loaders** | 加载 PDF/HTML/Notion/网页等异构数据 | `PyPDFLoader`, `WebBaseLoader` |
> | **Text Splitters** | 把长文档切块 | `RecursiveCharacterTextSplitter`, `MarkdownHeaderTextSplitter` |
> | **Vector Stores** | 向量检索抽象，对接 FAISS/Chroma/Pinecone 等 | `FAISS`, `Chroma` |
> | **Callbacks** | 钩子机制，用于日志、追踪、流式 | `BaseCallbackHandler`, `LangSmithCallbackHandler` |
>
> **模块化演进**：0.1 之后官方把核心拆成 `langchain-core`（基础抽象）、`langchain`（高层链）、`langchain-community`（第三方集成）、`langchain-experimental`（实验特性），降低耦合。

### [LangChain核心架构是什么样的](https://www.mianshiya.com/bank/1906189461556076546/question/1916425041541709826)

> **答案**：
>
> LangChain 的整体架构是「**分层抽象 + 可组合原语**」：
>
> ```
> ┌──────────────────────────────────────────────┐
> │  Application Layer  (Chat / RAG / Agent App) │
> ├──────────────────────────────────────────────┤
> │  Orchestration Layer                          │
> │   - LCEL (Runnable 接口)                      │
> │   - LangGraph (状态图, 支持循环/分支)          │
> ├──────────────────────────────────────────────┤
> │  Core Abstractions (langchain-core)           │
> │   Prompts | Models | OutputParsers            │
> │   Retrievers | VectorStores | Documents       │
> │   Memory | Tools | Callbacks | Messages       │
> ├──────────────────────────────────────────────┤
> │  Integrations (community / partner packages)  │
> │   OpenAI / Anthropic / Cohere / HuggingFace   │
> │   FAISS / Chroma / Pinecone / PGVector        │
> ├──────────────────────────────────────────────┤
> │  Observability  (LangSmith / LangServe)       │
> └──────────────────────────────────────────────┘
> ```
>
> **设计哲学**：
> 1. **Runnable 协议**：所有组件都实现 `invoke / batch / stream / astream` 统一接口，因此可以无缝拼接。
> 2. **LCEL 表达式语言**：用 `chain = prompt | model | parser` 这样的管道语法替代旧的 `LLMChain`，天然支持流式、异步、批量。
> 3. **LangGraph 作为新一代 Agent 编排**：弥补 Chain「无状态、不能循环」的缺陷，用「节点 + 边 + 状态」表达复杂工作流。
> 4. **可观测性优先**：通过 Callbacks + LangSmith 全链路追踪，解决 LLM 应用「黑盒」调试难题。
>
> 简言之：**底层抽象稳定、上层灵活组合、新场景用 LangGraph 兜底**。

### [什么是 LangChain Agent](https://www.mianshiya.com/bank/1906189461556076546/question/1916425041839505410)

> **答案**：
>
> **LangChain Agent** 是 LangChain 中让 LLM 「自主决策调用哪个工具」的运行模式，与固定流程的 Chain（链）相对。
>
> **核心思想**：把「做什么」交给 LLM 推理。LLM 在每一步观察用户问题 + 工具描述，**决定**是调用某个工具、还是直接回答、还是结束。本质上是 **ReAct（Reasoning + Acting）范式** 的工程实现。
>
> **关键组成**：
> - **LLM**：作为「大脑」做决策，通常用支持 function calling 的模型。
> - **Tools**：可被调用的函数（搜索、计算器、SQL 查询、API 调用等），用 `@tool` 装饰器或 `Tool` 类定义，并附带描述。
> - **Agent Executor**：循环驱动（思考 → 行动 → 观察 → 思考…），直到 LLM 给出 Final Answer 或达到 max_iterations。
> - **Agent Type**：ReAct、OpenAI Functions、Tool Calling、Structured Chat 等不同推理风格。
>
> **演进**：早期是 `AgentExecutor`，0.1+ 推荐用 `create_tool_calling_agent` + `AgentExecutor`，更复杂的循环/分支/人在回路则用 **LangGraph**。
>
> **典型场景**：能联网搜索的助手、自动写 SQL 查数据库的 BI Agent、能调多个 SaaS API 的工作流自动化。

### [什么是 LangChain model](https://www.mianshiya.com/bank/1906189461556076546/question/1916425042099552258)

> **答案**：
>
> **LangChain Model** 指 LangChain 对各类大模型的统一抽象层，主要分三类：
>
> | 抽象类 | 输入 / 输出 | 用途 | 代表实现 |
> |--------|------------|------|---------|
> | `LLM` | str → str | 传统纯文本补全（已少见） | `OpenAI`(text-davinci), `HuggingFaceHub` |
> | `ChatModel` | `List[BaseMessage]` → `BaseMessage` | 对话型模型（当前主流） | `ChatOpenAI`, `ChatAnthropic`, `ChatOllama` |
> | `Embeddings` | str → `List[float]` | 文本向量化，用于检索 | `OpenAIEmbeddings`, `HuggingFaceEmbeddings` |
>
> **关键设计**：
> 1. **统一接口**：所有 ChatModel 都实现 `invoke / stream / batch` + 异步版本，模型替换只改类名。
> 2. **消息角色**：用 `SystemMessage / HumanMessage / AIMessage / ToolMessage` 描述多轮对话。
> 3. **缓存**：通过 `set_llm_cache` 全局缓存相同 prompt 的结果，省钱省时。
> 4. **回调**：所有模型都接入 Callbacks，便于打日志和上传 LangSmith。
> 5. **结构化输出**：通过 `with_structured_output()` 让模型按 Pydantic Schema 返回 JSON。
>
> **版本变化**：0.1 之后 `LLM`（补全型）在产品中几乎不再使用，业务侧主要面对 `ChatModel`。

### [LlamaIndex 如何与 LangChain 结合？](https://www.mianshiya.com/bank/1906189461556076546/question/1916425042367987714)

> **答案**：
>
> **LlamaIndex 与 LangChain 结合的方式**：两者并非互斥，而是各有所长——LangChain 强在「**Agent 编排 + 通用链 + 工具生态**」，LlamaIndex 强在「**数据接入 + 索引 + 高质量 RAG**」。常见组合模式有：
>
> 1. **LlamaIndex 做 Retrieval，LangChain 做 Agent/Chain**
>    把 LlamaIndex 的 `Index` / `QueryEngine` 包装成一个 LangChain `Tool`，由 LangChain Agent 决定何时检索。
>    ```python
>    from llama_index.core import VectorStoreIndex
>    from langchain.agents import initialize_agent
>
>    query_engine = VectorStoreIndex.from_documents(docs).as_query_engine()
>    tool = Tool.from_function(query_engine.query, name="kb_search", description="...")
>    agent = initialize_agent([tool], llm, agent="zero-shot-react-description")
>    ```
>
> 2. **LangChain 做 Loader/Splitter，LlamaIndex 做高级索引**
>    用 LangChain 的 `PyPDFLoader`、`RecursiveCharacterTextSplitter` 把数据准备好，转成 LlamaIndex `Document`，再走 LlamaIndex 的 `SummaryIndex / VectorStoreIndex / KnowledgeGraphIndex`。
>
> 3. **共用底层模型**
>    通过 `llama_index.llms.langchain` 适配器，把 LangChain 的 `ChatModel` 直接喂给 LlamaIndex，反之亦然，避免重复配置 OpenAI Key。
>
> 4. **可观测性共享**
>    两者都能上报到 **LangSmith / Arize Phoenix**，统一追踪。
>
> **选型建议**：纯 RAG 优先 LlamaIndex；需要复杂 Agent + 多工具编排优先 LangChain（或 LangGraph）。

### [什么是 LangGraph ？](https://www.mianshiya.com/bank/1906189461556076546/question/1915349256003428353)

> **答案**：
>
> **LangGraph** 是 LangChain 团队 2024 年初推出的**有状态、可循环、图式 Agent 编排框架**，弥补了传统 LangChain Chain「只能 DAG、不能循环、无中央状态」的短板。
>
> **核心抽象**：
> - **State**（状态）：一个 `TypedDict`，所有节点共享、可读写，通常含 `messages`、`history`、`intermediate_steps` 等。
> - **Node**（节点）：接收 State、返回 State 增量的函数（可以是 LLM 调用、工具调用、纯逻辑）。
> - **Edge**（边）：固定边（A→B）或**条件边**（根据 State 决定下一步去哪个节点，从而支持循环与分支）。
> - **Checkpointer**：把 State 持久化（内存/SQLite/Postgres），支持中断、回放、人在回路。
>
> **典型图结构**：
> ```
> START → agent → (条件边)
>                  ├─ call_tool → agent   (循环)
>                  └─ END
> ```
>
> **与 Chain/LCEL 的区别**：
> | 维度 | LCEL Chain | LangGraph |
> |------|-----------|-----------|
> | 拓扑 | 有向无环 | 有环、有分支 |
> | 状态 | 无中央状态 | 显式 State 对象 |
> | 控制流 | 顺序 | 条件边 + 循环 |
> | 适用 | 单轮 RAG/链 | 多步 Agent、人在回路、子图 |
>
> **典型用法**：多 Agent 协作（Supervisor 模式）、Self-correcting RAG（CRAG）、长任务可恢复 Agent。

### [LangGraph 编排的原理是什么？](https://www.mianshiya.com/bank/1906189461556076546/question/1915664467716059138)

> **答案**：
>
> **LangGraph 编排原理**可以拆成四件事：**状态建模 + 节点注册 + 边路由 + 解释器循环**。
>
> 1. **状态建模**：开发者定义一个 `TypedDict`（例如含 `messages: Annotated[list, add_messages]`），`Annotated` 的 reducer 函数决定多个节点写入时如何合并（追加 or 覆盖）。
>
> 2. **节点注册**：每个 Node 是 `State -> dict` 的纯函数，只返回对 State 的**增量修改**（如 `{"messages": [new_msg]}`），由框架合并到全局 State。
>
> 3. **边路由**：
>    - `add_edge(A, B)`：固定跳转。
>    - `add_conditional_edges(A, router_fn, mapping)`：调 `router_fn(state)` 返回字符串，框架按 mapping 跳到对应节点，**这就是循环与分支的来源**。
>    - `add_edge(START, A)` / `add_edge(B, END)`：定义入口和出口。
>
> 4. **解释器循环（Pregel 风格）**：
>    框架内置一个事件循环：
>    ```
>    while current != END:
>        state = reducer_merge(state, node_outputs)
>        next_node = graph.step(current, state)
>        current = next_node
>        checkpoint(state)   # 通过 Checkpointer 持久化
>    ```
>    每次 step 前后会触发 Checkpointer 落盘，因此可以**断点续跑、回放、人工介入**。
>
> 5. **并发与分发**：节点可以并行（fan-out / fan-in），通过 reducer 合并结果，适合「多个 Agent 同时工作再汇总」。
>
> **与 LCEL 的本质差异**：LCEL 是声明式 DAG，没有「时间维度」；LangGraph 把时间维度引入，让「Agent 反复尝试、修正、确认」这类有循环的流程能自然落地。

### [​LangChain 和 LangGraph 有什么区别？](https://www.mianshiya.com/bank/1906189461556076546/question/1915671751515152385)

> **答案**：
>
> 两者都出自 LangChain 团队，但**定位不同、解决的问题不同**：
>
> | 维度 | **LangChain（Chain / LCEL）** | **LangGraph** |
> |------|------------------------------|---------------|
> | 抽象 | 顺序管道 `prompt | model | parser` | 状态图（Node + Edge + State） |
> | 拓扑 | **有向无环图 DAG** | **可循环、可分支** |
> | 状态 | 无中央状态，靠 Memory/局部变量 | 显式 State 对象，全节点共享 |
> | 控制流 | 顺序执行 | 条件边路由 + 循环 |
> | 适用场景 | 单轮 RAG、Prompt 链、固定流程 | 多步 Agent、自纠错、人在回路、多 Agent 协作 |
> | 持久化 | 弱（Memory 简单） | 强（Checkpointer，可中断/恢复） |
> | 上手成本 | 低 | 中 |
> | 失败案例 | ReAct 在 AgentExecutor 里硬塞循环，不优雅 | 天生为循环而生 |
>
> **一句话区分**：
> - 如果你的流程**没有循环、能从头跑到尾**，用 **LangChain / LCEL**。
> - 如果你的流程**需要 LLM 反复决策、可能回退、需要持久化和人在回路**，用 **LangGraph**。
>
> **演进趋势**：官方把 Agent 的未来押在 LangGraph 上；`AgentExecutor` 仍可用，但复杂 Agent 推荐迁移到 LangGraph。

### [解释LangChain框架中的Chain和Agent概念，并举例说明各自的应用场景](https://www.mianshiya.com/bank/1906189461556076546/question/1906192366640078850)

> **答案**：
>
> **Chain（链）** 是 LangChain 中把多个组件按**固定顺序**串起来的编排单元，类似 Unix 管道。**Agent** 则是把「**调用什么**」交给 LLM 自主决策的运行模式。
>
> | 维度 | Chain | Agent |
> |------|-------|-------|
> | 控制流 | 固定、预先定义 | 动态、由 LLM 在每步决定 |
> | 决策者 | 开发者 | LLM（基于 ReAct/Tool Calling） |
> | 是否循环 | 通常无 | 是（Thought → Action → Observation 循环） |
> | 可预测性 | 高 | 低（受模型影响） |
> | 成本 | 低、可控 | 高（多轮 LLM 调用） |
> | 调试 | 容易 | 难，需要 trace |
>
> **Chain 应用场景举例**：
> - **RAG 固定流程**：`retriever | prompt | model | output_parser`。
> - **翻译流水线**：`英文 → 翻译模型 → 校对模型 → 输出`。
> - **结构化抽取**：`Prompt | LLM | PydanticOutputParser`。
>
> **Agent 应用场景举例**：
> - **联网搜索助理**：根据问题决定是否调用搜索工具、调用几次。
> - **数据库 BI Agent**：写 SQL、执行、看结果、决定是否重写 SQL。
> - **多工具协作**：调日历 API + 邮件 API + 文档库，组合完成任务。
>
> **简言之**：流程**确定**用 Chain；流程**需要 LLM 来选**用 Agent。

### [请描述使用LangChain构建一个文档问答系统的关键技术组件及实现步骤](https://www.mianshiya.com/bank/1906189461556076546/question/1906309919110635521)

> **答案**：
>
> 用 LangChain 构建文档问答（RAG）系统的关键组件与步骤：
>
> **一、关键技术组件**
> 1. **Document Loader**：加载 PDF/Word/HTML/Markdown（如 `PyPDFLoader`、`DirectoryLoader`）。
> 2. **Text Splitter**：长文档切块，最常用 `RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50)`。
> 3. **Embedding 模型**：把文本转向量（如 `OpenAIEmbeddings`、`bge-large-zh`）。
> 4. **Vector Store**：存向量与原文（FAISS / Chroma / PGVector / Milvus）。
> 5. **Retriever**：检索接口（top-k 召回、MMR 多样化、多查询检索）。
> 6. **Prompt Template**：把检索结果拼进系统提示。
> 7. **ChatModel**：生成最终回答。
> 8. **Output Parser / Source attribution**：抽取答案 + 引用来源。
>
> **二、典型实现步骤（LCEL 写法）**
> ```python
> from langchain_community.vectorstores import FAISS
> from langchain_openai import ChatOpenAI, OpenAIEmbeddings
> from langchain_core.prompts import ChatPromptTemplate
> from langchain_core.output_parsers import StrOutputParser
> from langchain_core.runnables import RunnablePassthrough
> from langchain.text_splitter import RecursiveCharacterTextSplitter
> from langchain_community.document_loaders import PyPDFLoader
>
> # 1. 加载 + 切分
> docs = PyPDFLoader("book.pdf").load()
> chunks = RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50).split_documents(docs)
>
> # 2. 索引
> vectorstore = FAISS.from_documents(chunks, OpenAIEmbeddings())
> retriever = vectorstore.as_retriever(search_type="mmr", search_kwargs={"k": 4})
>
> # 3. Prompt + 链
> prompt = ChatPromptTemplate.from_template("根据以下资料回答问题：\n{context}\n\n问题：{question}")
> def format(docs): return "\n\n".join(d.page_content for d in docs)
>
> rag = (
>     {"context": retriever | format, "question": RunnablePassthrough()}
>     | prompt | ChatOpenAI(temperature=0) | StrOutputParser()
> )
> print(rag.invoke("什么是 RAG？"))
> ```
>
> **三、生产化要点**
> - 加 **Multi-Query Retriever / HyDE** 提升召回。
> - 加 **Reranker**（如 bge-reranker）做二次精排。
> - 加 **Source attribution**：让答案带 `source` 字段，提升可信度。
> - 加 **LangSmith 追踪** 调试 prompt 与召回质量。
> - 复杂场景（自纠错、多跳问答）升级到 **LangGraph + CRAG / Self-RAG**。

### [使用LangChain时，如何实现多路召回结果的动态权重分配？](https://www.mianshiya.com/bank/1906189461556076546/question/1906314200551301121)

> **答案**：
>
> 多路召回（如 BM25 + 向量 + 元数据过滤 + 多查询）的**动态权重分配**，常见四种做法，由浅入深：
>
> **1. 静态权重融合（baseline）**
> `EnsembleRetriever(retrievers=[bm25, vec], weights=[0.4, 0.6])`。简单但不能随问题变化。
>
> **2. RRF（Reciprocal Rank Fusion）**
> 不看分数只看排名：`score(d) = Σ 1/(k + rank_i(d))`，避免不同检索器分数量纲差异。LangChain 内置 `EnsembleRetriever` 默认走 RRF。
>
> **3. 路由（Router）按问题类型动态选权重**
> 用一个轻量分类器/LLM 把问题分类，再查表选权重：
> ```python
> def route(question):
>     if "代码" in question:  return {"bm25":0.6, "vec":0.4}
>     if "语义" in question:  return {"bm25":0.2, "vec":0.8}
>     return {"bm25":0.5, "vec":0.5}
> ```
> LangChain 可用 `RunnableBranch` 或 LangGraph 的 conditional_edges 实现。
>
> **4. 学习型权重 / 在线学习**
> - **离线训练**：用带标注的查询-文档对，训练一个小模型（如 logistic regression / XGBoost），输入 query 和候选特征，输出 relevance，再用 LambdaMART 重排。
> - **在线学习（RLHF / 反馈学习）**：把用户点击/点赞/「重新生成」作为反馈信号，用 Bandit 算法或 NN 更新权重。
> - **LLM-as-Judge**：让大模型对每个候选片段打分（如 0-10），用分数做加权融合，灵活性最高但成本高。
>
> **5. 工程要点**
> - 各路召回返回的 `score` 必须**归一化**（min-max 或 softmax）后再加权，避免量纲问题。
> - 用 **Reranker（Cross-Encoder，如 bge-reranker-large）** 做最后的精排，效果优于任何加权策略。
> - A/B 测试 + LangSmith 追踪，确保指标（Recall@k、MRR、人工满意度）真实提升。

### [使用LangChain实现RAG系统时，如何处理PDF文档中的表格数据召回问题？](https://www.mianshiya.com/bank/1906189461556076546/question/1906316700050853889)

> **答案**：
>
> PDF 表格召回是 RAG 的难点：表格被切散后语义断裂、向量化效果差。处理思路分**解析、切分、索引、召回**四层：
>
> **1. 高质量解析（最关键）**
> - 不用 `PyPDFLoader`（粗暴文本提取会把表格变乱行），改用：
>   - **`UnstructuredPDFLoader`**：能识别 layout，把表格保留为 `Table` 元素。
>   - **`PyMuPDFLoader`**（fitz）：保留位置信息。
>   - **专门方案**：`pdfplumber` / `camelot`（基于线框）/ `Table Transformer`（视觉模型）/ **Unstructured.io**（深度学习）/ **LayoutLMv3**。
>   - 商用：LlamaParse、Marker、Nougat（Meta，针对学术 PDF）。
>
> **2. 切分策略：表格不要切**
> - 表格作为一个独立 chunk 整体保留，不要被 `RecursiveCharacterTextSplitter` 切成两半。
> - 配合 `MarkdownHeaderTextSplitter`：把表格前后章节标题作为 metadata，提升过滤召回。
>
> **3. 多模态索引**
> - **文本索引**：表格序列化为 Markdown/HTML，向量化。
> - **表摘要索引**：让 LLM 给每个表生成一段自然语言摘要，对摘要做向量化，召回时再回填原始表（Table Summary Retrieval）。
> - **多向量检索**：用 `MultiVectorRetriever`，把「摘要向量 + 原表」存起来。
>
> **4. 召回阶段**
> - 加 **BM25 / 全文检索**，因为表格里的数字、专有名词对关键词检索更敏感。
> - 加 **元数据过滤**（年份、产品、地区）减少误召回。
> - 必要时上 **Reranker**（cross-encoder）。
>
> **5. 进阶：Text-to-SQL**
> 对结构化强的表格，把 PDF 解析后入库（SQLite/DuckDB），让 Agent 写 SQL 查表，比向量召回准确得多——这是处理「我想要 2023 年 Q3 销售额」这类问题的更优解。

### [如何在 LangChain 中自定义 Tool 工具？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796955499503618)

> **答案**：
>
> 在 LangChain 中自定义 Tool 有三种主流方式（0.1+）：
>
> **方式一：`@tool` 装饰器（最推荐）**
> ```python
> from langchain_core.tools import tool
>
> @tool
> def search_db(query: str, top_k: int = 5) -> str:
>     """在内部知识库中检索。适用于用户问公司产品/制度等内部信息时。
>     Args:
>         query: 检索关键词
>         top_k: 返回条数
>     """
>     return vectorstore.similarity_search(query, k=top_k)[0].page_content
> ```
> - 函数**类型注解** + **docstring** 是必须的，LLM 靠它们判断什么时候调用。
> - 自动从 type hints 生成 JSON Schema。
>
> **方式二：继承 `BaseTool`（需要更复杂逻辑时）**
> ```python
> from langchain_core.tools import BaseTool
> from pydantic import BaseModel, Field
>
> class SearchInput(BaseModel):
>     query: str = Field(description="检索关键词")
>     top_k: int = Field(default=5, description="返回条数")
>
> class SearchDBTool(BaseTool):
>     name: str = "search_db"
>     description: str = "在内部知识库中检索..."
>     args_schema: type[BaseModel] = SearchInput
>
>     def _run(self, query: str, top_k: int = 5) -> str:
>         return ...
>     async def _arun(self, query: str, top_k: int = 5) -> str:
>         ...  # 异步实现
> ```
>
> **方式三：`Tool.from_function`（轻量场景）**
> ```python
> from langchain.tools import Tool
> Tool.from_function(func=my_func, name="...", description="...")
> ```
>
> **最佳实践**
> 1. **description 写清楚**：什么时候用、什么时候不用（"only use this when user asks about internal policy"）。
> 2. **错误处理**：`try/except` 返回明确错误信息，让 Agent 能根据反馈调整下一步。
> 3. **参数尽量少**：能用默认值的就别让 LLM 决定。
> 4. **返回字符串或可序列化对象**：复杂对象 LLM 看不懂。
> 5. **副作用工具加确认**：发邮件、删数据等，在 LangGraph 里加「human-in-the-loop」节点。

### [LangChain 的 Memory 组件有什么作用？常见的 Memory 类型有哪些？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796955029741570)

> **答案**：
>
> **Memory 组件**用于在多轮对话中维护「上下文」，让 LLM 能记住之前说过什么。无状态 LLM 本身没有记忆，全靠 Memory 把历史拼接回 prompt。
>
> **常见 Memory 类型**：
>
> | 类型 | 原理 | 优点 | 缺点 |
> |------|------|------|------|
> | **ConversationBufferMemory** | 完整保留全部对话 | 简单、信息无损 | 长会话 token 爆炸 |
> | **ConversationBufferWindowMemory** | 只保留最近 N 轮 | token 受控 | 早期信息丢失 |
> | **ConversationSummaryMemory** | 用 LLM 持续把历史总结成摘要 | 长会话压缩好 | 有摘要成本、可能丢细节 |
> | **ConversationSummaryBufferMemory** | 短期用 buffer，超过阈值后总结 | 平衡保留与压缩 | 实现复杂 |
> | **ConversationEntityMemory** | 抽取实体（人名/地名/属性）单独存 | 对实体问答好 | 抽取依赖 LLM |
> | **VectorStoreRetrieverMemory** | 历史入向量库，按相关性召回 | 支持超长会话 | 召回可能漏 |
> | **Token Buffer Memory** | 按 token 数量截断 | 精准控制成本 | 略粗暴 |
>
> **使用示例**
> ```python
> from langchain.memory import ConversationBufferWindowMemory
> memory = ConversationBufferWindowMemory(k=5, return_messages=True)
> memory.save_context({"input": "你好"}, {"output": "你好，有什么可以帮你？"})
> ```
>
> **新一代实践（0.1+）**
> - LCEL 时代 `RunnableWithMessageHistory` 替代 Memory，直接管理 `Messages` 列表。
> - LangGraph 时代直接把 `messages: Annotated[list, add_messages]` 放进 State，配合 `trim_messages` 控制长度。
> - 长期记忆走 **向量库 + 实体库 + Summary** 三层结构（参考 MemGPT 思路）。

### [LangChain 中如何实现多轮对话的上下文管理？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796961639964673)

> **答案**：
>
> 多轮对话的上下文管理在 LangChain 中有三种代际方案：
>
> **1. 传统 Memory 方式（0.0）**
> ```python
> from langchain.chains import ConversationChain
> from langchain.memory import ConversationBufferWindowMemory
>
> chain = ConversationChain(
>     llm=llm,
>     memory=ConversationBufferWindowMemory(k=5)
> )
> chain.predict(input="你好")
> ```
> 特点：自动 save_context/load_memory，使用简单但和 Chain 强耦合。
>
> **2. RunnableWithMessageHistory（0.1+，LCEL 风格）**
> ```python
> from langchain_core.runnables.history import RunnableWithMessageHistory
> from langchain_core.chat_history import InMemoryChatMessageHistory
>
> prompt = ChatPromptTemplate.from_messages([
>     ("system", "你是助手"),
>     MessagesPlaceholder(variable_name="history"),
>     ("human", "{input}")
> ])
> chain = prompt | ChatOpenAI()
>
> store = {}  # session_id -> history
> chain_with_history = RunnableWithMessageHistory(
>     chain, lambda sid: store.setdefault(sid, InMemoryChatMessageHistory()),
>     input_messages_key="input", history_messages_key="history"
> )
> chain_with_history.invoke({"input": "我叫张三"},
>                           config={"configurable": {"session_id": "u1"}})
> ```
> 特点：Runnable 协议原生支持、可以接 Redis/DB 后端、与 LCEL 无缝组合。
>
> **3. LangGraph 状态管理（推荐用于复杂场景）**
> ```python
> from typing import Annotated, TypedDict
> from langgraph.graph import StateGraph, START
> from langgraph.graph.message import add_messages
>
> class State(TypedDict):
>     messages: Annotated[list, add_messages]
>
> def chatbot(state): return {"messages": [llm.invoke(state["messages"])]}
>
> g = StateGraph(State)
> g.add_node("chatbot", chatbot); g.add_edge(START, "chatbot")
> app = g.compile(checkpointer=MemorySaver())
> app.invoke({"messages": [("user","你好")]},
>            config={"configurable": {"thread_id": "u1"}})
> ```
> 特点：State 即记忆，天然支持多轮、人在回路、中断恢复。
>
> **长度控制策略**
> - `trim_messages(max_tokens=2000)`：按 token 截断。
> - 滑动窗口：保留最近 N 轮 + 永久 system prompt。
> - 摘要压缩：超阈值让 LLM 总结历史。
> - 检索式记忆：历史入向量库，按当前 query 召回相关片段。

### [LangChain 中的 Chain 是什么？有哪些常见类型？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796954488676354)

> **答案**：
>
> **Chain** 是 LangChain 中把多个步骤（Prompt → Model → Parser、Retriever → Prompt → Model 等）按顺序串起来的抽象。0.1+ 后统一用 **LCEL（LangChain Expression Language）** 表达。
>
> **常见 Chain 类型**：
>
> | 类型 | 作用 | 关键 API |
> |------|------|----------|
> | **LLM Chain** | Prompt → Model → Parser 的基础流水线 | `prompt \| model \| parser` |
> | **Retrieval QA Chain** | 检索 + 生成（RAG） | `retriever \| prompt \| model \| parser` |
> | **Conversation Chain** | 多轮对话 + Memory | LCEL + `RunnableWithMessageHistory` |
> | **Summarization Chain** | 长文档摘要（map-reduce） | `load_summarize_chain` |
> | **SQL Database Chain** | 自然语言 → SQL → 执行 | `create_sql_agent` |
> | **Router Chain** | 按问题路由到不同子链 | `RunnableBranch` / LangGraph 条件边 |
> | **Sequential Chain** | 多个 Chain 顺序执行，前者输出是后者输入 | LCEL 管道 |
> | **Transform Chain** | 加自定义预处理/后处理函数 | `RunnableLambda` |
>
> **LCEL 示例（推荐写法）**
> ```python
> from langchain_core.prompts import ChatPromptTemplate
> from langchain_openai import ChatOpenAI
> from langchain_core.output_parsers import StrOutputParser
>
> prompt = ChatPromptTemplate.from_template("用一句话解释{topic}")
> chain = prompt | ChatOpenAI(temperature=0) | StrOutputParser()
> chain.invoke({"topic": "量子计算"})
> ```
>
> **LCEL 优势**：天然支持流式（`stream`）、异步（`ainvoke`）、批量（`batch`）、可 traced、可 fallback。

### [LangChain 支持哪些向量数据库？如何选择？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796956300615681)

> **答案**：
>
> LangChain 通过 `VectorStore` 抽象对接几乎所有主流向量库，选择主要看**规模、性能、运维成本、是否需要过滤**：
>
> | 向量库 | 类型 | 适用场景 | 优点 | 缺点 |
> |--------|------|---------|------|------|
> | **FAISS** | 库（嵌入式） | 原型/单机 ≤ 千万级 | 极快、零运维 | 不能动态增删、不持久化、不分布式 |
> | **Chroma** | 嵌入式 DB | 中小项目、本地开发 | 简单、Python 友好 | 单机、规模有限 |
> | **Milvus** | 分布式 DB | 千万~亿级生产 | 高性能、分布式、多索引 | 部署复杂（需 etcd/MinIO） |
> | **Qdrant** | 分布式 DB | 中大型生产 | Rust 写、快、API 优雅、过滤强 | 生态略小于 Milvus |
> | **Weaviate** | 分布式 DB | 中大型、需要模块化（混合检索） | 自带向量化模块、混合检索 | 配置项多 |
> | **Pinecone** | SaaS | 不想运维 | 全托管、稳定 | 闭源、付费、数据出境 |
> | **pgvector** | PG 扩展 | 已有 PG、量级 < 1000 万 | 复用 PG、事务强 | 大规模性能弱于专用库 |
> | **Redis Stack** | 内存 | 高 QPS、短 TTL | 极快、有成熟生态 | 内存成本 |
> | **Elasticsearch** | 搜索引擎 | 已有 ES、需要全文+向量混合 | 混合检索强、BM25 优秀 | 资源占用大 |
>
> **选择建议**：
> - **原型 / Demo**：FAISS 或 Chroma，零成本。
> - **中型生产（百万级）**：pgvector（已有 PG）或 Qdrant。
> - **大规模生产（千万级以上）**：Milvus / Qdrant 集群。
> - **混合检索（BM25+向量）重要**：Elasticsearch / Weaviate / Qdrant。
> - **不想运维**：Pinecone / Zilliz Cloud。
> - **强一致 + 事务**：pgvector。
>
> **LangChain 集成方式统一**：
> ```python
> from langchain_community.vectorstores import FAISS, Chroma, Milvus, Qdrant
> vstore = Chroma.from_documents(chunks, embedding=embedder, persist_directory="./db")
> vstore.similarity_search("query", k=4)
> ```

### [LangChain 中如何处理多模态数据？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796958360018946)

> **答案**：
>
> LangChain 处理多模态（图像、音频、视频）数据的思路分三种：
>
> **1. 多模态 LLM 直连（推荐，0.1+ 原生支持）**
> OpenAI GPT-4o、Anthropic Claude 3.5、Gemini 等支持图像输入。LangChain 的 `HumanMessage(content=[{"type":"text",...},{"type":"image_url","image_url":{...}}])` 可直接传图：
> ```python
> from langchain_core.messages import HumanMessage
> msg = HumanMessage(content=[
>     {"type": "text", "text": "图中是什么？"},
>     {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + b64}},
> ])
> response = vision_llm.invoke([msg])
> ```
>
> **2. 多模态 Embedding + 向量检索（多模态 RAG）**
> - 图像：用 `CLIP` / `OpenCLIPEmbeddings` 把图与文映射到同一向量空间，存入支持多模态的向量库（如 LanceDB、Chroma + clip）。
> - 检索时：文查图、图查文、图查图都能做。
> - LangChain 提供 `MultiVectorRetriever` 把图像原件存起来，向量做检索键。
>
> **3. Pipeline 拆分（音频/视频/表格）**
> - **音频**：先 `Whisper`（或 `SpeechToTextLoader`）转文字 → 走文本 RAG。
> - **视频**：抽帧 → 每帧当图、配时间戳 → 多模态 LLM 理解。
> - **PDF 含图**：用 `UnstructuredPDFLoader` 把图作为单独 Document；或用 LayoutLMv3 解析。
>
> **典型多模态 RAG 架构**
> ```
> 图片文档
>    ├─ UnstructuredLoader → 文本块 + 图像块
>    ├─ 文本块 → 文本 Embedding → 向量库
>    └─ 图像块 → CLIP Embedding → 向量库（或描述化后入文本库）
> 检索 → 拼成 multimodal prompt → GPT-4o 生成答案
> ```
>
> **实践建议**
> - 能用 GPT-4o / Claude 3.5 Sonnet 直接读图，就别做复杂的图文分离 pipeline。
> - 大量图片做检索时，先用「LLM 给每张图生成 caption」转成文本，再走纯文本 RAG，性价比最高。
> - 多模态评估要单独做（图理解准确率、图文对齐率）。

### [如何在 LangChain 中实现函数调用 Function Calling？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796959769305089)

> **答案**：
>
> LangChain 实现 Function Calling 的**最现代**做法是直接用支持 tool calling 的模型（GPT-4o、Claude 3.5、Qwen 等）配合 `@tool` + `create_tool_calling_agent`：
>
> ```python
> from langchain_core.tools import tool
> from langchain_openai import ChatOpenAI
> from langchain.agents import create_tool_calling_agent, AgentExecutor
> from langchain_core.prompts import ChatPromptTemplate
>
> @tool
> def get_weather(city: str) -> str:
>     """获取指定城市的天气"""
>     return f"{city}今天晴，25°C"
>
> @tool
> def calc(expression: str) -> str:
>     """计算数学表达式"""
>     return str(eval(expression))
>
> tools = [get_weather, calc]
> llm = ChatOpenAI(model="gpt-4o", temperature=0)
>
> prompt = ChatPromptTemplate.from_messages([
>     ("system", "你是助手，必要时调用工具"),
>     ("human", "{input}"),
>     ("placeholder", "{agent_scratchpad}"),
> ])
> agent = create_tool_calling_agent(llm, tools, prompt)
> executor = AgentExecutor(agent=agent, tools=tools, verbose=True)
>
> executor.invoke({"input": "北京天气怎么样？再加 23+45"})
> ```
>
> **执行流程**
> 1. LLM 收到 prompt + tools 描述（自动转 JSON Schema）。
> 2. LLM 输出 `tool_calls`（结构化 JSON，不需要 OutputParser 解析自然语言）。
> 3. `AgentExecutor` 调用对应 tool，把结果包成 `ToolMessage` 拼回 `agent_scratchpad`。
> 4. 重复直到 LLM 不再发 `tool_calls`，给出最终答案。
>
> **与「老式 ReAct」的区别**
> | 方式 | 模型输出 | 解析 | 可靠性 |
> |------|---------|------|--------|
> | 老式 ReAct | 自然语言 Thought/Action/Observation | 正则解析 | 易错 |
> | Tool Calling（推荐） | 原生结构化 JSON | 直接读 | 高 |
>
> **进阶**
> - **并行调用**：GPT-4o / Claude 3.5 支持一轮里发多个 tool_calls，框架会并发执行。
> - **流式**：用 `astream_events` 可以流式看到「正在调哪个工具」。
> - **结构化输出**：`llm.with_structured_output(PydanticModel)` 等价于「单 tool 强制调用」，适合数据抽取。

### [LangChain 有哪些常见的性能瓶颈？如何优化？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796961883234305)

> **答案**：
>
> LangChain 应用的常见性能瓶颈与优化：
>
> **1. LLM 调用延迟（最大瓶颈）**
> - 现象：单次 GPT-4 调用 1~5 秒，多步 Agent 10+ 秒。
> - 优化：
>   - **流式输出** `chain.stream()`，让用户看到逐 token，体感快。
>   - **小模型替代**：分类/抽取等简单任务用 GPT-4o-mini / Haiku，复杂任务才用大模型。
>   - **缓存**：`set_llm_cache(SQLiteCache(...))`，对相同 prompt 直接命中。
>   - **并发**：`chain.batch([...])` 或 `asyncio.gather`，充分利用 token 并行。
>   - **降温度**：低 temperature 减少重试。
>
> **2. Token 成本**
> - 优化：
>   - **Prompt 精简**：去掉冗长 few-shot、压缩历史（用 summary memory）。
>   - **召回控制**：检索 top_k 不要太大，3~5 通常够。
>   - **小模型**：能用 mini 就别用 max。
>   - **本地 Embedding**：用 `bge`、`e5` 替代 OpenAI Embedding，省钱且对中文更好。
>
> **3. 检索召回质量**
> - 优化：
>   - **Reranker**（cross-encoder，如 bge-reranker）二次精排。
>   - **多路召回**：向量 + BM25 + 元数据。
>   - **HyDE / Multi-query**：扩展查询。
>   - **Chunk 大小调参**：500~1000 token、overlap 10%。
>
> **4. 序列化 / 反射化开销**
> - Pydantic 校验、回调链过长会拖慢；生产前关掉开发期的 verbose 日志。
>
> **5. Agent 死循环 / 过多轮**
> - 必设 `max_iterations=5~10`，否则一旦模型卡住会烧钱。
> - 复杂 Agent 用 LangGraph 加显式终止条件。
>
> **6. 向量库性能**
> - HNSW 参数（efSearch、M）调优。
> - 大规模上 Milvus / Qdrant 分布式；别用 FAISS 跑生产。
> - 频繁更新场景用支持 upsert 的库（Chroma / Qdrant）。
>
> **7. 可观测性开销**
> - LangSmith 全采样会显著拖慢请求；生产环境用 1% 采样。
>
> **8. 网络抖动 / 限流**
> - 接入方加重试 + 指数退避 + 多 Key 轮询。
> - 用 `langchain_core` 的 `with_retry`。

### [LangChain 和 LlamaIndex 有什么区别？各自适合什么场景？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796957315637250)

> **答案**：
>
> 两者都是 LLM 应用框架，但**定位、强项、生态**差异明显：
>
> | 维度 | **LangChain** | **LlamaIndex** |
> |------|---------------|----------------|
> | 核心定位 | **通用 LLM 应用编排框架** | **数据/文档中心的应用框架** |
> | 设计哲学 | 「胶水」：组件丰富，可拼一切 | 「索引」：把数据接入做到极致 |
> | 强项 | Agent、Tools、Memory、多场景 | RAG、文档解析、索引结构、查询引擎 |
> | 抽象层级 | Chain / LCEL / LangGraph | Reader / Index / Query Engine / Agent |
> | 文档处理 | 通用 Loader + Splitter | **更专业**：LlamaParse、层级索引、树索引、关键词表索引 |
> | 检索 | Retriever（够用） | **更深**：SubQuestion、Recursive、Router、Sub-Query |
> | Agent | LangGraph 表现力强 | Worker / OpenAI Agent（够用） |
> | 生态 | 最大 | 较大，集中在数据/RAG |
> | 学习曲线 | 偏陡（概念多） | 偏缓（聚焦 RAG） |
>
> **适用场景**
> - **LangChain 适合**：需要复杂 Agent、多工具编排、与各种 SaaS 集成、自定义工作流。
> - **LlamaIndex 适合**：纯 RAG、文档问答、知识库检索、需要高级索引（树/图/关键词）、文档结构复杂（PDF 表格、长文档层级）。
>
> **实际选择**
> - 简单 RAG：两者都能做，**LlamaIndex 起步更快**。
> - 复杂 Agent / 自定义流程：**LangChain + LangGraph**。
> - **混合用**：LlamaIndex 做数据/检索，LangChain 做 Agent 编排（见「LlamaIndex 如何与 LangChain 结合」一题）。

### [LangChain 中的 DocumentLoader 有哪些类型？如何选择？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796957584072706)

> **答案**：
>
> Document Loader 用于把不同来源的数据加载成统一的 `Document`（`page_content` + `metadata`）。LangChain 内置 100+ Loader，主要分以下几类：
>
> **1. 文件类**
> - `PyPDFLoader` / `PyMuPDFLoader` / `PDFMinerLoader`：PDF（效果差异大，PyMuPDF 最稳）
> - `Docx2txtLoader` / `UnstructuredWordDocumentLoader`：Word
> - `CSVLoader` / `UnstructuredCSVLoader`：CSV（按行成 Document）
> - `JSONLoader`：JSON（按 jq 语法定位字段）
> - `TextLoader` / `MarkdownLoader`：纯文本
>
> **2. 网页类**
> - `WebBaseLoader`：通用 URL，用 BeautifulSoup 解析
> - `SeleniumURLLoader` / `PlaywrightURLLoader`：动态渲染页面
> - `SitemapLoader`：从 sitemap.xml 批量抓
> - `AsyncHtmlLoader`：异步抓取加速
>
> **3. 数据库 / 数据平台**
> - `SQLDatabaseLoader`：SQL 查询结果
> - `SnowflakeLoader`、`BigQueryLoader`
> - `NotionDirectoryLoader`、`AirbyteJSONLoader`
>
> **4. 云存储**
> - `S3FileLoader` / `S3DirLoader`
> - `GoogleDriveLoader`
> - `GitHubIssuesLoader` / `GitLoader`
>
> **5. 协作工具**
> - `ConfluenceLoader`、`SlackDirectoryLoader`
> - `JiraLoader`、`TrelloLoader`
>
> **6. 多模态**
> - `UnstructuredPDFLoader`：能识别表格、图片
> - `AmazonTextractPDFLoader`、`GoogleVisionPDFLoader`：云 OCR
> - `YTAudioLoader` + `OpenAIWhisperParser`：YouTube → 文本
>
> **选择原则**
> 1. **简单 PDF（纯文本）**：`PyMuPDFLoader`（最快）。
> 2. **复杂 PDF（表格、排版）**：`UnstructuredPDFLoader` 或商业方案 `LlamaParse`。
> 3. **网页**：静态用 `WebBaseLoader`，动态用 `PlaywrightURLLoader`。
> 4. **大批量**：用 `DirectoryLoader` + 并发 + `glob`。
> 5. **结构化数据**：能 SQL 就 SQL，不要先转 Document。
> 6. **多源混合**：考虑直接用 `Unstructured` 统一处理，layout 识别效果最好。

### [什么是 LangChain 中的 Agent？它和 Chain 有什么区别？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796954765500417)

> **答案**：
>
> 这一题与「什么是 LangChain Agent」核心一致，重点补充**与 Chain 的对比**：
>
> **Agent**：让 LLM 自主决定调用什么工具、什么时候结束。运行时**循环**：Thought → Action → Observation，直到 Final Answer。
>
> **与 Chain 的核心区别**
> | 维度 | Chain | Agent |
> |------|-------|-------|
> | 决策者 | 开发者写死流程 | LLM 实时决策 |
> | 控制流 | 顺序、无循环 | 循环、可分支 |
> | 失败模式 | 流程清晰，错误好定位 | 错误链长、易陷入循环 |
> | 成本 | 单次 LLM 调用 | 多次 LLM 调用 + 多次工具调用 |
> | 可控性 | 高 | 低（需 max_iterations / human-in-loop 兜底） |
> | 适用 | 已知流程 | 探索性、动态任务 |
>
> **举例对比**
> - 任务：查"上海明天天气"
>   - Chain：固定调 `WeatherTool` → 返回。
>   - Agent：LLM 看问题 → 决定调 `WeatherTool("上海")` → 看结果 → 决定输出"上海明天…"。
> - 任务：用户问"研究一下 LangGraph"，**不确定**用哪个工具
>   - Chain：没法做（流程未知）。
>   - Agent：LLM 选搜索工具 → 看摘要 → 选抓网页工具 → 综合输出。
>
> **关键判断**：流程**确定**用 Chain；流程**需要 LLM 选择**用 Agent；流程**复杂到有循环/分支/多 Agent 协作**用 **LangGraph**。

### [LangChain 中如何实现对话历史的管理和持久化？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796959194685441)

> **答案**：
>
> 对话历史的持久化在 LangChain 中有三种代际方案：
>
> **1. ChatMessageHistory（推荐，0.1+）**
> LangChain 提供统一的 `BaseChatMessageHistory` 抽象，对接多种后端：
> ```python
> from langchain_community.chat_message_histories import (
>     RedisChatMessageHistory, SQLChatMessageHistory, PostgresChatMessageHistory,
>     DynamoDBChatMessageHistory, FirestoreChatMessageHistory,
>     StreamlitChatMessageHistory, ChatMessageHistory  # 内存
> )
>
> history = RedisChatMessageHistory("user_123", url="redis://localhost:6379")
> history.add_user_message("你好")
> history.add_ai_message("你好，有什么可以帮你？")
> ```
>
> **2. RunnableWithMessageHistory 自动注入**
> ```python
> from langchain_core.runnables.history import RunnableWithMessageHistory
>
> store = {}  # session_id -> ChatMessageHistory
> chain_with_history = RunnableWithMessageHistory(
>     chain,
>     lambda sid: store.get(sid) or RedisChatMessageHistory(sid),
>     input_messages_key="input",
>     history_messages_key="history",
> )
> chain_with_history.invoke({"input": "你好"},
>     config={"configurable": {"session_id": "user_123"}})
> ```
> 特点：调用方只管传 `session_id`，框架自动 save/load。
>
> **3. LangGraph Checkpointer（适合复杂 Agent）**
> ```python
> from langgraph.checkpoint.postgres import PostgresSaver
> graph = builder.compile(checkpointer=PostgresSaver.from_conn_string(...))
> graph.invoke({"messages": [...]},
>              config={"configurable": {"thread_id": "user_123"}})
> ```
> 特点：不仅存消息，还存整个 State（含中间步骤），可中断/恢复/回放。
>
> **选型建议**
> - **Demo / 单机**：`ChatMessageHistory`（内存）或 `SQLChatMessageHistory`（SQLite）。
> - **Web 服务**：`RedisChatMessageHistory`（快）或 Postgres（持久）。
> - **Serverless**：`DynamoDBChatMessageHistory` / Firestore。
> - **复杂 Agent / 多轮任务**：LangGraph + Postgres Checkpointer。
>
> **额外建议**
> - 历史不能无限存：用 `ConversationSummaryMemory` 或 `trim_messages` 定期压缩。
> - 长期记忆走「**Summary + 向量库 + 实体库**」三层结构（MemGPT 思路）。
> - 敏感数据加密、TTL 过期、GDPR 删除接口都要在 DB 层准备好。

### [LangChain 中的 Callback 回调机制是什么？有什么用？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796959496675330)

> **答案**：
>
> **Callback 机制**是 LangChain 的「**钩子**」：在链/模型/工具执行的关键节点（start/end/error/token/stream）触发用户自定义函数，用于**日志、追踪、监控、流式输出、缓存命中**等。
>
> **核心接口**：`BaseCallbackHandler`，常用钩子：
> - `on_llm_start` / `on_llm_end` / `on_llm_new_token`
> - `on_chat_model_start`
> - `on_chain_start` / `on_chain_end`
> - `on_tool_start` / `on_tool_end`
> - `on_agent_action` / `on_agent_finish`
> - `on_error`
>
> **典型用途**
> 1. **LangSmith 追踪**：`LangSmithCallbackHandler` 自动上报全链路 trace。
> 2. **Langfuse / Phoenix / Arize**：第三方可观测性平台都用 Callback 接入。
> 3. **流式输出**：通过 `on_llm_new_token` 把 token 推到前端。
> 4. **自定义日志**：写入文件、发到 Kafka、推送告警。
> 5. **审计**：记录每次工具调用、模型调用，满足合规。
> 6. **限流 / 熔断**：在 `on_llm_start` 检查配额，超出抛错。
>
> **使用方式**
> ```python
> from langchain_core.callbacks import BaseCallbackHandler
>
> class MyHandler(BaseCallbackHandler):
>     def on_llm_start(self, serialized, prompts, **kwargs):
>         print("LLM 调用开始", prompts)
>     def on_tool_end(self, output, **kwargs):
>         print("工具返回", output)
>
> chain.invoke({"input":"..."}, config={"callbacks":[MyHandler()]})
> # 或全局
> llm.callbacks = [MyHandler()]
> ```
>
> **AsyncCallbacks**：异步版本 `on_llm_start_async` 等，配合 `ainvoke` 使用。
>
> **进阶**
> - `CallbackManager`：管理多个 handler。
> - 与 LCEL 集成：所有 Runnable 都接受 `config={"callbacks":[...]}`。
> - 生产环境采样：不要全量上报，否则严重影响性能。

### [LangChain 中的 TextSplitter 文档切分策略有哪些？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796957865091074)

> **答案**：
>
> TextSplitter 决定了文档切块质量，直接影响 RAG 召回。常见策略：
>
> | Splitter | 切分依据 | 适用场景 |
> |----------|---------|---------|
> | **CharacterTextSplitter** | 单字符（如 `\n\n`） | 简单、纯文本 |
> | **RecursiveCharacterTextSplitter** | 多分隔符递归（`["\n\n","\n"," ",""]`） | **最通用**，推荐默认 |
> | **TokenTextSplitter** | token 数 | 精控 token、给 OpenAI |
> | **MarkdownHeaderTextSplitter** | Markdown 标题（#, ##） | 文档/手册，按章节 |
> | **HTMLHeaderTextSplitter** | HTML 标签 | 网页 |
> | **PythonCodeTextSplitter** | 函数/类边界 | 代码 |
> | **LatexTextSplitter** | LaTeX 章节 | 学术论文 |
> | **SentenceTransformersTokenTextSplitter** | 句子 + token | 与 Embedding 模型对齐 |
>
> **关键参数**
> - `chunk_size`：单块大小，通常 300~1000 token。RAG 偏 500；摘要偏 1000~2000。
> - `chunk_overlap`：相邻块重叠，10%~20%，避免关键信息被切散。
> - `separators`：自定义分隔符优先级，中文场景应加 `["。","！","？","，"]`。
>
> **实战经验**
> 1. **中文优先 Recursive + 自定义分隔符**：默认分隔符对中文不友好。
> 2. **结构化文档用 Header Splitter**：先按 Markdown 标题切，再对每块用 Recursive 二次切，保留 metadata（章节路径），过滤召回效果好。
> 3. **表格/代码不要切**：作为整体 chunk。
> 4. **chunk_size 与 Embedding 模型 max_length 匹配**：BGE 512、OpenAI 8191、E5 512。
> 5. **小 chunk + 父文档检索（Parent Document Retriever）**：检索用小 chunk（精准），生成用大 parent（上下文全）。
> 6. **语义切分（SemanticChunker）**：基于 embedding 相似度切分，质量更高但慢，适合高质量 RAG。

### [在生产环境中使用 LangChain 需要注意哪些问题？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796958603288577)

> **答案**：
>
> 生产环境上线 LangChain 应用需要重点关注以下问题：
>
> **1. 可观测性（最重要）**
> - 必须接 **LangSmith / Langfuse / Arize Phoenix**，全链路追踪每次 LLM 调用、工具调用、检索召回。
> - 监控指标：P50/P95 延迟、token 消耗、错误率、召回命中率、用户满意度。
> - 采样率：生产 1%~10%，开发 100%。
>
> **2. 成本控制**
> - 模型分级：分类/抽取用 mini，复杂推理才用 max。
> - 缓存：`set_llm_cache` 缓存确定性问题。
> - 召回 top_k 控制在 3~5。
> - 监控每日 token 消耗，设预算告警。
>
> **3. 可靠性 / 错误处理**
> - LLM 调用加 `with_retry`（指数退避）。
> - 工具失败要有 fallback 答案，不能直接抛错给用户。
> - Agent 必须 `max_iterations` 兜底。
> - 超时控制：LLM、Tool、Retriever 都设 timeout。
> - 熔断：错误率超阈值自动降级（如返回缓存结果）。
>
> **4. 安全**
> - **Prompt Injection**：用户输入 + 检索内容都可能注入，做输入过滤、输出审查。
> - **敏感信息**：日志中 mask 手机号/身份证/API Key。
> - **工具权限**：危险工具（发邮件、删数据）必须人在回路确认。
> - **数据隔离**：多租户场景，向量库按 tenant_id 过滤。
>
> **5. 评估与回归**
> - 建立 **Golden Set**：50~200 个典型问题 + 期望答案。
> - 上线前跑回归：用 `LangSmith Datasets` 或 RAGAS（faithfulness、answer_relevancy、context_precision）。
> - 持续收集 bad case 反哺。
>
> **6. 性能**
> - 流式输出，提升体感。
> - 并发：`chain.batch` + asyncio。
> - 向量库选型匹配规模。
> - 热数据缓存到 Redis。
>
> **7. 版本管理**
> - Prompt 版本化（LangSmith Prompt Hub）。
> - 模型版本固定（不要默认 latest）。
> - 灰度发布：5% → 20% → 100%。
>
> **8. 合规与隐私**
> - 数据出境：用户数据能否调 OpenAI？
> - 内容审核：输出涉黄涉政要拦截。
> - 审计日志：保留 90+ 天。
>
> **9. 团队协作**
> - Prompt/Chain 评审流程。
> - 文档化架构与决策。
> - 离线评测 + 在线 A/B。

### [如何处理 LangChain 应用中的错误和异常？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796961388306433)

> **答案**：
>
> LangChain 应用的错误和异常处理要分层做：
>
> **1. 网络层（最常见）**
> - LLM API 限流（429）、超时（ReadTimeout）、瞬时不可用（5xx）。
> - 处理：`with_retry(retry_if_exception_type=(...))` + 指数退避 + 多 Key 轮询 + fallback 模型。
> ```python
> from langchain_core.runnables import RunnableLambda
> llm_with_retry = llm.with_retry(
>     stop_after_attempt=3,
>     retry_if_exception_type((TimeoutError, RateLimitError)),
> )
> ```
>
> **2. LLM 输出层**
> - 输出不符合 schema、JSON 解析失败、tool_call 格式错。
> - 处理：
>   - 用 `with_structured_output` 强制 JSON Schema。
>   - OutputParser 加 `OutputFixingParser`（让 LLM 修复自己）。
>   - 失败重试 + 失败 fallback（返回默认 / 走小模型 / 转人工）。
>
> **3. 工具层**
> - 工具函数抛错（参数错、外部 API 挂）。
> - 处理：
>   - 工具内部 `try/except`，**返回明确错误信息给 LLM**（让 Agent 自己调整）。
>   - 工具加 timeout，避免卡死。
>   - 危险工具失败立即停止 Agent。
>
> ```python
> @tool
> def query_db(sql: str) -> str:
>     """查询数据库"""
>     try:
>         return db.run(sql)
>     except Exception as e:
>         return f"工具执行失败：{e}。请改写 SQL 重试。"
> ```
>
> **4. 检索层**
> - 召回为空 / 质量差。
> - 处理：
>   - 召回为空时 fallback 到 FAQ 或通用回答。
>   - 阈值过滤：score < threshold 视为无召回。
>   - 多路召回互备。
>
> **5. Agent 层**
> - 死循环 / 步数过多 / 卡在某工具。
> - 处理：
>   - `max_iterations=10` 兜底。
>   - `early_stopping_method="generate"` 强制最终回答。
>   - 检测重复 Action，主动跳出。
>
> **6. 全局兜底**
> - 顶层 `try/except` 捕获未处理异常，返回友好兜底响应（"系统繁忙，请稍后再试"）。
> - 上报 Sentry / LangSmith，落库分析。
>
> **7. 用户侧**
> - 流式输出失败时降级为非流式。
> - 前端展示「重试」按钮。
> - 错误信息对用户友好，对开发者详细（trace_id 关联）。
>
> **经验**：**永远假设 LLM 会出错**，每一层都要有 fallback。

### [LangChain 中的 LCEL 表达式语言是什么？有什么优势？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796960247455745)

> **答案**：
>
> **LCEL（LangChain Expression Language）** 是 LangChain 0.1 引入的声明式 DSL，用 `|` 把多个 Runnable 串成链，**底层是 `RunnableSequence`**。
>
> **核心语法**
> ```python
> chain = prompt | model | output_parser
> ```
> 等价于：`output_parser.invoke(model.invoke(prompt.invoke(input)))`。
>
> **优势**
>
> 1. **统一协议**：所有组件都实现 `Runnable` 接口（`invoke / batch / stream / astream / ainvoke`），天然可拼。
>
> 2. **流式优先**：
>    ```python
>    for chunk in chain.stream({"topic":"RAG"}):
>        print(chunk, end="", flush=True)
>    ```
>    - 不需要改代码，自动端到端流式（prompt 流式 → model 流式 → parser 流式）。
>
> 3. **异步原生**：
>    ```python
>    await chain.ainvoke(...)
>    await chain.abatch([...])   # 并发批量
>    ```
>
> 4. **批量并发**：`chain.batch([q1,q2,q3])` 自动并发，省时。
>
> 5. **可追踪**：每一步自动上报 LangSmith，trace 清晰。
>
> 6. **可组合**：
>    - `RunnableParallel`：并行执行多路，结果合并。
>    - `RunnablePassthrough`：透传输入。
>    - `RunnableLambda`：包任意函数。
>    - `RunnableBranch`：条件路由。
>    - `itemgetter` / 字典：灵活数据流。
>
> 7. **Fallback**：`chain.with_fallbacks([chain2])`，主链失败自动切。
>
> 8. **重试**：`chain.with_retry(stop_after_attempt=3)`。
>
> 9. **绑定工具/参数**：`model.bind_tools(tools)` / `model.bind(temperature=0)`。
>
> **示例：RAG 全链**
> ```python
> from langchain_core.runnables import RunnablePassthrough, RunnableParallel
>
> rag = RunnableParallel(
>     {"context": retriever | format, "question": RunnablePassthrough()}
> ) | prompt | model | StrOutputParser()
> ```
>
> **与旧 `LLMChain` 对比**
> - `LLMChain(llm=..., prompt=...)`：黑盒、不天然支持流式、扩展性差。
> - LCEL：白盒、流式原生、可组合性强。**官方推荐全面迁移到 LCEL**。

### [LangChain 中如何实现条件分支和动态路由？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796960616554497)

> **答案**：
>
> LangChain 中条件分支和动态路由有三种典型写法：
>
> **1. RunnableBranch（LCEL 内置）**
> ```python
> from langchain_core.runnables import RunnableBranch
>
> branch = RunnableBranch(
>     (lambda x: "代码" in x["topic"], code_chain),
>     (lambda x: "翻译" in x["topic"], translate_chain),
>     default_chain,  # 默认
> )
> result = branch.invoke({"topic": "代码生成"})
> ```
>
> **2. 自定义 RunnableLambda + 字典派发（更灵活）**
> ```python
> def router(state):
>     if "代码" in state["topic"]:  return "code"
>     if "翻译" in state["topic"]:  return "translate"
>     return "default"
>
> chains = {"code": code_chain, "translate": translate_chain, "default": default_chain}
>
> route = (
>     {"topic": RunnablePassthrough()}
>     | RunnableLambda(router)
> )
> # 拿到 key 后再调用对应 chain
> ```
> 或用 LCEL 的「Runnable 编排」：
> ```python
> from langchain_core.runnables import RunnableLambda
> def dispatcher(x):
>     return chains[router(x)].invoke(x)
> chain = RunnableLambda(dispatcher)
> ```
>
> **3. LangGraph 条件边（最强大，推荐复杂场景）**
> ```python
> from langgraph.graph import StateGraph, END
>
> def route(state):
>     if "代码" in state["question"]: return "code_node"
>     if "翻译" in state["question"]: return "translate_node"
>     return "default_node"
>
> g = StateGraph(State)
> g.add_node("code_node", code_chain); g.add_node("translate_node", translate_chain)
> g.add_node("default_node", default_chain)
> g.set_conditional_entry_point(route)   # 或 add_conditional_edges
> g.add_edge("code_node", END); g.add_edge("translate_node", END); g.add_edge("default_node", END)
> app = g.compile()
> ```
>
> **4. LLM Router（动态分类）**
> 用 LLM 做语义路由，提示词约束输出 `{topic: "code"|"translate"|"default"}`，再走上面任一方式。
>
> **选择建议**
> - 简单 if/else 路由 → `RunnableBranch`。
> - 多链 + 复杂条件 → `RunnableLambda` + dict。
> - 需要循环 / 多步 / 多 Agent → **LangGraph**。
> - 路由本身需要语义理解 → LLM Router + one of above。

### [如何保证 LangChain 应用的输出质量和一致性？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796962126503937)

> **答案**：
>
> 保证 LangChain 应用输出质量与一致性的实践框架：
>
> **1. Prompt 工程层**
> - **结构化 Prompt**：清晰的角色、任务、约束、输出格式、Few-shot。
> - **变量固定**：用 `ChatPromptTemplate` 而非 f-string，避免注入。
> - **温度控制**：抽取/分类/JSON 任务 `temperature=0`；创意写作允许 0.5+。
> - **JSON 模式**：用 `with_structured_output(PydanticModel)` 或 `response_format={"type":"json_object"}`。
>
> **2. 结构化输出层**
> ```python
> from pydantic import BaseModel, Field
>
> class Answer(BaseModel):
>     summary: str
>     confidence: float = Field(ge=0, le=1)
>     sources: list[str]
>
> structured_llm = llm.with_structured_output(Answer)
> result = structured_llm.invoke("...")  # 直接拿到 Pydantic 对象
> ```
> - 强制 schema，杜绝格式漂移。
>
> **3. OutputParser + 自动修复**
> - `PydanticOutputParser` 解析失败时，用 `OutputFixingParser` 让 LLM 修复自己的输出。
> - `RetryOutputParser` 把解析错误回喂给 LLM 重生成。
>
> **4. Self-Check / Self-Critique**
> - 让 LLM 自己检查答案：生成 → 检查 → 修正。
> - LangGraph 实现自然的「Generator → Critic → Reviser」循环。
>
> **5. 多路生成 + 投票（Self-Consistency）**
> - 同一 prompt 多次采样（temperature=0.7）→ 投票选最优。
> - 适合数学/逻辑题。
>
> **6. 检索质量保障（RAG）**
> - 高质量 Reranker（cross-encoder）。
> - Faithfulness 检查：让 LLM 验证答案是否真的来自检索内容。
> - 召回阈值过滤：低质 recall 不进 prompt。
>
> **7. 离线评估**
> - **Golden Set**：标注 50~200 题期望答案。
> - **RAGAS** 指标：faithfulness、answer_relevancy、context_precision、context_recall。
> - **LLM-as-Judge**：用 GPT-4 给答案打分。
> - 每次改 prompt/模型/检索都跑回归。
>
> **8. 在线 A/B + 反馈闭环**
> - 灰度新 prompt vs 旧 prompt。
> - 收集用户「👍/👎/重新生成」反馈。
> - 用反馈数据做 Few-shot 或微调。
>
> **9. Prompt 版本管理**
> - 用 LangSmith Prompt Hub 把 prompt 当代码版本管理。
> - 每次发布绑定模型版本 + 参数 + 评测分数。
>
> **10. 监控**
> - 监控输出长度分布、JSON 解析失败率、用户负反馈率。
> - 异常告警（突然变长/变短/违规词激增）。
>
> **核心理念**：把 LLM 当成不可靠组件，**用工程手段（schema、retry、self-check、评估、监控）把它焊牢**。

### [LangChain 中的 Retriever 检索器有哪些类型？各有什么特点？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796961153425410)

> **答案**：
>
> Retriever 是 LangChain 检索层的统一抽象（`Runnable`），常见类型：
>
> | Retriever | 原理 | 适用场景 |
> |-----------|------|---------|
> | **VectorStoreRetriever** | 向量相似度（cosine）召回 | 语义匹配，最常用 |
> | **BM25Retriever** | 关键词倒排索引 + BM25 | 关键词/数字/术语精准匹配 |
> | **EnsembleRetriever** | 多路融合（向量+BM25，默认 RRF） | 综合召回质量最高 |
> | **MultiQueryRetriever** | LLM 生成多个 query 并行召回 | 提升 recall，对抗 query 表述差异 |
> | **ContextualCompressionRetriever** | 召回后再用 LLM/Embedding 压缩 | 减噪、提升 precision |
> | **ParentDocumentRetriever** | 小块召回、返回大父文档 | 小块精准 + 大块上下文 |
> | **SelfQueryRetriever** | LLM 拆出 metadata 过滤条件 | "2023 年关于 X 的文档" |
> | **TimeWeightedVectorRetriever** | 时间衰减加权 | 偏新内容（新闻、对话） |
> | **MultiVectorRetriever** | 一个 doc 多个向量（summary/doc/chunk） | 表格/长文档，summary 召回 |
> | **WebResearchRetriever** | 联网搜索 + 索引 | 时效性问题 |
> | **LlamaIndex / WeaviateHybridSearchRetriever** | 混合检索 | 既需要语义又需要关键词 |
>
> **特点对比**
> - **Vector**：语义强，关键词弱。
> - **BM25**：关键词强，语义弱，但对专有名词、数字更准。
> - **Ensemble（Vector+BM25, RRF）**：综合最好，**生产首选 baseline**。
> - **MultiQuery / HyDE**：成本高但 recall 高，适合高质量 RAG。
> - **Parent Document**：解决「chunk 太短没上下文」。
> - **Self-Query**：解决「带元数据过滤的自然语言查询」。
>
> **进阶**
> - 召回后加 **Reranker**（cross-encoder）几乎必做。
> - top_k 不要太大（3~5），交给 Reranker 排序。
> - 评估召回质量：Recall@k、MRR、nDCG。
>
> **示例**
> ```python
> from langchain.retrievers import EnsembleRetriever, MultiQueryRetriever
> ensemble = EnsembleRetriever(
>     retrievers=[bm25, vector], weights=[0.4, 0.6]
> )
> ```

### [LangChain 中的 Prompt 模板有什么作用？如何使用？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796956015403010)

> **答案**：
>
> **PromptTemplate** 用于把可复用提示词模板化，支持变量插值、Few-shot、消息角色，避免每次拼接字符串。
>
> **主要类型**
>
> 1. **PromptTemplate**（普通文本补全）
> ```python
> from langchain_core.prompts import PromptTemplate
> p = PromptTemplate.from_template("用一句话解释{topic}")
> p.format(topic="量子计算")
> ```
>
> 2. **ChatPromptTemplate**（对话消息列表，最常用）
> ```python
> from langchain_core.prompts import ChatPromptTemplate
> p = ChatPromptTemplate.from_messages([
>     ("system", "你是{role}，用{style}的语气回答"),
>     ("human", "{question}"),
> ])
> p.invoke({"role":"数学老师","style":"幽默","question":"什么是导数？"})
> ```
>
> 3. **FewShotChatMessagePromptTemplate**（带 few-shot）
> ```python
> from langchain_core.prompts import FewShotChatMessagePromptTemplate
> examples = [{"q":"1+1","a":"2"}, {"q":"2+3","a":"5"}]
> example_prompt = ChatPromptTemplate.from_messages([
>     ("human","{q}"),("ai","{a}")])
> few = FewShotChatMessagePromptTemplate(
>     example_prompt=example_prompt, examples=examples)
> final = ChatPromptTemplate.from_messages([
>     ("system","按示例风格回答"), few, ("human","{input}")])
> ```
>
> 4. **MessagesPlaceholder**（占位符，常用于塞入 history）
> ```python
> ChatPromptTemplate.from_messages([
>     ("system","你是助手"),
>     MessagesPlaceholder("history"),
>     ("human","{input}"),
> ])
> ```
>
> **作用**
> - **变量插值**：避免字符串拼接、防注入。
> - **复用**：模板一处定义、多处使用。
> - **多模型适配**：不同模型需要的消息格式不同，统一抽象。
> - **Few-shot 管理**：示例集中维护。
> - **Prompt 版本化**：上传 LangSmith Prompt Hub，A/B 测试。
>
> **最佳实践**
> - 用 `from_template` 写法（自动解析 `{var}`），别用位置参数 `PromptTemplate(...)`。
> - 系统提示写在最前，历史用 `MessagesPlaceholder`，最后是当前 user 输入。
> - Few-shot 示例用 `SemanticSimilarityExampleSelector` 动态选最相关示例。
> - 把 prompt 当代码管理：版本、diff、review。

### [如何优化 LangChain 应用的性能和成本？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796956988481538)

> **答案**：
>
> LangChain 应用的性能与成本优化清单：
>
> **一、模型层**
> 1. **分级用模型**：分类/抽取/格式化用 GPT-4o-mini、Claude Haiku、Qwen-Turbo；复杂推理才用 GPT-4o、Claude Sonnet/Opus。
> 2. **本地 Embedding**：用 `bge-large-zh-v1.5`、`bge-m3`、`e5-mistral` 替代 OpenAI Embedding，省钱 + 中文更好。
> 3. **缓存**：`set_llm_cache(SQLiteCache / RedisCache)`，重复 prompt 命中缓存。
> 4. **温度=0**：抽取/分类任务降温，减少重试与不稳定。
> 5. **结构化输出**：`with_structured_output` 比"请输出 JSON"省 token 又稳定。
>
> **二、Prompt 层**
> 1. **精简 Prompt**：删冗长 few-shot，留 2~3 个高质量示例。
> 2. **压缩历史**：用 `ConversationSummaryMemory` 或 `trim_messages`。
> 3. **召回数量控制**：top_k=3~5，太多浪费 token。
> 4. **角色清晰**：System / Human / AI 分明，模型更稳。
>
> **三、检索层**
> 1. **Reranker**：bge-reranker-large 做二次精排，召回质量大幅提升。
> 2. **多路召回**：向量 + BM25 + RRF。
> 3. **元数据过滤**：减少无关召回。
> 4. **Parent Document Retrieval**：小块检索 + 大块生成，平衡精准与上下文。
>
> **四、编排层**
> 1. **流式输出**：`chain.stream()`，首 token 时间降到 200ms 内。
> 2. **并发批量**：`chain.batch([q1,q2,q3])` 或 `asyncio.gather`。
> 3. **异步**：`ainvoke / astream`，IO 密集型场景吞吐量翻倍。
> 4. **Agent max_iterations**：默认 10~15，避免烧钱死循环。
>
> **五、工程层**
> 1. **向量库选型匹配规模**：百万级用 pgvector/Qdrant，亿级用 Milvus。
> 2. **HNSW 参数调优**：efSearch、M。
> 3. **请求合并**：多个独立 LLM 调用合并成一个 prompt。
> 4. **CDN/边缘部署**：减小网络延迟。
>
> **六、监控**
> 1. **LangSmith / Langfuse**：每次调用 token、延迟、成本可视化。
> 2. **预算告警**：日 token 上限。
> 3. **慢请求分析**：P95 链路定位瓶颈。
>
> **七、业务层**
> 1. **FAQ 兜底**：高频问题命中 FAQ 不走 LLM。
> 2. **意图分流**：简单问题走规则/小模型，复杂问题才走大模型。
> 3. **预先计算**：能批处理的不实时算。
>
> **核心原则**：**用工程手段把 LLM 调用次数和 token 数都压到最低**。

### [什么是 RAG？在 LangChain 中如何实现 RAG 应用？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796955780521985)

> **答案**：
>
> **RAG（Retrieval-Augmented Generation，检索增强生成）** 是一种在生成前**先从外部知识库检索相关文档**、再把检索内容拼入 prompt 的技术，目的是**减少幻觉、引入私有/时效数据、提升可溯源性**，无需重新训练模型。
>
> **LangChain 实现 RAG 的标准步骤**：
>
> **1. 数据准备（离线）**
> ```python
> from langchain_community.document_loaders import PyPDFLoader
> from langchain.text_splitter import RecursiveCharacterTextSplitter
> from langchain_community.vectorstores import FAISS
> from langchain_openai import OpenAIEmbeddings
>
> docs = PyPDFLoader("book.pdf").load()
> chunks = RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50).split_documents(docs)
> vectorstore = FAISS.from_documents(chunks, OpenAIEmbeddings())
> retriever = vectorstore.as_retriever(search_type="mmr", search_kwargs={"k":4})
> ```
>
> **2. 生成（在线）**
> ```python
> from langchain_core.prompts import ChatPromptTemplate
> from langchain_core.runnables import RunnablePassthrough
> from langchain_core.output_parsers import StrOutputParser
> from langchain_openai import ChatOpenAI
>
> prompt = ChatPromptTemplate.from_template("""
> 你是一个严谨的问答助手。仅根据下面提供的资料回答问题，如果资料中没有答案，请回答"我不知道"。
>
> 资料：{context}
>
> 问题：{question}
> """)
> def format(docs): return "\n\n".join(d.page_content for d in docs)
>
> rag = (
>     {"context": retriever | format, "question": RunnablePassthrough()}
>     | prompt | ChatOpenAI(temperature=0) | StrOutputParser()
> )
> print(rag.invoke("什么是 RAG？"))
> ```
>
> **3. 进阶（生产 RAG）**
> - **多路召回**：向量 + BM25 + RRF（`EnsembleRetriever`）。
> - **查询改写**：`MultiQueryRetriever` / HyDE / Rewrite-Retrieve-Read。
> - **Reranker**：cross-encoder 二次精排。
> - **Parent Document**：小 chunk 检索，大 parent 生成。
> - **Multi-Vector**：表格/长文档的 summary 索引。
> - **源引用**：让答案带 `source` 字段，提升可信度。
> - **自纠错**：LangGraph + CRAG / Self-RAG，召回质量差时自动重检索或转网搜。
> - **结构化输出**：`with_structured_output` 强制 schema。
>
> **4. 评估**
> - RAGAS：faithfulness / answer_relevancy / context_precision / context_recall。
> - 自建 Golden Set 跑回归。
>
> **5. 演进**
> - **Naive RAG** → **Advanced RAG**（检索前后优化）→ **Modular RAG**（检索/路由/记忆模块化）→ **Agentic RAG**（Agent 决策何时检索/如何检索）。

### [LangChain 如何与其他 AI 框架或工具集成？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796960914350081)

> **答案**：
>
> LangChain 与其他 AI 框架/工具的集成方式：
>
> **1. 与 LlamaIndex**
> - 见前面专题：LlamaIndex 做 RAG，LangChain 做 Agent。
> - `llama_index.llms.langchain.LangChainLLM` 让 LlamaIndex 用 LangChain 的 LLM；反之把 `QueryEngine` 包成 LangChain `Tool`。
>
> **2. 与 LlamaHub / LangChain Hub**
> - LangSmith Hub 管理 Prompt、LlamaHub 管理数据 Loader，可互相引用。
>
> **3. 与 Vector Store**
> - 几乎所有主流库都有官方/社区 integration（FAISS、Chroma、Milvus、Qdrant、Pinecone、pgvector、Weaviate、Redis、Elasticsearch…），统一通过 `VectorStore` 抽象。
>
> **4. 与 Embedding / Reranker**
> - 集成 OpenAI、Cohere、HuggingFace（sentence-transformers / BGE / E5）、Voyage AI、Jina。
> - Reranker：Cohere Rerank、bge-reranker、Jina Rerank。
>
> **5. 与 Document Loader**
> - 100+ Loader：Unstructured、LlamaParse、AWS Textract、Google Vision、Notion、Slack、GitHub、Confluence、Jira、Airbyte。
>
> **6. 与 LLM Provider**
> - OpenAI、Anthropic、Google（Gemini）、Cohere、HuggingFace、Mistral、Together、Anyscale、本地 Ollama / vLLM / LM Studio。
>
> **7. 与 Observability**
> - LangSmith（官方）、Langfuse（开源）、Arize Phoenix、Weave、Helicone、Lunary——全部通过 Callback 接入。
>
> **8. 与 Workflow / Agent 框架**
> - **LangGraph**：官方 Agent 编排。
> - **CrewAI**：可与 LangChain Tool 复用。
> - **AutoGen / TaskWeaver**：通过 Tool / Message 桥接。
>
> **9. 与 Tool / Function**
> - 任何 Python 函数都能用 `@tool` 装饰成 Tool。
> - 集成 Zapier NLA、Wolfram Alpha、Wikipedia、Arxiv、PubMed、DuckDuckGo、Google Search、SerpAPI、Tavily。
>
> **10. 与 SQL / 数据**
> - `SQLDatabaseChain`、`create_sql_agent`（自然语言查数据库）。
> - SparkLoader、Snowflake、BigQuery、Pandas DataFrame Agent。
>
> **11. 与 MCP（Model Context Protocol）**
> - LangChain Agent 可以加载 MCP server 作为 Tool 集合（`langchain-mcp-adapters`），打通与 Anthropic / 其他框架的工具生态。
>
> **12. 与部署框架**
> - **LangServe**：把 Chain 一键部署成 REST API。
> - **FastAPI / Flask**：自定义。
> - **Streamlit / Gradio**：原型 UI。
>
> **13. 与 Vector Search Engine**
> - Elastic、OpenSearch、Vespa、Redis Search、Typesense——都可作为 Retriever。
>
> **14. 与 IDE / Notebook**
> - Jupyter、VS Code、Cursor——开发体验丝滑。
>
> **集成原则**：能用社区包就别自己写适配；优先选 `Runnable` 协议组件，天然支持 LCEL。

### [LangChain 的未来发展趋势如何？有哪些值得关注的方向？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796962424299522)

> **答案**：
>
> LangChain 的未来趋势可以从**官方演进方向**和**行业大方向**两个维度看：
>
> **1. 从 Chain 到 Graph：LangGraph 是核心押注**
> - 复杂 Agent 几乎都迁移到 LangGraph（多 Agent、人在回路、自纠错、长任务）。
> - LangChain 本体会越来越「薄」，作为基础组件库，编排交给 LangGraph。
> - 类比 Keras → TensorFlow 2 的演进：高层 API 简化，底层灵活。
>
> **2. 长记忆（Long-term Memory）**
> - 当前 Memory 仍然粗粒度（buffer/summary）。
> - 趋势：**MemGPT / Letta 式分层记忆**（工作记忆 + 归档记忆 + 实体记忆）。
> - LangChain 已经引入 `VectorStoreRetrieverMemory`、`EntityMemory`，未来会更强。
>
> **3. 多模态融合**
> - 视觉、音频、视频原生支持。
> - 多模态 RAG（图文混合检索）成为标配。
>
> **4. Agent 评估与可观测性**
> - LangSmith 持续投入，评估自动化、在线 A/B、数据飞轮。
> - 行业级 Agent Benchmark（SWE-bench、TerminalBench）成为衡量标准。
>
> **5. 工具协议标准化（MCP）**
> - 工具从「每个框架自定义」走向「MCP 统一标准」。
> - LangChain 已经接入 MCP，未来 Tool 生态会跨框架共享。
>
> **6. 多 Agent 协作**
> - LangGraph 已支持 Supervisor / Swarm / Hierarchical 模式。
> - 与 A2A（Agent2Agent）协议结合，跨框架 Agent 协作。
>
> **7. 长上下文与「In-Context RAG」**
> - Claude 3.5 / Gemini 1.5 上下文 1M+，部分场景「直接塞」比 RAG 还简单。
> - 但成本和精度仍让 RAG 不死，**Hybrid** 是趋势。
>
> **8. 端侧 / 本地化**
> - Ollama / llama.cpp / vLLM 让本地部署普及。
> - LangChain 通过 `ChatOllama` 等无缝对接。
>
> **9. 性能与成本优化**
> - LLM Compiler（并行 tool calling）、Speculative Decoding、Caching。
> - 小模型 + 路由 + 大模型 fallback 的混合架构。
>
> **10. 安全与治理**
> - Prompt Injection 防护、内容审核、PII 脱敏、审计日志。
> - 「红队 + 防御」工具链。
>
> **11. 行业垂直化**
> - 法律、医疗、金融、客服等垂直领域的「模板化」LangChain 应用增多。
>
> **值得关注的方向**：**LangGraph、长记忆、MCP、Agentic RAG、多 Agent 协作、自动化评估**——这六个方向决定了 LangChain 未来 1~2 年的形态。

### [LangChain 的 OutputParser 有什么作用？有哪些常见类型？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796958846558210)

> **答案**：
>
> **OutputParser** 负责把 LLM 输出的字符串解析成结构化数据，是「LLM 文本 → 程序对象」的桥梁。
>
> **常见类型**
>
> | Parser | 输出格式 | 适用 |
> |--------|---------|------|
> | **StrOutputParser** | 原字符串 | 最简单，直接透传 |
> | **CommaSeparatedListOutputParser** | 逗号分隔列表 | 简单列表 |
> | **PydanticOutputParser** | JSON → Pydantic 对象 | **最常用**，强 schema |
> | **JsonOutputParser** | JSON → dict | 不想定义 Pydantic 时 |
> | **DatetimeOutputParser** | 日期时间 | 时间抽取 |
> | **EnumOutputParser** | 枚举 | 分类任务 |
> | **BooleanOutputParser** | 布尔 | 是/否判断 |
> | **XMLOutputParser** | XML | 某些场景（如 Claude 适合 XML） |
> | **PandasDataFrameOutputParser** | DataFrame | 数据分析 |
> | **RetryOutputParser** | 失败重试 | 配合其他 parser |
> | **OutputFixingParser** | 自动修复 | LLM 修自己的格式错 |
>
> **PydanticOutputParser 示例**
> ```python
> from langchain_core.output_parsers import PydanticOutputParser
> from pydantic import BaseModel, Field
>
> class Person(BaseModel):
>     name: str = Field(description="姓名")
>     age: int = Field(description="年龄")
>
> parser = PydanticOutputParser(pydantic_object=Person)
> print(parser.get_format_instructions())
> # 输出 prompt 提示词："输出 JSON，schema: {...}"
>
> chain = prompt | llm | parser
> result = chain.invoke({"input":"张三，30岁"})
> # result: Person(name="张三", age=30)
> ```
>
> **作用**
> 1. **结构化**：把自由文本变成可程序消费的对象。
> 2. **校验**：Pydantic 自动校验类型、字段、范围。
> 3. **提示词自动生成**：`get_format_instructions()` 告诉 LLM 应该输出什么格式。
> 4. **错误恢复**：配合 `OutputFixingParser` / `RetryOutputParser` 自动修复。
>
> **新一代实践（0.1+）**
> - 简单场景直接用 `llm.with_structured_output(PydanticModel)`，**不需要再写 OutputParser**，框架内部走 function calling 强制 schema。
> - 复杂场景（带格式指令、需要重试）才用 `PydanticOutputParser`。

### [在 LangChain 中如何实现流式输出？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796955252039682)

> **答案**：
>
> LangChain 实现流式输出主要有三种粒度：
>
> **1. 整链流式（最常用，LCEL 天然支持）**
> ```python
> chain = prompt | llm | StrOutputParser()
> for chunk in chain.stream({"topic":"量子计算"}):
>     print(chunk, end="", flush=True)
> # 或异步
> async for chunk in chain.astream({"topic":"量子计算"}):
>     print(chunk)
> ```
> - LCEL 会自动把流从 LLM 一路传到 parser，每个 chunk 都是 string。
> - 底层靠 `Runnable.stream()` / `astream()` 协议。
>
> **2. Token 级流式 + 元信息（Callbacks）**
> ```python
> from langchain_core.callbacks import BaseCallbackHandler
>
> class TokenHandler(BaseCallbackHandler):
>     def on_llm_new_token(self, token, **kwargs):
>         print(token, end="", flush=True)
>
> llm.callbacks = [TokenHandler()]
> llm.invoke(...)
> ```
> - 适合需要同时拿到 token 之外的元信息（finish_reason、tool_call 等）。
>
> **3. 事件流（astream_events，推荐复杂场景）**
> ```python
> async for ev in chain.astream_events({"input":"你好"}, version="v2"):
>     if ev["event"] == "on_chat_model_stream":
>         print(ev["data"]["chunk"].content, end="")
>     elif ev["event"] == "on_tool_start":
>         print(f"\n[Tool] {ev['name']} 开始")
>     elif ev["event"] == "on_tool_end":
>         print(f"\n[Tool] {ev['name']} 返回 {ev['data'].output}")
> ```
> - 适合需要**知道每个组件、每个 token、每个工具**的全部细节的场景（前端 UI 展示「正在思考」「正在调用工具」「正在生成」）。
>
> **4. Web 前端集成**
> - **FastAPI + SSE**：
> ```python
> @app.post("/chat")
> async def chat(req):
>     async def gen():
>         async for chunk in chain.astream({"input": req.message}):
>             yield f"data: {chunk}\n\n"
>     return StreamingResponse(gen(), media_type="text/event-stream")
> ```
> - **LangServe**：自带 `/stream` 端点。
> - **前端**：用 `EventSource` 接收，逐 token 渲染。
>
> **5. 流式 + 工具调用**
> - 模型流式输出 `tool_calls` 增量时，要累积 deltas 拼成完整 ToolCall。
> - `astream_events` 已处理好累积逻辑。
>
> **最佳实践**
> - 用 `astream_events(version="v2")` 是最现代、最全的方式。
> - 流式不阻塞 UI，体感延迟从「5s 等待」变成「200ms 首 token」。
> - 别忘了给前端加错误降级（流中断时切非流式）。

### [LangChain 的 Agent 执行流程是怎样的？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796956661325825)

> **答案**：
>
> LangChain Agent 一次完整执行的标准流程（以 tool calling agent 为例）：
>
> ```
> User Input
>     │
>     ▼
> [AgentExecutor]
>     │  ① 构造 prompt：system + history + input + tool descriptions + agent_scratchpad
>     ▼
> [LLM]
>     │  ② LLM 决策：直接回答 or 调用工具？
>     │     - 直接回答 → 输出文本，结束
>     │     - 调用工具 → 输出 tool_calls (结构化 JSON)
>     ▼
> [AgentExecutor]
>     │  ③ 解析 tool_calls
>     │  ④ 路由到对应 Tool，并发执行（如有多个）
>     ▼
> [Tool]
>     │  ⑤ 执行函数，返回结果字符串
>     ▼
> [AgentExecutor]
>     │  ⑥ 把 ToolMessage 拼回 agent_scratchpad
>     │  ⑦ 回到 ①，循环
>     ▼
> ... 直到 LLM 不再调用工具，输出 Final Answer
>     │
>     ▼
> return {input, output, intermediate_steps}
> ```
>
> **关键点**
> 1. **agent_scratchpad** 是循环中的「短期记忆」：保存之前的 Thought / Action / Observation。
> 2. **intermediate_steps** 是 Executor 维护的 (Action, Observation) 元组列表，最终结果里能拿到。
> 3. **每轮都是一次完整 LLM 调用**，所以 N 步 Agent = N+1 次 LLM 调用，成本随步数线性增长。
> 4. **终止条件**：LLM 输出 Final Answer、达到 `max_iterations`、抛错。
> 5. **并行 tool calling**：现代模型支持一轮发多个 tool_calls，框架会并发执行。
>
> **ReAct 风格（老式）vs Tool Calling 风格（新式）**
>
> | 维度 | ReAct | Tool Calling |
> |------|-------|--------------|
> | LLM 输出 | 自然语言 `Thought: ... Action: ... Action Input: ...` | 结构化 JSON `tool_calls` |
> | 解析 | 正则解析 | 直接读 JSON |
> | 可靠性 | 易解析失败 | 高 |
> | 推荐 | 不推荐 | 推荐 |
>
> **LangGraph 实现的差异**
> - 不再是「AgentExecutor 黑盒循环」，而是显式的 `agent → tools → agent` 节点 + 条件边。
> - State 显式，可中断、可恢复、可人在回路。
> - 同样的执行逻辑，但**可控、可观测、可扩展**。

### [如何评估 LangChain RAG 应用的效果？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796958070611969)

> **答案**：
>
> 评估 LangChain RAG 应用效果，主要从**召回**和**生成**两端入手，常用 **RAGAS + 自建 Golden Set + LLM-as-Judge**：
>
> **一、评估指标**
>
> **1. 检索层指标（召回质量）**
> - **Context Precision**：召回的文档中，真正相关的比例。
> - **Context Recall**：所有相关文档被召回的比例（需要 ground truth context）。
> - **Recall@k / MRR / nDCG**：经典 IR 指标。
> - **Hit Rate**：top-k 里至少命中一个相关文档的比例。
>
> **2. 生成层指标（答案质量）**
> - **Faithfulness（忠诚度）**：答案是否完全来自召回内容，没有幻觉。
> - **Answer Relevancy**：答案与问题的相关程度。
> - **Answer Correctness**：与 ground truth 答案的相似度/事实一致性。
> - **Answer Similarity**：语义相似度（embedding cosine）。
>
> **3. 端到端业务指标**
> - 用户满意度（👍/👎、CSAT、NPS）。
> - 完成率（用户是否真完成任务）。
> - 重生成率、人工接管率。
> - 响应时延、token 成本。
>
> **二、评估方法**
>
> **1. RAGAS（最流行）**
> ```python
> from datasets import Dataset
> from ragas import evaluate
> from ragas.metrics import (
>     faithfulness, answer_relevancy,
>     context_precision, context_recall,
> )
>
> ds = Dataset.from_list([{
>     "question": "...", "answer": "...",
>     "contexts": ["...","..."], "ground_truth": "..."
> }])
> result = evaluate(ds, metrics=[faithfulness, answer_relevancy, context_precision, context_recall])
> # 输出 0~1 分数 + 详情
> ```
> RAGAS 用 LLM 自动评分，省人工。
>
> **2. LangSmith Datasets**
> - 把线上 trace 转成 dataset。
> - 改 prompt / 模型 / 检索后跑回归对比。
> - 内置 RAGAS 集成。
>
> **3. LLM-as-Judge**
> - 让 GPT-4 / Claude 当评委，给 (question, answer, reference) 打分。
> - 适合没有 ground truth 的开放任务。
> - 注意：评判模型与生成模型最好不是同一个，避免偏见。
>
> **4. Human Evaluation**
> - 50~200 题人工标注，作为 Golden Set。
> - 上线前必跑，作为其他方法的 ground truth。
>
> **三、评估流程（生产）**
> 1. **建 Golden Set**：从真实用户问题中抽 100~500 题，人工标注期望答案（+ 关键 context）。
> 2. **离线评估**：每次改 RAG 链路 → 跑 RAGAS + LLM-as-Judge + 关键指标对比，回归保护。
> 3. **在线 A/B**：新版本灰度 5%~20%，对比业务指标。
> 4. **反馈飞轮**：收集 bad case → 补到 Golden Set → 反哺 prompt / 检索 / 微调。
>
> **四、常见坑**
> - **Faithfulness 高但 Correctness 低**：召回质量差，模型只会"我不知道"或瞎编——优先解决召回。
> - **Recall 高但 Precision 低**：召回太多噪声，加 Reranker / 阈值过滤。
> - **LLM-as-Judge 偏见**：长答案、自信表达得分高，需要 normalize。
> - **Golden Set 漂移**：用户问题分布会变，定期更新。
>
> **核心理念**：**没有评估就没有改进**。建立可重复、自动化的评估管线，比任何 prompt 技巧都重要。

### [LangChain 是什么？它主要用来解决什么问题？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796954178297857)

> **答案**：
>
> 这一题与第一题（什么是 LangChain）核心一致，补充「**主要解决的问题**」：
>
> **LangChain 主要解决 5 类问题**：
>
> 1. **模型 API 碎片化**
>    - 痛点：OpenAI / Anthropic / Google / 本地模型 API 各不相同，切换成本高。
>    - LangChain 解法：统一 `ChatModel` 抽象，改一行代码切换模型。
>
> 2. **LLM 应用组件重复造轮子**
>    - 痛点：每次都要写 prompt 模板、output 解析、memory 管理、tool 定义。
>    - LangChain 解法：提供 Prompts / OutputParsers / Memory / Tools / Retrievers 标准组件。
>
> 3. **数据接入难**
>    - 痛点：要从 PDF / 网页 / Notion / 数据库 各种来源取数据，处理复杂。
>    - LangChain 解法：100+ DocumentLoader + TextSplitter，开箱即用。
>
> 4. **复杂 Agent 编排难**
>    - 痛点：写一个能调多工具、会循环、能纠错的 Agent 极复杂。
>    - LangChain 解法：LCEL + LangGraph 提供声明式 / 图式编排。
>
> 5. **LLM 应用可观测性差**
>    - 痛点：链路长、调用多、模型黑盒，调试靠猜。
>    - LangChain 解法：Callbacks + LangSmith 全链路追踪。
>
> **典型应用场景**
> - 文档问答（RAG）
> - 客服 Bot
> - 个人助理 / 工作流自动化
> - 代码助手
> - 数据分析 Agent
> - 知识库 + 联网搜索的混合 Agent
>
> **一句话定位**：LangChain = **LLM 时代的 Spring Boot**，让你专注业务逻辑，不被基础设施细节拖累。
