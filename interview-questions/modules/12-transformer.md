# Transformer 架构

> 来源：[面试鸭 · 最全 AI 大模型面试题库（含详细答案）](https://www.mianshiya.com/bank/1906189461556076546)
> 本模块共 27 题。完整索引见 [bank-llm-by-topic.md](../bank-llm-by-topic.md)。

---



> 共 27 题

### [讲一下你对 Transformer 的 Encoder 模块的理解。](https://www.mianshiya.com/bank/1906189461556076546/question/1821834695759925249)

> **答案**：
>
> **Transformer 的 Encoder 模块理解**
>
> Encoder 是 Transformer 的"理解"部分——把输入序列编码成上下文丰富的向量表示。
>
> **整体结构**
>
> ```
> Input Tokens
>     ↓
> Token Embedding + Positional Encoding
>     ↓
> ┌────────────────────────────┐
> │ Encoder Layer × N（N=6）   │
> │                            │
> │   ┌──────────────────┐     │
> │   │ Multi-Head Self- │     │
> │   │ Attention        │     │
> │   └────────┬─────────┘     │
> │            ↓               │
> │   Add & LayerNorm          │
> │            ↓               │
> │   ┌──────────────────┐     │
> │   │ Feed-Forward     │     │
> │   │ Network          │     │
> │   └────────┬─────────┘     │
> │            ↓               │
> │   Add & LayerNorm          │
> └────────────────────────────┘
>     ↓
> Contextualized Embeddings (N × d_model)
> ```
>
> **5 个核心组件**
>
> **1. Embedding Layer**
>
> ```
> input_tokens (N,) → embedding (N, d_model)
> ```
>
> - 词表 V（如 30000-100000）
> - d_model（512、768、4096、...）
> - 每个 token 一个 d_model 维向量
>
> **2. Positional Encoding**
>
> ```
> x = token_embedding + positional_encoding
> ```
>
> 详见位置编码章节。给序列加入顺序信息。
>
> **3. Multi-Head Self-Attention**
>
> 每个位置"看到"所有其他位置，加权融合：
>
> ```
> Attention(x, x, x) = softmax(QK^T / √d_k) · V
> ```
>
> 作用：
> - 主语找谓语
> - 代词找指代
> - 长程依赖建模
>
> **4. Add & LayerNorm（残差 + 层归一化）**
>
> ```
> output = LayerNorm(x + Sublayer(x))
> ```
>
> - 残差：信息可以直接"跳过"子层，缓解梯度消失
> - LayerNorm：每层特征归一化，训练稳定
>
> **现代变体**：
> - **Post-LN**（原始）：LayerNorm 在 Add 之后，深层训练不稳
> - **Pre-LN**（GPT-2、Llama）：LayerNorm 在 Sublayer 之前，稳定但效果略差
> - **Sandwich-LN**：两边都有
>
> **5. Feed-Forward Network（FFN）**
>
> ```
> FFN(x) = ReLU(x · W_1 + b_1) · W_2 + b_2
>
> W_1: d_model × d_ff (d_ff = 4 × d_model)
> W_2: d_ff × d_model
> ```
>
> - 每个位置独立做（**position-wise**）
> - 中间维度通常 4 倍 d_model
> - 引入非线性 + 升维降维
>
> **现代变体**：
> - **ReLU**（原始）
> - **GeLU**（BERT、GPT-2）
> - **SwiGLU** = Swish(x · W_1) ⊙ (x · V)（Llama 系，门控）
> - **GeGLU**（同 SwiGLU 但用 GELU）
>
> **Encoder 的性质**
>
> 1. **双向（Bidirectional）**：每个位置能看到左右所有 token。
> 2. **置换不变 + 位置编码**：通过位置编码感知顺序。
> 3. **堆叠层（Stacked）**：N 层逐步抽象——浅层学语法，中层学语义，深层学任务。
> 4. **输出是 N 个向量**：每个 token 一个上下文化表示，不像 RNN Encoder 把所有信息压成一个向量。
>
> **Encoder-only 模型（BERT、RoBERTa、DeBERTa）**
>
> 只用 Encoder 堆叠，没有 Decoder：
> - 输入：完整序列（双向）
> - 输出：每个 token 的上下文表示
> - 适合**理解类任务**：分类、NER、QA、相似度
>
> **预训练任务**：
> - BERT：Masked Language Model（MLM）+ Next Sentence Prediction
> - RoBERTa：只 MLM，更多数据
> - DeBERTa：disentangled attention
>
> **对比：Encoder vs Decoder**
>
> | 维度 | Encoder | Decoder |
> |---|---|---|
> | 注意力 | 双向 self-attention | causal（masked）self-attention |
> | 看到的信息 | 整个序列 | 只看过去 + 当前 |
> | 主要任务 | 理解、表示 | 生成 |
> | 典型模型 | BERT | GPT |
> | 训练目标 | MLM（mask 填空） | 下一个 token 预测 |
>
> **Layer 数 N 的选择**
>
> - 原始：N=6
> - BERT-base：12 层
> - BERT-large：24 层
> - 现代编码器通常 12-24 层（理解类任务不需要太深）
>
> **总结**：Transformer Encoder = **Embedding + PE + N × (Self-Attention + FFN, with Add&LN)**。**核心：双向 self-attention**，让每个位置看到全局信息。**输出：每个 token 的上下文化表示**。**典型应用：BERT 系理解类任务**。理解 Encoder 是理解 BERT、RoBERTa、Embedding 模型的基础。

### [K 和 Q 可以使用同一个值通过对自身进行点乘得到吗？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834694384193537)

> **答案**：
>
> **K 和 Q 可以用同一个值通过自乘得到吗？**
>
> **技术上可以，但不应该这么做**。
>
> **自乘（Self-Dot Product）的形式**
>
> 如果 Q = K = X：
> ```
> Attention(X, X, V) = softmax(X · X^T / √d) · V
> ```
>
> 注意：这其实**就是 Linformer 之前的简化版本，或者 SVD/PCA 思想的 attention**。
>
> 但原始 Transformer 不这么做，原因如下：
>
> **问题 1：对称性导致表达能力下降**
>
> Q · K^T 是一个**对称矩阵**（如果 Q=K）：
> - score(i, j) = score(j, i)
> - 每个 token 对另一个 token 的"关注"被强制对称
>
> 但语言中关系天然**不对称**：
> - "主语 → 谓语" vs "谓语 → 主语" 关注的内容不同
> - 代词 "it" 指代前面的 "cat"（强关注），但 "cat" 不一定关注 "it"（弱）
>
> Q=K 强制对称 → 模型无法表达"单向依赖"。
>
> **问题 2：Q 和 K 的"角色"不同**
>
> - Q：作为**主动查询者**——"我要找什么"
> - K：作为**被查询者**——"我能匹配什么"
>
> 同一个 token 在两种角色下需要呈现**不同的特征**：
> - 查询时关心语法角色
> - 被查询时关心语义内容
>
> 如果共享 Q、K，模型必须用同一个投影同时满足两种角色，能力受限。
>
> **问题 3：数值稳定性**
>
> X · X^T 的对角线是 X_i · X_i = ||X_i||²，通常远大于非对角线元素。这导致：
> - softmax 输出在"自己关注自己"上过于集中
> - token 更新时主要看自己，等于没融合信息
>
> Q≠K 通过学习不同投影矩阵，可以缓解这个问题。
>
> **实验对比**
>
> - Q=K：WMT 翻译 BLEU 下降 2-3 分
> - Q、K 各自投影：标准 Transformer
> - 现代优化（MHA、MQA、GQA）仍保持 Q、K 独立
>
> **什么情况下 Q=K 是合理的**
>
> 某些**对称关系建模**任务：
> - **图注意力（GAT）**：节点之间关系天然对称。
> - **聚类的 attention**：如 Set Transformer 的 ISAB。
> - **相似度学习**：度量学习、对比学习。
> - **Linear Attention** 的某些实现：`φ(Q) · (φ(K)^T V)`，当 Q、K 共享 φ 时退化但仍能跑。
>
> 但这些都不是标准 Transformer。
>
> **衍生：共享 Q、K 的真实案例**
>
> **1. Memory Networks（早期）**
> - 用一个 memory bank，K=V=memory，Q=user input
> - 这里 K=V 是合理的（每个 memory 提供自己的内容）
>
> **2. Multi-Query Attention（MQA）**
> - 多个 head 共享 K、V
> - Q 还是独立的
> - 减少 KV cache 显存
>
> **3. Grouped Query Attention（GQA）**
> - 多个 head 共享一组 K、V
> - Llama 2 70B 用
>
> **结论**
>
> | 设计 | 是否合理 | 备注 |
> |---|---|---|
> | Q=K | ✗ | 强制对称，能力下降 |
> | Q=V | ✗ | 角色混淆 |
> | K=V | ✓ 有时合理 | 在 cross-attention 中常见 |
> | Q、K、V 各自独立 | ✓ | 标准 Transformer |
>
> **总结**：**Q 和 K 应该使用不同投影矩阵**——即使来源都是同一个 X，也要经过不同线性变换。**核心理由：① 不对称关系需要不对称表达；② 角色不同需要不同特征；③ 数值稳定性**。这是 Transformer 设计的基本原则之一，**不要为了省参数把 Q 和 K 合并**。如果非要省，去研究 MQA / GQA（共享 K、V 跨 head），那是另一个维度。

### [在进行多头注意力的时候需要对每个 head 进行降维吗？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834695491489793)

> **答案**：
>
> **多头注意力中是否需要对每个 head 降维？**
>
> **答案：是的，每个 head 通过降维投影到 d_head = d_model / h 的子空间**。
>
> **降维的机制**
>
> ```
> d_model = 512, h = 8 → d_head = 64
>
> Q ∈ R^(N × 512) → W_Q^i ∈ R^(512 × 64) → Q^i ∈ R^(N × 64)
> K ∈ R^(N × 512) → W_K^i ∈ R^(512 × 64) → K^i ∈ R^(N × 64)
> V ∈ R^(N × 512) → W_V^i ∈ R^(512 × 64) → V^i ∈ R^(N × 64)
>
> head_i = Attention(Q^i, K^i, V^i)  # R^(N × 64)
>
> concat(head_1, ..., head_8) ∈ R^(N × 512)
> → W_O ∈ R^(512 × 512) → 输出 R^(N × 512)
> ```
>
> **降维的两个层面**
>
> **层面 1：Q、K、V 的投影降维（必备）**
>
> ```
> X ∈ R^(N × d_model)
> W_Q^i ∈ R^(d_model × d_head)
> Q^i = X · W_Q^i ∈ R^(N × d_head)
> ```
>
> **层面 2：输出的投影还原（必备）**
>
> ```
> concat(heads) ∈ R^(N × h · d_head) = R^(N × d_model)
> output = concat · W_O
> ```
>
> 如果 h · d_head = d_model，则 W_O 是方阵。**总计算量等于单头 attention**（这是 multi-head 的精妙之处）。
>
> **为什么必须降维**
>
> **理由 1：保持计算量不变**
>
> 单头 attention：`Q · K^T` 是 (N, d) × (d, N)，FLOPs = N²·d。
> 多头 attention：h 个 head 各自做 (N, d/h) × (d/h, N)，FLOPs = h · N²·(d/h) = N²·d。
>
> → **总计算量相同**。不降维的话 h=8 头 = 8 倍计算。
>
> **理由 2：让不同 head 投影到不同子空间**
>
> 如果每个 head 不降维（用全部 d=512 维），不同 head 学到的模式高度重叠（都在同一个完整空间操作）。
> 降到 64 维，每个 head 只看到一部分特征 → **强制分工**。
>
> **理由 3：参数量平衡**
>
> 每个 head：3 个投影矩阵（Q、K、V）+ 1 个输出投影。降维后每个矩阵小（d × d_head），总参数 ≈ 4 · d²，与单头相同。
>
> **不做降维的情况（理论可能但不推荐）**
>
> ```
> head_i = Attention(Q · W_Q^i, K · W_K^i, V · W_V^i)
> W_Q^i, W_K^i, W_V^i ∈ R^(d × d)  # 不降维
> ```
>
> → 每个 head 都在全维度做 attention：
> - 计算量 × h 倍
> - 参数量 × h 倍
> - head 之间冗余
> - 没人这么做
>
> **Variants：变体降维策略**
>
> **1. 不同 head 不同维度（罕用）**
> - head 1 用 d_head=128，head 2 用 d_head=64
> - 实现复杂，收益小
>
> **2. 共享 K、V 跨 head（MQA, GQA）**
> - K、V 不分 head，只 Q 分
> - d_head 还是 d_model / h
> - KV cache 大幅减少
>
> **3. 低秩投影（MLA, DeepSeek-V2）**
> - 把 K、V 投到更小的潜空间（如 d_c=512），再上投影
> - 显存和计算大幅减少
>
> **实际工程**
>
> PyTorch 实现：
> ```python
> self.W_Q = nn.Linear(d_model, d_model)  # 一次性算所有 head
> self.W_K = nn.Linear(d_model, d_model)
> self.W_V = nn.Linear(d_model, d_model)
>
> def forward(Q, K, V):
>     Q = self.W_Q(Q).view(N, h, d_head).transpose(1, 2)  # (h, N, d_head)
>     K = self.W_K(K).view(N, h, d_head).transpose(1, 2)
>     V = self.W_V(V).view(N, h, d_head).transpose(1, 2)
>     # F.scaled_dot_product_attention 自动处理多头
> ```
>
> 注意：这里 W_Q 是 `d_model × d_model`，但 view 之后等价于 h 个 `d_model × d_head` 投影——降维在 reshape 中完成。
>
> **总结**：**多头 attention 必须对每个 head 降维**，从 d_model 投到 d_head = d_model / h。**核心理由：① 计算量与单头相当 ② 强制子空间分工 ③ 参数量平衡**。降维通过 W_Q, W_K, W_V 的形状 `R^(d_model × d_head)` 实现，最后 concat + W_O 还原回 d_model。这是 multi-head 设计的精髓——**多模式并行 + 同等代价**。

### [Transformer 中的"残差连接"可以缓解梯度消失问题吗？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834697638973442)

> **答案**：
>
> **残差连接（Residual Connection）能缓解梯度消失吗？**
>
> **能**。残差连接是 Transformer 训练稳定的关键之一。
>
> **残差连接的形式**
>
> ```
> output = x + Sublayer(x)
> ```
>
> 每个子层（attention、FFN）都被一个残差路径"绕过"。
>
> 来自 ResNet（He et al. 2015）：
> ```
> y = F(x) + x
> ```
>
> **为什么能缓解梯度消失**
>
> **问题：深层网络的梯度消失**
>
> 考虑一个 N 层网络，每层 f：
> ```
> L = f_N(...f_2(f_1(x))...)
> ```
>
> 反向传播：
> ```
> ∂L/∂x = ∂f_N/∂h_{N-1} · ∂f_{N-1}/∂h_{N-2} · ... · ∂f_1/∂x
> ```
>
> 每个 ∂f_i 都是 < 1 的数 → 连乘 → 指数衰减 → 梯度消失。
>
> **残差连接的"解药"**
>
> 加残差后：
> ```
> y = F(x) + x
> ∂y/∂x = ∂F/∂x + 1
> ```
>
> 反向传播 N 层：
> ```
> ∂L/∂x = Π_i (∂F_i/∂h_{i-1} + 1)
>       = 1 + Σ F_i' + Σ F_i' F_j' + ...
> ```
>
> **始终有一个"1"的项** → 梯度可以直接"传回去"，不会消失。
>
> 直观理解：残差让信息（前向）和梯度（反向）都有"高速公路"直接通过，不经过子层。
>
> **Transformer 中的残差**
>
> 每个子层包：
> ```
> output = LayerNorm(x + Sublayer(x))  # Post-LN，原始
> output = x + Sublayer(LayerNorm(x))  # Pre-LN，GPT/Llama
> ```
>
> - Sublayer 是 Self-Attention 或 FFN
> - 残差路径让信息从输入直接到输出
> - 深层 Transformer（>100 层）能稳定训练
>
> **为什么 Transformer 特别需要残差**
>
> 1. **Self-attention 是加权和** → 不增维
> 2. **LayerNorm 归一化** → 可能"洗掉"信息
> 3. **深层堆叠** → 没残差会"塌缩"
>
> **实验对比**
>
> - 无残差 Transformer：>6 层就开始退化
> - 有残差 Transformer：能稳定训到 100+ 层
> - GPT-3 96 层、Llama 70B 80 层，都靠残差连接
>
> **残差的额外好处**
>
> 1. **梯度流更顺畅**：训练收敛快
> 2. **能"绕过"无用层**：相当于集成浅层模型
> 3. **Loss landscape 平滑**：训练更稳
> 4. **支持非常深的网络**：100-200 层都能训
>
> **与现代设计的关系**
>
> - **Pre-LN**：把 LN 放残差外面，残差路径完全干净
> - **RMSNorm**：同 LayerNorm 配合残差
> - **Stochastic Depth**：随机跳过层（DropPath），训练正则
>
> **残差的局限**
>
> 1. **不能解决梯度爆炸**（那是梯度裁剪的事）
> 2. **不一定解决 trainability 退化**（Pre-LN 帮更多）
> 3. **深层仍可能"能力饱和"**（不是越深越好）
>
> **残差连接 vs Highway Network**
>
> Highway（2015）：`y = g · F(x) + (1-g) · x`，门控混合。
> 残差（2015）：`y = F(x) + x`，简化版，g=1。
> → 实验证明：残差足够好，门控多余。
>
> **总结**：**残差连接能缓解梯度消失**。**原理：提供"高速公路"让信息和梯度直接传播，反向传播始终有一个"1"项**。**Transformer 每个子层都包残差，是深层稳定训练的基石**。配合 LayerNorm、warm-up、梯度裁剪，让 LLM 能训到 100+ 层、千亿参数。没有残差连接，就没有现代大模型。

### [Transformer 中的注意力遮蔽（Attention Masking）的工作原理是什么？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834696833667074)

> **答案**：
>
> **注意力遮蔽（Attention Masking）**
>
> Attention Masking = **在 attention 计算时，用 mask 矩阵屏蔽某些位置的注意力分数**，让模型不去关注这些位置。
>
> **两种核心 Mask**
>
> **1. Padding Mask**
>
> NLP 训练时一个 batch 内序列长度不一，短的用 0 padding。padding token 不应该参与 attention 计算。
>
> ```
> batch_size=2, max_len=5
>
> 序列 A：[I, love, NLP, <pad>, <pad>]
> 序列 B：[Hello, world, foo, bar, baz]
>
> padding_mask（在 attention 中 mask 掉 <pad>）：
> A: [1, 1, 1, 0, 0]
> B: [1, 1, 1, 1, 1]
> ```
>
> 实现：把 padding 位置的 attention score 设为 -∞，softmax 后权重为 0。
>
> **2. Causal Mask（Look-ahead Mask）**
>
> Decoder 自回归生成时，**不能看到未来 token**。
>
> ```
> 序列 "I love NLP"，causal mask（5×5）：
>
>     I   love NLP  ?   ?
> I   0   -∞  -∞  -∞  -∞
> love 0   0   -∞  -∞  -∞
> NLP 0   0   0   -∞  -∞
> ?   0   0   0   0   -∞
> ?   0   0   0   0   0
> ```
>
> 下三角矩阵。位置 i 只看到 ≤i 的位置。
>
> **Padding + Causal 组合**
>
> Decoder 中两个 mask 同时使用：
> ```
> combined_mask = padding_mask | causal_mask
> ```
>
> 只要其中一个是 -∞，结果就是 -∞。
>
> **Mask 在 Attention 公式中的应用**
>
> ```
> S = Q · K^T / √d_k              # (N, N) 原始分数
> S = S + mask                     # masked 位置变成 -∞
> A = softmax(S)                   # -∞ → 0
> output = A · V
> ```
>
> **Softmax 对 -∞ 的处理**
>
> `exp(-∞) = 0` → softmax 输出为 0 → 该位置的 V 不贡献 → 屏蔽生效。
>
> 工程实现：不要用 -1e9，要用 **-inf** 或 **float('-inf')**，避免数值精度问题。
>
> **3 种 Transformer Attention 的 Mask**
>
> | Attention 类型 | Padding Mask | Causal Mask |
> |---|---|---|
> | Encoder Self-Attention | ✓ | ✗（双向） |
> | Decoder Masked Self-Attention | ✓ | ✓ |
> | Cross-Attention | ✓（K 侧） | ✗ |
>
> **Mask 的工程实现（PyTorch）**
>
> ```python
> # Padding mask
> padding_mask = (input_ids != PAD_TOKEN_ID)  # (B, N)
> padding_mask = padding_mask[:, None, None, :]  # broadcastable to (B, 1, 1, N)
>
> # Causal mask
> causal_mask = torch.tril(torch.ones(N, N))  # 下三角 = 1
> causal_mask = causal_mask[None, None, :, :]  # (1, 1, N, N)
>
> # 合并
> combined = padding_mask & (causal_mask == 1)  # bool
>
> # 应用到 score
> S = S.masked_fill(~combined, float('-inf'))
> ```
>
> **PyTorch 内置：F.scaled_dot_product_attention**
>
> ```python
> import torch.nn.functional as F
>
> attn_output = F.scaled_dot_product_attention(
>     Q, K, V,
>     attn_mask=combined_mask,  # bool 或 -inf/0
>     is_causal=True,           # 自动生成 causal mask
>     dropout_p=0.1,
> )
> ```
>
> **特殊 Mask**
>
> **1. Prefix LM Mask（如 T5、UniLM）**
> - 前缀部分双向，生成部分 causal
> - 适合：填空 + 生成混合任务
>
> **2. Span Mask（SpanBERT, BART）**
> - Mask 整个 span，不是单 token
> - 适合：denoising 任务
>
> **3. Document Mask（Longformer）**
> - 局部窗口 attention + 全局 token
> - 处理超长文档
>
> **4. Block Sparse Mask（BigBird）**
> - 块状稀疏 attention
> - O(N) 复杂度
>
> **Mask 在 Cross-Attention 中**
>
> Cross-Attention：Q 来自 Decoder，K、V 来自 Encoder。
> - Mask K 侧的 padding（不关注 Encoder 的 padding token）
> - **不需要 causal mask**（Decoder 可以看 Encoder 的所有位置）
>
> **Mask 与 KV Cache**
>
> 推理时（自回归生成），每生成一个 token 都要算 attention。
> - KV Cache 存历史 K、V
> - 当前 token 的 Q 只关注"过去所有 + 自己"
> - causal mask 已经隐含在 cache 结构中（只能访问已生成的）
>
> **Mask 的可视化**
>
> BERT 的 attention map（无 causal mask）：
> - 矩阵满（双向关注）
>
> GPT 的 attention map（有 causal mask）：
> - 下三角亮，上三角暗
>
> **总结**：Attention Masking 是 Transformer 控制"能看到什么"的核心机制。**两种核心 mask：Padding（忽略 padding）+ Causal（防止看到未来）**。**应用方式：在 softmax 前加 -∞**。**Decoder 用 causal，Encoder 不用，Cross-Attention 不用 causal**。理解 Mask 是理解 Decoder 自回归、Encoder 双向编码、padding 处理的关键。

### [了解 ViT（Vision Transformer） 吗？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834699513827329)

> **答案**：
>
> **Vision Transformer（ViT）**
>
> ViT = **把 Transformer 应用到图像识别**的架构，Dosovitskiy et al. 2020 提出（Google）。论文《An Image is Worth 16x16 Words》。
>
> **核心思想**
>
> 把图像当成"序列"处理，用 NLP 的 Transformer Encoder 直接分类。
>
> **架构**
>
> ```
> 图像 (H, W, C)
>     ↓
> 切 patch：(N, P, P, C)，N = (H/P) × (W/P)
>     ↓
> flatten 每个 patch：(N, P*P*C)
>     ↓
> 线性投影到 d_model：(N, d_model)  ← 等价于 NLP 的 token embedding
>     ↓
> 加 [CLS] token：(N+1, d_model)
>     ↓
> 加位置编码（learned 1D）：(N+1, d_model)
>     ↓
> Transformer Encoder × L
>     ↓
> 取 [CLS] token 输出
>     ↓
> MLP Head → 分类
> ```
>
> **示例：ViT-B/16**
> - 输入：224×224×3
> - Patch 大小：16×16
> - Patch 数：14 × 14 = 196
> - d_model：768
> - 层数：12
> - Head 数：12
> - 参数：86M
>
> **关键设计**
>
> **1. Patch Embedding**
> ```
> patches = image.unfold(P, P)  # 切块
> patches = patches.flatten().Linear(d_model)
> ```
> 等价于卷积核大小 = stride = P 的 Conv2d。
>
> **2. [CLS] Token**
> - 借鉴 BERT
> - 是一个可学习的 token，放在序列开头
> - 最后用它做分类（聚合全局信息）
>
> **3. 位置编码**
> - Learned 1D PE（每个 patch 一个可学向量）
> - 不用 2D PE 实验证明 1D 足够
>
> **4. Transformer Encoder**
> - 标准架构：Multi-Head Self-Attention + FFN + LN + 残差
> - 用 Pre-LN（GPT 风格）更稳定
>
> **训练**
>
> - **大数据是关键**：ViT 在小数据（ImageNet 1.2M）上不如 CNN（ResNet），但在大数据（JFT-300M、ImageNet-21k）上超越 CNN
> - 监督预训练 → 下游微调
>
> **性能**
>
> - ViT-H/14：88.55% ImageNet Top-1（SOTA 之一）
> - 计算量比 ResNet 高，但更可扩展
> - 大数据下的"_scaling law_"：模型越大，效果越好
>
> **ViT 的意义**
>
> 1. **证明 Transformer 是通用架构**：不止 NLP，CV 也行
> 2. **打破 CNN 垄断**：CV 不一定需要归纳偏置（局部性、平移不变性）
> 3. **多模态基础**：CLIP、BLIP、Flamingo 都基于 ViT 处理图像
> 4. **Scaling Law 通用**：CV 也能 scale up
>
> **ViT 的演进**
>
> - **DeiT**（Facebook）：数据高效训练，ImageNet 单数据集就能 SOTA
> - **Swin Transformer**（Microsoft）：层级 + 滑窗，处理密集预测（检测、分割）
> - **BEiT**（Microsoft）：BERT 式预训练（mask patch + 重建）
> - **MAE**（He et al. 2022）：masked autoencoder，自监督预训练
> - **CLIP**（OpenAI）：图文对比学习，对齐到统一空间
> - **DINOv2**：自监督 ViT 特征，无需标注
>
> **应用**
>
> - **图像分类**：标准任务
> - **目标检测**：DETR 系列（ViT + 检测头）
> - **分割**：Mask2Former、Segment Anything（SAM 用 ViT）
> - **多模态**：CLIP、BLIP、Flamingo、LLaVA（ViT + LLM）
> - **视频**：TimeSformer、ViViT（时空 ViT）
>
> **ViT vs CNN**
>
> | 维度 | CNN（ResNet） | ViT |
> |---|---|---|
> | 归纳偏置 | 局部性 + 平移不变 | 无（数据驱动） |
> | 小数据表现 | 好 | 差 |
> | 大数据表现 | 饱和 | 持续提升 |
> | 全局信息 | 弱（深层才有） | 强（自注意力直接交互） |
> | 计算量 | 中 | 高（O(N²)） |
> | 可解释性 | 中 | 高（attention 可视化） |
>
> **总结**：ViT = **把图像切 patch + 当序列处理 + 标准 Transformer Encoder**。**意义：证明 Transformer 通用性 + 打破 CNN 垄断 + 开启多模态时代**。**关键设计：Patch Embedding + [CLS] Token + Learned PE + Transformer Encoder**。**演进：DeiT（数据高效）+ Swin（密集预测）+ MAE（自监督）+ CLIP（多模态）**。现代 CV 离不开 ViT，所有 vision-language 模型（CLIP、LLaVA、SAM）都用 ViT 处理图像。

### [ViLT 模型是如何将 Transformer 应用于图像识别任务的](https://www.mianshiya.com/bank/1906189461556076546/question/1821834700050698241)

> **答案**：
>
> **ViLT 如何将 Transformer 应用于图像识别**
>
> ViLT = **Vision-and-Language Transformer**（Kim et al. 2021），把图像和文本统一到**单一 Transformer**里做视觉-语言任务（VQA、视觉推理、图文检索）。
>
> **关键设计**
>
> ViLT 的核心创新：**去掉 CNN/RoI 特征提取器，直接用 Transformer 同时处理 patch + 文本 token**。
>
> **与其他模型对比**
>
> | 模型 | 图像特征 | 文本特征 | 融合方式 | 模型大小 |
> |---|---|---|---|---|
> | VisualBERT / VL-BERT | Region Features（Faster R-CNN） | BERT | 早期融合 | 大（含 detector） |
> | CLIP | ResNet/ViT | Transformer | 晚期对比 | 大 |
> | **ViLT** | Linear Patch Projection | BERT Token | 早期融合 | **最小**（无 visual backbone） |
>
> **架构**
>
> ```
> 输入：
>   图像 → Patch Embedding（线性投影，无 CNN）
>   文本 → BPE Token + Token Embedding
>   [CLS]_img, [CLS]_text → 模态标识 token
>
> 拼接：
>   [img_tok, patch_1, ..., patch_N, text_tok, word_1, ..., word_M]
>   +
>   位置编码 + 模态 embedding + 段 embedding
>
> → Transformer Encoder × L（模态自由交互）
>
> → 取 [CLS] 输出 → MLP Head → 任务输出
> ```
>
> **核心：用最简的方式注入图像信息**
>
> - 不用 Region Features（省掉 Faster R-CNN 这个庞然大物）
> - 不用深层 CNN backbone（省掉 ResNet）
> - 仅用 **patch + 线性投影**（与 ViT 一样）
> - 模型参数大幅减少（~80M vs ~250M）
>
> **模态融合**
>
> ViLT 通过 Transformer 的 self-attention 自然实现模态融合：
> - patch token 和 word token 在同一序列里
> - cross-attention 通过 self-attention 实现
> - 浅层就发生模态交互
>
> **预训练目标**
>
> **1. Image Text Matching（ITM）**
> - 给定 (image, text)，判断是否匹配
> - 用 [CLS] 输出做二分类
> - 负样本：随机替换图文
>
> **2. Masked Language Modeling（MLM）**
> - 随机 mask 文本 token
> - 用图像 + 上下文 token 预测
> - 让文本"看到"图像
>
> **3. Word Patch Alignment（WPA，可选）**
> - 对齐 image patch 和 word
>
> **性能**
>
> - VQA、COCO Retrieval 等任务上 SOTA（同等参数量）
> - 训练快、参数少
> - 但纯粹依赖大数据预训练
>
> **ViLT 的意义**
>
> 1. **极简架构**：证明视觉-语言任务不需要复杂的 visual backbone
> 2. **效率**：参数少 70%，训练快
> 3. **可扩展**：奠定后续 BLIP、Flamingo 的设计思路
>
> **ViLT 的局限**
>
> 1. **缺乏 inductive bias**：小数据训练效果差
> 2. **图像表示简单**：没用 CNN 的局部性，需要大量预训练
> 3. **下游任务受限**：对密集预测（检测、分割）不友好
>
> **后续演进**
>
> - **BLIP / BLIP-2**（Salesforce）：更高效的视觉-语言预训练，加入 Q-Former
> - **Flamingo**（DeepMind）：few-shot 视觉问答
> - **LLaVA**：ViT + LLM（Vicuna）+ 投影层
> - **GPT-4V、Gemini**：原生多模态大模型
>
> **典型应用：Visual Question Answering（VQA）**
>
> ```
> 输入：
>   Image: 一张猫坐在沙发上的照片
>   Question: "What is the cat sitting on?"
>
> ViLT:
>   Patch tokens + Question tokens → Transformer → [CLS]
>
> 输出：
>   Answer: "a couch" / "a sofa"
> ```
>
> **与纯 ViT 的区别**
>
> - ViT：仅图像分类
> - ViLT：图像 + 文本，跨模态理解
> - ViLT 复用了 ViT 的 patch embedding 思想，扩展到多模态
>
> **总结**：ViLT = **Vision-and-Language Transformer，用最简方式（线性 patch 投影）注入图像，文本和图像通过 Transformer self-attention 自然融合**。**创新：去掉 visual backbone（CNN/RoI），参数减 70%**。**预训练：ITM + MLM**。**意义：奠基现代多模态架构（BLIP、Flamingo、LLaVA）**。理解 ViLT 是理解现代 vision-language 模型的关键——**让 Transformer "一统" 模态**。

### [为什么 Transformer 采用多头注意力机制？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834694916870145)

> **答案**：
>
> **为什么 Transformer 采用多头注意力（Multi-Head Attention）**
>
> Multi-Head Attention = **把 Q、K、V 分成 h 组，各自独立做 attention，最后 concat**。
>
> ```
> MultiHead(Q, K, V) = Concat(head_1, ..., head_h) · W_O
>
> head_i = Attention(Q·W_Q^i, K·W_K^i, V·W_V^i)
> ```
>
> 例如 d_model=512, h=8, 每个 head d=64。
>
> **为什么不用单头大 attention？**
>
> 单头 attention（d=512）理论上能学到所有关系，但实际上：
> - **表达能力受限**： softmax 单一分布，模型只能学到一种"关注模式"。
> - **特征混杂**：所有特征在同一个 512 维空间里被拉扯。
>
> **多头的好处**
>
> **1. 多子空间并行学习（核心）**
>
> 每个 head 投影到 64 维子空间，独立学不同的关系模式：
> - Head 1：学语法依存（主谓一致）
> - Head 2：学指代消解
> - Head 3：学长程依赖
> - Head 4：学局部窗口
> - Head 5：学标点结构
> - Head 6：学语义相似
> - Head 7：学 rare word attention
> - Head 8：学 syntactic head
>
> 可视化研究（Clark et al. 2019, Vig 2019）证实不同 head 学到的模式差异巨大。
>
> **2. 计算量与单头相当**
>
> ```
> 单头 d=512：Q·K^T 是 (N, 512) × (512, N) = N²·512
> 多头 h=8, d=64：8 × (N, 64) × (64, N) = 8·N²·64 = N²·512
> ```
>
> → **总 FLOPs 相同**！但表达能力更强。
>
> **3. 类比 CNN 的多通道**
>
> CNN 用多个卷积核学不同特征（边缘、纹理、形状），多头 attention 同理——多个 head 学不同语义/语法关系。
>
> **4. 集成效应**
>
> 每个 head 像一个"弱学习器"，concat 后再投影 → 类似集成学习，提升泛化。
>
> **5. 稳定训练**
>
> 不同 head 提供多条梯度路径，避免单点失效。
>
> **实验证据**
>
> - 原论文实验：h=8 比 h=1 在 WMT 翻译上 BLEU +1-2。
> - h=4 也行，但 h=1 显著变差。
> - h 太大（如 h=32）效果不一定更好——子空间太小，每个 head 表达力不足。
>
> **头数选择**
>
> | 模型 | 头数 h | 每头维度 d | d_model |
> |---|---|---|---|
> | Transformer base | 8 | 64 | 512 |
> | Transformer big | 16 | 64 | 1024 |
> | BERT-base | 12 | 64 | 768 |
> | GPT-2 small | 12 | 64 | 768 |
> | GPT-3 175B | 96 | 128 | 12288 |
> | Llama 2 7B | 32 | 128 | 4096 |
> | Llama 2 70B | 64 | 128 | 8192（用 GQA） |
>
> 经验法则：**d_head = 64~128 之间最好**。h 多了省不下太多参数（d_model 固定）。
>
> **头的简化（推理优化）**
>
> **1. Head Pruning（剪枝）**
> - 很多 head 训练后"无用"（不学特定模式）
> - 剪掉 30-50% head，性能损失 <1%
> - 推理更快
>
> **2. Multi-Query Attention（MQA）**
> - 所有 head 共享一组 K、V
> - KV cache 大幅减少
> - 推理快 5-10 倍
>
> **3. Grouped Query Attention（GQA）**
> - 分组共享 K、V
> - 性能介于 MHA 和 MQA 之间
> - Llama 2 70B、Llama 3 都用 GQA
>
> **现代趋势**
>
> - 训练时仍用多头（学更多模式）
> - 推理时通过 GQA / MLA 减少 KV cache
> - "8 head 各 64 维" 几乎是黄金标准（从 2017 到 2024 不变）
>
> **总结**：多头注意力 = **同一计算量下，多子空间并行学习多种关系模式**。**核心理由：① 多模式并行学习 ② 计算量不变 ③ 集成效应 ④ 类比 CNN 通道**。**经验：d_head = 64~128 最佳**。**现代优化（MQA/GQA/MLA）主要减少 KV cache，不改变多头核心思想**。这是 Transformer 设计中最优雅的部分之一。

### [self attention 中的 K 和 Q 是用来做什么的？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834694111563778)

> **答案**：
>
> **Self-Attention 中 K 和 Q 的用途**
>
> K（Key）和 Q（Query）来自信息检索（search）的"key-value store"类比——**用 Q 去匹配 K，取出对应的 V**。
>
> **信息检索类比**
>
> 想象一个字典：
> ```python
> database = {
>   "apple": "水果",
>   "car": "交通工具",
>   ...
> }
>
> query = "fruit"
> # 找最匹配的 key，返回对应的 value
> result = database[query]
> ```
>
> 但现实中的 query 通常**不会精确匹配** key，需要"软匹配"：query 与每个 key 算相似度，按权重返回所有 value 的加权和。
>
> ```
> attention(q, K, V) = Σ softmax(q · k_i) · v_i
> ```
>
> - Q = "我在找什么"
> - K = "每个候选有什么标签"
> - V = "每个候选实际提供的内容"
>
> **Self-Attention 的特殊点**
>
> 在 self-attention 中，**Q、K、V 都来自同一个输入 X**（同一个序列的不同线性投影）：
>
> ```
> Q = X · W_Q  # 我作为"提问者"想找什么
> K = X · W_K  # 我作为"被查询者"能提供什么匹配
> V = X · W_V  # 我作为"信息源"实际提供的内容
> ```
>
> 为什么用不同投影矩阵 W_Q, W_K, W_V？因为**同一个 token 在不同角色下需要表达不同信息**：
> - "我"作为提问者（Q）：可能关注语法角色（如主语找谓语）
> - "我"作为被查询者（K）：可能暴露语义特征
> - "我"作为信息源（V）：可能提供词义内容
>
> **例子：理解 "The cat sat on the mat because it was tired"**
>
> 处理 `it` 时：
> - Q(`it`)："我是个代词，要找指代对象"
> - K(`cat`)："我是名词，主语"
> - K(`mat`)："我是名词，宾语"
> - attention score = Q(`it`) · K(`cat`) > Q(`it`) · K(`mat`)
> - → `it` 的输出主要融合了 `cat` 的信息（V(`cat`)）
>
> **Q 和 K 不同，让模型学到的能力**
>
> 1. **语法依存**：主语-谓语、修饰-中心词
> 2. **指代消解**：代词找指代对象
> 3. **长程依赖**：开头词影响后面词
> 4. **对齐**：翻译时源词对目标词
>
> 如果 Q = K：
> - `score(i, j) = q_i · q_j` → 矩阵对称
> - 模型无法区分"我作为查询者"和"我作为被查询者"
> - 表达能力下降
>
> 实验：Q=K 的模型在 NLP 任务上掉点 3-8%。
>
> **多头（Multi-Head）下的 Q、K**
>
> 每个 head 独立学一组 W_Q, W_K, W_V：
> ```
> head_i = Attention(Q · W_Q^i, K · W_K^i, V · W_V^i)
> ```
>
> 不同 head 学不同模式：
> - Head 1 可能学"语法依存"
> - Head 2 可能学"指代"
> - Head 3 可能学"长程依赖"
> - ...
>
> 可视化（Bertology 研究）确实发现不同 head 关注不同关系。
>
> **Cross-Attention 中的 Q、K、V**
>
> 在 Encoder-Decoder（如 T5、原始 Transformer）中：
> - Q 来自 Decoder 当前位置
> - K、V 来自 Encoder 输出
> - 这就是 "Decoder 在 Encoder 输出里查找信息"
>
> **总结**：
> - **Q（Query）**：当前 token "想找什么"——发起查询。
> - **K（Key）**：每个 token "提供什么标签"——被查询。
> - **V（Value）**：每个 token "实际提供什么内容"——被取用。
> - 三者来自同输入的不同投影，让模型**扮演不同角色**学习不同关系。
> - 这是 Transformer 表达能力的核心。

### [Transformer 中如何实现序列到序列的映射？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834697366343682)

> **答案**：
>
> **Transformer 如何实现序列到序列（seq2seq）映射**
>
> Sequence-to-Sequence = **输入一个变长序列，输出另一个变长序列**。典型任务：机器翻译、摘要、对话、代码生成。
>
> **Encoder-Decoder 架构**
>
> 原始 Transformer 用完整的 Encoder-Decoder 实现 seq2seq：
>
> ```
> 源序列 X = (x_1, x_2, ..., x_m)
>
> Encoder：
>   X → Embedding + PE → Encoder × N → Context Vectors (m × d)
>
> 目标序列 Y = (y_1, y_2, ..., y_n)
> （训练时 shifted right：输入 <sos> y_1 y_2 ... y_{n-1}）
>
> Decoder：
>   Y' → Embedding + PE → Decoder × N → Logits (n × V)
>                                     → Softmax → P(y_t | y_<t, X)
>
> 生成（推理）：
>   Y' = [<sos>]
>   loop:
>     logits = Decoder(Y', Context)
>     next_token = sample(argmax(logits[-1]))
>     Y' = Y' + [next_token]
>     if next_token == <eos>: break
> ```
>
> **Encoder 部分**
>
> - **双向 self-attention**：每个源 token 看到所有源 token
> - 输出：每个源 token 的上下文向量
> - 作用：**理解源输入**
>
> **Decoder 部分**
>
> 3 个子层：
> 1. **Masked Self-Attention**：自回归，避免看未来
> 2. **Cross-Attention**：Q 来自 Decoder，K/V 来自 Encoder
> 3. **FFN**：非线性变换
>
> 作用：**生成目标序列，并动态查阅源信息**
>
> **Cross-Attention 是 seq2seq 的核心**
>
> ```
> CrossAttn:
>   Q = Decoder 当前位置 → "我要生成 y_t，要找什么源信息"
>   K, V = Encoder 输出 → "源序列每个位置提供什么"
>
>   → y_t 主要融合与当前位置相关的源 token
> ```
>
> 例如翻译 "I love cats" → "我喜欢猫"：
> - 生成 "我" 时，Q 关注源 "I"
> - 生成 "喜欢" 时，Q 关注源 "love"
> - 生成 "猫" 时，Q 关注源 "cats"
>
> **训练：Teacher Forcing**
>
> 训练时不用上一步预测结果作为输入，而用**真实答案**：
>
> ```
> 输入 Decoder：<sos> 我 喜欢 猫
> 目标：       我 喜欢 猫 <eos>
> ```
>
> - 避免"错一步步步错"
> - 训练高效（并行）
> - 但 train/test 不一致（exposure bias）
>
> **推理：自回归生成**
>
> ```
> step 1: 输入 [<sos>]，预测 → "我"
> step 2: 输入 [<sos>, 我]，预测 → "喜欢"
> step 3: 输入 [<sos>, 我, 喜欢]，预测 → "猫"
> step 4: 输入 [<sos>, 我, 喜欢, 猫]，预测 → <eos>，停止
> ```
>
> 每步 forward 都要重算 attention。**KV Cache 大幅加速**。
>
> **Loss 函数**
>
> ```
> L = -Σ_t log P(y_t | y_<t, X; θ)
> ```
>
> 每个位置的 cross-entropy，平均。
>
> **Variants**
>
> **1. Encoder-Decoder（原始 Transformer, T5, BART）**
> - 适合：翻译、摘要
> - 双向理解 + 自回归生成
>
> **2. Decoder-only（GPT, Llama, Claude）**
> - 现代主流
> - 把"源 + 目标"拼成一个序列，全部 causal attention
> - 例：`[源文本] [SEP] [生成内容]`
> - 通用，能力全面
>
> **3. Prefix-LM（UniLM, T5 部分）**
> - 源序列双向，目标序列 causal
> - 介于两者之间
>
> **为什么 Decoder-only 流行**
>
> - **训练目标统一**（next token）
> - **架构简单**
> - **数据效率高**
> - **能力全面**（理解 + 生成）
> - **Scaling Law 友好**
>
> 代价：理解类任务（如分类）效率不如 Encoder-only，但通过 prompting 弥补。
>
> **seq2seq 的关键挑战**
>
> 1. **长度变化**：m ≠ n
>    - Decoder 自回归，输出长度可变
> 2. **对齐**：哪个源 token 对应哪个目标 token
>    - Cross-attention 自动学
> 3. **长依赖**：源中远距离信息
>    - Encoder self-attention 直接交互
> 4. **生成质量**：diversity、fluency
>    - 采样策略（top-k, top-p, temperature）
>
> **典型 seq2seq 任务**
>
> | 任务 | 输入 | 输出 |
> |---|---|---|
> | 机器翻译 | 英文 | 中文 |
> | 摘要 | 长文档 | 短摘要 |
> | 对话 | 用户消息 | 回复 |
> | 代码生成 | 需求描述 | 代码 |
> | 问答 | 问题 + 上下文 | 答案 |
>
> **总结**：Transformer 实现 seq2seq 的核心是 **Encoder-Decoder 架构 + Cross-Attention**。**Encoder 双向理解源序列**，**Decoder 自回归生成目标**，**Cross-Attention 让 Decoder 动态查阅源**。训练用 Teacher Forcing，推理用自回归。**现代 LLM 多用 Decoder-only**，通过拼接序列实现 seq2seq，更通用更可扩展。理解 seq2seq 是理解翻译、摘要、对话等任务的基础。

### [Transformer 中，如何处理大型数据集？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834697936769026)

> **答案**：
>
> **Transformer 处理大型数据集**
>
> Transformer 是当前大模型预训练的标准架构，处理 TB-PB 级数据需要**数据、工程、算法**协同。
>
> **1. 数据层**
>
> **数据来源**
> - **网页**：Common Crawl（CLUE、RefinedWeb）
> - **书籍**：Books3、Gutenberg
> - **代码**：The Stack、GitHub
> - **学术**：arXiv、PubMed
> - **对话**：Reddit、Stack Overflow
>
> **数据预处理**
> - **清洗**：去 HTML、去重、去低质（perplexity 过滤）
> - **去重**：MinHash、LSH（大规模相似检测）
> - **分词**：BPE、Unigram（sentencepiece、tiktoken）
> - **混合配比**：网页 60%、代码 20%、书籍 10%、学术 5%、对话 5%
>
> **数据规模（参考）**
> - BERT：16 GB
> - GPT-3：570 GB
> - Chinchilla：1.4 TB
> - Llama 2：2 TB
> - Llama 3：15+ TB
>
> **2. 训练工程**
>
> **分布式训练（必需）**
>
> 单卡训不动大模型，必须分布式：
> - **数据并行（DP）**：每卡完整模型，处理不同 batch
> - **张量并行（TP）**：单层内分卡（Megatron-LM）
> - **流水线并行（PP）**：层间分卡（PipeDream）
> - **3D 并行**：DP + TP + PP 组合（GPT-3、Llama 训练）
>
> **优化器状态分片**
> - **ZeRO-1/2/3**（DeepSpeed）：分片优化器/梯度/参数
> - **FSDP**（PyTorch 原生）
>
> **混合精度**
> - bf16 / fp16 训练
> - fp32 master weight + fp16 gradients
>
> **Gradient Checkpointing**
> - 不存 activations，反向传播时重算
> - 省 10x activations 显存，慢 ~30%
>
> **FlashAttention**
> - IO-aware attention 算法
> - 不改数学等价，但 HBM 访问大幅减少
> - 训练快 2-3 倍，省显存 5-10 倍
>
> **3. 算法层**
>
> **学习率调度**
> - Warm-up：前 2000-8000 step 线性升到峰值
> - Cosine decay：之后余弦衰减
> - 最终降到峰值的 10%
>
> **Batch Size Schedule**
> - 小 batch 起步（避免初期不稳）
> - 逐步增大（如 GPT-3 从 32 到 2M tokens）
> - 提升训练效率
>
> **梯度裁剪**
> - max_norm = 1.0
> - 防止梯度爆炸
>
> **初始化**
> - 截断正态、Xavier
> - μP（maximal update parameterization）：让超参可移植
>
> **4. 数据加载**
>
> **Iterable Dataset**
> - 不一次性加载到内存
> - 流式读取（适合 TB 级）
>
> **Multi-source Sampling**
> - 不同来源按比例混合
> - 动态调整（如训练后期增加高质量数据）
>
> **Packing**
> - 短样本拼接（避免 padding 浪费）
> - 注意 attention mask 防止跨样本
>
> **5. 监控与容错**
>
> **Loss 曲线**
> - 监控 train loss、eval loss
> - Loss spike → 检查数据、学习率
>
> **梯度监控**
> - 梯度范数、裁剪触发频率
> - 异常告警
>
> **Checkpoint**
> - 定期保存（每 1000-10000 step）
> - 故障恢复
> - 关键 checkpoint 永久存档
>
> **硬件故障**
> - 大规模训练 24 小时内必有 GPU 故障
> - 自动恢复机制
> - 备份 checkpoint
>
> **6. 数据质量与多样性**
>
> **质量分级**
> - 高质量数据训练后期 / SFT
> - 一般数据预训练
> - 低质过滤掉
>
> **多样性**
> - 覆盖多语言、多领域、多格式
> - 避免单一来源主导
>
> **去污染**
> - 移除评测集（防 leakage）
> - 移除有毒 / 偏见内容
>
> **7. 训练后（Post-training）**
>
> **SFT（监督微调）**
> - 高质量人工标注
> - 几千到几百万样本
>
> **RLHF / DPO**
> - 偏好数据训练
> - 提升对齐
>
> **Instruction Tuning**
> - 多任务指令数据
> - FLAN、InstructIE
>
> **典型训练栈**
>
> ```
> 数据：Common Crawl + Book + Code + ...
>    ↓
> 预处理：去重 + 清洗 + 分词
>    ↓
> 训练：3D Parallel + ZeRO + bf16 + FlashAttention
>    ↓
> 监控：wandb / tensorboard + 自动恢复
>    ↓
> 评估：MMLU + HumanEval + 自建评测
>    ↓
> 对齐：SFT + RLHF
>    ↓
> 部署：vLLM / TensorRT-LLM
> ```
>
> **典型成本**
>
> - Llama 2 7B：~$1-2M
> - Llama 2 70B：~$20M
> - Llama 3 70B：~$50M
> - Llama 3 405B：~$700M
> - GPT-4：~$100M
>
> **总结**：处理大型数据集训练 Transformer 需要 **数据 + 工程 + 算法** 协同：
> - **数据**：清洗 + 去重 + 配比 + 多样性
> - **工程**：3D 并行 + ZeRO + FlashAttention + bf16
> - **算法**：warm-up + 梯度裁剪 + 适当初始化
> - **监控**：loss / 梯度 / checkpoint / 故障恢复
> - **成本**：百万 - 千万美元级
>
> Llama 3、Qwen、DeepSeek 等开源模型的训练报告（technical report）是学习大规模训练的最佳材料。

### [了解 LLaMA 中的旋转位置编码吗？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834678626193410)

> **答案**：
>
> **LLaMA 的旋转位置编码（RoPE, Rotary Position Embedding）**
>
> RoPE（Su et al. 2021，论文《RoFormer》）是 Llama / Qwen / Mistral / ChatGLM 等现代 LLM 的标准位置编码。
>
> **核心思想**
>
> 通过**旋转矩阵**把位置信息编码到 Q 和 K 上，让它们的内积**自然反映相对位置**。
>
> **数学形式**
>
> 给定位置 m 的 query 向量 q 和位置 n 的 key 向量 k（d 维，d=偶数）：
>
> ```
> q_m = R(m) · q
> k_n = R(n) · k
>
> R(m) = block-diagonal([[cos(mθ_1), -sin(mθ_1)],
>                        [sin(mθ_1),  cos(mθ_1)]],
>                       ...
>                       [[cos(mθ_{d/2}), -sin(mθ_{d/2})],
>                        [sin(mθ_{d/2}),  cos(mθ_{d/2})]])
>
> θ_i = 10000^(-2i/d)
> ```
>
> 把 q、k 看作 d/2 个复数（每 2 维一组），R 就是把这 d/2 个复数旋转不同角度。
>
> **关键性质**
>
> ```
> q_m · k_n = q · R(m-n) · k
> ```
>
> → 内积只依赖**相对位置 m-n**！
>
> **RoPE 的实现（高效版）**
>
> 不用矩阵乘法（O(d²)），用 element-wise：
> ```python
> def apply_rope(x, positions):
>     # x: (..., N, d)
>     # 把 x 拆成 (x_even, x_odd) 两半
>     x1, x2 = x[..., ::2], x[..., 1::2]
>     # 计算每个位置的旋转角度
>     angles = positions[:, None] * inv_freq  # (N, d/2)
>     cos, sin = angles.cos(), angles.sin()
>     # 旋转
>     out_even = x1 * cos - x2 * sin
>     out_odd  = x1 * sin + x2 * cos
>     # interleave 回去
>     return stack_interleave(out_even, out_odd)
> ```
>
> 计算复杂度 O(d)，可忽略。
>
> **RoPE 的优势**
>
> **1. 相对位置敏感**
>
> attention score 自然反映"两 token 距离"，更适合自然语言（绝对位置不重要，相对位置重要）。
>
> **2. 训练推理一致**
>
> 不需要像 learned PE 那样学参数，对任意位置 m 都能算 R(m)。
>
> **3. 外推性（Extrapolation）**
>
> 理论上 RoPE 能处理任意长度，但实际有衰减问题（远距离注意力下降）。配合 NTK、YaRN 等技术，能从 4K 扩展到 1M。
>
> **4. 不增加参数**
>
> 纯数学运算，不学位置向量。比 learned PE 少 ~1M 参数（d_model × max_len）。
>
> **5. 与 attention 公式无缝**
>
> 只改 Q、K（旋转），attention 公式不变。V 不动。
>
> **RoPE 的外推问题**
>
> 直接用训练长度外的位置（如训练 4K，推理 32K），效果会下降。原因：
> - 远距离 cos/sin 振荡剧烈
> - 模型在训练时没见过这些位置
>
> **解决方案**
>
> **1. Position Interpolation（PI）**
> - 把位置索引缩放：`m → m · (train_len / target_len)`
> - 简单线性外推
> - Llama 2 Long 用
>
> **2. NTK-aware Scaling**
> - 调整 θ 的基数：`10000 → 10000 · α^(d/(d-2))`
> - 不同维度不同缩放
> - 平滑外推
>
> **3. YaRN**
> - 分段缩放（不同频率不同处理）
> - 更精细，效果最好
> - Llama 3、Qwen2 用
>
> **4. LongRoPE**
> - 进化算法搜索最佳缩放策略
> - 到 2M 上下文
>
> **Llama 系列中的 RoPE**
>
> - **Llama 1/2/3**：RoPE + (Llama 3 用 YaRN 长上下文)
> - **Qwen 1/2/2.5**：RoPE
> - **Mistral / Mixtral**：RoPE
> - **ChatGLM**：RoPE（早期 GLM 用 LayerNorm + RoPE）
> - **DeepSeek**：RoPE（V2/V3 用 MLA + 解耦 RoPE）
>
> **RoPE 的实现细节（Llama）**
>
> ```python
> class LlamaRotaryEmbedding(nn.Module):
>     def __init__(self, dim, max_position_embeddings=2048, base=10000):
>         super().__init__()
>         inv_freq = 1.0 / (base ** (torch.arange(0, dim, 2).float() / dim))
>         self.register_buffer("inv_freq", inv_freq)
>         self.max_seq_len_cached = max_position_embeddings
>
>     def forward(self, x, seq_len):
>         # seq_len: 当前序列长度
>         t = torch.arange(seq_len, device=x.device).type_as(self.inv_freq)
>         freqs = torch.einsum("i,j->ij", t, self.inv_freq)
>         emb = torch.cat([freqs, freqs], dim=-1)  # (seq_len, dim)
>         return emb.cos(), emb.sin()
>
> def rotate_half(x):
>     x1 = x[..., :x.shape[-1] // 2]
>     x2 = x[..., x.shape[-1] // 2:]
>     return torch.cat([-x2, x1], dim=-1)
>
> def apply_rotary_pos_emb(q, k, cos, sin):
>     q_embed = (q * cos) + (rotate_half(q) * sin)
>     k_embed = (k * cos) + (rotate_half(k) * sin)
>     return q_embed, k_embed
> ```
>
> **对比其他位置编码**
>
> | 方案 | 类型 | 外推 | 主要用户 |
> |---|---|---|---|
> | Sinusoidal | 绝对 | 一般 | 原始 Transformer |
> | Learned | 绝对 | 差 | BERT、GPT-2 |
> | T5 Relative Bias | 相对 | 一般 | T5 |
> | **RoPE** | **相对** | **好（可扩展）** | **Llama、Qwen、Mistral** |
> | ALiBi | 相对 | 极好 | BLOOM |
>
> **总结**：**RoPE = 旋转矩阵 + 内积自然反映相对位置**。**优势：相对位置敏感 + 训练推理一致 + 可外推 + 零额外参数 + 与 attention 无缝集成**。**外推方法：PI、NTK-aware、YaRN、LongRoPE**。**所有现代开源 LLM 几乎都用 RoPE**。理解 RoPE 是理解现代 LLM 架构的关键，也是长上下文工程（如 RAG、Agent）的基础。

### [了解 Transformer 模型训练中的梯度裁剪（Gradient Clipping）吗？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834696267436033)

> **答案**：
>
> **梯度裁剪（Gradient Clipping）**
>
> 梯度裁剪 = **训练时反向传播计算出梯度后，对梯度大小进行限制**，防止梯度爆炸。是 Transformer（以及所有深层神经网络）训练的**必备技巧**。
>
> **为什么需要梯度裁剪**
>
> **问题：梯度爆炸**
>
> - 深层网络梯度连乘多次
> - 即使有 LayerNorm、残差连接，训练初期梯度仍可能爆炸
> - 一旦某步梯度太大，权重更新过猛 → loss 突然变 NaN → 训练崩溃
>
> **典型场景**：
> - 大模型预训练（GPT-3、Llama）
> - 长序列训练
> - 学习率过大 / warm-up 不充分
> - mixed precision（fp16/bf16）下数值溢出
>
> **梯度裁剪的两种方式**
>
> **1. 按值裁剪（Clip by Value）**
>
> ```
> grad = max(min(grad, max_val), min_val)
> ```
>
> - 把每个梯度分量限制在 [min_val, max_val] 区间
> - 简单粗暴，会改变梯度方向
> - 较少用
>
> **2. 按范数裁剪（Clip by Norm，主流）**
>
> ```
> if ||g||_2 > max_norm:
>     g = g · (max_norm / ||g||_2)
> ```
>
> - 计算**全局梯度**的 L2 范数
> - 若超过阈值 max_norm，按比例缩小到 max_norm
> - **保留方向，只缩放幅度**
> - Transformer 标准做法
>
> **PyTorch 实现**
>
> ```python
> torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
> ```
>
> 在 backward 之后、step 之前调用。
>
> **Transformer 中的标准用法**
>
> ```python
> optimizer.zero_grad()
> loss = model(input).loss
> loss.backward()
> torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
> optimizer.step()
> scheduler.step()
> ```
>
> **max_norm 的选择**
>
> - 经验值：**1.0**（最常见）
> - 大模型有时用 0.5 或 2.0
> - Llama 2：1.0
> - GPT-3：1.0
> - BERT：1.0
> - 太大没效果，太小限制学习
>
> **为什么是 L2 范数而不是别的**
>
> - L2 范数是欧氏长度，几何意义清晰
> - 等比例缩放保留梯度方向
> - 对所有参数"一视同仁"
> - 比 by-value 更鲁棒（不会因为某个分量巨大就单独压扁）
>
> **梯度裁剪的"伙伴"**
>
> 单靠梯度裁剪不够，还要配合：
>
> **1. Warm-up**
> - 学习率先线性升，再 decay
> - 防止训练初期梯度爆炸
> - Transformer 必备
>
> **2. LayerNorm（Pre-LN）**
> - 每层归一化
> - 让训练更稳定
>
> **3. 适当的初始化**
> - Xavier、He、截断正态
> - 让初始梯度在合理范围
>
> **4. Mixed Precision（fp16 / bf16）**
> - 数值范围小
> - 必须 + 梯度裁剪 + loss scaling
>
> **5. Learning Rate Scheduler**
> - Cosine、Linear、StepLR
> - 与 warm-up 配合
>
> **梯度裁剪的局限**
>
> 1. **治标不治本**：只压住症状，根因（架构、初始化、超参）没解决。
> 2. **影响收敛速度**：压梯度 → 学习变慢。
> 3. **可能掩盖 bug**：loss spike 被压下来，但问题没修。
> 4. **不一定适合所有任务**：少量 fine-tuning 可能不需要。
>
> **进阶：自适应梯度裁剪**
>
> - AGC（Adaptive Gradient Clipping，NFNet 论文）
> - 按参数范数和梯度范数的比例裁剪
> - 更鲁棒，能用更大学习率
>
> **实战监控**
>
> 训练时监控：
> - 梯度范数（裁剪前 / 裁剪后）
> - 裁剪触发频率（多少 step 触发一次）
> - 梯度爆炸告警
>
> **理想情况**：
> - 大多数 step 不触发裁剪
> - 偶尔（<5%）触发是正常的
> - 频繁触发说明学习率太大或初始化有问题
>
> **总结**：梯度裁剪 = **训练深度网络（含 Transformer）的标配**。**主流：L2 范数裁剪，max_norm=1.0**。**作用：防止梯度爆炸，稳定训练**。**用法：backward → clip → step**。**必须配合 warm-up、LayerNorm、合理初始化、混合精度**。生产级 LLM 训练 100% 都用梯度裁剪——不裁剪训不出大模型。

### [在不考虑计算量的情况下，head 能否无限增多？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834695227248642)

> **答案**：
>
> **不考虑计算量，head 数量能无限增加吗？**
>
> **答案：不能**。
>
> 虽然计算量不是问题，但有几个数学和工程上的限制让 head 数量有上限。
>
> **限制 1：每个 head 维度必须够大**
>
> 总维度 d_model 固定（如 512），分 h 个 head，每个 head 维度：
> ```
> d_head = d_model / h
> ```
>
> | h | d_head（d_model=512） |
> |---|---|
> | 8 | 64（推荐） |
> | 16 | 32 |
> | 32 | 16 |
> | 64 | 8 |
> | 128 | 4 |
> | 512 | 1 |
>
> **d_head 太小的问题**：
> - `q · k` 是 d_head 个分量之和
> - d_head=1 时，score(q, k) = q_1 · k_1，标量乘积 → 表达力极弱
> - softmax 后基本是均匀分布（无法区分）或 one-hot（极端）
>
> 经验：**d_head < 16 时性能明显下降**。所以 h ≤ d_model / 16。
>
> **限制 2：d_head 也不应过大**
>
> 如果反过来减少 h：
> - h=1, d_head=512 → 单头 attention，表达力差（见前述）
> - 实验最优 d_head ≈ 64-128
>
> **经验范围**：`d_head ∈ [32, 128]`，最常见 64。
>
> **限制 3：参数量**
>
> 每个 head 有 W_Q, W_K, W_V, W_O，参数量 ≈ 4 · d_model²。head 多了 W_O 维度膨胀（h · d_head → d_model 的投影）。
> 但 h 增加（保持 d_head），总参数基本不变。
>
> **所以"head 无限多"的真正限制是 d_model 不变**。
>
> 如果允许 d_head 固定 64，d_model = h · 64，那 h 越大模型越大——但这是"模型变大"而不是"head 变多"。
>
> **限制 4：表征能力饱和**
>
> 实验显示：
> - h=8 → 性能 X
> - h=16 → 性能 X+0.5
> - h=32 → 性能 X+0.7
> - h=64 → 性能 X+0.7（不再涨）
> - h=128 → 性能 X+0.5（甚至下降）
>
> **边际收益递减 + 退化**——某些 head 学到的模式高度重叠，不再贡献新信息。
>
> Vyas et al. 2019《Pyramidal Stacked Attention》实验：在 SNLI、WMT 上，h>16 后基本无收益。
>
> **限制 5：很多 head 训练后"无用"**
>
> Michel et al. 2019《Are Sixteen Heads Really Better than One?》发现：
> - 很多 head 可以在测试时丢弃而不影响性能
> - 保留 30-40% 的 head 就够
> - 说明冗余严重
>
> 如果 head 之间冗余，增加数量不会带来新能力。
>
> **限制 6：训练难度**
>
> - head 太多，每个 head 数据少，训练不充分
> - 梯度路径复杂，初始化敏感
> - 大模型训练本就不易，head 多了更不稳
>
> **经验法则**
>
> | 模型规模 | 推荐 h |
> |---|---|
> | 小模型（d_model=256-512） | 4-8 |
> | 中模型（d_model=768-1024） | 12-16 |
> | 大模型（d_model=4096-8192） | 32-96 |
>
> 固定 d_head = 64-128，让 h = d_model / d_head。
>
> **实战例子**
>
> - BERT-base：d=768, h=12, d_head=64 ✓
> - GPT-2 small：d=768, h=12, d_head=64 ✓
> - GPT-3 175B：d=12288, h=96, d_head=128 ✓
> - Llama 2 7B：d=4096, h=32, d_head=128 ✓
> - Llama 2 70B：d=8192, h=64（GQA），d_head=128 ✓
>
> **总结**：**head 数量不能无限增加**。**主要限制：d_head 必须保持 32-128**。**h = d_model / d_head**，d_model 固定时 h 有上限。**经验：h=8-96，d_head=64-128**。**h 过多导致 ① 子空间太小 ② 表达饱和 ③ 冗余 head ④ 训练困难**。现代 LLM 在 d_model 增大时 h 同步增加，但 d_head 几乎不变，这是设计上的一致共识。

### [Transformer 为什么采用 Layer Normalization 而不是 Batch Normalization](https://www.mianshiya.com/bank/1906189461556076546/question/1821834696569425922)

> **答案**：
>
> **Transformer 用 LayerNorm 而不是 BatchNorm**
>
> **核心理由：序列长度可变 + 训练 + 推理一致性**。
>
> **LayerNorm vs BatchNorm**
>
> **BatchNorm（批归一化）**
> - 在 **batch 维度**上归一化
> - 对每个特征，统计 batch 内所有样本的均值、方差
> - 主要用于 CV（CNN）
>
> ```
> input: (B, C, H, W)
> 对每个 channel c：mean, var = statistics over (B, H, W)
> 归一化：x_normalized = (x - mean) / sqrt(var + ε)
> 仿射变换：out = γ · x_normalized + β
> ```
>
> **LayerNorm（层归一化）**
> - 在 **特征维度**上归一化
> - 对每个样本，统计其所有特征的均值、方差
> - 主要用于 NLP（Transformer、RNN）
>
> ```
> input: (B, N, d_model)
> 对每个 (batch, position)：mean, var = statistics over (d_model,)
> 归一化：x_normalized = (x - mean) / sqrt(var + ε)
> 仿射变换：out = γ · x_normalized + β
> ```
>
> **为什么 Transformer 用 LayerNorm**
>
> **理由 1：序列长度可变**
>
> - BatchNorm 在 batch 维度归一化，要求 batch 内样本"形状一致"
> - NLP 中序列长度变化大（10 词 vs 1000 词）
> - 用 BatchNorm，长序列的统计会主导，短序列被压扁
> - LayerNorm 在每个位置独立做，与序列长度无关 ✓
>
> **理由 2：batch size 灵活**
>
> - BatchNorm 依赖 batch 统计，batch 太小（如 1、2）统计不稳
> - 训练用 batch=32，推理用 batch=1 → 分布漂移
> - LayerNorm 与 batch 无关 ✓ 训练推理一致
>
> **理由 3：训练 vs 推理一致性**
>
> - BatchNorm 训练用 batch 统计，推理用全局 running statistics → 不一致
> - 推理时若 batch=1，running mean 仍要算 → 复杂
> - LayerNorm 训练推理完全一致 ✓
>
> **理由 4：padding 处理**
>
> - NLP batch 里不同长度用 padding
> - BatchNorm 会被 padding 干扰（padding 是 0，影响统计）
> - LayerNorm 在每个位置独立做，padding 不影响其他位置 ✓
>
> **理由 5：长程依赖**
>
> - BatchNorm 在 batch 内归一化，可能"洗掉"个体样本的特征
> - LayerNorm 保留每个样本的"个性"，更适合需要长程上下文的任务
>
> **LayerNorm 在 Transformer 中的位置**
>
> **Post-LN（原始 Transformer）**
> ```
> output = LayerNorm(x + Sublayer(x))
> ```
> - LN 在残差后
> - 深层训练不稳，需要 warm-up
>
> **Pre-LN（GPT-2、Llama、主流）**
> ```
> output = x + Sublayer(LayerNorm(x))
> ```
> - LN 在子层前
> - 训练稳定，warm-up 可简化
>
> **Sandwich-LN**
> - 两边都有
> - 实验性，少用
>
> **RMSNorm（Root Mean Square Normalization）**
> - LayerNorm 的简化版
> - 不减均值，只用 RMS 归一化
> - 计算更快，效果相当
> - **Llama 2 / Qwen / Mistral 用 RMSNorm**
>
> ```
> RMSNorm(x) = x / sqrt(mean(x²) + ε) · γ
> ```
>
> **BN 在 NLP 的尝试（失败案例）**
>
> - 早期有人试过在 Transformer 用 BN，效果差
> - 不稳定、训练崩溃
> - 现在 NLP 完全不用 BN
>
> **反过来：LN 在 CV 的尝试**
>
> - LayerNorm 在 CNN 上效果不如 BN
> - 但 Vision Transformer（ViT）用 LayerNorm（因为基于 Transformer）
> - CNN 还是 BN 主流（或 GroupNorm、InstanceNorm）
>
> **对比表**
>
> | 维度 | BatchNorm | LayerNorm |
> |---|---|---|
> | 归一化维度 | batch | feature |
> | 主要领域 | CV | NLP |
> | 序列长度 | 需固定 | 可变 ✓ |
> | batch 大小依赖 | 强 | 无 ✓ |
> | 训练推理一致 | 否 | 是 ✓ |
> | padding 影响 | 有 | 无 ✓ |
> | Transformer | 不行 ✓ | 行 ✓ |
>
> **总结**：Transformer 用 **LayerNorm** 是因为：**① 序列长度可变 ② batch 大小无关 ③ 训练推理一致 ④ 不被 padding 干扰**。**现代 LLM 进一步用 RMSNorm 简化**（Llama、Qwen）。BatchNorm 是 CNN 时代的产物，不适合变长序列。理解 LayerNorm 是理解 Transformer 训练稳定性的关键。

### [如果让 K 和 Q 变成同一个矩阵，你觉得对模型性能会带来怎样的影响？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834694635851777)

> **答案**：
>
> **Q 和 K 共享同一矩阵对模型性能的影响**
>
> 让 Q = K（即 W_Q = W_K）会让 attention 矩阵**对称**，对性能有显著负面影响。
>
> **核心影响：表达能力下降**
>
> **1. 不对称关系丢失**
>
> 自然语言中很多关系是**不对称**的：
>
> - **修饰关系**：形容词修饰名词
>   - "红色的" 关注 "苹果"（形容词找名词）
>   - "苹果" 不太关注 "红色的"（中心词不依赖修饰语）
> - **指代关系**：代词找指代对象
>   - "it" 强烈关注 "cat"
>   - "cat" 不关注后面的 "it"
> - **依存关系**：介词-名词、动词-宾语
> - **问答关系**：疑问词找答案
>
> Q=K 强制 `score(i, j) = score(j, i)`，上述所有"单向依赖"都建模不了。
>
> **2. 数值问题：对角线占优**
>
> ```
> score(i, j) = q_i · q_j
> score(i, i) = ||q_i||²
> ```
>
> 通常 `||q_i||²` 远大于 `q_i · q_j`（i≠j）：
> - softmax 在 i=i 上集中
> - 每个 token 主要"关注自己"
> - 信息无法流动 → 等于没做 attention
>
> **3. 训练不稳定**
>
> - 对角线值大 → softmax 梯度小 → 学习慢
> - 容易陷入平凡解（每个 token 只看自己）
>
> **实验证据**
>
> Vaswani et al. 2017 原论文未直接做 Q=K 实验，但后续研究有：
>
> - 《Efficient Transformers》综述：Q、K 共享会在 WMT 翻译上掉 2-4 BLEU。
> - 《Analyzing Multi-Head Self-Attention》：Q=K 头部倾向于"自我循环"。
> - 微软 **DeepNet** 实验：Q、K 共享在深层 Transformer（>12 层）训练崩溃率明显升高。
>
> **特殊场景：Q=K 是合理的**
>
> 虽然标准 Transformer 不应该 Q=K，但有些场景用得到：
>
> **1. 对称关系建模**
> - 图注意力（GAT）：边的关系可能对称
> - 蛋白质相互作用
> - 关系抽取的对称实体
>
> **2. 简化 / 压缩模型**
> - 移动端小模型，省参数
> - Linear Transformer 的某些变体
> - Performer 用 kernel 近似时 Q、K 共享 φ
>
> **3. 数学性质研究**
> - 高斯过程中核函数对称
> - PCA / SVD 形式的 attention
>
> **Q=K 但 W_Q, W_K 各自学**
>
> 注意区分：
> - **Q=K 完全共享**：W_Q = W_K，参数量减半 → 通常不行
> - **Q=X·W_Q, K=X·W_K，W_Q ≠ W_K**：标准 Transformer → 行
> - **Q=K=V**：极端退化 → 完全不行
>
> **实际工程中的"省参数"技巧**
>
> 如果想省 Q/K 的参数，标准做法是：
>
> **1. Multi-Query Attention（MQA, Shazeer 2019）**
> - 多个 head 共享一组 K、V（每个 head 仍独立 Q）
> - KV cache 显存减到 1/h
> - 性能略降，推理大幅加速
>
> **2. Grouped Query Attention（GQA, Llama 2 70B）**
> - 分组共享，介于 MHA 和 MQA 之间
> - 性能更接近 MHA，加速接近 MQA
>
> **3. Multi-Head Latent Attention（MLA, DeepSeek-V2/V3）**
> - 把 K、V 压到低维潜空间再投影
> - 显存大幅降低，性能保持
>
> **这些都比"Q=K"合理得多。**
>
> **总结**
>
> | 设计 | 性能影响 | 备注 |
> |---|---|---|
> | Q、K 独立（标准） | 基线 | 推荐方案 |
> | Q=K（W 共享） | -2~4 BLEU | 不推荐 |
> | Q=K=V | 大幅退化 | 严禁 |
> | MQA / GQA / MLA | -0.5~1 BLEU，省显存 | 推理友好 |
>
> **核心结论**：**Q 和 K 必须用不同投影矩阵**，让模型表达"**不对称、单向、有方向**"的关系。这是 Transformer 表达能力的基础。性能优化的方向是 **MQA / GQA / MLA**（共享 K、V 跨 head），而不是 **Q=K**。

### [聊一聊 Transformer 的架构和基本原理。](https://www.mianshiya.com/bank/1906189461556076546/question/1821834692723249153)

> **答案**：
>
> **Transformer 架构与基本原理**
>
> Transformer 是 2017 年 Google《Attention is All You Need》提出的**完全基于注意力机制**的序列建模架构，是现代大模型（GPT、BERT、LLaMA、Claude）的基石。
>
> **整体结构（Encoder-Decoder）**
>
> 原始 Transformer 用于机器翻译，包含两部分：
>
> ```
> 输入序列 → [Embedding + Positional Encoding]
>          → Encoder × 6（自注意力 + FFN）
>          → Context
>
> 输出序列（shifted right）→ [Embedding + Positional Encoding]
>                           → Decoder × 6（masked 自注意力 + cross 注意力 + FFN）
>                           → Linear + Softmax → 输出概率
> ```
>
> **核心组件**
>
> **1. Multi-Head Self-Attention（多头自注意力）**
>
> 让序列中每个位置都能"看到"其他所有位置，并加权聚合：
>
> ```
> Attention(Q, K, V) = softmax(QK^T / √d_k) · V
>
> Q = X · W_Q
> K = X · W_K
> V = X · W_V
> ```
>
> - Q（Query）：当前位置"问什么"
> - K（Key）：其他位置"有什么标签"
> - V（Value）：其他位置"提供的信息"
> - 点积 → 相似度 → softmax 权重 → 加权 V
>
> Multi-Head = 并行做 h 次（h=8），每个 head 学不同的子空间，最后 concat 后投影。
>
> **2. Positional Encoding（位置编码）**
>
> Self-attention 没有"顺序"概念（置换不变），所以要给每个位置加一个位置向量：
>
> - **Sinusoidal**：`PE(pos, 2i) = sin(pos / 10000^(2i/d))`，`PE(pos, 2i+1) = cos(...)`
> - **Learned**：每个位置一个可学向量（BERT 用这个）
> - **RoPE（旋转位置编码）**：Llama / Qwen 用，对相对位置敏感，支持长上下文
> - **ALiBi**：在 attention 分数上加位置偏置
>
> **3. Feed-Forward Network（FFN）**
>
> 每个位置独立过一个 2 层 MLP：
> ```
> FFN(x) = max(0, x·W_1 + b_1) · W_2 + b_2
> ```
> 中间维度通常是 4×d_model（如 d_model=512，FFN=2048）。
> 现代变体：SwiGLU（Llama，门控线性单元）。
>
> **4. Residual + LayerNorm**
>
> 每个子层都包：
> ```
> output = LayerNorm(x + Sublayer(x))
> ```
> - 残差：缓解梯度消失，让深层可训
> - LayerNorm（不是 BatchNorm）：对每个样本归一化，适合变长序列
>
> **5. Masking**
>
> - **Padding mask**：忽略 padding 位置
> - **Causal mask（look-ahead mask）**：Decoder 中防止看到未来 token（三角矩阵）
>
> **为什么 Transformer 颠覆了 RNN**
>
> 1. **并行**：RNN 必须逐步算，Transformer 一次性算所有位置 → 训练快 N 倍
> 2. **长程依赖**：RNN 距离衰减，Transformer 任意两位置直接交互
> 3. **可扩展性**：参数和数据增加，性能可预测地提升（Scaling Law）
>
> **Encoder-only vs Decoder-only vs Encoder-Decoder**
>
> | 架构 | 代表 | 任务 |
> |---|---|---|
> | Encoder-only | BERT | 理解类（分类、NER、QA） |
> | Decoder-only | GPT / LLaMA | 生成类（对话、续写） |
> | Encoder-Decoder | T5 / BART | 序列到序列（翻译、摘要） |
>
> **GPT 系列是 Decoder-only**：把原始 Transformer 的 Decoder 拿出来，去掉 cross-attention，只保留 masked self-attention + FFN，自回归生成下一个 token。
>
> **现代 LLM 的改进**
>
> - **Pre-LayerNorm / RMSNorm**：稳定训练
> - **RoPE**：长上下文
> - **SwiGLU FFN**：性能更强
> - **Grouped Query Attention / Multi-Query Attention**：减少 KV cache 显存
> - **Sparse Attention / Sliding Window**：长序列
>
> **总结**：Transformer = **Self-Attention + FFN + 残差 + LayerNorm + 位置编码**。**核心创新：完全并行 + 长程依赖 + 可扩展**。是深度学习过去十年最重要的架构突破，开启了 LLM 时代。理解 Transformer 是理解所有现代 LLM 的前提。

### [使用 Transformer 解决了 RNN 面临的一些什么问题？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834692991684610)

> **答案**：
>
> **Transformer 解决了 RNN 的哪些问题**
>
> RNN（含 LSTM / GRU）曾是序列建模的主流，但有几个根本性问题。Transformer 的设计就是为了解决这些痛点。
>
> **问题 1：无法并行训练**
>
> **RNN 的痛**：
> - 第 t 步依赖第 t-1 步的 hidden state：`h_t = f(h_{t-1}, x_t)`
> - 必须按时间步串行计算。
> - GPU 并行能力用不上。
>
> **Transformer 的解**：
> - Self-attention：所有位置同时计算 `softmax(QK^T)V`。
> - 整个序列一次性 forward。
> - 训练速度提升 N 倍（N=序列长度）。
>
> **影响**：
> - 用同等算力训练更大模型。
> - 训练时间从几周变几小时。
> - 让 LLM 的 Scaling Law 成为可能。
>
> **问题 2：长程依赖弱**
>
> **RNN 的痛**：
> - 即使有 LSTM / GRU 的门控，长序列（>200 步）信息仍会衰减。
> - " gradient vanishing through time"。
> - 第 1 个词的影响传到第 200 个词已经很微弱。
>
> **Transformer 的解**：
> - Self-attention：任意两个位置 O(1) 直接交互。
> - 距离无关。
> - 长程依赖自然捕捉。
>
> **实验证据**：
> - LSTM 处理 >512 长度时性能掉得明显。
> - Transformer 在 4K-32K 上下文仍能稳定工作（RoPE、长上下文训练后能到 1M）。
>
> **问题 3：信息瓶颈（bottleneck）**
>
> **RNN 的痛**：
> - Encoder-Decoder RNN（如 seq2seq）把整个输入压缩成一个固定长度的 context vector。
> - 长输入信息必然丢失。
>
> **Transformer 的解**：
> - Encoder 输出是 N 个 vector（每个 token 一个）。
> - Decoder 在每一步通过 cross-attention "查阅"所有 Encoder 输出。
> - 没有"压缩"步骤。
>
> **问题 4：梯度消失 / 爆炸**
>
> **RNN 的痛**：
> - BPTT（backprop through time）让梯度连乘 N 次。
> - 即使有残差门控，深层 RNN 仍难训。
>
> **Transformer 的解**：
> - 残差连接：`x + Sublayer(x)`
> - LayerNorm：每层归一化
> - 路径短：任意两位置距离 O(1)
> - 深层稳定可训
>
> **问题 5：位置感知弱**
>
> **RNN 的痛**：
> - RNN 隐式按时间顺序处理，但 "顺序"信息其实藏在 hidden state 里，难以显式利用。
>
> **Transformer 的解**：
> - 显式位置编码（Sinusoidal / RoPE / ALiBi）
> - 模型可以学到"位置 i 与位置 j"的关系
> - 位置信息可控、可解释
>
> **问题 6：上下文长度受限**
>
> **RNN 的痛**：
> - 实际工程中 LSTM 处理 >500 长度就吃力。
> - 长序列训练慢、效果差。
>
> **Transformer 的解**：
> - 默认就能处理几 K。
> - 配合 KV cache、Sliding Window、Sparse Attention，能到 100K-1M（Gemini 1.5、Claude 200K、GPT-4 Turbo 128K）。
>
> **Transformer 的代价（不是免费的）**
>
> 虽然 Transformer 解决了 RNN 的痛点，但也带来新问题：
>
> 1. **O(N²) 复杂度**：attention 矩阵大小是 N²。
> 2. **位置编码处理超长上下文有外推问题**。
> 3. **推理时 KV cache 显存大**。
> 4. **小数据集容易过拟合**（参数多）。
>
> **与现代架构对比**
>
> | 维度 | RNN/LSTM | Transformer |
> |---|---|---|
> | 并行 | ✗ 串行 | ✓ 全并行 |
> | 长程依赖 | 弱 | 强 |
> | 训练速度 | 慢 | 快 |
> | 推理速度（每 token） | O(1) | O(N) KV cache |
> | 上下文 | <500 | 4K-1M |
> | 可扩展性 | 差 | 极好 |
>
> 注意：**推理阶段** RNN 反而快（O(1) hidden state），Transformer 慢（要算 attention over KV cache）。这也是为什么有 Mamba、RWKV 等"线性 RNN"复兴——想兼顾训练并行和推理高效。
>
> **总结**：Transformer 解决了 RNN 的**并行训练、长程依赖、信息瓶颈、梯度消失**四大痛点，让 LLM 时代成为可能。代价是 **O(N²) attention 复杂度**。现代研究（FlashAttention、Sparse Attention、Linear Attention、Mamba）都在尝试进一步解决 Transformer 自身的新问题。

### [了解 ViLT（Vision-and-Language Transformer） 吗？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834699778068481)

> **答案**：
>
> **ViLT（Vision-and-Language Transformer）**
>
> ViLT（Kim et al. 2021）= **去掉视觉 backbone，把图像和文本喂入同一个 Transformer 的多模态模型**。论文《ViLT: Vision-and-Language Transformer Without Convolution or Region Supervision》。
>
> **核心理念**
>
> 之前的 vision-language（VL）模型层级：
> 1. **模态特定编码器**（Region-based / CNN-based）—— 模型重
> 2. **跨模态融合 Transformer** —— 才是真正的"理解"
>
> ViLT 大胆假设：
> > 既然 Transformer 这么强，**为什么还需要 visual backbone？** 直接把图像 patch 喂给 Transformer 不就行了？
>
> **视觉特征的演进**
>
> | 阶段 | 视觉表示 | 代表 | 参数 |
> |---|---|---|---|
> | 1 | Region features（Faster R-CNN） | VisualBERT, LXMERT | ~250M |
> | 2 | CNN features（ResNet） |UNITER, Oscar | ~200M |
> | 3 | **Linear Patch Projection（无 backbone）** | **ViLT** | **~87M** |
> | 4 | ViT features | BLIP-2, LLaVA | ~100M+ |
>
> ViLT 是第 3 阶段代表——**最激进的简化**。
>
> **架构**
>
> ```
> 图像 (H, W, C)
>     ↓ 切 patch (P×P)
>     ↓ 线性投影到 d_model（无 CNN）
>     → 图像 token 序列：[I_1, I_2, ..., I_N]
>
> 文本 "A cat on sofa"
>     ↓ WordPiece 分词
>     ↓ Token Embedding
>     → 文本 token 序列：[T_1, T_2, ..., T_M]
>
> 拼接：
> [CLS] [IMG] I_1 ... I_N [TEXT] T_1 ... T_M [SEP]
> +
> 位置编码 + 模态类型 embedding
>
> → Transformer Encoder × L（12 层，标准）
>
> → [CLS] 输出 → 任务头
> ```
>
> **3 个关键 Embedding**
>
> 每个 token 加：
> 1. **Token Embedding**：内容
> 2. **Positional Embedding**：位置（learned 1D）
> 3. **Modality Type Embedding**：图像 or 文本
> 4. **Segment Embedding**：可选（同 BERT）
>
> **预训练任务**
>
> **1. Image-Text Matching（ITM）**
> - 输入：(image, text)，输出：匹配 / 不匹配
> - 负采样：hard negative（图相似但文本不匹配）
> - Loss：BCE
>
> **2. Masked Language Modeling（MLM）**
> - Mask 15% 文本 token
> - 用图像 + 上下文预测
> - Loss：CE
>
> **3. Patch Masking（PM，可选）**
> - 类似 MAE，mask 部分 patch
> - 用文本 + 上下文 patch 重建
>
> **下游任务（fine-tune）**
>
> - **VQA**：[CLS] + MLP → 答案分类
> - **Visual Commonsense Reasoning（VCR）**
> - **COCO/Flickr Image-Text Retrieval**：用 [CLS] 做 retrieval
> - **NLVR2**（视觉推理）：双图 + 文本 → 二分类
>
> **性能表现**
>
> | 任务 | ViLT | SOTA（含 visual backbone） |
> |---|---|---|
> | VQA | 70.4 | 76+（但参数大 3 倍） |
> | COCO Retrieval (R@1) | 61.5 | 75+（但参数大 3 倍） |
> | NLVR2 | 76.2 | 88+（但参数大） |
>
> **结论**：**参数效率极高，绝对性能略低**
>
> **ViLT 的优势**
>
> 1. **架构极简**：单一 Transformer，无 visual backbone
> 2. **参数少 70%**：~87M vs ~250M
> 3. **训练快**：省掉 Faster R-CNN 推理
> 4. **可扩展**：奠基后续 vision-language 模型
>
> **ViLT 的局限**
>
> 1. **绝对性能上限低**：没有视觉特征提取能力
> 2. **数据需求大**：需要海量预训练数据
> 3. **下游微调敏感**：小数据容易过拟合
> 4. **被后续模型超越**：BLIP-2、LLaVA 用 ViT + LLM 更强
>
> **对后续模型的影响**
>
> ViLT 验证了"**Transformer 可处理一切模态**"的可能性：
> - **BLIP / BLIP-2**：ViT + Q-Former + LLM
> - **Flamingo**：ViT + Perceiver + LLM，few-shot 多模态
> - **LLaVA**：ViT + 简单投影层 + LLM
> - **GPT-4V / Gemini**：原生多模态大模型
>
> 现代多模态架构**回归了 visual backbone**（用 ViT），但 ViLT 的"统一 Transformer"思想保留了下来——只是 ViT 成为了新的"backbone"。
>
> **总结**：ViLT = **去掉 CNN/RoI backbone，把图像 patch 直接喂 Transformer 与文本统一处理**。**贡献：① 极简架构 ② 参数效率高 ③ 验证 Transformer 跨模态能力**。**预训练：ITM + MLM**。**意义：奠基现代 vision-language 模型（BLIP、LLaVA、Flamingo）的设计思路**。**局限：绝对性能被 ViT + LLM 超越**。理解 ViLT 是理解多模态 Transformer 的关键——是"Transformer 一统模态"思潮的代表作。

### [Transformer 和 LLM 有哪些区别](https://www.mianshiya.com/bank/1906189461556076546/question/1821834699207643137)

> **答案**：
>
> **Transformer 和 LLM 的区别**
>
> Transformer 是**架构**，LLM 是**模型**。一个是"图纸"，一个是"用图纸建的大楼"。
>
> **定义对比**
>
> **Transformer**
> - 2017 年 Google 提出的**神经网络架构**
> - 核心组件：self-attention + FFN + 残差 + LayerNorm
> - 是"架构图"，不是具体模型
>
> **LLM（Large Language Model，大语言模型）**
> - 基于 Transformer 架构（绝大部分）的**具体大模型**
> - 参数规模：百亿 - 万亿
> - 训练数据：TB - PB 级
> - 典型代表：GPT、Llama、Claude、Gemini、Qwen
>
> **类比**
>
> - **Transformer**：建筑图纸、配方表、计算机架构
> - **LLM**：用图纸建的大楼、按配方做的菜、具体的 CPU 实现
>
> **Transformer 的"家族"**
>
> | 模型类型 | 代表 | 任务 |
> |---|---|---|
> | Encoder-only | BERT、RoBERTa、DeBERTa | 理解类 |
> | Decoder-only | **GPT、Llama、Claude** | 生成类（LLM 主流） |
> | Encoder-Decoder | T5、BART | seq2seq |
> | Multi-modal | ViT、CLIP | 视觉、多模态 |
> | Speech | Whisper、wav2vec | 语音 |
>
> → LLM 是 Transformer 家族中的一个**子集**（多为 Decoder-only + 文本）。
>
> **LLM 的"非 Transformer 部分"**
>
> LLM 不只是 Transformer 架构，还包含大量工程：
>
> **1. 规模（Scale）**
> - 参数量：7B、70B、405B
> - 训练数据：TB 级
> - 训练算力：千卡 / 月级
>
> **2. 训练流程**
> - **预训练**：海量无监督文本
> - **SFT**（监督微调）：指令数据
> - **RLHF / DPO**：人类偏好对齐
> - **Constitutional AI**（Claude）
>
> **3. Tokenizer**
> - BPE、Unigram
> - tiktoken、sentencepiece
> - 词表：32K - 256K
>
> **4. 位置编码改进**
> - RoPE、ALiBi
> - YaRN、LongRoPE（长上下文）
>
> **5. 架构优化**
> - SwiGLU FFN
> - RMSNorm
> - GQA / MQA / MLA
> - Sliding Window（Mistral）
>
> **6. 训练工程**
> - 3D Parallelism（DP + TP + PP）
> - ZeRO / FSDP
> - FlashAttention
> - bf16 / fp8
>
> **7. 推理优化**
> - KV Cache
> - PagedAttention（vLLM）
> - Speculative Decoding
> - Quantization（int8、int4）
>
> **8. 工具与生态**
> - LangChain、LlamaIndex
> - Hugging Face Transformers
> - OpenAI API、Anthropic API
> - Vector DB（Pinecone、Milvus）
>
> **核心区别总结**
>
> | 维度 | Transformer | LLM |
> |---|---|---|
> | 性质 | 架构 / 论文 | 具体模型 |
> | 提出时间 | 2017 | 2018（GPT-1）/ 2020+（GPT-3） |
> | 规模 | 几千万 - 几亿参数 | 几十亿 - 万亿参数 |
> | 训练数据 | 百万 - 千万 token | 万亿 token |
> | 训练目标 | 任务特定（翻译等） | 通用（next token） |
> | 能力 | 单一任务 | 通用（涌现能力） |
> | 训练流程 | 端到端训练 | 预训练 + SFT + RLHF |
> | 部署 | 实验室 / 学术 | 产品级（API、应用） |
> | 训练成本 | 千 - 万美元 | 百万 - 亿万美元 |
> | 应用 | 翻译、特定任务 | 对话、推理、代码、多模态 |
>
> **Transformer 时代 → LLM 时代**
>
> **2017-2020：Transformer 时代**
> - Transformer 替代 RNN
> - BERT、GPT-2、T5 各显神通
> - 任务专用模型（每个任务一个）
>
> **2020+：LLM 时代（GPT-3 起）**
> - Scaling Law：模型越大越强
> - 涌现能力：少样本学习、推理、指令跟随
> - 通用模型替代专用模型
> - ChatGPT 引爆产业
>
> **非 Transformer 的 LLM（少数）**
>
> - **Mamba**（2023）：状态空间模型（SSM）
> - **RWKV**：线性 RNN
> - **Hyena**：长卷积
> - 这些架构挑战 Transformer，但仍非主流
>
> **Transformer 是 LLM 的"必要条件"吗？**
>
> 不是。理论上 LLM 可以用任何序列建模架构（RNN、SSM、CNN）。
> 但实际上：
> - Transformer 是目前唯一**可扩展到千亿参数**且**性能优越**的架构
> - 替代架构（Mamba、RWKV）在小规模上接近，但还没在 LLM 规模上验证
> - 所以 **LLM ≈ Large Transformer-based Model**
>
> **总结**：
> - **Transformer 是架构**（2017），**LLM 是基于该架构的大模型**（2020+）
> - Transformer 提供了**可扩展性**，让 Scaling Law 成立
> - LLM 在 Transformer 之上加了：**规模化 + 多阶段训练 + 对齐 + 工程优化 + 生态**
> - 现代 LLM 几乎都基于 Decoder-only Transformer（+ RoPE + SwiGLU + RMSNorm + GQA）
> - 非Transformer LLM（Mamba、RWKV）是新方向，但仍非主流
>
> **一句话总结**：Transformer 是"**how**"（架构），LLM 是"**what**"（产品）。Transformer 让 LLM 成为可能，LLM 让 Transformer 价值最大化。

### [什么是自回归属性（autoregressive property）？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834697093713922)

> **答案**：
>
> **自回归属性（Autoregressive Property）**
>
> Autoregressive = **模型在生成时，每次预测下一个 token，且依赖之前已生成的所有 token**。
>
> **定义**
>
> 给定一个序列 `x_1, x_2, ..., x_T`，自回归模型分解联合概率：
>
> ```
> P(x_1, x_2, ..., x_T) = Π_{t=1}^{T} P(x_t | x_1, x_2, ..., x_{t-1})
> ```
>
> 每个 token 的概率只依赖**历史 token**，与未来无关。
>
> **Transformer 中的实现**
>
> GPT（Decoder-only）就是自回归模型：
> ```
> 输入：<s> I love NLP
> 预测：  I love NLP </s>
>                 ↑
>         每个位置预测下一个 token
> ```
>
> 训练时：
> - 完整序列一次性 forward（causal mask 保证不偷看未来）
> - 每个位置预测下一个 token
> - Loss = 平均 cross-entropy
>
> 推理时（生成）：
> ```
> 输入: <s>
> → 模型预测下一个 token（如 "I"）
> → 新输入: <s> I
> → 模型预测下一个（如 "love"）
> → 新输入: <s> I love
> → ...
> → 直到 </s>
> ```
>
> **自回归 vs 一次性生成**
>
> **自回归（GPT、Llama、Claude）**
> - 每次生成 1 个 token
> - 慢（N 次推理）
> - 质量高、灵活
> - 适合开放生成
>
> **非自回归（NAT，机器翻译研究过）**
> - 一次预测所有 token
> - 快（1 次推理）
> - 质量较低
> - 适合并行解码（GLAT、CTC）
>
> **Mask-Predict（BERT 式生成）**
> - 输入多个 [MASK]，迭代填充
> - 介于自回归和 NAT 之间
> - 如 Mask-Predict NAT
>
> LLM 主流仍是自回归。
>
> **自回归的优势**
>
> 1. **训练简单**：next token prediction 是统一目标
> 2. **生成灵活**：任意长度
> 3. **可扩展**：Scaling Law 友好
> 4. **数据高效**：无监督，海量文本都可用
>
> **自回归的代价**
>
> 1. **推理慢**：生成 N 个 token 要 N 次 forward
> 2. **错误累积**：早期 token 错了，后面跟着错
> 3. **无法逆向**：生成后无法修改前面的 token
>
> **KV Cache：加速自回归**
>
> 朴素推理：每生成一个 token，要重新计算所有历史 token 的 K、V。
> 优化：**KV Cache**——把历史的 K、V 缓存，下次只算新 token 的 K、V。
>
> ```
> 生成 token 5：
> 旧方法：Q_5 + (K_1, K_2, K_3, K_4, K_5) 重新算
> KV Cache：Q_5 + (cached K_1..K_4, new K_5)
> ```
>
> 复杂度从 O(N²) → O(N) per token。
>
> **自回归在 LLM 中的体现**
>
> - **GPT 系列**：标准自回归
> - **Llama**：自回归 + RoPE + SwiGLU
> - **Claude**：自回归（Anthropic 没透露细节）
> - **Gemini**：自回归（多模态）
> - **DeepSeek、Qwen、Mistral**：自回归
>
> **非自回归结构**
>
> - **Diffusion Model**：去噪过程，不是 token-by-token
> - **Masked Language Model（BERT）**：随机 mask，双向预测，不适合生成
> - **MAE（图像）**：随机 mask patch，重建
>
> **自回归与 Causal Mask**
>
> Decoder-only Transformer 之所以能自回归，关键在 **causal mask**：
> - 训练时：mask 未来 token，模拟"生成时只能看到过去"
> - 推理时：左到右生成，自然因果
>
> **自回归的"涌现"能力**
>
> 当模型规模足够大，自回归训练会涌现：
> - **In-context learning**：Few-shot 学习
> - **Chain-of-thought**：推理能力
> - **Instruction following**：指令跟随
>
> 这些能力都来自"预测下一个 token"这一简单目标 + 足够大的模型 + 足够多数据。
>
> **自回归的局限：长度限制**
>
> - 上下文窗口有限（4K、32K、200K、1M）
> - 超出窗口的内容看不到
> - 解决：KV Cache 压缩、LongRoPE、Recall、RAG
>
> **总结**：**自回归 = 模型一次预测一个 token，依赖所有历史 token**。**公式：P(seq) = Π P(x_t | x_<t)**。**实现：Decoder-only Transformer + causal mask**。**优势：训练简单、生成灵活、Scaling Law**。**代价：推理慢、错误累积**。**KV Cache 是加速核心**。所有现代 LLM（GPT、Llama、Claude）都是自回归模型。理解自回归是理解 LLM 生成机制的关键。

### [Transformer 模型的性能瓶颈在哪？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834698599469057)

> **答案**：
>
> **Transformer 模型的性能瓶颈**
>
> Transformer 在效果上是 SOTA，但工程上有几个**核心瓶颈**。
>
> **瓶颈 1：O(N²) Attention 复杂度**
>
> ```
> attention 计算量 ∝ N² （N = 序列长度）
> KV cache 大小 ∝ N
> ```
>
> - 短序列（<4K）：问题不大
> - 中等序列（4K-32K）：明显
> - 长序列（128K-1M）：训练和推理都极贵
>
> **解决方向**：
> - Sparse Attention（Longformer、BigBird）
> - Linear Attention（Performer、Linformer）
> - FlashAttention（IO-aware，不改公式）
> - Sliding Window + Global Token（Mistral）
>
> **瓶颈 2：推理时 KV Cache 增长**
>
> 每生成 1 个 token，KV Cache + 1 项，attention 矩阵 +1 维。
>
> - 上下文越长，生成越慢
> - KV Cache 比模型本身还大（长上下文场景）
>
> **解决**：
> - GQA / MQA / MLA：减少 KV cache
> - PagedAttention：内存高效
> - 量化（fp8、int4）
> - Sliding Window
>
> **瓶颈 3：自回归生成慢**
>
> ```
> 生成长度 N → 需要 N 次 forward
> 每次只能算 1 个 token（不能并行）
> ```
>
> 延迟：N × T_single_token。GPT-4 类模型生成 1000 token 要 10-30 秒。
>
> **解决**：
> - Speculative Decoding（推测解码）：小模型先草拟，大模型验证
> - Medusa：多头并行预测
> - **新增推理模型**（o1、DeepSeek-R1）的"思考"更慢——但这是 trade-off
>
> **瓶颈 4：内存带宽瓶颈（推理）**
>
> LLM 推理是 **memory-bound**：
> - 每 token 加载全部模型权重一次
> - 7B fp16 = 14 GB，从 HBM 读一遍 = 几 ms
> - 算力（FLOPS）远没用满
>
> **解决**：
> - **Batching**：多个请求合并，复用权重加载
> - **Quantization**：减半/四分之一权重
> - **Speculative Decoding**：算力换带宽
>
> **瓶颈 5：训练显存爆炸**
>
> 7B 模型训练需 ~100 GB 显存（bf16 + Adam + activations）。
>
> **解决**：
> - **3D Parallelism**（数据 + 张量 + 流水线并行）
> - **ZeRO**（参数 / 梯度 / 优化器分片）
> - **Activation Recomputation**
> - **Mixed Precision**
>
> **瓶颈 6：数据 / 标注成本**
>
> - 大模型需要 TB 级数据
> - 高质量数据稀缺
> - SFT / RLHF 需要人工标注
>
> **解决**：
> - Synthetic Data（用强模型生成训练数据）
> - DPO / RLHF 替代部分 SFT
> - Self-improvement（STaR、Self-Rewarding）
>
> **瓶颈 7：训练成本**
>
> - GPT-4 训练 ~$100M
> - Llama 3 405B：~$700M
> - 一次大模型训练 = 数百 GPU 月
>
> **解决**：
> - 更高效的架构（Mamba、Hyena）
> - 更高效的训练（如 Llama 3 用 16K GPU）
> - 蒸馏（大模型 → 小模型）
>
> **瓶颈 8：能耗**
>
> - 训练 GPT-3 耗电 ~190 MWh（约 17 个美国家庭一年用电）
> - 推理总能耗（ChatGPT 用户每天查询）巨大
> - 环境影响显著
>
> **解决**：
> - 高效硬件（TPU、专用 ASIC）
> - 模型压缩（量化、剪枝、蒸馏）
> - 推理优化（batching、KV cache 复用）
>
> **瓶颈 9：长上下文的外推问题**
>
> 位置编码（特别是 RoPE）在超长上下文时不稳定。
>
> **解决**：
> - Position Interpolation
> - NTK-aware RoPE
> - YaRN
> - LongRoPE
>
> **瓶颈 10：多模态扩展**
>
> 把 Transformer 用于图像、视频、音频，扩展性挑战：
> - 序列长度爆炸（图片 1024 patch，视频 16 × N）
> - 不同模态信息密度不同
>
> **解决**：
> - ViT、Swin Transformer（空间）
> - TimeSformer、VideoSwin（时空）
> - 跨模态对齐（CLIP、Flamingo）
>
> **瓶颈 11：模型架构的"天花板"**
>
> - Decoder-only Transformer 是否还能 scale？
> - 替代架构：Mamba、RWKV、Hyena（线性 RNN）
> - 超大上下文：状态空间模型可能更优
>
> **总结**：Transformer 的核心瓶颈：**① O(N²) attention ② KV Cache 增长 ③ 自回归生成慢 ④ 内存带宽 ⑤ 训练显存 / 成本 ⑥ 数据 ⑦ 能耗 ⑧ 长上下文 ⑨ 多模态**。**最关键的两点：O(N²) attention（计算）+ Memory-bound 推理（带宽）**。当前研究热点（FlashAttention、Speculative Decoding、GQA、Quantization、Mamba）都在解决这些瓶颈。

### [Transformer 模型训练完成后，如何评估其性能和效果？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834698230370305)

> **答案**：
>
> **Transformer 模型评估：性能与效果**
>
> 评估 Transformer 模型要看四个层面：**任务效果、系统性能、安全鲁棒、业务价值**。
>
> **1. 任务效果（Effectiveness）**
>
> **通用 NLP 基准**
> - **GLUE / SuperGLUE**：经典 NLP 任务套件
> - **MMLU**：57 学科多选题（知识广度）
> - **BBH**（BIG-Bench Hard）：复杂推理
> - **GSM8K**：小学数学
> - **MATH**：竞赛数学
> - **HumanEval / MBPP**：代码生成
> - **HellaSwag**：常识推理
> - **ARC**：科学问答
>
> **生成 / 对话评估**
> - **MT-Bench**：多轮对话（GPT-4 评分）
> - **AlpacaEval**：指令跟随
> - **LMSYS Chatbot Arena**：人类盲测对战
> - **LLM-as-Judge**：用强模型评分
>
> **多语言**
> - **MGSM**：多语言数学
> - **XCOPA、XNLI**：跨语言推理
>
> **领域评测**
> - **MedQA、MedMCQA**：医疗
> - **LegalBench**：法律
> - **FinBench**：金融
>
> **2. 系统性能（Performance）**
>
> **延迟**
> - **TTFT**（Time To First Token）：首 token 延迟
> - **TPOT**（Time Per Output Token）：生成每 token 时间
> - **End-to-end latency**：总响应时间
>
> **吞吐**
> - **Tokens/sec**：每秒生成 token 数
> - **Concurrent requests**：最大并发
> - **QPS**（Queries per second）
>
> **资源占用**
> - **显存（VRAM）**：模型 + KV Cache
> - **CPU、内存**
> - **磁盘 / 网络 IO**
>
> **成本**
> - **$/1M tokens**：单价
> - **训练成本**（GPU 小时）
> - **推理成本**（生产部署）
>
> **3. 安全与鲁棒性（Safety）**
>
> **对抗鲁棒**
> - **AdvGLUE**：对抗样本
> - **PromptBench**：Prompt 攻击
>
> **毒性 / 偏见**
> - **RealToxicityPrompts**：毒性
> - **BBQ**：社会偏见
> - **CrowS-Pairs**：刻板印象
>
> **越狱 / 注入**
> - **Red-teaming**：人工攻击
> - **JailbreakBench**：自动化越狱测试
> - **Prompt Injection benchmarks**
>
> **事实性**
> - **TruthfulQA**：避免幻觉
> - **FactScore**：事实准确度
> - **Hallucination Rate**
>
> **4. 业务指标（Business）**
>
> - **用户满意度**（thumbs up/down）
> - **任务完成率**（如客服解决率）
> - **转化率**（如营销文案 CTR）
> - **留存率**
> - **人工接管率**（AI 不行时转人工）
>
> **评估方法**
>
> **1. 自动评估（Automated）**
> - 评测集 + 标准答案
> - 精确匹配、BLEU、ROUGE
> - LLM-as-Judge
> - 代码：执行单元测试
>
> **2. 人工评估（Human Eval）**
> - 标注员打分（相关性、流畅性、有用性）
> - A/B 测试
> - Pairwise comparison
>
> **3. 在线评估（Online）**
> - 灰度发布
> - 用户隐式反馈（重试、复制、停留）
> - 业务指标
>
> **评估的常见坑**
>
> **坑 1：评测集污染**
> - 训练数据包含评测集
> - 分数虚高
> - 解决：动态评测集、新数据
>
> **坑 2：单一指标过拟合**
> - 只刷 MMLU，其他能力没提升
> - 解决：多维度评测
>
> **坑 3：自动评估不准**
> - BLEU、ROUGE 与人类判断相关性弱
> - LLM-as-Judge 有 bias（偏好长答案）
> - 解决：人工抽样校验
>
> **坑 4：离线 ≠ 在线**
> - 评测集分数高，线上体验差
> - 解决：在线 A/B 真实用户
>
> **坑 5：平均分掩盖长尾**
> - 平均分好，但极端 case 灾难
> - 解决：分群评测、worst-case
>
> **评估工具**
>
> - **lm-eval-harness**（EleutherAI）：开源评测框架
> - **OpenCompass**（上海 AI Lab）
> - **HELM**（Stanford）
> - **BIG-bench**（Google）
> - **LangSmith / Langfuse**：生产应用监控
> - **RAGAS**：RAG 评测
>
> **生产评估流程**
>
> 1. **离线评测集**：每次模型/prompt 变更
> 2. **影子流量**：新模型跑线上流量但不返用户
> 3. **A/B 灰度**：5% → 50% → 100%
> 4. **持续监控**：业务指标、用户反馈、Bad case
> 5. **定期重训**：分布漂移
>
> **总结**：评估 Transformer / LLM 要从 **任务效果（MMLU/BBH/MT-Bench）+ 系统性能（延迟/吞吐/显存）+ 安全（越狱/幻觉/毒性）+ 业务（满意度/转化）** 四维度。**方法：自动评测 + 人工评测 + 在线 A/B**。**坑：污染、单维度、自动不准、离线在线不匹配**。**工具：lm-eval-harness / OpenCompass / LangSmith**。生产 LLM 必须有完整的评测体系——不评测 = 不改进。

### [Transformer 的哪个部分最占用显存？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834693230759937)

> **答案**：
>
> **Transformer 哪部分最占显存？**
>
> **最占显存的两部分**：
>
> 1. **训练阶段**：模型参数 + 梯度 + 优化器状态（特别是 Adam）
> 2. **推理阶段**：KV Cache（特别是长上下文）
>
> **训练阶段显存分布**
>
> 以 7B 模型、bf16 训练为例：
>
> | 部分 | 占用 | 备注 |
> |---|---|---|
> | 模型参数 | 14 GB | 7B × 2 bytes |
> | 梯度 | 14 GB | 同参数量 |
> | Adam 状态（m, v） | 56 GB | 2 × fp32 × 7B |
> | Activations | 30-100 GB | 与 batch、seq_len、深度相关 |
> | **总计** | **~100-200 GB** | 需要 8×A100 |
>
> **最占的：Activations + 优化器状态**
>
> **Activations 显存分析**
>
> 每个 Transformer 层的 activations：
> ```
> input:        B × N × d
> Q, K, V:      B × N × d （×3）
> attention:    B × h × N × N  ← 大头
> FFN hidden:   B × N × 4d
> ```
>
> 注意力矩阵 `B × h × N × N` 是大头：
> - B=8, h=32, N=4096 → 8 × 32 × 4096² × 2 bytes = **8 GB / 层**
> - 32 层 → **256 GB**（不可能装下）
>
> **解决：Gradient Checkpointing / Activation Recomputation**
> - 不保存 activations，反向传播时重算
> - 节省 10-30x 显存
> - 代价：30% 训练慢
>
> **优化器状态（Adam）**
>
> ```
> m = β₁ 的累积 → fp32, 同参数量
> v = β₂ 的累积 → fp32, 同参数量
>
> 总共 = 2 × 4 bytes × N_params
> ```
>
> 7B 模型：8 × 7B = **56 GB**
>
> **解决办法**：
> - **ZeRO-1**：优化器状态分片到多卡
> - **8-bit Adam**：m, v 用 int8
> - **Adafactor**：不存 m, v 的完整矩阵
> - **LoRA**：只训小 adapter，冻结主干
>
> **推理阶段：KV Cache**
>
> 自回归生成时，缓存历史 K、V：
>
> ```
> KV cache 大小 = 2 × N_layers × N × d_model × batch × dtype_size
>
> 例：Llama 7B, N=4096, 32 层, d_model=4096, bf16
> KV cache = 2 × 32 × 4096 × 4096 × 2 = **4 GB / sequence**
> ```
>
> | 上下文 | KV Cache / 单条 |
> |---|---|
> | 4K | 4 GB |
> | 32K | 32 GB |
> | 128K | 128 GB |
> | 1M | 1 TB |
>
> **长上下文瓶颈：KV Cache 比 model size 还大**
>
> **KV Cache 优化**
>
> 1. **MQA / GQA**：多个 head 共享 K、V → 减少几倍
> 2. **MLA（DeepSeek-V2/V3）**：低秩压缩 K、V
> 3. **PagedAttention（vLLM）**：分页管理，无碎片
> 4. **Quantization**：fp16 → int8 / int4
> 5. **Sliding Window**：只缓存最近 K 个 token
>
> **模型权重量化**
>
> - bf16：基线
> - fp8：新硬件支持
> - int8：LLM.int8()、bitsandbytes
> - int4：GPTQ、AWQ、GGUF
>
> 7B 模型：
> - bf16：14 GB
> - int8：7 GB
> - int4：3.5 GB
>
> → 让大模型能在消费级 GPU 上跑。
>
> **显存优化技术栈**
>
> | 技术 | 节省 |
> |---|---|
> | ZeRO-1/2/3 | 优化器分片 |
> | Tensor Parallelism | 模型分片到多卡 |
> | Pipeline Parallelism | 层间分片 |
> | Activation Recomputation | 重算 activations |
> | Mixed Precision (fp16/bf16) | 减半 |
> | PagedAttention | KV cache 高效 |
> | GQA / MQA / MLA | KV cache 减少 |
> | Quantization (int4/8) | 权重 + KV 压缩 |
> | LoRA / QLoRA | 微调省显存 |
>
> **实测显存占用（Llama 2 7B，A100 80GB）**
>
> | 场景 | 显存 |
> |---|---|
> | FP16 推理（4K） | ~20 GB |
> | FP16 推理（32K） | ~50 GB |
> | FP16 训练（全参） | ~140 GB（需要多卡） |
> | LoRA 训练（bf16） | ~20 GB（单卡可行） |
> | QLoRA 训练（4bit + LoRA） | ~6 GB（消费级可行） |
>
> **总结**：
> - **训练**：**优化器状态（Adam）+ Activations** 最占
> - **推理**：**KV Cache**（特别是长上下文）最占
> - **优化**：ZeRO + Activation Recomputation + MQA/GQA + 量化
> - **微调**：LoRA / QLoRA 是消费级 GPU 友好的方案
> - **长上下文**：KV Cache 比模型本身还大，是真正的瓶颈
>
> 理解显存占用是工程化大模型的基础——不懂这个，无法训/部署 LLM。

### [Transformer 的位置编码是怎样的？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834693532749826)

> **答案**：
>
> **Transformer 的位置编码（Positional Encoding）**
>
> Self-attention 是**置换不变**的——把输入顺序打乱，输出只是对应打乱，没有"顺序"概念。位置编码就是给每个位置加上"位置信号"，让模型感知顺序。
>
> **为什么需要位置编码**
>
> ```
> "我 打 你" vs "你 打 我"
> ```
>
> 对 self-attention 来说，这两个句子如果只看 token 集合，是一样的。必须告诉模型"哪个 token 在第几位"。
>
> **主流位置编码方案**
>
> **1. Sinusoidal（正弦余弦，原始 Transformer）**
>
> 公式：
> ```
> PE(pos, 2i)   = sin(pos / 10000^(2i/d))
> PE(pos, 2i+1) = cos(pos / 10000^(2i/d))
> ```
>
> - 不同维度对应不同波长的正余弦波
> - 任意位置 PE(pos+k) 都能由 PE(pos) 线性表示 → 模型能学相对位置
> - 不需要训练
>
> **加入方式**：
> ```
> input_embedding = token_emb(x) + PE(pos)
> ```
>
> **2. Learned Positional Encoding（学习式，BERT/GPT-2）**
>
> 每个位置一个可学习向量：
> ```
> PE = nn.Parameter(torch.randn(max_len, d_model))
> ```
>
> - 简单，效果好
> - 缺点：**不能外推**（训练 max_len=512，推理 1024 直接崩）
> - BERT、GPT-2、ViT 用这个
>
> **3. Relative Positional Encoding（相对位置，T5）**
>
> 不直接编码"绝对位置"，而是编码"两个 token 的相对距离"：
> ```
> attention_score(i, j) += b[offset(i-j)]
> ```
>
> - 偏置 b 是可学的
> - 对相对位置敏感
> - 但计算量大，没大规模流行
>
> **4. RoPE（Rotary Position Embedding，旋转位置编码）**
>
> **Llama、Qwen、ChatGLM、Mistral 都用 RoPE**。
>
> 核心思想：**通过旋转矩阵把位置信息编码到 Q 和 K 上**。
>
> ```
> q_m = q · R(θ_m)
> k_n = k · R(θ_n)
>
> q_m · k_n = q · R(θ_m) · R(θ_n)^T · k^T
>           = q · R(θ_m - θ_n) · k
> ```
>
> - 内积只依赖**相对位置** (m - n)
> - 在 attention 计算时**自然注入位置**
> - 不改变 attention 公式
> - **支持外推**（通过 NTK-aware、YaRN 等扩展）
>
> **5. ALiBi（Attention with Linear Biases）**
>
> 不用位置编码，直接在 attention score 上加距离偏置：
> ```
> attention(i, j) = softmax(q_i · k_j / √d - m · |i-j|)
> ```
>
> - m 是预设的斜率（几何衰减）
> - **外推性极强**：训练 1024，推理 8K 仍稳定
> - BLOOM 用过
>
> **6. NoPE（No Positional Encoding）**
>
> 研究发现：**Decoder-only 模型即使没有显式位置编码，也能感知顺序**（因为 causal mask 本身隐含顺序）。
> 适合超长上下文。
>
> **对比表**
>
> | 方案 | 类型 | 可学习 | 外推 | 主要用户 |
> |---|---|---|---|---|
> | Sinusoidal | 绝对 | 否 | 一般 | 原始 Transformer |
> | Learned | 绝对 | 是 | 差 | BERT、GPT-2、ViT |
> | T5 Relative | 相对 | 是 | 一般 | T5 |
> | RoPE | 相对 | 否 | 好（可扩展） | LLaMA、Qwen、Mistral |
> | ALiBi | 相对 | 否 | 极好 | BLOOM |
> | NoPE | - | - | - | 实验 |
>
> **长上下文与位置编码**
>
> LLM 上下文从 2K → 100K+，位置编码是关键：
>
> - **Position Interpolation（PI）**：把 RoPE 的索引缩放，简单线性扩展。
> - **NTK-aware Scaling**：调整 RoPE 基础频率，外推更平滑。
> - **YaRN**：分段缩放，更精细。
> - **LongRoPE**：进化算法搜索最佳缩放，到 2M 上下文。
>
> **实战注意**
>
> 1. **不同位置编码不可互换**：BERT 的 learned PE 不能直接给 Llama 用。
> 2. **训练长度 ≠ 推理长度**：超出训练长度要用外推方法。
> 3. **位置编码影响"远程衰减"**：好的编码让注意力随距离衰减，避免"看见所有 token 都一样重要"。
> 4. **新模型基本都用 RoPE**：面试和实战的默认答案。
>
> **总结**：位置编码 = **给 self-attention 注入顺序信息**。**主流：RoPE（Llama 系）+ ALiBi（外推强）+ Learned（BERT）**。**长上下文的核心技术之一**——从 2K 到 1M，靠的就是位置编码的不断进化。理解 RoPE 是现代 LLM 工程师的必备技能。

### [Transformer 在计算 attention 的时候使用的是点乘还是加法？请说明理由。](https://www.mianshiya.com/bank/1906189461556076546/question/1821834693834739714)

> **答案**：
>
> **Transformer 用点积（Dot-Product）还是加法（Additive）Attention？**
>
> **答案：用点积（Scaled Dot-Product Attention）**。
>
> 原始论文采用：
> ```
> Attention(Q, K, V) = softmax(Q·K^T / √d_k) · V
> ```
>
> **两种 Attention 的对比**
>
> **1. Dot-Product Attention（点积）**
>
> ```
> score(q, k) = q · k = Σ q_i · k_i
> ```
>
> - 用内积衡量相似度
> - 可以用矩阵乘法高度并行
> - Transformer 用这个
>
> **2. Additive Attention（加性，Bahdanau 2014）**
>
> ```
> score(q, k) = v^T · tanh(W_q · q + W_k · k)
> ```
>
> - 用一个小 MLP 算相似度
> - 早期 seq2seq + attention 用这个
>
> **为什么 Transformer 选点积**
>
> **理由 1：计算极快（核心原因）**
>
> 点积可以用矩阵乘法（GEMM）一次算完：
> ```
> S = Q · K^T  # (N, d) × (d, N) → (N, N)
> ```
>
> 加性 attention 必须逐对算（q 和每个 k 都过一次 MLP），无法用 GEMM。
>
> 实测：点积 attention **快 6-10 倍**（在大维度下）。
>
> **理由 2：可扩展性强**
>
> 矩阵乘法是 GPU 高度优化的核心算子。Transformer 训练能 scale 到万亿参数，靠的就是把所有计算都尽量表达成矩阵乘。
>
> **理由 3：效果不差**
>
> 论文实验：当 d_k 较小（≤64）时，点积和加性效果相当；当 d_k 大时，点积要除以 √d_k 才稳定。
>
> **为什么除以 √d_k（Scaling）**
>
> 不除时的问题：
> - 当 d_k 大（如 512），q · k 是 512 个分量之和
> - 假设 q_i, k_i 是均值 0、方差 1 的独立变量，q · k 方差 = d_k
> - 方差很大 → softmax 输入有极端大值 → softmax 输出接近 one-hot → 梯度消失
>
> 除以 √d_k 把方差拉回 1：
> ```
> (q · k) / √d_k  → 方差 = 1
> ```
>
> → softmax 输入在合理范围 → 梯度健康
>
> **加性 Attention 还在哪些地方用**
>
> - 早期神经机器翻译（Bahdanau、Loung 之前）
> - 某些图神经网络（GAT）
> - 某些特殊场景（极端长序列时点积数值溢出）
> - 现代基本不用了
>
> **现代变体**
>
> **1. Scaled Dot-Product（标准）**
> ```
> softmax(QK^T / √d_k) V
> ```
>
> **2. FlashAttention**
> - 数学等价于 scaled dot-product
> - 但通过分块、重计算减少 HBM 访问
> - 是事实标准
>
> **3. Linear Attention**
> ```
> φ(Q) · (φ(K)^T V)
> ```
> - 把 softmax 替换成核函数 φ
> - O(N²) → O(N)
> - 性能略差但速度快
>
> **4. Multi-Query / Grouped-Query Attention**
> - 共享 K、V（多个 Q head 共享）
> - 不影响 attention 公式
> - 减少 KV cache 显存
>
> **面试延伸问题**
>
> **Q1：加性 attention 真的比点积 attention 效果好吗？**
> A：在 d_k 较小时差不多。原始论文实验显示，加性略好但慢得多。综合 ROI，点积胜出。
>
> **Q2：为什么不用余弦相似度？**
> A：余弦要先归一化，会丢失向量模长信息（模长可能编码了"重要性"）。点积保留了模长。但有些场景（如 CLIP）确实用 cosine。
>
> **Q3：attention 一定要 softmax 吗？**
> A：不一定。Linear Attention、Reformer、Performer 用其他归一化或直接不归一化，但效果通常有损。softmax 是主流因为"概率解释"清晰。
>
> **总结**：Transformer 用 **Scaled Dot-Product Attention**——`softmax(QK^T / √d_k) V`。**核心理由：矩阵乘法快、可扩展、效果不输加性**。除以 √d_k 是为了**控制 softmax 输入范围，避免梯度消失**。这是 Transformer 设计中"工程驱动研究"的经典案例——选择不是因为"理论上更好"，而是"实际上更快"。

### [Transformer 中，Decoder 阶段的多头自注意力和 Encoder 阶段的多头自注意力是相同的吗？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834696015777794)

> **答案**：
>
> **Decoder 与 Encoder 的多头自注意力是否相同？**
>
> **不同**。Decoder 的自注意力多了 **Causal Mask（因果掩码）**，并且通常还有额外的 **Cross-Attention** 层。
>
> **对比**
>
> **Encoder 多头自注意力**
> - **双向**：每个位置看到所有位置
> - 无 mask（除了 padding mask）
> - 用于编码输入
>
> **Decoder 多头自注意力（masked self-attention）**
> - **单向（因果）**：每个位置只能看到自己和之前的位置
> - 加 **causal mask**（look-ahead mask）：上三角部分设为 -∞
> - 用于自回归生成
>
> **Causal Mask 的工作原理**
>
> ```
> 序列长度 = 5，causal mask：
>
>     [0,  -∞, -∞, -∞, -∞]
>     [0,   0, -∞, -∞, -∞]
> M = [0,   0,  0, -∞, -∞]
>     [0,   0,  0,  0, -∞]
>     [0,   0,  0,  0,  0]
>
> attention_score = Q·K^T / √d + M
>                 → softmax → 加权 V
> ```
>
> - 位置 i 看到位置 j 的 score 被 mask 成 -∞
> - softmax(-∞) = 0 → 位置 i 的输出不包含位置 j 的信息
>
> 效果：**生成第 i 个 token 时，只能用第 0~i 个 token 的信息**，确保自回归。
>
> **Decoder 完整结构**
>
> ```
> Output Embedding + PE
>     ↓
> ┌────────────────────────────┐
> │ Decoder Layer × N（N=6）   │
> │                            │
> │  1. Masked Multi-Head      │  ← 自注意力（带 causal mask）
> │     Self-Attention         │
> │     Add & LayerNorm        │
> │                            │
> │  2. Cross-Attention        │  ← 与 Encoder 输出交互
> │     (Q from Decoder,       │
> │      K, V from Encoder)    │
> │     Add & LayerNorm        │
> │                            │
> │  3. Feed-Forward           │
> │     Add & LayerNorm        │
> └────────────────────────────┘
>     ↓
> Linear + Softmax → next token probability
> ```
>
> **Cross-Attention（Encoder-Decoder Attention）**
>
> - Q 来自 Decoder 当前位置
> - K、V 来自 Encoder 输出
> - 让 Decoder 在生成时"查阅"输入信息
>
> 例：翻译 "I love cats" → "我喜欢猫"
> - 生成 "我" 时，Decoder 看 Encoder 的所有 token，发现 "I" 最相关
> - 生成 "喜欢" 时，"love" 最相关
> - 生成 "猫" 时，"cats" 最相关
>
> **3 种 Attention 总结**
>
> | 类型 | Q | K, V | Mask | 用途 |
> |---|---|---|---|---|
> | Encoder Self-Attention | X | X | padding | 双向编码输入 |
> | Decoder Masked Self-Attention | Y | Y | padding + causal | 自回归生成 |
> | Cross-Attention | Y | X（Encoder 输出） | padding | Decoder 查阅 Encoder |
>
> **Decoder-only 模型（GPT 系）**
>
> 现代 LLM（GPT、Llama、Claude）大多是 **Decoder-only**：
> - 去掉 cross-attention（没有 Encoder 可查）
> - 只保留 masked self-attention + FFN
> - 自回归生成
>
> 为什么 Decoder-only 主导？
> 1. 训练目标统一（next token prediction）
> 2. 容易 scale
> 3. 数据效率高（无监督预训练）
> 4. 生成能力强
>
> **对比：Encoder-Decoder（T5、BART）**
>
> - Encoder 双向编码输入
> - Decoder 自回归生成输出
> - 适合：翻译、摘要、QA 抽取式
> - 训练目标：denoising / span corruption
>
> **Padding Mask vs Causal Mask**
>
> - **Padding Mask**：忽略 padding token（Encoder 和 Decoder 都有）
> - **Causal Mask**：防止看到未来（只有 Decoder 的 self-attention 有）
> - **Cross-Attention** 只有 padding mask（没有 causal，因为 Encoder 的输出对 Decoder 是"已知"的）
>
> **总结**：
> - **Encoder 多头自注意力 = 双向，无 causal mask**
> - **Decoder 多头自注意力 = 单向（causal mask），防止看到未来**
> - **Decoder 还有 Cross-Attention**，与 Encoder 输出交互
> - 现代 LLM 多为 **Decoder-only**，只有 masked self-attention
> - 区别关键在 **Mask 类型**，不在 attention 公式本身
>
> 理解这三种 attention 的差异是理解 Transformer 全貌的关键。
