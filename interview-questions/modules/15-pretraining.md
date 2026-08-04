# 预训练与数据工程

> **本模块为原创补写，非面试鸭题库爬取内容**。基于公开论文与工程实践整理，覆盖原 14 个模块未深入的「预训练与数据工程」领域，共 9 题。完整索引见 [bank-llm-by-topic.md](../bank-llm-by-topic.md)。

---

> 共 9 题

### 什么是 Scaling Law？Chinchilla 法则如何指导「算力 / 参数 / 数据」三者分配？

> **答案**：
>
> **一、Scaling Law 的核心命题**
>
> Scaling Law（Kaplan et al. 2020, OpenAI）指出：**在足够大的规模下，LLM 的验证损失随算力、参数量、数据量呈幂律（power law）下降**——即每增加 10 倍算力，loss 按可预测比例下降。
>
> 形式化表达：
>
> ```
> L(N, D) = E + A / N^α + B / D^β
> ```
>
> 其中 `N` 为参数量（不含 embedding），`D` 为训练 token 数，`E` 为不可约损失，`A/B/α/β` 由拟合实验数据得到。
>
> **关键含义**：loss 不是"调参调出来的"，而是"算力堆出来的"——可外推、可预测。这把 LLM 训练从炼丹变成了工程。
>
> **二、Chinchilla 法则（Hoffmann et al. 2022, DeepMind）**
>
> OpenAI 原文给出的配比偏「参数多、数据少」（GPT-3：175B 参数 / 300B token）。DeepMind 重新拟合后得到**修正结论**：
>
> > **在固定算力预算 C 下，最优分配是 `N ∝ C^0.5`，`D ∝ C^0.5`——参数和数据 1:1 增长。**
>
> 实操公式（Chinchilla paper Table 3）：
>
> ```
> N_opt ≈ 1.74 × C^0.50
> D_opt ≈ 58 × C^0.50
> ```
>
> 即每增加 1 个参数，应配 ~20 个 token 的训练数据。
>
> **Chinchilla 模型本身**：70B 参数 + 1.4T token = 算力与 GPT-3 同等，但效果超过 GPT-3 175B，验证了「GPT-3 数据严重不足」。
>
> **三、对工程实践的指导**
>
> | 总参数量 | Chinchilla 最优 token 数 | 实际训练 token（典型） |
> |----------|--------------------------|------------------------|
> | 7B       | 140B                     | 1.5T~2T（Llama 2/3）   |
> | 70B      | 1.4T                     | 15T+（Llama 3.1）      |
> | 405B     | 8T                       | 15T+（Llama 3.1）      |
>
> **观察**：现代模型**普遍过训练（over-trained）**——Llama 3 8B 训了 15T token，是 Chinchilla 最优的 ~10 倍。原因：
>
> 1. **推理成本主导**：训练多花一次，推理省无数次 → 小模型 + 多数据 更划算。
> 2. **下游微调友好**：过训练的基座在 SFT / LoRA 下表现更稳。
> 3. **数据墙**：高质量网络数据接近枯竭（Common Crawl 已被反复洗）。
>
> **四、计算算力预算**
>
> 经验公式（Chinchilla）：
>
> ```
> C ≈ 6 × N × D   （FLOPs）
> ```
>
> 例：70B × 1.4T token → C ≈ 6 × 70e9 × 1.4e12 = 5.88e23 FLOPs ≈ **588 ExaFLOPs**。
>
> 折算 H100（989 TFLOPs bf16，MFU 取 0.4）：
>
> ```
> GPU-hours ≈ C / (MFU × H100_throughput) ≈ 5.88e23 / (0.4 × 989e12) / 3600 ≈ 413k H100-hours
> ```
>
> 2048 张 H100 跑 ~10 天。这是 Llama 3 70B 级别训练的典型规模。
>
> **五、Scaling Law 的局限**
>
> 1. **数据质量没纳入**：公式只看 token 数，但「质量」无法用 D 表达。高质量数据的实际收益远大于 D 的预测。
> 2. **架构变了就失效**：MoE、推理模型（o1）改变了「参数 / 数据 / 推理算力」的权衡。
> 3. ** emergent abilities 不可预测**：scaling law 只预测 loss，不预测下游能力何时涌现。
> 4. **过训练破坏配比**：现代模型偏离 Chinchilla 配比，公式需要重新拟合。
>
> **六、最新进展（2024-2026）**
>
> - **Llama 3 / 4**：明确走「小模型过训练」路线，8B 训 15T+ token，推理性价比极致。
> - **DeepSeek V3 / R1**：MoE 671B（37B 激活），训练仅 555K H800-hours，打破 Dense Scaling 的成本曲线。
> - **Chinchilla 之后**：社区开始拟合「推理算力 vs 训练算力」的新 scaling law（OpenAI o1 论文）。
>
> **总结**：Scaling Law 把 LLM 训练变成了可预测工程——**算力 → 参数 / 数据配比 → loss**。Chinchilla 给出 1:20 配比，但现代模型为推理成本考虑普遍 5-10× 过训练。理解这套数字是估算训练预算、评估模型性价比的基础。

### MoE（混合专家）架构原理是什么？DeepSeek / Mixtral 的路由是怎么设计的？

> **答案**：
>
> **一、MoE 的核心思想**
>
> MoE（Mixture of Experts，混合专家）= **稀疏激活**：模型总参数量大，但每次前向只激活一部分「专家」子网络。
>
> 对比 Dense（稠密）模型：
>
> | 维度          | Dense（如 Llama 70B）   | MoE（如 Mixtral 8x7B）         |
> |---------------|-------------------------|--------------------------------|
> | 总参数量      | 70B 全部参与每次前向    | 47B 总参数，每次只用 ~13B       |
> | 计算量（FLOPs）| 与参数量成正比          | 与激活参数量成正比，远小于总参 |
> | 训练成本      | 高                      | 低（同等能力下省 ~2-5 倍）      |
> | 推理显存      | 只需装权重              | 需装全部专家，显存开销大       |
> | 路由不确定性  | 无                      | 有（token 分配不均问题）       |
>
> **核心收益**：**用显存换算力**——同等推理质量下，FLOPs 大幅下降；同等 FLOPs 下，能力大幅提升。
>
> **二、MoE 的数学结构**
>
> 标准 MoE 替换 FFN 层：
>
> ```
> Dense FFN:   y = W2 · activation(W1 · x)
>
> MoE FFN:     y = Σ_i G(x)_i · FFN_i(x)
>              其中 G(x) = Top-K(softmax(W_g · x))
>              通常 K=1 或 K=2,专家数 E=8/64/256
> ```
>
> - `G(x)` 是门控（router），输出每个专家的权重。
> - **Top-K 稀疏**：只选 K 个权重最高的专家，其余置零。
> - 被选中专家的输出加权求和。
>
> **Mixtral 8x7B**：E=8, K=2，每个 token 选 2 个专家。
>
> **DeepSeek V3**：E=256（fine-grained 细粒度）, K=8，每个 token 选 8 个；再加 1 个共享专家（shared expert），所有 token 都过。
>
> **三、路由（Router）的设计要点**
>
> 1. **负载均衡（Load Balancing）**
>
>    如果不约束，router 会偏向少数专家（rich-get-richer），其他专家训练不充分。
>
>    **Switch Transformer 解法**：在 loss 里加辅助项：
>
>    ```
>    L_aux = α × E × Σ_i f_i × P_i
>    ```
>
>    其中 `f_i` = 分给专家 i 的 token 比例，`P_i` = router 给专家 i 的平均概率。当分配均匀时 `L_aux` 最小。
>
> 2. **容量因子（Capacity Factor）**
>
>    每个专家有 token 容量上限 `capacity = (N tokens / E) × CF`，超出丢弃。CF 越大越不丢但越浪费显存。
>
> 3. **Router Z-Loss**
>
>    防止 router logits 过大导致 softmax 数值不稳定，加正则项。
>
> **四、DeepSeek V3 的关键创新**
>
> 1. **细粒度专家（Fine-grained Experts）**：把每个标准 FFN 拆成多个小专家，路由到更小粒度 → 表达力更强。
> 2. **共享专家（Shared Expert）**：1 个专家永远激活，承担「通用能力」，路由专家只学专长 → 减少冗余。
> 3. **无辅助损失（Auxiliary-Loss-Free Load Balancing）**：不靠 L_aux，而是在 router 输出加 bias 项动态调整 → 偏好未被充分使用的专家，**不污染主 loss**。
> 4. **多 token 预测（MTP）**：训练时同时预测下 K 个 token，提升数据效率。
>
> 结果：671B 总参数，37B 激活，14.8T token 训练，**仅用 555K H800-hours**（GPT-4 估算是 100×）。
>
> **五、MoE 的工程挑战**
>
> 1. **显存爆炸**：总参数全装内存，70B Dense ≈ 140GB，MoE 671B ≈ 1.3TB → 必须 TP 多卡。
> 2. **通信开销**：MoE 需要 all-to-all 通信把 token 发到对应专家所在的 GPU → 通信占比高。
> 3. **batch 调度**：每个专家的 batch 大小动态变化，影响 MFU。
> 4. **推理框架支持**：vLLM、SGLang、TensorRT-LLM 对 MoE 支持成熟度参差。
>
> **六、MoE vs Dense：怎么选**
>
> - **训练算力受限、追求极致性价比**：MoE（DeepSeek V3）。
> - **推理场景显存充裕、追求低延迟**：MoE（激活少 → FLOPs 少）。
> - **边缘部署、单卡推理**：Dense 小模型（MoE 显存吃不消）。
> - **微调场景**：Dense 更稳，MoE 路由不稳定会导致 SFT 难收敛。
>
> **总结**：MoE 用「稀疏激活」打破 Dense 模型的算力墙——**同等推理 FLOPs 下质量大幅提升**。DeepSeek V3 的细粒度 + 共享专家 + 无辅助损失三连，把 MoE 推到新高度。理解 MoE 的关键不是公式，而是「显存 vs 算力」的工程权衡——这是 2024-2026 大模型架构的主流方向。

### BPE / SentencePiece / tiktoken：tokenizer 是怎么工作的？为什么中英文 token 效率差异这么大？

> **答案**：
>
> **一、为什么需要 tokenizer**
>
> LLM 不能直接处理字符，必须把文本切成「token」（整数 ID）。tokenizer 决定了：
>
> 1. **上下文有效长度**：同样 8K 窗口，中文用户实际能用 ~4000 字，英文能用 ~6000 词。
> 2. **训练数据量**：相同文本，token 多 → 训练 step 多 → 算力开销大。
> 3. **推理成本**：按 token 计费，中文 1 字 ≈ 1-2 token，英文 1 词 ≈ 1-1.5 token。
>
> **二、BPE（Byte Pair Encoding，字节对编码）**
>
> BPE 是现代 LLM 的主流方案。算法：
>
> ```
> 1. 把语料拆成最小单元（字符 / 字节）
> 2. 统计相邻 pair 的频次，合并频次最高的 pair 成新 token
> 3. 重复 2，直到词表达到目标大小（如 50K、100K、128K）
> ```
>
> **优点**：
> - 高频词整体成一个 token（"the" / "的"）。
> - 低频词拆成子词（"tokenization" → "token" + "ization"）。
> - 不会出现 <UNK>，任意文本都可编码。
>
> **例**：词表 50K，"unbelievable" 可能编码为 `["un", "believable"]` 或 `["un", "believ", "able"]`，取决于训练语料里这些 pair 的频率。
>
> **三、主流 tokenizer 实现**
>
> | 实现              | 代表模型                  | 特点                                             |
> |-------------------|---------------------------|--------------------------------------------------|
> | **BPE**           | GPT-2/3、Llama 1/2/3      | 字符级 BPE，词表 32K-128K                        |
> | **SentencePiece** | T5、ALBERT、Qwen、Mistral | 字节级 BPE 或 Unigram，多语言友好                |
> | **tiktoken**      | GPT-3.5/4/4o              | OpenAI 开源的高效 BPE 实现，词表 100K-200K       |
> | **Unigram LM**    | T5、XLNet                 | 概率模型，每种切分有概率，可选最优               |
> | **WordPiece**     | BERT                      | 类似 BPE，但用似然判别                           |
>
> **关键差异**：
> - **字符级 vs 字节级**：字符级遇到未登录字符（如 emoji）会失败；字节级（SentencePiece 的 byte_fallback）保证可逆。
> - **词表大小**：GPT-2 50K → GPT-4 100K → Llama 3 128K。词表越大，token 越短，但 embedding 参数越多。
>
> **四、中文效率差异的根因**
>
> 同一句话：
>
> ```
> 中文：大模型推理优化
> 英文：Large model inference optimization
> ```
>
> 不同 tokenizer 的结果：
>
> | 模型        | 中文 token 数 | 英文 token 数 | 中文/英文字数比 |
> |-------------|---------------|---------------|-----------------|
> | GPT-3.5     | 6             | 4             | 1.5×            |
> | GPT-4o      | 3             | 3             | 1.0×            |
> | Llama 3     | 5             | 3             | 1.67×           |
> | Qwen 2.5    | 4             | 3             | 1.33×           |
> | Claude 3.5  | 3             | 3             | 1.0×            |
>
> **根因**：
>
> 1. **训练语料中英文比例**：Llama 系列中文语料占比 < 5%，中文词表项少 → 中文 1 字常常 = 1-2 token；Qwen / GPT-4o 中文语料丰富，常用字整体编码 → 效率高。
> 2. **词表大小**：词表越大，中文常用字越可能整体成 token。
> 3. **分词粒度**：英文按空格天然分词；中文需要分词算法决定哪些字组成词。
>
> **五、对应用工程的影响**
>
> 1. **预算估算**：中文任务按 1 字 ≈ 1.5 token 估算（保守）。
> 2. **上下文压缩**：英文 RAG 召回 5 个 chunk ≈ 5K token，中文同等字符数可能 7.5K token → 提前压缩。
> 3. **模型选型**：纯中文场景，国产模型（Qwen / DeepSeek / GLM）token 效率比 Llama 系列高 30-50%。
> 4. **微调数据准备**：必须用 base model 对应 tokenizer 编码，否则 chat_template 不匹配。
>
> **六、tokenizer 的常见坑**
>
> - **chat_template 不匹配**：同一句话用 Llama 3 vs Qwen 的 tokenizer，token ID 完全不同 → 微调数据必须用对应模型的 tokenizer。
> - **特殊 token 占位**：`<|im_start|>`、`<|endoftext|>` 等特殊 token 会被分配 ID，但用户文本里如果出现这些字符串可能造成注入。
> - **数字与代码**：长数字（"3.14159265"）和代码（驼峰、下划线）token 效率低于自然语言。
> - **emoji 与罕见字符**：可能拆成 3-4 token，甚至字节 fallback 拆到 4+ 字节。
>
> **七、实战建议**
>
> 1. **估算 token 数**：用 `tiktoken`（OpenAI）或 `transformers.AutoTokenizer` 直接 count，不要按字符估算。
> 2. **选择 tokenizer**：中文优先 Qwen / GLM / DeepSeek 的 tokenizer；英文 + 代码用 Llama 3 / GPT-4o。
> 3. **监控 token 消耗**：在线服务按 user 维度统计 token 消耗比，发现异常及时调模型或压缩 prompt。
> 4. **tokenizer 版本管理**：模型升级（如 Llama 2 → 3）往往换 tokenizer，旧数据要重新编码。
>
> **总结**：tokenizer 是 LLM 的「基础协议」——它决定了上下文有效长度、训练成本、推理费用。BPE 是主流，SentencePiece / tiktoken 是工程实现。中英文效率差异源于训练语料配比和词表设计——这直接影响模型选型和成本估算。理解 tokenizer 不是炫技，而是 LLM 应用工程的财务基础课。

### 预训练数据流水线：去重、去污、毒性过滤、数据配比怎么做？

> **答案**：
>
> 预训练数据的质量直接决定模型上限。Llama 3 敢用过训练（15T token），是因为 Meta 把数据流水线做到了工业级。整套流水线一般分 6 步：
>
> **一、数据采集**
>
> 来源类型与典型配比（Llama 3 参考）：
>
> | 类型            | 占比  | 典型来源                                      |
> |-----------------|-------|-----------------------------------------------|
> | 网页            | ~50%  | Common Crawl、FineWeb、RefinedWeb             |
> | 代码            | ~20%  | GitHub、StarCoder 数据                        |
> | 学术 / 图书     | ~15%  | arXiv、PubMed、Books3（已下架）、Gutenberg    |
> | 对话            | ~5%   | Stack Exchange、Reddit、Wiki                  |
> | 数学            | ~5%   | OpenWebMath、AlgebraicStack                   |
> | 多语言（非英）  | ~5%   | 各语种 CC 抓取                                |
>
> 配比直接影响能力：**代码 + 数学 = 推理能力**；**对话 = 指令跟随能力**；**学术 = 专业知识**。
>
> **二、提取与清洗（Extraction & Cleaning）**
>
> 1. **HTML → 纯文本**：trafilatura、resiliparse、boilerpipe 去广告、导航、模板。
> 2. **质量分类**：用 fasttext / kenLM 训练一个分类器，分数低的丢弃。参考 Gopher、GPT-3 的启发式规则：
>    - 长度过短 / 过长
>    - 重复行比例过高
>    - 字符熵过低（如乱码、关键词堆砌）
>    - 不含标点的"伪文档"
> 3. **PII 脱敏**：正则 + NER 去除手机号、邮箱、身份证、信用卡号。
>
> **三、去重（Deduplication）—— 最关键的一步**
>
> 重复数据会让模型"记住"而不是"学会"，且严重过拟合。典型三层去重：
>
> 1. **精确去重（Exact Dedup）**：MD5 / SHA-256 哈希，完全相同的文档丢弃。快，但只能处理"完全复制"。
> 2. **文档级模糊去重（Fuzzy Dedup）**：MinHash + LSH（Locality Sensitive Hashing）：
>    - 文档 shingle（n-gram 切片）→ MinHash 签名 → LSH 桶分组 → 同桶内相似度 > 阈值（如 0.8）的丢弃。
>    - Llama 3 用这套，能去掉 CC 里 50%+ 的内容。
> 3. **跨语种 / 跨文档去重**：跨数据源（如 GitHub 代码与网页里的代码片段）也要去重。
>
> **去重收益**：相同算力下，去重后训练的模型在下游任务提升 5-15%。
>
> **四、毒性 / 安全过滤**
>
> 1. **毒性分类器**：用 Jigsaw / Perspective API / 内部分类器，识别仇恨、暴力、色情。
> 2. **越狱 / 有害指令过滤**：识别"如何制造 XX"类内容。
> 3. **隐私过滤**：去除包含真实姓名、地址、电话的文档。
>
> **权衡**：过滤太严会损失能力（如医学、法律文本里的"敏感词"被误杀）；过滤太松会有合规风险。Llama 3 报告显示，过严过滤会显著降低少数语种比例。
>
> **五、数据配比优化**
>
> 不是一次性确定，而是**迭代训练 + 评测**：
>
> 1. 小规模试训练（如 1B 模型 + 100B token）。
> 2. 在多个 benchmark（MMLU、GSM8K、HumanEval）上评测。
> 3. 用 DoReMi / gradient-based 方法自动搜索最优配比。
> 4. 调整配比，全量训练。
>
> Llama 3.1 报告提到数据配比调整了 200+ 次。
>
> **六、合成数据（Synthetic Data）**
>
> 数据墙（高质量人类数据接近枯竭）背景下，合成数据成为关键：
>
> - **代码 / 数学**：用更强模型生成（代码题解、数学推导）→ 训练目标模型。
> - **指令数据**：Self-Instruct、Evol-Instruct 自动扩展指令多样性。
> - **倒推 / 反思**：让模型生成"为什么这么做"，作为 CoT 训练数据。
>
> **风险**：合成数据会**放大原模型偏见**（model collapse），且质量评估困难。Phi 系列成功证明了合成数据可行，但需要严格筛选。
>
> **七、工具链**
>
> - **数据处理**：Apache Spark、Dask、Polars（分布式）+ datatrove（HuggingFace）、ccnet（FAIR）。
> - **去重**：datasketch（MinHash）、text-dedup。
> - **质量分类**：fasttext、kenLM、 transformers + 分类头。
> - **PII**：presidio（微软）、scrubadub。
>
> **八、典型数据集对比**
>
> | 数据集        | 规模   | 关键技术                                |
> |---------------|--------|-----------------------------------------|
> | RefinedWeb    | 5T     | 强去重 + 启发式过滤                     |
> | FineWeb       | 15T    | RefinedWeb 升级版 + 多步质量分类        |
> | RedPajama V2  | 30T    | 多源 + 标注信号（质量分、毒性分）       |
> | FineWeb-Edu   | 1.3T   | 教育领域，用 LLM 打分过滤              |
>
> **总结**：预训练数据流水线是大模型工程的"地基"——**80% 的工作在数据，20% 在模型**。核心步骤：采集 → 清洗 → 三层去重 → 毒性过滤 → 配比优化 → 合成数据补充。Llama 3、DeepSeek V3 的成功不仅是架构，更是数据流水线工程化的胜利。理解这套流水线，才能在微调、RAG、Agent 项目里复用同样的数据质量标准。

### CPT（继续预训练）vs SFT（指令微调）：边界在哪？业务接入新领域该选哪个？

> **答案**：
>
> **一、两者本质区别**
>
> | 维度          | CPT（Continued Pre-Training）          | SFT（Supervised Fine-Tuning）              |
> |---------------|----------------------------------------|--------------------------------------------|
> | **训练目标**  | Next-token prediction（与预训练相同）  | 指令-答案映射                              |
> | **数据格式**  | 纯文本（无指令结构）                   | `(instruction, output)` 或 ChatML 对话     |
> | **Loss mask** | 所有 token 都算 loss                   | 仅 assistant token 算 loss                 |
> | **学什么**    | 知识 / 领域语料 / 术语 / 风格          | 行为 / 格式 / 指令跟随                     |
> | **基座**      | Base 模型（或 Instruct 模型）          | Instruct 模型（多数情况）                  |
> | **副作用**    | 改变模型知识结构                       | 改变模型行为                               |
> | **典型数据量**| 1B~100B token                          | 1k~100k 条样本                             |
>
> **二、什么时候选 CPT？**
>
> 1. **新领域，基座不懂术语**：医疗（病历、药品）、法律（条文）、金融（研报）、工业（设备手册）。
> 2. **基座训练数据没覆盖的语种**：小语种、方言、文言文。
> 3. **特定风格 / 文体**：客服话术、公文写作、文学创作。
> 4. **大量未结构化内部文档**：wiki、代码库、研究档案（数十亿 token）。
>
> **判断标准**：
> - 信息是「**知识性**」的（模型需要"知道"）→ CPT。
> - 信息是「**程序性**」的（模型需要"会做"）→ SFT。
>
> **三、什么时候选 SFT？**
>
> 1. **想让模型按特定格式输出**：JSON、XML、Markdown 表格。
> 2. **想改变交互风格**：客服式礼貌、儿童友好的解释、技术专家的简洁。
> 3. **少量任务 + 少量数据**（< 1M token）：CPT 收益低于 SFT。
> 4. **基座已经"懂"领域，只是不会"用"**：现代大模型（Llama 3、Qwen 2.5）已经预训练了海量网络数据，多数领域只需 SFT 引导。
>
> **四、典型组合方案**
>
> **方案 A：仅 SFT（最常见）**
>
> ```
> Base / Instruct 模型 → SFT (5k-50k 样本) → 部署
> ```
>
> 适用：通用能力够用，只需行为对齐。
>
> **方案 B：CPT + SFT（垂直领域）**
>
> ```
> Base 模型 → CPT (10B token 内部数据) → SFT (10k 指令样本) → 部署
> ```
>
> 适用：领域术语密集、Base 模型知识不足。
>
> **方案 C：CPT + SFT + DPO（追求极致）**
>
> ```
> Base → CPT → SFT → 偏好数据收集 → DPO → 部署
> ```
>
> 适用：客服、助手类应用，需要降低幻觉、提升用户满意度。
>
> **方案 D：RAG + SFT（推荐多数业务）**
>
> ```
> Instruct 模型 → RAG（外部知识库）+ SFT（少量指令调优）
> ```
>
> 适用：知识频繁变化、知识量大但调用频次低。**比 CPT 更便宜、更可控**。
>
> **五、CPT 的工程要点**
>
> 1. **学习率**：要比原预训练低 1-2 个数量级（如 1e-5 而非 3e-4），避免破坏已学能力。
> 2. **数据混合**：CPT 数据里掺 10-30% 通用数据（如 FineWeb、Wikipedia），缓解灾难性遗忘。
> 3. **数据格式**：用 `document = text` 的纯文本格式，不要包成对话；如果用 Instruct 模型做 CPT，要保留原 chat_template 但不参与 loss。
> 4. **训练量**：CPT 数据量 = 领域 token 数的 1-3 个 epoch，过多会过拟合。
> 5. **评估**：除领域 benchmark，还要在通用 benchmark（MMLU）上测，确保未崩溃。
>
> **六、SFT 的工程要点**
>
> 1. **chat_template**：必须用目标模型的 tokenizer 编码，错一个字符模型就废。
> 2. **loss mask**：仅 assistant token 算 loss（PyTorch 中 `labels[non_assistant] = -100`）。
> 3. **数据多样性**：覆盖问答、写作、推理、代码、拒答，避免单一格式。
> 4. **数据量**：1k-10k 高质量样本 > 100k 噪声样本（LIMA 经验）。
> 5. **超参**：lr 1e-4（LoRA）/ 1e-5（全量），epoch 2-3，warmup 3%。
>
> **七、决策流程图**
>
> ```
> 业务有大量领域文本？
>     │
>     ├─ 是 → 基座懂这些术语吗？
>     │       │
>     │       ├─ 懂 → SFT（少样本即可）
>     │       └─ 不懂 → CPT + SFT
>     │
>     └─ 否 → SFT 或 RAG（看知识更新频率）
> ```
>
> **八、实战建议**
>
> - **从 RAG + Instruct 模型开始**：90% 的业务问题能用这套解决，且迭代最快。
> - **CPT 是重投入**：算力 > 100 GPU-hours，且失败率高（数据质量、灾难性遗忘、训练不稳定）。
> - **SFT 是性价比之王**：LoRA + 5k 样本 + 8 GPU-hours 就能见效。
> - **数据决定上限**：CPT 的 1B 高质量数据 > 10B 噪声数据；SFT 的 5k 精标 > 50k 自动生成。
>
> **总结**：CPT 学知识，SFT 学行为——**先想清楚业务缺的是知识还是行为，再决定走哪条路**。多数业务场景下，SFT + RAG 已经够用；只有当基座模型真的不懂你的领域术语时，才值得上 CPT。决策错了，钱和算力会浪费在错误的方向上。

### 预训练目标函数：MLM vs CLM vs Span Corruption vs FIM，主流大模型用的是哪个？

> **答案**：
>
> **一、四大目标函数对比**
>
> | 目标函数               | 代表模型                  | 任务描述                                | 是否适合生成 |
> |------------------------|---------------------------|-----------------------------------------|--------------|
> | **MLM**（Masked LM）   | BERT、RoBERTa、DeBERTa    | 随机 mask 15% token，预测被 mask 的     | 否（仅理解）|
> | **CLM**（Causal LM）   | GPT 全系列、Llama、Qwen   | 从左到右预测下一个 token                | 是           |
> | **Span Corruption**    | T5、mT5、Flan-T5          | 随机 mask 连续片段，生成被 mask 的      | 是           |
> | **FIM**（Fill-in-Middle）| Codex、StarCoder、Code Llama | 给前缀 + 后缀，填中间                | 是           |
>
> **二、MLM（Masked Language Modeling）**
>
> ```
> 输入：The [MASK] sat on the mat.
> 目标：cat
> ```
>
> - **优势**：双向注意力，理解任务强（分类、NER、检索）。
> - **劣势**：不能做生成（mask 占位不符合真实推理场景）。
> - **典型场景**：BERT 系列，用于 encoder-only 任务。
>
> **三、CLM（Causal Language Modeling）**
>
> ```
> 输入：The cat sat on the
> 目标：mat
> ```
>
> - **优势**：自回归生成，与推理一致；一个模型能做所有 NLP 任务（GPT 路线）。
> - **劣势**：单向注意力，理解任务略弱于 MLM（但大模型规模抹平了差距）。
> - **主流 LLM 全用 CLM**：GPT、Llama、Qwen、DeepSeek、Claude、Gemini 都是 CLM。
>
> **Loss**：
>
> ```
> L = -Σ log P(x_t | x_<t)
> ```
>
> **四、Span Corruption**
>
> ```
> 输入：The cat <X> on the <Y>.
> 目标：<X> sat, <Y> mat
> ```
>
> - T5 提出，把"mask 单 token"扩展为"mask 连续片段"，模型生成整个片段。
> - **优势**：encoder-decoder 架构，适合翻译、摘要。
> - **劣势**：与现代 decoder-only LLM 不兼容；预训练目标和生成任务错位。
> - **现状**：T5 之后基本被 CLM 取代。
>
> **五、FIM（Fill-in-the-Middle）—— 代码模型的核心**
>
> ```
> 输入：<prefix> def add(a, b): <suffix> return result <middle>
> 目标：c = a + b
>       return c
> ```
>
> - **场景**：代码补全（IDE 自动补全）。用户光标在中间，前面有 prefix，后面有 suffix。
> - **训练技巧**：把训练数据重组为 PSM（Prefix-Suffix-Middle）或 SPM（Suffix-Prefix-Middle）格式，用 CLM loss 训练。
> - **代表模型**：Codex、StarCoder、Code Llama、DeepSeek-Coder、Qwen-Coder。
> - **效果**：纯 CLM 训练的代码模型只擅长"续写"，加 FIM 后才擅长"补全"。
>
> **六、现代 LLM 的混合目标**
>
> 主流大模型预训练**以 CLM 为主，加少量辅助目标**：
>
> 1. **CLM 主目标**：90%+ 数据用 CLM。
> 2. **FIM（代码模型）**：代码数据上加 FIM。
> 3. **MTP（Multi-Token Prediction, DeepSeek V3）**：一次预测下 K 个 token，提升数据效率。
> 4. **UL2（Unifying Language Learning）**：混合多种 span 长度和 corruption 策略。
>
> **七、为什么 CLM 一统天下**
>
> 1. **简单**：只需 next-token loss，工程实现最简单。
> 2. **统一**：一个目标解决所有任务（生成、理解、推理）。
> 3. **扩展性**：scaling law 在 CLM 上最稳定。
> 4. **In-Context Learning 涌现**：CLM 训练的大模型涌现出 ICL 能力，MLM 不会。
>
> **代价**：训练数据效率低于 MLM（同样算力，MLM 学得更快），但工程简单性 + 涌现能力碾压。
>
> **八、对应用工程的启示**
>
> 1. **微调用 CLM**：所有现代 LLM 都用 CLM，SFT loss = next-token loss on assistant tokens。
> 2. **代码任务选 FIM 模型**：IDE 补全用 Code Llama / DeepSeek-Coder（带 FIM 训练）。
> 3. **理解任务也可以用 CLM 模型**：现代 CLM 模型在分类、NER 上已经超过 BERT。
> 4. **embedding 模型例外**：BGE、E5 等 embedding 模型仍用 MLM 训练 + 对比学习，因为它们只做理解。
>
> **总结**：预训练目标函数经历了 MLM（BERT）→ Span Corruption（T5）→ CLM 一统天下（GPT 路线）的演进。**现代 LLM 几乎都是 CLM**，代码模型加 FIM。理解这套演进有助于选模型（生成任务选 CLM 模型，纯理解任务可选 MLM 模型）和理解为什么 LLM 是"自回归"的。

### 合成数据在预训练中的应用与风险：为什么 Phi 系列能成功？什么是 Model Collapse？

> **答案**：
>
> **一、为什么需要合成数据**
>
> 1. **数据墙（Data Wall）**：高质量人类文本接近枯竭。Epoch AI 估算：到 2026-2028 年，可用高质量文本将被训练完。
> 2. **长尾领域稀缺**：高质量数学、代码、推理数据少。
> 3. **可控性**：合成数据可以指定难度、风格、领域，更易配比。
> 4. **成本**：用 LLM 生成 1M token 成本 ~$1-10，人工标注 ~$100-1000。
>
> **二、合成数据的应用场景**
>
> 1. **预训练阶段**：
>    - Phi 系列用 GPT-4 生成「教科书级」数据 → 训练小模型（1.3B）超过 Llama 2 70B。
>    - 数学：用强模型生成 step-by-step 推理数据（OpenWebMath、MathInstruct）。
>    - 代码：用 LLM 生成代码 + 注释 + 测试用例。
>
> 2. **SFT 阶段**：
>    - Self-Instruct：让 LLM 自己生成指令-答案对。
>    - Evol-Instruct：逐步复杂化指令（WizardLM 路线）。
>    - ShareGPT 收集 + GPT-4 改写。
>
> 3. **RLHF / RLAIF**：
>    - Constitutional AI（Anthropic）：用规则让模型自我批评 + 修订。
>    - RLAIF（Google）：用 LLM 替代人类做偏好标注。
>
> 4. **推理模型（o1 / R1）**：
>    - 用 MCTS / RL 生成大量推理轨迹 → 训练。
>    - DeepSeek R1：纯 RL（无 SFT）训练出 V3 的推理能力。
>
> **三、Phi 系列为什么成功**
>
> 微软 Phi 系列证明「**小模型 + 高质量合成数据**」可以打败大模型：
>
> | 模型       | 参数量 | 关键数据                           | 性能              |
> |------------|--------|------------------------------------|-------------------|
> | Phi-1      | 1.3B   | 7B token "Textbooks Are All You Need" | HumanEval 50.6%  |
> | Phi-1.5    | 1.3B   | 合成 + 哲学 / 科普                 | 接近 Llama 7B     |
> | Phi-2      | 2.7B   | 合成 + 教科书 + 代码               | 接近 Llama 2 70B  |
> | Phi-3      | 3.8B/7B| 合成 + 过滤网页                    | 接近 GPT-3.5      |
> | Phi-4      | 14B    | 合成 + 严格质量过滤                | 接近 GPT-4o mini  |
>
> **核心思想**："Textbooks are all you need"——给小模型看「教科书级」高质量合成数据，远胜于给它看海量低质网页。
>
> **关键工程实践**：
> 1. **过滤而非生成**：合成数据用 LLM 生成后，再用另一个 LLM 评分筛选（Phi-4 的关键创新）。
> 2. **多样性 + 难度梯度**：覆盖不同难度，避免模式塌缩。
> 3. **混合真实数据**：合成 + 真实数据混合，避免分布偏移。
>
> **四、Model Collapse（模型坍塌）**
>
> **定义**：当模型用「上一代模型生成的数据」训练时，分布的尾部会丢失，模型逐渐"收敛"到主流模式，丧失生成多样性。
>
> **机制**：
>
> ```
> Gen 0：人类数据 D0（长尾丰富）
> 训练 → Model M1
> Gen 1：M1 生成 D1（D0 的"压缩版"，长尾丢失）
> 训练 → Model M2
> Gen 2：M2 生成 D2（长尾更少）
> ...
> Gen N：Model MN 只能生成最常见的样本，多样性崩塌
> ```
>
> **实验证据**：
> - Shumailov et al. 2023《The Curse of Recursion》：5 代合成数据后，模型在尾部能力上完全崩溃。
> - 在数学 / 代码 / 长尾知识上尤其明显。
>
> **现实风险**：互联网上越来越多内容是 LLM 生成的（Reddit、SEO 文章、博客），下一代模型抓取这些数据训练，能力会逐步下降。
>
> **五、缓解 Model Collapse 的策略**
>
> 1. **混入真实数据**：合成数据 + 10-30% 真实数据，避免完全合成。
> 2. **多源合成**：用多个不同的 LLM 生成，避免单模型偏见。
> 3. **质量过滤**：用强模型（GPT-4）评分筛掉低质合成数据。
> 4. **避免递归**：不要让"模型的后代"训自己生成的数据；用强模型给弱模型造数据。
> 5. **保留原分布**：合成数据要覆盖原数据的长尾，而非只生成主流模式。
>
> **六、合成数据的质量评估**
>
> 1. **多样性**：n-gram 多样度、self-BLEU。
> 2. **正确性**：代码（执行测试）、数学（验算答案）、事实（与权威源对比）。
> 3. **难度分布**：用 LLM 评分分级。
> 4. **下游性能**：在 benchmark 上对比合成 vs 真实数据训练效果。
>
> **七、典型工作流**
>
> ```
> 1. 用 GPT-4 / Claude 生成 100k 候选样本
> 2. 用 LLM-as-Judge 评分筛掉 70% 低质
> 3. 用 dedup + MinHash 去重
> 4. 用代码 / 数学执行器验证
> 5. 配比混合（70% 合成 + 20% 真实 + 10% 拒答）
> 6. 训练 + 评估
> ```
>
> **总结**：合成数据是数据墙时代的"解药"——Phi 系列证明了「**质量 >> 数量**」。但 Model Collapse 是它的"诅咒"，必须通过混真实数据、多源生成、严格过滤来缓解。未来大模型的竞争力，越来越取决于「**合成数据流水线**」的工程能力——OpenAI o1、DeepSeek R1 的成功本质上是数据工程的胜利。

### 大模型训练稳定性：bf16 混合精度、ZeRO / DeepSpeed、张量并行、流水线并行怎么组合？

> **答案**：
>
> 训练大模型最大的工程挑战不是写代码，而是**让训练稳定不崩**。70B 模型在 1000 张 H100 上训几周，loss spike、NaN、OOM 是家常便饭。
>
> **一、混合精度训练（Mixed Precision）**
>
> | 精度      | 字节 | 数值范围              | 典型用途                  |
> |-----------|------|-----------------------|---------------------------|
> | FP64      | 8    | 极高                  | 科学计算，不用            |
> | FP32      | 4    | 高                    | 主权重、optimizer state   |
> | TF32      | 4    | 中（NVIDIA A100+）    | A100 默认，速度 + 稳定    |
> | FP16      | 2    | 低（易溢出）          | activation / gradient     |
> | BF16      | 2    | 与 FP32 同（精度低）  | 主流选择                  |
> | FP8       | 1    | 极低                  | H100 新精度，前沿         |
> | INT8/INT4 | 1/0.5| 整数                  | 仅推理，不训练            |
>
> **混合精度策略**：
>
> ```
> master weight: FP32（高精度保存，避免误差累积）
> gradient: BF16（计算用，省显存）
> activation: BF16
> optimizer state (Adam): FP32 momentum + FP32 variance
> ```
>
> **为什么 BF16 > FP16**：
> - FP16 范围 ~6e-5 ~ 65504，gradient 容易下溢或上溢。
> - BF16 与 FP32 指数位相同，范围一致，精度低但不会溢出。
> - A100 / H100 都原生支持 BF16，速度与 FP16 相同。
> - **现代大模型训练默认 BF16**。
>
> **二、显存占用拆解**
>
> Adam 优化器下，1 个参数的显存占用：
>
> ```
> 参数 FP32:        4 字节
> 梯度 BF16:        2 字节
> Momentum FP32:    4 字节
> Variance FP32:    4 字节
> ─────────────────────
> 合计:             14 字节/参数
> ```
>
> 70B 模型：14 × 70e9 = **980 GB** —— 单卡（80GB）放不下，必须切分。
>
> **三、ZeRO（Zero Redundancy Optimizer）**
>
> DeepSpeed 提出，把训练状态在 GPU 间切分，消除冗余：
>
> | Stage  | 切分内容                          | 70B 显存（每卡，8 卡） |
> |--------|-----------------------------------|------------------------|
> | ZeRO-1 | optimizer state                  | ~150GB → 切分 ~20GB    |
> | ZeRO-2 | optimizer state + gradient       | ~150GB → 切分 ~20GB    |
> | ZeRO-3 | optimizer + gradient + parameter | ~980GB → 切分 ~125GB   |
>
> **ZeRO-3** 等价于"全参数分片"，每张卡只保存 1/N 的参数；前向 / 反向时 all-gather 临时聚合。
>
> **代价**：通信量增加，吞吐降低 ~20-30%。
>
> **四、张量并行（Tensor Parallelism, TP）**
>
> Megatron-LM 提出，**把单层权重切片到多卡**：
>
> ```
> Linear(in=4096, out=4096) on 8 GPUs
> → 每卡 Linear(in=4096, out=512)
> 前向：每卡算自己那 512 输出，all-reduce 求和
> ```
>
> **特点**：
> - 适合单机内（NVLink 高速互联）。
> - 跨机通信太慢，TP 一般 = 单机 GPU 数（8）。
> - 与 ZeRO 互补：TP 切层内，ZeRO 切层间。
>
> **典型 TP=8**：单机 8 卡跑 70B。
>
> **五、流水线并行（Pipeline Parallelism, PP）**
>
> 把模型**按层切**到多卡，前向逐层流过：
>
> ```
> GPU 0: layer 0-15
> GPU 1: layer 16-31
> GPU 2: layer 32-47
> GPU 3: layer 48-63
> ```
>
> **问题**：bubble（气泡）—— 后面的 GPU 等前面的。
>
> **解法**：Micro-batching（1F1B、Interleaved PP）把 batch 拆成多个 micro-batch 流水过。
>
> **适用**：超深模型（405B+），跨机部署。
>
> **六、3D 并行组合**
>
> 训练超大模型的标准组合：
>
> ```
> Data Parallel (DP)  ── 横向扩展 batch
> Tensor Parallel (TP) ── 单机内 8 卡切层内
> Pipeline Parallel (PP) ── 跨机切层间
> ```
>
> 例：405B 模型，1024 张 H100：
>
> - TP=8（单机内）
> - PP=16（跨 16 台机）
> - DP=8（1024 / 8 / 16）
>
> FSDP（PyTorch 原生 ZeRO-3）也常替代 DP + ZeRO。
>
> **七、训练稳定性的工程技巧**
>
> 1. **Gradient Clipping**：限制梯度范数 < 1.0，防止爆炸。
> 2. **Learning Rate Warmup**：前 2000-4000 steps 线性升温，避免初期发散。
> 3. **Weight Decay**：0.01-0.1，正则化。
> 4. **Adam β1=0.9, β2=0.95**（而非 0.999）：大模型偏好更短记忆。
> 5. **Loss Scaling**（FP16 时代）：放大 loss 避免梯度下溢；BF16 不需要。
> 6. **梯度累积（Gradient Accumulation）**：小显存模拟大 batch。
> 7. **Checkpoint Recovery**：每 1-2 小时存 checkpoint，崩了能恢复。
> 8. **Skip Steps**：loss spike 时跳过该 step 的更新。
>
> **八、典型部署方案**
>
> | 模型规模 | 典型配置                                | 算力                |
> |---------|-----------------------------------------|---------------------|
> | 7B 全量 | 8×A100 + ZeRO-3 + TP=2                  | ~50 GPU-hours       |
> | 70B LoRA| 8×A100                                  | ~24 GPU-hours       |
> | 70B 全量| 64-128×A100 + FSDP / ZeRO-3 + TP=8      | ~1000 GPU-hours     |
> | 405B 全量| 1024+ H100 + 3D 并行 + FSDP            | ~10000+ GPU-hours   |
>
> **总结**：训练稳定性是大模型工程的"硬功夫"——**精度选 BF16，并行用 3D 组合，超参走 Adam β2=0.95 + warmup + clip**。Llama 3、DeepSeek V3 的训练日志里，每个超参都是反复实验得出的。理解这套工程栈，才能在微调 7B 时知道为什么 OOM、为什么 loss 发散，以及如何对症下药。

### Tokenizer 词表设计：词表大小选 32K / 50K / 100K / 128K 各有什么取舍？多语言场景怎么选？

> **答案**：
>
> **一、词表大小（Vocabulary Size）的影响**
>
> 词表大小 V 直接影响：
>
> 1. **Embedding 参数量**：`V × d_model`。V=128K，d=4096 → 524M 参数（占 Llama 7B 的 7%）。
> 2. **输出头参数量**：`d_model × V`，与 embedding 同等。
> 3. **Token 序列长度**：词表大 → 同样文本切得更短 → 上下文有效信息更多。
> 4. **训练效率**：序列短 → step 内 token 少 → 训练快。
> 5. **显存**：softmax over V 是显存大头，V 越大显存越大。
>
> **二、主流模型词表对比**
>
> | 模型        | 词表    | 主要语种     | 备注                       |
> |-------------|---------|--------------|----------------------------|
> | GPT-2       | 50K     | 英           | BPE 起源                   |
> | GPT-3.5     | 50K     | 英 + 多语    | 同 GPT-2                   |
> | GPT-4o      | 200K    | 多语优化     | tiktoken，中文效率大幅提升 |
> | Llama 1/2   | 32K     | 英为主       | 中文效率低                 |
> | Llama 3     | 128K    | 多语扩展     | 中文效率提升 1.5×          |
> | Qwen 1/2/2.5| 152K    | 中文 + 多语  | 中文效率最高               |
> | DeepSeek V3 | 128K    | 中 + 英 + 代码| 平衡                       |
> | Mistral     | 32K     | 英 + 欧      | 词表小但效率高             |
> | Gemma       | 256K    | 多语         | SentencePiece              |
>
> **三、词表选择的权衡**
>
> **V 小（32K-50K）**：
>
> - **优势**：embedding 参数少（小模型占比可控），softmax 快，显存友好。
> - **劣势**：低资源语种 token 效率低（中文 / 日文 / 韩文 1 字 = 2-3 token），上下文实际有效长度缩水。
> - **适用**：纯英文或单一语种小模型。
>
> **V 大（100K+）**：
>
> - **优势**：多语种常用词整体成 token，序列短，训练 / 推理效率高。
> - **劣势**：embedding 占总参数比例高（小模型可能 > 20%）；低频 token 训练不充分。
> - **适用**：多语种、大模型。
>
> **经验法则**：
>
> - 小模型（< 7B）：词表 32K-64K，避免 embedding 占比过高。
> - 中模型（7B-70B）：词表 64K-128K。
> - 大模型（70B+）：词表 128K+。
>
> **四、多语言词表设计**
>
> **挑战**：中、日、韩（CJK）字符多，词表项不足以覆盖所有字符。
>
> **策略**：
>
> 1. **按语种配比分配词表**：
>    - 英文 30%、中文 20%、日韩 5%、代码 10%、其他 35%（典型配比）。
>    - 用 SentencePiece Unigram 模型自动学习。
> 2. **byte_fallback**：罕见字符回退到字节级编码（4 字节），保证可逆。
> 3. **简繁统一 / 异体字归一**：减少冗余。
> 4. **代码 token**：常见驼峰 / 下划线 token、缩进 token。
>
> **Qwen 词表设计**：152K，其中中文常用字 + 词 ~30K，覆盖 99%+ 中文场景，1 字 ≈ 1 token。
>
> **五、词表扩缩容的工程场景**
>
> 1. **扩词表（Vocabulary Extension）**：
>    - 场景：基于 Llama 2 做中文模型（Llama 2 中文词表只有 ~500 字）。
>    - 操作：用中文语料训新 BPE，合并到原词表，新 token 加到 embedding，重新初始化新 token 的 embedding → CPT 训练。
>    - 代表：Chinese-LLaMA、Yayi。
>
> 2. **缩词表（Vocabulary Pruning）**：
>    - 场景：基于 Qwen 做纯英文部署，减少 embedding 显存。
>    - 操作：删掉低频 token，重新映射。
>    - 风险：删错可能导致下游任务失效，需谨慎评估。
>
> **六、词表对应用工程的影响**
>
> 1. **预算估算**：必须用目标模型的 tokenizer 数 token，不要按字符估算。
> 2. **微调数据准备**：必须用 base model 对应 tokenizer 编码 chat_template，错一个 token 都不行。
> 3. **上下文压缩**：低效 tokenizer 模型（如 Llama 2 中文）需要更激进的压缩。
> 4. **模型选型**：中文场景优先 Qwen / DeepSeek / GLM，效率比 Llama 2 高 30-50%。
>
> **七、特殊 token 与扩展**
>
> 1. **特殊功能 token**：
>    - `<|endoftext|>`：文档结束。
>    - `<|im_start|>` / `<|im_end|>`：消息分隔（ChatML）。
>    - `<|begin_of_text|>`：序列起始。
>    - `<pad>`：填充。
> 2. **预留扩展位**：词表保留 100-1000 个 `<unused>` 给下游微调使用。
> 3. **工具调用 token**：`<tool_call>`、`<function_call>`（Function Calling / MCP）。
>
> **八、实战建议**
>
> - **中文场景选大词表模型**：Qwen 152K > DeepSeek 128K > Llama 3 128K > Llama 2 32K。
> - **代码任务看代码 token 覆盖**：Code Llama、StarCoder 在代码 token 上做了优化。
> - **多语混合场景**：选 GPT-4o、Qwen、Gemma 这些词表大的多语模型。
> - **微调不要改 tokenizer**：除非必要（如扩中文词表），改 tokenizer 会破坏预训练对齐。
>
> **总结**：词表大小是「**embedding 参数占比 vs token 效率**」的权衡——小模型选小词表，多语大模型选大词表。中文场景 Qwen / DeepSeek 的 128K-152K 词表是 2024-2026 的最优解，token 效率比 Llama 2 提升 30-50%，直接影响上下文有效长度和计费成本。
