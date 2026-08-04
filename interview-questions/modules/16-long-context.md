# 长上下文技术

> **本模块为原创补写，非面试鸭题库爬取内容**。基于公开论文与工程实践整理，覆盖原 14 个模块未深入的「长上下文技术」领域，共 8 题。完整索引见 [bank-llm-by-topic.md](../bank-llm-by-topic.md)。

---

> 共 8 题

### RoPE（旋转位置编码）的数学原理是什么？为什么它能支持长度外推？

> **答案**：
>
> **一、为什么需要位置编码**
>
> Self-Attention 本身是 permutation-invariant（排列不变）的——打乱输入顺序输出不变。要让模型"知道"token 的位置，必须显式注入位置信息。
>
> 主流方案：
>
> | 方案                    | 代表模型       | 特点                                       |
> |-------------------------|----------------|--------------------------------------------|
> | Sinusoidal（正弦）      | Transformer 原版 | 固定，不可学；外推能力差                  |
> | Learned（可学习）       | GPT-2、BERT    | 灵活，但完全不能外推                       |
> | ALiBi（线性偏置）       | BLOOM          | attention 分数加距离惩罚                   |
> | **RoPE（旋转）**        | Llama 全系列、Qwen、DeepSeek | 通过旋转矩阵注入相对位置，支持外推 |
>
> **二、RoPE 的核心思想**
>
> **Su et al. 2021《RoFormer》** 提出：**用旋转矩阵把位置信息注入到 query 和 key 上，使得 `q_m · k_n` 只依赖相对位置 `m - n`**。
>
> **数学推导**：
>
> 设 q, k 是 2D 向量，位置 m, n。希望找到一个变换 `f(q, m)`，使得 `f(q, m) · f(k, n) = g(q, k, m-n)`（只依赖相对位置）。
>
> 解：用 2D 旋转矩阵
>
> ```
> R_θ = [[cos(mθ), -sin(mθ)], [sin(mθ), cos(mθ)]]
>
> f(q, m) = R_mθ · q = q · e^(imθ)   （复数表示）
> ```
>
> 验证：
>
> ```
> (R_mθ · q) · (R_nθ · k) = q · R_(m-n)θ · k = g(q, k, m-n)
> ```
>
> **高维扩展**（d 维向量）：
>
> 把 d 维向量两两分组（d/2 个 2D 子空间），每组用不同频率 θ_i 旋转：
>
> ```
> θ_i = base^(-2i/d),  base = 10000（典型）
> ```
>
> 频率从高到低覆盖不同尺度——高频捕捉局部位置，低频捕捉全局位置。
>
> **三、RoPE 的工程实现**
>
> 给位置 m 的向量 x ∈ R^d：
>
> ```
> x'_2i   = x_2i × cos(m·θ_i) - x_2i+1 × sin(m·θ_i)
> x'_2i+1 = x_2i × sin(m·θ_i) + x_2i+1 × cos(m·θ_i)
> ```
>
> 实现：一个 element-wise multiply，无参数，O(d) 计算。
>
> 应用：在 attention 计算前，给 q 和 k 各乘一次 RoPE，v 不动。
>
> ```python
> def apply_rope(x, positions, theta=10000.0):
>     # x: (batch, seq, heads, dim)
>     d = x.shape[-1]
>     freqs = 1.0 / (theta ** (torch.arange(0, d, 2).float() / d))
>     angles = positions[:, None] * freqs[None, :]   # (seq, d/2)
>     cos = angles.cos()[None, :, None, :]
>     sin = angles.sin()[None, :, None, :]
>     x1, x2 = x[..., ::2], x[..., 1::2]
>     rotated = torch.stack([x1 * cos - x2 * sin, x1 * sin + x2 * cos], dim=-1)
>     return rotated.flatten(-2)
> ```
>
> **四、为什么 RoPE 支持外推**
>
> 1. **相对位置编码**：attention 分数只依赖 `m-n`，模型在训练时见过相对距离 [0, L_train]，外推到更长距离时，相对距离仍在合理范围。
> 2. **多尺度频率**：低频维度 θ_i 极小（如 base=10000 时最低频 ~1e-4），一个周期跨越数万 token → 长距离上仍有变化。
> 3. **平滑性**：cos/sin 是连续的，长距离上的 attention 模式可以从短距离平滑外推。
>
> **五、外推的局限**
>
> 纯 RoPE 外推到 2× 训练长度还行，4× 就开始退化，10× 完全崩溃。
>
> 原因：
> - 训练时没见过的频率组合 → attention 分布异常。
> - attention 分数被远距离 token 主导（softmax 在长距离上分布过散）。
>
> 解决：**长度外推技术**（PI、NTK-aware、YaRN，见下一题）。
>
> **六、RoPE 的变体**
>
> - **base 调整**：Llama 3 把 base 从 10000 改为 500000，降低频率，提升外推能力。
> - **LongRoPE（Microsoft）**：不同维度用不同 base，进化搜索找最优组合 → 支持 2M+ 上下文。
> - **RoPEntial**：把 RoPE 与 ALiBi 结合。
>
> **七、为什么 RoPE 一统天下**
>
> 1. **相对位置**：比绝对位置编码泛化好。
> 2. **无参数**：不增加模型参数。
> 3. **计算高效**：element-wise multiply，开销可忽略。
> 4. **外推友好**：配合 YaRN 等技术可达百万 token。
> 5. **工程实现简单**：几十行代码搞定。
>
> 几乎所有开源大模型（Llama、Qwen、DeepSeek、Mistral、Gemma）都用 RoPE。
>
> **八、对应用工程的影响**
>
> 1. **长上下文场景选 RoPE 模型**：Llama 3、Qwen 2.5、DeepSeek V3 都用 RoPE + 长度外推。
> 2. **微调长上下文**：用 YaRN / NTK-aware 扩展 base，加少量长文本数据训练。
> 3. **理解为什么长上下文贵**：attention 平方复杂度 + KV cache 线性增长 → 1M token 推理可能 30s+ 首延迟。
>
> **总结**：RoPE 通过旋转矩阵把位置信息融入 q/k，使 attention 只依赖相对位置——**这是它支持外推的数学根因**。配合 YaRN 等技术，RoPE 把上下文从 2K 推到 2M。理解 RoPE 是理解所有现代长上下文技术（PI、NTK、YaRN）的前提。

### RoPE 长度外推的三种方案：Position Interpolation / NTK-aware / YaRN 各是什么原理？

> **答案**：
>
> **背景**：模型训练时上下文 L_train（如 4K），推理时想用 L_infer（如 32K），直接外推会导致 attention 异常、质量崩塌。三种主流外推方案：
>
> **一、Position Interpolation（PI，位置插值）**
>
> **Chen et al. 2023《Extending Context Window of LLaMA》** 提出。
>
> **核心思想**：**不外推，内插**——把推理时的位置 `[0, L_infer)` 线性映射回训练范围 `[0, L_train)`。
>
> ```
> m' = m × (L_train / L_infer)
> ```
>
> 例：L_train=4K，L_infer=32K，原始位置 32000 → 映射到 4000。
>
> **优点**：
>
> - 实现简单（一行代码改 RoPE 的 position）。
> - 短训练（~1000 step + 1B token）即可生效。
>
> **缺点**：
>
> - **破坏局部精度**：相邻 token 的相对距离被压缩（原本距离 1 → 压缩为 0.125），高频细节丢失。
> - 远距离 token 仍然模糊。
> - 长距离任务（needle in haystack）效果差。
>
> **代表**：最早的 Llama 2 long、Vicuna-long。
>
> **二、NTK-aware Scaling**
>
> **社区方案（/u/bloc97 2023）**，源于 NTK（Neural Tangent Kernel）理论。
>
> **核心思想**：**只调整 base，不直接缩放位置**——让 RoPE 的频率分布更好覆盖长距离。
>
> ```
> base_new = base × (scale_factor)^(d / (d-2))
>
> 其中 scale_factor = L_infer / L_train
> ```
>
> 例：base 10000 → 32K 长度 → base ~16M。
>
> **原理**：
>
> - 高频维度（局部细节）几乎不变。
> - 低频维度（全局位置）周期被拉长，覆盖更远距离。
> - 保留了局部精度，又扩展了全局范围。
>
> **优点**：
>
> - 局部精度保留（高频不变）。
> - 长距离效果好于 PI。
> - **zero-shot 即可用**（无需训练）。
>
> **缺点**：
>
> - 极长距离（> 8×）仍不够稳。
> - 短训练（fine-tune）后效果更好。
>
> **代表**：Code Llama、SuperHOT、早期 Qwen2 长上下文。
>
> **三、YaRN（Yet another RoPE extensioN）**
>
> **Peng et al. 2023《YaRN》** 提出，是目前 SOTA 的外推方法。
>
> **核心思想**：**分段插值**——不同频率维度用不同策略。
>
> RoPE 的频率谱可以分三段：
>
> 1. **高频段**（波长 < L_train）：保持不变（局部细节重要）。
> 2. **中频段**（L_train < 波长 < L_infer）：NTK-style 调整（外推）。
> 3. **低频段**（波长 > L_infer）：插值（PI-style，压缩回训练范围）。
>
> **数学形式**：
>
> ```
> 对每个频率维度 i：
>   wavelength_i = 2π / θ_i
>   if wavelength_i < L_train:    频率不变
>   elif wavelength_i < L_infer:  NTK-style 混合
>   else:                          PI-style 插值
> ```
>
> 外加一个 attention scaling factor `temp = 1 + 0.1 × log(scale_factor)`，缓解长距离 attention 分布过散。
>
> **优点**：
>
> - 当前 SOTA，外推效果最好。
> - 短训练（~400 step）即可支持 4× 外推。
> - Llama 3、Qwen 2.5、DeepSeek 等都用 YaRN 或其变体。
>
> **缺点**：
>
> - 实现复杂（分段插值）。
> - 仍需少量长文本训练数据。
>
> **四、三种方案对比**
>
> | 方案          | 训练成本   | 局部精度 | 长距离效果 | 适用场景           |
> |---------------|------------|----------|------------|--------------------|
> | **PI**        | ~1000 step | 差       | 中         | 4× 外推            |
> | **NTK-aware** | 0 (zero-shot) | 好    | 中         | 4× 外推快速上手    |
> | **YaRN**      | ~400 step  | 好       | 优         | 8× - 32× 外推      |
>
> **实测效果**（Llama 2 7B 从 4K 扩到 16K，PG19 per-token 困惑度）：
>
> - 直接外推：完全发散。
> - PI：~6.0
> - NTK-aware：~5.5
> - YaRN：~5.0（接近原模型在 4K 上的表现）。
>
> **五、训练数据策略**
>
> 长上下文外推需要"激活"训练：
>
> 1. **数据准备**：长文档（书籍、代码库、长对话），1B-5B token。
> 2. **课程学习**：先短后长（4K → 8K → 16K → 32K），逐步扩展。
> 3. **配比**：长上下文数据 30% + 短上下文 70%（防遗忘）。
> 4. **训练量**：1000-5000 step 即可。
>
> Llama 3.1 训练到 128K 上下文，用了大量合成长文档 + 课程学习。
>
> **六、LongRoPE / 进化搜索（前沿）**
>
> Microsoft 2024 提出，把外推参数搜索转化为进化算法：
>
> - 每个频率维度独立选择 frequency scaling 和 position scaling。
> - 用困惑度 + needle-in-haystack 评估作为 fitness。
> - 进化搜索找到近似最优组合。
>
> 支持外推到 **2M token**（Phi-3、Gemma 长上下文版用）。
>
> **七、对应用工程的影响**
>
> 1. **选模型看长度**：Llama 3.1 (128K native)、Qwen 2.5 (128K)、DeepSeek V3 (128K)、Gemini 1.5 (2M)。
> 2. **自部署长上下文**：vLLM 支持 YaRN 配置，加 `--rope-scaling-type yarn --rope-scaling-factor 4.0`。
> 3. **微调长上下文模型**：用 YaRN + 长文档数据，4× 外推只需 1-2 GPU-hours。
> 4. **预算评估**：长上下文 token 成本仍高（Gemini 2M 输入 $7/1M），优先用 prompt caching。
>
> **总结**：长度外推是 2023-2024 大模型最重要的工程突破之一。**YaRN 是当前 SOTA**，Llama 3、Qwen 2.5、DeepSeek 全都用它。理解这三种方案的演进，等于理解了"上下文从 4K 扩到 2M"背后的工程栈——这是面试加分点，也是自部署长上下文模型的必备知识。

### ALiBi vs RoPE：两种位置编码的本质区别与适用场景

> **答案**：
>
> **一、两种位置编码的本质区别**
>
> | 维度          | RoPE（Llama 全系列）                    | ALiBi（BLOOM、MPT）                    |
> |---------------|-----------------------------------------|----------------------------------------|
> | **注入位置**  | q 和 k 上（旋转）                       | attention 分数上（线性偏置）           |
> | **类型**      | 相对位置                                | 相对距离惩罚                           |
> | **可学参数**  | 无                                      | 无（每 head 一个 slope）               |
> | **外推能力**  | 中等（需 YaRN）                         | 原生强（zero-shot 4-8× 外推有效）      |
> | **实现位置**  | Q,K 投影后、attention 前                | attention 分数上加 bias                |
> | **代表模型**  | Llama、Qwen、DeepSeek、Mistral          | BLOOM、MPT、Falcon（早期）             |
>
> **二、ALiBi 的原理**
>
> **Press et al. 2022《Train Short, Test Long》** 提出。
>
> 思想：**直接在 attention 分数上加一个与距离成正比的负偏置**，距离越远注意力越小。
>
> ```
> attention_score(i, j) = (q_i · k_j) / sqrt(d) + m · (i - j)
> ```
>
> 其中 `m` 是每个 head 的 slope（斜率），负值，按几何级数分配：
>
> ```
> 对于 8 heads: m = [1/2^0, 1/2^1, ..., 1/2^7] 取负
> 即 [-1, -0.5, -0.25, ..., -0.0078]
> ```
>
> 不同 head 用不同 slope → 不同 head 关注不同距离范围。
>
> **三、ALiBi 的外推能力**
>
> ALiBi 训练时只需短上下文（如 1K），推理时直接 4-8× 外推，perplexity 平滑增长。
>
> **原因**：
>
> 1. distance penalty 是连续函数，远距离自然衰减，不会突然崩。
> 2. 不同 head 覆盖不同尺度（slope 几何级数）。
> 3. 不依赖学到的位置 embedding，外推时不需要"见过"。
>
> **实验**：在 1024 token 训练，2048 token 推理，BLOOM 性能几乎不降。
>
> **四、ALiBi 的劣势**
>
> 1. **绝对距离衰减**：模型对远距离信息"看到"的能力被强制降低 → 长距离 retrieval 任务（needle in haystack）反而差。
> 2. **不适合超长上下文**：100K+ token 时，远距离 attention 几乎为零，信息完全丢失。
> 3. **表达能力弱**：硬编码的线性 bias 限制了模型学习复杂位置模式。
> 4. **生态支持少**：vLLM、TensorRT-LLM 对 ALiBi 支持不如 RoPE 完善。
>
> **五、为什么 RoPE 胜出**
>
> 1. **长上下文时代**：100K+ token 场景，ALiBi 的 distance penalty 反而成为障碍；RoPE + YaRN 能到 2M。
> 2. **检索能力强**：RoPE 不会强制衰减远距离 attention，needle in haystack 表现更好。
> 3. **工程成熟**：RoPE 已成为社区标准，工具链完善。
> 4. **可扩展性**：YaRN / LongRoPE 等技术在 RoPE 上蓬勃发展。
>
> 2023 年后，**所有主流大模型（Llama 3、Qwen 2.5、DeepSeek V3、Mistral、Gemma）都用 RoPE**，ALiBi 几乎被淘汰。
>
> **六、ALiBi 的应用场景**
>
> 仍有一些场景 ALiBi 有优势：
>
> 1. **zero-shot 短外推**：训练预算极紧，希望 2-4× 外推不训练 → ALiBi。
> 2. **理论分析**：ALiBi 数学简单，论文常用作 baseline。
> 3. **小模型 + 短上下文**：1B 以下模型 + 4K 上下文，ALiBi 够用。
>
> **七、其他位置编码方案**
>
> 1. **Sinusoidal**：Transformer 原版，外推差，已不用。
> 2. **Learned**：每个位置一个 embedding，完全不能外推。
> 3. **NoPE（No Position Encoding）**：研究发现 decoder-only 模型即使不加位置编码也能学到顺序（通过 causal mask）→ 极长外推能力。
> 4. **Hybrid**：Llama 4 部分层用 RoPE，部分层用 NoPE / attention sink，追求超长上下文。
>
> **八、对应用工程的启示**
>
> 1. **选模型默认选 RoPE**：生态最完善，工具链最好。
> 2. **不要被 ALiBi 的"原生外推"迷惑**：长上下文实际效果差于 RoPE + YaRN。
> 3. **关注 NoPE 等新方向**：未来 10M+ 上下文可能依赖 NoPE 或混合方案。
>
> **总结**：ALiBi 和 RoPE 是 2022-2023 位置编码的两条路线——ALiBi 简单粗暴地加距离惩罚，RoPE 优雅地用旋转矩阵编码相对位置。**长上下文时代选择了 RoPE**，ALiBi 因远距离能力衰减被淘汰。理解这个演进，能帮助你选模型、调长上下文配置，并在面试时讲清楚"为什么 Llama 用 RoPE 而不用 ALiBi"。

### FlashAttention 1 / 2 / 3 分别优化了什么？为什么能实现精确 attention 而非近似？

> **答案**：
>
> FlashAttention 是 Tri Dao（斯坦福）的代表作，**长上下文时代的核心基础设施**。所有主流大模型推理框架（vLLM、TensorRT-LLM、SGLang）都基于它。
>
> **一、为什么需要 FlashAttention**
>
> 标准 attention 的瓶颈：
>
> ```
> Attention(Q, K, V) = softmax(Q · K^T / sqrt(d)) · V
> ```
>
> 序列长度 N 时：
>
> - **HBM 读写**：N×N 的 attention matrix 要写到 HBM（显存）再读回。N=8K，单层 attention matrix 几 GB → 频繁读写 HBM 拖慢训练。
> - **显存**：N×N 矩阵 + activation，128K context 单层几百 GB。
> - **IO 复杂度**：O(N²) memory access，远超算力 O(N²·d)。
>
> **GPU 内存层次**：
>
> ```
> SRAM (on-chip, ~20MB, 19TB/s)
>     ↓
> HBM (显存, ~80GB, 3TB/s)
> ```
>
> SRAM 比 HBM 快 6×，但容量极小。标准 attention 在 HBM 之间反复搬运数据 → 瓶颈是 IO 而非算力。
>
> **二、FlashAttention v1（2022）的核心创新**
>
> **思想**：用 **tiling（分块）+ online softmax**，避免实例化 N×N matrix。
>
> **算法**：
>
> 1. 把 Q, K, V 切成块（block size ~64×64，适配 SRAM）。
> 2. 每次只载入一个 Q 块 + 一个 K, V 块到 SRAM。
> 3. 在 SRAM 内计算 attention 分数，**online softmax** 增量更新（不需要先全部算完）。
> 4. 累加输出，写回 HBM。
>
> **Online Softmax**：
>
> ```
> 传统：softmax(x_i) = exp(x_i) / Σ exp(x_j)
>       需要先算完所有 x_j 才能归一化
>
> Online：维护 running max m 和 running sum Σ
>        每来一个新块，更新 m 和 Σ，重新归一化之前的结果
> ```
>
> **结果**：
>
> - **计算精确**（不是近似）：与标准 attention 数学等价。
> - **IO 复杂度 O(N²d / M)**，M 是 SRAM 大小 → 远好于 O(N²)。
> - **显存 O(N)**：不实例化 N×N matrix。
> - **训练加速 2-4×**（A100，GPT-2 上下文 1K → 4K）。
> - **长上下文加速 5-10×**（16K+）。
>
> **三、FlashAttention v2（2023）的优化**
>
> v1 仍有问题：work partition 不均、矩阵乘法效率未拉满。v2 改进：
>
> 1. **更少 non-matmul FLOPs**：减少 rescale 操作。
> 2. **更均衡的并行**：v1 按 batch × head 切分，v2 在 sequence 维度也切分 → 长序列下 GPU 利用率显著提升。
> 3. **更优 warp partition**：把一个 block 内的工作分给 4 个 warp，减少同步。
>
> **结果**：
>
> - 比 v1 再快 **2×**（A100 上）。
> - 长 sequence 加速更明显（达到 GPU 峰值算力的 50-70%）。
> - 成为 Llama 2/3、Qwen、DeepSeek 训练标配。
>
> **四、FlashAttention v3（2024，Hopper 专属）**
>
> 针对 NVIDIA H100（Hopper 架构）的优化：
>
> 1. **异步执行（async）**：利用 Hopper 的 TMA（Tensor Memory Accelerator）+ warpgroup，让数据搬运和计算重叠。
> 2. **FP8 支持**：Hopper 原生 FP8，v3 利用 FP8 tensor core → **比 v2 快 1.5-2×**。
> 3. **低精度保留精度**：用 FP32 accumulate + FP8 compute，精度损失 < 0.5%。
>
> **结果**：
>
> - H100 上达到 **740 TFLOPs**（FP16），接近理论峰值。
> - 比 v2 快 **1.5-2×**（FP16）/ **2×+**（FP8）。
> - Claude 3.5、Llama 3.1 405B 训练的核心。
>
> **五、为什么 FlashAttention 是精确而非近似**
>
> 常见误解："更快 = 精度损失"。FlashAttention **数学上与标准 attention 完全等价**。
>
> 证明：
>
> 1. tiled block matrix multiply 的结果与全矩阵相乘相同（线性代数保证）。
> 2. online softmax 通过 max-subtraction 技巧保证数值稳定性，结果与一次性 softmax 完全一致。
>
> **对比 sparse / linear attention**（这些是近似）：
>
> - Sparse Attention（Longformer）：只算部分位置，**有信息损失**。
> - Linear Attention（Performer）：用核近似 softmax，**精度损失大**。
> - FlashAttention：**IO 优化，数学等价，零精度损失**。
>
> **六、FlashAttention 的工程价值**
>
> 1. **训练成本降低**：长上下文训练从"不可能"变为"可行"。
> 2. **推理加速**：配合 KV cache，长上下文推理 latency 显著降低。
> 3. **显存友好**：支持更大的 batch size，提升吞吐。
> 4. **生态完善**：PyTorch 2.0+ 原生集成（`F.scaled_dot_product_attention`）。
>
> **七、使用 FlashAttention**
>
> ```python
> # PyTorch 2.0+
> import torch.nn.functional as F
> out = F.scaled_dot_product_attention(q, k, v, is_causal=True)
> # 自动选择 FlashAttention 后端
>
> # vLLM 启用
> python -m vllm.entrypoints.api_server --model meta-llama/Llama-3-8B \
>        --enforce-eager  # 不开 CUDA Graph 时用 FlashAttention
>
> # HuggingFace transformers
> model = AutoModelForCausalLM.from_pretrained(
>     "...", torch_dtype=torch.bfloat16, attn_implementation="flash_attention_2"
> )
> ```
>
> **八、其他 attention 优化**
>
> | 方案                 | 类型     | 精度     | 适用场景                  |
> |----------------------|----------|----------|---------------------------|
> | FlashAttention 1/2/3 | IO 优化  | 精确     | 所有 LLM，标准方案        |
> | PagedAttention       | KV 管理  | 精确     | 推理服务（vLLM）          |
> | Sparse Attention     | 稀疏化   | 近似     | 超长上下文（Longformer）  |
> | Linear Attention     | 核近似   | 近似     | 极长序列，精度要求低      |
> | Ring Attention       | 分布式   | 精确     | 多 GPU 切分长上下文       |
> | Mamba / SSM          | 架构变   | N/A      | 替代 attention 的方案     |
>
> **总结**：FlashAttention 是过去三年最重要的 LLM 基础设施——**通过 IO 优化把 attention 从 O(N²) 显存降到 O(N)，且数学上完全精确**。v1 → v2 → v3 持续提升，Hopper 上 v3 已达峰值算力。理解 FlashAttention 是理解为什么 Llama 3、DeepSeek 能训出 128K 上下文的关键，也是面试高频加分点。

### Ring Attention / Sparse Attention / Longformer 怎么扩展到百万 token？

> **答案**：
>
> 标准 attention 是 O(N²) 算力 + O(N²) 显存。当 N=1M 时，单层 attention matrix 就是 1TB——单卡根本装不下。三种主流方案：
>
> **一、Sparse Attention（稀疏注意力）**
>
> **思想**：不计算所有 (i, j) 对，只计算"重要"的。
>
> **代表方案**：
>
> 1. **Local/Window Attention**：每个 token 只看周围 W 个 token（窗口大小 W=512/1024）。
>    - Longformer、BigBird 用。
>    - 适合"局部信息重要"的任务（文本分类）。
>    - 缺点：远距离信息完全丢失。
>
> 2. **Global Token**：选几个 token 作为"全局 token"，所有 token 都看它们。
>    - Longformer 的设计：少量 global token + 大量 local token。
>    - 适合"全文摘要"任务（少量关键 token 桥接全局）。
>
> 3. **Random Attention**：每个 token 随机看几个远距离 token。
>    - BigBird 的设计：local + global + random。
>    - 理论证明：这三种组合的 sparse attention 与 dense attention 表达能力等价。
>
> 4. **Block-sparse**：固定稀疏模式（如每隔 256 token 跨块 attend）。
>    - Longformer、GPT-3 早期版本。
>
> **优点**：算力 / 显存从 O(N²) 降到 O(N·W) 或 O(N·log N)。
>
> **缺点**：
> - 稀疏模式是手工设计的，不灵活。
> - 远距离 retrieval 任务可能漏掉关键信息。
> - 训练时稀疏 kernel 不如 dense 高效。
>
> **现状**：现代大模型几乎不用 sparse attention——FlashAttention 的 IO 优化已经把 dense attention 优化到可用，sparse 的精度损失不值得。
>
> **二、Ring Attention（分布式长上下文）**
>
> **Liu et al. 2023《Ring Attention》** 提出，**让多 GPU 协作处理超长序列**。
>
> **思想**：把序列切分到多卡上，每卡只持有自己那段的 Q, K, V；通过环形通信（ring all-gather）传递 K, V，让每张卡都能"看到"完整序列。
>
> **算法**：
>
> ```
> 序列 [0, 4N) 切到 4 卡：
> GPU 0: Q[0:N], K[0:N], V[0:N]
> GPU 1: Q[N:2N], K[N:2N], V[N:2N]
> GPU 2: Q[2N:3N], K[2N:3N], V[2N:3N]
> GPU 3: Q[3N:4N], K[3N:4N], V[3N:4N]
>
> 步骤：
> 1. 每卡用本地 Q + 本地 K, V 计算 partial attention
> 2. 把本地 K, V 沿环形传给下一卡
> 3. 同时计算本地 Q + 刚收到的 K, V
> 4. 重复 3 轮，每卡都见过所有 K, V
> 5. 合并结果，输出完整 attention
> ```
>
> **关键**：**通信和计算重叠**——K, V 在通信时，Q 在并行计算上一轮的 attention。
>
> **优点**：
>
> - 算力精确，与 dense attention 数学等价。
> - 显存：每卡只装 N/d 个 token 的 KV，可扩展到任意长度。
> - 已部署在 Claude（200K）、Gemini（1M+）的训练中。
>
> **缺点**：
>
> - 通信开销随 GPU 数增加。
> - 需要高速互联（NVLink、InfiniBand）。
>
> **代表**：Anthropic Claude 200K、Google Gemini 2M 都用类似技术。
>
> **三、FlashAttention-3 + Ring Attention 组合**
>
> 现代超长上下文（1M+）训练的标配：
>
> - **单卡内**：FlashAttention-3，IO 优化。
> - **跨卡**：Ring Attention，序列切分。
> - **跨机**：Pipeline Parallel，按层切。
>
> Llama 3.1 405B 训练 128K 上下文用这套栈。
>
> **四、StreamingLLM（attention sink）**
>
> **Xiao et al. 2023《Efficient Streaming Language Models》** 发现：
>
> - 训练时模型对**前几个 token** 形成了"attention sink"——大量 attention 权重集中在 sequence 开头。
> - 如果推理时丢弃前几个 token，模型会崩。
> - 保留前 4 个 token + 滑动窗口 → 无限长度流式推理。
>
> **应用**：流式对话、超长文档摘要（不需要全部 attention）。
>
> **五、 kv cache 压缩**
>
> 长上下文推理时 KV cache 是显存大头（70B × 128K ≈ 几十 GB）。压缩方案：
>
> 1. **Sliding Window**：只保留最近 N tokens KV（StreamingLLM）。
> 2. **H2O (Heavy-Hitter Oracle)**：识别重要 token，丢弃不重要。
> 3. **KV quantization**：FP16 KV → INT8 / INT4。
> 4. **KV offload**：旧 KV swap 到 CPU 内存。
> 5. **PagedAttention**：分页管理 KV，减少碎片。
>
> **六、Longformer / BigBird 现状**
>
> Longformer、BigBird 是 2020 年的方案，当时没有 FlashAttention。
>
> 现在：
>
> - **训练**：FlashAttention 已让 dense attention 在 128K 上下文可行 → 不需要 sparse。
> - **推理**：sparse attention 可能在极长上下文（10M+）有用，但工业级 LLM 几乎都不用。
> - **理论价值**：sparse attention 的表达能力证明仍有学术意义。
>
> **七、对比表**
>
> | 方案              | 算力       | 精度     | 工程复杂度 | 适用场景                  |
> |-------------------|------------|----------|------------|---------------------------|
> | Dense + FlashAttention | O(N²d) | 精确     | 低         | < 1M token                |
> | Sparse Attention  | O(N·W·d)   | 近似     | 中         | 极长 + 局部任务           |
> | Ring Attention    | O(N²d / G) | 精确     | 高         | 多 GPU 超长上下文训练     |
> | StreamingLLM      | O(N·W·d)   | 近似     | 低         | 流式推理                  |
> | KV Cache 压缩     | 与基础一致 | 近似     | 中         | 推理服务降显存            |
> | Mamba / SSM       | O(N·d)     | N/A      | 中         | 替代 attention（实验中）  |
>
> **八、对应用工程的影响**
>
> 1. **长上下文选 dense + FlashAttention**：现代 LLM 默认方案，质量最高。
> 2. **超长（1M+）训练用 Ring Attention**：跨卡切分。
> 3. **推理显存优化**：PagedAttention + KV quant + sliding window。
> 4. **流式场景**：StreamingLLM 或 sliding window。
>
> **总结**：从 sparse attention（Longformer）到 Ring Attention，长上下文技术的演进方向是「**保持 dense attention 的精度，通过 IO 优化和分布式突破显存墙**」。FlashAttention 让 dense attention 可用，Ring Attention 让 dense attention 跨卡，二者组合是 2024-2026 长上下文 LLM 的主流。

### KV Cache 显存怎么计算？PagedAttention 的核心思想是什么？

> **答案**：
>
> **一、KV Cache 的来源**
>
> 自回归生成时，每生成一个 token，要重新算 attention。如果每步都重算所有历史 token 的 K, V，算力浪费严重。
>
> **优化**：把已计算过的 K, V 缓存起来，下一步只算新 token 的 K, V 追加进去。
>
> ```
> Step 1: 输入 [a, b, c] → 计算所有 K, V → 缓存
> Step 2: 输入 [d] → 计算新 K_d, V_d → 追加到缓存 → attention with [a,b,c,d]
> Step 3: 输入 [e] → 计算新 K_e, V_e → 追加到缓存 → attention with [a,b,c,d,e]
> ```
>
> **算力节省**：从 O(N²) 降到 O(N)（每步只算新 token 与历史的 attention）。
>
> **二、KV Cache 显存计算**
>
> 每层每个 token 的 KV cache 大小：
>
> ```
> K, V 各一份，每个 = (num_heads × head_dim) × dtype_size
>
> 简化（合并 K, V）：
> KV_per_token_per_layer = 2 × num_heads × head_dim × dtype_size
>                       = 2 × hidden_dim × dtype_size
> ```
>
> 总 KV cache：
>
> ```
> KV_total = batch × seq_len × 2 × hidden_dim × num_layers × dtype_size
> ```
>
> **例**：Llama 3 70B（hidden=8192, layers=80, batch=1, seq=128K, dtype=bf16=2 bytes）
>
> ```
> KV_total = 1 × 131072 × 2 × 8192 × 80 × 2
>          = 343 GB
> ```
>
> **单请求 128K 上下文，KV cache 343GB** —— 显存比模型权重（70B × 2 = 140GB）还大！
>
> 用 GQA（Grouped Query Attention）可减半甚至更少（Llama 3 70B 用 8 个 KV head 而非 64 个 query head）：
>
> ```
> KV_total (GQA, KV heads=8) = 1 × 131072 × 2 × 1024 × 80 × 2 = 43 GB
> ```
>
> 仍然不小。
>
> **三、KV Cache 的痛点**
>
> 1. **显存碎片**：
>    - 序列长度变化（有的请求 100 token，有的 100K），固定分配会浪费。
>    - 传统方案：pre-allocate max_len → 极度浪费。
>
> 2. **请求间共享难**：
>    - 同一 system prompt 被多个请求复用，传统方案每请求独立 cache → 重复存储。
>
> 3. **batch 调度死板**：
>    - 静态 batching：batch 内最长序列决定显存，短的浪费。
>
> **四、PagedAttention（vLLM 的核心创新）**
>
> **Kwon et al. 2023《Efficient Memory Management for LLM Serving with PagedAttention》** 提出。
>
> **核心思想**：**借鉴 OS 虚拟内存的分页机制**，把 KV cache 切成固定大小的 block（页），按需分配。
>
> **机制**：
>
> 1. **Block（页）**：KV cache 切成固定大小，如 16 个 token 一页。
> 2. **Block Table（页表）**：每个 sequence 维护一个 block table，记录自己的 KV 分布在哪些物理 block。
> 3. **物理 block 共享**：多个 sequence 可以引用同一物理 block（共享 system prompt KV）。
> 4. **Copy-on-Write**：当某 sequence 修改共享 block 时（罕见），才复制。
>
> **优点**：
>
> - **显存几乎零碎片**：按需分配，不需要 pre-allocate。
> - **支持 shared prefix**：相同 system prompt / few-shot 共享 KV → 节省 50%+ 显存。
> - **支持 variable-length batch**：长短序列共存，无浪费。
> - **吞吐提升 2-4×**（vs static allocation）。
>
> **五、PagedAttention vs 传统方案对比**
>
> | 维度             | 传统 (Continuous Buffer) | PagedAttention        |
> |------------------|--------------------------|-----------------------|
> | 显存分配         | Pre-allocate max_len     | 按需分页              |
> | 碎片             | 高（短序列浪费）         | 几乎零                |
> | Shared Prefix    | 不支持（重复存储）       | 原生支持              |
> | Variable batch   | 难（被最长序列限制）     | 自然支持              |
> | 吞吐             | 1×                       | 2-4×                  |
> | 实现复杂度       | 低                       | 中                    |
>
> **六、vLLM 的整体架构**
>
> vLLM = PagedAttention + Continuous Batching + 优化的 CUDA kernel：
>
> 1. **PagedAttention**：KV cache 管理。
> 2. **Continuous Batching**：请求随到随合，不等。
> 3. **Prefix Caching**：自动识别共享前缀（system prompt）→ 复用 KV。
> 4. **Speculative Decoding**：可选，小模型草拟加速。
>
> 结果：vLLM 吞吐比 HuggingFace Transformers 高 **10-20×**。
>
> **七、KV Cache 优化技术全景**
>
> 1. **PagedAttention**：分页管理。
> 2. **Prefix Caching / RadixAttention**（SGLang）：共享前缀 KV 复用，按前缀树管理。
> 3. **KV Quantization**：FP16 → INT8 / INT4，节省 50-75%。
> 4. **KV Offload**：旧 KV swap 到 CPU 内存，按需取回。
> 5. **Sliding Window**：只保留最近 N tokens KV（StreamingLLM）。
> 6. **H2O**：识别"重要"token，丢弃不重要。
> 7. **Cross-Layer Attention**：相邻层共享 KV（Layer-wise caching）。
>
> **八、对应用工程的影响**
>
> 1. **部署 LLM 用 vLLM 或 SGLang**：吞吐 10-20× 提升。
> 2. **System Prompt 长时启用 prefix caching**：显著降本。
> 3. **长上下文场景监控 KV cache**：单请求 100K+ token，KV cache 可能 > 模型权重。
> 4. **GQA 模型优先**：Llama 3、Qwen 2.5、Mistral 都用 GQA，KV cache 小 8-16×。
> 5. **batch size 调优**：vLLM 自动调度，但 max_batch_size 仍需根据显存调。
>
> **总结**：KV Cache 是 LLM 推理的"显存大头"——长上下文场景甚至超过模型权重。PagedAttention 用"虚拟内存分页"思想解决了碎片问题，配合 Continuous Batching 让 vLLM 实现了 10-20× 吞吐提升。理解这套机制是部署运维 LLM 服务的核心知识，也是面试高频考点。

### Lost in the Middle 是什么现象？如何缓解？

> **答案**：
>
> **一、现象定义**
>
> **Liu et al. 2023《Lost in the Middle: How Language Models Use Long Contexts》** 通过实验发现：
>
> > **LLM 在长上下文中，对开头和结尾的信息利用率高，对中间位置的信息利用率显著下降。**
>
> **实验设计**：
>
> - 给 LLM 一份长文档（如 50 个段落）。
> - 把"答案段落"放在不同位置（开头 / 中间 / 结尾）。
> - 测试 LLM 能否正确回答基于该段落的问题。
>
> **结果**（多文档 QA 任务）：
>
> ```
> 位置           准确率
> ──────────────────────
> 开头 (1)       75%
> 第 5 位        70%
> 第 25 位 (中)  45%   ← 显著下降
> 第 45 位       72%
> 结尾 (50)      78%
> ```
>
> 形成 **U 形曲线**——开头和结尾高，中间低。
>
> **二、为什么会出现 Lost in the Middle**
>
> 1. **Attention 分布偏置**：
>    - 训练时模型对开头 token 形成 attention sink（大量 attention 集中在开头）。
>    - 因果 attention 让最近 token（结尾）天然权重高。
>    - 中间 token 既不是 sink 也不是 recent，被边缘化。
>
> 2. **位置编码外推**：
>    - 中等位置（如 32K 上下文的 16K 处）处于训练数据稀疏区。
>    - 长度外推（YaRN 等）对中间位置效果弱于两端。
>
> 3. **Softmax 归一化**：
>    - 100K+ token 的 softmax 分布过散，每个 token 平均权重低。
>    - 极端 token（开头 sink、结尾 recent）相对权重更高。
>
> 4. **训练数据分布**：
>    - 训练样本大多是短文档 + 部分超长。
>    - 长文档的"关键信息在中间"模式训练不充分。
>
> **三、影响场景**
>
> 1. **RAG**：检索到的文档拼到 prompt 里，关键内容如果排在中间，模型可能漏掉。
> 2. **长文档问答**：合同、论文、代码库分析时，中间章节信息丢失。
> 3. **Few-shot learning**：示例排序影响效果，中间示例权重低（推荐把最重要示例放最后）。
> 4. **Multi-turn 对话**：很久之前的轮次（中间位置）容易被忽略。
>
> **四、缓解策略**
>
> **1. 信息排布（最简单有效）**
>
> - **关键信息放结尾**：检索结果按相关性升序排，最相关的放最后。
> - **关键信息放开头**：少数情况（attention sink 强）。
> - **避免中间**：长 prompt 中间放补充资料，不放关键信息。
>
> RAG 实战：召回 top-10 → Rerank → 把 top-1 放 prompt 最末尾。
>
> **2. Rerank + 截断**
>
> - 召回 top-50 → Reranker 打分 → 只取 top-3~5。
> - 减少上下文长度，降低 Lost in Middle 概率。
>
> **3. Map-Reduce 式处理**
>
> 长文档分块，每块单独推理，最后合并：
>
> ```
> 长文档 → 切 10 块 → 每块单独问 LLM → 合并答案
> ```
>
> 避免一次性塞入过长上下文。
>
> **4. 滑动窗口 + 摘要**
>
> ```
> 文档 → 滑动窗口逐段摘要 → 累积摘要 → 最终摘要
> ```
>
> 每步上下文都短，避免 Lost in Middle。
>
> **5. Multi-Query / 多次检索**
>
> 同一问题问多次（不同 prompt 顺序），合并结果。
>
> **6. Fine-tune 长上下文**
>
> - 用"needle in haystack"数据训练。
> - 把答案放在不同位置，强制模型学习全范围检索。
>
> **7. Attention Sink 优化**
>
> - 保留前 4 个 token 作为 sink（StreamingLLM 思想）。
> - 显式约束 attention 不要过度集中。
>
> **五、Needle in a Haystack 评估**
>
> 测试 LLM 长上下文检索能力的标准方法：
>
> 1. 把一句"针"（如"密码是 12345"）藏在长文档不同位置。
> 2. 问 LLM "密码是什么"。
> 3. 测试不同上下文长度（1K、4K、16K、128K）× 不同位置（0%、25%、50%、75%、100%）。
> 4. 绘制热力图：横轴位置，纵轴长度，颜色表示准确率。
>
> **结果对比**：
>
> - GPT-4（128K）：边缘高，中间略低（轻微 Lost in Middle）。
> - Claude 3（200K）：几乎无 Lost in Middle（Anthropic 显式优化）。
> - Gemini 1.5（2M）：长上下文检索强。
> - Llama 3.1（128K）：中等。
>
> **六、对 RAG 的实战建议**
>
> 1. **召回后必 Rerank**：避免无关文档占据关键位置。
> 2. **关键信息放 prompt 末尾**：用户问题 → 检索内容 → 最后重述问题。
> 3. **控制上下文长度**：top-3~5 高质量 chunk > top-20 长上下文。
> 4. **结构化分隔**：用 `===`、`---` 等分隔符让模型区分段落。
> 5. **Multi-hop 检索**：复杂问题分多次检索，每次只关注一个子问题。
>
> **七、Prompt 设计技巧**
>
> ```
> [System Prompt]
> 
> [Background Context]
> ================
> [Document 1]
> ...
> [Document 10]
> ================
> 
> [Key Information] ← 把最相关放这里
> 
> [User Question] ← 最后重述问题
> ```
>
> 把"最相关文档"放在 key information 区，靠近 question，避开中间位置。
>
> **八、最新进展（2024-2026）**
>
> - **Claude 3 / 3.5**：Anthropic 通过专门训练显著缓解 Lost in Middle。
> - **Gemini 1.5/2.0**：2M 上下文，官方报告 Lost in Middle 已大幅改善。
> - **Llama 3.1**：128K 上下文，中间位置性能仍略弱。
> - **DeepSeek V3**：128K + MTP 训练，全范围性能均衡。
>
> 但**没有任何模型完全消除 Lost in Middle**，应用层仍需配合信息排布。
>
> **总结**：Lost in the Middle 是长上下文 LLM 的"顽疾"——**信息放中间被忽略，放两端被重视**。根因是 attention sink + 训练数据偏置。缓解靠"信息排布 + Rerank + Map-Reduce + Fine-tune"。即使是 SOTA 模型也只缓解未消除，应用层 prompt 工程仍是关键防线。

### 长上下文 vs RAG：什么场景用哪个？混合方案怎么设计？

> **答案**：
>
> 长上下文（128K-2M）和 RAG 都解决"模型不知道某信息"的问题，但思路完全不同。理解各自优劣，才能合理选型。
>
> **一、对比维度**
>
> | 维度          | 长上下文（直接塞 prompt）           | RAG（按需检索）                       |
> |---------------|-------------------------------------|---------------------------------------|
> | **实现成本**  | 低（无向量库）                      | 高（向量库 + Embedding + Reranker）   |
> | **响应延迟**  | 高（输入长 → TTFT 高）              | 低（只塞 top-K）                      |
> | **token 成本**| 高（每次输入全部）                  | 低（只输入相关）                      |
> | **数据规模**  | < 上下文窗口（128K-2M）             | 理论无限（TB 级）                     |
> | **检索精度**  | 中（Lost in Middle）                | 高（向量 + Rerank）                   |
> | **数据更新**  | 重新 prompt / 重训                  | 增量索引                              |
> | **多源融合**  | 难（多文档拼 prompt 易乱）          | 易（向量库原生支持）                  |
> | **可解释性**  | 低（不知模型看了哪段）              | 高（带引用）                          |
> | **维护**      | 简单                                | 复杂                                  |
>
> **二、什么时候选长上下文**
>
> 1. **数据量小（< 100K token）**：能直接塞进去，没必要建 RAG。
> 2. **任务需要全局推理**：跨章节对比、整体摘要、代码 review。
>    - 例：合同审阅（前后条款关联）。
>    - 例：100 页论文总结（跨章节提炼）。
> 3. **少量数据 + 高频调用**：prompt caching 让前缀复用，成本可控。
> 4. **数据更新慢**：一份文档反复问，不用每次检索。
> 5. **多模态**：长视频、多图分析（RAG 难处理多模态）。
>
> **典型场景**：
>
> - 法律合同审阅（Claude 200K）。
> - 代码库 review（Cursor、Claude Code）。
> - 论文 / 书籍总结。
> - 多轮长对话（持久上下文）。
>
> **三、什么时候选 RAG**
>
> 1. **数据量大（> 1M token）**：超过任何模型上下文窗口。
> 2. **数据更新频繁**：知识库每天 / 每小时变。
> 3. **多源异构**：PDF + 数据库 + API + 网页。
> 4. **高并发 + 低延迟**：客服、FAQ。
> 5. **可解释性要求**：金融、医疗、法律需要答案带引用。
> 6. **成本敏感**：每次只塞 top-K，省钱。
>
> **典型场景**：
>
> - 企业知识库客服。
> - 技术文档问答（产品手册）。
> - 法律 / 学术检索。
> - 电商商品搜索 + 问答。
>
> **四、长上下文的"隐藏成本"**
>
> 1. **token 成本爆炸**：
>    - 1M token 输入（Gemini 2.0）：~$7。
>    - 高频调用一天烧几千美元。
>
> 2. **延迟高**：
>    - 1M token TTFT 可能 30s+。
>    - 用户感知卡顿。
>
> 3. **质量衰减**：
>    - Lost in Middle 导致 100K 之后效果下降。
>    - 1M 实际有效记忆可能 200K。
>
> 4. **缓存救场**：
>    - Prompt caching 让前缀复用，输入成本降到 1/10。
>    - 适用场景：固定 system prompt + 重复知识库。
>
> **五、混合方案设计**
>
> **方案 A：RAG 召回 + 长上下文深度推理（推荐）**
>
> ```
> 用户问题
>     │
>     ▼
> [RAG 召回]：从海量数据找 top-K 相关
>     │
>     ▼
> [拼接]：top-K chunks + 用户问题 → 长上下文 LLM
>     │
>     ▼
> [深度推理]：LLM 基于召回内容做多步推理
> ```
>
> **优势**：兼具 RAG 的精准召回 + 长上下文的深度推理。
>
> **例**：法律咨询——RAG 召回 50 个相关条款 → Claude 200K 一次审完 → 给出综合建议。
>
> **方案 B：长上下文 + 持久记忆**
>
> ```
> 用户长期对话历史 → 摘要 → 长上下文 prompt
> 新问题 + 历史摘要 + 最近 N 轮 → LLM
> ```
>
> **优势**：避免每轮重新塞全部历史，prompt caching 让摘要复用。
>
> **方案 C：分块处理（MapReduce）**
>
> ```
> 长文档 → 切块
>     │
>     ▼ Map：每块单独问 LLM
> [chunk_1 → LLM → 中间结果_1]
> [chunk_2 → LLM → 中间结果_2]
> ...
>     │
>     ▼ Reduce：合并中间结果
> 最终答案
> ```
>
> **优势**：避免单次长上下文，可并行加速。
>
> **方案 D：分层检索**
>
> ```
> 第一层：粗排（BM25 / Embedding）→ top-100
> 第二层：精排（Cross-Encoder）→ top-10
> 第三层：长上下文 LLM → 答案
> ```
>
> **优势**：兼顾召回率和精度，避免长上下文信息过载。
>
> **六、实战决策树**
>
> ```
> 数据量 < 100K token？
>     │
>     ├─ 是 → 长上下文（简单直接）
>     │
>     └─ 否 → 数据更新频繁？
>             │
>             ├─ 是 → RAG（增量更新）
>             │
>             └─ 否 → 数据量 < 1M？
>                     │
>                     ├─ 是 → 长上下文 + Prompt Caching
>                     │
>                     └─ 否 → RAG + 长上下文（混合方案 A）
> ```
>
> **七、主流长上下文模型对比（2026）**
>
> | 模型             | 窗口     | 特点                                 |
> |------------------|----------|--------------------------------------|
> | Claude 3.5       | 200K     | 长上下文质量最高，Lost in Middle 弱 |
> | Gemini 2.0       | 2M       | 窗口最大，多模态强                   |
> | GPT-4o           | 128K     | 综合能力强，窗口适中                 |
> | Llama 3.1        | 128K     | 开源 SOTA，自部署友好                |
> | Qwen 2.5         | 128K     | 中文长上下文最佳                     |
> | DeepSeek V3      | 128K     | 性价比最高                           |
>
> **八、对应用工程的建议**
>
> 1. **默认走 RAG**：90% 业务场景 RAG + Instruct 模型够用，简单可靠。
> 2. **长上下文用于深度推理**：RAG 召回后，用长上下文模型做整合分析。
> 3. **Prompt Caching 必开**：长上下文不加 caching，成本爆炸。
> 4. **监控 token 消耗**：长上下文容易"塞太多"，监控每请求 token 数。
> 5. **评估用 Needle in Haystack**：自部署长上下文模型必测，避免"长度够但精度差"。
>
> **总结**：长上下文和 RAG **不是替代关系，而是互补**——长上下文深度强但成本高、规模受限；RAG 规模无限但召回精度受限。生产级 RAG 系统的最佳实践是 **RAG 召回 + 长上下文深度推理 + Prompt Caching** 的混合方案。理解这套权衡，才能为大模型应用选择最划算的架构。
