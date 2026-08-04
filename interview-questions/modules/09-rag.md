# RAG 检索增强生成

> 来源：[面试鸭 · 最全 AI 大模型面试题库（含详细答案）](https://www.mianshiya.com/bank/1906189461556076546)
> 本模块共 53 题。完整索引见 [bank-llm-by-topic.md](../bank-llm-by-topic.md)。

---



> 共 53 题

### [什么是 RAG？RAG 的主要流程是什么？](https://www.mianshiya.com/bank/1906189461556076546/question/1909864020422012930)

> **答案**：
>
> **RAG（Retrieval-Augmented Generation，检索增强生成）** 是一种在生成回答前**先从外部知识库检索相关文档**、再把检索内容拼入 prompt 的技术，让 LLM 基于"权威资料"作答。
>
> **核心目的**：
> 1. **减少幻觉**：答案有据可查。
> 2. **引入私有 / 时效 / 长尾知识**：突破训练截止限制。
> 3. **可溯源**：答案带引用，提升可信度。
> 4. **无需重训**：增删文档即可更新知识。
>
> **主要流程**（经典 5 步）：
>
> ```
> 1. 加载（Load）    ：PDF/HTML/DB → Document
> 2. 切分（Split）   ：长文档 → chunks（500~1000 token，overlap 10%）
> 3. 嵌入（Embed）   ：每个 chunk → 高维向量（BGE / OpenAI Embedding）
> 4. 索引（Index）   ：向量 + 元数据 → 向量库（Qdrant / Milvus）
> 5. 检索 + 生成     ：query → embedding → ANN top-k → Rerank → 拼入 prompt → LLM
> ```
>
> **离线部分**（建库）：1~4 步，定期更新。
> **在线部分**（查询）：第 5 步，实时响应。
>
> **完整代码骨架**：
> ```python
> from langchain_community.vectorstores import Qdrant
> from langchain_openai import OpenAIEmbeddings, ChatOpenAI
> from langchain.text_splitter import RecursiveCharacterTextSplitter
> from langchain_community.document_loaders import PyPDFLoader
> from langchain_core.prompts import ChatPromptTemplate
>
> # 离线建库
> docs = PyPDFLoader("book.pdf").load()
> chunks = RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50).split_documents(docs)
> vs = Qdrant.from_documents(chunks, OpenAIEmbeddings(), url="...", collection_name="kb")
>
> # 在线查询
> retriever = vs.as_retriever(search_type="mmr", search_kwargs={"k": 4})
> context = "\n\n".join(d.page_content for d in retriever.invoke("什么是 RAG？"))
> prompt = ChatPromptTemplate.from_template("根据资料回答：\n{context}\n\n问题：{q}")
> answer = (prompt | ChatOpenAI(temperature=0)).invoke({"context": context, "q": "什么是 RAG？"})
> ```
>
> **RAG 演进**：Naive RAG → Advanced RAG（检索前后优化）→ Modular RAG（模块化）→ Agentic RAG（Agent 决策）。

### [什么是 RAG 中的 Rerank？具体需要怎么做？](https://www.mianshiya.com/bank/1906189461556076546/question/1909895340787347458)

> **答案**：
>
> **Rerank（重排序）** 是 RAG 中**召回后、生成前**的二次精排步骤：用更强的模型对召回的 top-N 候选重新打分，挑出真正最相关的 top-k 喂给 LLM。
>
> **一、为什么需要 Rerank？**
>
> 1. **召回模型 vs 精排模型**：
>    - 召回（Embedding / Bi-Encoder）：双塔结构，query 和 doc 独立编码 → 快但精度有限。
>    - 精排（Cross-Encoder）：query 和 doc 一起输入 → 慢但精度极高。
> 2. **召回的局限**：
>    - ANN 索引本身有精度损失。
>    - embedding 对长尾 / 专业领域敏感。
>    - top-k 里常有"看起来相关但实际无关"的内容。
> 3. **Rerank 收益**：通常能提升 RAG 准确率 **10~30%**，是 RAG 最值的优化。
>
> **二、具体怎么做？**
>
> **流程**：
> ```
> 用户 query
>     │
>     ▼
> [召回层]：向量库 ANN + BM25 → top-20 候选
>     │
>     ▼
> [Rerank 层]：Cross-Encoder 对每个 (query, candidate) 打分 → 重新排序
>     │
>     ▼
> top-5 → 拼入 prompt → LLM 生成
> ```
>
> **主流 Reranker**：
> - **bge-reranker-large / v2-m3**（智源，开源 SOTA，中文友好）。
> - **Cohere Rerank 3**（商业 API）。
> - **Jina Rerank**（开源 / API）。
> - **Voyage Rerank**。
> - **ColBERT v2**（late interaction，介于 Bi 和 Cross 之间）。
> - **LLM-as-Reranker**：让 GPT-4 / Claude 给候选打分，灵活但贵。
>
> **典型代码（LangChain）**：
> ```python
> from langchain.retrievers import ContextualCompressionRetriever
> from langchain_cohere import CohereRerank
> # 或 from langchain_community.document_compressors import BgeReranker
>
> reranker = CohereRerank(top_n=4)
> compression_retriever = ContextualCompressionRetriever(
>     base_compressor=reranker,
>     base_retriever=vector_retriever,   # 召回层
> )
> docs = compression_retriever.invoke("query")
> ```
>
> **三、Rerank 实现细节**
>
> 1. **召回数量**：top-20~50，宁多勿漏。
> 2. **Rerank 后**：top-3~5，宁精勿滥。
> 3. **Cross-Encoder 输入长度限制**：通常 512 token，长 chunk 要截断或分段。
> 4. **批处理**：多候选并行打分，加速。
> 5. **缓存**：相同 query 缓存 Rerank 结果。
>
> **四、不同 Reranker 选择**
>
> | 场景 | 推荐 Reranker |
> |------|--------------|
> | 中文 RAG | **bge-reranker-v2-m3**（开源，中文友好） |
> | 英文 RAG | Cohere Rerank 3 或 bge-reranker-large |
> | 多语言 | bge-reranker-v2-m3 |
> | 高精度（成本不敏感） | LLM-as-Reranker（GPT-4 / Claude） |
> | 自托管 | bge-reranker-large + 本地 GPU |
> | SaaS 调用 | Cohere / Voyage / Jina |
>
> **五、Rerank 的边界**
>
> - **不能弥补召回缺失**：召回阶段漏掉的，Rerank 救不回来。
> - **不能解决幻觉**：仅提升"召回精度"，不解决"模型瞎编"。
> - **延迟代价**：多一次模型调用，P95 延迟增加 100~500ms。
>
> **六、实战建议**
>
> 1. **RAG baseline 必加 Reranker**：性价比最高的优化。
> 2. **bge-reranker-v2-m3 是 2026 年开源 SOTA**，中文 RAG 默认选它。
> 3. **召回宽（top-20）+ Reranker 严（top-5）**：组合最稳。
> 4. **配合元数据过滤**：先 filter 再 Rerank，省成本。
> 5. **评估 Recall@k、MRR、答案准确率**：建立 Golden Set 跑回归。
>
> **总结**：Rerank 是 RAG 质量的"二次把关"，**几乎所有生产 RAG 都该加**。开销小、收益大，是 RAG 工程的必选项而非可选项。

### [什么混合检索？在基于大模型的应用开发中，混合检索主要解决什么问题？](https://www.mianshiya.com/bank/1906189461556076546/question/1910235326833721345)

> **答案**：
>
> **混合检索（Hybrid Search）** = **向量检索（语义）+ 关键词检索（BM25）** 融合，是生产 RAG 的标准配置。
>
> **一、解决的问题**
>
> LLM 应用中检索的两大短板：
> 1. **纯向量检索的盲区**：
>    - 对专有名词、代码、数字、产品型号不敏感。
>    - 例：搜 "GPT-4" 找不到 "GPT4"（字面差异）。
>    - 例：搜 "2024Q3 销售额" 找不到精确数字匹配。
>
> 2. **纯关键词检索的盲区**：
>    - 不理解同义词、近义词、改写。
>    - 例：搜 "如何提高效率" 找不到 "提升生产力的方法"。
>
> **两者互补**：向量找"意思相近的"，BM25 找"字面匹配的"，融合后召回质量最佳。
>
> **二、实现方式**
>
> **主流方案：多路召回 + RRF 融合**
> ```
> query
>   │
>   ├─→ 向量检索 → top-k1
>   └─→ BM25 检索 → top-k2
>        │
>        ▼
>   RRF 融合：score(d) = Σ 1/(60 + rank_i(d))
>        │
>        ▼
>   综合候选 → Reranker → top-k
> ```
>
> **LangChain 实现**：
> ```python
> from langchain.retrievers import EnsembleRetriever
> from langchain_community.retrievers import BM25Retriever
>
> ensemble = EnsembleRetriever(
>     retrievers=[bm25_retriever, vector_retriever],
>     weights=[0.4, 0.6],   # RRF 下仅作语义偏向
> )
> ```
>
> **其它方案**：
> - **加权融合**：归一化分数后加权求和。
> - **路由（Router）**：按问题类型选不同检索器。
> - **BGE-M3 三合一**：一个模型同时输出 dense + sparse + ColBERT。
> - **Learning to Rank**：训练排序模型（XGBoost / LambdaMART）。
>
> **三、生产价值**
>
> 1. **召回质量提升**：混合检索通常比单路提升 10~20% recall。
> 2. **覆盖更广问题类型**：语义题和关键词题都能答好。
> 3. **降低幻觉**：召回更准 → 模型基于正确内容作答 → 减少瞎编。
> 4. **抗 embedding 模型偏置**：embedding 对某些查询不敏感时，BM25 兜底。
>
> **四、实战建议**
>
> 1. **混合检索 + Reranker**：召回层用混合，精排层用 Cross-Encoder，黄金组合。
> 2. **BGE-M3**：2024+ 推荐方案，简化架构。
> 3. **召回宽（top-20）+ Reranker 严（top-5）**：稳健。
> 4. **监控 recall、MRR**：建立 Golden Set 评估。
>
> **总结**：混合检索是生产级 RAG 的"**标配**"，解决单一检索的字面/语义盲区。

### [RAG 的完整流程是怎么样的？](https://www.mianshiya.com/bank/1906189461556076546/question/1910262700195414018)

> **答案**：
>
> **RAG 完整流程**（生产级）：
>
> ```
> [离线 - 建库]
> 原始数据（PDF/HTML/Confluence/DB/...）
>     │
>     ▼ ① Document Loader（加载）
> Document 对象（page_content + metadata）
>     │
>     ▼ ② 清洗（去噪、去重、脱敏、归一化）
> 干净 Document
>     │
>     ▼ ③ Text Splitter（切块）
> Chunks（chunk_size=500, overlap=50）
>     │
>     ▼ ④ Metadata 增强（标题、章节、摘要、问题生成）
> 增强 Chunks
>     │
>     ▼ ⑤ Embedding（向量化）
> 向量 + 元数据
>     │
>     ▼ ⑥ 写入向量库（HNSW 索引）
> 向量库（Qdrant / Milvus）
>
> [在线 - 查询]
> 用户 query
>     │
>     ▼ ⑦ Query 改写（Rewrite / HyDE / Multi-Query）
> 优化 query
>     │
>     ▼ ⑧ 多路召回（向量 + BM25）
> top-N 候选（通常 20）
>     │
>     ▼ ⑨ 元数据过滤（时间、来源、tenant）
> 过滤后候选
>     │
>     ▼ ⑩ Reranker（Cross-Encoder 精排）
> top-k 候选（通常 3~5）
>     │
>     ▼ ⑪ Context 拼接 + Prompt 模板
> 完整 prompt
>     │
>     ▼ ⑫ LLM 生成
> 答案 + 引用
>     │
>     ▼ ⑬ 后处理（去敏感、格式化、source 标注）
> 最终输出
> ```
>
> **核心步骤详解**：
>
> 1. **加载**：选对 Loader（PyMuPDF 优于 PyPDF；Unstructured 适合复杂版式）。
> 2. **清洗**：去重（MinHash）、去噪、脱敏（PII）、统一编码。
> 3. **切分**：Recursive + 结构化（Markdown header、代码块不切）。
> 4. **元数据增强**：每 chunk 加上所属标题、章节、摘要、关键问题（提升过滤召回）。
> 5. **Embedding**：BGE-M3 / OpenAI 3 / E5。
> 6. **建库**：HNSW（默认）/ IVF-PQ（大规模）。
> 7. **Query 改写**：HyDE（生成假设答案再查）、Multi-Query（扩写多版本）。
> 8. **召回**：top-20，混合检索。
> 9. **过滤**：按 tenant、time、source、permission。
> 10. **Rerank**：bge-reranker-v2-m3。
> 11. **Prompt**：`根据资料回答...如果资料中没有请说"我不知道"...资料：{context}...问题：{q}`。
> 12. **生成**：低温度（0）、结构化输出（带 source）。
> 13. **后处理**：去掉 prompt 残留、引用规范化、敏感词审查。
>
> **进阶**：
> - **Agentic RAG**：Agent 决策何时检索、检索哪个库、是否再查一次。
> - **Self-RAG / CRAG**：模型自纠错，召回质量差时自动重检索。
> - **Modular RAG**：检索、记忆、路由模块化，按场景组合。
>
> **生产化要素**：
> - 评估：RAGAS（faithfulness、context_precision、answer_relevancy）+ 人工评测。
> - 监控：召回质量、token 成本、用户反馈。
> - 持续优化：bad case 反哺 → 补文档 / 改 prompt / 微调 embedding。

### [在 RAG 应用中为了优化检索精度，其中的数据清洗和预处理怎么做？](https://www.mianshiya.com/bank/1906189461556076546/question/1910276173482655746)

> **答案**：
>
> **RAG 数据清洗和预处理** 是检索质量的基础——"Garbage In, Garbage Out"。
>
> **一、为什么重要？**
>
> - 文档原始质量参差不齐（PDF 有页眉页脚、HTML 有 navigation 噪声）。
> - 噪声进入向量库 → 召回不准 → 模型基于错误内容作答 → 幻觉。
> - 数据清洗做不好，再好的 embedding / reranker 也救不回来。
>
> **二、典型清洗流程**
>
> **1. 加载层（Loader 选择）**
> - PDF 纯文本：`PyMuPDFLoader`（fitz，最快最稳）。
> - PDF 复杂版式 / 表格：`UnstructuredPDFLoader`（深度学习识别 layout）。
> - PDF 学术：`Nougat`（Meta，专门处理公式）。
> - 商业方案：LlamaParse、Marker。
> - HTML：`BeautifulSoup` + 自定义标签过滤（去 nav/header/footer/script）。
> - Markdown：直接读，保留标题层级。
>
> **2. 噪声去除**
> - 删除页眉、页脚、页码、水印。
> - 删除 navigation 菜单、广告、cookie 提示。
> - 删除控制字符、零宽字符、异常空格。
> - 删除重复行、重复模板。
> - 中文场景：全角半角统一、繁简转换。
>
> **3. 文本归一化**
> - 统一编码（UTF-8）。
> - 统一换行（\r\n → \n）。
> - 统一引号（" " → "）。
> - 统一空格（多个空格合并）。
> - 标点修正（中英文混排）。
>
> **4. 去重**
> - **完全重复**：hash 去重。
> - **近似重复**：MinHash、SimHash、embedding 相似度。
> - **跨文档去重**：避免同一信息被多次召回。
>
> **5. 敏感信息脱敏（PII）**
> - 手机号、身份证、邮箱、银行卡号、IP。
> - 用正则或 NER 模型识别 → mask 或替换。
>
> **6. 文档结构化**
> - 提取标题层级（H1, H2, H3）。
> - 提取表格（保留为 Markdown / HTML）。
> - 提取图片描述（OCR 或 LLM 生成 caption）。
> - 保留列表、引用、代码块格式。
>
> **7. 内容增强**
> - **生成摘要**：每个 chunk 用 LLM 生成 summary，作为 metadata。
> - **生成假设问题**：每个 chunk 让 LLM 生成"这段内容能回答什么问题"。
> - **抽取关键词**：作为 metadata，用于过滤召回。
> - **实体抽取**：人名、地名、机构名，用于实体过滤。
>
> **8. 元数据标准化**
> - source（URL / 文件路径）。
> - title、section、page。
> - author、date、version。
> - tenant_id、permission_level。
> - tags、category。
>
> **三、典型问题与解决**
>
> **问题 1：PDF 表格错乱**
> - 解决：用 Unstructured / Camelot / LlamaParse；或表独立处理（转 SQL）。
>
> **问题 2：长文档语义断裂**
> - 解决：结构化切分（Markdown header、章节）；overlap 10%~20%。
>
> **问题 3：多语言混杂**
> - 解决：语言检测 + 翻译统一 / 分库存储。
>
> **问题 4：HTML 噪声多**
> - 解决：Readability.js / trafilatura 提取正文。
>
> **问题 5：扫描版 PDF**
> - 解决：OCR（Tesseract / PaddleOCR / 云服务）。
>
> **问题 6：图文混排信息丢失**
> - 解决：图独立 chunk + caption（LLM 生成）。
>
> **四、质量控制**
>
> 1. **抽样人工审查**：每批数据抽 100~500 条目检。
> 2. **统计指标**：
>    - 平均 chunk 长度（500~1000 token）。
>    - 重复率（< 5%）。
>    - 空值率（< 1%）。
>    - 字符分布异常告警。
> 3. **持续监控**：上线后定期抽检，发现 bad case 反哺清洗规则。
>
> **五、工具链**
>
> - **Unstructured**：通用文档解析。
> - **LlamaParse**：商业版，PDF 表格强。
> - **trafilatura**：HTML 正文提取。
> - **PaddleOCR / Tesseract**：OCR。
> - **Presidio**：微软 PII 脱敏。
> - **datasketch**：MinHash 去重。
> - **LangChain DocumentLoader / TextSplitter**：集成方便。
>
> **六、实战经验**
>
> 1. **数据清洗占 RAG 工程 50% 工作量**，不要低估。
> 2. **每类文档独立 pipeline**：PDF / HTML / Confluence 各自适配。
> 3. **元数据是金子**：好的 metadata 让过滤召回质量翻倍。
> 4. **生成"假设问题"作为索引**：是 RAG 召回质量提升的大招（DPR 风格）。
> 5. **持续优化**：bad case → 加清洗规则 → 重跑。
>
> **总结**：RAG 数据清洗是"看不见的工程"，决定上限。投入再多都值得——再强的模型也救不回垃圾输入。

### [什么查询扩展？为什么在 RAG 应用中需要查询扩展？](https://www.mianshiya.com/bank/1906189461556076546/question/1910534938416754689)

> **答案**：
>
> **查询扩展（Query Expansion）** 是在 RAG 检索前**把用户原始 query 扩展成多个相关 query**，并行检索后融合，提升召回质量。
>
> **一、为什么需要？**
>
> 1. **用户 query 表述差异**
>    - 用户问"如何提高效率"，文档写的是"提升生产力的方法"。
>    - 单一 query 用词不同 → 召回不到。
>
> 2. **query 模糊 / 多义**
>    - "苹果"：水果？公司？
>    - 单一 query 召回会偏。
>
> 3. **多跳问题**
>    - "对比 2023 和 2024 年销售"：需要两次检索（2023 + 2024）。
>
> 4. **embedding 对短 query 不敏感**
>    - 短 query 的 embedding 信号弱，扩展后信号强。
>
> **二、常见方法**
>
> **1. Multi-Query Expansion**
> - 用 LLM 把原 query 改写成 3~5 个不同表述。
> - 例：
>   - 原 query："如何提高效率"
>   - 改写：["提升生产力的方法", "工作效率改进技巧", "时间管理建议"]
> - 每个改写并行检索 → 合并 top-k → RRF 融合。
> - LangChain `MultiQueryRetriever` 内置。
>
> **2. HyDE（Hypothetical Document Embedding）**
> - 思想：让 LLM 先生成"假设答案"，用答案的 embedding 检索（而非 query 本身）。
> - 直觉：答案比 query 更接近文档的表述风格。
> - 流程：
>   ```
>   query → LLM 生成假设答案 → 答案 embedding → 检索 → top-k
>   ```
> - 适合：query 短、文档长、表述差异大的场景。
>
> **3. Rewrite-Retrieve-Read**
> - 先让 LLM 改写 query（更清晰、更具体），再检索。
> - 例：
>   - 原 query："GPT-4 怎么样"
>   - 改写："GPT-4 模型的能力、性能、应用场景评估"
>
> **4. Step-Back Prompting**
> - 让 LLM 把具体问题抽象成更宽泛的概念，先召回背景知识。
> - 例：
>   - 原 query："2024 年 Q3 iPhone 销量"
>   - 后退："iPhone 历史销量趋势"
>
> **5. Query Decomposition（查询分解）**
> - 复杂问题拆成多个子问题，并行检索。
> - 例：
>   - 原 query："对比 A 和 B 公司 2024 年财报"
>   - 拆解：["A 公司 2024 年财报", "B 公司 2024 年财报"]
>
> **6. 同义词 / 术语扩展**
> - 用同义词词典或 LLM 扩展专业术语。
> - 例：query "汽车" → 扩展 ["汽车", "轿车", "车辆"]。
>
> **7. Pseudo-Relevance Feedback（伪相关反馈）**
> - 第一次检索 top-k → 用 top-k 文档中的关键词扩展 query → 第二次检索。
> - 经典 IR 技术。
>
> **三、LangChain 实战**
>
> ```python
> from langchain.retrievers import MultiQueryRetriever, HyDE
> from langchain_openai import ChatOpenAI
>
> # Multi-Query
> mq_retriever = MultiQueryRetriever.from_llm(
>     retriever=vector_retriever,
>     llm=ChatOpenAI(temperature=0),
> )
> mq_retriever.invoke("如何提高效率")
>
> # HyDE
> hyde_retriever = HyDE.from_llm(ChatOpenAI(), vector_retriever, include_original=True)
> ```
>
> **四、效果与代价**
>
> **收益**：
> - Recall 提升 10~30%（看场景）。
> - 抗 query 表述差异。
> - 解决多跳 / 多义问题。
>
> **代价**：
> - 多次 LLM 调用：延迟 +200ms~1s。
> - 多次向量检索：成本 +N 倍。
> - 适合精度优先、成本不敏感场景。
>
> **五、适用场景**
>
> - **Multi-Query**：通用、稳健，适合大多数 RAG。
> - **HyDE**：query 短、文档长。
> - **Rewrite**：query 模糊 / 错别字多。
> - **Step-Back**：需要背景知识的问题。
> - **Decomposition**：复杂多跳问题。
>
> **六、实战建议**
>
> 1. **默认 Multi-Query + RRF**：性价比最高。
> 2. **HyDE 适合短 query**：长 query 用 Multi-Query 更稳。
> 3. **缓存扩展结果**：相同 query 不重复扩展。
> 4. **配合 Reranker**：扩展后召回更多，Rerank 精排更准。
> 5. **评估 Recall@k 提升**：建 Golden Set 量化收益。
>
> **总结**：查询扩展是 RAG "进阶优化"的标配，让模型从"用户说的"扩展到"用户真正想要的"。Multi-Query + RRF 是最实用的起点。

### [什么自查询？为什么在 RAG 中需要自查询？](https://www.mianshiya.com/bank/1906189461556076546/question/1910542542891614209)

> **答案**：
>
> **自查询（Self-Query）** 是 RAG 中一种**让 LLM 从用户问题中自动拆出"语义查询 + 元数据过滤条件"**的技术。
>
> **一、为什么需要？**
>
> 向量检索擅长语义匹配，但不擅长**精确条件过滤**：
> - "2023 年 Q3 销售额是多少？"：需要按时间过滤。
> - "张三负责的项目有哪些？"：需要按 owner 过滤。
> - "OpenAI 的 GPT 系列有哪些？"：需要按 company 过滤。
>
> **痛点**：
> - 纯向量检索：把整个 query 当语义查询 → 召回不准。
> - 手动过滤：用户不会自己拆条件。
>
> **自查询解决**：让 LLM 自动拆分。
>
> **二、工作原理**
>
> ```
> 用户 query："2023 年 Q3 张三负责的项目里销售额超过 100 万的有几个？"
>        │
>        ▼
> [Self-Query 拆分]
>        │
>        ├─ 语义查询："销售额超过 100 万的项目"
>        └─ 元数据过滤：
>             - year = 2023
>             - quarter = "Q3"
>             - owner = "张三"
>        │
>        ▼
> [向量库检索 + filter]
>        │
>        ▼
> top-k 结果
> ```
>
> **LangChain 实现**：
> ```python
> from langchain.retrievers.self_query.base import SelfQueryRetriever
> from langchain.chains.query_constructor.base import AttributeInfo
>
> metadata_field_info = [
>     AttributeInfo(name="year", description="年份", type="integer"),
>     AttributeInfo(name="quarter", description="季度", type="string"),
>     AttributeInfo(name="owner", description="负责人", type="string"),
> ]
>
> retriever = SelfQueryRetriever.from_llm(
>     llm=ChatOpenAI(),
>     vectorstore=vectorstore,
>     document_contents="项目销售记录",
>     metadata_field_info=metadata_field_info,
>     enable_limit=True,
> )
> docs = retriever.invoke("2023 年 Q3 张三负责的项目里销售额超过 100 万的有几个？")
> ```
>
> LLM 内部会生成类似：
> ```
> {
>   "query": "销售额超过 100 万的项目",
>   "filter": "and(eq("year", 2023), eq("quarter", "Q3"), eq("owner", "张三"))"
> }
> ```
>
> **三、优势**
>
> 1. **解决带条件的自然语言查询**：用户不需要写 SQL。
> 2. **召回精度大幅提升**：先过滤再语义匹配，候选集小且准。
> 3. **元数据驱动**：充分利用文档结构化信息。
> 4. **支持复杂逻辑**：AND / OR / 范围 / 比较。
>
> **四、适用场景**
>
> - 业务报表查询（时间、地区、产品）。
> - 文档库多分类（部门、类型、作者）。
> - 电商产品搜索（价格、品牌、规格）。
> - 客服工单（状态、优先级、负责人）。
> - 任何"语义 + 结构化条件"的混合查询。
>
> **五、关键设计**
>
> **1. 元数据建模**
> - 文档入库时必须打好 metadata。
> - 字段：year、quarter、category、author、source、tenant_id。
> - 类型：string / integer / float / date。
>
> **2. AttributeInfo 描述**
> - LLM 靠 description 理解字段含义。
> - 写清楚：`AttributeInfo(name="year", description="订单创建的年份，如 2024", type="integer")`。
>
> **3. LLM 提示**
> - 给 LLM 看字段描述 + 用户 query。
> - 让 LLM 输出结构化的 `query + filter`。
>
> **4. 错误处理**
> - LLM 拆错时降级：纯向量检索或返回 "无法理解"。
> - 复杂 query 拆失败时提示用户简化。
>
> **六、局限**
>
> 1. **依赖 metadata 完整性**：没打好 metadata 的文档无法过滤。
> 2. **LLM 拆分不完美**：复杂 query 可能拆错。
> 3. **不支持聚合**：不能像 SQL 那样 COUNT / SUM，需配合 Text-to-SQL。
> 4. **延迟增加**：多一次 LLM 调用。
>
> **七、进阶**
>
> - **Text-to-SQL**：完全用 SQL 替代自查询，适合结构化数据。
> - **Hybrid（向量 + SQL）**：结构化走 SQL，非结构化走向量。
> - **Agentic Self-Query**：Agent 决定何时用自查询、何时纯向量。
>
> **八、实战经验**
>
> 1. **元数据是基础**：建库时 metadata 打全、打准。
> 2. **AttributeInfo 描述清晰**：决定 LLM 拆分准确率。
> 3. **复杂场景加 Reranker**：自查询召回后再精排。
> 4. **降级方案**：LLM 拆错时回退到纯向量检索。
> 5. **持续优化**：bad case 反哺 metadata 设计。
>
> **总结**：Self-Query 把"自然语言 + 结构化条件"查询变得自然，是业务 RAG 的关键技术。前提是元数据建模扎实——好的 metadata 是 Self-Query 成功的 80%。

### [什么提示压缩？为什么在 RAG 中需要提示压缩？](https://www.mianshiya.com/bank/1906189461556076546/question/1910609088653524993)

> **答案**：
>
> **提示压缩（Prompt Compression）** 是 RAG 中**减少 prompt 中检索内容 token 数**的技术，在不损失关键信息的前提下**降成本、加速、扩大可用 context**。
>
> **一、为什么需要？**
>
> 1. **Token 成本**：每 1M token 价格 $5~$20，长 context 累计成本高。
> 2. **延迟**：长 prompt → 长 TTFT（首 token 时间）→ 用户体验差。
> 3. **上下文窗口限制**：模型虽有 128K/1M，但有效注意力衰减，长 context 反而效果差（"Lost in the Middle"）。
> 4. **召回冗余**：召回的 top-k 通常有大量重复 / 无关内容。
>
> **二、压缩方法**
>
> **1. 抽取式压缩（Extractive）**
> - 从召回内容中**抽取关键句**，删除冗余。
> - 算法：TextRank、句子 embedding 聚类、LLM 抽取。
> - 简单、保真，但效果有限。
>
> **2. 摘要式压缩（Abstractive）**
> - 用 LLM 把召回内容**总结**成精简版。
> - 例：5 个 chunks → LLM 总结成 1 段摘要。
> - 信息损失大，但 token 节省多。
>
> **3. Token 级压缩**
> - 用专门模型（如 LLMLingua）逐 token 评估重要性，**删除低信息 token**。
> - 不改变文本结构，只去掉"水分"。
> - 压缩比 2~10 倍，几乎无损。
> - 代表：**LLMLingua、LongLLMLingua**（微软）。
>
> **4. Chunk 截断**
> - 每 chunk 只保留前 N 字符（粗略压缩）。
> - 简单粗暴，效果一般。
>
> **5. 上下文学习（In-Context Learning）优化**
> - Few-shot 示例精选（聚类 + 多样性）。
> - 减少示例数，保持代表性。
>
> **三、典型工具**
>
> **1. LLMLingua**
> - 微软开源。
> - 用小模型（LLaMA-7B）评估 token 重要性。
> - 压缩 prompt 2~10 倍，效果几乎无损。
> - 集成 LangChain：`from langchain.retrievers import ContextualCompressionRetriever` + `LLMLinguaCompressor`。
>
> **2. Selective Context**
> - 用小模型识别重要句子 / 词。
>
> **3. Summary Compressor**
> - 用 LLM 把多 chunk 总结成一段。
>
> **4. Custom Compressor**
> - 自定义规则：删 URL、删空白、删样板句。
>
> **四、LangChain 实战**
>
> ```python
> from langchain.retrievers import ContextualCompressionRetriever
> from langchain.retrievers.document_compressors import (
>     LLMLinguaCompressor, DocumentLLMChainSummarizer, EmbeddingsFilter
> )
>
> # LLMLingua 压缩
> compressor = LLMLinguaCompressor()
> compression_retriever = ContextualCompressionRetriever(
>     base_compressor=compressor,
>     base_retriever=vector_retriever,
> )
>
> # Embeddings 过滤（删除与 query 不相似的 chunk）
> embeddings_filter = EmbeddingsFilter(embeddings=embedder, similarity_threshold=0.7)
> ```
>
> **五、压缩 vs 不压缩的权衡**
>
> | 维度 | 不压缩 | 压缩 |
> |------|--------|------|
> | **Token 成本** | 高 | 低 2~10x |
> | **延迟** | 高 | 低 |
> | **信息保真** | 100% | 90~99% |
> | **答案质量** | baseline | 几乎持平（部分场景更好，因为去噪） |
> | **复杂度** | 低 | 中（多一层） |
> | **适用** | 短 context / 高精度 | 长 context / 成本敏感 |
>
> **六、实战经验**
>
> 1. **LLMLingua 是默认选择**：开源、效果好、几乎无损。
> 2. **配合 Reranker**：先 Rerank（精排）再压缩（去水分）。
> 3. **不要过度压缩**：压缩比 > 10x 可能丢关键信息。
> 4. **测试 P95 延迟**：压缩后首 token 时间应明显下降。
> 5. **监控答案质量**：建立 Golden Set，确认压缩后准确率不掉。
>
> **七、其他降 token 技巧**
>
> 1. **召回数量控制**：top-k 不要过大（3~5 通常够）。
> 2. **chunk_size 适中**：500~1000 token，太大浪费、太小碎片化。
> 3. **系统提示精简**：删冗长指令、删 few-shot。
> 4. **历史压缩**：多轮对话用 summary memory。
> 5. **小模型优先**：能用 GPT-4o-mini 就别用 GPT-4o。
> 6. **缓存**：相同 query 直接命中。
>
> **八、典型架构**
>
> ```
> query → Multi-Query 扩展 → 多路召回 → RRF 融合
>   → Reranker 精排 top-5 → LLMLingua 压缩 → 拼入 prompt → LLM 生成
> ```
>
> **总结**：Prompt 压缩是 RAG 性价比优化的重要环节，**LLMLingua 是当前最优开源方案**。配合 Reranker、合理 top-k、精简系统提示，能让 RAG 既准又快又省。

### [如何进行 RAG 调优后的效果评估？请给出真实应用场景中采用的效果评估标准与方法](https://www.mianshiya.com/bank/1906189461556076546/question/1912765231981187074)

> **答案**：
>
> **RAG 评估** 是质量保障的基础——"没有评估就没有改进"。
>
> **一、评估维度**
>
> RAG = **Retrieval（检索）+ Generation（生成）**，分别评估。
>
> **1. 检索指标（Recall 质量）**
> - **Recall@k**：top-k 里真正相关的比例。
> - **MRR（Mean Reciprocal Rank）**：第一个相关结果排名的倒数平均。
> - **nDCG**：考虑排序的相关性。
> - **Hit Rate**：top-k 至少命中一个相关的比例。
> - **Context Precision**：召回内容中相关比例。
> - **Context Recall**：所有相关文档被召回的比例。
> - **Context Relevancy**：召回内容与问题的相关性。
>
> **2. 生成指标（Answer 质量）**
> - **Faithfulness（忠诚度）**：答案是否完全来自召回内容（防幻觉）。
> - **Answer Relevancy**：答案与问题的相关度。
> - **Answer Correctness**：与 ground truth 的事实一致性。
> - **Answer Similarity**：语义相似度（embedding cosine）。
> - **任务专属**：
>   - 抽取：Precision / Recall / F1。
>   - 摘要：ROUGE、BERTScore、FactCC。
>   - 代码：pass@k。
>   - 数学：Accuracy。
>
> **3. 端到端业务指标**
> - 用户满意度（👍/👎、CSAT）。
> - 重生成率。
> - 人工接管率。
> - 任务完成率。
> - 引用准确率（citation accuracy）。
> - 延迟、token 成本。
>
> **二、评估方法**
>
> **1. RAGAS（最流行）**
> - 开源框架。
> - 用 LLM 自动评分。
> - 指标：faithfulness、answer_relevancy、context_precision、context_recall。
>
> ```python
> from datasets import Dataset
> from ragas import evaluate
> from ragas.metrics import faithfulness, answer_relevancy, context_precision
>
> ds = Dataset.from_list([{
>     "question": "...", "answer": "...",
>     "contexts": ["..."], "ground_truth": "..."
> }])
> result = evaluate(ds, metrics=[faithfulness, answer_relevancy, context_precision])
> ```
>
> **2. LangSmith / Langfuse**
> - 平台集成 trace + 评估 + dataset 管理。
> - 在线 + 离线评估一体化。
>
> **3. LLM-as-Judge**
> - GPT-4 / Claude 当评委，给 (q, a) 打分。
> - 适合无 ground truth 的开放任务。
> - 注意偏见（长答案得分高）。
>
> **4. 人工评估（金标准）**
> - 业务专家盲评 50~200 题。
> - 多标注者一致性（Cohen's Kappa）。
> - A/B 测试。
>
> **5. 在线 A/B**
> - 灰度新版本 vs 旧版本。
> - 监控业务指标 + 用户反馈。
>
> **三、真实场景的评估标准**
>
> **场景 1：客服 RAG**
> - 召回：Recall@5 ≥ 90%（5 个里至少 1 个相关）。
> - 答案：人工评估准确率 ≥ 95%。
> - 业务：人工接管率 < 20%，重生成率 < 15%。
> - 引用：100% 答案带可点击 source。
>
> **场景 2：法律 / 医疗 RAG**
> - 召回：Recall@10 ≥ 95%（不能漏关键条款）。
> - 答案：faithfulness ≥ 0.95（绝不瞎编）。
> - 业务：100% 引用准确，专家盲评准确率 ≥ 98%。
>
> **场景 3：内部知识库**
> - 召回：MRR ≥ 0.7。
> - 答案：answer_relevancy ≥ 0.85。
> - 业务：用户满意度 ≥ 4.2/5。
>
> **四、评估流程（生产）**
>
> 1. **建 Golden Set**：100~500 题，覆盖：
>    - 简单 / 中等 / 困难。
>    - 各类业务场景。
>    - 已知 bad case。
>    - 期望答案 + 期望来源文档。
>
> 2. **离线评估**：
>    - 每次改 prompt / 模型 / 检索 → 跑 RAGAS + LLM-as-Judge + 业务指标。
>    - 与基线对比 → 回归保护。
>
> 3. **在线 A/B**：
>    - 新版本灰度 5~20%。
>    - 对比业务指标 + 用户反馈。
>
> 4. **反馈飞轮**：
>    - 收集 bad case → 标注 → 补到 Golden Set。
>    - 反哺 prompt / 检索 / 微调。
>
> **五、常见坑**
>
> 1. **只看自动指标**：RAGAS 高但用户不满意（脱离业务）。
> 2. **Golden Set 不更新**：用户问题分布变化，旧 Golden 失效。
> 3. **没 ground truth**：没法算 Recall / Correctness，只能靠 LLM-as-Judge。
> 4. **LLM-as-Judge 偏见**：长答案、自信表达得分高。
> 5. **不评估引用**：RAG 答案带 source，但 source 错了没人发现。
>
> **六、工具链**
>
> - **RAGAS**：开源 RAG 评估。
> - **TruLens**：RAG 评估 + 监控。
> - **LangSmith / Langfuse**：trace + 评估 + dataset。
> - **Promptfoo**：prompt/模型对比。
> - **DeepEval**：开源，类似 RAGAS。
> - **Phoenix**（Arize）：可观测性 + 评估。
>
> **七、推荐指标体系（生产）**
>
> | 维度 | 关键指标 | 阈值（参考） |
> |------|---------|-------------|
> | **检索** | Recall@5 | ≥ 90% |
> | | MRR | ≥ 0.7 |
> | | Context Precision | ≥ 0.8 |
> | **生成** | Faithfulness | ≥ 0.9 |
> | | Answer Relevancy | ≥ 0.85 |
> | **业务** | 用户满意度 | ≥ 4.2/5 |
> | | 引用准确率 | ≥ 95% |
> | | 重生成率 | < 15% |
> | **性能** | P95 延迟 | < 3s |
> | | 单 query 成本 | < $0.01 |
>
> **八、总结**
>
> RAG 评估的核心：
> 1. **多维评估**：检索 + 生成 + 业务 + 性能。
> 2. **Golden Set 是基石**：人工标注 100~500 题。
> 3. **自动化评估管线**：RAGAS + LLM-as-Judge + 业务指标。
> 4. **持续监控**：在线 + 离线 + 反馈飞轮。
> 5. **数据驱动决策**：任何改动都用数据说话。
>
> **核心理念**：**没有评估就没有改进**。建立可重复、自动化的评估管线，比任何 prompt 技巧都重要。

### [什么是 RAG 中的分块？为什么需要分块？](https://www.mianshiya.com/bank/1906189461556076546/question/1912781951991074817)

> **答案**：
>
> **分块（Chunking）** 是 RAG 中把长文档切成小块的过程，每块作为一个独立的"知识单元"被向量化、检索、拼入 prompt。
>
> **一、为什么需要分块？**
>
> 1. **Embedding 模型长度限制**
>    - 大多数 embedding 模型 max_length=512 token。
>    - 长文档直接 embedding 会被截断，信息丢失。
>
> 2. **检索精度**
>    - 整篇文档 embedding 把太多信息压成一个向量 → 召回不准。
>    - 小块 embedding 更"专注"，召回更准。
>
> 3. **LLM 上下文限制**
>    - 召回的 top-k 拼入 prompt，长度受 context window 限制。
>    - 小块更省 token，可塞更多 chunks。
>
> 4. **答案精度**
>    - 小块更精准，模型基于"最相关的片段"作答，避免噪声。
>    - 大块容易带入无关内容，干扰生成。
>
> 5. **可溯源性**
>    - 答案带 source 时，小块对应到具体段落，引用更精确。
>
> **二、分块的核心问题**
>
> - **太大**：召回精度低、token 浪费、信息稀释。
> - **太小**：上下文断裂、信息碎片化。
> - **关键**：找到"语义完整 + 大小适中"的甜蜜点。
>
> **三、常见策略**
>
> **1. 固定长度切分（Character-based）**
> - 每 N 字符切一块。
> - 简单、粗暴。
> - 缺点：可能切断句子、段落。
>
> **2. 字符递归切分（Recursive Character）**
> - 按分隔符优先级递归：`["\n\n", "\n", " ", ""]`。
> - 优先在段落边界切，找不到再降级。
> - **最通用**，LangChain 默认。
>
> ```python
> from langchain.text_splitter import RecursiveCharacterTextSplitter
> splitter = RecursiveCharacterTextSplitter(
>     chunk_size=500,
>     chunk_overlap=50,
>     separators=["\n\n", "\n", "。", "！", "？", "，", " ", ""]
> )
> ```
>
> **3. Token 切分（Token-based）**
> - 按 token 数切，与 LLM 计费单位一致。
> - 适合精控 token。
>
> **4. Markdown 标题切分（Markdown Header）**
> - 按 # / ## / ### 标题层级切。
> - 保留章节路径作为 metadata。
> - 适合文档、手册。
>
> **5. HTML 标签切分**
> - 按 HTML 标签切。
> - 适合网页。
>
> **6. 代码切分（Code Splitter）**
> - 按函数 / 类边界切。
> - 适合代码库。
>
> **7. 句子切分（Sentence-based）**
> - 用 NLP 工具（spaCy / NLTK）按句子切。
> - 适合精排场景。
>
> **8. 语义切分（Semantic Chunking）**
> - 用 embedding 相似度判断"语义断点"。
> - 相邻句子 embedding 差异大时切。
> - 质量最高但慢。
> - 2024+ 上升趋势。
>
> **9. 父子文档切分（Parent Document）**
> - 小块用于检索（精准）。
> - 大块（父文档）用于生成（上下文全）。
> - `ParentDocumentRetriever`。
>
> **10. 表格 / 代码独立成块**
> - 表格、代码不要被切散。
> - 作为整体 chunk。
>
> **四、参数选择**
>
> **chunk_size**：
> - 200~500 token：精准检索、省 token。
> - 500~1000 token：**通用推荐**，平衡精度和上下文。
> - 1000~2000 token：长上下文场景（摘要、长文 RAG）。
> - 与 embedding 模型 max_length 对齐（BGE 512、OpenAI 8191）。
>
> **chunk_overlap**：
> - 10%~20% 是经典范围。
> - 防止关键句被切散。
> - 500 token chunk → overlap 50~100。
>
> **五、实战经验**
>
> 1. **中文场景自定义分隔符**：默认是英文优先级，中文应加 `["。", "！", "？", "，"]`。
> 2. **结构化文档先按 Header 切**：保留章节 metadata，过滤召回效果好。
> 3. **表格 / 代码不切**：作为整体 chunk。
> 4. **chunk_size 与下游模型匹配**：embedding max_length、LLM context window。
> 5. **Parent Document Retrieval**：小块检索、大块生成，是高质量 RAG 的标配。
> 6. **元数据增强**：每 chunk 加上 title / section / 章节路径，便于过滤。
> 7. **语义切分提升精度**：高质量场景值得尝试。
>
> **六、不同场景的策略**
>
> | 场景 | 推荐 chunk_size | 推荐策略 |
> |------|---------------|---------|
> | FAQ 问答 | 200~300 | 问答对独立 chunk |
> | 长文档摘要 | 1000~2000 | Markdown header + Recursive |
> | 客服知识库 | 300~500 | Recursive + 句子优先 |
> | 法律 / 医疗 | 500~800 | Markdown header + Parent Document |
> | 代码库 | 函数级 | Code Splitter |
> | 表格数据 | 整表 | 不切，独立 chunk |
> | 多模态 | caption + 图 | 图独立，caption 配对 |
>
> **七、总结**
>
> 分块是 RAG 质量的"**地基**"，决定了召回上限：
> - **Recursive + 结构化** 是通用首选。
> - **chunk_size=500、overlap=50** 是稳健起点。
> - **元数据增强** 让过滤召回质量翻倍。
> - **Parent Document** 平衡精准与上下文。
> - **语义切分** 是高质量场景的进阶选择。
>
> **核心理念**：好的 chunking 让模型"看到对的、看到全的"，是 RAG 工程最重要的环节之一。

### [在 RAG 中，常见的分块策略有哪些？分别有什么区别？](https://www.mianshiya.com/bank/1906189461556076546/question/1912795349320720385)

> **答案**：
>
> RAG 中**常见分块策略**对比：
>
> | 策略 | 原理 | 适用 | 优点 | 缺点 |
> |------|------|------|------|------|
> | **Fixed-size** | 每 N 字符切 | 简单场景 | 简单 | 切断句子 |
> | **Recursive Character** | 多分隔符递归 | **通用首选** | 灵活 | 参数需调 |
> | **Token-based** | 按 token 数 | 精控 token | 与 LLM 一致 | 不考虑语义 |
> | **Markdown Header** | 按标题切 | 文档、手册 | 保留结构 | 仅适用 Markdown |
> | **HTML Header** | 按标签切 | 网页 | 保留结构 | 仅 HTML |
> | **Code Splitter** | 按函数 / 类切 | 代码 | 保留逻辑 | 仅代码 |
> | **Sentence-based** | 按句子切 | 精排 | 语义边界 | chunk 大小不均 |
> | **Semantic Chunking** | embedding 相似度切 | 高质量 RAG | 质量最高 | 慢、复杂 |
> | **Parent Document** | 小检大生 | 高质量 RAG | 平衡精准与上下文 | 实现复杂 |
>
> **详解主流策略**：
>
> **1. Recursive Character（最推荐）**
> - 分隔符优先级：段落 → 行 → 句号 → 逗号 → 空格 → 字符。
> - 中文：自定义 `["\n\n", "\n", "。", "！", "？", "，", " ", ""]`。
> - chunk_size=500, overlap=50 是经典起点。
>
> **2. Markdown Header（结构化文档）**
> ```python
> from langchain.text_splitter import MarkdownHeaderTextSplitter
> splitter = MarkdownHeaderTextSplitter([
>     ("#", "Header 1"),
>     ("##", "Header 2"),
>     ("###", "Header 3"),
> ])
> # 切完后每 chunk 自带 metadata: {"Header 1": "...", "Header 2": "..."}
> ```
> 配合 Recursive 二次切，得到结构化小块。
>
> **3. Parent Document（高质量 RAG）**
> - 把文档切成**父块（大）**和**子块（小）**。
> - 子块用于检索（精准）。
> - 父块用于生成（上下文全）。
> - LangChain `ParentDocumentRetriever`。
>
> **4. Semantic Chunking（进阶）**
> - 计算相邻句子 embedding 余弦相似度。
> - 相似度 < 阈值处切分。
> - 适合长文档、高质量场景。
>
> **选择决策**：
> - 通用：**Recursive Character**（默认）。
> - 结构化文档：**Markdown Header + Recursive**。
> - 高精度：**Parent Document** 或 **Semantic**。
> - 代码：**Code Splitter**。
> - 网页：**HTML Header**。
>
> **核心**：不同数据类型用不同策略，**混合使用**最佳。

### [在 RAG 中的 Embedding 嵌入是什么？](https://www.mianshiya.com/bank/1906189461556076546/question/1912805533338296322)

> **答案**：
>
> **Embedding 嵌入** 是 RAG 的核心步骤：把文本（或图、音）转成**稠密高维向量**，让"语义相近"的内容在向量空间距离近。
>
> **一、原理**
>
> - 输入：token 序列。
> - 输出：固定维度向量（768 / 1024 / 1536 / 3072 维）。
> - 训练目标：对比学习（InfoNCE），拉近正样本、推远负样本。
> - 模型架构：Bi-Encoder（双塔），query 和 doc 独立编码。
>
> **二、RAG 中的工作流程**
>
> ```
> [离线]
> 文档 → 切块 → Embedding → 向量 + 元数据 → 向量库
>
> [在线]
> query → 同一 Embedding → query 向量
>        ↓
>    向量库 ANN 检索 → top-k 候选
>        ↓
>    Reranker → 精排
>        ↓
>    拼入 prompt → LLM 生成
> ```
>
> **关键**：建库和查询**必须用同一 Embedding 模型**。换模型 = 重建库。
>
> **三、常见 Embedding 模型**
>
> **中文**：
> - **BGE-large-zh-v1.5**（智源）：开源 SOTA。
> - **BGE-M3**（智源）：多语言、多功能（dense + sparse + ColBERT），**2024+ 推荐**。
> - **M3E**：国产，中文友好。
> - **piccol-**（华为）：小而精。
> - **Qwen3-Embedding**：阿里最新。
>
> **英文**：
> - **OpenAI text-embedding-3-small / large**：API，效果稳。
> - **Cohere Embed v3**：商业 API。
> - **E5-mistral-7b**：开源大模型 embedding。
> - **GTE**（阿里）：开源。
> - **jina-embeddings-v3**：开源，多任务。
>
> **多模态**：
> - **CLIP / SigLIP / EVA-CLIP**：图文对齐。
> - **BGE-VL**：图文多模态 embedding。
>
> **MTEB 排行榜**是选型参考：覆盖 8 大任务（检索、聚类、分类等）的综合排名。
>
> **四、关键技巧**
>
> 1. **指令 Embedding**：BGE / E5 支持 query 前缀指令，如 `"Represent this sentence for searching relevant passages: "`。
> 2. **维度选择**：1024 维是性价比最优；3072 维精度略高但成本翻倍。
> 3. **归一化**：余弦相似度时 L2 归一化，点积 = 余弦。
> 4. **Matryoshka**（套娃）：OpenAI / BGE 支持，外层维度即可使用，可降维省内存。
> 5. **chunk_size 匹配**：embedding 模型 max_length 通常 512，chunk 别超过。
>
> **五、维护**
>
> - 监控 query-doc 相似度分布。
> - 定期评估 recall@k。
> - bad case 反哺微调。
>
> **总结**：Embedding 是 RAG 的"语义引擎"，BGE-M3 / OpenAI 3 是 2026 年主流选择。

### [在 RAG 中，你知道有哪些 Embedding Model 嵌入模型？](https://www.mianshiya.com/bank/1906189461556076546/question/1913119866891001857)

> **答案**：
>
> **主流 Embedding 模型清单**（按场景）：
>
> **一、中文 Embedding**
>
> | 模型 | 维度 | 来源 | 特点 |
> |------|------|------|------|
> | **BGE-large-zh-v1.5** | 1024 | 智源 | 开源 SOTA，常用 |
> | **BGE-M3** | 1024 | 智源 | 多语言、dense+sparse+ColBERT，**推荐** |
> | **M3E-large** | 1024 | 国产社区 | 开源、中文友好 |
> | **piccol-base** | 768 | 华为 | 小而精 |
> | **Qwen3-Embedding-8B** | 4096 | 阿里 | 最新、效果强 |
> | **text2vec-large-chinese** | 768 | 国产 | 早期，已淘汰 |
> | **DPR-chinese** | 768 | 早期 | 已淘汰 |
>
> **二、英文 Embedding**
>
> | 模型 | 维度 | 来源 | 特点 |
> |------|------|------|------|
> | **text-embedding-3-small** | 1536 | OpenAI | API，性价比高 |
> | **text-embedding-3-large** | 3072 | OpenAI | API，精度高 |
> | **Cohere Embed v3** | 1024 | Cohere | 商业 API |
> | **Voyage AI** | 1024 | Voyage | 商业，定制强 |
> | **E5-mistral-7b** | 4096 | Microsoft | 开源大模型 |
> | **GTE-large** | 1024 | 阿里 | 开源 |
> | **jina-embeddings-v3** | 1024 | Jina | 开源，多任务 |
> | **BGE-large-en-v1.5** | 1024 | 智源 | 开源 |
> | **SBERT**（all-mpnet-base-v2） | 768 | SentenceTransformers | 经典 |
> | **SimCSE** | 768 | 清华 | 自监督对比学习 |
>
> **三、多语言**
>
> | 模型 | 维度 | 特点 |
> |------|------|------|
> | **BGE-M3** | 1024 | **100+ 语言，推荐** |
> | **multilingual-E5-large** | 1024 | 多语言 |
> | **LaBSE** | 768 | 跨语言对齐 |
> | **OpenAI text-embedding-3** | 1536/3072 | 多语言强 |
>
> **四、多模态**
>
> | 模型 | 维度 | 特点 |
> |------|------|------|
> | **CLIP** | 512/768 | 图文对齐经典 |
> | **SigLIP** | 768 | 改进 CLIP |
> | **EVA-CLIP** | 1024 | 强 CLIP |
> | **BGE-VL** | 1024 | 多模态 RAG |
> | **ImageBind** | 1024 | 6 模态统一 |
>
> **五、代码 Embedding**
>
> | 模型 | 维度 | 特点 |
> |------|------|------|
> | **CodeT5+** | 768 | 代码理解 |
> | **UniXcoder** | 768 | 代码 + 文本 |
> | **jina-embeddings-v2-code** | 768 | 代码专用 |
> | **Voyage Code** | 1024 | 商业 |
>
> **六、特殊用途**
>
> | 模型 | 维度 | 特点 |
> |------|------|------|
> | **ColBERT v2** | 128/768 | late interaction，高精度 |
> | **SPLADE** | sparse | 稀疏向量，类似 BM25 |
> | **BGE-M3 sparse** | sparse | BGE-M3 的稀疏模式 |
>
> **七、选型参考**
>
> - **中文 RAG 默认**：BGE-M3。
> - **英文 RAG 默认**：OpenAI text-embedding-3-small（API）或 BGE-large-en（开源）。
> - **多语言**：BGE-M3。
> - **多模态**：CLIP / BGE-VL。
> - **代码 RAG**：jina-code / Voyage Code。
> - **本地部署**：BGE-M3 / E5。
> - **最高精度**：E5-mistral-7b / OpenAI text-embedding-3-large。
>
> **八、MTEB / C-MTEB 排行榜**
>
> HuggingFace MTEB 是权威 embedding 评测（涵盖检索、聚类、分类等 8 类任务）。选型时优先看 retrieval 子榜（与 RAG 最相关）。
>
> **总结**：BGE-M3（中文/多语言）、OpenAI text-embedding-3（英文 API）、CLIP（多模态）是 2026 年三大主力选择。

### [在 RAG 中，你如何选择 Embedding Model 嵌入模型，需要考虑哪些因素？](https://www.mianshiya.com/bank/1906189461556076546/question/1913129432949186562)

> **答案**：
>
> **选择 Embedding 模型需要考虑**：
>
> **一、业务维度**
>
> **1. 语言**
> - 中文为主：BGE-M3 / BGE-large-zh / M3E。
> - 英文为主：OpenAI text-embedding-3 / E5 / GTE。
> - 多语言：BGE-M3 / multilingual-E5。
> - 代码：jina-code / Voyage Code / CodeT5+。
>
> **2. 领域**
> - 通用：BGE / OpenAI / E5。
> - 医疗、法律、金融：通用 embedding + 领域数据微调（用 Sentence-Transformers）。
> - 代码：CodeT5+ / jina-code。
> - 多模态：CLIP / SigLIP / BGE-VL。
>
> **3. 任务**
> - RAG / 语义搜索：dense embedding（默认）。
> - 关键词敏感：sparse embedding（SPLADE）或 BM25。
> - 高精度：ColBERT。
> - 混合：BGE-M3（dense + sparse + ColBERT 三合一）。
>
> **二、技术维度**
>
> **4. 性能（MTEB / C-MTEB 排行）**
> - 看 retrieval 子榜，与 RAG 最相关。
> - 综合榜（分类、聚类、检索等 8 任务平均）作为参考。
>
> **5. 维度**
> - 768：经典，速度快。
> - 1024：**性价比最优**。
> - 1536 / 3072：高精度，存储 / 检索成本翻倍。
> - Matryoshka：可截断使用（如 OpenAI / BGE 支持）。
>
> **6. 输入长度**
> - 512 token：主流（BGE / E5）。
> - 8192 token：长文本（jina-v3、BGE-M3 部分模式）。
> - 必须与 chunk_size 匹配。
>
> **7. 推理速度**
> - 小模型（300M）：快，适合实时场景。
> - 大模型（7B）：慢但精度高。
> - 商业 API：延迟稳定但贵。
>
> **三、工程维度**
>
> **8. 部署方式**
> - API（OpenAI / Cohere）：免运维，按调用量付费。
> - 本地部署（BGE / E5）：需 GPU，长期更便宜。
> - 量化：int8 加速 2x，几乎无损。
>
> **9. 成本**
> - OpenAI：$0.02~$0.13 / 1M tokens。
> - Cohere / Voyage：类似量级。
> - 本地 BGE：仅 GPU 成本。
> - 大规模（> 1 亿 token）：本地部署省钱。
>
> **10. 生态集成**
> - LangChain / LlamaIndex 是否原生支持。
> - 向量库（Qdrant / Milvus）是否有官方 integration。
> - 工具链完善度。
>
> **四、安全 / 合规**
>
> **11. 数据出境**
> - 国内合规：选可本地部署的（BGE / M3E）。
> - 海外业务：OpenAI / Cohere / Voyage 任意。
>
> **12. License**
> - 商用必须 Apache 2.0 / MIT。
> - BGE / E5：Apache 2.0。
> - OpenAI / Cohere：商业 API，受条款约束。
>
> **五、可演进性**
>
> **13. 是否支持指令前缀**
> - BGE / E5 支持：query 加指令（如 `"Represent this for retrieval: "`），效果更好。
> - OpenAI 不需要。
>
> **14. 是否多功能**
> - BGE-M3：dense + sparse + ColBERT。
> - jina-v3：多任务（检索、分类、聚类）。
>
> **15. 微调友好**
> - 用自己数据微调：选有训练框架的（BGE / Sentence-Transformers）。
> - 不微调：直接选 SOTA 大模型（E5-mistral / BGE-M3）。
>
> **六、决策清单**
>
> | 优先级 | 问题 |
> |--------|------|
> | 1 | 主要语言？（中/英/多） |
> | 2 | 部署形态？（API / 本地） |
> | 3 | 数据合规？（能出境吗） |
> | 4 | 性能 vs 成本？（精度优先还是省钱） |
> | 5 | 是否需要微调？ |
> | 6 | 多功能需求？（混合检索、ColBERT） |
> | 7 | 上下文长度？（短 chunk / 长 chunk） |
> | 8 | 生态集成？（LangChain / 向量库支持） |
>
> **七、典型推荐**
>
> | 场景 | 推荐 |
> |------|------|
> | **中文 RAG（本地）** | BGE-M3 |
> | **中文 RAG（API）** | 智源 API / 阿里通义 |
> | **英文 RAG（API）** | OpenAI text-embedding-3-small |
> | **英文 RAG（本地）** | BGE-large-en / E5-large |
> | **多语言** | BGE-M3 / multilingual-E5 |
> | **多模态** | CLIP / BGE-VL |
> | **代码 RAG** | jina-code / Voyage Code |
> | **最高精度** | E5-mistral-7b / OpenAI text-embedding-3-large |
> | **超低成本** | BGE-small / sentence-transformers |
> | **国内合规** | BGE / M3E / 智源 API |
>
> **八、最佳实践**
>
> 1. **建立基线**：先用 BGE-M3 / OpenAI 3 跑 baseline。
> 2. **MTEB 评估**：在自己数据上跑 retrieval 评估。
> 3. **微调提升**：业务有领域数据时，微调 BGE / E5。
> 4. **混合检索**：dense + sparse + Reranker 三件套。
> 5. **定期评估**：recall@k、MRR、业务指标双轨监控。
>
> **总结**：选型核心是"**语言匹配 + 部署形态 + 性能 vs 成本**"三要素。**BGE-M3 是 2026 年最稳健的默认选择**（中文友好、多功能、开源、效果好）。

### [在 RAG 中，索引流程中的文档解析你们怎么做的？](https://www.mianshiya.com/bank/1906189461556076546/question/1913138628935540737)

> **答案**：
>
> **RAG 索引流程中的文档解析** 是质量基础——再强的检索算法也救不回垃圾输入。
>
> **一、解析难点**
>
> - **PDF**：版式复杂、表格错乱、扫描版、公式、图文混排。
> - **HTML**：navigation 噪声、script/style、广告。
> - **Word**：版本差异、嵌入对象、修订模式。
> - **Markdown**：层级结构、代码块、表格。
> - **多语言 / 编码**：UTF-8 / GBK 混杂、繁简、全半角。
> - **扫描版**：需要 OCR。
>
> **二、按文档类型的解析方案**
>
> **1. PDF 解析**
>
> | 工具 | 特点 | 适用 |
> |------|------|------|
> | **PyMuPDF (fitz)** | 快、稳、保留位置 | 纯文本 PDF（首选） |
> | **PyPDFLoader** | 简单 | 简单 PDF（不推荐生产） |
> | **pdfplumber** | 表格识别好 | 含表格 PDF |
> | **Camelot** | 表格抽取 | 表格为主 |
> | **UnstructuredPDFLoader** | DL 识别 layout | 复杂版式（推荐） |
> | **Nougat**（Meta） | 学术 PDF、公式 | 论文 |
> | **Marker** | PDF → Markdown，开源 | 高质量转换 |
> | **LlamaParse** | 商业，表格强 | 生产级复杂 PDF |
> | **AWS Textract / Google Document AI** | 云 OCR | 扫描版 |
>
> **最佳实践**：
> - 简单 PDF：PyMuPDF。
> - 复杂版式 / 表格：Unstructured 或 LlamaParse。
> - 扫描版：OCR（Tesseract / PaddleOCR / 云）。
> - 学术论文：Nougat。
>
> **2. HTML 解析**
> - **BeautifulSoup**：通用，但需要自定义去 nav/script。
> - **trafilatura**：自动提取正文，去噪强。
> - **Readability.js**：浏览器端的正文提取。
> - **Newspaper3k**：新闻文章专用。
> - **Selenium / Playwright**：动态渲染页面。
>
> **最佳实践**：trafilatura 一把梭，提取正文 + 去广告 + 去导航。
>
> **3. Word（.docx）**
> - **python-docx**：标准库。
> - **UnstructuredWordDocumentLoader**：保留格式。
> - **docx2txt**：简单粗暴。
> - 注意：旧 .doc 格式需要 LibreOffice 转换。
>
> **4. Markdown**
> - **直接读取**：保留 # 标题、列表、代码块。
> - **MarkdownHeaderTextSplitter**：按标题层级切分。
>
> **5. PPT**
> - **python-pptx**。
> - **UnstructuredPowerPointLoader**。
> - 注意：每页作为一个 chunk，加上 slide number。
>
> **6. Excel / CSV**
> - **openpyxl / pandas**。
> - **CSVLoader**：每行作为一个 Document。
> - **决策**：能 SQL 就 SQL，不要先转 Document。
>
> **7. EPUB / 电子书**
> - **EbookLib**。
> - 转 Markdown 后处理。
>
> **8. JSON**
> - **JSONLoader**：用 jq 语法定位字段。
>
> **9. 多模态（图、音、视频）**
> - **图**：CLIP / OCR / LLM 生成 caption。
> - **音频**：Whisper 转文字。
> - **视频**：抽帧 + 多模态 LLM 理解。
>
> **三、解析后的清洗**
>
> 1. **去噪**：删除页眉、页脚、页码、水印、navigation。
> 2. **归一化**：编码统一（UTF-8）、换行统一、空格合并。
> 3. **去重**：hash 去重、MinHash 近似去重。
> 4. **脱敏**：删除手机号、邮箱、身份证、IP。
> 5. **格式修复**：修复断行、断句、标点。
> 6. **繁简转换**（中文场景）：统一为简体或保留原貌。
>
> **四、结构化增强**
>
> 1. **保留标题层级**：作为 metadata，便于过滤。
> 2. **抽取表格**：保留为 Markdown / HTML，单独 chunk。
> 3. **图片处理**：
>    - OCR 提取文字。
>    - LLM 生成 caption。
>    - 作为独立 chunk 或附加到所属段落。
> 4. **抽取实体**：人名、地名、机构、时间，作为 metadata。
> 5. **生成摘要**：每段 LLM 生成 summary，作为额外 metadata。
> 6. **生成假设问题**：每段 LLM 生成"这段内容能回答什么问题"，作为召回优化（DPR 风格）。
>
> **五、典型 pipeline**
>
> ```python
> from langchain_community.document_loaders import PyMuPDFLoader, WebBaseLoader
> from langchain.text_splitter import RecursiveCharacterTextSplitter, MarkdownHeaderTextSplitter
> from langchain_community.document_transformers import (
>     BeautifulSoapTransformer,  # HTML 去噪
>     EmbeddingsRedundantFilter, # 去重
> )
>
> # 1. 加载
> docs = PyMuPDFLoader("book.pdf").load()
> # 或 UnstructuredPDFLoader / LlamaParse API
>
> # 2. 清洗（自定义）
> for d in docs:
>     d.page_content = clean_text(d.page_content)   # 去噪、归一化
>
> # 3. 切分
> md_splitter = MarkdownHeaderTextSplitter([("#", "chapter"), ("##", "section")])
> recursive_splitter = RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50)
>
> # 4. 二次切分（结构 → 小块）
> chunks = []
> for d in docs:
>     sections = md_splitter.split_text(d.page_content)
>     for s in sections:
>         chunks.extend(recursive_splitter.split_documents([s]))
>
> # 5. 元数据增强
> for c in chunks:
>     c.metadata["source"] = "..."
>     c.metadata["summary"] = llm.summarize(c.page_content)
>     c.metadata["questions"] = llm.generate_questions(c.page_content)
>
> # 6. 入库
> vectorstore = Qdrant.from_documents(chunks, embedder, ...)
> ```
>
> **六、生产经验**
>
> 1. **每种文档独立 pipeline**：PDF / HTML / Word 各自适配。
> 2. **抽样人工审查**：每批抽 100 条目检，质量把关。
> 3. **持续监控**：bad case 反哺清洗规则。
> 4. **商业方案优先**：LlamaParse / Marker 比开源强很多，复杂场景值得付费。
> 5. **多模态分离**：图、表、代码独立 chunk，避免被切碎。
> 6. **元数据是金子**：好的 metadata 让过滤召回质量翻倍。
>
> **七、典型坑**
>
> - **PyPDFLoader 解析表格错乱** → 改 PyMuPDF 或 Unstructured。
> - **HTML 抓回一堆 nav/script** → 用 trafilatura。
> - **扫描 PDF 直接 parse 全空** → 必须先 OCR。
> - **繁体中文检索差** → 统一转为简体或保留双语。
> - **表格被切散** → 表格独立 chunk，不切。
>
> **总结**：文档解析是 RAG 工程"看不见的 50%"。选对工具（PyMuPDF / Unstructured / LlamaParse）+ 清洗到位 + 结构化增强 + 元数据丰富，是高质量 RAG 的基础。

### [在 RAG 应用的过程中，关于提示工程的设计有什么心得和技巧吗？](https://www.mianshiya.com/bank/1906189461556076546/question/1914601776862990337)

> **答案**：
>
> **RAG 中提示工程的心得和技巧**：
>
> **一、Prompt 的核心结构**
>
> 一个生产级 RAG prompt 通常包含：
>
> ```
> [角色 / 系统指令]（system）
>   - 你是 XX 领域的助手。
>   - 你的目标是 XX。
>   - 你必须遵循 XX 规则。
>
> [约束 / 边界]（system / user）
>   - 仅根据提供的资料回答。
>   - 资料中没有的，回答"我不知道"。
>   - 不要编造、不要假设。
>   - 答案带 source 引用。
>
> [资料 / Context]（user）
>   - 资料 1（来源：xxx）：...
>   - 资料 2（来源：xxx）：...
>
> [问题]（user）
>   - 用户问题
>
> [输出格式]（system / user）
>   - 用 JSON / Markdown 输出。
>   - 包含 answer、sources、confidence。
> ```
>
> **二、关键技巧**
>
> **1. 强约束"忠于资料"**
> - "只能根据下面提供的资料回答，资料中没有的请回答'我不知道'"。
> - 这一条减少幻觉 80%。
>
> **2. 显式 source 引用**
> - "回答时必须在每个论点后标注来源 [资料 N]"。
> - 让答案可溯源。
>
> **3. 思维链（CoT）**
> - "先分析资料，再给出答案"。
> - 复杂问题用 CoT 提升准确率。
>
> **4. Few-shot 示例**
> - 给 2~3 个标准 (question, answer, sources) 示例。
> - 让模型学会格式和风格。
>
> **5. 结构化输出**
> - 用 `with_structured_output(PydanticModel)` 强制 schema。
> - 答案带 answer、sources、confidence。
>
> **6. 防御性指令**
> - "如果资料相互矛盾，请说明并标注"。
> - "如果用户问题不清晰，请提问澄清"。
> - "不要回答与资料无关的问题"。
>
> **7. 角色清晰**
> - "你是公司内部知识助手，仅回答公司相关问题"。
> - 防止越界。
>
> **8. 兜底策略**
> - "如果资料不足，请回答'资料不足，请联系 XX'"。
> - 比硬答更好。
>
> **三、典型模板**
>
> **模板 1：简洁版（客服 / FAQ）**
> ```
> 你是助手。仅根据以下资料回答问题。
>
> 资料：
> {context}
>
> 问题：{question}
>
> 要求：
> 1. 仅根据资料，不要编造。
> 2. 资料不足时回答"我不知道"。
> 3. 简洁回答（≤200 字）。
>
> 答案：
> ```
>
> **模板 2：引用版（医疗 / 法律 / 合规）**
> ```
> 你是法律顾问。基于以下法条回答问题。
>
> 法条：
> [1] 《XXX法》第N条：...
> [2] 《YYY条例》第M条：...
>
> 问题：{question}
>
> 要求：
> 1. 每个论点必须标注 [资料N] 引用。
> 2. 引用必须准确（不要张冠李戴）。
> 3. 如果法条未覆盖，明确说明"该问题需要专业律师评估"。
> 4. 输出格式：
>    答案：...
>    引用：[1][3]
>    注意事项：...
> ```
>
> **模板 3：思维链版（复杂推理）**
> ```
> 基于资料回答问题，按以下步骤：
>
> 资料：{context}
> 问题：{question}
>
> 步骤：
> 1. 提取问题关键词。
> 2. 在资料中找相关信息。
> 3. 分析信息是否能回答问题。
> 4. 给出答案 + 引用。
>
> 开始分析：
> ```
>
> **模板 4：结构化输出**
> ```python
> from pydantic import BaseModel, Field
>
> class RAGAnswer(BaseModel):
>     answer: str = Field(description="基于资料的答案")
>     sources: list[str] = Field(description="引用的资料 id 列表")
>     confidence: float = Field(ge=0, le=1, description="置信度")
>     needs_clarification: bool = Field(description="是否需要澄清")
>
> structured_llm = llm.with_structured_output(RAGAnswer)
> ```
>
> **四、进阶技巧**
>
> **1. 自我反思（Self-Reflection）**
> - 第一轮生成 → 第二轮让模型自检"是否忠于资料"。
> - LangGraph 实现自然的"生成 → 检查 → 修正"循环。
>
> **2. 多路生成 + 投票（Self-Consistency）**
> - 同一 prompt 多次采样（temperature=0.7）→ 投票。
> - 适合数学 / 逻辑题。
>
> **3. 路由（Router）**
> - 按问题类型路由到不同 prompt。
> - 闲聊、问答、代码、数学各自专用 prompt。
>
> **4. 动态 prompt**
> - 根据召回质量动态调整：
>   - 召回高质量：直接答。
>   - 召回低质量：让模型说"不确定"。
>   - 召回为空：fallback 提示。
>
> **5. 多语言适配**
> - 不同语言用不同 prompt（中文/英文模板差异）。
>
> **五、常见坑**
>
> 1. **prompt 太长**：信息淹没，模型记不住约束。
> 2. **few-shot 太多**：浪费 token、过拟合示例。
> 3. **没约束"忠于资料"**：幻觉严重。
> 4. **没要求 source**：答案无法溯源。
> 5. **温度太高**：抽取 / 事实题应 temperature=0。
> 6. **没测试边界**：拒答、敏感、超范围问题。
>
> **六、实战经验**
>
> 1. **从简洁版开始**：先跑通基线，再加约束。
> 2. **建立 prompt 版本管理**：LangSmith Prompt Hub。
> 3. **每次改 prompt 都评估**：Golden Set + RAGAS。
> 4. **A/B 测试**：新 prompt 灰度对比旧 prompt。
> 5. **few-shot 精选 2~3 个**：质量 > 数量。
> 6. **结构化输出**：用 `with_structured_output` 强制 schema。
> 7. **监控 prompt 长度分布**：太长要压缩（LLMLingua）。
>
> **总结**：RAG prompt 的灵魂是"**强约束忠于资料 + 显式引用 + 结构化输出**"。把这几点做扎实，RAG 质量就已经赢在起跑线。

### [什么是 Advanced RAG？](https://www.mianshiya.com/bank/1906189461556076546/question/1914614341832523778)

> **答案**：
>
> **Advanced RAG（进阶 RAG）** 是在 Naive RAG 基础上对**检索前、检索后、生成各环节**做精细化优化的 RAG 范式，由 Gao et al. (2023) 在综述中正式定义。
>
> **一、Naive RAG 的痛点**
>
> 经典 RAG：`document → chunk → embed → vector store → retrieve → LLM`，简单但问题多：
> - **检索前**：query 表述差、文档噪声多。
> - **检索中**：单一检索、ANN 不准。
> - **检索后**：召回冗余、上下文过长。
> - **生成**：幻觉、答非所问、无引用。
>
> **二、Advanced RAG 的三大优化环节**
>
> **1. Pre-Retrieval（检索前优化）**
> - **Query Rewriting**：改写 query（更清晰）。
> - **HyDE**：生成假设答案，用答案 embedding 检索。
> - **Multi-Query**：扩展为多个 query 并行检索。
> - **Query Decomposition**：复杂问题拆解。
> - **Step-Back**：抽象成更宽泛概念。
> - **Query Routing**：按问题类型路由到不同知识库。
>
> **2. Retrieval（检索过程优化）**
> - **Hybrid Search**：向量 + BM25 + RRF。
> - **Multi-Vector Retrieval**：summary / questions / chunk 多视角。
> - **Hierarchical Retrieval**：先粗筛后精筛。
> - **Re-rank during retrieval**：边检索边重排。
> - **Self-Query**：自动拆出 metadata 过滤条件。
> - **Time-weighted / Personalized**：加时间衰减、用户偏好。
>
> **3. Post-Retrieval（检索后优化）**
> - **Reranker**：Cross-Encoder 精排。
> - **Context Compression**：LLMLingua 等。
> - **Context Deduplication**：去重相似 chunks。
> - **Context Filtering**：按 score 阈值过滤。
> - **Context Re-ordering**：避免 Lost in the Middle（重要的放前后）。
> - **Parent Document Retrieval**：小检大生。
>
> **4. Generation（生成优化）**
> - **Strong Constraint Prompt**：忠于资料 + 引用。
> - **Self-Reflection / Self-Critique**：自检修正。
> - **CRAG / Self-RAG**：召回质量差时重检索。
> - **Structured Output**：JSON schema。
> - **Citation Enforcement**：强制 source 标注。
>
> **三、Advanced RAG vs Naive RAG**
>
> | 维度 | Naive RAG | Advanced RAG |
> |------|-----------|--------------|
> | Query 处理 | 直接用原 query | 改写、扩展、分解 |
> | 检索 | 单一向量 | 多路混合 + Reranker |
> | Context 处理 | 直接拼 | 压缩、去重、reorder |
> | 生成 | 简单 prompt | 强约束 + 自检 |
> | 评估 | 仅看答案 | RAGAS 全维度 |
> | 工程 | 几百行代码 | 完整管线 + 监控 |
> | 效果 | baseline | 提升 20~50% |
>
> **四、典型架构**
>
> ```
> query
>   │
>   ├─ [Pre-Retrieval]
>   │   ├─ Query Rewrite
>   │   ├─ HyDE
>   │   └─ Multi-Query
>   │
>   ├─ [Retrieval]
>   │   ├─ Vector Search (dense)
>   │   ├─ BM25 (sparse)
>   │   ├─ Self-Query (metadata filter)
>   │   └─ RRF 融合
>   │
>   ├─ [Post-Retrieval]
>   │   ├─ Reranker (Cross-Encoder)
>   │   ├─ Context Compression (LLMLingua)
>   │   ├─ Deduplication
>   │   └─ Re-ordering
>   │
>   ├─ [Generation]
>   │   ├─ Strong Constraint Prompt
>   │   ├─ CoT
>   │   ├─ Self-Reflection
>   │   └─ Structured Output
>   │
>   └─ [Evaluation]
>       ├─ RAGAS
>       ├─ LLM-as-Judge
>       └─ Business Metrics
> ```
>
> **五、何时升级到 Advanced RAG？**
>
> Naive RAG 在以下场景不够用：
> - 召回质量差（用户表述差异大）。
> - 答案幻觉严重。
> - 多源 / 多知识库。
> - 长 / 复杂文档。
> - 高精度要求（医疗、法律、金融）。
> - 多跳推理。
>
> **六、演进路径**
>
> **Naive RAG → Advanced RAG → Modular RAG → Agentic RAG**
>
> - **Modular RAG**：把上述各环节模块化，按需组合。
> - **Agentic RAG**：Agent 决策何时检索、检索哪个库、是否重检索。
>
> **七、生产化要点**
>
> 1. **不要一次全上**：先优化瓶颈环节。
> 2. **A/B 评估**：每加一个模块都量化收益。
> 3. **监控延迟和成本**：Advanced RAG 增加多次 LLM 调用。
> 4. **建立 Golden Set**：用回归测试保护。
> 5. **LangGraph 实现**：把流程图式化，便于调优。
>
> **总结**：Advanced RAG 是从"跑通"到"做好"的关键升级。核心是**"每个环节都做精细化优化"**——检索前、检索中、检索后、生成、评估全链路打磨。生产级 RAG 必须走 Advanced RAG 路线。

### [什么是 Modular RAG？](https://www.mianshiya.com/bank/1906189461556076546/question/1914621474818531330)

> **答案**：
>
> **Modular RAG（模块化 RAG）** 是 RAG 的第三代范式（继 Naive RAG、Advanced RAG 之后），核心思想是**把 RAG 拆成可配置、可组合、可替换的模块**，按场景灵活拼装。
>
> **一、核心思想**
>
> - **Naive RAG**：固定流程，一条道走到黑。
> - **Advanced RAG**：在固定流程上加优化点。
> - **Modular RAG**：流程本身可重构，模块可换、可加、可去、可循环。
>
> **二、典型模块**
>
> | 模块 | 作用 | 常见实现 |
> |------|------|---------|
> | **Indexing** | 文档解析、切分、向量化、入库 | Loader / Splitter / Embedder / VectorStore |
> | **Memory** | 长期记忆 / 实体记忆 / 摘要 | Vector Memory / Entity Memory |
> | **Router** | 路由决策 | LLM Router / 规则路由 |
> | **Retriever** | 检索器 | Vector / BM25 / Web / SQL / Tool |
> | **Pre-Retrieval** | Query 改写 / 扩展 / 分解 | Rewrite / HyDE / Multi-Query |
> | **Post-Retrieval** | Rerank / 压缩 / 去重 | Reranker / LLMLingua |
> | **Generation** | 生成答案 | LLM + Prompt |
> | **Self-Reflection** | 自检、纠错 | CRAG / Self-RAG |
> | **Citation** | 引用标注 | Source attribution |
> | **Evaluation** | 评估 | RAGAS / LLM-as-Judge |
> | **Orchestration** | 编排 | LangGraph / DSPy / Flow |
>
> **三、与传统 RAG 的区别**
>
> | 维度 | Naive / Advanced | Modular |
> |------|------------------|---------|
> | 流程 | 固定线性 | 灵活可配 |
> | 模块 | 强耦合 | 独立可替换 |
> | 扩展 | 改代码 | 加配置 / 插件 |
> | 复用 | 难 | 模块库复用 |
> | 调试 | 整链路 | 单模块单测 |
> | 场景适配 | 一套流程走天下 | 按需组合 |
>
> **四、典型架构（LangGraph 实现）**
>
> ```python
> from langgraph.graph import StateGraph, END
>
> class State(TypedDict):
>     query: str
>     rewritten_queries: list[str]
>     retrieved_docs: list
>     reranked_docs: list
>     answer: str
>     sources: list[str]
>
> def rewrite(state): ...        # Pre-Retrieval
> def retrieve(state): ...       # Multi-Route Retrieval
> def rerank(state): ...         # Post-Retrieval
> def generate(state): ...       # Generation
> def reflect(state): ...        # Self-Reflection
>
> g = StateGraph(State)
> g.add_node("rewrite", rewrite)
> g.add_node("retrieve", retrieve)
> g.add_node("rerank", rerank)
> g.add_node("generate", generate)
> g.add_node("reflect", reflect)
>
> g.add_edge("rewrite", "retrieve")
> g.add_edge("retrieve", "rerank")
> g.add_edge("rerank", "generate")
> g.add_edge("generate", "reflect")
> g.add_conditional_edges("reflect", lambda s: "retrieve" if s["needs_retry"] else END)
> g.set_entry_point("rewrite")
>
> app = g.compile(checkpointer=...)
> ```
>
> **五、Modular RAG 的优势**
>
> 1. **灵活组合**：按场景挑选模块（客服 / 法律 / 医疗各不同）。
> 2. **易迭代**：单模块升级不影响其他。
> 3. **可复用**：跨项目共享模块库。
> 4. **可观测**：单模块独立 trace、评估。
> 5. **可扩展**：新需求加新模块，不动旧逻辑。
>
> **六、典型变体**
>
> 1. **CRAG（Corrective RAG）**：检索质量评估 + 自纠错重检索。
> 2. **Self-RAG**：模型决定何时检索、检索什么。
> 3. **Adaptive RAG**：根据问题复杂度路由（单次检索 / 多次检索 / 不检索）。
> 4. **Agentic RAG**：Agent 主导，工具调用、多步推理。
> 5. **Multi-modal RAG**：图文音视频混合检索。
>
> **七、Modular RAG 的工程化**
>
> 1. **配置化**：YAML / JSON 定义流程。
> 2. **插件化**：每个模块符合统一接口（Runnable）。
> 3. **可观测**：LangSmith / Langfuse 全链路 trace。
> 4. **可评估**：每模块独立指标 + 端到端指标。
> 5. **版本化**：模块、配置、prompt 都版本管理。
>
> **八、演进路径**
>
> ```
> Naive RAG (2022)
>     ↓
> Advanced RAG (2023) - 精细化优化
>     ↓
> Modular RAG (2024) - 模块化、可组合
>     ↓
> Agentic RAG (2025+) - Agent 主导
> ```
>
> **总结**：Modular RAG 是 RAG 走向"软件工程化"的标志——**从单一流程到可组合架构**。LangGraph 是当前实现 Modular RAG 的事实标准。理解 Modular RAG 的关键不是记住几个模块名，而是建立"**模块化思维**"——把 RAG 当成一个软件系统而非一条流水线。

### [什么是护栏技术？](https://www.mianshiya.com/bank/1906189461556076546/question/1915309618303901698)

> **答案**：
>
> **护栏技术（Guardrails）** 是 LLM 应用中**在输入、输出、检索各环节加约束和检查**的安全/质量保障机制，防止模型越界、出错、违规。
>
> **一、为什么需要？**
>
> LLM 的固有问题：
> - **幻觉**：编造事实。
> - **越权**：回答不该答的问题（敏感、违法）。
> - **注入**：用户/检索内容注入恶意指令。
> - **数据泄露**：PII、API Key、商业机密。
> - **偏见 / 歧视**：性别、种族、地域。
> - **格式不稳**：JSON 解析失败、格式漂移。
> - **成本失控**：长 prompt、循环调用。
> - **法律合规**：GDPR、内容审核。
>
> **二、护栏的层级**
>
> **1. 输入护栏（Input Guardrails）**
> - **Prompt Injection 检测**：识别"忽略上面指令"、"扮演 DAN"等。
> - **PII 检测**：手机号、身份证、邮箱、卡号。
> - **敏感话题过滤**：政治、暴力、色情。
> - **长度 / 复杂度限制**：防 token 滥用。
> - **意图分类**：拒绝非业务问题。
>
> **2. 检索护栏（Retrieval Guardrails）**
> - **召回质量阈值**：cosine < 0.7 视为无召回。
> - **召回数量限制**：top-k 不超过 N。
> - **来源白名单**：仅可信文档可入 prompt。
> - **机密信息过滤**：从召回中删敏感数据。
>
> **3. 输出护栏（Output Guardrails）**
> - **格式校验**：JSON schema、必填字段。
> - **事实核查**：与召回内容比对（faithfulness）。
> - **毒性 / 偏见检测**：Perspective API、自训分类器。
> - **PII 脱敏**：mask 输出中的 PII。
> - **citation 校验**：确保引用真实存在。
> - **法律审核**：行业合规（医疗、金融）。
>
> **4. 行为护栏（Behavioral Guardrails）**
> - **拒答策略**：敏感问题统一回复。
> - **人审触发**：高风险操作转人工。
> - **rate limit**：单用户/单 IP 频率限制。
> - **审计日志**：所有请求留痕。
>
> **三、主流护栏框架**
>
> **1. NeMo Guardrails（NVIDIA）**
> - 开源，Python + Colang（DSL）。
> - 支持输入 / 输出 / 对话 / 检索 / 执行护栏。
> - 可定义"对话流程"，约束 bot 行为。
>
> **2. Guardrails AI**
> - 开源 Python 库。
> - Validator 验证 LLM 输出（格式、范围、合规）。
> - 与 Pydantic / LangChain 集成好。
>
> **3. Llama Guard（Meta）**
> - 开源模型，分类输入/输出是否违规。
> - 支持自定义安全类别。
>
> **4. OpenAI Moderation API**
> - 商业 API，免费额度。
> - 检测 hate、violence、sexual 等。
>
> **5. LangChain Guardrails / Output Parsers**
> - 集成 NeMo / Guardrails AI。
> - 用 OutputParser + Pydantic 做格式护栏。
>
> **6. 自研护栏**
> - 用小模型 + 规则做实时检查。
> - 适合有特定合规要求的场景。
>
> **四、典型实现**
>
> ```python
> # 输入护栏
> def input_guardrail(query):
>     if detect_injection(query): return "拒绝：检测到注入"
>     if has_pii(query): return "请删除个人信息后重试"
>     if is_sensitive(query): return "无法回答此类问题"
>     return None  # 放行
>
> # 检索护栏
> def retrieval_guardrail(docs):
>     docs = [d for d in docs if d.score > 0.7]
>     docs = [mask_pii(d) for d in docs]
>     return docs[:5]
>
> # 输出护栏
> def output_guardrail(answer, sources):
>     if not verify_citations(answer, sources):
>         return "无法验证引用，请重新生成"
>     if detect_toxicity(answer) > 0.8:
>         return "拒绝：输出违规"
>     return mask_pii(answer)
>
> # 主流程
> query = input(...)
> if guard := input_guardrail(query): return guard
> docs = retrieval_guardrail(retriever(query))
> answer = llm.generate(query, docs)
> return output_guardrail(answer, docs)
> ```
>
> **五、生产化要点**
>
> 1. **多层防御**：不要只靠一层，输入 + 检索 + 输出都要拦。
> 2. **快速失败**：检测到违规立即返回，节省成本。
> 3. **可观测**：所有拦截事件记录、监控、告警。
> 4. **可灰度**：新护栏先小流量验证。
> 5. **用户体验**：拒答要友好（"我无法回答，请联系 XX"）。
> 6. **持续更新**：新攻击手法不断出现，护栏规则要持续迭代。
>
> **六、典型场景**
>
> - **客服**：拒答竞品问题、不承诺、不引战。
> - **医疗**：不诊断、不开药、建议就医。
> - **金融**：不荐股、不预测、风险提示。
> - **法律**：仅普法、不代理、建议咨询律师。
> - **教育**：拒答代写、鼓励自主思考。
> - **政企**：意识形态合规、敏感词过滤。
>
> **七、护栏 vs Fine-tuning vs Prompt**
>
> - **Prompt**：行为约束，软性。
> - **Guardrails**：硬性技术拦截，最后一道防线。
> - **Fine-tuning**：内化行为，长期有效。
> - **三者结合**：Prompt 教、Guardrails 拦、Fine-tune 烙。
>
> **八、总结**
>
> 护栏技术是 LLM 应用走向生产的**必经环节**：
> 1. **多层防御**：输入 / 检索 / 输出 / 行为。
> 2. **主流框架**：NeMo Guardrails、Guardrails AI、Llama Guard。
> 3. **关键场景**：合规、安全、质量。
> 4. **持续运营**：监控、更新、迭代。
>
> **核心理念**：**LLM 是不可靠组件，护栏是工程化的"安全带 + 刹车"**——不能指望模型永远对，但可以保证它不会"出大错"。

### [什么是 GPTCache？](https://www.mianshiya.com/bank/1906189461556076546/question/1915316449118035969)

> **答案**：
>
> **GPTCache** 是一个**为 LLM 应用提供语义缓存**的开源库，让相同/相似 query 直接命中缓存、不再调用 LLM。
>
> **一、为什么需要？**
>
> LLM 应用的痛点：
> - **成本高**：每次调用 $0.01~$0.1。
> - **延迟长**：1~10 秒。
> - **重复 query 多**：FAQ、常见问题、客服场景 30~70% 是重复。
>
> **缓存价值**：
> - 命中缓存 → 0 成本 + 毫秒级响应。
> - 大幅降低 LLM API 成本（节省 30~70%）。
> - 提升用户体验（首 token 时间 < 100ms）。
>
> **二、传统缓存的局限**
>
> - Key 必须**完全相同**（hash 匹配）。
> - 用户表述差异（"你好" / "您好" / "嗨"）→ 缓存失效。
> - 不适合开放性 LLM 场景。
>
> **三、GPTCache 的核心：语义缓存**
>
> - 用 **embedding 相似度**判断"是否同一问题"。
> - 即使表述不同，只要语义相近就命中。
> - 流程：
>   ```
>   query → embedding → 与缓存中所有 query embedding 比较
>         → 相似度 > 阈值 → 返回缓存的 answer
>         → 相似度 < 阈值 → 调用 LLM → 缓存结果
>   ```
>
> **四、架构**
>
> ```
> [User Query]
>      │
>      ▼
> [Embedding Model]（如 BGE / OpenAI）
>      │
>      ▼
> [Similarity Search]（向量库 SQLite / FAISS）
>      │
>      ├─ 命中（cosine > threshold）→ 返回 cached answer
>      │
>      └─ 未命中 → 调用 LLM → 写入缓存
> ```
>
> **五、核心组件**
>
> 1. **Embedding 模型**：把 query 转向量（默认 Onnx / OpenAI）。
> 2. **Cache Store**：存 query embedding + answer。
> 3. **Similarity Evaluator**：判断是否命中（余弦、阈值）。
> 4. **LLM Adapter**：兼容 OpenAI / LangChain / Anthropic 等。
>
> **六、使用示例**
>
> ```python
> from gptcache import cache
> from gptcache.adapter import openai  # 替换 openai
> from gptcache.embedding import Onnx
> from gptcache.manager import manager_factory
> from gptcache.similarity_evaluation import SearchDistanceEvaluation
>
> # 初始化
> onnx = Onnx(model_name="all-MiniLM-L6-v2")
> data_manager = manager_factory(
>     "sqlite,faiss",
>     data_dir="cache",
>     vector_params={"dimension": onnx.dimension}
> )
> cache.init(
>     embedding_func=onnx.to_embeddings,
>     data_manager=data_manager,
>     similarity_evaluation=SearchDistanceEvaluation(),
> )
>
> # 用法（与 openai 一致）
> response = openai.ChatCompletion.create(
>     model="gpt-4",
>     messages=[{"role": "user", "content": "什么是 RAG?"}],
> )
> # 第一次：调用 OpenAI。
> # 第二次相同/相似 query：直接返回缓存。
> ```
>
> **七、关键参数**
>
> - **similarity_threshold**：相似度阈值（0.8 默认）。太低 → 误命中（不同问题返回相同答案）；太高 → 命中率低。
> - **storage**：SQLite（默认）/ Postgres / Redis / DuckDB。
> - **vector_store**：FAISS（默认）/ Chroma / Milvus / Qdrant。
> - **TTL**：缓存过期时间。
> - **max_cache_size**：上限。
>
> **八、生产化要点**
>
> 1. **阈值调优**：根据业务调，FAQ 场景可低（0.7），开放问答要高（0.9）。
> 2. **场景隔离**：不同业务（客服 / 推荐 / 问答）独立缓存。
> 3. **失效策略**：
>    - 文档更新 → 相关缓存失效。
>    - 模型升级 → 全量失效。
>    - 时间敏感 → TTL。
> 4. **监控命中率**：
>    - 命中率 < 20%：阈值过高或 query 多样。
>    - 命中率 > 80%：正常，效果好。
> 5. **隐私脱敏**：缓存中可能含 PII，需加密或脱敏。
> 6. **缓存污染防护**：错误答案进缓存会被反复返回，加质量过滤。
>
> **九、典型场景**
>
> - **客服 Bot**：30~70% 重复问题，节省巨大。
> - **FAQ 系统**：基本所有 query 都可缓存。
> - **翻译**：相同原文直接命中。
> - **代码助手**：相同代码问题。
> - **教育辅导**：相同题目。
>
> **不适合**：
> - 创意写作（每次需不同）。
> - 实时数据查询（股票、天气）。
> - 个性化推荐。
>
> **十、与 LangChain 集成**
>
> ```python
> from langchain.globals import set_llm_cache
> from langchain.cache import SQLiteCache
> # 或 GPTCache LangChain integration
>
> set_llm_cache(GPTCache(...))
> ```
>
> LangChain 也内置 `InMemoryCache`、`SQLiteCache`、`RedisCache`、`UpstashRedisCache`。
>
> **十一、进阶**
>
> - **分级缓存**：
>   - L1：精确匹配（hash）。
>   - L2：语义匹配（embedding）。
>   - L3：fallback 到 LLM。
> - **预热缓存**：常见问题提前写入。
> - **A/B 测试**：缓存 vs 不缓存对比效果。
> - **学习型缓存**：根据用户反馈调整阈值。
>
> **总结**：GPTCache 是 LLM 应用**降本增效**的利器，特别适合 FAQ、客服等高重复场景。配合 LangChain 全局缓存，几乎零代码改动就能集成。**生产 RAG / Chat 应用必加**。

### [大模型的结构化输出指的是什么？](https://www.mianshiya.com/bank/1906189461556076546/question/1916425465556885506)

> **答案**：
>
> **结构化输出** 指 LLM 输出**符合预定义 schema（如 JSON Schema / Pydantic）的数据**，而非自由文本。
>
> **一、为什么重要？**
>
> LLM 默认输出自由文本，但下游程序需要：
> - **JSON / XML / YAML**：程序消费。
> - **特定字段**：name / age / email / items。
> - **类型约束**：int / float / list / enum。
> - **范围约束**：age ≥ 0、confidence ∈ [0,1]。
>
> 自由文本 → 程序解析困难、易错。结构化输出 → 可直接 `json.loads()` / Pydantic 校验。
>
> **二、实现方式**
>
> **1. Prompt 提示**
> - "请用 JSON 格式回答，包含字段：name, age"。
> - **不可靠**：模型可能漏字段、加多余字段、格式漂移。
>
> **2. Output Parser**
> - LangChain 的 PydanticOutputParser / JsonOutputParser。
> - 在 prompt 中自动注入 format_instructions。
> - 失败时尝试修复（OutputFixingParser）。
>
> **3. Function Calling / Tool Calling**
> - 让模型"调用一个工具"，工具的 schema 就是输出 schema。
> - OpenAI / Anthropic / Qwen 都支持。
> - 模型原生训练过 schema 遵循，**最可靠**。
>
> **4. JSON Mode**
> - OpenAI `response_format={"type": "json_object"}`。
> - 强制输出合法 JSON（但 schema 自定义）。
>
> **5. Structured Output（OpenAI 2024+）**
> - `response_format={"type": "json_schema", "json_schema": {...}}`。
> - 严格遵循 schema，违规会被模型自纠。
>
> **6. with_structured_output（LangChain）**
> ```python
> from pydantic import BaseModel, Field
>
> class Person(BaseModel):
>     name: str
>     age: int = Field(ge=0)
>     email: str | None = None
>
> structured_llm = llm.with_structured_output(Person)
> result = structured_llm.invoke("张三 30 岁，邮箱 zhang@x.com")
> # result: Person(name="张三", age=30, email="zhang@x.com")
> ```
>
> **三、应用场景**
>
> 1. **信息抽取**：从合同抽 (party, amount, date, terms)。
> 2. **数据 ETL**：非结构化 → 结构化（简历 → 字段）。
> 3. **API 调用参数**：自然语言 → API 参数。
> 4. **RAG 答案**：`{answer, sources, confidence}`。
> 5. **Tool Calling**：function 参数生成。
> 6. **多 Agent 通信**：消息格式标准化。
> 7. **表格 / 报表**：Markdown / CSV 输出。
> 8. **数据增强**：生成训练数据。
>
> **四、最佳实践**
>
> 1. **用 with_structured_output**：底层走 function calling，最可靠。
> 2. **Pydantic Schema 设计**：
>    - 字段名清晰、加 description。
>    - 用 Enum 限制枚举值。
>    - 用 `Optional` 处理缺失。
> 3. **Few-shot 示例**：复杂 schema 加示例。
> 4. **温度=0**：抽取/分类任务降温，提高一致性。
> 5. **失败兜底**：解析失败 → 重试 / fallback / 转人工。
> 6. **测试边界**：空值、超长、特殊字符。
>
> **五、常见坑**
>
> - **schema 太复杂**：模型学不会，输出乱。
> - **字段太抽象**：description 不清晰。
> - **没用 function calling**：仅靠 prompt 不稳。
> - **没处理 None**：Optional 字段必须用 Optional。
> - **没 fallback**：解析失败就崩。
>
> **六、进阶**
>
> - **Pydantic v2**：性能更好、校验更强。
> - **Instructor**：Python 库，强制结构化输出。
> - **Marvin**：类似 Instructor。
> - **Outlines / Guidance**：底层 token 级约束（强制 grammar）。
>
> **总结**：结构化输出是 LLM 走向生产的关键技术，让"自然语言 → 程序可消费数据"变得可靠。**用 with_structured_output + Pydantic + function calling** 是 2026 年最稳健的方案。

### [什么是 GPT Structured Outputs？](https://www.mianshiya.com/bank/1906189461556076546/question/1915324657713266690)

> **答案**：
>
> **GPT Structured Outputs** 是 OpenAI 2024 年 8 月推出的功能，让 GPT-4o / GPT-4o-mini 等**严格遵循 JSON Schema 输出**，不会漏字段、不会类型错、不会多余内容。
>
> **一、与传统 JSON Mode 的区别**
>
> | 功能 | JSON Mode | Structured Outputs |
> |------|-----------|-------------------|
> | 保证 | 合法 JSON | **严格遵循 schema** |
> | Schema | 不指定 | 任意 JSON Schema |
> | 字段必填 | 不保证 | **保证** |
> | 类型 | 不保证 | **保证** |
> | 枚举 | 不保证 | **保证** |
> | 模型 | 全部 | GPT-4o-2024-08-06+ |
>
> **示例**：
> ```python
> from pydantic import BaseModel
>
> class Person(BaseModel):
>     name: str
>     age: int
>
> response = client.beta.chat.completions.parse(
>     model="gpt-4o-2024-08-06",
>     messages=[{"role":"user", "content":"张三 30 岁"}],
>     response_format=Person,
> )
> person = response.choices[0].message.parsed
> # Person(name="张三", age=30)
> ```
>
> 底层用 Constrained Decoding（受限解码），在生成时直接屏蔽不符合 schema 的 token，**100% 保证结构**。
>
> **二、应用场景**
>
> 1. **信息抽取**：合同、简历、医疗病历 → 结构化字段。
> 2. **数据 ETL**：非结构化 → 数据库。
> 3. **Agent Tool Calling**：function 参数。
> 4. **RAG 答案**：`{answer, sources, confidence}`。
> 5. **多模态**：图 → 结构化描述。
> 6. **API 适配**：自然语言 → API 请求。
>
> **三、优势**
>
> 1. **100% 保证结构**：节省解析容错代码。
> 2. **省 token**：模型不需输出"我来帮你..."废话。
> 3. **下游友好**：直接 `json.loads()` / Pydantic 校验。
> 4. **减少幻觉**：结构化迫使模型严谨。
>
> **四、限制**
>
> 1. **schema 复杂度**：极复杂 schema（深嵌套 + 复杂 enum）模型可能拒答。
> 2. **first token 延迟略增**：需要先解析 schema。
> 3. **额外成本**：在某些 API 计费中略贵。
> 4. **温度固定**：部分实现强制 temperature=0。
>
> **五、其他厂商**
>
> - **Anthropic**：用 tool_use 实现类似（schema = tool 参数）。
> - **Google Gemini**：响应模式（response_schema）。
> - **Qwen / DeepSeek**：支持 function calling，等价于结构化输出。
> - **开源**：Outlines / Guidance / Instructor 实现 constrained decoding。
>
> **六、最佳实践**
>
> 1. **schema 简洁**：字段 ≤ 20 个，嵌套 ≤ 3 层。
> 2. **description 清晰**：每个字段加描述。
> 3. **用 Enum**：枚举值明确。
> 4. **Optional 处理缺失**：`Optional[str] = None`。
> 5. **Few-shot 示例**（如果 schema 复杂）。
> 6. **测试边界**：空输入、特殊字符。
>
> **七、与 LangChain 集成**
>
> ```python
> from langchain_openai import ChatOpenAI
> from pydantic import BaseModel
>
> llm = ChatOpenAI(model="gpt-4o-2024-08-06")
> structured_llm = llm.with_structured_output(Person)
> result = structured_llm.invoke("张三 30 岁")
> ```
>
> LangChain 抽象屏蔽了底层差异，可在 OpenAI / Anthropic / Qwen 间无缝切换。
>
> **总结**：GPT Structured Outputs 是 OpenAI 把"结构化输出"做成了"零代码、零错误"的标配，是 LLM 走向生产的里程碑。**所有需要程序消费 LLM 输出的场景都应该用 Structured Outputs**，告别字符串解析时代。

### [当发现RAG系统召回结果与用户query意图不匹配时，有哪些可能的改进方向？](https://www.mianshiya.com/bank/1906189461556076546/question/1906314515354787842)

> **答案**：
>
> **RAG 召回与用户 query 意图不匹配**是常见问题，可从多角度改进：
>
> **一、分析原因（先定位）**
>
> 1. **Query 表述问题**
>    - 模糊、错别字、多义、口语化。
> 2. **Embedding 模型不匹配**
>    - 模型对该领域/语言不敏感。
> 3. **文档/Chunk 切分不当**
>    - 信息被切断、关键内容散落。
> 4. **召回策略单一**
>    - 仅向量检索，无关键词兜底。
> 5. **元数据缺失**
>    - 无法过滤无关内容。
> 6. **意图理解错**
>    - 用户问 X，召回 Y。
>
> **二、改进方向（按优先级）**
>
> **1. Query 优化（Pre-Retrieval）**
>
> - **Query Rewriting**：LLM 改写 query 更清晰。
> - **Multi-Query**：扩成多个 query 并行检索 + RRF。
> - **HyDE**：LLM 生成假设答案 → 用答案 embedding 检索。
> - **Query Decomposition**：复杂问题拆子问题。
> - **Query Classification**：先分类意图，再走对应 pipeline。
> - **纠错**：错别字 / 拼音 / 同音字修正。
>
> **2. Embedding 模型优化**
>
> - **换更强的模型**：BGE-M3、OpenAI text-embedding-3、E5-mistral。
> - **微调**：用业务数据微调 embedding（对比学习 + hard negative）。
> - **多 embedding 融合**：dense + sparse + ColBERT（BGE-M3）。
> - **指令前缀**：BGE/E5 加 query 指令前缀。
>
> **3. 文档 / 切分优化**
>
> - **重新切分**：chunk_size、overlap、结构化切分。
> - **Parent Document**：小检大生。
> - **语义切分**（Semantic Chunking）。
> - **元数据增强**：title、section、summary、questions、keywords。
> - **生成假设问题**：每 chunk 让 LLM 生成"这段能回答什么问题" → 加入索引。
>
> **4. 检索策略优化**
>
> - **混合检索**：向量 + BM25 + RRF。
> - **Reranker**：bge-reranker-v2-m3 精排。
> - **Self-Query**：拆出 metadata 过滤条件。
> - **多路召回**：不同库、不同 embedding、不同 chunk 级别。
> - **元数据过滤**：时间、来源、tenant、permission。
>
> **5. 意图理解（Router）**
>
> - **意图分类**：LLM 或小模型分类问题类型。
> - **路由到对应 pipeline**：
>   - 闲聊 → 直接答。
>   - FAQ → 精确匹配。
>   - 知识库 → RAG。
>   - 时效性 → 联网搜索。
>   - 结构化 → Text-to-SQL。
> - **LangGraph 条件边**实现。
>
> **6. 评估与迭代**
>
> - **建立 Golden Set**：100~500 题标注 ground truth。
> - **多指标评估**：Recall@k、MRR、答案准确率。
> - **bad case 分析**：每个失败案例定位原因。
> - **A/B 测试**：新策略对比旧策略。
>
> **三、典型改进路径**
>
> ```
> v0：单向量检索 → baseline
> v1：+ BM25 混合 + RRF → recall 提升
> v2：+ Reranker（bge-reranker）→ precision 提升
> v3：+ Multi-Query 改写 → 抗表述差异
> v4：+ 元数据过滤 + Self-Query → 业务条件查询
> v5：+ 意图路由（LangGraph）→ 场景适配
> v6：+ 微调 embedding → 领域适配
> ```
>
> **四、常见场景的针对性方案**
>
> **场景 1：用户表述多样**
> - 加 Multi-Query + HyDE。
> - 微调 embedding 用同义改写数据。
>
> **场景 2：专业术语召回差**
> - 加术语词典 + BM25 兜底。
> - 微调 embedding 用领域数据。
>
> **场景 3：长文档召回碎片化**
> - Parent Document Retrieval。
> - 重新切分 + 摘要索引。
>
> **场景 4：多跳问题**
> - Query Decomposition。
> - Agentic RAG。
>
> **场景 5：意图歧义**
> - Router 分类 + 子 pipeline。
> - 让 LLM 主动澄清。
>
> **五、工具链**
>
> - **LangChain**：MultiQueryRetriever、HyDE、EnsembleRetriever、SelfQueryRetriever、ContextualCompressionRetriever。
> - **LangGraph**：意图路由、Agent 决策。
> - **RAGAS**：评估指标。
> - **LangSmith**：trace + 调试。
>
> **六、总结**
>
> RAG 召回质量问题没有"一招鲜"，需要**系统化诊断 + 多管齐下**：
> 1. **诊断**：先分析 bad case，定位瓶颈。
> 2. **改 query**：Multi-Query / HyDE / Rewrite。
> 3. **改 embedding**：换模型 / 微调。
> 4. **改检索**：混合 + Reranker + 元数据。
> 5. **改架构**：Router / Agentic RAG。
> 6. **持续评估**：Golden Set + A/B。
>
> **核心理念**：RAG 是系统工程，**不要追求单一银弹，要建立可迭代、可评估的管线**。

### [如何优化 RAG 的检索效果？](https://www.mianshiya.com/bank/1906189461556076546/question/1929742545445736450)

> **答案**：
>
> **优化 RAG 检索效果** 的系统化清单（按性价比排序）：
>
> **一、低成本（立竿见影）**
>
> 1. **加 Reranker**
>    - bge-reranker-v2-m3。
>    - 召回 top-20 → Rerank → top-5。
>    - **几乎必做**，提升 10~30%。
>
> 2. **混合检索**
>    - 向量 + BM25 + RRF。
>    - 解决关键词 / 语义盲区。
>    - **生产标配**。
>
> 3. **chunk_size 调优**
>    - 默认 500，根据场景调（300~1000）。
>    - 配合 overlap 10%~20%。
>
> 4. **元数据过滤**
>    - 时间、来源、tenant、permission。
>    - 减少无关召回。
>
> 5. **召回数量调优**
>    - top-k=10 召回 → top-5 Rerank。
>
> **二、中等成本（显著提升）**
>
> 6. **Query 改写**
>    - Multi-Query：扩展多个表述。
>    - HyDE：假设答案 embedding。
>    - Rewrite-Retrieve-Read。
>
> 7. **更强大的 Embedding**
>    - BGE-M3 / OpenAI text-embedding-3 / E5-mistral。
>    - 看场景选对模型。
>
> 8. **结构化切分**
>    - Markdown Header、代码、表格独立 chunk。
>    - 保留章节 metadata。
>
> 9. **Parent Document Retrieval**
>    - 小块检索、大块生成。
>    - 平衡精准与上下文。
>
> 10. **Self-Query**
>     - 拆出 metadata 过滤条件。
>     - 解决"语义 + 结构化条件"查询。
>
> 11. **相似度阈值**
>     - 设定 cosine 下限，过滤低质召回。
>     - 数据驱动调优。
>
> **三、高成本（深度优化）**
>
> 12. **微调 Embedding**
>     - 用业务数据对比学习。
>     - 加 hard negative。
>     - 召回质量大幅提升。
>
> 13. **语义切分（Semantic Chunking）**
>     - 用 embedding 相似度判断断点。
>     - 质量高但慢。
>
> 14. **Agentic RAG**
>     - LangGraph 实现。
>     - Agent 决策何时检索、检索哪个库、是否重检索。
>
> 15. **多向量索引**
>     - 每 chunk 生成多个 embedding（原文、摘要、问题）。
>     - MultiVectorRetriever。
>
> 16. **CRAG / Self-RAG**
>     - 召回质量评估 + 自纠错重检索。
>
> **四、架构级优化**
>
> 17. **意图路由**
>     - 按问题类型走不同 pipeline。
>     - LangGraph 条件边。
>
> 18. **多知识库协同**
>     - 不同库独立索引 + 路由。
>     - 减少单库规模。
>
> 19. **记忆模块**
>     - 长期记忆 / 实体记忆。
>     - 减少重复检索。
>
> **五、评估与监控**
>
> 20. **Golden Set**
>     - 100~500 题人工标注。
>     - 持续维护更新。
>
> 21. **RAGAS 评估**
>     - faithfulness、context_precision、context_recall、answer_relevancy。
>
> 22. **LangSmith / Langfuse**
>     - 全链路 trace。
>     - 定位 bad case。
>
> 23. **在线 A/B**
>     - 新策略灰度对比。
>
> 24. **反馈飞轮**
>     - 用户 👍/👎 → 反哺数据 → 优化。
>
> **六、不同场景的重点**
>
> | 场景 | 重点优化 |
> |------|---------|
> | 通用知识库 | 混合检索 + Reranker + Multi-Query |
> | 法律 / 医疗 | Parent Document + 微调 embedding + Reranker |
> | 代码库 | 代码切分 + 专门 embedding（jina-code） |
> | 客服 FAQ | Self-Query + 时间过滤 + 元数据 |
> | 多语言 | BGE-M3 + 多语言 Reranker |
> | 长文档 | Parent Document + 摘要索引 |
> | 多跳推理 | Agentic RAG + Query Decomposition |
>
> **七、典型优化路径（性价比从高到低）**
>
> ```
> 1. 加 Reranker（必做）
> 2. 混合检索（必做）
> 3. chunk + 元数据优化
> 4. Query 改写
> 5. 换 / 微调 embedding
> 6. Parent Document / 语义切分
> 7. 意图路由
> 8. Agentic RAG
> ```
>
> **八、常见误区**
>
> - **过度优化**：还没建 baseline 就堆复杂技术。
> - **不评估**：凭感觉改，没数据支撑。
> - **不诊断**：不知道 bad case 是哪种问题。
> - **求银弹**：想用一个技术解决所有问题。
>
> **九、总结**
>
> RAG 检索优化是**系统工程**：
> 1. **先建 baseline + 评估**。
> 2. **诊断 bad case**：定位瓶颈。
> 3. **按性价比排序**：先低成本，再高成本。
> 4. **小步迭代**：每次改一个点 + A/B 评估。
> 5. **持续运营**：Golden Set + 反馈飞轮。
>
> **核心理念**：**没有评估就没有改进**。先把评估管线搭好，再谈优化。

### [RAG 的文档处理流程是怎样的？](https://www.mianshiya.com/bank/1906189461556076546/question/1929742302566273026)

> **答案**：
>
> **RAG 文档处理流程**（生产级）：
>
> ```
> 原始文档（PDF / HTML / Word / Markdown / 多模态）
>     │
>     ▼ 〔1. 加载〕
> DocumentLoader → Document（page_content + metadata）
>     │
>     ▼ 〔2. 清洗〕
> 去噪 / 归一化 / 去重 / 脱敏 / 繁简转换
>     │
>     ▼ 〔3. 解析结构〕
> 提取标题层级、表格、图片、代码块
>     │
>     ▼ 〔4. 切分（Chunking）〕
> RecursiveCharacterTextSplitter / MarkdownHeader / Semantic
> chunk_size=500, overlap=50
>     │
>     ▼ 〔5. 元数据增强〕
> - 保留 source / title / section / page
> - 生成 summary（LLM）
> - 生成假设问题（LLM）
> - 抽取关键词、实体
>     │
>     ▼ 〔6. 向量化（Embedding）〕
> BGE-M3 / OpenAI text-embedding-3
>     │
>     ▼ 〔7. 入库（Indexing）〕
> Qdrant / Milvus（HNSW 索引） + 元数据
> ```
>
> **一、加载（Loading）**
>
> 按文档类型选 Loader：
> - PDF：PyMuPDFLoader / UnstructuredPDFLoader / LlamaParse。
> - HTML：WebBaseLoader + trafilatura / BeautifulSoup。
> - Word：python-docx / UnstructuredWordDocumentLoader。
> - Markdown：直接读，保留层级。
> - 代码：Tree-sitter / CodeSplitter。
> - Excel：CSVLoader / openpyxl。
> - 多模态：Unstructured / LLM 视觉模型。
>
> **二、清洗（Cleaning）**
>
> 1. **去噪**：
>    - 删页眉、页脚、页码、水印。
>    - 删 nav、script、style、广告。
>    - 删控制字符、零宽字符。
> 2. **归一化**：
>    - 统一编码 UTF-8。
>    - 统一换行 \r\n → \n。
>    - 全半角统一。
>    - 标点修正。
> 3. **去重**：
>    - hash 完全去重。
>    - MinHash 近似去重。
> 4. **脱敏**：
>    - 删 / 替换手机号、邮箱、身份证、IP、卡号。
>    - 用 Presidio / 正则 / NER。
> 5. **繁简转换**（中文）：统一为简体或保留双语。
>
> **三、解析结构（Structure Parsing）**
>
> - **标题层级**：保留 H1/H2/H3 作为 metadata。
> - **表格**：保留为 Markdown / HTML，独立 chunk。
> - **图片**：OCR + LLM caption。
> - **代码块**：独立保留，不切散。
> - **列表 / 引用**：保留格式。
>
> **四、切分（Chunking）**
>
> 策略选择：
> - **Recursive Character**（默认）：`separators=["\n\n", "\n", "。", "！", "？", "，", " ", ""]`。
> - **Markdown Header**：按 # / ## / ### 切，保留章节 metadata。
> - **Code Splitter**：按函数 / 类切。
> - **Semantic Chunking**：embedding 相似度判断断点。
>
> 参数：
> - `chunk_size`：500（默认）。
> - `overlap`：10%~20%。
> - 表格、代码不切散。
>
> **五、元数据增强（Metadata Enrichment）**
>
> 每 chunk 加：
> - `source`：URL / 文件路径。
> - `title`、`section`、`page`、`page_range`。
> - `author`、`date`、`version`。
> - `tenant_id`、`permission_level`。
> - `tags`、`category`。
> - `summary`（LLM 生成）。
> - `questions`（LLM 生成"这段能回答什么问题"）。
> - `keywords`（TF-IDF / KeyBERT 抽取）。
> - `entities`（NER 抽取人名、地名、机构）。
>
> **元数据价值**：让 Self-Query、过滤召回、Reranking 都受益。
>
> **六、向量化（Embedding）**
>
> - 选模型：BGE-M3（推荐）/ OpenAI text-embedding-3。
> - 与 chunk_size 匹配（max_length）。
> - 加 query 指令前缀（如 BGE/E5）。
> - L2 归一化（cosine）。
>
> **七、入库（Indexing）**
>
> - 选向量库：Qdrant / Milvus / Chroma / pgvector。
> - 选索引：HNSW（默认）/ IVF-PQ（大规模）。
> - 配置元数据 schema。
> - 批量插入（10x 单条）。
> - 异步索引（写入即可查）。
>
> **八、生产化要点**
>
> 1. **每种文档独立 pipeline**。
> 2. **抽样人工审查**：每批 100 条目检。
> 3. **持续监控**：bad case 反哺规则。
> 4. **数据治理**：
>    - 文档版本化（doc_id + version）。
>    - 来源追溯。
>    - 删除策略（TTL、合规删除）。
> 5. **增量更新**：CDC + upsert + 定期重建。
>
> **九、典型代码**
>
> ```python
> from langchain_community.document_loaders import PyMuPDFLoader
> from langchain.text_splitter import RecursiveCharacterTextSplitter, MarkdownHeaderTextSplitter
> from langchain_community.vectorstores import Qdrant
> from langchain_openai import OpenAIEmbeddings, ChatOpenAI
> from presidio_analyzer import AnalyzerEngine
>
> # 1. 加载 + 清洗
> docs = PyMuPDFLoader("book.pdf").load()
> analyzer = AnalyzerEngine()
> for d in docs:
>     d.page_content = clean_text(d.page_content)
>     d.page_content = mask_pii(d.page_content, analyzer)
>
> # 2. 切分
> md_splitter = MarkdownHeaderTextSplitter([("#","chapter"),("##","section")])
> recursive = RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50)
>
> chunks = []
> for d in docs:
>     sections = md_splitter.split_text(d.page_content)
>     for s in sections:
>         chunks.extend(recursive.split_documents([s]))
>
> # 3. 元数据增强
> llm = ChatOpenAI(temperature=0)
> for c in chunks:
>     c.metadata["summary"] = llm.invoke(f"一句话总结：{c.page_content[:500]}").content
>
> # 4. 入库
> vs = Qdrant.from_documents(chunks, OpenAIEmbeddings(), url="...", collection_name="kb")
> ```
>
> **十、总结**
>
> RAG 文档处理是"**看不见的 50%**"工程，决定 RAG 上限：
> 1. **Loader 选对**：PyMuPDF / Unstructured / LlamaParse。
> 2. **清洗到位**：去噪、归一化、脱敏、去重。
> 3. **结构化切分**：保留章节、表格、代码。
> 4. **元数据丰富**：source、section、summary、questions、entities。
> 5. **持续迭代**：bad case 反哺规则。
>
> **核心理念**：**Garbage In, Garbage Out**——再强的检索算法也救不回垃圾输入。文档处理是 RAG 工程最值得投入的环节。

### [你有多个知识库，做 RAG 的时候，怎么保证查询效率和准确性兼容，并尽可能减少幻觉？](https://www.mianshiya.com/bank/1906189461556076546/question/1935980352104529921)

> **答案**：
>
> **多知识库 RAG 的查询效率、准确性、防幻觉**：
>
> **一、核心挑战**
>
> 1. **查询效率**：N 个库全查太慢。
> 2. **准确性**：选错库召回错。
> 3. **幻觉**：跨库内容混杂，模型乱编。
> 4. **维护**：N 个库版本、schema、权限。
> 5. **数据隔离**：tenant、密级、合规。
>
> **二、架构设计**
>
> **方案 1：路由（Router）**
> ```
> query → 意图分类 → 路由到对应知识库
>                 ├─ 产品库
>                 ├─ 政策库
>                 ├─ 案例库
>                 └─ FAQ 库
> ```
> - 每个库独立 RAG。
> - 用 LLM 或小模型分类意图。
> - LangGraph conditional_edges 实现。
> - 优点：高效、隔离好。
> - 缺点：路由错则全错。
>
> **方案 2：并行多库召回 + Rerank 融合**
> ```
> query → 并行查 N 个库（各 top-k）→ RRF 融合 → Reranker 精排 → top-k
> ```
> - 优点：召回全。
> - 缺点：慢、token 多、可能混入无关。
>
> **方案 3：统一索引 + 元数据分类**
> ```
> 所有文档入同一个库 → metadata: {kb: "product"/"policy"/...}
> 查询时按 metadata 过滤
> ```
> - 优点：简单、维护方便。
> - 缺点：单库可能太大、不同库特征被淹没。
>
> **方案 4：Agentic RAG**
> ```
> query → Agent 决策 → 调用哪个库 / 何时调 / 是否再查 → 综合答案
> ```
> - 优点：最灵活。
> - 缺点：成本高、复杂。
>
> **生产推荐**：**方案 1（路由）+ 方案 3（元数据过滤）混合**。
>
> **三、查询效率优化**
>
> 1. **路由（最有效）**：只查相关库，减少 N 倍查询。
> 2. **并行多库召回**：异步 IO，效率提升。
> 3. **库的分级**：FAQ（极快）→ 知识库（快）→ 联网搜索（慢）。
> 4. **缓存**：GPTCache 语义缓存。
> 5. **预热**：高频 query 预查。
> 6. **限制 top-k**：每库 top-3~5。
>
> **四、准确性提升**
>
> 1. **路由准确率**：
>    - LLM 分类 + 显式选项。
>    - 加 few-shot 示例。
>    - 不确定时走 multi-route。
> 2. **每库独立优化**：
>    - 各自最优 chunking、embedding、Reranker。
>    - 不同库用不同 embedding（产品 vs 政策）。
> 3. **元数据过滤**：时间、来源、tenant。
> 4. **Reranker 精排**：跨库候选统一 Rerank。
> 5. **Self-Query**：自动拆 metadata 条件。
>
> **五、防幻觉策略**
>
> 1. **强约束 Prompt**：
>    - "仅根据提供的资料回答"。
>    - "资料中没有请回答'我不知道'"。
>    - "答案必须标注来源 [资料N]"。
> 2. **多源冲突处理**：
>    - 检测资料矛盾 → 让模型说明。
>    - 优先级排序（官方 > 用户生成）。
> 3. **引用强制**：每段答案标 source。
> 4. **置信度阈值**：低置信度兜底"建议咨询人工"。
> 5. **Citation 校验**：自动检查引用是否真实存在。
> 6. **护栏**：输入/输出/检索各层兜底。
>
> **六、典型架构**
>
> ```
> 用户 query
>     │
>     ▼
> [1. 意图分类（LLM / 小模型）]
>     │
>     ├─ 闲聊 → 直接答
>     ├─ FAQ → FAQ 精确匹配（极快）
>     ├─ 产品咨询 → 产品库 RAG
>     ├─ 政策问题 → 政策库 RAG + 强引用
>     ├─ 案例查询 → 案例库 RAG + 时间过滤
>     ├─ 数据查询 → Text-to-SQL
>     └─ 通用知识 → 联网搜索
>     │
>     ▼
> [2. 单库 RAG（独立优化）]
>     │
>     ▼
> [3. 跨库 Rerank（可选）]
>     │
>     ▼
> [4. 强约束生成（带 source）]
>     │
>     ▼
> [5. 护栏校验（faithfulness / citation）]
>     │
>     ▼
> 最终答案（带引用）
> ```
>
> **七、典型实现（LangGraph）**
>
> ```python
> from langgraph.graph import StateGraph, END
>
> class State(TypedDict):
>     query: str
>     intent: str
>     docs: list
>     answer: str
>     sources: list[str]
>
> def classify(state):
>     state["intent"] = llm_classify(state["query"])
>     return state
>
> def route(state):
>     return {"product": "rag_product",
>             "policy": "rag_policy",
>             "faq": "rag_faq"}[state["intent"]]
>
> def rag_product(state): ...
> def rag_policy(state): ...
> def rag_faq(state): ...
>
> def generate(state): ...
>
> g = StateGraph(State)
> g.add_node("classify", classify)
> g.add_node("rag_product", rag_product)
> g.add_node("rag_policy", rag_policy)
> g.add_node("rag_faq", rag_faq)
> g.add_node("generate", generate)
>
> g.set_entry_point("classify")
> g.add_conditional_edges("classify", route)
> [g.add_edge(name, "generate") for name in ["rag_product","rag_policy","rag_faq"]]
> g.add_edge("generate", END)
>
> app = g.compile()
> ```
>
> **八、生产实战经验**
>
> 1. **先做路由**：单库 RAG 路由化是最高 ROI 优化。
> 2. **路由容错**：分类不确定时走"通用知识库"兜底。
> 3. **元数据是金子**：好的 metadata 让过滤召回质量翻倍。
> 4. **每库独立优化**：不要一套参数走天下。
> 5. **强约束 + 引用**：防幻觉的核心。
> 6. **持续评估**：每库独立指标 + 端到端指标。
> 7. **A/B 灰度**：新策略先小流量验证。
> 8. **用户反馈**：👍/👎 收集，反哺路由 / 检索。
>
> **九、常见坑**
>
> 1. **路由分类错**：训练数据不够、覆盖不全。
> 2. **库间内容冲突**：处理不当引发幻觉。
> 3. **库太大**：单库召回慢、混入无关。
> 4. **没引用**：答案无法溯源。
> 5. **没护栏**：违规内容流出。
>
> **十、总结**
>
> 多知识库 RAG 的核心是"**路由 + 单库优化 + 跨库融合 + 强约束**"：
> - **路由**：让 query 走对地方。
> - **单库优化**：每库极致化。
> - **跨库融合**：Rerank 统一精排。
> - **强约束 + 引用**：防幻觉 + 可溯源。
> - **Agentic 演进**：Agent 决策复杂场景。
>
> **核心理念**：**不要建一个巨大的库解决所有问题，要建多个专业的库 + 智能路由**。这是 2026 年生产级 RAG 的主流架构。

### [什么是 AI 护栏（Guardrails）技术？它在生产环境中如何保障 AI 应用的安全？](https://www.mianshiya.com/bank/1906189461556076546/question/2052284834864181249)

> **答案**：
>
> **AI 护栏（Guardrails）** 是 LLM 应用中**多层防御**的安全 / 质量保障机制，确保 AI 行为符合业务、合规、伦理要求。
>
> **一、为什么生产环境必备？**
>
> LLM 是不可靠组件：
> - **幻觉**：编造事实、错误引用。
> - **越权**：回答敏感、违法、超范围问题。
> - **注入**：prompt injection、jailbreak。
> - **数据泄露**：PII、API Key、商业机密。
> - **偏见 / 歧视**：性别、种族、地域。
> - **格式不稳**：JSON 解析失败。
> - **成本失控**：长 prompt、循环调用。
> - **法律合规**：GDPR、医疗、金融、内容审核。
>
> 生产 AI 应用没有护栏 = 裸奔，迟早出大事。
>
> **二、护栏层级（端到端防御）**
>
> **1. 输入护栏（Input Guardrails）**
> - **Prompt Injection 检测**：识别"忽略上面指令"、"扮演 DAN"等。
> - **PII 检测**：手机号、邮箱、身份证、卡号、API Key。
> - **敏感话题过滤**：政治、暴力、色情、自残。
> - **意图分类**：拒绝非业务问题。
> - **长度 / 复杂度限制**：防 token 滥用。
> - **Rate Limiting**：单用户 / IP 频率限制。
>
> **2. 检索护栏（Retrieval Guardrails）**
> - **召回质量阈值**：cosine < 0.7 视为无召回。
> - **召回数量限制**：top-k 不超过 N。
> - **来源白名单**：仅可信文档可入 prompt。
> - **机密信息过滤**：从召回中删敏感数据。
> - **跨租户隔离**：tenant_id 严格过滤。
>
> **3. 生成护栏（Generation Guardrails）**
> - **强约束 Prompt**：忠于资料、不编造、不假设。
> - **拒答策略**：敏感问题统一回复。
> - **温度控制**：抽取 / 分类 temperature=0。
> - **结构化输出**：强制 schema。
>
> **4. 输出护栏（Output Guardrails）**
> - **格式校验**：JSON schema、必填字段。
> - **事实核查**：与召回内容比对（faithfulness）。
> - **毒性 / 偏见检测**：Perspective API、Llama Guard。
> - **PII 脱敏**：mask 输出中的 PII。
> - **Citation 校验**：确保引用真实存在。
> - **法律审核**：行业合规（医疗、金融）。
>
> **5. 行为护栏（Behavioral Guardrails）**
> - **人审触发**：高风险操作转人工。
> - **审计日志**：所有请求留痕（90 天+）。
> - **熔断**：错误率高自动降级。
> - **灰度**：新功能小流量。
>
> **三、主流护栏框架**
>
> **1. NeMo Guardrails（NVIDIA）**
> - 开源，Python + Colang（DSL）。
> - 支持 input / output / dialog / retrieval / execution 护栏。
> - 可定义"对话流程"，约束 bot 行为。
> - 集成 LangChain / LlamaIndex。
>
> **2. Guardrails AI**
> - 开源 Python 库。
> - Validator 验证 LLM 输出（格式、范围、合规）。
> - 与 Pydantic / LangChain 集成好。
>
> **3. Llama Guard / Llama Guard 3（Meta）**
> - 开源分类模型。
> - 检测输入 / 输出违规（暴力、性、自残、隐私）。
> - 支持自定义安全类别。
>
> **4. OpenAI Moderation API**
> - 商业 API（有免费额度）。
> - 检测 hate、violence、sexual、harassment。
>
> **5. Perspective API（Google）**
> - 毒性检测，免费 API。
>
> **6. LangChain Guardrails / Output Parsers**
> - 集成 NeMo / Guardrails AI。
> - OutputParser + Pydantic 做格式护栏。
>
> **7. 自研护栏**
> - 用小模型 + 规则做实时检查。
> - 适合特定合规要求。
>
> **四、典型实现**
>
> ```python
> # 输入护栏
> def input_guardrail(query):
>     if detect_prompt_injection(query): return reject("检测到注入")
>     if has_pii(query): return reject("请删除个人信息")
>     if is_sensitive(query): return reject("无法回答此类问题")
>     if len(query) > 1000: return reject("query 过长")
>     return None
>
> # 检索护栏
> def retrieval_guardrail(docs, tenant_id):
>     docs = [d for d in docs if d.score > 0.7]
>     docs = [d for d in docs if d.metadata["tenant"] == tenant_id]
>     docs = [mask_pii(d) for d in docs]
>     docs = [filter_confidential(d) for d in docs]
>     return docs[:5]
>
> # 输出护栏
> def output_guardrail(answer, sources):
>     if not verify_citations(answer, sources):
>         return regenerate("引用有误，请重新生成")
>     if detect_toxicity(answer) > 0.8:
>         return reject("输出违规")
>     if has_pii(answer):
>         answer = mask_pii(answer)
>     return answer
>
> # 主流程
> def rag_pipeline(query, tenant_id):
>     if guard := input_guardrail(query): return guard
>     docs = retriever.retrieve(query)
>     docs = retrieval_guardrail(docs, tenant_id)
>     answer = llm.generate(query, docs)
>     answer = output_guardrail(answer, docs)
>     audit_log(query, answer, tenant_id)
>     return answer
> ```
>
> **五、典型场景的护栏设计**
>
> **医疗 AI**：
> - 输入：拒答非医疗问题。
> - 输出：不诊断、不开药、建议就医。
> - 引用：所有结论标医学文献。
> - 法规：HIPAA / 个人信息保护法。
>
> **金融 AI**：
> - 输入：拒答"如何洗钱"等违规问题。
> - 输出：不荐股、不预测收益、加风险提示。
> - 数据：客户数据严格隔离。
> - 法规：MiFID II、金融广告法。
>
> **客服 AI**：
> - 输入：仅业务问题，拒竞品讨论。
> - 检索：tenant 严格隔离。
> - 输出：不承诺、不引战、不带个人观点。
> - 行为：高风险投诉转人工。
>
> **政企 AI**：
> - 输入：意识形态合规。
> - 输出：政治敏感词过滤。
> - 数据：数据不出境。
> - 审计：所有请求留痕 180 天+。
>
> **六、生产化要点**
>
> 1. **多层防御**：输入 + 检索 + 生成 + 输出 + 行为。
> 2. **快速失败**：检测到违规立即返回，节省成本。
> 3. **可观测**：所有拦截事件记录、监控、告警。
> 4. **持续更新**：新攻击手法不断出现，规则要迭代。
> 5. **用户体验**：拒答要友好（"我无法回答，请联系 XX"）。
> 6. **灰度**：新护栏先小流量验证。
> 7. **审计**：合规审计、定期评估。
>
> **七、护栏 vs Fine-tuning vs Prompt**
>
> - **Prompt**：行为约束，软性。
> - **Guardrails**：硬性技术拦截，最后防线。
> - **Fine-tuning**：内化行为，长期有效。
> - **三者结合**：Prompt 教、Guardrails 拦、Fine-tune 烙。
>
> **八、监控指标**
>
> - 输入拦截率（< 5% 正常，> 20% 说明用户行为异常）。
> - 输出违规率（< 0.1%）。
> - 引用准确率（> 95%）。
> - PII 泄露次数（0 容忍）。
> - 审计覆盖率（100%）。
>
> **九、总结**
>
> AI 护栏是 LLM 应用走向生产的**必经环节**：
> 1. **多层防御**：输入 / 检索 / 生成 / 输出 / 行为。
> 2. **主流框架**：NeMo Guardrails、Guardrails AI、Llama Guard、OpenAI Moderation。
> 3. **关键场景**：合规、安全、质量。
> 4. **持续运营**：监控、更新、迭代。
>
> **核心理念**：**LLM 是不可靠组件，护栏是工程化的"安全带 + 刹车 + ABS"**——不能指望模型永远对，但可以保证它不会"出大错"。生产 AI = 模型能力 + 工程护栏，缺一不可。

### [如何减少 RAG 系统的幻觉问题？有哪些实用方法？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796745444564994)

> **答案**：
>
> **减少 RAG 幻觉** 的实用方法，从数据、检索、生成、后处理四层入手：
>
> **一、数据层（基础）**
>
> 1. **高质量文档**：
>    - 清洗去噪（页眉页脚、广告）。
>    - 去重（MinHash）。
>    - 脱敏（PII）。
>    - "Garbage In, Garbage Out"。
>
> 2. **合理切分**：
>    - chunk_size 适中（500 token）。
>    - 结构化切分（保留章节、表格、代码不切散）。
>    - overlap 10~20%。
>
> 3. **元数据丰富**：
>    - source、author、date、version、tags。
>    - 让模型能引用准确来源。
>
> 4. **生成"假设问题"**：
>    - 每 chunk 让 LLM 生成"这段能回答什么问题"。
>    - 加入索引，提升召回精度。
>
> 5. **领域知识注入**：
>    - 微调 embedding 模型用领域数据。
>    - 或 CPT 注入领域语料。
>
> **二、检索层（关键）**
>
> 6. **混合检索**：
>    - 向量 + BM25 + RRF。
>    - 解决语义 / 关键词盲区。
>
> 7. **Reranker 精排**：
>    - bge-reranker-v2-m3。
>    - 召回 top-20 → 精排 top-5。
>
> 8. **相似度阈值**：
>    - cosine < 0.7 视为不相关。
>    - 防止"硬塞"无关内容。
>
> 9. **元数据过滤**：
>    - 时间、来源、tenant、permission。
>    - 减少无关召回。
>
> 10. **Multi-Query / HyDE**：
>     - 抗表述差异。
>     - 提升召回质量。
>
> 11. **Parent Document Retrieval**：
>     - 小块检索、大块生成。
>     - 平衡精准和上下文。
>
> 12. **Self-Query**：
>     - 自动拆出 metadata 条件。
>     - 减少错误召回。
>
> **三、生成层（核心）**
>
> 13. **强约束 Prompt**：
>     - "仅根据下面资料回答"。
>     - "资料中没有请回答'我不知道'"。
>     - "不要编造、不要假设"。
>
> 14. **显式引用要求**：
>     - "每个论点后标注 [资料N]"。
>     - 让答案可溯源。
>
> 15. **温度=0**：
>     - 抽取 / 事实题降温，减少随机。
>
> 16. **结构化输出**：
>     - `with_structured_output(PydanticModel)`。
>     - 强制 schema：answer、sources、confidence。
>
> 17. **思维链（CoT）**：
>     - "先分析资料，再回答"。
>     - 复杂问题提升准确率。
>
> 18. **拒答策略**：
>     - 不确定时显式说"不确定"。
>     - 资料矛盾时说明并标注。
>
> 19. **Few-shot 示例**：
>     - 给 2~3 个"忠于资料"的范例。
>     - 让模型学会风格。
>
> **四、后处理 / 自检层**
>
> 20. **Self-Reflection / Self-Critique**：
>     - 第一轮生成 → 第二轮自检"是否忠于资料"。
>     - LangGraph 实现自然循环。
>
> 21. **Faithfulness 校验**：
>     - 用 LLM / NLI 模型检查答案是否完全来自召回内容。
>     - 不达标 → 重生成。
>
> 22. **Citation 校验**：
>     - 自动检查引用的 [资料N] 是否真实存在。
>     - 引用错 → 重生成。
>
> 23. **CRAG / Self-RAG**：
>     - 召回质量评估 → 不达标重检索。
>     - 模型自纠错。
>
> 24. **事实核查**：
>     - 关键事实用外部知识源核查。
>     - 自训 fact-checker。
>
> **五、护栏层**
>
> 25. **输出护栏**：
>     - 毒性、偏见、PII、违规检测。
>     - 不达标 → 拒答。
>
> 26. **人工兜底**：
>     - 高风险问题（医疗、法律）转人工。
>     - 低置信度答案触发人审。
>
> **六、评估与监控**
>
> 27. **RAGAS Faithfulness**：
>     - 量化幻觉率。
>     - 阈值不达标自动告警。
>
> 28. **TruthfulQA / HalluQA 评估**：
>     - 标准 hallucination benchmark。
>
> 29. **在线监控**：
>     - 用户 👎 反馈率。
>     - 引用准确率。
>     - 人工抽检。
>
> 30. **反馈飞轮**：
>     - bad case → 补数据 / 改 prompt / 微调。
>
> **七、组合方案（推荐）**
>
> ```
> v0：Naive RAG → 幻觉率 30%+
> v1：+ 强约束 Prompt + 温度=0 → 幻觉率 15%
> v2：+ Reranker + 相似度阈值 → 幻觉率 10%
> v3：+ Self-Reflection + Faithfulness 校验 → 幻觉率 5%
> v4：+ CRAG + 引用强制 → 幻觉率 < 3%
> v5：+ 持续评估 + 反馈飞轮 → 持续优化
> ```
>
> **八、典型场景**
>
> **医疗**：幻觉 = 生命危险。
> - 强约束 + 引用 + faithfulness 校验 + 人工兜底。
>
> **法律**：幻觉 = 法律责任。
> - 强引用 + 多源校验 + 律师审核。
>
> **金融**：幻觉 = 财务损失。
> - 数据严格过滤 + 多源验证 + 风险提示。
>
> **客服**：幻觉 = 客户投诉。
> - 仅基于 KB + 拒答策略 + 转人工。
>
> **九、总结**
>
> 减少 RAG 幻觉的核心：**让模型基于"对的、全的、可信的"内容，"严谨地、可溯地、谦虚地"作答**。
>
> 实战清单：
> 1. **数据**：高质量、合理切分、元数据丰富。
> 2. **检索**：混合 + Reranker + 阈值 + Multi-Query。
> 3. **生成**：强约束 + 引用 + 温度=0 + 结构化。
> 4. **后处理**：Self-Reflection + Faithfulness + Citation 校验。
> 5. **护栏**：输出过滤 + 人工兜底。
> 6. **评估**：RAGAS + 在线监控 + 反馈飞轮。
>
> **核心理念**：**没有 0 幻觉的 RAG，只有 < 5% 幻觉的 RAG**。工程化目标不是消除，而是控制在业务可接受范围，并通过引用、护栏、人审等机制兜底。

### [RAG 系统如何标注信息来源和提供引用？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796743498407937)

> **答案**：
>
> **RAG 信息来源标注与引用**：
>
> **一、为什么重要？**
>
> 1. **可信度**：用户能验证答案。
> 2. **防幻觉**：模型被强制引用，减少编造。
> 3. **合规**：医疗 / 法律 / 金融必须可溯源。
> 4. **审计**：出现问题能定位责任。
> 5. **用户体验**：能点击 source 看原文。
>
> **二、实现方法**
>
> **1. 文档入库时打好 metadata**
>
> 每个 chunk 必须有：
> - `source_id`：文档唯一 id。
> - `source_url`：原文链接。
> - `title`、`section`、`page`、`page_range`。
> - `author`、`date`、`version`。
> - `tenant_id`、`permission`。
>
> **2. 检索时保留 source 信息**
>
> 召回的每个 chunk 都带上 metadata，传到 prompt。
>
> **3. Prompt 强制引用**
>
> 模板：
> ```
> 基于以下资料回答问题，每个论点后必须标注来源。
>
> 资料：
> [1] {title1}（来源：{url1}，第 {page1} 页）：{content1}
> [2] {title2}（来源：{url2}）：{content2}
> ...
>
> 问题：{question}
>
> 要求：
> 1. 仅根据资料回答，不要编造。
> 2. 每个论点后标注 [资料N]。
> 3. 引用必须真实对应（不要张冠李戴）。
>
> 答案格式：
> 答案：...
> 引用：[1][3]
> ```
>
> **4. 结构化输出**
>
> ```python
> from pydantic import BaseModel, Field
>
> class RAGAnswer(BaseModel):
>     answer: str = Field(description="基于资料的答案")
>     citations: list[Citation] = Field(description="引用列表")
>
> class Citation(BaseModel):
>     chunk_id: str
>     source_url: str
>     title: str
>     page: int | None = None
>     quote: str = Field(description="原文片段")
>
> structured_llm = llm.with_structured_output(RAGAnswer)
> ```
>
> 强制 schema，引用结构化、可机器消费。
>
> **5. Citation 校验**
>
> - 检查模型说的 [资料N] 是否真实存在。
> - 检查引用内容是否真的来自该 chunk。
> - 自动验证（LLM-as-Judge / NLI 模型）。
> - 不通过 → 重生成。
>
> **6. UI 渲染**
>
> 前端把 citations 渲染成可点击链接：
> ```
> 答案是 XX [1]，原因是 YY [2][3]。
>
> [1] 产品手册 v3，第 5 页 → 可点击跳转
> [2] 销售政策 2024，第 12 页 → 可点击跳转
> [3] 客户案例：A 公司 → 可点击跳转
> ```
>
> **三、引用粒度**
>
> **chunk 级**：标注到 chunk_id。
> **段落级**：标注到具体段落。
> **句子级**：精确到原文句子（quote）。
> **页码级**：PDF 标到页码。
>
> 粒度越细，可信度越高，但实现复杂度也越高。
>
> **四、多源冲突处理**
>
> - 多个资料矛盾时：
>   - 让模型说明"资料 [1] 说 X，资料 [2] 说 Y"。
>   - 不擅自决定。
> - 优先级排序：
>   - 官方文档 > 用户生成 > 第三方。
>   - 最新版本 > 旧版本。
>
> **五、Citation 质量保障**
>
> 1. **召回阶段**：保证 top-k 中确有相关内容。
> 2. **生成阶段**：Prompt 强约束 + Few-shot 示范正确引用。
> 3. **校验阶段**：自动检查引用真实性。
> 4. **反馈阶段**：用户 👍/👎 反馈，反哺优化。
>
> **六、典型架构**
>
> ```
> [入库]
> 文档 → chunk → metadata(source_url, page, ...) → 向量库
>
> [查询]
> query → embedding → 检索 top-k → 每个带 metadata
>      → Rerank 精排
>      → 拼入 prompt（带编号 [1][2]...）
>      → LLM 结构化输出 {answer, citations}
>      → Citation 校验
>      → 渲染 UI（可点击 source）
>
> [审计]
> 所有 (query, answer, citations) 入库审计表。
> ```
>
> **七、LangChain 实现**
>
> ```python
> from langchain_core.prompts import ChatPromptTemplate
> from langchain_core.runnables import RunnablePassthrough
> from pydantic import BaseModel, Field
>
> prompt = ChatPromptTemplate.from_template("""
> 基于资料回答，每个论点标注 [资料N]。
>
> 资料：
> {numbered_context}
>
> 问题：{question}
>
> 输出 JSON：{{"answer": "...", "citations": [{{"ref": 1, "quote": "..."}}]}}
> """)
>
> def format_with_num(docs):
>     return "\n\n".join(
>         f"[{i+1}] {d.metadata.get('title','')}（来源：{d.metadata.get('source_url','')}，第{d.metadata.get('page','?')}页）：{d.page_content}"
>         for i, d in enumerate(docs)
>     )
>
> chain = (
>     {"numbered_context": retriever | format_with_num, "question": RunnablePassthrough()}
>     | prompt
>     | llm.with_structured_output(RAGAnswer)
> )
> ```
>
> **八、生产实战**
>
> 1. **入库元数据必须全**：source_url / page / title / version。
> 2. **Prompt 显式要求**：每个论点都标 [资料N]。
> 3. **结构化输出**：强制 schema。
> 4. **Citation 校验**：自动 + 抽检。
> 5. **UI 友好**：可点击、可跳转、可复制。
> 6. **审计留痕**：所有引用入库，便于追溯。
>
> **九、典型坑**
>
> - **citation 张冠李戴**：模型引用了错误 chunk → Citation 校验拦截。
> - **引用过时**：资料已更新但缓存旧版 → 版本管理。
> - **多源矛盾不告知**：模型擅自选一边 → Prompt 要求说明冲突。
> - **UI 不友好**：source 不可点击 → 用户失去验证能力。
>
> **十、总结**
>
> RAG 引用是"**信任的基石**"：
> 1. **元数据完整**：source_url、page、title、version、date。
> 2. **Prompt 强约束**：每个论点标 [资料N]。
> 3. **结构化输出**：citations 字段。
> 4. **Citation 校验**：自动检查真实性。
> 5. **UI 渲染**：可点击、可跳转。
> 6. **审计留痕**：可追溯。
>
> **核心理念**：**没有引用的 RAG 答案 = 编造**。生产 RAG 必须 100% 带引用，且引用必须真实对应。

### [什么是 RAG 检索增强生成？它解决了大模型的哪些问题？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796737861263362)

> **答案**：
>
> **RAG（Retrieval-Augmented Generation，检索增强生成）** 是一种在生成回答前**先从外部知识库检索相关文档**、再把检索内容拼入 prompt 的技术。
>
> **解决的 LLM 问题**：
>
> **1. 知识时效性**
> - LLM 训练有截止日期（GPT-4 是 2023 年），不知道新事件。
> - RAG：联网 / 文档实时检索，获取最新信息。
>
> **2. 私有知识 / 长尾知识**
> - LLM 不知道公司内部数据、产品文档、个人笔记。
> - RAG：接入私有知识库。
>
> **3. 幻觉（Hallucination）**
> - LLM 自由生成时容易"编造"事实。
> - RAG：基于"权威资料"作答，答案有据可查。
>
> **4. 不可溯源**
> - LLM 直接答，无法验证。
> - RAG：答案带 source 引用，可信度高。
>
> **5. 领域专业度**
> - LLM 通用，对医疗 / 法律 / 金融等深度不够。
> - RAG：注入领域文档，临时"专业化"。
>
> **6. 更新成本**
> - 微调更新需要重训，成本高、周期长。
> - RAG：增删文档即可，无需重训。
>
> **7. 多源融合**
> - 单一模型能力有限，需融合多知识源。
> - RAG：多库协同，路由 + 融合。
>
> **8. 个性化**
> - 用户偏好、历史、上下文。
> - RAG：检索个性化数据。
>
> **9. 合规 / 审计**
> - 数据不能进权重（GDPR、医疗、金融）。
> - RAG：数据存受控知识库，模型权重不接触。
>
> **10. 成本控制**
> - 微调 + 大模型成本极高。
> - RAG：小模型 + 检索，性价比高。
>
> **典型应用**：
> - 文档问答（PDF / Confluence / Notion）。
> - 客服 Bot。
> - 法律 / 医疗咨询。
> - 代码助手（仓库 + 文档）。
> - 学术研究助手。
> - 企业内部知识库。
> - 联网搜索增强。
>
> **RAG vs Fine-tuning**：
> - RAG：解决"知识"问题（动态、私有、长尾）。
> - Fine-tuning：解决"能力 / 风格"问题（领域、行为）。
> - 二者互补共存。
>
> **总结**：RAG 让 LLM 从"封闭的预言机"变成"开放的、可溯源的、可信的助手"，是 2026 年 LLM 应用工程化的核心技术。

### [简述 Word Embedding 可以怎样运用于文本分类任务？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834677762166785)

> **答案**：
>
> **Word Embedding 在文本分类中的应用**：
>
> **一、基本思路**
>
> 文本分类需要把"文本"变成"特征向量"。Word Embedding（如 Word2Vec、GloVe、FastText）把每个词映射到稠密向量，从而：
>
> 1. 词 → 向量。
> 2. 文本（词序列）→ 文本向量（聚合）。
> 3. 文本向量 → 分类器（SVM / LR / MLP / CNN / RNN）。
>
> **二、典型流程**
>
> **1. 词向量获取**
> - 预训练：Word2Vec（Google）、GloVe（Stanford）、FastText（Facebook）。
> - 自训练：用自己的语料训练。
> - 加载：`gensim.models.KeyedVectors.load_word2vec_format(...)`。
>
> **2. 文本向量化**
>
> **a. 平均向量（最简单）**
> ```python
> def embed(text):
>     tokens = tokenize(text)
>     vecs = [w2v[t] for t in tokens if t in w2v]
>     return np.mean(vecs, axis=0) if vecs else np.zeros(dim)
> ```
>
> **b. TF-IDF 加权平均**
> ```python
> def embed_tfidf(text):
>     # 每个词向量按 TF-IDF 权重加权
>     return np.average(vecs, axis=0, weights=tfidf_weights)
> ```
>
> **c. SIF（Smooth Inverse Frequency）**
> - 加权平均 + 移除第一主成分。
> - 效果优于简单平均。
>
> **d. 时序模型（CNN / LSTM）**
> - 词向量序列输入 CNN / LSTM，自动学特征。
> - 准确率最高，但需要训练。
>
> **3. 分类器**
>
> - **传统**：SVM、LR、随机森林、XGBoost。
> - **神经网络**：MLP、CNN（TextCNN）、LSTM / GRU、Attention。
>
> **三、典型代码**
>
> ```python
> from gensim.models import KeyedVectors
> from sklearn.svm import LinearSVC
> from sklearn.pipeline import Pipeline
> import numpy as np
>
> # 1. 加载预训练词向量
> w2v = KeyedVectors.load_word2vec_format(" Tencent-emb.bin", binary=True)
>
> # 2. 文本向量化
> def embed(text, dim=200):
>     tokens = jieba.lcut(text)
>     vecs = [w2v[t] for t in tokens if t in w2v]
>     return np.mean(vecs, axis=0) if vecs else np.zeros(dim)
>
> X_train_vec = np.array([embed(t) for t in X_train])
> X_test_vec = np.array([embed(t) for t in X_test])
>
> # 3. 训练分类器
> clf = LinearSVC()
> clf.fit(X_train_vec, y_train)
> print(clf.score(X_test_vec, y_test))
> ```
>
> **四、TextCNN（深度学习版）**
>
> ```python
> import torch
> import torch.nn as nn
>
> class TextCNN(nn.Module):
>     def __init__(self, vocab_size, emb_dim, num_classes, kernel_sizes=[3,4,5]):
>         super().__init__()
>         self.embedding = nn.Embedding(vocab_size, emb_dim)
>         self.convs = nn.ModuleList([
>             nn.Conv1d(emb_dim, 128, k) for k in kernel_sizes
>         ])
>         self.fc = nn.Linear(128 * len(kernel_sizes), num_classes)
>
>     def forward(self, x):
>         emb = self.embedding(x).transpose(1, 2)  # (B, dim, L)
>         features = [torch.max(conv(emb), dim=2)[0] for conv in self.convs]
>         out = torch.cat(features, dim=1)
>         return self.fc(out)
> ```
>
> **五、效果对比**
>
> | 方法 | 准确率（中文新闻分类） |
> |------|---------------------|
> | TF-IDF + SVM | ~85% |
> | Word2Vec 平均 + SVM | ~87% |
> | Word2Vec + TF-IDF 加权 + SVM | ~89% |
> | Word2Vec + TextCNN | ~92% |
> | Word2Vec + BiLSTM + Attention | ~93% |
> | BERT fine-tune | ~95%+ |
>
> **六、Word Embedding 分类的优缺点**
>
> **优点**：
> - 计算快（向量平均 + SVM，秒级训练）。
> - 不需要 GPU。
> - 可解释（看哪些词主导分类）。
> - 适合小数据集。
>
> **缺点**：
> - 词袋模型，丢失词序信息（平均聚合）。
> - 不理解上下文（一词多义无法处理）。
> - 中文需要分词（依赖 jieba 等）。
> - 准确率上限低于 BERT。
>
> **七、何时仍用 Word Embedding 分类？**
>
> - 数据量小（< 1 万）。
> - 计算资源紧（CPU、边缘设备）。
> - 实时性要求高。
> - 简单任务（情感、垃圾邮件、新闻分类）。
> - 作为 baseline 对比深度模型。
>
> **八、BERT 时代的演进**
>
> - BERT 的 contextual embedding 替代静态 word embedding。
> - 一词多义、上下文、长距离依赖都更好。
> - 但 Word Embedding 仍在小模型、快速场景、向量检索（作为词级 retrieval）等有价值。
>
> **九、进阶：Sentence Embedding**
>
> - SBERT、SimCSE、BGE：直接生成句子向量。
> - 比词向量平均质量高。
> - 是 RAG 检索的基础。
>
> **十、总结**
>
> Word Embedding 用于文本分类的核心步骤：
> 1. 预训练 / 加载词向量。
> 2. 文本向量化（平均 / TF-IDF 加权 / SIF / 深度模型）。
> 3. 训练分类器（SVM / LR / CNN / LSTM）。
>
> **适用场景**：小数据、低成本、快速原型。
> **BERT 时代**：高精度任务用 BERT / Sentence Transformer；轻量任务仍可用 Word Embedding + SVM 作 baseline。
>
> **核心理念**：Word Embedding 是 NLP 的"古典武器"——简单、快速、可解释，在某些场景仍不可替代。

### [RAG 系统中为什么要进行文档切割？有哪些切割策略？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796740113604609)

> **答案**：
>
> **为什么要文档切割（Chunking）**
>
> LLM 上下文窗口有限（4K~200K），整篇长文档塞进 prompt 既超 token 又稀释关键信息。切割的目的是：
> 1. **控制单块大小**：让每块都能被 Embedding 模型 / LLM 完整消费。
> 2. **提升检索精度**：小块语义聚焦，向量更"纯"，召回更准。
> 3. **降低噪声**：避免大段无关文本稀释相似度分数。
> 4. **节省成本**：只把 top-K 最相关的块送给 LLM，而不是整篇。
>
> **主流切割策略**
>
> 1. **固定长度切割（Fixed-size）**：按字符 / token 数硬切，可加 overlap。
>    - 优点：实现简单、稳定。
>    - 缺点：可能切断句子、段落，语义不完整。
>
> 2. **递归字符切割（RecursiveCharacterTextSplitter，LangChain 默认）**：
>    - 依次尝试 `["
>
> ", "
> ", " ", ""]` 作为分隔符，优先在段落边界切，切不开再降级。
>    - 兼顾语义完整性和大小约束，**最常用**。
>
> 3. **Token 切割（TokenTextSplitter）**：按 tiktoken / tokenizer 切，token 数精确。
>    - 适合对 LLM 上下文预算严格控制的场景。
>
> 4. **Markdown / HTML 结构化切割**：
>    - 按 `#`、`##` 标题或 DOM 节点切，保留层级。
>    - 适合文档结构强的语料（技术文档、Wiki）。
>
> 5. **语义切割（Semantic Chunking）**：
>    - 用 Embedding 计算相邻句子相似度，相似度骤降处作为切点。
>    - 语义最自然，但计算成本高。
>
> 6. **按文档元素切割**：用 Unstructured / LayoutParser 把 PDF 拆成 Title / Narrative / ListItem / Table，分别处理。
>
> **chunk_size 与 overlap 的取舍**
>
> - chunk_size 太小：上下文不完整，丢失关键信息。
> - chunk_size 太大：向量语义稀释，召回精度下降，LLM 成本高。
> - overlap（一般 10%~20%）：避免边界切断关键句子，造成信息丢失。
>
> **经验值**：
> - 通用问答：`chunk_size=500~1000 chars`，`overlap=50~200`。
> - 代码 / 表格：按函数 / 行切，不要硬切。
> - 长篇叙事：用语义切割或递归切割 + 较大 overlap。
>
> **进阶：父子块（Parent-Child / Small-to-Big）**
> - 小块用于精准检索，命中后返回其所属的大块（父块）给 LLM。
> - 兼顾检索精度和上下文完整性。
>
> **总结**：切割是 RAG 的"地基"，策略选错，后面再好的 Embedding 和 Reranker 也救不回来。优先 RecursiveCharacterTextSplitter 作 baseline，再针对文档类型调优。

### [RAG 中如何设计 Prompt 来有效利用检索到的文档？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796742797959169)

> **答案**：
>
> **RAG Prompt 设计的核心原则**
>
> 检索到的文档只是"原料"，能否被 LLM 正确利用，取决于 Prompt 怎么写。糟糕的 Prompt 会让 LLM 忽略文档、答非所问或编造答案。
>
> **关键设计要点**
>
> 1. **明确角色与任务边界**：
>    ```
>    你是一个严谨的问答助手。请只根据下面提供的【参考资料】回答用户问题，
>    不要使用任何外部知识。如果资料中没有答案，请直接回答"我不知道"。
>    ```
>    - 关键是"**只依据资料**"+"**不知道就说不知道**"，抑制幻觉。
>
> 2. **结构化分隔参考资料**：
>    - 用明显的分隔符把 context 和 question 隔开：`【参考资料】... 【用户问题】...`。
>    - 给每段资料编号 `[1] xxx`，便于 LLM 引用，也方便做溯源。
>
> 3. **约束输出格式**：
>    - 要求结构化输出：`请按 JSON 格式回答：{"answer": "...", "sources": [1,2]}`。
>    - 或要求引用来源：`回答末尾请标注引用的资料编号`。
>
> 4. **少样本示例（Few-shot）**：
>    - 给 1~3 个"问题 + 资料 + 正确回答"的示例，让 LLM 学会"基于资料回答"的风格。
>    - 对抑制幻觉特别有效。
>
> 5. **指令的优先级**：
>    - 把最强约束（"不知道就说不知道"、"不要编造"）放在 prompt 开头和结尾，重复强调。
>    - LLM 对开头和结尾的指令更敏感（Lost in the middle 问题）。
>
> 6. **思维链引导**：
>    ```
>    请按以下步骤思考：
>    1. 找出资料中与问题相关的句子。
>    2. 判断资料是否足以回答问题。
>    3. 如果足够，整合答案；如果不足，回答"我不知道"。
>    ```
>
> **Prompt 模板示例（生产级）**
>
> ```
> 你是一个严谨的问答助手。
>
> 【规则】
> 1. 只能使用【参考资料】中的内容回答问题。
> 2. 资料中没有的信息，必须回答"根据已知资料无法回答"。
> 3. 回答必须客观，不得编造、推测或添加外部知识。
> 4. 回答末尾用 [编号] 标注引用来源。
>
> 【参考资料】
> [1] ...
> [2] ...
> [3] ...
>
> 【用户问题】
> {question}
>
> 【回答格式】
> 答案：...
> 引用：[1,3]
> ```
>
> **常见坑**
>
> - **文档堆得太长**：超过 LLM 注意力有效范围，中段资料被忽略（Lost in the middle）。解决：限制 top-K，Reranking 排好序，或用 LongLLMLingua 做压缩。
> - **指令和资料混杂**：LLM 可能把指令当成资料。解决：用清晰的分隔符。
> - **缺少"不知道"兜底**：LLM 倾向于"硬答"，必须显式允许它说不知道。
>
> **总结**：RAG Prompt 的核心是"**约束 LLM 只做资料到答案的映射，而不是资料 + 自由发挥**"。把每条规则写在显眼位置，用分隔符隔离 context，用 few-shot 示范风格，用引用编号支持溯源——这四件事做到，幻觉率会大幅下降。

### [如何为 RAG 系统选择合适的 Embedding 模型？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796740650475521)

> **答案**：
>
> **选 Embedding 模型的核心维度**
>
> 1. **语言**：中文（BGE-zh、M3E、Conan）、英文（text-embedding-3、Instructor）、多语言（BGE-M3、multilingual-e5）。
> 2. **维度**：768 / 1024 / 1536 / 3072。维度越高表达越强，但存储和检索成本越高。
> 3. **领域**：通用 vs. 法律 / 医疗 / 代码等专用语料。
> 4. **MTEB / C-MTEB 排行榜分数**：检索（Retrieval）、STS、分类等子任务的得分。
> 5. **推理成本与延迟**：本地部署（开源）vs. API（OpenAI、Cohere）。
> 6. **最大输入长度**：512 tokens 是常见上限，长文档需要支持 8K+ 的模型（如 jina-embeddings-v2）。
>
> **主流模型选型**
>
> | 场景 | 推荐模型 | 备注 |
> |------|---------|------|
> | 中文通用，本地部署 | **BGE-large-zh-v1.5 / bge-m3** | C-MTEB 顶尖，开源商用友好 |
> | 中文通用，社区版 | M3E-large | 中文社区训练，效果接近 BGE |
> | 英文 / 多语言 | **BGE-M3** / text-embedding-3-large / Cohere embed-v3 | 多语言+长文本选 BGE-M3 |
> | 高质量 API | OpenAI text-embedding-3-large (3072 维) | 闭源但稳定，省运维 |
> | 跨语种检索 | BGE-M3 / multilingual-e5-large | 支持稠密 + 稀疏 + ColBERT 三路 |
> | 长文档（8K+） | jina-embeddings-v2 / bge-m3 | 输入窗口大 |
>
> **评估方法**
>
> 1. **标准榜单**：MTEB（英文）/ C-MTEB（中文）。
> 2. **自有数据集评估**：
>    - 准备 (query, positive_doc, negative_docs) 三元组。
>    - 指标：Recall@K、MRR、NDCG@10。
> 3. **A/B 灰度**：线上用一部分流量跑两个模型对比，看下游 LLM 答案质量（Faithfulness、Answer Relevancy）。
>
> **常见坑**
>
> - **维度越高≠效果越好**：3072 维对小数据集增益有限，反而拖慢检索。
> - **领域不匹配**：通用模型在医疗 / 法律术语上召回差，可能需要微调（BGE 也支持 contrastive fine-tune）。
> - **指令差异**：BGE 查询需要加 prompt `"为这个句子生成表示以用于检索相关文章："`，不加效果打折。
> - **版本漂移**：OpenAI / Cohere 模型更新后向量分布变化，已存向量需要重新生成。
>
> **决策流程**
>
> 1. 先选 2~3 个候选（BGE-M3 + 一个 API）。
> 2. 在自有评测集上跑 Recall@10 / MRR。
> 3. 看延迟和成本（GPU vs API 调用费）。
> 4. 留出升级路径（向量化模块抽象出来，便于切换）。
>
> **总结**：默认首选 **BGE-M3 / BGE-large-zh-v1.5**（中文）或 **text-embedding-3-large**（API 党）。**必须**在自有数据上用 Recall@K 验证，不要盲信榜单。

### [RAG 系统如何利用元数据过滤提升检索精度？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796742504357889)

> **答案**：
>
> **什么是元数据过滤（Metadata Filtering）**
>
> 给每个 chunk 打上结构化标签（如 `source`、`date`、`author`、`category`、`language`），检索时先按元数据过滤候选集，再做向量相似度。本质是"**先用 SQL 把范围圈出来，再用 ANN 找最近邻**"。
>
> **为什么能提升精度**
>
> 1. **缩小搜索空间**：把"全库 1M 文档"压到"近 3 个月的客服文档 5K 条"，召回噪声大幅下降。
> 2. **多路条件混合**：用户问"2024 年报销政策"，先过滤 `category=finance AND year=2024`，再向量检索。
> 3. **支持权限控制**：按 `department` / `acl` 过滤，确保用户只能检索到有权限的内容。
>
> **实现方式**
>
> 1. **向量库原生支持**：
>    - Milvus：Scalar Field + `expr="category == 'x' && year == 2024"`。
>    - Qdrant：payload + `Filter(must=[FieldCondition(...)])`。
>    - pgvector：直接 SQL `WHERE category='x' ORDER BY embedding <=> $1 LIMIT 10`。
>    - Pinecone：`filter={"category": {"$eq": "x"}}`。
>
> 2. **混合检索模式**：
>    - Pre-filtering：先过滤再 ANN（适合过滤后候选较少）。
>    - Post-filtering：先 ANN top-N（N 较大）再过滤（适合过滤条件宽松）。
>    - Milvus / Qdrant 的 native filter 是 pre-filter，更稳。
>
> **典型用法**
>
> ```python
> # Qdrant 示例
> client.search(
>     collection_name="docs",
>     query_vector=embed(q),
>     query_filter=Filter(must=[
>         FieldCondition(key="category", match=MatchValue(value="finance")),
>         FieldCondition(key="date", range=Range(gte="2024-01-01")),
>     ]),
>     limit=10,
> )
> ```
>
> **自动元数据抽取**
>
> - 用 LLM 从原文抽取 tags、summary、entities，作为元数据写入。
> - 例：把"苹果 2024Q3 财报"自动打上 `company=Apple, type=earnings, quarter=Q3`。
>
> **常见坑**
>
> - **过滤太严**：召回数=0，用户搜不到东西。需要监控过滤后候选数，过少时降级（放宽条件 / 全局检索）。
> - **元数据质量差**：自动抽取的 tag 错误率 10%+，反而误伤召回。需要人工抽检。
> - **隐式过滤损失多样性**：只看本部门文档，可能错过其他部门的相关经验。
>
> **进阶：混合元数据 + 重排**
>
> - 元数据过滤用于"硬约束"（必须满足）。
> - 向量 + BM25 用于"软排序"。
> - Reranker 做最终精排。
>
> **总结**：元数据过滤是 RAG 中**最低成本提升精度的手段**——不需要重训模型，只要在入库时多写几个字段。但它依赖文档治理（schema 规范、抽取准确率），是工程问题多于算法问题。

### [RAG 为什么需要重排序 Reranking？如何实现？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796744790253570)

> **答案**：
>
> **为什么要 Reranking（重排序）**
>
> 向量检索（Dense Retrieval）的优势是**召回率高**——能找到语义相关但用词不同的文档；劣势是**精度有限**——
> - 用的是双塔 Bi-Encoder（query 和 doc 独立编码），没有交互。
> - 必须用低维度向量（768/1024）做 ANN，表达能力受限。
> - top-10 中常常混入"语义沾边但答非所问"的文档。
>
> Reranking 用 Cross-Encoder（query 和 doc 拼接后过 BERT，做 token 级交互）重新打分，精度远高于 Bi-Encoder。
>
> **典型两阶段流程**
>
> ```
> Query → Bi-Encoder 召回 top-100（高召回）→ Cross-Encoder 精排 top-10（高精度）→ LLM
> ```
>
> **主流 Reranker**
>
> | 模型 | 特点 |
> |------|------|
> | **bge-reranker-v2-m3** | 多语言、轻量、开源，中文社区主流 |
> | **bge-reranker-large** | 英文/中文都不错，开源 |
> | **Cohere Rerank 3** | 商用 API，效果好，省运维 |
> | **Jina Reranker v2** | 多语言，支持 8K 长文档 |
> | **GPT-4 作 Reranker** | 效果好但贵且慢，适合小流量或评估集 |
>
> **实现示例（BGE Reranker）**
>
> ```python
> from FlagEmbedding import FlagReranker
> reranker = FlagReranker('BAAI/bge-reranker-v2-m3', use_fp16=True)
>
> pairs = [[query, doc] for doc in candidates]
> scores = reranker.compute_score(pairs, normalize=True)
> ranked = sorted(zip(candidates, scores), key=lambda x: -x[1])[:10]
> ```
>
> **关键参数**
>
> 1. **召回阶段 top-K（candidates）**：50~200，太小漏召，太大延迟高。
> 2. **重排后保留 top-N**：5~10，给 LLM 的最终上下文。
> 3. **批大小**：Cross-Encoder 慢，建议 batch=16~32，fp16 推理。
>
> **进阶技巧**
>
> 1. **多路召回 + 单路重排**：Dense + Sparse + Web Search 都召回，统一进 Reranker。
> 2. **分数阈值过滤**：rerank score < 0.3 直接丢弃，避免 LLM 看到不相关内容。
> 3. **LLM-based Rerank**：让 LLM 直接打分（`从 1-10 评估相关性`），效果最好但成本高。可作 baseline 评估 Reranker 质量。
> 4. **蒸馏 / 自训练 Reranker**：用 GPT-4 标注的 (q, doc, score) 蒸馏到小模型，兼顾效果和成本。
>
> **常见坑**
>
> - **没 Reranker 直接给 LLM top-5**：精度瓶颈往往在召回，不在生成。
> - **Reranker 和 Embedding 用同一家**：BGE Embedding + BGE Reranker 配套训练，效果更好。
> - **延迟敏感场景**：Reranker 一次几十毫秒，高 QPS 下需要批量 + GPU 加速。
>
> **总结**：Reranking 是 RAG 的"**精度放大器**"——成本远低于换 LLM，效果立竿见影。默认配方：BGE-M3 召回 + bge-reranker-v2-m3 精排。

### [RAG 系统在生产环境中如何优化性能和降低成本？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796745729777665)

> **答案**：
>
> **RAG 生产化的核心优化方向**
>
> 1. **延迟优化**（用户感知最直接）
> 2. **成本优化**（Token / GPU / 存储）
> 3. **质量优化**（召回率、答案准确率）
> 4. **可观测性**（监控、调优闭环）
> 5. **运维与稳定性**（缓存、限流、降级）
>
> **一、延迟优化**
>
> 1. **召回阶段**：
>    - 用 ANN 索引（HNSW）替代暴力检索。
>    - 异步并行：Embedding、BM25、Reranker 同时跑（多路召回）。
>    - Embedding 批处理 + GPU。
> 2. **生成阶段**：
>    - 用更快的 LLM（GPT-4o-mini / Claude Haiku / 自部署 vLLM）。
>    - 流式输出（SSE）让用户感知更快。
>    - KV Cache 复用（vLLM、SGLang）。
> 3. **整体链路**：
>    - 异步 pipeline，召回和 prompt 构造并行。
>    - CDN / 就近部署（向量库和 LLM 同 region）。
>
> **二、成本优化**
>
> 1. **Embedding 缓存**：query 向量缓存（Redis），重复 query 零成本。
> 2. **Answer 缓存（GPTCache）**：相似 query 直接返回历史答案，省掉 LLM 调用。
> 3. **Prompt 压缩**：LongLLMLingua / LLMLingua 把 context 压缩 50%+，省 token。
> 4. **小模型优先**：能用 GPT-4o-mini 解决的，不用 GPT-4；能用 Haiku 的，不用 Sonnet。
> 5. **路由（Routing）**：简单 query 走小模型，复杂 query 才上大模型。
> 6. **chunk_size 控制**：top-K × chunk_size 直接决定 token 量，是成本大头。
>
> **三、质量优化**
>
> 1. **召回侧**：Hybrid Search（Dense + Sparse）、Reranking、Query Rewriting。
> 2. **生成侧**：Prompt 工程（约束输出、Few-shot、思维链）。
> 3. **数据侧**：定期清洗、增量更新、坏样本回流。
> 4. **评估闭环**：用 RAGAS / TruLens 持续监控 Faithfulness、Answer Relevancy。
>
> **四、可观测性**
>
> 1. **Trace**：每条 query 记录 (召回数、相似度分数、Reranker 分数、LLM 输入输出、延迟)。
> 2. **指标**：
>    - 召回：Recall@K、IoU（与人工标注）。
>    - 生成：Faithfulness、Answer Relevancy、Hallucination Rate。
>    - 系统：P50/P95 延迟、错误率、缓存命中率。
> 3. **错误样本回流**：用户反馈（thumb up/down）→ 标注 → 评测集 → 调优。
>
> **五、运维**
>
> 1. **限流 + 降级**：QPS 超阈值时跳过 Reranker，或回退到关键词检索。
> 2. **向量库分片 + 多副本**：避免单点故障，支持水平扩展。
> 3. **模型版本管理**：Embedding / LLM 升级时双跑对比，灰度切换。
> 4. **数据隐私**：敏感数据本地部署；调用 API 时脱敏。
>
> **典型配置经验**
>
> - **延迟**：P95 < 2s（召回 < 200ms，Reranker < 200ms，LLM < 1.5s）。
> - **成本**：单次查询成本控制在 ¥0.01~¥0.05。
> - **缓存命中率**：30%+ 算健康。
> - **召回 Recall@10**：80%+ 算合格。
>
> **总结**：RAG 生产化是个系统工程，**最高杠杆的优化顺序是**：① 召回质量（决定上限）→ ② 缓存（决定成本下限）→ ③ 延迟（决定用户体验）→ ④ 监控（决定能否持续优化）。不要一开始就上花哨的 Agentic RAG，先把 baseline 跑稳。

### [Word2Vec 如何获取词向量？如何评估该训练得到的词向量的好坏？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834669457444865)

> **答案**：
>
> **Word2Vec 获取词向量的原理**
>
> Word2Vec 是一种**自监督**的词向量训练方法——用大规模无标注语料，通过预测任务自动学到词向量。两种核心架构：
>
> 1. **CBOW（Continuous Bag-of-Words）**：用上下文窗口里的词预测中心词。
>    - 输入：上下文词 one-hot。
>    - 输出：中心词概率分布。
>    - 适合小语料、高频词。
>
> 2. **Skip-gram**：用中心词预测上下文里的每个词。
>    - 输入：中心词。
>    - 输出：上下文词概率分布。
>    - 适合大语料、低频词，**实际更常用**。
>
> **词向量从哪儿来**
>
> Word2Vec 是一个**只有两层全连接**的浅层神经网络：
> ```
> Input(one-hot) → Hidden(无激活，维度=N) → Output(Softmax)
> ```
> - Hidden 层的权重矩阵 `W ∈ R^{|V|×N}`：每一行就是一个词的输入向量（input embedding）。
> - 训练完成后，**直接取 `W` 的第 i 行作为第 i 个词的词向量**（也有的取 input+output 向量的平均）。
>
> **训练优化的关键：Softmax 加速**
>
> - 全词表 Softmax 计算量太大（|V|=10 万~100 万）。
> - **Hierarchical Softmax**：用 Huffman 树把 O(|V|) 降到 O(log|V|)。
> - **Negative Sampling**：把多分类转成二分类（正样本 + 几个负样本），简单有效。
>
> **如何评估词向量的好坏**
>
> 1. **内部评估（Intrinsic）**——不依赖下游任务：
>    - **词类比（Analogy）**：`vec(国王) - vec(男) + vec(女) ≈ vec(女王)`。准确率越高越好。
>    - **词相似度（Similarity）**：人工标注的 word pair（如 man/woman, king/queen），算余弦相似度，与人工分数做 Spearman 相关。
>    - **聚类**：K-means 后看是否语义聚在一起。
>
> 2. **外部评估（Extrinsic）**——下游任务真实表现：
>    - 文本分类、NER、情感分析、机器翻译等任务上的准确率 / F1。
>    - 更可靠，但成本高。
>
> **影响词向量质量的关键因素**
>
> | 因素 | 影响 |
> |------|------|
> | **语料大小** | 越大越好，<100M tokens 训不出好向量 |
> | **语料领域** | 通用语料 vs. 领域语料（医疗、法律）|
> | **向量维度** | 100~300 较常用；太小欠拟合，太大过拟合且稀疏 |
> | **窗口大小** | 小窗口（5）→ 语义相近；大窗口（10+）→ 主题相近 |
> | **训练轮数** | 3~5 epoch 即可，过多会过拟合 |
> | **负样本数** | 5~20 较常用 |
>
> **总结**：Word2Vec 通过浅层网络 + 自监督预测任务，把词映射到稠密向量空间。**词向量就是输入嵌入矩阵 W 的行向量**。评估上以**外部任务为主，内部指标为辅**——内部指标只能看趋势，最终要看下游表现。

### [Word2Vec 到 BERT 有怎样的改进？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834675128143874)

> **答案**：
>
> **Word2Vec 的局限**
>
> 1. **静态词向量**：同一个词在不同语境下向量相同，无法解决多义词（如"苹果"是水果还是公司）。
> 2. **无上下文**：词与词之间独立编码，丢失了语序和句法信息。
> 3. **窗口有限**：只学到局部上下文（窗口内 5~10 个词），缺乏长程依赖。
> 4. **OOV 问题**：词表外的词没有向量（FastText 通过 subword 部分解决）。
> 5. **无深度**：单层表示，无法捕捉层次化语义。
>
> **BERT 的关键改进**
>
> 1. **动态上下文词向量**：
>    - 同一个词在不同句子中向量不同。
>    - 例："我吃苹果" 和 "苹果发布手机"，"苹果"的向量完全不同。
>    - **本质**：BERT 不再输出固定词向量，而是输入整句后输出"被上下文调整过"的词表示。
>
> 2. **Transformer Encoder 架构**：
>    - 多层（12~24 层）、多头自注意力（Self-Attention）。
>    - 每个 token 都能"看到"整句所有 token，捕捉长程依赖。
>
> 3. **双向建模**：
>    - Word2Vec 的 Skip-gram 是单向预测（中心词 → 上下文）。
>    - BERT 用 **MLM（Masked Language Model）** 同时从左和从右建模，真正双向。
>
> 4. **预训练任务**：
>    - **MLM**：随机 mask 15% 的 token，让模型预测被 mask 的词。
>    - **NSP**：句子级二分类（句子 B 是否接在句子 A 后）。（RoBERTa 证明 NSP 作用不大，去掉后效果更好。）
>
> 5. **Subword 分词（WordPiece）**：
>    - 解决 OOV：未见过的词被拆成子词，组合成向量。
>    - 例如 `playing` → `play` + `##ing`。
>
> 6. **迁移学习友好**：
>    - 预训练 + 微调范式：在通用语料预训练，在下游任务（分类、NER、QA）微调一个轻量头部即可。
>    - 比 Word2Vec 的"固定词向量 + 任务特定分类器"性能强一个量级。
>
> **对比表**
>
> | 维度 | Word2Vec | BERT |
> |------|---------|------|
> | 词向量类型 | 静态 | 动态（上下文相关）|
> | 架构 | 浅层（2 层）| 深层 Transformer（12~24 层）|
> | 上下文范围 | 窗口内 | 整句（甚至整篇）|
> | 训练目标 | 局部预测（CBOW/Skip-gram）| MLM + NSP |
> | OOV | 完全不支持 | WordPiece 部分解决 |
> | 下游用法 | 词向量 + 任务模型 | 直接微调 |
> | 多义消歧 | 无 | 天然支持 |
> | 计算成本 | 极低 | 较高 |
>
> **BERT 之后**
>
> - **RoBERTa**：去掉 NSP、更大 batch、更多数据，效果稳定提升。
> - **ALBERT**：参数共享、降维，轻量化。
> - **DeBERTa**：disentangled attention，长程依赖更强。
> - **GPT 系列**：Decoder-only，生成任务更强（用因果 attention 替代 MLM）。
> - **Sentence-BERT**：专为句子级嵌入设计，是 RAG 检索的基础。
>
> **总结**：从 Word2Vec 到 BERT，本质是从**静态、浅层、无上下文**到**动态、深层、双向上下文**的跨越。BERT 把 NLP 从"词向量 + 任务模型"两阶段，推进到"预训练 + 微调"统一范式，是深度学习 NLP 的分水岭。

### [如何让 AI 输出指定格式的内容，比如 JSON、表格、Markdown？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796944137134081)

> **答案**：
>
> **让 LLM 输出指定格式的核心方法**
>
> 1. **Prompt 工程约束**（轻量、不保证 100%）
> 2. **Structured Output / Function Calling**（API 原生支持）
> 3. **JSON Schema / Grammar 约束解码**（强保证）
> 4. **后处理解析 + 重试**（兜底）
>
> **一、Prompt 工程约束**
>
> ```
> 请按以下 JSON 格式回答，不要输出任何其他内容：
> {
>   "name": "字符串",
>   "age": 数字,
>   "skills": ["字符串"]
> }
>
> 示例：
> 输入：张三，30 岁，会 Python 和 SQL
> 输出：{"name":"张三","age":30,"skills":["Python","SQL"]}
> ```
>
> - 技巧：**给出 schema、给 few-shot、加"不要输出 markdown 围栏"指令**。
> - 缺点：模型可能漏字段、类型错、加额外文本，**不保证 100% 合规**。
>
> **二、Structured Output / Function Calling（推荐）**
>
> 主流厂商 API 原生支持 JSON Schema 约束：
>
> ```python
> # OpenAI
> response = client.chat.completions.create(
>     model="gpt-4o-2024-08-06",
>     messages=[...],
>     response_format={
>         "type": "json_schema",
>         "json_schema": {
>             "name": "Person",
>             "schema": {
>                 "type": "object",
>                 "properties": {
>                     "name": {"type": "string"},
>                     "age": {"type": "integer"}
>                 },
>                 "required": ["name", "age"],
>                 "additionalProperties": False
>             },
>             "strict": True
>         }
>     }
> )
> ```
>
> - **OpenAI**：`response_format=json_schema` + `strict=True`，**100% 保证**符合 schema。
> - **Anthropic**：通过 tool use 强制 JSON 结构。
> - **Gemini**：`response_schema` 参数。
> - **本地模型**：vLLM / SGLang / Outlines 用 grammar 约束解码，100% 保证。
>
> **三、Grammar / 正则约束解码（强保证）**
>
> - **Outlines / Guidance / lm-format-enforcer**：在 decoding 阶段 mask 掉不符合 schema 的 token，从根上保证输出结构。
> - 适用：本地部署开源模型（Llama、Qwen）+ 需要严格格式。
> - 代价：少量延迟、可能略微降低生成质量。
>
> ```python
> from outlines import models, generate
> model = models.transformers("mistral-7b")
> @generate.json
> class Person(BaseModel):
>     name: str
>     age: int
> person = Person(model, "张三 30 岁")  # 100% 合规
> ```
>
> **四、后处理 + 重试（兜底）**
>
> ```python
> import json, re
> from pydantic import BaseModel, ValidationError
>
> class Person(BaseModel):
>     name: str
>     age: int
>
> def parse(text, retries=2):
>     # 1. 剥离 markdown 围栏
>     text = re.sub(r"^```(json)?|```$", "", text.strip(), flags=re.M)
>     for _ in range(retries + 1):
>         try:
>             return Person.model_validate_json(text)
>         except ValidationError as e:
>             # 把错误信息丢回去让 LLM 修正
>             text = llm_repair(text, str(e))
>     raise RuntimeError("无法解析")
> ```
>
> **进阶：输出表格 / Markdown**
>
> - 表格：用 Markdown 表格语法约束（`|col1|col2|`），或用 JSON 数组再前端渲染。
> - Markdown：明确要求使用 `#`、`-`、`**` 等符号，并给示例。
>
> **常见坑**
>
> - **模型版本差异**：老 GPT-3.5 不支持 strict mode，需要 GPT-4o / Claude 3+。
> - **schema 过于复杂**：嵌套太深、union type 容易出错，建议拆分。
> - **温度太高**：T=0.7+ 时格式错乱率上升，结构化输出建议 T=0~0.3。
> - **字段名歧义**：`date` 容易和 `data` 混淆，schema 描述要清晰。
>
> **总结**：生产级方案优先级：**API 原生 Structured Output > Grammar 约束解码 > Prompt + few-shot + Pydantic 重试**。不要只靠 prompt——任何不强制约束的方案，错误率都会在 1%~5% 之间徘徊。

### [RAG 检索中的 Top-K 是什么意思？K 值如何确定？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796740365262849)

> **答案**：
>
> **Top-K 是什么**
>
> 在 RAG 检索阶段，向量库会返回 N 个候选 chunk，按相似度从高到低排序。**Top-K 就是"取前 K 个"作为送给下游（Reranker 或 LLM）的结果**。
>
> 例如：
> ```
> Query → 向量库召回 100 个候选 → 按余弦相似度排序 → 取 top-10 → Reranker 精排 → 取 top-5 → LLM
> ```
>
> **K 值如何确定**
>
> K 的选择是**召回率（Recall）和 LLM 上下文成本/噪声**之间的权衡：
> - K 太小：可能漏掉正确文档，召回率低。
> - K 太大：噪声文档混入，稀释 LLM 注意力，token 成本上升（Lost in the middle）。
>
> **典型经验值**
>
> | 阶段 | K | 备注 |
> |------|---|------|
> | Bi-Encoder 召回（向量检索）| 50~200 | 高召回，给 Reranker 用 |
> | Reranker 精排后 | 5~10 | 给 LLM 用 |
> | 无 Reranker | 3~5 | 直接给 LLM，宁少勿多 |
>
> **如何选定 K**
>
> 1. **基于评测集**：
>    - 准备 (query, ground-truth doc) 测试集。
>    - 跑 Recall@K（K=1, 3, 5, 10, 20, 50）。
>    - 选 Recall 拐点：曲线趋于平缓处的 K。
>    - 典型：Recall@5 ≈ 80%+，Recall@10 ≈ 90%+。
>
> 2. **基于 LLM 上下文窗口反推**：
>    - 上下文窗口 - 系统提示 - 用户问题 - 输出预留 = 可用 context。
>    - `K_max = 可用 context / chunk_size`。
>    - 例：GPT-4o 128K，留 4K 给其他，chunk=512 token → K_max ≈ 240（理论值）；实际为了质量，控制在 5~20。
>
> 3. **基于延迟和成本**：
>    - K 越大，LLM 推理 token 越多，成本和延迟线性上涨。
>    - 设单次查询预算 → 反推 K。
>
> **进阶策略**
>
> 1. **动态 K（Adaptive Retrieval）**：
>    - 简单问题 K=3，复杂问题 K=10。
>    - 用 LLM 或规则先判断难度。
>
> 2. **阈值过滤**：
>    - 不固定 K，而是 `score > threshold` 的全部返回。
>    - 注意不同 query 的相似度分布不同，固定阈值不稳定；建议用 **Z-score 归一化** 或 **相对差距**。
>
> 3. **多路融合 Top-K**：
>    - Dense top-20 + BM25 top-20 → RRF 融合 → 取 top-10。
>
> **常见坑**
>
> - **K=1 太激进**：哪怕最好的 Embedding，单 chunk 也不能保证答对，至少留 3 个。
> - **K=50 全塞给 LLM**：除非性能极强，否则中段文档被忽略，反而拉低答案质量。
> - **召回 K 和 LLM K 不分**：召回阶段取 50，Reranker 精排后取 5；两个 K 应分开调。
>
> **总结**：Top-K 不是单一参数，召回和生成阶段分别有 K_recall 和 K_llm。**默认配方：K_recall=50~100（向量召回）+ K_rerank=10（精排）+ K_llm=5（最终上下文）**。用 Recall@K 曲线找拐点，配合成本和延迟做最终取舍。

### [RAG 中的查询重写 Query Rewriting 是什么？如何优化检索效果？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796744526012418)

> **答案**：
>
> **Query Rewriting 是什么**
>
> 用户原始 query 往往"口语化、模糊、缺失上下文"，直接拿去检索效果差。Query Rewriting 用 LLM 把原始 query 改写成**更适合检索的形式**，本质是"用户视角 → 检索视角"的翻译。
>
> **典型场景**
>
> 1. **多轮对话的指代消解**：
>    - 用户："那它的价格呢？" → 检索"iPhone 15 价格"。
>    - 用 LLM 把"它"补全为上文实体。
>
> 2. **口语化 → 关键词化**：
>    - 用户："我电脑开不了机怎么办" → 改写为"电脑无法开机 故障排查"。
>    - 检索关键词形式比自然语言句子召回更准。
>
> 3. **缩写 / 专业术语展开**：
>    - "GAN 怎么训练" → "生成对抗网络 训练方法"。
>
> 4. **多查询生成（Multi-Query）**：
>    - 一个 query 改写成 3~5 个不同表述，并行检索，结果融合（RRF）。
>    - 例："如何提高 RAG 精度" → ["RAG 优化技巧", "RAG 召回率提升", "RAG 答案准确率改进"]。
>
> 5. **HyDE（Hypothetical Document Embeddings）**：
>    - 让 LLM 先**编一个假想答案**，再用这个假答案的向量去检索。
>    - 假答案比短 query 更接近文档的"答案文本"分布，召回率显著提升。
>
> 6. **子查询分解（Decomposition）**：
>    - 复杂问题拆成子问题，分别检索。
>    - "对比 GPT-4 和 Claude 在代码任务上的表现" → 拆成"GPT-4 代码能力" + "Claude 代码能力" + "对比"。
>
> **实现示例（LangChain）**
>
> ```python
> from langchain.retrievers.multi_query import MultiQueryRetriever
> from langchain_openai import ChatOpenAI
>
> llm = ChatOpenAI(temperature=0)
> retriever = MultiQueryRetriever.from_llm(
>     retriever=base_retriever, llm=llm
> )
> docs = retriever.invoke("如何提高 RAG 精度")  # 自动生成多 query 并融合
> ```
>
> **HyDE 示例**
>
> ```python
> from langchain.retrievers import HydraRetriever  # 或自己实现
> # Step 1: 让 LLM 生成假答案
> hypothetical = llm("请回答这个问题，简短即可：{query}")
> # Step 2: 用假答案检索
> docs = vector_db.similarity_search(hypothetical, k=5)
> ```
>
> **何时该用 Query Rewriting**
>
> | 场景 | 是否值得 |
> |------|---------|
> | 单轮、关键词明确的 query | 不需要，直接检索 |
> | 多轮对话、有指代 | **必须**改写 |
> | 长尾、口语化 query | 推荐 |
> | 客服 / FAQ 系统 | 强烈推荐（口语 → 标准）|
> | 严格预算 / 延迟敏感 | 谨慎，多一次 LLM 调用 |
>
> **常见坑**
>
> - **改写偏离原意**：LLM 改写时丢失关键词。建议保留原 query + 改写 query **并行检索**，结果融合。
> - **延迟翻倍**：每次检索多一次 LLM 调用，可用小模型（Haiku、GPT-4o-mini）。
> - **过度改写**：简单 query 也要拆成 5 个子查询，浪费成本。可加判断："只在原始 query 召回 top-1 相似度 < 阈值时才改写"。
>
> **总结**：Query Rewriting 是 RAG 检索质量的"低成本高回报"优化，**多轮对话和口语化场景必上**。优先级：指代消解 > Multi-Query > HyDE > Sub-query Decomposition。

### [如何处理 RAG 检索不到相关文档的情况？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796743116726273)

> **答案**：
>
> **检索不到文档的常见原因**
>
> 1. **召回阶段失败**：Embedding 不匹配、query 表述偏差、相关文档不在库中。
> 2. **过滤太严**：元数据 filter 把候选全干掉。
> 3. **真正的"未覆盖"**：用户问的知识库就没有。
>
> **处理策略（从轻到重）**
>
> **1. 检测：什么时候算"检索不到"**
>
> 不要只看"返回了几个文档"，要看**相关性**：
> - top-1 的相似度分数 < 阈值（如余弦 < 0.7）。
> - Reranker top-1 分数 < 阈值（如 < 0.3）。
> - 返回的文档和 query 实体完全不重合（实体匹配 = 0）。
>
> ```python
> if not docs or rerank_scores[0] < 0.3:
>     # 进入"低置信"分支
> ```
>
> **2. 自动补救（Fallback Chain）**
>
> ```
> 原始 query → 检索 → 不相关？
>    ↓ 是
> Query Rewriting（改写 query 重试）
>    ↓ 还不相关
> 放宽元数据过滤 / 提高 top-K
>    ↓ 仍不相关
> 切换检索方式（Dense → BM25 / Web Search）
>    ↓ 仍不相关
> 触发兜底响应
> ```
>
> **3. 兜底响应设计**
>
> **A. 诚实回答"不知道"**（推荐）
> ```
> 抱歉，根据知识库无法回答这个问题。
> 您可以：
> 1. 换一种问法重试
> 2. 联系人工客服
> 3. 提交问题，我们会补充相关文档
> ```
>
> **B. 转人工 / 升级**
> - 客服场景：直接转接人工坐席。
> - 把本次 query 记录到"未覆盖问题池"，运营定期补文档。
>
> **C. 用 LLM 通用知识回答（谨慎）**
> - 明确标注"以下内容不在知识库中，来自模型通用知识"。
> - 高风险领域（医疗、法律、金融）**禁止**这样做。
>
> **4. 主动澄清**
>
> - 让 LLM 判断 query 是否模糊，模糊时反问用户：
>   ```
>   您是想问 A 还是 B？请提供更多细节（如时间、对象）。
>   ```
>
> **5. 扩展召回（提升覆盖率）**
>
> - **Web Search 兜底**：本地库没有，调 Bing / Google API 检索。
> - **多源融合**：内部知识库 + 公开文档 + 历史对话。
>
> **预防：监控"未召回率"**
>
> - 指标：`no_result_rate = 检索为空 / 总查询`。
> - 健康范围：< 5%。超过需要补数据。
> - 错误样本回流：低召回的 query 收集起来，分析是 query 问题还是数据问题。
>
> **常见坑**
>
> - **只看返回数量，不看相关性**：返回 10 个不相关文档 ≠ 检索成功。
> - **兜底响应太死板**：永远回"我不知道"，用户体验差。应该给"换问法 / 转人工 / 看相关文档"的建议。
> - **偷偷用 LLM 通用知识**：用户以为答案来自知识库，实际是模型瞎编，信任崩塌。
>
> **总结**：检索不到不可怕，可怕的是不告诉用户。**核心三步**：① 用阈值或 Reranker 判断"真的不相关"；② 自动 fallback chain（改写 → 放宽 → 多源）；③ 诚实兜底 + 转人工 + 错误回流。把"未召回率"作为持续监控的核心指标。

### [说说 GloVE 技术，怎样进行训练？有哪些应用场景？相比 Word2Vec 有哪些优缺点？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834670556352514)

> **答案**：
>
> **GloVe（Global Vectors for Word Representation）**
>
> GloVe 是 Stanford 2014 年提出的词向量模型。和 Word2Vec 的"局部上下文预测"不同，GloVe 基于**全局词共现矩阵**做因式分解。
>
> **核心思想**
>
> 1. 构建词共现矩阵 `X`：`X_ij` 表示词 j 在词 i 的上下文中出现的次数。
> 2. 训练目标：让 `vec(i) · vec(j) + b_i + b_j ≈ log(X_ij)`。
>    - 直觉：两个词向量的点积应该接近它们共现次数的对数。
> 3. 损失函数加权：`f(X_ij) = (X_ij / X_max)^α`，让高频词对（如 "the"）权重不要过大。
>
> **训练流程**
>
> 1. **语料预处理**：分词、构建词表（保留 top N 个词）。
> 2. **构建共现矩阵**：滑动窗口遍历语料，统计 `X_ij`。
> 3. **初始化**：词向量 `W` 和上下文向量 `W_tilde` 各随机初始化。
> 4. **梯度下降**：用 AdaGrad 优化，最小化加权平方损失。
> 5. **最终向量**：`W + W_tilde`（两个向量的和，效果更好）。
>
> **应用场景**
>
> - 词相似度 / 词类比（与 Word2Vec 相同）。
> - 命名实体识别、文本分类、情感分析等下游任务的词向量初始化。
> - 跨语言对齐（用 CLSAA、MUSE 等方法）。
>
> **GloVe vs. Word2Vec**
>
> | 维度 | Word2Vec | GloVe |
> |------|---------|------|
> | **统计基础** | 局部上下文（滑动窗口）| 全局共现矩阵 |
> | **训练目标** | 预测（生成式）| 矩阵分解（判别式）|
> | **训练速度** | 快（线上 SGD）| 慢（需要先建共现矩阵）|
> | **内存** | 低（不需要存矩阵）| 高（共现矩阵 O(N²)）|
> | **数据效率** | 小数据仍可训 | 大数据更优 |
> | **可解释性** | 弱 | 强（共现次数显式建模）|
> | **理论支撑** | 启发式 | 数学推导更严谨 |
> | **下游表现** | 接近 | 接近（有时互有胜负）|
>
> **GloVe 的优点**
>
> 1. **利用全局信息**：Word2Vec 只看窗口内，GloVe 看全语料的共现统计。
> 2. **训练目标清晰**：直接优化"向量点积 = 共现强度"，可解释。
> 3. **大语料上稳定**：共现矩阵压缩了语料统计，重复利用高效。
>
> **GloVe 的缺点**
>
> 1. **内存消耗大**：词表 50 万时，共现矩阵 sparse 仍可能上百 GB。
> 2. **无法增量训练**：新词加入需要重建共现矩阵。
> 3. **静态词向量**：和 Word2Vec 一样，无法解决多义词。
> 4. **不擅长捕捉短语**：如 "New York" 当作两个词处理。
>
> **实践建议**
>
> - 小数据 / 增量场景：用 Word2Vec / FastText。
> - 大数据 / 一次性训练：GloVe 或 FastText（基于 PMI 的变体）。
> - 工业界主流：直接用预训练好的 GloVe / Word2Vec / FastText 向量，自训成本高、收益小。
>
> **总结**：GloVe 是 Word2Vec 的"统计学亲戚"——前者看全局共现，后者看局部窗口。两者性能接近，**工业界通常直接用预训练向量（GloVe.6B / GloVe.840B）**，自训场景不多。两者都被 BERT 时代的上下文嵌入取代，但在轻量任务和基线对比中仍有价值。

### [RAG 的完整工作流程是怎样的？核心步骤有哪些？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796738154864642)

> **答案**：
>
> **RAG 完整工作流程**
>
> ```
> [离线: Indexing]                  [在线: Query Time]
> 原始文档                          用户 Query
>    ↓                                 ↓
> 1. 数据加载                        6. Query 处理
>    (PDF/HTML/DB)                     (改写、扩展、HyDE)
>    ↓                                 ↓
> 2. 文档切割                        7. 向量化
>    (Recursive/Semantic)              (Embedding 模型)
>    ↓                                 ↓
> 3. 向量化                          8. 检索
>    (Embedding 模型)                  (Dense + BM25 + 元数据)
>    ↓                                 ↓
> 4. 入库                            9. 重排 (Rerank)
>    (Vector DB + 元数据)              (Cross-Encoder)
>    ↓                                 ↓
> 5. 索引构建                        10. Prompt 构造
>    (HNSW/IVF)                        (context + question + 规则)
>                                      ↓
>                                     11. LLM 生成
>                                      ↓
>                                     12. 后处理 + 引用
>                                         (解析、溯源、缓存)
> ```
>
> **核心步骤详解**
>
> **离线阶段（Indexing Pipeline）**
>
> 1. **数据加载（Load）**：
>    - PDF / Word / Markdown / HTML / 数据库 / API。
>    - 工具：Unstructured、LlamaIndex、LangChain Loader。
>
> 2. **文档切割（Chunking）**：
>    - 策略：RecursiveCharacterSplitter（默认）、Token Splitter、Semantic Chunker。
>    - 经验：chunk_size=500~1000 chars，overlap=50~200。
>
> 3. **向量化（Embedding）**：
>    - 模型：BGE-M3、text-embedding-3-large。
>    - 注意：批处理 + GPU 加速。
>
> 4. **元数据写入**：
>    - source、date、author、category、acl 等。
>    - 用于后续元数据过滤。
>
> 5. **入库（Vector DB）**：
>    - Milvus / Qdrant / pgvector / Pinecone。
>    - 索引：HNSW（精度优先）或 IVF-PQ（成本优先）。
>
> **在线阶段（Query Pipeline）**
>
> 6. **Query 处理**：
>    - 指代消解（多轮）。
>    - Query Rewriting / Multi-Query / HyDE。
>
> 7. **Query 向量化**：用与离线**同一个** Embedding 模型。
>
> 8. **检索（Retrieval）**：
>    - Dense（向量）：召回率高。
>    - Sparse（BM25）：精确匹配关键词。
>    - 元数据过滤：缩小范围。
>    - 多路融合：RRF（Reciprocal Rank Fusion）。
>
> 9. **重排（Reranking）**：
>    - Cross-Encoder 精排 top-K。
>    - 模型：bge-reranker-v2-m3、Cohere Rerank。
>
> 10. **Prompt 构造**：
>     - System prompt（角色、规则、输出格式）。
>     - Context（top-K 文档，编号）。
>     - User question。
>     - Few-shot（可选）。
>
> 11. **LLM 生成**：
>     - 模型：GPT-4o / Claude / 自部署 Qwen。
>     - 流式输出（SSE）改善体验。
>
> 12. **后处理**：
>     - 输出解析（Pydantic）。
>     - 引用溯源（标注 doc id）。
>     - 缓存（GPTCache）。
>     - 安全过滤（敏感词、PII）。
>
> **评估闭环（贯穿全程）**
>
> - 检索指标：Recall@K、MRR、NDCG。
> - 生成指标：Faithfulness、Answer Relevancy（用 RAGAS / TruLens）。
> - 用户反馈：thumb up/down → 错误样本池 → 调优。
>
> **关键工程组件**
>
> | 组件 | 选型 |
> |------|------|
> | 文档处理 | Unstructured、LlamaHub |
> | Embedding | BGE-M3、OpenAI text-embedding-3 |
> | 向量库 | Milvus、Qdrant、pgvector |
> | 重排 | bge-reranker、Cohere Rerank |
> | LLM | GPT-4o、Claude Sonnet、自部署 vLLM |
> | 编排 | LangChain、LlamaIndex、自研 pipeline |
> | 评估 | RAGAS、TruLens、DeepEval |
> | 监控 | LangSmith、Phoenix、自建 trace |
>
> **总结**：RAG 的核心是"**离线建好可检索的索引 + 在线召回-重排-生成三段式**"。流程不复杂，但每一步都有大量优化空间（切割策略、Embedding 选择、Reranker 配置、Prompt 设计、缓存策略）。生产级 RAG 的难点不在算法，而在**调参、评估闭环、运维稳定性**。

### [说说 FastText 技术，是否比 Word2Vec 更优越？哪些情况下更适合使用 FastText](https://www.mianshiya.com/bank/1906189461556076546/question/1821834670824787969)

> **答案**：
>
> **FastText 概述**
>
> FastText 是 Facebook 2016 年提出的词向量模型，本质上是 **Word2Vec 的扩展**——核心创新是把词拆成**子词（subword，即 n-gram 字符片段）**，让每个词的向量由其字符 n-gram 向量求和得到。
>
> **核心思想**
>
> - Word2Vec：每个词对应一个独立的向量 `vec(word)`。
> - FastText：每个词 = 其所有字符 n-gram 向量之和。
>   ```
>   vec("where") = vec("<wh") + vec("whe") + vec("her") + vec("ere") + vec("re>") + vec("<where>")
>   ```
> - 词表：除了词典中的词，还包括所有 3~6 字符的 n-gram。
>
> **训练流程**
>
> 1. 分词、构建词表 + n-gram 表。
> 2. 用 CBOW 或 Skip-gram 训练目标（与 Word2Vec 一致）。
> 3. 每个词的向量 = 词向量本身 + 其 n-gram 向量之和。
>
> **优点**
>
> 1. **OOV（Out-Of-Vocabulary）友好**：
>    - 词表外的词也能算出向量（用其 n-gram 求和）。
>    - 例：训练时没见过"unhappiness"，但见过 "un"、"<hap"、"ness>"，能拼出近似向量。
>
> 2. **形态学丰富语言效果好**：
>    - 拼音文字、词缀多的语言（土耳其语、芬兰语、德语）。
>    - 中文收益小（汉字本身已是原子单位）。
>
> 3. **拼写错误鲁棒**：
>    - "teh" 和 "the" 共享 n-gram，向量接近。
>
> 4. **可解释性增强**：
>    - 词根、词缀的向量可单独分析。
>
> **缺点**
>
> 1. **内存和计算开销大**：
>    - n-gram 数量远多于词数（百万级），存储和训练慢。
>    - 用 hashing trick 控制表大小。
>
> 2. **中文收益小**：
>    - 中文一字一义，字符 n-gram 价值有限。
>    - 中文用 FastText 不如 Word2Vec。
>
> 3. **训练更慢**：
>    - 每个词要 lookup 多个 n-gram 向量。
>
> **FastText vs. Word2Vec**
>
> | 维度 | Word2Vec | FastText |
> |------|---------|----------|
> | **表示粒度** | 词级别 | subword（字符 n-gram）|
> | **OOV 处理** | 无法处理 | 自然支持 |
> | **形态学** | 不捕捉 | 捕捉词缀、词根 |
> | **训练速度** | 快 | 慢（n-gram 多）|
> | **内存** | 小 | 大 |
> | **中文表现** | 较好 | 收益小 |
> | **拼写鲁棒** | 差 | 好 |
>
> **何时选 FastText**
>
> - **形态学丰富的拼音文字**：英语、德语、土耳其语、阿拉伯语。
> - **OOV / 拼写错误多**：社交媒体、用户评论、OCR 文本。
> - **小语种、低资源语言**：词表覆盖率低，subword 帮助大。
> - **需要细粒度相似度**：词缀相似的词（如 "play" / "playing" / "plays"）。
>
> **何时不选**
>
> - **中文 / 日文**：字符已原子化，FastText 收益小。
> - **延迟敏感**：n-gram lookup 慢。
> - **大词汇领域**：n-gram 表过大。
>
> **FastText 的额外能力**
>
> 除了词向量，FastText 还内置了**文本分类**功能（用 n-gram 平均 + 线性分类器），速度快、效果不弱——在简单分类任务上经常打 baseline。
>
> **总结**：FastText 不是"绝对优于"Word2Vec，而是在**OOV 多、形态学丰富、拼写噪声大**的场景更优。**中文场景优先 Word2Vec / BGE**；拼音文字 + OOV 多 → FastText。两者都被 BERT 时代的上下文嵌入取代，但作为轻量、可解释的 baseline 仍有一席之地。

### [RAG 系统如何处理 PDF、Word、Markdown 等不同格式文档？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796740939882498)

> **答案**：
>
> **多格式文档处理的核心挑战**
>
> 1. **格式解析**：PDF 二进制、Word OOXML、Markdown 纯文本，解析方式完全不同。
> 2. **结构保留**：标题、表格、图片、列表、脚注，不能丢。
> 3. **版面理解**：多栏、跨页、图文混排，简单切分会破坏语义。
> 4. **OCR**：扫描版 PDF 没有文字层，需要 OCR。
>
> **通用处理 pipeline**
>
> ```
> 原始文件 → 解析器 → 结构化文档（Title/Text/Table/Image）
>    ↓
> 元数据抽取（页码、章节、来源）
>    ↓
> 按元素类型分别 chunking
>    ↓
> 表格 → 转成 Markdown / HTML / JSON
> 图片 → OCR 或描述（多模态 LLM）
> 文本 → RecursiveCharacterSplitter
>    ↓
> 统一向量化 + 入库
> ```
>
> **主流解析工具**
>
> | 工具 | 特点 |
> |------|------|
> | **Unstructured** | 支持 PDF/Word/HTML/Email 等 20+ 格式，按元素（Title/Narrative/Table/ListItem）输出 |
> | **LlamaHub（LlamaIndex）** | 大量 loader，集成方便 |
> | **PyMuPDF（fitz）** | PDF 解析利器，速度快 |
> | **pdfplumber** | PDF 表格识别好 |
> | **python-docx** | Word 文档解析 |
> | **markdown** | Python 库，解析 MD |
> | **Tika** | Apache，跨格式稳定 |
> | **PaddleOCR / Tesseract** | OCR |
> | **LayoutLM / Donut** | 多模态版面理解 |
>
> **PDF 处理的特殊性**
>
> 1. **数字 PDF（有文字层）**：
>    - PyMuPDF / pdfplumber 直接抽取文字。
>    - 注意：表格识别需要专门工具（camelot、pdfplumber）。
>
> 2. **扫描 PDF（图片）**：
>    - 先 OCR（PaddleOCR、Tesseract）。
>    - 复杂版面用 LayoutLM / Donut。
>
> 3. **常见坑**：
>    - 换行符被错误插入（PDF 字符位置驱动）。
>    - 多栏排版被读成一行的乱序文本。
>    - 表格被拆成普通文本。
>    - 解决：用 Unstructured 或 LayoutParser 做版面分析。
>
> **Word / Markdown**
>
> - **Word**：python-docx 抽取段落、表格、样式；样式（H1/H2）保留为元数据。
> - **Markdown**：直接按 `#`、`##` 标题切，结构化切割（MarkdownHeaderTextSplitter）。
>
> **表格处理**
>
> - 表格不能直接 chunk。
> - 三种方案：
>   1. **转 Markdown 表格**：保留结构，LLM 能理解。
>   2. **转 JSON 行**：每行一个 chunk，元数据带列名。
>   3. **自然语言描述**：用 LLM 把表格转成文本描述。
>
> **图片处理**
>
> - **OCR**：提取文字（如扫描合同）。
> - **多模态 LLM 描述**：GPT-4V / Claude / Gemini 生成图片描述，向量化和检索用描述。
> - **特殊场景**：医疗影像、工程图纸需要专门模型。
>
> **结构化元数据保留**
>
> 无论哪种格式，chunk 都要带上：
> - `source_file`：来源文件。
> - `page`：页码。
> - `section`：所属章节（H1/H2 标题）。
> - `element_type`：text / table / image。
> - `bbox`：坐标（PDF 版面分析后）。
>
> 这些元数据是后续**元数据过滤**和**引用溯源**的基础。
>
> **典型配置**
>
> ```python
> from unstructured.partition.auto import partition
> elements = partition("input.pdf", strategy="hi_res")
> # elements 是 Title/Narrative/Table/ListItem 列表
> for el in elements:
>     chunk = text_splitter.split_text(el.text)
>     metadata = {"source": "input.pdf", "page": el.metadata.page_number, "type": el.category}
>     vector_db.add(chunk, metadata=metadata)
> ```
>
> **常见坑**
>
> - **直接 read PDF 文本**：表格、图片全丢，多栏乱序。**应该用版面分析工具**。
> - **表格 chunking**：硬切会把表格拆成无意义片段。**应该一行一 chunk 或整表作一个 chunk**。
> - **OCR 质量差**：扫描件 OCR 错误率高，需要后处理（拼写纠错）。
> - **格式 metadata 丢失**：只存文本，丢了页码 / 章节，引用溯源困难。
>
> **总结**：多格式处理的核心是**用对的工具按元素类型拆分**，而不是"一刀切文本"。PDF 用 Unstructured / PyMuPDF + 版面分析，表格转 Markdown，图片用多模态 LLM 描述，结构化元数据全程保留。这一步做好，RAG 上限就稳了。

### [如何评估 RAG 系统的效果？检索和生成分别看哪些指标？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796745104826370)

> **答案**：
>
> **RAG 评估的两大维度**
>
> RAG = Retrieval + Generation，必须**分别评估、再合起来看**——只看端到端答案对错，无法定位瓶颈是召回不行还是生成不行。
>
> ```
> 检索指标（独立评估召回质量） + 生成指标（评估最终答案质量）
>                 ↓                   ↓
>             RAGAS / TruLens 统一打分
> ```
>
> **一、检索阶段指标**
>
> 需要标注数据：`(query, ground_truth_doc_id_set)`。
>
> 1. **Recall@K**：top-K 中包含正确文档的比例。
>    - **最关键**。Recall@5 < 80%，说明召回有严重问题。
> 2. **Precision@K**：top-K 中相关文档的比例。
> 3. **MRR（Mean Reciprocal Rank）**：第一个相关文档位置的倒数的平均。
>    - 反映"用户能否快速找到答案"。
> 4. **NDCG@K**：考虑排序位置的加权相关度。
>    - 比 Precision 更细粒度，常用于学术对比。
> 5. **Hit Rate@K**：top-K 中至少有一个相关文档的 query 比例。
>
> **评估方式**：
> - 离线：人工标注 100~500 个 (q, gold_doc)。
> - 在线：用户 thumb up 反馈作为弱标注。
>
> **二、生成阶段指标**
>
> 1. **Faithfulness（忠实度）**：答案是否完全来自检索到的 context，有没有幻觉。
>    - 用 LLM-as-judge 自动评估：抽取答案中所有 claim，逐条判断是否能被 context 支持。
>    - **最重要的生成指标**——Faithfulness 低 = 模型在编造。
> 2. **Answer Relevancy**：答案是否切题（与 query 相关）。
>    - 用 LLM 反向生成问题，与原 query 算相似度。
> 3. **Context Precision**：检索到的 context 中，有多少是真正相关的。
> 4. **Context Recall**：相关 context 是否都被检索到（与 Recall@K 类似但更语义化）。
> 5. **Answer Correctness**：与 ground truth 答案对比（人工或 LLM judge）。
>
> **RAGAS 框架的核心四指标**：
> - Faithfulness
> - Answer Relevancy
> - Context Precision
> - Context Recall
>
> **三、端到端业务指标**
>
> 1. **Answer Correctness**：人工或 LLM-as-judge 评分。
> 2. **用户满意度**：thumb up rate、CSAT、NPS。
> 3. **任务完成率**：用户问题是否被解决（对话是否还要转人工）。
> 4. **首次响应解决率（FCR）**：一次回答就解决问题的比例。
>
> **四、系统性能指标（运维视角）**
>
> - 延迟：P50/P95/P99（召回、重排、生成分别看）。
> - 吞吐量：QPS。
> - 错误率：超时、API 失败、解析失败。
> - 缓存命中率。
> - 成本：单次查询 ¥。
>
> **评估工具**
>
> | 工具 | 特点 |
> |------|------|
> | **RAGAS** | 最常用，4 大核心指标，开源 Python 库 |
> | **TruLens** | 可视化 dashboard，支持多版本对比 |
> | **DeepEval** | pytest 风格，集成 CI 友好 |
> | **LangSmith** | LangChain 官方，trace + 评估一体 |
> | **Phoenix** | 开源可观测平台 |
> | **LLM-as-judge** | 直接用 GPT-4 / Claude 打分 |
>
> **评估集构建**
>
> 1. **真实 query 抽样**：从生产日志抽 200~500 条。
> 2. **答案标注**：人工标注 ground truth answer + 相关 doc。
> 3. **难度分层**：简单 / 中等 / 困难，分开看指标。
> 4. **持续扩充**：线上低分样本回流到评测集。
>
> **典型 baseline**
>
> | 指标 | 合格 | 良好 |
> |------|------|------|
> | Recall@5 | 75% | 90% |
> | Faithfulness | 80% | 95% |
> | Answer Relevancy | 75% | 90% |
> | Answer Correctness | 70% | 85% |
>
> **评估流程**
>
> 1. 离线：每次改动（换 Embedding、改 Prompt）跑评测集，看四指标。
> 2. A/B：线上灰度对比。
> 3. 持续监控：每日/每周跑评测，报警异常。
>
> **总结**：RAG 评估必须**检索、生成分开看**——检索指标定位召回瓶颈（Recall@K、MRR），生成指标定位答案质量（Faithfulness、Answer Relevancy）。**RAGAS 四指标是行业事实标准**，配合自建评测集 + LLM-as-judge + A/B 灰度，形成持续优化闭环。

### [RAG 中文档切割的 chunk_size 和 overlap 应该如何设置？](https://www.mianshiya.com/bank/1906189461556076546/question/1991796741313175553)

> **答案**：
>
> **chunk_size 和 overlap 的本质**
>
> - `chunk_size`：每块的大小（按字符 / token 计）。
> - `overlap`：相邻块之间的重叠部分。
>
> 两者决定 RAG 的**召回精度**和**上下文完整性**，是 RAG 调参中最敏感的旋钮之一。
>
> **chunk_size 怎么选**
>
> 太小（100~300 chars）：
> - 优点：语义聚焦，向量纯，**召回精度高**。
> - 缺点：上下文片段化，LLM 看不到完整逻辑，**答案质量可能下降**。
> - 适合：FAQ、单句问答、精准事实检索。
>
> 太大（2000+ chars）：
> - 优点：上下文完整，LLM 能看到全貌。
> - 缺点：向量语义稀释，**召回率下降**；单 chunk 占用太多 LLM token。
> - 适合：长篇叙事、政策条款、需要背景的复杂问题。
>
> 经验范围：
> - **通用问答**：500~1000 chars（约 100~200 token）。
> - **技术文档**：800~1500 chars。
> - **法律 / 合同**：按条款自然边界切，不按字符。
> - **代码**：按函数 / 类切，不要硬切。
>
> **overlap 怎么选**
>
> - 目的：避免关键句子被硬切，造成信息丢失。
> - 经验值：chunk_size 的 **10%~20%**。
> - 例：chunk_size=500，overlap=50~100。
>
> 太大（>30%）：
> - 优点：上下文连续。
> - 缺点：存储和检索成本上涨（重复内容多），召回结果冗余。
>
> 为 0：
> - 风险：边界正好切断关键句，召回精度受损。
>
> **关键考量因素**
>
> 1. **Embedding 模型的最大输入**：
>    - 多数 BERT 类 Embedding 上限 512 token。
>    - chunk_size 不能超过这个上限（否则会被截断）。
>    - jina-embeddings-v2 / bge-m3 支持 8K+，可以切更大。
>
> 2. **LLM 上下文窗口**：
>    - 反推：`K_max × chunk_size ≤ 可用 context`。
>    - GPT-4o 128K → 理论可塞几十个 chunk，但实际为了质量控制在 5~20。
>
> 3. **文档类型**：
>    - 叙事类（小说、报道）：可以大一点，让上下文连贯。
>    - 事实类（FAQ、字典）：必须小，让向量精准。
>    - 代码 / 表格：按结构切，不要硬切。
>
> **调参方法**
>
> 1. **A/B 测试**：
>    - 准备评测集（query + ground truth doc）。
>    - 跑不同 chunk_size（如 300/500/800/1200）和 overlap（10%/15%/20%）。
>    - 看 Recall@K 和 Faithfulness。
>
> 2. **找拐点**：
>    - 画 Recall vs chunk_size 曲线。
>    - 选 Recall 最高点附近，结合 LLM 答案质量最终决定。
>
> 3. **不同 chunk_size 混用**：
>    - 小块（300）用于向量检索（精准召回）。
>    - 命中后返回其所属大块（1500）给 LLM（完整上下文）。
>    - 即 **Small-to-Big / Parent-Child** 策略。
>
> **经验配方**
>
> | 场景 | chunk_size | overlap |
> |------|-----------|---------|
> | 通用问答 | 500 chars | 50 |
> | 长篇理解 | 1000 chars | 100 |
> | FAQ 精准检索 | 200~300 chars | 0~30 |
> | 代码 | 按函数切 | 不硬切 |
> | 表格 | 整表 / 一行 | 不硬切 |
> | 法律条款 | 按条款编号切 | 不需要 |
>
> **常见坑**
>
> - **chunk_size 固定不调**：不同文档类型用同一个 size，召回质量参差不齐。
> - **overlap=0**：边界切断关键句子，召回时少一半信息。
> - **chunk_size 跟着 Embedding 上限走**：BERT 512 token 上限 ≠ chunk_size 应该=512，多数情况 200 token 效果更好。
> - **忽视父子块策略**：单纯调 chunk_size 难以兼顾召回精度和上下文完整，Small-to-Big 是更优解。
>
> **总结**：chunk_size 和 overlap 不是"set and forget"，而是 RAG 调参的核心旋钮。**默认从 `chunk_size=500, overlap=50` 开始**，用评测集 A/B 找最优，复杂场景上 Small-to-Big（小块检索 + 大块上下文）。

### [说一说负采样技术在 Word2Vec 中的运用。](https://www.mianshiya.com/bank/1906189461556076546/question/1821834670023675906)

> **答案**：
>
> **负采样（Negative Sampling）的本质**
>
> Word2Vec 的 Skip-gram 训练目标是：
> ```
> 最大化：P(context | center) = softmax(vec(center) · vec(context))
> ```
> - 全词表 softmax 计算量 O(|V|)，词表 10 万~100 万时无法接受。
> - 负采样的核心思路：**把多分类转成二分类**——
>   - 正样本：中心词的真实上下文词。
>   - 负样本：随机采几个"不在上下文"的词，让模型学会"区分"。
>
> **新的目标函数**
>
> ```
> log σ(vec(center) · vec(pos)) + Σ log σ(-vec(center) · vec(neg_i))
> ```
> - σ 是 sigmoid。
> - 正样本希望点积大，负样本希望点积小（接近 0）。
> - 计算量从 O(|V|) 降到 O(1+k)，k 通常 5~20。
>
> **负样本怎么采**
>
> 1. **均匀采样**：每个词被采为负样本的概率相等。
>    - 缺点：常见词（the、is）被频繁采，信号弱。
>
> 2. **按频率 3/4 次方采样（Word2Vec 默认）**：
>    - `P(w) ∝ count(w)^0.75`。
>    - **为什么 3/4 次方？**
>      - 高频词被压制（避免负样本全是最常见的 the、of）。
>      - 低频词被相对提升（增加低频词的训练机会）。
>      - 实验经验值，效果好。
>
> 3. **Hard Negative Mining**：故意选"难区分"的负样本（与正样本语义相近）。
>    - 让模型学更细的判别。
>    - 在 RAG / 推荐中常用。
>
> **为什么有效**
>
> - Softmax 训练时，每个正样本都要和全词表对比，开销巨大。
> - 负采样每个正样本只对比 1+k 个，**计算量降低 10000 倍**。
> - 训练效果几乎无损——负采样学到的词向量质量接近 hierarchical softmax。
> - 工业实现（gensim、original Word2Vec）默认用负采样。
>
> **参数选择**
>
> | 参数 | 经验值 | 影响 |
> |------|--------|------|
> | 负样本数 K | 小数据 5~10，大数据 2~5 | K 大训练慢但更准 |
> | 采样分布 | `count^0.75` | Word2Vec 默认 |
> | 迭代轮数 | 3~5 | 太多过拟合 |
> | 学习率 | 0.025 起步，linear decay | 太大震荡，太小慢 |
>
> **为什么 3/4 次方不是 1/2 或 1**
>
> - 直观：高频词（"the"）几乎和所有词共现，作为负样本信号弱；低频词（"光合作用"）才是"真负样本"。
> - `^0.75` 平衡了：
>   - 高频词仍占多数（保持训练效率）。
>   - 低频词有合理出现概率（不被淹没）。
>   - 实验证明优于 `^1`（按原始频率）和 `^0.5`（过度抑制高频）。
>
> **负采样在其他任务中的应用**
>
> 负采样思想不仅用于 Word2Vec，是"对比学习"的基石：
> - **推荐系统**：用户-物品交互，正样本是点击的，负样本是未点击的。
> - **Sentence Embedding（SimCSE）**：正样本是同句的不同 dropout，负样本是其他句。
> - **CLIP**：图文匹配，正样本是配对的图-文，负样本是 batch 内不配对的。
> - **RAG**：Dense Retrieval 训练时，正样本是相关文档，负样本是不相关文档（常用 in-batch negatives + hard negatives）。
>
> **常见坑**
>
> - **负样本全是高频词**：模型只学会"区分高频 vs 低频"，没学到语义。
> - **负样本太少（K=1）**：训练信号弱，向量质量差。
> - **负样本太多（K=50）**：训练慢，且容易把相关词错认为负样本。
> - **未做频率加权**：均匀采样导致常用词训练不足。
>
> **总结**：负采样是 Word2Vec 能在大词表下高效训练的关键——把 O(|V|) 的 softmax 降到 O(1+k)。**`count^0.75` 的采样分布**是经验最优的高频压制策略。负采样的思想已被对比学习（SimCSE、CLIP、推荐）发扬光大，是现代自监督学习的核心范式。

### [什么是词嵌入（Word Embedding）？有哪些常见的词嵌入方法？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834668647944194)

> **答案**：
>
> **词嵌入（Word Embedding）是什么**
>
> 把单词、句子甚至文档映射到**低维稠密实向量**（如 100~3072 维），让**语义相似的词在向量空间中距离接近**。
>
> ```
> "猫"   → [0.2, -0.5, 0.8, ...]
> "狗"   → [0.3, -0.4, 0.7, ...]   # 与"猫"距离近
> "汽车" → [-0.5, 0.9, -0.2, ...]  # 与"猫""狗"距离远
> ```
>
> 这种向量化让机器能"理解"语义——通过向量运算做相似度、聚类、分类、检索。
>
> **为什么需要词嵌入**
>
> - **传统方法（One-hot、TF-IDF）的问题**：
>   - One-hot 维度等于词表大小（10万+），稀疏、无法表达相似度。
>   - 所有词两两正交，"猫"和"狗"的距离 = "猫"和"汽车"的距离。
> - **嵌入的优势**：
>   - 低维稠密（几百到几千维）。
>   - 语义信息编码在向量分布中。
>   - 可做算术：`vec(国王) - vec(男) + vec(女) ≈ vec(女王)`。
>
> **主流词嵌入方法（按时代演进）**
>
> **第一代：基于矩阵分解（2000s 初）**
> - **LSA（Latent Semantic Analysis）**：对词-文档共现矩阵做 SVD。
> - **PLSA / LDA**：概率主题模型。
> - 特点：全局统计，但维度高、稀疏。
>
> **第二代：基于神经网络（Word2Vec 时代，2013~）**
> - **Word2Vec（Mikolov, 2013）**：
>   - CBOW / Skip-gram，浅层网络预测。
>   - 词向量 = 输入嵌入矩阵的行向量。
> - **GloVe（Stanford, 2014）**：
>   - 基于全局词共现矩阵的因式分解。
> - **FastText（Facebook, 2016）**：
>   - Word2Vec + subword（字符 n-gram），解决 OOV。
> - 特点：**静态词向量**——一个词永远对应同一向量，无法解决多义词。
>
> **第三代：上下文嵌入（BERT 时代，2018~）**
> - **ELMo（2018）**：双向 LSTM，词向量依赖上下文。
> - **BERT（2018）**：Transformer Encoder + MLM，真正双向。
> - **GPT 系列**：Transformer Decoder，自回归生成。
> - 特点：**动态词向量**——同一个词在不同句子中向量不同，解决多义消歧。
>
> **第四代：句子级 + 检索优化（2020~）**
> - **Sentence-BERT（SBERT）**：专为句子相似度优化。
> - **SimCSE**：对比学习训练句子嵌入。
> - **BGE / E5 / GTE**：检索专用嵌入，C-MTEB 顶尖。
> - **多模态嵌入**：CLIP（图文）、ImageBind（多模态）。
>
> **评估方法**
>
> 1. **内部评估**：
>    - 词类比（Analogy）：`king - man + woman ≈ queen`。
>    - 词相似度：与人工标注的 Spearman 相关。
> 2. **外部评估**：下游任务（分类、NER、检索）准确率。
> 3. **检索任务**：C-MTEB / MTEB 榜单，Recall@K。
>
> **应用场景**
>
> | 场景 | 推荐方法 |
> |------|---------|
> | 文本分类 baseline | Word2Vec / FastText + SVM |
> | 词义相似度 | BERT / SBERT |
> | 句子检索 / RAG | BGE-M3 / text-embedding-3 |
> | 跨语言任务 | multilingual-e5 / BGE-M3 |
> | 长文档检索 | jina-embeddings / bge-m3 |
> | 推荐系统 | Item2Vec / 两塔模型 |
>
> **演进趋势**
>
> - 静态 → 动态（上下文相关）
> - 浅层 → 深层（Transformer）
> - 词级 → 句子级 → 文档级
> - 通用 → 任务专用（检索、分类、推荐）
> - 单语 → 多语言 → 多模态
>
> **总结**：词嵌入是 NLP 的基础设施，本质是"**用向量空间结构编码语义关系**"。从 Word2Vec 的静态词向量，到 BERT 的动态上下文嵌入，再到 BGE-M3 的检索优化嵌入，每一代都在解决上一代的痛点。今天的 RAG、推荐、搜索、对话系统，都建立在这一脉络之上。

### [Word2Vec 有哪些加速方法？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834669184815106)

> **答案**：
>
> **Word2Vec 加速的必要性**
>
> 原始 Skip-gram 用全词表 softmax 计算每一步梯度，复杂度 O(|V|)。
> - 词表 10 万 → 每个样本 10 万次运算。
> - 训练 1B tokens 的语料 → 计算量爆炸。
>
> 加速技术分两类：**计算加速**（softmax 近似）和**工程加速**（并行、I/O 优化）。
>
> **一、Softmax 近似（计算加速）**
>
> 1. **Hierarchical Softmax（层次 softmax）**：
>    - 用 Huffman 树把词表组织成二叉树，每个词对应从根到叶的路径。
>    - 预测从 O(|V|) 多分类 → O(log|V|) 个二分类（沿树路径）。
>    - 词频越高的词路径越短（Huffman 编码），高频词训练更快。
>    - 缺点：树结构构建后无法变化，叶子节点位置影响训练效果。
>
> 2. **Negative Sampling（负采样）**：
>    - 把多分类转成二分类：正样本 + 几个随机负样本。
>    - 复杂度从 O(|V|) → O(1+k)，k 通常 5~20。
>    - **最常用**，简单有效。
>    - 负样本采样分布 `count^0.75`，平衡高低频。
>
> 3. **NCE（Noise Contrastive Estimation）**：
>    - 负采样的"理论严格版"，但实际效果与 NegSampling 接近。
>    - 工业界少见，学术研究用。
>
> **二、工程加速**
>
> 1. **向量化 / 批量化**：
>    - batch 训练（mini-batch SGD）。
>    - 用 BLAS / GPU 加速矩阵运算。
>
> 2. **Hogwild（异步并行 SGD）**：
>    - 多线程无锁更新参数。
>    - 原始 Word2Vec C 实现 default 开启，加速 3~5 倍。
>    - 假设：参数稀疏访问，冲突概率低。
>
> 3. **负样本表预生成**：
>    - 预计算一个 1 亿词频的随机表，按 `count^0.75` 加权。
>    - 训练时 O(1) 查找，避免每次采样开销。
>
> 4. **子采样高频词（Subsampling）**：
>    - 高频词（the、of）训练信号弱，反而拖慢收敛。
>    - 按 `P(discard) = 1 - sqrt(t / f(w))` 随机丢弃。
>    - t 一般 1e-4，丢弃 90% 的高频词，**速度提升 + 质量提升**。
>
> 5. **内存映射 + 流式读取**：
>    - 大语料用 mmap，不全载入内存。
>    - 分 shard 并行训练。
>
> 6. **GPU 加速**：
>    - Gensim 4 / torch 用 GPU 训练，比 CPU 快 10~50 倍。
>
> **三、I/O 优化**
>
> 1. **预处理一次，多次训练**：分词、清洗一次，存为二进制格式（如 gensim LineSentence）。
> 2. **词表预过滤**：低频词（freq < 5）直接丢，缩小词表。
> 3. **预先 shuffle**：每 epoch 前打乱语料顺序，提升 SGD 收敛。
>
> **四、参数选择对速度的影响**
>
> | 参数 | 影响 |
> |------|------|
> | 维度（dim）| 100~300 较快；500+ 显著变慢 |
> | 窗口（window）| 小窗口快；大窗口慢 |
> | 负样本数（negative）| K=5 快；K=20 慢一倍 |
> | epoch 数 | 3~5 够，多则慢且过拟合 |
> | 工作者数（workers）| 多线程，CPU 核数附近最佳 |
>
> **典型加速效果对比**
>
> | 优化 | 加速倍数 |
> |------|---------|
> | 全 softmax（baseline）| 1x |
> | + Hierarchical Softmax | 100x |
> | + Negative Sampling | 1000x |
> | + Hogwild 并行 | 3000x |
> | + Subsampling 高频词 | 5000x |
> | + GPU | 50000x+ |
>
> **实践建议**
>
> - 用 Gensim 4 / FastText 官方实现，已经把上述优化都内置。
> - 小语料（< 1B tokens）：CPU 足够，几小时搞定。
> - 大语料（10B+ tokens）：GPU + 分布式（Spark、PyTorch DDP）。
> - 不要从零实现 Word2Vec，**直接用现成库**。
>
> **总结**：Word2Vec 的"快"来自三个关键加速——**负采样（O(|V|)→O(1+k)）+ Hogwild 多线程无锁 + 高频词子采样**。这三者让 Word2Vec 能在几小时内训完亿级 token 的语料，是它能被工业广泛使用的根本原因。

### [是否使用 Word2Vec 训练过数据？在这个过程中，如何获取语料？如何选择超参数？语料、词表和维度大小如何确定？怎样把握训练时长？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834668916379649)

> **答案**：
>
> **Word2Vec 训练实战经验**
>
> **一、如何获取语料**
>
> 1. **通用语料**（适合 baseline）：
>    - 中文：维基百科 dump、百度百科、清华 THUCNews、悟道语料。
>    - 英文：Wikipedia dump、Common Crawl、BooksCorpus、One Billion Word Benchmark。
>    - 工具：`gensim.scripts.segment_wiki`、`wikiextractor`。
>
> 2. **领域语料**（适合专业场景）：
>    - 内部业务文本（客服、文档、评论）。
>    - 行业公开数据集（法律判例、医疗文献、技术博客爬取）。
>    - 优先级：**领域 > 通用**——领域内 100M tokens 比通用 1B tokens 更有用。
>
> 3. **数据清洗**：
>    - 去重（minhash、SimHash）。
>    - 去乱码、HTML 标签、模板内容。
>    - 繁简转换、全半角统一。
>    - 敏感信息脱敏（PII）。
>    - 中文分词（jieba、LTP、HanLP）或字级别。
>
> **二、超参数选择**
>
> | 超参数 | 经验值 | 说明 |
> |--------|--------|------|
> | `vector_size`（维度）| 100~300 | 小数据 100，大数据 200~300；> 500 收益递减 |
> | `window`（窗口）| 5~10 | 语义相近用 5，主题相近用 10+ |
> | `min_count` | 5~10 | 低于此频次的词丢弃，缩小词表 |
> | `negative` | 5~15 | 负样本数，小数据 5，大数据 5~10 |
> | `epochs` | 3~10 | 小语料 10+，大语料 3~5 |
> | `alpha` | 0.025 起步 | 学习率，linear decay 到 min_alpha=1e-4 |
> | `sg` | 1 (Skip-gram) | CBOW（0）快但低频词差，Skip-gram（1）慢但低频词好 |
> | `sample` | 1e-3~1e-5 | 高频词子采样阈值，1e-4 较常用 |
> | `workers` | CPU 核数 | 多线程并行 |
>
> **经验法则**：
> - 中文用 Skip-gram + 字级别 → 不分词也能跑得不错。
> - 想要"主题相似"用大窗口（10+），想要"语义相似"用小窗口（5）。
> - 维度和语料规模成正比：< 100M tokens 用 100 维；> 1B tokens 才考虑 300 维。
>
> **三、词表大小如何确定**
>
> - 词表 = `min_count` 过滤后的唯一词数。
> - 中文：字级别词表 < 1 万；词级别 5 万~30 万。
> - 英文：词级别 5 万~30 万；subword（BPE）3 万~5 万。
> - **不要无脑扩大词表**：低频词训练不足，向量质量差；词表大训练慢。
>
> **四、维度大小如何确定**
>
> - 维度 = `vector_size`，典型 100~300。
> - 经验：维度 ≈ 词表大小的 1/100 ~ 1/1000。
> - 例：词表 5 万 → 维度 100~300。
> - 太小（< 50）：欠拟合，无法表达语义。
> - 太大（> 500）：过拟合，存储和检索成本高，效果反而下降。
>
> **五、训练时长怎么把握**
>
> **判断收敛**：
> - 监控 loss 曲线，loss 趋于平稳即可停止。
> - gensim 的 `Word2Vec` 有 `callbacks` 可以打日志。
>
> **经验时长**：
> - 100M tokens，CPU 8 核，3~5 epoch：1~3 小时。
> - 1B tokens，CPU 16 核，5 epoch：10~20 小时。
> - GPU 训练可以快 5~20 倍。
>
> **避免过拟合**：
> - 不要无限训，3~5 epoch 通常够。
> - 看外部任务指标（词类比、相似度），停止时机选在指标不再提升的点。
>
> **六、训练完成的验证**
>
> 1. **词类比测试**：
>    ```python
>    model.wv.most_similar(positive=["国王", "女"], negative=["男"])
>    # 应该返回"女王"附近词
>    ```
> 2. **词相似度**：人工抽 20~50 个词对，看相似度排序。
> 3. **下游任务**：直接用词向量跑一个文本分类 baseline，看 F1。
>
> **七、训练完的向量怎么用**
>
> 1. 保存：`model.save("model.w2v")` 或导出为纯文本（`model.wv.save_word2vec_format`）。
> 2. 加载：`KeyedVectors.load_word2vec_format(...)`。
> 3. 应用：
>    - 文本分类：词向量平均 + SVM / LR。
>    - 句子相似度：word mover's distance 或平均向量余弦。
>    - 命名实体识别：作为 BiLSTM-CRF 的嵌入层。
>
> **常见坑**
>
> - **没有清洗数据**：垃圾进，垃圾出，词向量质量差。
> - **词表过大**：保留所有低频词，训练慢且向量差。`min_count` 必调。
> - **维度太高**：300 维数据少，过拟合。小语料用 100 维。
> - **窗口太小（2~3）**：学到的是字面共现，不是语义。
> - **不分词直接训（中文）**：除非字级别，否则效果差。
> - **训练 epoch 太多**：过拟合，外部任务反而变差。
>
> **总结**：Word2Vec 训练的核心是"**数据 > 参数**"。10 倍数据带来的提升 > 调任何超参数。默认配置：`Skip-gram + 维度 200 + 窗口 5 + min_count 5 + negative 10 + epoch 5`，再根据下游任务微调。最终用外部任务验证，而不是迷信 loss 数值。
