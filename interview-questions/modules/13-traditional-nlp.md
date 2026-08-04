# 传统 NLP 与词嵌入

> 来源：[面试鸭 · 最全 AI 大模型面试题库（含详细答案）](https://www.mianshiya.com/bank/1906189461556076546)
> 本模块共 22 题。完整索引见 [bank-llm-by-topic.md](../bank-llm-by-topic.md)。

---



> 共 22 题

### [请解释 LSTM 和 GRU 网络在处理序列数据中的应用。](https://www.mianshiya.com/bank/1906189461556076546/question/1821834674037624834)

> **答案**：
>
> **LSTM 和 GRU 在序列数据中的应用**
>
> LSTM / GRU 是 RNN 的改进版，专为**变长、有序、有时序依赖**的序列数据设计。
>
> **典型序列数据类型**
>
> **1. 文本（NLP）**
> - 句子、文档、对话
> - 词序敏感、长程依赖
>
> **2. 语音**
> - 音频信号（每秒 ~100 帧）
> - 上下文决定语义
>
> **3. 时间序列**
> - 股价、天气、传感器数据
> - 周期性 + 趋势 + 异常
>
> **4. 视频**
> - 帧序列
> - 时空依赖
>
> **5. 生物序列**
> - DNA、蛋白质
> - 长程 + 多模态
>
> **LSTM/GRU 的核心能力**
>
> 1. **处理变长**：序列可长可短
> 2. **捕捉顺序**：token 顺序敏感
> 3. **建模长程依赖**：cell state 保留远距离信息
> 4. **门控选择**：决定记什么、忘什么
>
> **典型应用场景**
>
> **1. 文本分类**
>
> ```
> Input: "I love NLP"  → [I, love, NLP]
> LSTM: 一步一步处理
> 取最后一步 h_T → 全连接 → softmax → 类别
> ```
>
> 例：情感分类、意图识别、垃圾邮件分类。
>
> **2. 序列标注**
>
> ```
> Input: "Beijing is the capital of China"
> LSTM: 每步输出 h_t
> 每步经 CRF/softmax → 标签
> Output: B-LOC O O O O O B-LOC
> ```
>
> 例：NER、POS tagging、Chunking。
> 经典模型：BiLSTM-CRF。
>
> **3. 机器翻译**
>
> ```
> Encoder LSTM：源句子 → 最后的 (h_T, C_T)
> Decoder LSTM：从 (h_T, C_T) 开始，自回归生成目标
> ```
>
> 经典 seq2seq + attention（Bahdanau 2014）。
>
> **4. 语言模型**
>
> ```
> Input: "The cat sat on the"
> LSTM: 逐步预测下一个 token
> Output: "mat"
> ```
>
> 例：早期语言模型（awd-lstm、ULMFiT）。
>
> **5. 语音识别**
>
> ```
> Input: 音频特征序列 (T, 80)  # MFCC 或 mel-spectrogram
> LSTM: 编码时序信息
> CTC Loss: 对齐到字符 / 音素
> Output: 文字
> ```
>
> 例：DeepSpeech、Listen-Attend-Spell。
>
> **6. 时间序列预测**
>
> ```
> Input: 过去 N 天股价 [p_1, ..., p_N]
> LSTM: 编码历史趋势
> Output: 预测 p_{N+1}, ..., p_{N+k}
> ```
>
> 例：股票、销量、电力负荷、交通流量。
>
> **7. 异常检测**
>
> ```
> Input: 传感器时间序列
> LSTM Autoencoder：重建输入
> 重建误差大 → 异常
> ```
>
> 例：工业设备、信用卡欺诈。
>
> **8. 文本生成**
>
> ```
> LSTM Language Model
> 逐步生成 token：
> "The" → "cat" → "sat" → "on" → "the" → "mat"
> ```
>
> 例：早期文本生成（在 GPT 之前）。
>
> **LSTM vs GRU 应用选择**
>
> | 场景 | 推荐 |
> |---|---|
> | 短序列 + 移动端 | GRU |
> | 长序列 + 高精度 | LSTM |
> | Encoder-Decoder | LSTM |
> | 简单分类 | GRU |
> | 实时低延迟 | GRU |
>
> **LSTM/GRU 应用的工程细节**
>
> **1. 双向（Bidirectional）**
> ```
> BiLSTM: 同时正向和反向
> 输出: [h_forward; h_backward]
> 理解类任务标配
> ```
>
> **2. 多层堆叠（Stacked）**
> ```
> LSTM layer 1 → LSTM layer 2 → ...
> num_layers = 2~4
> 增加表达力
> ```
>
> **3. Dropout**
> - 层间 dropout（防止过拟合）
> - 同层时间步 dropout（变分 dropout）
>
> **4. Attention 结合**
> - BiLSTM + Attention：早期 NLP 强配置
> - LSTM Encoder + Attention Decoder：seq2seq 标准架构
>
> **5. CRF 结合**
> - BiLSTM-CRF：NER 经典架构
> - LSTM 提供 token 表示，CRF 建模标签依赖
>
> **示例：BiLSTM-CRF 用于 NER**
>
> ```python
> class BiLSTM_CRF(nn.Module):
>     def __init__(self, vocab_size, tagset_size):
>         super().__init__()
>         self.embedding = nn.Embedding(vocab_size, 100)
>         self.lstm = nn.LSTM(100, 256, num_layers=2, bidirectional=True, batch_first=True)
>         self.hidden2tag = nn.Linear(512, tagset_size)
>         self.crf = CRF(tagset_size, batch_first=True)
>
>     def forward(self, x):
>         emb = self.embedding(x)
>         lstm_out, _ = self.lstm(emb)
>         emissions = self.hidden2tag(lstm_out)
>         return self.crf(emissions)
> ```
>
> **LSTM/GRU 的局限**
>
> 1. **训练慢**：必须按时间步串行
> 2. **长程仍弱**：cell state 也无法解决超过 1000 步的依赖
> 3. **容量有限**：参数量难以扩展
> 4. **被 Transformer 取代**：2017 后逐渐式微
>
> **现代场景**
>
> | 任务 | 主流 | LSTM/GRU 是否还用 |
> |---|---|---|
> | 文本分类 | BERT | 边缘场景还用 |
> | NER | BERT | 资源受限场景 |
> | 机器翻译 | Transformer | 几乎不用 |
> | 语言模型 | Transformer | 不用 |
> | 时间序列 | LSTM/GRU/Transformer | 仍主流 |
> | 语音识别 | Transformer/Conformer | 仍有用 |
>
> **总结**：LSTM 和 GRU 在序列数据中的应用极其广泛——**文本、语音、时间序列、视频、生物序列**都能用。**核心场景**：
> - **文本分类**（BiLSTM）
> - **序列标注**（BiLSTM-CRF）
> - **机器翻译**（seq2seq + attention）
> - **语音识别**（DeepSpeech）
> - **时间序列预测**（金融、IoT）
>
> 虽然 NLP 已被 Transformer 主导，但**时间序列、语音、资源受限场景**仍是 LSTM/GRU 的主场。理解 LSTM/GRU 的应用，对学习序列建模、设计工业级时序系统非常重要。

### [LLaMA 有哪些实际应用？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834678903017474)

> **答案**：
>
> **LLaMA 的实际应用**
>
> LLaMA 作为开源 LLM 的里程碑，催生了海量应用，覆盖**消费级、企业级、垂直领域**等多个层面。
>
> **1. 对话助手 / Chatbot**
>
> **应用**：
> - 客服机器人
> - 个人助手
> - 知识问答
>
> **典型方案**：
> - Llama 3 8B / 70B Instruct
> - 配合 vLLM / Ollama 部署
> - 接入 RAG 提供领域知识
>
> **例子**：
> - OpenAssistant（开源 ChatGPT 替代）
> - LocalAI（本地 LLM 服务）
> - LibreChat（自托管 ChatGPT）
>
> **2. 代码生成 / 辅助编程**
>
> **应用**：
> - 代码补全
> - 代码解释
> - Bug 修复
> - 代码 review
>
> **典型方案**：
> - **Code Llama**（Meta 基于 Llama 微调）
> - **CodeLlama-Instruct**：指令跟随
> - **CodeLlama-Python**：Python 专用
> - **Phind**：搜索 + 代码生成
> - **Continue.dev**：开源 Copilot 替代
>
> **例子**：
> - GitHub Copilot 替代：Tabby、Refact、Promptloop
> - 本地代码助手：Ollama + CodeLlama
>
> **3. 内容创作**
>
> **应用**：
> - 文案撰写（营销、博客、SEO）
> - 小说 / 剧本辅助
> - 翻译
> - 摘要
>
> **典型方案**：
> - Llama 3 70B 创意写作
> - 配合 prompt engineering
>
> **例子**：
> - NovelAI 类应用
> - Notion AI 类插件
> - 营销自动化（Jasper 类）
>
> **4. 教育 / 学习辅助**
>
> **应用**：
> - 个性化辅导
> - 自动出题
> - 论文 / 作业反馈
> - 编程学习
>
> **典型方案**：
> - Llama 3 8B 部署到学校本地（隐私）
> - Tutor 模式（苏格拉底式提问）
>
> **例子**：
> - Khanmigo 替代（开源版）
> - 多语言教育助手
>
> **5. 搜索 / 问答**
>
> **应用**：
> - 知识库问答
> - 企业内部搜索
> - 文档对话
>
> **典型方案**：
> - **Llama 3 + RAG**
> - 配合 LangChain / LlamaIndex
> - 向量数据库（Chroma、Milvus）
>
> **例子**：
> - Dify（开源 AI 应用开发）
> - Flowise（可视化 LLM 流）
> - Quivr（第二大脑）
>
> **6. 客服 / 呼叫中心**
>
> **应用**：
> - 智能客服
> - 工单分类
> - 转人工判断
> - 对话摘要
>
> **典型方案**：
> - Llama 3 8B（低成本）或 70B（高质量）
> - 配合 Function Calling
> - 接入 CRM / 工单系统
>
> **例子**：
> - 自托管客服（替代 ChatGPT API）
> - 多租户客服平台
>
> **7. 数据分析 / BI**
>
> **应用**：
> - Text-to-SQL
> - 数据洞察
> - 报告生成
>
> **典型方案**：
> - Llama 3 + Function Calling
> - 接入数据库 / BI 工具
>
> **例子**：
> - Text2SQL 开源方案（DB-GPT、Vanna）
> - 自然语言 BI（Open Interpreter）
>
> **8. 多语言 / 跨语言**
>
> **应用**：
> - 多语言客服
> - 翻译
> - 跨语言搜索
>
> **典型方案**：
> - **Chinese-LLaMA**（中文优化）
> - **Llama 3 多语言**（128K 词表）
> - 配合 LaBSE / LASER embedding
>
> **例子**：
> - 中文 LLM 应用
> - 多语言内容审核
>
> **9. 垂直领域应用**
>
> **医疗**：
> - 病历摘要
> - 医学问答
> - Med-PaLM 替代
>
> **法律**：
> - 合同审查
> - 法律问答
> - 案例检索
>
> **金融**：
> - 财报分析
> - 风险评估
> - 投资研究
>
> **科研**：
> - 论文摘要
> - 文献综述
> - 实验设计
>
> **典型方案**：在领域数据上继续预训练 / SFT。
>
> **10. 嵌入式 / 边缘部署**
>
> **应用**：
> - 手机本地 LLM
> - 车载助手
> - IoT 设备
>
> **典型方案**：
> - Llama 3 8B 量化（int4 / GGUF）
> - llama.cpp / Ollama 部署
> - 离线运行
>
> **例子**：
> - LM Studio
> - MLC LLM
> - 手机端本地助手
>
> **11. Agent / 自动化**
>
> **应用**：
> - 自动化工作流
> - 多 Agent 协作
> - 代码执行 / 工具调用
>
> **典型方案**：
> - Llama 3 + Function Calling + ReAct
> - LangGraph / AutoGen
>
> **例子**：
> - AutoGen
> - CrewAI
> - MetaGPT
> - BabyAGI
>
> **12. 多模态应用**
>
> **应用**：
> - 图文理解
> - OCR + 文档对话
> - 视觉问答
>
> **典型方案**：
> - **Llama 3.2 Vision**（11B / 90B）
> - **LLaVA**（Llama + ViT）
>
> **例子**：
> - 文档分析（发票、合同）
> - 图像描述
> - 视觉问答
>
> **13. 内容审核 / 风控**
>
> **应用**：
> - 违规内容识别
> - 垃圾过滤
> - 欺诈检测
>
> **典型方案**：
> - Llama 3 + few-shot prompt
> - 替代传统分类器
>
> **14. 智能写作辅助**
>
> **应用**：
> - 语法纠错
> - 文风改写
> - 润色
>
> **典型方案**：
> - Llama 3 8B 实时辅助
> - IDE / 浏览器插件
>
> **15. 游戏与娱乐**
>
> **应用**：
> - NPC 对话
> - 故事生成
> - 角色扮演
>
> **典型方案**：
> - Llama 3 + 角色设定
> - 长期记忆系统
>
> **LLaMA 部署方式**
>
> | 方式 | 适合 |
> |---|---|
> | 云端 API（Together、Groq、Replicate） | 快速验证、无运维 |
> | 自托管（vLLM、SGLang） | 生产、私有数据 |
> | 本地（Ollama、llama.cpp） | 个人、隐私敏感 |
> | 边缘（GGUF 量化） | 嵌入式、移动 |
>
> **LLaMA 应用栈**
>
> ```
> 应用层：聊天 / 代码 / RAG / Agent
>    ↓
> 框架层：LangChain / LlamaIndex / Dify / Flowise
>    ↓
> 推理层：vLLM / SGLang / TGI / Ollama
>    ↓
> 模型层：Llama 3 8B / 70B / 405B / Vision
>    ↓
> 硬件层：NVIDIA GPU / Apple Silicon / CPU
> ```
>
> **总结**：LLaMA 的实际应用极其广泛：
> - **消费级**：本地助手、写作辅助、学习辅导
> - **企业级**：客服、搜索、BI、知识管理
> - **垂直领域**：医疗、法律、金融、科研
> - **多模态**：图文理解、文档对话
> - **Agent**：自动化、多 Agent 协作
>
> **生态**：Meta 出模型，社区出工具（vLLM、Ollama、LangChain、Dify），企业出应用。Llama 已成为开源 LLM 的事实标准，催生万亿规模的 AI 应用生态。理解 Llama 的应用，对设计 LLM 产品、规划 AI 战略非常重要。

### [LSTM 和 GRU 有什么区别？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834673773383682)

> **答案**：
>
> **LSTM 和 GRU 的区别**
>
> LSTM（1997）和 GRU（2014）都是改进的 RNN，目标都是解决长程依赖。GRU 是 LSTM 的**简化版**，参数更少、效果相当。
>
> **架构对比**
>
> **LSTM（4 个公式，3 个门 + 1 个候选）**
> ```
> f_t = σ(W_f · [h_{t-1}, x_t])  # 遗忘门
> i_t = σ(W_i · [h_{t-1}, x_t])  # 输入门
> C̃_t = tanh(W_c · [h_{t-1}, x_t])  # 候选
> o_t = σ(W_o · [h_{t-1}, x_t])  # 输出门
>
> C_t = f_t · C_{t-1} + i_t · C̃_t  # cell state 更新
> h_t = o_t · tanh(C_t)  # 输出
> ```
>
> **GRU（3 个公式，2 个门 + 1 个候选）**
> ```
> z_t = σ(W_z · [h_{t-1}, x_t])  # 更新门（合并遗忘+输入）
> r_t = σ(W_r · [h_{t-1}, x_t])  # 重置门
> h̃_t = tanh(W · [r_t · h_{t-1}, x_t])  # 候选
>
> h_t = (1 - z_t) · h_{t-1} + z_t · h̃_t  # 直接更新 hidden state
> ```
>
> **关键区别**
>
> | 维度 | LSTM | GRU |
> |---|---|---|
> | 门数量 | 3（遗忘/输入/输出） | 2（更新/重置） |
> | 状态变量 | 2（C_t, h_t） | 1（h_t） |
> | 候选计算 | 不依赖门 | 依赖重置门 r_t |
> | 输出门 | 有 | 无 |
> | 参数量 | 4·(d_x+d_h)·d_h | 3·(d_x+d_h)·d_h |
> | 计算量 | 高 | 低（约 75%） |
> | 训练速度 | 慢 | 快 |
> | 长序列 | 略好 | 略差 |
> | 短序列 | 略冗余 | 更优 |
> | 主流（NLP） | 双向 BiLSTM 常见 | 也常用 |
>
> **核心简化**
>
> **1. 合并状态**
> - LSTM：C_t（长期） + h_t（短期）
> - GRU：只有 h_t，承担两个角色
>
> **2. 合并门**
> - LSTM 的遗忘门 f_t 和输入门 i_t 是独立的
> - GRU 的更新门 z_t 同时控制：
>   - `(1-z_t)`：保留旧 h_{t-1}（等价于遗忘门）
>   - `z_t`：写入新 h̃_t（等价于输入门）
>   - 互补：`f_t + i_t = 1` 强制
>
> **3. 去掉输出门**
> - LSTM 的输出门 o_t 控制"从 cell state 读多少到 h_t"
> - GRU 没有这层，h_t 直接是输出
> - 简化但损失一些灵活性
>
> **4. 重置门的作用**
> - GRU 的 r_t 控制"算候选 h̃_t 时，要不要用旧 h_{t-1}"
> - r_t = 0：完全忘记过去，h̃_t 只看 x_t
> - r_t = 1：完全用过去，相当于标准 RNN
> - 类似 LSTM 遗忘门 + 输入门的组合效果
>
> **性能对比**
>
> Chung et al. 2014 实验对比 LSTM vs GRU：
>
> - **音乐建模、语音信号**：LSTM 略好
> - **机器翻译、NLP 任务**：基本持平
> - **计算资源受限**：GRU 更优
> - **长序列（>500）**：LSTM 略好（cell state 更稳定）
>
> 结论：**多数任务两者相当，GRU 是更好的"性价比"选择**。
>
> **何时用 LSTM**
>
> - 序列很长（>500 步）
> - 需要稳定的长期记忆
> - 复杂任务（如机器翻译的 Encoder/Decoder）
> - 不在乎参数量
>
> **何时用 GRU**
>
> - 序列较短
> - 计算资源受限
> - 快速迭代实验
> - 简单任务（分类、序列标注）
>
> **代码示例**
>
> PyTorch：
> ```python
> # LSTM
> lstm = nn.LSTM(input_size, hidden_size, num_layers, bidirectional=True)
> output, (h_n, c_n) = lstm(x)
>
> # GRU
> gru = nn.GRU(input_size, hidden_size, num_layers, bidirectional=True)
> output, h_n = gru(x)  # 没有 c_n
> ```
>
> **实际工程**
>
> 2014-2018（Transformer 之前）：
> - 机器翻译：LSTM（OpenNMT）和 GRU（GNMT）都常见
> - 情感分析：BiLSTM 是标配
> - 序列标注：BiLSTM-CRF 是 NER 标配
>
> 2018+（Transformer 时代）：
> - BERT、GPT 取代 LSTM 和 GRU
> - 但小模型 / 资源受限场景仍用 GRU
> - Mamba、RWKV 等新架构继承 GRU 思想（线性 RNN）
>
> **特殊情况**
>
> **双向 LSTM/GRU（BiLSTM / BiGRU）**
> - 同时正向和反向处理
> - 输出拼接：[h_forward; h_backward]
> - 性能提升明显（理解类任务）
> - ELMO 就是多层 BiLSTM 堆叠
>
> **LSTM/GRU 在大模型时代的角色**
>
> - **不再主流**：被 Transformer 取代
> - **小模型场景**：嵌入式、移动端、IoT 仍用
> - **状态空间模型（SSM）的灵感**：Mamba、S4 用类似 GRU 的设计但并行化
> - **历史价值**：理解门控、记忆机制的基础
>
> **总结**：LSTM 和 GRU 都是 RNN 的改进，**核心区别**：
> - **LSTM**：3 门 + 2 状态（C_t, h_t），更灵活，参数多
> - **GRU**：2 门 + 1 状态（h_t），更简洁，参数少 25%
>
> **性能相当**，**GRU 性价比更高**。在 Transformer 时代已被取代，但理解它们的门控思想对学习现代架构（Mamba、RWKV）非常重要。

### [说说 LSTM 的基本原理。](https://www.mianshiya.com/bank/1906189461556076546/question/1821834671391019010)

> **答案**：
>
> **LSTM 的基本原理**
>
> LSTM（Long Short-Term Memory，长短期记忆网络）= Hochreiter & Schmidhuber 1997 提出的**改进版 RNN**，专门解决长序列梯度消失 / 爆炸问题。
>
> **核心问题：RNN 的痛点**
>
> 标准 RNN：
> ```
> h_t = tanh(W_x · x_t + W_h · h_{t-1} + b)
> ```
>
> 问题：
> - 长序列下，梯度连乘多次 → 消失或爆炸
> - 长程依赖弱（前面信息传不远）
> - 实际能记住 < 100 步
>
> **LSTM 的核心创新：Cell State + 门控机制**
>
> LSTM 引入一条**独立的"记忆带"** cell state `C_t`，配合 3 个"门"控制信息流。
>
> ```
> C_t = f_t · C_{t-1} + i_t · C̃_t   (cell state 更新)
> h_t = o_t · tanh(C_t)              (hidden state 输出)
> ```
>
> **3 个门**
>
> **1. 遗忘门（Forget Gate）** `f_t`
> ```
> f_t = σ(W_f · [h_{t-1}, x_t] + b_f)
> ```
> - 决定 cell state 中哪些信息要**遗忘**
> - 输出 0-1：0 全忘，1 全保留
> - 例：处理"她"时，遗忘前面的"他"
>
> **2. 输入门（Input Gate）** `i_t`
> ```
> i_t = σ(W_i · [h_{t-1}, x_t] + b_i)
> C̃_t = tanh(W_c · [h_{t-1}, x_t] + b_c)
> ```
> - 决定哪些**新信息**写入 cell state
> - i_t：写入多少
> - C̃_t：候选新内容
>
> **3. 输出门（Output Gate）** `o_t`
> ```
> o_t = σ(W_o · [h_{t-1}, x_t] + b_o)
> h_t = o_t · tanh(C_t)
> ```
> - 决定 cell state 中哪些信息**输出**为 hidden state
>
> **为什么 LSTM 能解决梯度消失**
>
> **关键：Cell state 的加法更新**
>
> ```
> C_t = f_t · C_{t-1} + i_t · C̃_t
> ```
>
> - 反向传播时，梯度 `∂C_t/∂C_{t-1} = f_t`（不是连乘）
> - 只要 f_t ≈ 1，梯度可以直接流过
> - 没有 tanh / sigmoid 的连乘衰减
>
> 对比 RNN：
> ```
> h_t = tanh(...)  ← 连乘多次后梯度消失
> ```
>
> LSTM 的 cell state 就像"**高速公路**"，让信息和梯度直接流过长序列。
>
> **LSTM 完整流程**
>
> 输入序列 `x_1, x_2, ..., x_T`：
> ```
> 对每个时间步 t：
>     组合输入：[h_{t-1}, x_t]
>     算 3 个门：f_t, i_t, o_t = gates([h_{t-1}, x_t])
>     算候选：C̃_t = tanh(...)
>     更新 cell：C_t = f_t · C_{t-1} + i_t · C̃_t
>     算输出：h_t = o_t · tanh(C_t)
> ```
>
> 输出：每个时间步的 `h_t`（用于下游任务）。
>
> **LSTM 的应用**
>
> - 机器翻译（早期 seq2seq + attention）
> - 文本分类（取最后一个 h_T）
> - 语言模型
> - 时间序列预测
> - 语音识别
> - 音乐生成
>
> **LSTM 的变体**
>
> - **GRU**（2014）：合并遗忘门和输入门，简化版
> - **Peephole LSTM**：门控也看 cell state
> - **Coupled Input-Forget Gate**：f_t + i_t = 1
> - **Bidirectional LSTM**（BiLSTM）：双向处理
>
> **LSTM vs Transformer**
>
> | 维度 | LSTM | Transformer |
> |---|---|---|
> | 并行 | ✗（串行） | ✓（全并行） |
> | 长程依赖 | 中（依赖 cell state） | 强（O(1) 距离） |
> | 训练速度 | 慢 | 快 |
> | 推理效率 | O(1) hidden state | O(N) KV cache |
> | 主流 | 已被 Transformer 取代 | 主流 |
>
> **总结**：LSTM = **RNN + cell state + 3 个门（遗忘/输入/输出）**。**核心创新：cell state 加法更新 → 梯度高速公路 → 缓解长程梯度消失**。是 1997-2017 的序列建模王者，2017 后被 Transformer 取代。但理解 LSTM 仍是理解"门控"和"记忆机制"的基础，也启发后续 GRU、Mamba（用类似的 state space）等设计。

### [chatGLM 和 GPT 在结构上有什么区别？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834700398825474)

> **答案**：
>
> **ChatGLM 和 GPT 在结构上的区别**
>
> ChatGLM 是清华 + 智谱 AI 的中文 LLM 系列；GPT 是 OpenAI 的开创性 LLM 系列。两者都是 Decoder-only Transformer，但有几个关键结构差异。
>
> **核心结构对比**
>
> | 维度 | GPT 系列 | ChatGLM |
> |---|---|---|
> | 架构 | Decoder-only Transformer | GLM（General Language Model）架构 |
> | 注意力 | Causal self-attention | **双向 attention + 自回归生成**（Prefix LM） |
> | 位置编码 | Learned（GPT-2）/ RoPE（部分） | **RoPE** |
> | LayerNorm | LayerNorm（GPT-2） | **RMSNorm**（ChatGLM2/3）|
> | 激活 | GeLU | **SwiGLU**（ChatGLM2/3）|
> | 归一化位置 | Pre-LN | Pre-LN |
> | Mask 策略 | Causal | **Prefix LM**（前缀双向 + 生成 causal）|
> | 词表 | BPE 50K-100K | **SentencePiece** 65K-130K |
> | 训练目标 | Next token prediction | **Auto-regressive blank infilling** |
>
> **1. 核心差异：GLM 架构 vs 标准 Decoder**
>
> **GPT（标准 Decoder-only）**：
> - 整个序列 causal mask
> - 训练目标：predict next token
> - 单向
>
> **ChatGLM（GLM 架构）**：
> - **Prefix LM**：前缀部分双向，生成部分 causal
> - 训练目标：**Blank Infilling**（掩码填充）
> - 结合 BERT 和 GPT 的优点
>
> **GLM 的训练目标**：
>
> ```
> Input:  Part A (双向) || Part B (causal)
>        "I love NLP"  || "[MASK] is great [MASK]"
> Output:              ||  "NLP"         "!"
>
> Part A 用双向 attention（理解上下文）
> Part B 用 causal mask（自回归生成）
> ```
>
> 这种设计让 ChatGLM 同时擅长**理解（像 BERT）和生成（像 GPT）**。
>
> **2. 注意力 Mask 区别**
>
> ```
> GPT（全 causal）：
> [1 0 0 0 0]
> [1 1 0 0 0]
> [1 1 1 0 0]
> [1 1 1 1 0]
> [1 1 1 1 1]
>
> ChatGLM（Prefix LM，前 3 个 token 双向）：
> [1 1 1 0 0]
> [1 1 1 0 0]
> [1 1 1 0 0]
> [1 1 1 1 0]
> [1 1 1 1 1]
> ```
>
> **3. 版本演化**
>
> **GPT 系列**：
> - GPT-1（2018）：标准 Transformer Decoder，117M
> - GPT-2（2019）：1.5B，Pre-LN
> - GPT-3（2020）：175B，Sparse Attention
> - InstructGPT（2022）：+ RLHF
> - GPT-4（2023）：MoE（传闻），多模态
> - GPT-4o（2024）：原生多模态
>
> **ChatGLM 系列**：
> - GLM-130B（2022）：Prefix LM，130B
> - ChatGLM-6B（2023）：6B，开源
> - ChatGLM2-6B（2023）：+ RoPE + SwiGLU + RMSNorm + FlashAttention
> - ChatGLM3-6B（2023）：+ Function Calling + 工具使用
> - GLM-4（2024）：对标 GPT-4，多模态，开源 GLM-4-9B
>
> **4. 关键技术对比**
>
> **位置编码**：
> - GPT-2/3：learned positional encoding
> - ChatGLM：RoPE（旋转位置编码，从 ChatGLM2 开始）
>
> **LayerNorm**：
> - GPT：标准 LayerNorm
> - ChatGLM2/3：RMSNorm（更高效）
>
> **激活函数**：
> - GPT：GeLU
> - ChatGLM2/3：SwiGLU（性能更强）
>
> **FFN 维度**：
> - GPT：4 × d_model
> - ChatGLM：适配 SwiGLU，2/3 × 4 × d_model
>
> **Attention 优化**：
> - GPT-3：Sparse Attention（部分 head）
> - ChatGLM2/3：Multi-Query Attention / Multi-Head Attention（按规模选）
>
> **5. 训练数据与目标**
>
> **GPT**：
> - 数据：Web text、Books、Code、对话
> - 训练：next token prediction
> - 数据量：300B-3T tokens
>
> **ChatGLM**：
> - 数据：中英文 + 多语言
> - 训练：
>   1. **Pretrain**：Blank Infilling（GLM 特有）
>   2. **SFT**：指令数据
>   3. **RLHF**：人类反馈
> - 数据量：1T+ tokens
>
> **6. 应用导向**
>
> **GPT**：
> - 通用任务
> - 英文为主
> - 闭源（API 服务）
> - 强调通用智能
>
> **ChatGLM**：
> - 中英文双语（中文优化）
> - 部分开源（6B 系列）
> - 强调中文场景
> - 中国合规
>
> **7. 性能对比（GLM-4 vs GPT-4）**
>
> GLM-4 接近 GPT-4 水平：
> - MMLU：GPT-4 86，GLM-4 84
> - C-Eval（中文）：GLM-4 略胜
> - GSM8K：GPT-4 92，GLM-4 90
> - HumanEval：GPT-4 88，GLM-4 87
>
> **总结**：ChatGLM 和 GPT 都是 Decoder-only Transformer，但有关键差异：
> - **核心架构**：ChatGLM 用 **GLM（Prefix LM + Blank Infilling）**，GPT 用**标准 Decoder + Next Token**
> - **训练目标**：ChatGLM 兼顾理解 + 生成（BERT + GPT 优点），GPT 纯生成
> - **技术栈**：ChatGLM 用 RoPE / RMSNorm / SwiGLU（与 Llama 一致），GPT-2/3 用 learned PE / LayerNorm / GeLU
> - **数据**：ChatGLM 中英双语，GPT 英文为主
> - **开源**：ChatGLM 部分开源（6B），GPT 完全闭源
> - **应用**：ChatGLM 中国场景强，GPT 全球通用
>
> ChatGLM 是中国最早的高质量开源 LLM 之一，对推动国内 LLM 生态有重大意义。理解两者的差异，对选型（中文场景用 ChatGLM / GLM / Qwen，国际场景用 GPT / Claude）和架构理解都很有帮助。

### [解释一个 LSTM 单元（LSTM cell）的基本组成，以及它们各自的作用。](https://www.mianshiya.com/bank/1906189461556076546/question/1821834673223929858)

> **答案**：
>
> **LSTM 单元的组成与作用**
>
> LSTM cell 是 LSTM 网络的基本计算单元，每个时间步处理一个输入并更新状态。
>
> **LSTM Cell 的 5 个核心组件**
>
> ```
>                 ┌─────────────── cell state C_t ───────────────┐
>                 ↓                                              │
> 输入 x_t ──→  [遗忘门 f_t]                                      │
>               [输入门 i_t]                                      │
>               [候选 C̃_t ]                                      │
>               [输出门 o_t]                                      │
>                                                               ↓
>                               output h_t ─→ 下一时间步 / 下游任务
> ```
>
> **1. 遗忘门（Forget Gate, f_t）**
>
> ```
> f_t = σ(W_f · [h_{t-1}, x_t] + b_f)  ∈ (0, 1)^d
> ```
>
> **作用**：决定 cell state 中**哪些信息要保留 / 遗忘**。
>
> - 看 h_{t-1}（上一步输出）和 x_t（当前输入）
> - 输出 0-1 的向量（每个维度一个权重）
> - 0 = 全部遗忘，1 = 全部保留
>
> **例**：处理句子的第二个主语时，遗忘前一个主语信息。
>
> **2. 输入门（Input Gate, i_t）**
>
> ```
> i_t = σ(W_i · [h_{t-1}, x_t] + b_i)  ∈ (0, 1)^d
> ```
>
> **作用**：决定**哪些新信息要写入 cell state**。
>
> - 控制写入"开关"
> - 0 = 不写，1 = 全写
>
> **3. 候选内容（Candidate, C̃_t）**
>
> ```
> C̃_t = tanh(W_c · [h_{t-1}, x_t] + b_c)  ∈ (-1, 1)^d
> ```
>
> **作用**：生成**新的候选信息**，可能被写入 cell state。
>
> - tanh 输出 -1 到 1（有正有负）
> - 配合 i_t 决定写入多少
>
> **4. Cell State 更新（C_t）**
>
> ```
> C_t = f_t ⊙ C_{t-1} + i_t ⊙ C̃_t
> ```
>
> **作用**：**记忆核心**，长期信息的载体。
>
> - `f_t ⊙ C_{t-1}`：保留的旧信息
> - `i_t ⊙ C̃_t`：写入的新信息
> - ⊙ 是 element-wise 乘
>
> **核心特性**：加法更新 → 梯度直接流 → 解决长程梯度消失。
>
> **5. 输出门（Output Gate, o_t）**
>
> ```
> o_t = σ(W_o · [h_{t-1}, x_t] + b_o)  ∈ (0, 1)^d
> h_t = o_t ⊙ tanh(C_t)
> ```
>
> **作用**：决定 cell state 中**哪些信息输出为 hidden state**。
>
> - cell state 内部保留所有
> - 但只输出 o_t 选择的部分
>
> **完整公式汇总**
>
> ```
> combined = [h_{t-1}, x_t]  # 拼接
>
> f_t = σ(W_f · combined + b_f)
> i_t = σ(W_i · combined + b_i)
> C̃_t = tanh(W_c · combined + b_c)
> o_t = σ(W_o · combined + b_o)
>
> C_t = f_t ⊙ C_{t-1} + i_t ⊙ C̃_t
> h_t = o_t ⊙ tanh(C_t)
> ```
>
> **LSTM Cell 的输出**
>
> 每个时间步输出：
> - `h_t`：hidden state（短期记忆 + 输出）
> - `C_t`：cell state（长期记忆，内部）
> - 都传递到下一个时间步
>
> 下游任务用 `h_t`：
> - 取最后一步 `h_T`：分类（如情感分析）
> - 取所有步 `[h_1, ..., h_T]`：序列标注（如 NER）
> - attention 加权：编码器表示
>
> **举例：处理句子 "I love NLP"**
>
> ```
> t=1: x_1 = "I"
>     h_0 = 0, C_0 = 0
>     → f_1, i_1, C̃_1, o_1 → C_1, h_1（开始记忆 "I"）
>
> t=2: x_2 = "love"
>     → 用 h_1, x_2 算新门
>     → C_2 = f_2·C_1 + i_2·C̃_2（加入 "love" 信息）
>     → h_2 输出
>
> t=3: x_3 = "NLP"
>     → 用 h_2, x_3 算新门
>     → C_3, h_3（完整表示 "I love NLP"）
> ```
>
> **参数量**
>
> LSTM cell 参数：
> - W_f, W_i, W_c, W_o：每个 (d_h + d_x) × d_h
> - 偏置 b_f, b_i, b_c, b_o：d_h
> - 总计：4 × (d_h + d_x) × d_h + 4 × d_h
>
> 例：d_x = d_h = 256
> - 4 × 512 × 256 + 4 × 256 = 525,312 ≈ 0.5M 参数
>
> **LSTM 的工程实现**
>
> PyTorch：
> ```python
> lstm = nn.LSTM(input_size=256, hidden_size=256, num_layers=2, bidirectional=True, batch_first=True)
> output, (h_n, c_n) = lstm(x)
> # output: (B, T, 2 * hidden) 双向
> # h_n: (num_layers * 2, B, hidden)
> # c_n: 同 h_n
> ```
>
> 实际实现把 4 个门的矩阵合并成一个大矩阵乘法，加速。
>
> **与 GRU 的对比**
>
> GRU（Cho et al. 2014）是 LSTM 的简化版：
> - 合并 cell state 和 hidden state
> - 合并遗忘门和输入门成"更新门"
> - 少一个门，参数少 25%
> - 效果相当，更快
>
> **总结**：LSTM cell = **3 个门（遗忘/输入/输出）+ 候选 + cell state 更新 + hidden state 输出**。每个组件有明确职责：
> - 遗忘门：丢弃旧信息
> - 输入门 + 候选：写入新信息
> - cell state：长期记忆（加法 → 抗梯度消失）
> - 输出门：选择性输出
> 这是 RNN 时代最经典的单元设计，启发了 GRU、Highway Network、ResNet（残差）等后续工作。

### [LLaMA 模型中，输入句子的长度理论上是否可以无限长？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834678357757954)

> **答案**：
>
> **LLaMA 输入句子的长度理论上是否可以无限长**
>
> **理论上不能，工程上可大幅扩展**。
>
> **理论上限 vs 实际限制**
>
> **理论上**：
> - Transformer 架构本身没"绝对长度限制"
> - 只要显存够，attention 能算任意长度
> - RoPE 位置编码也能算任意位置
>
> **实际上**：受多个因素限制。
>
> **限制 1：训练时的上下文窗口**
>
> Llama 1/2 训练时上下文 = 2048 / 4096
> Llama 3 训练时上下文 = 8K
>
> 模型从未见过更长的序列，超出窗口行为不可预测。
>
> **限制 2：位置编码外推**
>
> RoPE 虽然理论上能处理任意位置，但**训练长度外效果下降**：
> - 远距离 cos/sin 振荡
> - attention 分数可能崩塌
> - 长 context 性能急剧下降
>
> **Llama 1/2**：超出 4K 后效果显著退化。
> **Llama 3**：训练 8K，原生 + 128K（YaRN）。
>
> **限制 3：计算复杂度**
>
> Attention 复杂度 O(N²)：
> - N=4K：1.6e7 操作
> - N=32K：1e9 操作（64 倍）
> - N=128K：1.6e10 操作（1000 倍）
> - N=1M：1e12 操作（64000 倍）
>
> 长 context 训练 / 推理极贵。
>
> **限制 4：显存**
>
> KV cache 大小 ∝ N：
>
> | 模型 | 上下文 N | KV Cache（bf16） |
> |---|---|---|
> | Llama 7B | 4K | 4 GB |
> | Llama 7B | 32K | 32 GB |
> | Llama 7B | 128K | 128 GB |
> | Llama 70B | 32K | 160 GB |
> | Llama 70B | 128K | 640 GB |
>
> **长 context 的显存比模型本身还大**。
>
> **限制 5：信息丢失**
>
> 研究表明：
> - 即使支持长 context，模型也倾向"关注开头和结尾"
> - "Lost in the Middle" 问题
> - 中间的信息容易被忽略
>
> **扩展上下文的方法**
>
> 虽然不能"无限长"，但可以大幅扩展：
>
> **1. Position Interpolation（PI）**
>
> 简单缩放位置索引：
> ```
> m → m · (train_len / target_len)
> ```
>
> 例：训练 4K，扩到 32K → 位置除以 8
> - 简单
> - 效果一般
>
> **2. NTK-aware RoPE Scaling**
>
> 调整 RoPE 基础频率：
> ```
> base = 10000 · α^(d/(d-2))
> ```
>
> - 不同频率不同缩放
> - 外推更平滑
> - Code Llama、Llama 2 Long 用
>
> **3. YaRN（Yet another RoPE extensioN）**
>
> 分段缩放：
> - 高频部分（局部）保留
> - 低频部分（全局）缩放
> - 效果最好
> - Llama 3 128K、Qwen2 128K 用
>
> **4. LongRoPE**
>
> 进化算法搜索最佳缩放：
> - 不同维度不同策略
> - 支持 2M context
> - Microsoft 2024
>
> **5. 长上下文 fine-tuning**
>
> 在长数据上继续预训练 / 微调：
> - 用长文档数据
> - 让模型适应长 context
>
> **6. KV Cache 优化**
>
> 减少显存压力：
> - GQA / MQA / MLA
> - PagedAttention
> - 量化（fp8、int4）
>
> **7. Sliding Window / Sparse Attention**
>
> 不全局 attention：
> - Mistral 8K 滑窗
> - Longformer 模式
> - 实际支持更长 context
>
> **当前主流 LLM 的上下文**
>
> | 模型 | 上下文 |
> |---|---|
> | Llama 2 | 4K |
> | Llama 3 | 8K（原生）/ 128K（YaRN） |
> | Qwen 2 | 128K |
> | DeepSeek V2/V3 | 128K |
> | Mistral | 8K 滑窗 |
> | Claude 3.5 | 200K |
> | Gemini 1.5 Pro | 1M |
> | GPT-4 Turbo | 128K |
> | GPT-4o | 128K |
> | Yarn-Llama 2 | 64K-128K |
> | LongChat | 32K |
>
> **"无限长"的工程方案**
>
> 虽然单模型有限，但工程上能处理"无限长"输入：
>
> **1. 分块处理（Chunking）**
> - 文档切多个 chunk
> - 分别处理
> - 聚合结果（map-reduce）
>
> **2. 检索增强（RAG）**
> - 文档存向量数据库
> - 检索 top-K 相关 chunk 喂给模型
> - "外部记忆"
>
> **3. 滚动窗口 + 摘要**
> - 处理一段 → 摘要
> - 摘要 + 下一段 → 新摘要
> - 累积信息
>
> **4. Agent + 记忆系统**
> - 长期记忆库
> - 工作记忆（当前 context）
> - 自动检索 / 写入
>
> **结论**
>
> **理论上**：LLaMA 没有绝对长度限制，attention 架构能算任意长度。
> **实际上**：
> - 受训练上下文窗口限制
> - 受位置编码外推限制
> - 受计算 / 显存限制
> - 受信息利用（lost in middle）限制
>
> **现代方法**：
> - YaRN / LongRoPE 扩到 128K-2M
> - GQA / MLA 减显存
> - FlashAttention 加速
> - 分块 / RAG / Agent 处理"无限长"
>
> **总结**：LLaMA 输入长度**理论上可以无限长，实际上有多个工程限制**。**当前主流**：128K（Llama 3 / Qwen 2 / DeepSeek V3）- 1M（Gemini 1.5）。**超长方案**：YaRN / LongRoPE 扩展位置编码 + GQA 减显存 + FlashAttention 加速。**"无限长"工程方案**：分块 / RAG / Agent + 外部记忆。理解 LLM 上下文限制对设计长文档应用（RAG、Agent、长对话）至关重要。

### [LSTM 中，隐藏状态（hidden state）和单元状态（cell state）有什么区别？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834673496559618)

> **答案**：
>
> **LSTM 中 hidden state 和 cell state 的区别**
>
> LSTM 有两个状态变量：**hidden state (h_t)** 和 **cell state (C_t)**。它们职责不同，互相配合。
>
> **核心区别**
>
> | 维度 | Hidden State (h_t) | Cell State (C_t) |
> |---|---|---|
> | 角色 | 短期记忆 / 输出 | 长期记忆 |
> | 公式 | `h_t = o_t ⊙ tanh(C_t)` | `C_t = f_t·C_{t-1} + i_t·C̃_t` |
> | 更新方式 | 受输出门控制 | 加法更新（线性） |
> | 范围 | -1 到 1（经 tanh） | 任意值（未限幅） |
> | 暴露 | 输出给下游和下一时间步 | 内部状态，不直接输出 |
> | 梯度流 | 经过 tanh 和 o_t，有衰减 | 直接加法路径，不衰减 |
>
> **1. Cell State（C_t）：长期记忆**
>
> ```
> C_t = f_t · C_{t-1} + i_t · C̃_t
> ```
>
> **核心特性**：
> - **加法更新**：信息和梯度可以直接流过
> - **没有 tanh 串联**：避免梯度消失
> - **范围无限制**：可以是任意实数
> - **内部状态**：不直接对外，但通过 o_t 控制后输出
>
> **类比**：
> - 一条传送带，信息在上面平稳流动
> - 门控（f_t、i_t）控制加入和移除
> - 一旦写入，能保持很久（f_t ≈ 1 时）
>
> **作用**：保存**长期依赖**，例如：
> - 句子开头的主语，到句末仍影响预测
> - 长文档中提到的关键事实
>
> **2. Hidden State（h_t）：短期记忆 + 输出**
>
> ```
> h_t = o_t · tanh(C_t)
> ```
>
> **核心特性**：
> - **从 cell state 派生**：经过输出门和 tanh
> - **范围 -1 到 1**：tanh 限幅
> - **暴露给外界**：传递到下一时间步 + 输出给下游任务
> - **受门控**：o_t 决定输出多少
>
> **类比**：
> - 工作记忆（working memory）：当前关注的、需要立即用的
> - 短期缓存，内容随时被新输入刷新
>
> **作用**：
> - 传递给下一个时间步（作为下一步的输入）
> - 输出给下游任务（分类、生成、attention）
>
> **3. 信息流：cell state → hidden state**
>
> ```
> C_t (内部长期记忆)
>   ↓
> tanh (限幅 + 引入非线性)
>   ↓
> o_t (输出门选择)
>   ↓
> h_t (短期记忆 + 输出)
> ```
>
> cell state 是"幕后"的，hidden state 是"台前"的。
>
> **4. 训练中的角色**
>
> **反向传播时**：
> - C_t 路径：`∂C_t/∂C_{t-1} = f_t`（直接，不衰减）→ 长程梯度
> - h_t 路径：经过 o_t 和 tanh（衰减）→ 短期梯度
>
> 所以 LSTM 主要靠 C_t 解决梯度消失，h_t 是配合的输出。
>
> **5. 何时用哪个？**
>
> 下游任务：
> - **取 h_t 用**（标准做法）：
>   - 分类：h_T（最后一步）
>   - NER：[h_1, ..., h_T]（每步一个标签）
>   - Seq2Seq Encoder 输出：每步的 h_t
> - **取 C_t 用**（少见）：
>   - 某些自定义架构
>   - 实验性
>   - 默认不用
>
> PyTorch 的 LSTM 默认返回 (output, (h_n, c_n))：
> - output：每步的 h_t
> - h_n：最后一层最后一步的 h_t
> - c_n：最后一层最后一步的 C_t
> - 大多数任务只用 output 和 h_n
>
> **6. 类比人类认知**
>
> - **Cell state**：长时记忆（Long-term memory）—— 知识、经历、稳定事实
> - **Hidden state**：工作记忆（Working memory）—— 当前思考、注意力焦点、易变
>
> LSTM 的设计正好对应人类记忆的双系统。
>
> **7. 简化版：GRU 的合并**
>
> GRU（Cho et al. 2014）大胆合并了 cell state 和 hidden state：
> - 只保留 h_t
> - 用"更新门" z_t 控制：`h_t = (1-z_t) · h_{t-1} + z_t · h̃_t`
> - 效果与 LSTM 相当，参数更少
>
> **8. 类似设计**
>
> - **Highway Network**（2015）：类似 cell state 的加法信息流
> - **ResNet**（2015）：残差连接，思想同 LSTM cell state
> - **Transformer 的残差**：同样思路——加法路径解决梯度消失
>
> **总结**：
> - **Cell state (C_t)**：长期记忆，加法更新，抗梯度消失，内部状态
> - **Hidden state (h_t)**：短期记忆 + 输出，从 C_t 派生，对外暴露
>
> LSTM 的精妙在于**两个状态分工**：C_t 负责"记住"，h_t 负责"输出"。两者配合实现既长期记忆又灵活响应。理解这个分工，就理解了 LSTM 的核心设计哲学——也是后续 Highway、ResNet、Transformer 残差的思想源头。

### [什么是注意力机制？它是如何改善 NLP 模型性能的？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834674301865985)

> **答案**：
>
> **注意力机制（Attention Mechanism）**
>
> 注意力机制源自人类视觉——**面对大量信息时，选择性地"关注"重要部分**。在深度学习中，attention 让模型动态地加权不同输入。
>
> **为什么需要 Attention**
>
> **RNN Encoder-Decoder 的痛点**：
> - Encoder 把整个输入压缩成一个**固定长度的 context vector**
> - 长输入信息必然丢失
> - Decoder 每步都用同一个 context，无法聚焦不同部分
>
> 例：翻译 50 词的句子，把所有信息压到 1 个 500 维向量 → 信息瓶颈。
>
> **Attention 的核心思想**
>
> > 在 Decoder 的每一步，**动态地**看 Encoder 的所有输出，按相关性加权。
>
> **信息检索类比**
>
> ```
> database = {key_1: value_1, key_2: value_2, ...}
>
> query = current Decoder state
> scores = [similarity(query, key_i) for i]
> weights = softmax(scores)
> output = Σ weights_i · value_i
> ```
>
> - Query：当前想知道什么
> - Key：每个候选的"标签"
> - Value：每个候选的"内容"
> - 加权和：动态聚合相关信息
>
> **Attention 的公式**
>
> ```
> attention(Q, K, V) = softmax(score(Q, K)) · V
>
> score(Q, K) 有几种：
> - Dot-product:        q · k
> - Scaled dot-product: q · k / √d
> - Additive (Bahdanau): v^T · tanh(W_q · q + W_k · k)
> - Cosine:             cos(q, k)
> ```
>
> **Attention 在 NLP 中的作用**
>
> **1. 解决信息瓶颈**
> - Decoder 每步独立"查阅"Encoder 输出
> - 不再有固定 context
>
> **2. 长程依赖**
> - 任意两位置 O(1) 交互
> - 距离无关
>
> **3. 可解释性**
> - attention 权重可视化
> - 看模型"关注"哪里
>
> **4. 对齐（Alignment）**
> - 机器翻译中自动学到源词到目标词的对齐
> - 例："我" ↔ "I"，"喜欢" ↔ "love"
>
> **Attention 的种类**
>
> **1. Bahdanau Attention（2014）**
> - Additive：`v^T · tanh(W_q · q + W_k · k)`
> - RNN + attention 的经典
> - Query 是 Decoder hidden state，Key/Value 是 Encoder hidden states
>
> **2. Luong Attention（2015）**
> - Dot-product / general / concat
> - 比 Bahdanau 简单
> - 仍是 RNN + attention
>
> **3. Self-Attention（2017, Transformer）**
> - Q, K, V 都来自同一个序列
> - 让序列内每个位置都"看"其他位置
> - 现代 Transformer 的核心
>
> **4. Cross-Attention**
> - Q 来自一个序列，K/V 来自另一个
> - Encoder-Decoder 之间
> - Multi-modal 对齐
>
> **5. Multi-Head Attention**
> - 并行多个 attention
> - 不同 head 学不同模式
> - Transformer 标准
>
> **6. Sparse / Local Attention**
> - 不全局看，只看局部窗口或部分位置
> - Longformer、BigBird
>
> **Attention 改善 NLP 的几个方面**
>
> **1. 机器翻译**
> - BLEU 提升 2-5 分
> - 长句子翻译质量大幅提升
> - 自动学对齐
>
> **2. 文本摘要**
> - 长文档压缩成短摘要
> - attention 关注核心信息
> - 摘要质量显著提升
>
> **3. 问答系统**
> - 给定问题 + 上下文，attention 找答案位置
> - BiDAF、Match-LSTM 等经典模型
>
> **4. 文本分类**
> - Self-attention + pooling
> - 关注重要 token（如情感词）
> - 性能提升
>
> **5. 序列标注**
> - Self-attention 让每个 token 看上下文
> - 替代 BiLSTM
> - 性能更好
>
> **经典模型（pre-Transformer）**
>
> - **Bahdanau NMT**（2014）：第一个 attention seq2seq
> - **Luong NMT**（2015）：简化 attention
> - **BiDAF**（2016）：双向 attention 问答
> - **Transformer**（2017）：完全基于 attention，无 RNN
>
> **Self-Attention 的革命**
>
> Transformer 把 attention 推到极致：
> - 完全不用 RNN
> - 仅有 attention + FFN
> - 并行计算（不再时间步串行）
> - 长程依赖强（O(1) 距离）
> - 可扩展（Scaling Law 友好）
>
> → 开启 LLM 时代。
>
> **Attention 的可视化**
>
> attention 权重可热力图可视化：
> - 高权重 = 强关注
> - 看模型学到了什么模式
>
> 例：翻译 "The cat sat" → "Le chat était assis"
> - 看 attention map，"cat" 关注 "chat"
> - 学到了语义对齐
>
> **Attention 的局限**
>
> 1. **O(N²) 复杂度**：长序列计算贵
> 2. **Attention ≠ 重要性**：attention 权重不一定是"重要程度"（被 Clark et al. 2019 指出）
> 3. **可解释性被夸大**：attention 只是模型内部的计算，不完全等于"模型在看什么"
>
> **总结**：Attention 机制的核心 = **用 Query 与 Key 算相似度，加权聚合 Value**。**解决了 RNN seq2seq 的信息瓶颈**，让 Decoder 能动态查阅 Encoder 信息。**演化路径：Bahdanau（additive）→ Luong（dot-product）→ Self-Attention（Transformer）→ Multi-Head → Sparse → Linear**。**Attention 是 Transformer 的灵魂，也是现代 LLM 的基础**。理解 attention 是理解现代 NLP 的前提。

### [在文本分类任务中，如何处理样本（类别）不平衡的问题？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834677187547137)

> **答案**：
>
> **文本分类中的类别不平衡（Class Imbalance）**
>
> 实际业务中类别不平衡很常见：垃圾检测（95% 正常、5% 垃圾）、欺诈、医疗诊断等。不平衡会让模型偏向多数类。
>
> **不平衡的影响**
>
> **例**：99% 正常、1% 欺诈
> - 模型全预测"正常"，准确率 99%——但完全没用
> - 召回率 0%，欺诈全部漏检
>
> **关键指标**：
> - 不平衡时**不能只看 Accuracy**
> - 看 **Precision、Recall、F1、AUC-ROC、AUC-PR**
>
> **衡量指标**
>
> **1. Confusion Matrix**
> ```
>               Predicted
> Actual     Positive  Negative
> Positive    TP        FN
> Negative    FP        TN
> ```
>
> **2. Precision / Recall / F1**
> ```
> Precision = TP / (TP + FP)
> Recall    = TP / (TP + FN)
> F1        = 2 · P · R / (P + R)
> ```
>
> **3. AUC-ROC**
> - ROC 曲线下面积
> - 不受阈值影响
> - 但极不平衡时高估性能
>
> **4. AUC-PR（推荐）**
> - Precision-Recall 曲线下面积
> - 极不平衡时更可靠
>
> **解决方案**
>
> **1. 重采样（Resampling）**
>
> **a. 过采样少数类**
> - 复制少数类样本
> - SMOTE：合成新样本（在特征空间插值）
> - ADASYN：自适应合成
>
> ```python
> from imblearn.over_sampling import SMOTE
> X_res, y_res = SMOTE().fit_resample(X, y)
> ```
>
> **b. 欠采样多数类**
> - 随机丢弃多数类
> - Tomek Links：移除边界样本
> - 可能让多数类信息丢失
>
> **c. 混合**
> - SMOTE + Tomek
> - 实践中常用
>
> **d. 平衡 batch（深度学习）**
> ```python
> from torch.utils.data import WeightedRandomSampler
> sampler = WeightedRandomSampler(weights, num_samples)
> DataLoader(dataset, sampler=sampler)
> ```
>
> 每个 batch 按权重采样，保持平衡。
>
> **2. 类别加权（Class Weight）**
>
> 让少数类的 loss 更大：
> ```python
> # sklearn
> LogisticRegression(class_weight='balanced')  # 自动按频率倒数加权
> LinearSVC(class_weight='balanced')
>
> # PyTorch
> criterion = nn.CrossEntropyLoss(weight=torch.tensor([1.0, 10.0]))
> # 少数类权重 10
> ```
>
> 公式：
> ```
> loss = -Σ w_i · y_i · log(p_i)
> ```
>
> w_i 通常 = total / (n_classes · count_i)
>
> **优点**：不改变数据，简单
> **缺点**：可能 overfit 少数类
>
> **3. Focal Loss（推荐深度学习）**
>
> Lin et al. 2017（RetinaNet）：
> ```
> FL(p_t) = -α_t · (1 - p_t)^γ · log(p_t)
> ```
>
> - `(1 - p_t)^γ`：模型预测越准，权重越小（降低简单样本贡献）
> - γ 通常 2
> - α_t：平衡类别权重
>
> ```python
> # PyTorch 实现
> class FocalLoss(nn.Module):
>     def __init__(self, alpha=None, gamma=2):
>         super().__init__()
>         self.alpha = alpha
>         self.gamma = gamma
>
>     def forward(self, preds, targets):
>         ce = F.cross_entropy(preds, targets, reduction='none', weight=self.alpha)
>         pt = torch.exp(-ce)
>         return ((1 - pt) ** self.gamma * ce).mean()
> ```
>
> **适合**：极端不平衡 + 难样本多。
>
> **4. 阈值调整**
>
> 默认阈值 0.5 对不平衡不友好。**根据 PR 曲线选最优阈值**：
> ```python
> from sklearn.metrics import precision_recall_curve
>
> p, r, thresholds = precision_recall_curve(y_true, y_prob)
> # 选 F1 最大的阈值
> f1 = 2 * p * r / (p + r + 1e-8)
> best_threshold = thresholds[f1.argmax()]
> ```
>
> **适合**：业务关心 P/R 平衡。
>
> **5. 数据增强（少数类）**
>
> 让少数类样本变多：
> - **文本**：同义词替换、回译、EDA、Contextual Augmentation（BERT-MLM 替换）
> - **代码**：变量重命名、表达式等价变换
> - **图片**：翻转、旋转、cutout（CV 场景）
>
> **6. 评估与监控**
>
> - 用 **StratifiedKFold**：保持每折类别比例
> - 用 **分层采样**：训练/验证/测试同分布
> - 监控 **per-class metrics**：不只看 macro F1
>
> **7. 集成方法**
>
> - **Bagging**：每个基模型在平衡数据上训练
> - **Boosting**：把少数类的误分类权重加大（XGBoost scale_pos_weight）
> - **EasyEnsemble**：多个平衡子集训练多个模型
>
> **XGBoost 处理不平衡**：
> ```python
> xgb.XGBClassifier(
>     scale_pos_weight=ratio,  # 多数类 / 少数类 数量
>     eval_metric='aucpr'
> )
> ```
>
> **8. 异常检测（Anomaly Detection）**
>
> 极端不平衡（< 1%）：把少数类当异常：
> - Isolation Forest
> - One-Class SVM
> - Autoencoder（重建误差检测异常）
>
> 适合：欺诈、入侵检测、罕见缺陷。
>
> **9. Multi-stage Pipeline**
>
> 第一阶段高召回，第二阶段高精度：
> ```
> 粗筛模型（recall 99%）→ 精排模型（precision 95%）
> ```
>
> 适合：成本敏感的欺诈检测、医疗筛查。
>
> **实战建议（按不平衡程度）**
>
> | 不平衡程度 | 推荐方案 |
> |---|---|
> | 1:2 - 1:5 | class_weight + 阈值调整 |
> | 1:10 - 1:50 | 过采样 / SMOTE + Focal Loss |
> | 1:100 - 1:1000 | SMOTE + Focal Loss + 集成 |
> | > 1:1000 | 异常检测 + 多阶段 |
>
> **评估时注意**
>
> 1. **不要看 Accuracy**
> 2. **看 Macro F1**（各类平均）
> 3. **看 AUC-PR**（比 ROC 更严格）
> 4. **看 Per-class Metrics**（找最差的类优化）
> 5. **看业务指标**（欺诈时 recall 是关键）
>
> **示例：金融欺诈检测**
>
> 99.5% 正常 + 0.5% 欺诈：
> 1. **数据**：SMOTE 过采样（欺诈变 5%）
> 2. **模型**：XGBoost + scale_pos_weight = 200
> 3. **Loss**：Focal Loss（γ=2）
> 4. **阈值**：按 PR 曲线选 F1 最佳点
> 5. **评估**：AUC-PR、Recall@95%Precision
> 6. **监控**：每天看实际欺诈捕获率
>
> **总结**：处理类别不平衡的方法：
> 1. **重采样**（过采样 / 欠采样 / SMOTE）
> 2. **类别加权**（class_weight）
> 3. **Focal Loss**（深度学习首选）
> 4. **阈值调整**（按 PR 曲线）
> 5. **数据增强**（少数类）
> 6. **集成方法**（Bagging / Boosting）
> 7. **异常检测**（极端不平衡）
> 8. **多阶段 pipeline**
>
> **关键原则**：**不平衡时换评估指标（AUC-PR、F1、Recall）**，再谈算法。**否则再好的模型也救不了一味追求 Accuracy 的目标**。

### [简述 LLaMA（Large Language Model Meta AI）的基本原理。](https://www.mianshiya.com/bank/1906189461556076546/question/1821834678093516802)

> **答案**：
>
> **LLaMA 基本原理**
>
> LLaMA（Large Language Model Meta AI）= Meta 2023 年开源的 LLM 系列（7B/13B/33B/65B），是开源 LLM 的里程碑。
>
> **LLaMA 的设计哲学：数据效率 + 开源**
>
> 不同于 GPT-4 闭源、PaLM 大数据，LLaMA 主张：
> > "用更少参数、更多数据，达到甚至超越更大模型的性能。"
>
> **核心改进（相比原始 Transformer）**
>
> LLaMA 基于 Decoder-only Transformer，但有几个关键改进：
>
> **1. Pre-normalization（RMSNorm）**
>
> ```
> 原始 Transformer（Post-LN）：
> output = LayerNorm(x + Sublayer(x))
>
> LLaMA（Pre-RMSNorm）：
> output = x + Sublayer(RMSNorm(x))
> ```
>
> - LN 放子层前
> - 用 RMSNorm 替代 LayerNorm（简化版）
> - 训练更稳定
>
> **RMSNorm**：
> ```
> RMSNorm(x) = x / sqrt(mean(x²) + ε) · γ
> ```
> - 不减均值，只用 RMS 归一化
> - 计算更快
>
> **2. SwiGLU 激活函数**
>
> 替代 ReLU FFN：
> ```
> FFN(x) = (Swish(x · W_1) ⊙ (x · V)) · W_2
>
> Swish(x) = x · sigmoid(x)
> ```
>
> - 门控线性单元（GLU）+ Swish 激活
> - 性能优于 GeLU、ReLU
>
> FFN 中间维度调整为 `2/3 · 4 · d_model = (8/3) · d_model`（保持参数量）。
>
> **3. RoPE 旋转位置编码**
>
> 替代绝对位置编码：
>
> ```
> q_m · k_n = q · R(m-n) · k
> ```
>
> - 内积自然反映相对位置
> - 支持外推（NTK、YaRN 扩展）
> - 现代 LLM 标配
>
> **4. 训练数据**
>
> Llama 1 训练数据（1T-1.4T tokens）：
> - CommonCrawl（67%）
> - C4（15%）
> - GitHub（4.5%）
> - Wikipedia（4.5%）
> - Books（4.5%）
> - ArXiv（2.5%）
> - StackExchange（2%）
>
> Llama 2：2T tokens
> Llama 3：15T tokens
>
> **5. Tokenizer**
>
> - BPE (Byte Pair Encoding)
> - SentencePiece 实现
> - 词表：32K（Llama 1/2）、128K（Llama 3）
>
> **6. 优化器**
>
> - AdamW（β₁=0.9, β₂=0.95）
> - 梯度裁剪 1.0
> - Cosine LR schedule + warm-up
>
> **架构图**
>
> ```
> Tokens
>    ↓
> Token Embedding
>    ↓
> [Decoder Block] × N
>    ├── RMSNorm
>    ├── Multi-Head Self-Attention（causal mask, RoPE）
>    ├── Residual
>    ├── RMSNorm
>    ├── SwiGLU FFN
>    └── Residual
>    ↓
> RMSNorm
>    ↓
> Linear → Vocab Logits → Next Token
> ```
>
> **模型规模**
>
> | 模型 | 层数 N | d_model | Head 数 | FFN 维度 | 参数 |
> |---|---|---|---|---|---|
> | Llama 1 7B | 32 | 4096 | 32 | 11008 | 6.7B |
> | Llama 1 13B | 40 | 5120 | 40 | 13824 | 13B |
> | Llama 1 33B | 60 | 6656 | 52 | 17920 | 32.5B |
> | Llama 1 65B | 80 | 8192 | 64 | 22016 | 65.2B |
> | Llama 2 7B | 32 | 4096 | 32 | 11008 | 6.7B |
> | Llama 2 70B | 80 | 8192 | 64（GQA） | 28672 | 68B |
> | Llama 3 8B | 32 | 4096 | 32（GQA） | 14336 | 8B |
> | Llama 3 70B | 80 | 8192 | 64（GQA） | 28672 | 70B |
> | Llama 3.1 405B | 126 | 16384 | 128（GQA） | 53248 | 405B |
>
> **Llama 2 的关键改进**
>
> 1. **GQA（Grouped Query Attention）**
>    - 70B 用 GQA
>    - 多 head 共享 K、V
>    - 减少 KV cache 显存
>
> 2. **上下文长度 4K**
>
> 3. **Chat 版本（Llama 2 Chat）**
>    - SFT + RLHF
>    - 多轮对话优化
>
> **Llama 3 的关键改进**
>
> 1. **词表扩到 128K**
>    - 支持多语言、代码、emoji
> 2. **GQA 全面采用**
> 3. **15T tokens 训练**（数据大幅增加）
> 4. **8B / 70B / 405B 三档**
> 5. **支持 128K 上下文**（RoPE 缩放）
>
> **LLaMA 的训练流程**
>
> 1. **预训练（Pre-training）**
>    - 1T-15T tokens
>    - next token prediction
>    - 大规模分布式训练
>
> 2. **SFT（Supervised Fine-Tuning）**
>    - 高质量指令数据
>    - 几万-几百万样本
>
> 3. **RLHF（Reinforcement Learning from Human Feedback）**
>    - 偏好数据
>    - PPO / DPO
>    - 多轮迭代
>
> **LLaMA 的影响**
>
> 1. **开源 LLM 时代**
>    - 比肩 GPT-3.5 的开源模型
>    - 商业可用（Llama 2 商用许可）
> 2. **催生生态**
>    - Alpaca、Vicuna、CodeLlama、Llama 2 Long
>    - 中文版：Chinese-LLaMA、Alpaca-CN
> 3. **证明 Scaling Law**
>    - 7B 模型 + 1T tokens 接近 GPT-3 175B
> 4. **推动产业**
>    - 开源让所有人能跑大模型
>    - 推动 LLM 应用爆发
>
> **LLaMA 的局限**
>
> 1. **Llama 1 不可商用**（研究用）
> 2. **Llama 2 商用需申请**（>700M MAU）
> 3. **Llama 3 改善许可**（更开放）
> 4. **中文偏弱**（预训练数据英文为主）
> 5. **推理成本高**（70B+ 需要多卡）
>
> **总结**：LLaMA = **Decoder-only Transformer + RMSNorm + SwiGLU + RoPE + GQA + BPE Tokenizer**。**设计哲学：数据效率（更多 tokens 训练）+ 开源**。**代表版本**：Llama 1（研究）、Llama 2（商用）、Llama 3（128K 长上下文，128K 词表）。**意义**：开启开源 LLM 时代，证明"小模型 + 多数据"可达 GPT-3.5 水平。**现代变体**：Qwen、DeepSeek、Mistral、Gemma 都借鉴 Llama 架构。Llama 是 2023+ LLM 生态的核心，理解 Llama 架构是理解现代 LLM 工程的关键。

### [CBOW 和 Skip-gram 分别更适合哪些应用场景？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834670296305666)

> **答案**：
>
> **CBOW vs Skip-gram：应用场景对比**
>
> Word2Vec（Mikolov 2013）有两种训练方式：**CBOW** 和 **Skip-gram**。核心区别在于"用上下文预测中心"还是"用中心预测上下文"。
>
> **两种模式**
>
> **CBOW (Continuous Bag-of-Words)**
> ```
> Input: 上下文窗口（如前后 2-5 个词）
> Output: 中心词
>
> 例：窗口 ±2
> "The cat [sat] on the mat"
> Input: [The, cat, on, the]
> Output: sat
> ```
>
> **Skip-gram**
> ```
> Input: 中心词
> Output: 上下文词
>
> 例：
> "The cat [sat] on the mat"
> Input: sat
> Output: [The, cat, on, the]
> ```
>
> **对比**
>
> | 维度 | CBOW | Skip-gram |
> |---|---|---|
> | 训练目标 | 上下文 → 中心 | 中心 → 上下文 |
> | 训练速度 | 快（一次预测） | 慢（每个上下文词一次） |
> | 训练样本 | 少（窗口算一次） | 多（窗口每个词算一次） |
> | 罕见词表示 | 较差 | 较好 |
> | 高频词表示 | 较好 | 较差 |
> | 数据需求 | 适合大数据 | 适合小数据 |
> | 准确率（相似度） | 略低 | 略高 |
> | 内存 | 低 | 高 |
>
> **为什么 Skip-gram 在罕见词上更好**
>
> CBOW：
> - 上下文（多个词）平均后预测中心
> - 罕见词被高频邻居"稀释"
>
> Skip-gram：
> - 每个上下文词都作为独立训练样本
> - 罕见词作为中心词时，能学很多次（每次窗口都生成样本）
> - 罕见词作为上下文时，也能学到
>
> **例**：
> ```
> 词 "XYLOPTIAN"（罕见）在 100 个句子中出现
> - CBOW：100 次训练（每次窗口算一次）
> - Skip-gram：100 × 窗口大小 = 500 次训练
> ```
>
> Skip-gram 给罕见词更多"露脸"机会。
>
> **为什么 CBOW 在高频词上更好**
>
> 高频词如 "the"、"is"：
> - CBOW：作为中心，用上下文预测，平滑
> - Skip-gram：作为中心，要预测大量上下文，但"the" 的上下文很杂乱（出现在所有场景），模型困惑
>
> → Skip-gram 在高频词上"过度训练"，表示被稀释。
>
> **适用场景**
>
> **CBOW 适合的场景**
>
> 1. **大数据集**（> 1B tokens）
>    - Skip-gram 慢，CBOW 充分利用大数据
> 2. **关注常见词**
>    - 通用语义（如情感词、常用名词）
> 3. **资源受限**
>    - 训练快、内存小
> 4. **简单相似度任务**
>    - 词类比、词相似度
> 5. **实时训练**
>    - 在线学习、流式训练
>
> **Skip-gram 适合的场景**
>
> 1. **小数据集**（< 100M tokens）
>    - 数据利用率高
> 2. **关注罕见词**
>    - 专业术语、人名、地名
> 3. **细粒度语义**
>    - 同义词、上下位词
> 4. **高质量词向量**
>    - 离线训练，质量优先
> 5. **下游 NLP 任务**
>    - NER、关系抽取（需要精细词表示）
> 6. **短语 / 实体表示**
>    - 罕见实体更好
>
> **典型应用对比**
>
> | 任务 | 推荐 | 原因 |
> |---|---|---|
> | 通用词嵌入（如 Word2Vec Google News） | Skip-gram | 质量 + 罕见词 |
> | 实时推荐系统 | CBOW | 速度 |
> | 搜索引擎 query embedding | CBOW | 高频词为主 |
> | 学术领域 embedding | Skip-gram | 罕见术语 |
> | 情感分析预处理 | CBOW | 情感词常见 |
> | NER 词嵌入初始化 | Skip-gram | 实体词罕见 |
> | 句子相似度 | CBOW | 通用 |
> | 文档检索 | CBOW | 关键词匹配 |
>
> **实际性能（Mikolov 论文数据）**
>
> 在 WordSim-353（语义相似度基准）上：
> - CBOW：~75% 准确率
> - Skip-gram：~77% 准确率
>
> 在罕见词数据集：
> - CBOW：明显落后
> - Skip-gram：领先
>
> **优化技巧（两者通用）**
>
> **1. Negative Sampling（负采样）**
> - 加速训练（不需要算完整 softmax）
> - Skip-gram + Negative Sampling 最常用组合
>
> **2. Hierarchical Softmax**
> - 树形 softmax
> - 大词表时显著加速
>
> **3. Subsampling 频繁词**
> - 高频词（the、a）按概率丢弃
> - 让模型更多关注有意义的词
>
> **4. Phrase Detection**
> - 把 "New York" 当一个 token 训练
> - 提升短语 / 实体表示
>
> **5. Subword（fastText）**
> - 把词拆成字符 n-gram
> - 处理罕见词 / OOV
> - fastText 是 Skip-gram 的进化版
>
> **实际推荐**
>
> **默认选 Skip-gram + Negative Sampling**
> - Mikolov 推荐
> - 多数 NLP 任务
> - 质量更优
>
> **例外用 CBOW**
> - 数据极大（10B+ tokens）
> - 实时训练需求
> - 关注常见词
>
> **总结**：CBOW 和 Skip-gram 是 Word2Vec 的两种训练模式：
> - **CBOW**：上下文 → 中心，**快、适合大数据、高频词**
> - **Skip-gram**：中心 → 上下文，**慢、适合小数据、罕见词、质量高**
>
> **默认推荐 Skip-gram**（特别是 + Negative Sampling），适合绝大多数 NLP 任务。
> **CBOW 适合大数据、资源受限、实时场景**。
>
> 理解两者差异对学习现代 embedding（fastText、BERT、Sentence-BERT）很有帮助——这些模型都受到 Word2Vec 思想的影响。虽然 Word2Vec 已被 BERT / GPT 取代，但在快速原型、资源受限、可解释场景仍是一等重要 baseline。

### [聊一聊 ELMo 技术，它有哪些优缺点？可以做到一词多义吗？为什么？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834671101612034)

> **答案**：
>
> **ELMo 技术**
>
> ELMo（Embeddings from Language Models，Peters et al. 2018）= **基于双向 LSTM 的上下文相关词嵌入**。是 BERT 之前的里程碑工作。
>
> **核心创新：上下文相关的词嵌入**
>
> **传统 word embedding（Word2Vec、GloVe）**：
> - 每个词一个**静态**向量
> - "bank" 永远是同一个向量（不分 river bank / money bank）
> - 无法解决多义词
>
> **ELMo**：
> - 每个词的 embedding **依赖上下文**
> - 同一个词在不同句子中向量不同
> - 自动区分多义
>
> **架构**
>
> ELMo 用 **双向多层层 LSTM** 训练语言模型：
>
> ```
> Input: 字符级 CNN（处理 OOV + 罕见词）→ 词表示
>
> Forward LSTM  ：从左到右预测下一个 token
> Backward LSTM：从右到左预测上一个 token
>
> ELMo(word) = [forward_hidden, backward_hidden, input_embedding]
> ```
>
> **2 层 biLSTM，每层 4096 单元，512 维投影**
>
> **预训练目标**：双向语言模型
> ```
> Forward:  P(w_t | w_1, ..., w_{t-1})
> Backward: P(w_t | w_{t+1}, ..., w_N)
> ```
>
> **ELMo 输出：3 层表示的加权组合**
>
> 对每个词，ELMo 输出 3 个向量：
> 1. **Input embedding**（词级别，character CNN）
> 2. **第一层 biLSTM hidden**（语法级）
> 3. **第二层 biLSTM hidden**（语义级）
>
> 下游任务学习一个加权组合：
> ```
> ELMo(word) = γ · (s_0 · input + s_1 · layer1 + s_2 · layer2)
> ```
>
> **ELMo 能解决一词多义吗？**
>
> **能**。这是 ELMo 的核心贡献之一。
>
> **例**：
> - "I went to the **bank** to deposit money"
> - "We sat by the river **bank**"
>
> Word2Vec：两个 "bank" 是同一个向量
> ELMo：两个 "bank" 的向量不同（由 biLSTM 根据上下文计算）
>
> **为什么 ELMo 能做到**：
> - biLSTM 看到上下文
> - 第二层 LSTM 学到语义
> - 同一词在不同上下文有不同的隐状态
>
> **ELMo 的优点**
>
> **1. 上下文相关**
> - 解决多义词、上下文语义
>
> **2. 字符级输入**
> - 处理 OOV、罕见词、拼写错误
> - 通过 character CNN 学到形态学
>
> **3. 多层表示**
> - 不同层学到不同信息
> - 浅层：语法
> - 深层：语义
> - 任务自适应选择
>
> **4. 易用**
> - 作为 feature 拼接到现有模型
> - 无需重训整个模型
> - 兼容 CNN、LSTM、Attention 等
>
> **5. 当时 SOTA**
> - 6 个 NLP benchmark 刷新
> - 包括 SQuAD、SNLI、NER 等
>
> **ELMo 的缺点**
>
> **1. 不是真正的双向**
> - Forward LSTM 只看左
> - Backward LSTM 只看右
> - 拼接而非融合
> - 不如 BERT 的真正双向 self-attention
>
> **2. LSTM 限制**
> - 长程依赖弱
> - 训练慢（必须时间步串行）
> - 难以 scale up
>
> **3. 任务特定加权**
> - 每个 downstream 任务要学习 ELMo 加权
> - 不能完全 end-to-end fine-tune
>
> **4. 字符 CNN 慢**
> - character 级处理计算量大
> - 推理慢
>
> **5. 已被 BERT 取代**
> - 性能不如 BERT
> - 不支持 fine-tuning（只能当 feature）
>
> **ELMo vs BERT**
>
> | 维度 | ELMo | BERT |
> |---|---|---|
> | 架构 | biLSTM | Transformer Encoder |
> | 双向 | 拼接（fake 双向） | 真正双向（self-attention） |
> | 预训练 | 双向 LM | MLM + NSP |
> | 使用方式 | feature（拼接） | fine-tune（端到端） |
> | 长程依赖 | 弱（LSTM） | 强（O(1) attention） |
> | 性能 | 中 | 强 |
> | 训练并行 | 弱 | 强 |
> | Scale up | 难 | 易 |
> | 年代 | 2018.2 | 2018.10 |
>
> **ELMo 的使用方式**
>
> ```python
> # 1. 加载预训练 ELMo
> elmo = Elmo(options_file, weight_file, num_output_representations=1, requires_grad=False)
>
> # 2. 句子编码
> sentence = ["I", "love", "NLP"]
> elmo_embedding = elmo.batch_to_embeddings([sentence])  # (1, 3, 1024)
>
> # 3. 拼接到下游模型
> combined = [word_emb, elmo_embedding, pos_emb]
> features = torch.cat(combined, dim=-1)
> # 喂给 BiLSTM-CRF 等
> ```
>
> **典型应用：BiLSTM-CRF + ELMo**
> - 早期 NER 的强 baseline
> - ELMo 提供 context，BiLSTM-CRF 做标注
>
> **ELMo 的历史意义**
>
> 1. **预训练-微调范式开端**
>    - 在 BERT 之前奠定了基础
>    - 让大家看到"预训练语言模型"的价值
>
> 2. **上下文相关 embedding**
>    - 解决了 word embedding 的多义词痛点
>    - 启发后续的 BERT、GPT
>
> 3. **多任务通用模型**
>    - 一个预训练模型，多个任务用
>    - 不再每个任务从头训
>
> 4. **从浅层表示到深层**
>    - word2vec（浅）
>    - ELMo（中层）
>    - BERT（深层）
>    - GPT（深层 + 生成）
>
> **现代视角**
>
> ELMo 已被取代，但思想保留：
> - **上下文相关**：现代 LLM 的核心
> - **多层表示**：BERT 的多层 hidden states
> - **字符级处理**：fastText、WordPiece 都有这思想
>
> **总结**：ELMo = **双向 LSTM + 上下文相关词嵌入**。**创新**：① 解决多义词 ② 字符级 OOV ③ 多层表示（语法/语义分离）④ 预训练-特征拼接范式。**缺点**：① 非真正双向（拼接）② LSTM 限制 ③ 不能端到端微调 ④ 已被 BERT 取代。**历史地位**：连接 Word2Vec 和 BERT 的桥梁，是预训练语言模型时代的重要里程碑。理解 ELMo 对理解现代 LLM（BERT、GPT）的演化路径非常有帮助。

### [在文本分类任务中，如何处理高维和稀疏数据？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834676906528770)

> **答案**：
>
> **文本分类中的高维和稀疏数据**
>
> 文本分类的特征经常是 **高维（几万-几百万）+ 稀疏（大部分为 0）**。这是 BoW / TF-IDF 的天然特性，处理不好会让模型慢、效果差。
>
> **为什么文本特征是高维稀疏**
>
> **Bag-of-Words (BoW)**：
> - 词表 V = 10K - 1M
> - 每个文档向量维度 = V
> - 文档只包含少量词（如 100-500 个）
> - 其余位置全为 0
> - 稀疏度通常 > 99%
>
> **例**：
> ```
> 词表 V = 100,000
> 文档 "I love NLP" → 向量 [0, 0, ..., 1, 0, 0, ..., 1, 0, 0, ..., 1, 0, 0]
>                           ↑              ↑              ↑
>                          "I"           "love"          "NLP"
>                     只有 3 个非零位置，其余 99,997 个为 0
> ```
>
> **高维带来的问题**
>
> 1. **维度灾难**：距离 / 相似度区分度下降
> 2. **过拟合风险**：参数多，数据少易过拟合
> 3. **训练慢**：计算量大
> 4. **存储大**： dense 表示浪费空间
> 5. **稀疏学习困难**：每个样本只激活少量特征
>
> **稀疏带来的问题**
>
> 1. **特征共现少**：训练信号弱
> 2. **罕见词多**：噪声特征多
> 3. **数据稀疏偏差**：相似文档向量距离大
>
> **解决方案**
>
> **1. 稀疏矩阵存储**
>
> 不要用 dense numpy array，用 scipy.sparse：
> ```python
> from scipy.sparse import csr_matrix
>
> X_sparse = csr_matrix(X_dense)  # 只存非零位置
> ```
>
> **好处**：
> - 存储 / 内存省 99%
> - 矩阵运算快
>
> **2. 特征选择（降维）**
>
> **a. 移除低频词**
> ```python
> vectorizer = TfidfVectorizer(min_df=5)  # 至少在 5 个文档中出现
> ```
>
> **b. 移除高频词**
> ```python
> vectorizer = TfidfVectorizer(max_df=0.8)  # 在 80% 以上文档中出现的词去除（停用词等）
> ```
>
> **c. Top-K by Statistics**
> - Chi-squared：卡方检验选 top-K
> - Mutual Information：互信息
> - ANOVA F-test
>
> ```python
> from sklearn.feature_selection import SelectKBest, chi2
>
> selector = SelectKBest(chi2, k=10000)
> X_selected = selector.fit_transform(X, y)
> ```
>
> **d. Embedding 降维**
> - 用 Word2Vec / BERT 把文本编码成 100-1000 维 dense 向量
> - 完全解决高维稀疏
>
> **3. 限制 n-gram**
>
> n-gram 范围过大 → 维度爆炸：
> ```
> unigram：     词表 V
> bigram：      ~V²
> trigram：     ~V³
> ```
>
> 控制：
> ```python
> TfidfVectorizer(ngram_range=(1, 2), max_features=100000)
> ```
>
> **4. 用合适的算法**
>
> **对高维稀疏友好的模型**：
> - **Linear SVM**（最经典）
> - **Logistic Regression**（L1 / L2 正则）
> - **Naive Bayes**（Multinomial NB）
> - **fastText**
> - **XGBoost / LightGBM**（树模型，需 dense 化）
>
> **避免**：
> - RBF Kernel SVM（核矩阵太大）
> - KNN（距离在高维下无意义）
> - 神经网络（dense 化后才能用）
>
> **5. L1 正则化**
>
> L1 自动做特征选择，让不重要的特征权重为 0：
> ```python
> LogisticRegression(penalty='l1', solver='liblinear')
> ```
>
> 适合：超高维（> 100K），需要自动选特征。
>
> **6. Hashing Trick**
>
> 不维护词表，直接 hash 到固定维度：
> ```python
> from sklearn.feature_extraction.text import HashingVectorizer
>
> vectorizer = HashingVectorizer(n_features=2**18)
> ```
>
> **好处**：
> - 内存省（无词表）
> - 流式处理
> - 适合在线学习
>
> **坏处**：
> - hash 冲突
> - 不可解释（hash 不能反查）
>
> **7. Embedding 表示（推荐）**
>
> 彻底解决高维稀疏：
> - **TF-IDF + SVD/LSA**：传统降维
> - **Word2Vec 平均**：每个文档一个 d 维 dense 向量
> - **BERT / Sentence-BERT**：上下文相关的 768/1024 维 dense
> - **fastText**：自带子词 embedding
>
> **例：BERT 文档表示**
> ```python
> from sentence_transformers import SentenceTransformer
>
> model = SentenceTransformer('all-MiniLM-L6-v2')
> embeddings = model.encode(texts)  # (N, 384) dense
> ```
>
> **8. 处理极端稀疏的方法**
>
> **a. 增加信号**
> - 加 n-gram（捕获短语）
> - 加 char-level 特征（处理罕见词）
> - 加 domain 特征（如长度、标点）
>
> **b. 数据增强**
> - 同义词替换
> - 回译（translate back）
> - EDA（Easy Data Augmentation）
>
> **c. Pretrain + Finetune**
> - 用大量无标注数据预训练（如 BERT）
> - 微调时少量数据也行
>
> **典型 pipeline 对比**
>
> **Pipeline 1：传统稀疏（BoW + SVM）**
> ```python
> Pipeline([
>     ('tfidf', TfidfVectorizer(max_features=100000, ngram_range=(1,2), min_df=5)),
>     ('svm', LinearSVC(C=5))
> ])
> ```
> - 维度：100K
> - 速度：极快
> - 性能：中等
>
> **Pipeline 2：Embedding + SVM**
> ```python
> Pipeline([
>     ('embed', BERTEmbedder()),
>     ('svm', LinearSVC())
> ])
> ```
> - 维度：768
> - 速度：BERT 推理慢，但 SVM 快
> - 性能：好
>
> **Pipeline 3：BERT 端到端**
> ```python
> BertForSequenceClassification.from_pretrained(...)
> ```
> - 维度：768 内部
> - 速度：慢
> - 性能：最好
>
> **实战建议**
>
> | 数据规模 | 推荐方案 |
> |---|---|
> | < 1K 样本 | TF-IDF + Logistic Regression |
> | 1K - 100K 样本 | TF-IDF + SVM / fastText |
> | > 100K | BERT 微调 |
> | 超大词表 + 资源受限 | Hashing + Linear SVM |
> | 多语言 | mBERT / XLM-R |
>
> **总结**：处理高维稀疏文本数据的方法：
> 1. **稀疏存储**（scipy.sparse）
> 2. **特征选择**（min_df, chi2, MI）
> 3. **限制 n-gram**
> 4. **用对模型**（Linear SVM / LR / NB）
> 5. **L1 正则**
> 6. **Hashing Trick**
> 7. **Embedding 表示**（彻底解决，推荐）
>
> **现代趋势**：用 BERT / Sentence-BERT 把稀疏特征转成 dense 表示，再用下游分类器（SVM / LR / MLP）。既解决高维稀疏，又捕获语义。纯稀疏方案（TF-IDF + SVM）仍是强 baseline，特别在资源受限、可解释性、快速实验场景。

### [请描述 BERT 模型的架构和应用场景。](https://www.mianshiya.com/bank/1906189461556076546/question/1821834674561912834)

> **答案**：
>
> **BERT 模型架构与应用场景**
>
> BERT = **Bidirectional Encoder Representations from Transformers**（Devlin et al. 2018, Google）。是基于 Transformer Encoder-only 的预训练语言模型，是 NLP 里程碑。
>
> **架构**
>
> ```
> Input Tokens + Segment Embedding + Positional Embedding
>        ↓
> Transformer Encoder × L（双向 self-attention）
>        ↓
> 每个 token 的上下文表示 (N × d_model)
>        ↓
> 任务特定 head（分类 / 序列标注 / QA）
> ```
>
> **核心特点**：
> - **Encoder-only**：只用 Transformer Encoder（双向）
> - **预训练 + 微调**：先大规模无监督预训练，再下游任务微调
>
> **BERT 的两个预训练任务**
>
> **1. Masked Language Model（MLM）**
>
> 随机 mask 15% 的 token，让模型预测：
>
> ```
> Input:  The man went to the [MASK] to buy milk
> Target: store
> ```
>
> - 让模型双向理解（既看左也看右）
> - 不像 GPT 只能看左
>
> **实现细节**（15% mask 内部）：
> - 80% 替换为 [MASK]
> - 10% 替换为随机 token
> - 10% 保持不变
>
> **2. Next Sentence Prediction（NSP）**
>
> 判断两个句子是否连续：
>
> ```
> Input A: "The man went to the store."
> Input B: "He bought milk."  → IsNext（连续）
> Input B: "The sky is blue." → NotNext（不连续）
>
> Target: IsNext / NotNext
> ```
>
> - 学句子级关系
> - 用于 QA、NLI 等任务
> - 后续研究（RoBERTa）证明 NSP 作用不大
>
> **BERT 的变体**
>
> | 模型 | 参数 | 改进 |
> |---|---|---|
> | BERT-base | 110M | 12 层, d=768, 12 head |
> | BERT-large | 340M | 24 层, d=1024, 16 head |
> | RoBERTa | 同 BERT | 移除 NSP，更多数据，动态 MLM |
> | ALBERT | 更少 | 跨层参数共享，降低参数 |
> | DeBERTa | - | Disentangled attention |
> | DistilBERT | 60M | 蒸馏，参数减半 |
> | Chinese-BERT | - | 中文词表 + 全词 mask |
>
> **为什么 BERT 颠覆了 NLP**
>
> 1. **预训练 + 微调范式**：一个通用模型搞定多个任务
> 2. **双向理解**：比 GPT 单向更适合理解任务
> 3. **SOTA 纪录**：11 个 NLP 任务刷榜
> 4. **易用**：开源 + Hugging Face，开箱即用
> 5. **可扩展**：架构清晰，便于改进
>
> **BERT 的应用场景**
>
> **1. 文本分类**
>
> ```
> [CLS] 文本 [SEP] → BERT → [CLS] 表示 → 全连接 → softmax
> ```
>
> - 情感分类
> - 意图识别
> - 主题分类
> - 垃圾检测
>
> **2. 序列标注**
>
> ```
> [CLS] 字 1 字 2 ... [SEP] → BERT → 每字表示 → softmax/CRF
> ```
>
> - NER（命名实体识别）
> - POS tagging
> - Chunking
> - CWS（中文分词）
>
> **3. 问答（QA）**
>
> ```
> [CLS] Question [SEP] Context [SEP] → BERT
> → 每个上下文 token 的 start_logits, end_logits
> → 答案 = context[start:end]
> ```
>
> - SQuAD、Machine Reading Comprehension
> - 抽取式 QA
>
> **4. 句子对（Pair Classification）**
>
> ```
> [CLS] Sentence A [SEP] Sentence B [SEP] → BERT → [CLS] → 二分类
> ```
>
> - 自然语言推理（NLI）：SNLI、MultiNLI
> - 语义相似度
> - 句子匹配
>
> **5. 句子嵌入**
>
> ```
> Sentence → BERT → [CLS] 或 mean pooling → 句向量
> ```
>
> - 文档检索（早期）
> - 聚类
> - 但原生 BERT 句向量质量一般，需微调（SimCSE、Sentence-BERT）
>
> **6. 命名实体链接 / 关系抽取**
>
> ```
> [CLS] Context with entity highlights [SEP] → BERT → relation
> ```
>
> - 实体消歧
> - 关系分类
>
> **7. 信息抽取（IE）**
>
> - 事件抽取
> - 槽位填充（slot filling）
> - 知识图谱构建
>
> **8. 推荐系统 / 搜索**
>
> - 用户-query 匹配
> - 文档表示
> - 召回 / 排序
>
> **BERT 的使用方式**
>
> **微调（Fine-tuning）**
> ```python
> model = BertForSequenceClassification.from_pretrained('bert-base-chinese', num_labels=N)
> # 加 task-specific head
> # 在下游数据微调 3-5 epoch
> ```
>
> **特征提取（Feature-based）**
> ```python
> model = BertModel.from_pretrained('bert-base-chinese')
> features = model(input_ids).last_hidden_state  # (N, d)
> # 作为下游模型的输入特征
> ```
>
> **实际工作流**
>
> 1. 加载预训练 BERT（Hugging Face）
> 2. 选择合适的 head（分类 / token 分类 / QA）
> 3. 准备下游任务数据
> 4. 微调 3-5 epoch
> 5. 评估 + 部署
>
> **BERT 的局限**
>
> 1. **输入长度限制 512**：长文档处理困难
> 2. **生成能力差**：Encoder-only，不适合生成
> 3. **预训练数据陈旧**：知识不更新
> 4. **被 LLM 取代**：通用任务用 GPT/Llama 更好
>
> **BERT 在 LLM 时代的角色**
>
> - **理解类任务**：仍是性价比之选（小、快、专）
> - **生产部署**：分类、NER 用 BERT 比 GPT 便宜 100x
> - **embedding 模型**：SimCSE、BGE 都基于 BERT 架构
> - **特定领域**：医疗 BioBERT、法律 LegalBERT、科学 SciBERT
>
> **总结**：BERT = **Transformer Encoder-only + MLM + NSP 预训练**。**双向理解 + 预训练-微调范式** 让 NLP 进入新阶段。**应用：分类、NER、QA、句子对、IE、检索**。**在 LLM 时代仍重要**：理解类任务、embedding 模型、特定领域、生产部署都是 BERT 主场。**现代变体：RoBERTa、DeBERTa、ALBERT、DistilBERT**。理解 BERT 是理解现代 NLP 工程的基础。

### [BERT 怎样进行 mask 相比 CBOW 有什么区别？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834675400773634)

> **答案**：
>
> **BERT 的 Mask vs CBOW**
>
> BERT 的 Masked Language Model (MLM) 和 Word2Vec 的 CBOW 都基于 "**用上下文预测中心词**"，但有深刻区别。
>
> **核心思想对比**
>
> **CBOW (Continuous Bag-of-Words, Word2Vec)**
> ```
> Input: 上下文窗口（如前后 5 个词）
> Output: 中心词
> ```
>
> 例：`"The cat [MASK] on the mat"` → 预测 `[MASK]` = "sat"
>
> **BERT MLM**
> ```
> Input: 整个句子（随机 mask 15%）
> Output: masked 词
> ```
>
> 例：同上 → 预测 "sat"
>
> **表面看：思想相同**。**实际有 6 个关键区别**。
>
> **区别 1：上下文窗口**
>
> | | CBOW | BERT MLM |
> |---|---|---|
> | 窗口 | 固定（±5 词） | 整个序列（最长 512） |
> | 远距离 | 看不到 | 双向看到所有 |
>
> **区别 2：上下文表示**
>
> **CBOW**：
> ```
> context_vector = average(window_words_embeddings)
> ```
> - 简单平均
> - 顺序无关（bag of words）
>
> **BERT**：
> ```
> context = Transformer Encoder(sequence)
> ```
> - 多层 self-attention
> - 深度交互
> - 顺序敏感（位置编码）
>
> **区别 3：模型深度**
>
> | | CBOW | BERT MLM |
> |---|---|---|
> | 模型 | 2 层（输入投影 + 输出 softmax） | 12-24 层 Transformer |
> | 容量 | 极小 | 巨大 |
> | 表示能力 | 弱 | 强 |
>
> **区别 4：预训练数据规模**
>
> | | CBOW | BERT MLM |
> |---|---|---|
> | 数据 | 100MB - 1GB（Google News 100B tokens） | 13GB - 数 TB（Books + Wiki + CC） |
> | 训练 | 单 GPU，几小时 | 多 TPU，几天 |
> | 词表 | 3M (Word2Vec) | 30K-50K (WordPiece) |
>
> **区别 5：表示性质**
>
> **CBOW**：
> - 每个词一个**静态 embedding**（与上下文无关）
> - "bank" 永远是同一个向量（不分 river bank vs money bank）
>
> **BERT**：
> - 每个词的表示**根据上下文动态变化**
> - "bank" 在 "river bank" 和 "bank account" 中向量不同
> - **解决了一词多义**
>
> **区别 6：Mask 策略**
>
> **CBOW**：
> - 不 mask，直接用窗口预测中心
> - 训练时中心词已知（标签）
>
> **BERT MLM**：
> - 训练时随机 mask 15% 的 token
> - 15% 内部细分：
>   - 80% 替换 [MASK]
>   - 10% 替换随机 token
>   - 10% 保持不变（让模型不能依赖 [MASK] 标记）
>
> **BERT 的 80/10/10 设计目的**：
> - 80% [MASK]：明确"预测任务"
> - 10% 随机：让模型不能只依赖 [MASK] 标记
> - 10% 保持：让模型学到"原词也可能是答案"
> - 微调时没 [MASK]，提前适应
>
> **对比表**
>
> | 维度 | CBOW | BERT MLM |
> |---|---|---|
> | 上下文窗口 | 固定 ±5 | 全序列 |
> | 顺序 | 不敏感 | 敏感（位置编码） |
> | 模型深度 | 2 层 | 12-24 层 |
> | 表示 | 静态 | 动态（上下文相关） |
> | 多义词 | 无法处理 | 自动区分 |
> | Mask | 不 mask | 15% mask + 80/10/10 |
> | 训练目标 | 简单 word prediction | MLM + NSP |
> | 应用 | 早期 embedding | 通用 NLP 模型 |
> | 性能 | 中 | SOTA（理解类） |
>
> **为什么 BERT 比 CBOW 强**
>
> 1. **上下文动态表示**：解决多义词
> 2. **深层架构**：理解复杂语义
> 3. **长程依赖**：远距离上下文
> 4. **多任务迁移**：预训练 → 微调范式
> 5. **大规模数据**：知识广度
>
> **CBOW 仍有的优势**
>
> 1. **极快训练**：几小时 vs 几天
> 2. **极小模型**：100MB vs 400MB+
> 3. **静态 embedding 可复用**：word2vec、GloVe 至今仍用
> 4. **简单可解释**
>
> **实际应用选择**
>
> | 场景 | 推荐 |
> |---|---|
> | 简单文本分类 | CBOW / GloVe + CNN |
> | 词向量查询 | Word2Vec / GloVe |
> | 复杂 NLP 任务 | BERT |
> | 上下文敏感任务 | BERT |
> | 多义词消歧 | BERT |
> | 资源受限 | CBOW |
>
> **演化关系**
>
> ```
> NNLM (2003) → Word2Vec (CBOW + Skip-gram, 2013)
>             → ELMo (双向 LSTM, 2018)
>             → BERT (Transformer MLM, 2018)
>             → GPT (autoregressive)
>             → 现代 LLM
> ```
>
> **BERT MLM 是 CBOW 思想的"超强化版"**——同样的"用上下文预测中心词"目标，但用更深的模型 + 更大的数据 + 更聪明的 mask 策略。
>
> **总结**：BERT MLM 和 CBOW 共享 "**用上下文预测中心词**" 的核心思想。**关键区别**：
> - **窗口**：CBOW 局部，BERT 全局
> - **顺序**：CBOW 不敏感，BERT 敏感
> - **深度**：CBOW 浅，BERT 深
> - **表示**：CBOW 静态，BERT 动态
> - **多义词**：CBOW 不能，BERT 能
>
> BERT = CBOW 思想 + Transformer 架构 + 大数据 + 巧妙 mask 策略。理解这个对比，就理解了 NLP 表示学习的演化。

### [与循环神经网络（RNN）相比，LSTM 是如何解决梯度消失问题的？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834671663648770)

> **答案**：
>
> **LSTM 如何解决 RNN 的梯度消失**
>
> RNN 的核心痛点：**梯度消失**（gradient vanishing）。LSTM 通过**加法 cell state + 门控**优雅解决。
>
> **RNN 为什么梯度消失**
>
> RNN 公式：
> ```
> h_t = tanh(W_x · x_t + W_h · h_{t-1} + b)
> ```
>
> 反向传播（BPTT）：
> ```
> ∂L/∂h_0 = ∂L/∂h_T · Π_{t=1}^{T} (∂h_t/∂h_{t-1})
>         = ∂L/∂h_T · Π_{t=1}^{T} (W_h^T · diag(tanh'(...)))
> ```
>
> 每个 `∂h_t/∂h_{t-1}` 包含：
> - W_h：权重（特征值 < 1 时连乘衰减）
> - tanh'：导数最大 1，多数时候 < 1
>
> → 连乘 T 次 → 指数衰减 → **梯度消失**
>
> 工程上表现：
> - 训练时只能记住 < 100 步
> - 长程依赖学不到
> - 深层 RNN 训不动
>
> **LSTM 的"梯度高速公路"**
>
> LSTM 多了一条 **cell state `C_t`**：
>
> ```
> C_t = f_t · C_{t-1} + i_t · C̃_t
> ```
>
> 反向传播到 C_{t-1}：
> ```
> ∂C_t / ∂C_{t-1} = f_t  (+ 间接路径)
> ```
>
> - 直接路径：只有 f_t 一个乘法
> - f_t 是 sigmoid 输出（0-1），可以被训练成接近 1
> - 当 f_t ≈ 1 时，**梯度直接传过**
>
> → 没有 tanh 串联衰减，没有 W_h 的连乘爆炸
>
> 对比 RNN 的 `h_t = f(h_{t-1})`：必须经过非线性变换，梯度衰减。
>
> LSTM 的 `C_t = f_t · C_{t-1} + ...`：是**线性加法**，梯度直接流。
>
> **门的作用**
>
> **1. 遗忘门 `f_t`**
> - 控制 cell state 的"遗忘程度"
> - 训练时学到"长程依赖"对应的位置 → f_t ≈ 1（保留）
> - "短程信息"对应的位置 → f_t ≈ 0（遗忘）
>
> **2. 输入门 `i_t`**
> - 控制新信息的"写入程度"
> - 写多少由 i_t 控制，避免一次性洗掉旧信息
>
> **3. 输出门 `o_t`**
> - 控制 cell state 到 hidden state 的"读出程度"
> - cell state 内部保留所有信息，但只输出需要的部分
>
> **核心机制：线性路径 + 门控选择性**
>
> LSTM 的精妙在于：
> 1. **cell state 提供"线性路径"** → 梯度不衰减
> 2. **门控提供"选择性"** → 何时记、何时忘、何时输出
> 3. **两条路径互补** → 既有长期记忆，也有短期响应
>
> **数学解释**
>
> Gers et al. 2000 证明：LSTM 的 cell state 在常数误差流（Constant Error Carousel, CEC）下：
> - 当 f_t = 1 时，`C_t = C_{t-1}`，梯度完全保留
> - 这种"恒定误差流"是 LSTM 解决梯度消失的本质
>
> **实验对比**
>
> 任务：复制 1000 步前的 token
> - RNN：失败（梯度消失）
> - LSTM：成功（cell state 保留信息）
>
> 任务：长文档情感分析
> - RNN：略高于随机
> - LSTM：80%+ 准确率
>
> **LSTM 不解决梯度爆炸**
>
> 注意：LSTM 缓解梯度**消失**，但不一定解决梯度**爆炸**（那是 `f_t > 1` 时的另一问题）。爆炸要用梯度裁剪处理。
>
> **与 Transformer 对比**
>
> Transformer 解决梯度消失的思路：**残差连接**。
> ```
> output = x + Sublayer(x)
> ∂output/∂x = 1 + ∂Sublayer/∂x  →  永远有 1
> ```
>
> LSTM 解决梯度消失的思路：**加法 cell state**。
> ```
> C_t = f_t · C_{t-1} + ...
> ∂C_t/∂C_{t-1} = f_t (直接) + 其他路径  →  不串联非线性
> ```
>
> 两者本质都是"**提供梯度直接流过的路径**"。
>
> **总结**：LSTM 解决 RNN 梯度消失的核心机制：
> 1. **加法 cell state**：梯度直接流，不串联非线性
> 2. **遗忘门**：可学习"何时保留"（f_t ≈ 1）
> 3. **输入门 / 输出门**：选择性写入和读出，避免洗掉旧信息
>
> LSTM 让 RNN 能记住 1000+ 步的依赖，统治 NLP 长达 20 年，直到 Transformer 出现。理解 LSTM 的门控思想，对理解现代架构（GRU、Mamba、SSM）非常有帮助。

### [BERT 是如何处理自然语言文本中不常见词或者罕见词的？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834674817765378)

> **答案**：
>
> **BERT 如何处理罕见词**
>
> BERT 通过 **WordPiece 子词分词 + [UNK] 兜底** 来处理罕见词（OOV, Out-Of-Vocabulary）。
>
> **核心机制：WordPiece 子词分词**
>
> BERT 不用 word-level（容易 OOV），而用 **WordPiece**（子词级别）：
>
> ```
> "unhappiness" → ["un", "##happiness"]
>                    ↓       ↓
>                   词表中   拆成 "##happy" + "##ness"
>
> 最终：["un", "##happy", "##ness"]
> ```
>
> `##` 表示"接续前面的子词"。
>
> **WordPiece 的好处**
>
> 1. **覆盖任意词**：训练时没见过的词也能表示
> 2. **词表可控**：30K-50K 即可（如 BERT-base 中文 21128）
> 3. **保留语义**：罕见词 = 已知子词组合，子词仍有语义
> 4. **避免 OOV 灾难**：不再有"完全无法表示"的词
>
> **WordPiece 算法**
>
> 训练时类似 BPE：
> 1. 绝始化：每个字符是一个 token
> 2. 反复合并：选择"使训练语料似然最大"的子词对
> 3. 直到达到目标词表大小
>
> 推理时：
> - 贪心：从最长匹配开始切分
> - 不在词表中的字符 → [UNK]
>
> **BERT 的词表大小**
>
> | 模型 | 词表 |
> |---|---|
> | BERT-base (English) | 30,522 |
> | BERT-base (Chinese) | 21,128 |
> | RoBERTa | 50,265 |
> | BERT-large multilingual | 119,546 |
>
> 中文 BERT 是**字级别**（每个汉字一个 token），不依赖分词。
>
> **罕见词的具体处理**
>
> **情况 1：罕见但可拆成子词**
>
> 例："tokenization"（训练时没见）
> - WordPiece 拆成 ["token", "##ization"]
> - 都在词表中 → 正常表示
>
> **情况 2：拆出的子词也不在词表**
>
> 例：人名 / 地名 / 拼写错误
> - 拆到字符级别
> - 若字符也不在词表 → [UNK]
>
> 例：英语 BERT 处理中文字符
> - 中文字符不在英文 BERT 词表 → [UNK]
>
> **情况 3：完全 OOV**
>
> 例：Unicode 表情符号、特殊符号
> - 直接 [UNK]
>
> **[UNK] 的处理**
>
> `[UNK]` 是 BERT 的特殊 token：
> - 词表中固定位置
> - 训练时也作为输入
> - 表示"未知 token"
>
> `[UNK]` 的 embedding 是模型学习的"通用未知"表示。
>
> **罕见词在下游任务**
>
> **1. 不影响主要任务**
> - 大多数 NLP 任务，罕见词不是关键
> - WordPiece 拆分已能捕获大部分语义
>
> **2. 命名实体（NER）**
> - 人名、地名经常是罕见词
> - BERT 通过字符级 + 上下文仍能识别
> - NER 性能不受太大影响
>
> **3. 关键词 / 槽位**
> - 某些任务关键词罕见
> - 通过子词 + 上下文处理
>
> **多语言 BERT（mBERT）**
>
> mBERT 在 104 种语言上预训练，词表 119K：
> - 每种语言共享部分词表
> - 罕见语言也能处理（虽然不如英语好）
> - 中文、阿拉伯语、日语：字符 + WordPiece
>
> **罕见词 vs 领域专有词**
>
> **通用 BERT 在医疗 / 法律 / 科技领域**：
> - "EGFR"、"BRCA1"（基因名）→ 罕见
> - "PCIe"、"NVMe"（技术词）→ 罕见
>
> 解决：
> - **领域 BERT**：BioBERT、LegalBERT、SciBERT
> - **继续预训练**：Domain-Adaptive Pretraining（DAPT）
> - **微调时扩词表** + 重新训练 embedding
>
> **BERT 处理罕见词的局限**
>
> 1. **拆分后语义可能损失**
>    - "unhappiness" → ["un", "##happy", "##ness"]
>    - 模型要重新组合，不一定准确
>
> 2. **拆得越细，attention 分散**
>    - 一个词变多个 token → 序列变长
>    - 512 长度更容易超
>
> 3. **字符级表示不如 word 级直接**
>    - 某些精细语义任务受影响
>
> 4. **[UNK] 是黑箱**
>    - 模型不知道 [UNK] 是什么
>    - 关键罕见词若 [UNK]，效果打折
>
> **对比其他模型**
>
> | 模型 | 分词 | 罕见词处理 |
> |---|---|---|
> | Word2Vec / GloVe | word-level | 直接 OOV，无 embedding |
> | BERT | WordPiece | 子词拆分 |
> | GPT-2/3 | BPE | 子词拆分（类似 WordPiece） |
> | Llama | BPE（SentencePiece） | 子词拆分 |
> | T5 | SentencePiece | 子词拆分 |
>
> → 现代主流都是子词分词，BERT 是较早采用者。
>
> **改进 BERT 罕见词处理**
>
> **1. 全词 mask（Whole Word Masking, WWM）**
> - 原 BERT：随机 mask 单个 token（可能只 mask "##ness"）
> - WWM：mask 整个词（"un" + "##happy" + "##ness" 都 mask）
> - 中文 BERT-wwm、MacBERT 都用
>
> **2. 扩词表 + 继续预训练**
> - 添加领域专用词到词表
> - 用领域语料继续预训练
> - 例：BioBERT 加生物医学术语
>
> **3. 字符级 embedding 补充**
> - 给罕见词额外加字符级特征
> - 类似 CharCNN
>
> **4. 检索增强（RAG）**
> - 罕见词通过外部知识库解释
> - 比纯参数记忆更可靠
>
> **总结**：BERT 处理罕见词的机制：
> 1. **WordPiece 子词分词**（核心）
> 2. **[UNK] 兜底**（极端情况）
> 3. **字符级 fallback**（中文 BERT 是字级别）
>
> **优势**：覆盖任意词 + 词表可控 + 保留语义
> **局限**：拆分损失 + [UNK] 黑箱 + 长度膨胀
>
> **改进**：全词 mask、领域适配、扩词表、字符特征、RAG。理解 BERT 的子词分词是理解现代 NLP 模型的关键，几乎所有现代 LLM（GPT、Llama）都用类似的子词分词。

### [解释 hierarchical softmax 的流程，以及它有什么优点？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834669751046145)

> **答案**：
>
> **Hierarchical Softmax（层次 Softmax）**
>
> Hierarchical Softmax（HS）= **把多分类拆成二叉树上的多个二分类**，将 softmax 计算从 O(V) 降到 O(log V)，V 是词表大小。
>
> **为什么需要 HS**
>
> 标准 softmax：
> ```
> P(w_i | context) = exp(h · v_i) / Σ_{j=1}^{V} exp(h · v_j)
> ```
>
> - 词表 V 很大（如 100K-1M）
> - 每次预测要算 V 个 exp + 求和
> - 训练 / 推理都慢
>
> Word2Vec（10 万词表）训练时，95% 时间花在 softmax 上。
>
> **HS 的核心思想**
>
> 把所有词组织成一棵**二叉树**，叶子节点是词，内部节点是"中间类"。预测一个词 = 从根走到该叶子。
>
> ```
>               root
>              /                A      B
>            / \    /         word1 word2 word3 word4
> ```
>
> 预测 word1：
> - root → A：二分类（向左 vs 向右）
> - A → word1：二分类
>
> 每次预测 = **路径长度 = log₂(V)** 次二分类。
>
> 每次二分类 = 1 个 sigmoid（O(1) 计算）。
>
> → 总复杂度 **O(log V)** 而不是 O(V)。
>
> **HS 的数学形式**
>
> 二叉树每个内部节点 n 有一个向量 v_n。
>
> 给定输入 h，走到叶子 w 的概率：
>
> ```
> P(w | h) = Π_{n ∈ path(w)} σ(sign(n) · h · v_n)
>
> sign(n) = +1 如果 n 是左子节点
>         = -1 如果 n 是右子节点
> ```
>
> 例：path(w) = [root, A, w]
> ```
> P(w | h) = σ(h · v_root) · σ(h · v_A) · σ(h · v_w)
>         （每次二分类的概率连乘）
> ```
>
> **HS 的优势**
>
> **1. 计算极快**
> - O(V) → O(log V)
> - 100K 词表：100K → 17 次计算
> - 加速 ~6000x
>
> **2. 内存省**
> - 不需要存完整 V × d 的输出矩阵
> - 只需 2V 个内部节点向量（仍 V 量级，但常数小）
>
> **3. 适合大词表**
> - Word2Vec、fastText 用 HS
> - 早期神经语言模型（NNLM）也用
>
> **HS 的构造**
>
> 二叉树的形状影响性能：
>
> **1. 随机构造**
> - 简单，但性能一般
>
> **2. Huffman 树（Word2Vec 默认）**
> - 高频词路径短，低频词路径长
> - 平均计算量最优
> - 训练快
>
> ```
> 高频词 "the" → 路径长度 5
> 低频词 "xylophone" → 路径长度 20
> ```
>
> **3. 类别树（Label Tree）**
> - 按类别语义分组
> - 相似词在同一子树
> - 性能略好
>
> **HS 的劣势**
>
> **1. 训练时只能更新路径上的节点**
> - 标准 softmax：每个 batch 更新所有 V 个词向量
> - HS：只更新 log V 个路径节点
> - **正样本学得快，负样本更新慢**
>
> **2. 推理时不能并行**
> - 标准 softmax：矩阵乘法，GPU 高度并行
> - HS：必须按路径走，串行
> - GPU 上 HS 可能比标准 softmax 慢
>
> **3. 失去精确概率**
> - HS 是"近似"概率（虽数学上精确，但训练时部分更新）
> - 某些任务（如采样、生成）需要精确概率
>
> **4. 不适合现代 LLM**
> - 现代 GPU 优化了 GEMM，标准 softmax 已经很快
> - HS 的优势只在 CPU 上明显
> - BERT、GPT 等都用标准 softmax
>
> **HS 与 Negative Sampling 对比**
>
> Word2Vec 解决大词表的两种方案：
>
> | | Hierarchical Softmax | Negative Sampling |
> |---|---|---|
> | 复杂度 | O(log V) | O(k)（k 个负样本） |
> | 思想 | 树形分解 | 随机采样负样本 |
> | 训练 | 一次更新路径 | 一次更新 k+1 个 |
> | 推理 | 必须 HS | 不能直接做完整 softmax |
> | Word2Vec | 默认之一 | 默认之一 |
> | 现状 | 较少用 | 仍常见（对比学习） |
>
> **HS 的现代意义**
>
> 虽然 HS 在现代 LLM 中少见，但思想保留：
>
> **1. 大词表分类**
> - 推荐系统：百万级 item 分类
> - 极大规模多标签
>
> **2. 对比学习的"近似"**
> - InfoNCE loss 用 negative sampling
> - 与 HS 思想类似（不计算完整 softmax）
>
> **3. 决策树 + 神经网络**
> - Deep Forest、Neural Decision Tree
> - 用树结构分解决策
>
> **实战代码（简化）**
>
> ```python
> class HierarchicalSoftmax:
>     def __init__(self, vocab_size, tree):
>         self.tree = tree  # 二叉树结构
>         self.node_vectors = nn.Parameter(...)
>
>     def forward(self, h, target):
>         path = self.tree.path_to(target)
>         loss = 0
>         for node, direction in path:
>             logit = h · self.node_vectors[node]
>             loss += BCE(logit, direction)  # 二分类 loss
>         return loss
>
>     def predict(self, h):
>         # 从根走，每次选 sigmoid 最大的方向
>         node = self.tree.root
>         while not node.is_leaf:
>             logit = h · self.node_vectors[node]
>             if sigmoid(logit) > 0.5:
>                 node = node.left
>             else:
>                 node = node.right
>         return node.word
> ```
>
> **Word2Vec 的实践**
>
> Word2Vec 训练选项：
> - `sg=0, hs=1`：CBOW + Hierarchical Softmax
> - `sg=1, hs=1`：Skip-gram + HS
> - `sg=0, negative=5`：CBOW + Negative Sampling
> - `sg=1, negative=5`：Skip-gram + Negative Sampling（最常用）
>
> 实际：**Negative Sampling 用得更多**，HS 是可选。
>
> **总结**：Hierarchical Softmax = **二叉树 + log V 次二分类**，将 softmax 复杂度从 O(V) 降到 O(log V)。**优势**：训练快、内存省、适合大词表。**劣势**：训练不充分（部分更新）、推理串行、不精确。**典型应用**：Word2Vec、fastText、早期 NMT。**现代 LLM 不用**（GPU 优化让标准 softmax 已足够快）。理解 HS 的"分解"思想对学习大词表分类、对比学习仍有价值。

### [现有文本分类算法在处理多语种文本数据时可能遭遇哪些挑战？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834677502119938)

> **答案**：
>
> **多语种文本分类的挑战**
>
> 多语种（Multilingual）分类：同一模型处理多种语言。如跨境电商（中文/英文/西班牙语）、国际客服（30+ 语言）。
>
> **主要挑战**
>
> **1. 语言的多样性**
>
> - **语法差异**：SVO（中英）、SOV（日语韩语）、VSO（阿拉伯语）
> - **书写方向**：LTR（中英）、RTL（阿拉伯语、希伯来语）
> - **字符集**：拉丁、汉字、阿拉伯、西里尔、假名……
> - **无空格**：中文、日语
> - **复合词**：德语、荷兰语
>
> **2. 资源不均衡**
>
> | 语言 | 训练数据 |
> |---|---|
> | 英语 | 海量 |
> | 中文、西班牙语、法语 | 多 |
> | 阿拉伯语、葡萄牙语 | 中 |
> | 斯瓦希里语、孟加拉语 | 极少 |
>
> 低资源语言性能差。
>
> **3. 文化差异**
>
> - 同义不同文化背景
> - "点赞" 在中文 vs 西方手势差异
> - 节日、习俗、典故
> - 俚语、网络用语
>
> **4. 跨语言对齐**
>
> - "good" 和 "好" → 同一表示空间
> - 但模型不一定学到
>
> **5. 词表共享**
>
> - 多语言词表大（30K → 250K）
> - 单语言模型权重稀释
> - 低资源语言表示能力差
>
> **6. 评测困难**
>
> - 每种语言都要标注
> - 标注一致性（不同语言标注员）
> - 跨语言 ground truth 对齐
>
> **主要方案**
>
> **方案 1：每语言单独训练**
>
> 每种语言训练一个 BERT：
> - mBERT-Chinese、BERT-English、BERT-Arabic……
>
> **优点**：
> - 性能好（每模型专精）
> - 解释性好
>
> **缺点**：
> - 部署 N 个模型，成本高
> - 数据少的语言训不出来
> - 跨语言迁移差
>
> **方案 2：多语言预训练模型**
>
> 用一个 BERT 在多种语言上预训练：
>
> - **mBERT**（Multilingual BERT）：104 语言，WordPiece
> - **XLM**（Cross-lingual Language Model）
> - **XLM-R**（XLM-RoBERTa）：100 语言，2.7TB 数据
> - **mT5**：多语言 T5
> - **mBART**：多语言 BART
>
> ```python
> from transformers import AutoTokenizer, AutoModel
> tokenizer = AutoTokenizer.from_pretrained('xlm-roberta-base')
> model = AutoModel.from_pretrained('xlm-roberta-base')
> ```
>
> **优点**：
> - 一个模型搞定多语言
> - 跨语言迁移（低资源语言受益于高资源）
> - 部署简单
>
> **缺点**：
> - 单语言性能略低于专精模型
> - 词表大、参数多
> - 仍偏向高资源语言
>
> **方案 3：翻译成英文再分类**
>
> ```
> 任意语言 → 翻译（Google Translate / DeepL） → 英文分类
> ```
>
> **优点**：
> - 复用英文模型
> - 简单
>
> **缺点**：
> - 翻译误差传导
> - 翻译 API 成本
> - 文化信息丢失
> - 双关语、俚语翻不过来
>
> **方案 4：跨语言 Embedding**
>
> 把所有语言映射到统一向量空间：
> - **LASER**（Facebook）：93 语言统一 embedding
> - **LaBSE**（Google）：109 语言 BERT embedding
> - **Sentence-BERT multilingual**
>
> ```python
> from sentence_transformers import SentenceTransformer
> model = SentenceTransformer('LaBSE')
> embeddings = model.encode(['Hello world', '你好世界', 'Bonjour le monde'])
> # 三个 embedding 在同一空间
> ```
>
> 然后用 KNN / 浅层分类器。
>
> **优点**：
> - 跨语言对齐好
> - 零样本跨语言迁移
>
> **方案 5：现代 LLM（推荐）**
>
> 直接用支持多语言的 LLM：
> - **GPT-4 / Claude / Gemini**：原生多语言
> - **Qwen 2.5**：中英 + 多语言
> - **DeepSeek**：中英为主，多语言支持
> - **Command R+**：多语言优化
>
> **优点**：
> - 性能极强
> - 灵活（prompting 即可）
> - 零样本能力
>
> **缺点**：
> - 推理成本高
> - 长尾语言仍弱
>
> **多语言分类的实战技巧**
>
> **1. 数据策略**
>
> - **数据增强**：翻译扩增低资源语言数据
> - **回译（Back-translation）**：A → B → A'，增加多样性
> - **伪标签**：用强模型给低资源语言打标签
>
> **2. 跨语言迁移**
>
> - 在高资源语言（如英语）上微调
> - 用 zero-shot 直接迁移到低资源语言
> - mBERT、XLM-R 都有这能力
>
> **3. 评测**
>
> - 每种语言单独评测
> - 关注低资源语言的性能
> - 监控跨语言一致性
>
> **4. 词表设计**
>
> - 多语言 BPE / SentencePiece
> - 防止单语言主导词表
> - tokenizer 平衡训练
>
> **5. 长尾处理**
>
> - 用 LLM 对低资源语言 few-shot
> - 检索增强（多语言 RAG）
> - Active learning（人工标注关键样本）
>
> **典型挑战案例**
>
> **案例 1：跨境电商商品分类**
> - 商品描述：英、中、西、日、阿
> - 类别：电子产品、服装、家居……
>
> 方案：
> - XLM-R 微调
> - 数据：英语人工标注，其他语言翻译扩增
> - 监控每语言 F1
>
> **案例 2：国际客服意图识别**
> - 用户消息：30+ 语言
> - 意图：退款、咨询、投诉、转人工
>
> 方案：
> - mBERT 微调
> - 多语言 embedding + KNN 兜底
> - LLM 处理长尾语言
>
> **案例 3：内容审核**
> - UGC 内容：全球用户多语言
> - 分类：合规 / 违规 / 严重违规
>
> 方案：
> - 多语言 BERT
> - 重点优化 recall（漏判成本高）
> - 人工 review 高风险内容
>
> **性能基准（XNLI 数据集）**
>
> XNLI 是跨语言 NLI 基准，15 种语言：
>
> | 模型 | 英语 | 中文 | 斯瓦希里语 |
> |---|---|---|---|
> | mBERT | 82 | 76 | 60 |
> | XLM-R | 86 | 78 | 70 |
> | mT5 | 86 | 78 | 71 |
> | GPT-4 | ~90 | ~88 | ~80 |
>
> **总结**：多语种文本分类的核心挑战：
> - **语言多样性**（语法 / 字符 / 方向）
> - **资源不均衡**（英语多、低资源少）
> - **文化差异**
> - **跨语言对齐**
> - **词表设计**
> - **评测困难**
>
> **主要方案**：
> 1. **每语言单独训练**（专精但贵）
> 2. **多语言预训练**（mBERT / XLM-R，主流）
> 3. **翻译 + 单语言模型**（简单但损失信息）
> 4. **跨语言 embedding**（LASER / LaBSE）
> 5. **现代 LLM**（GPT-4 / Claude / Qwen，性能最强）
>
> **实战推荐**：用 **XLM-R 微调** 做生产级多语言分类，**LLM** 做长尾语言兜底，**跨语言 embedding** 做 zero-shot 冷启动。

### [你有什么办法可以比较好地解决 BERT 输入长度的限制？](https://www.mianshiya.com/bank/1906189461556076546/question/1821834675660820482)

> **答案**：
>
> **解决 BERT 输入长度的限制**
>
> BERT 限制 512 token，处理长文档（如合同、论文、长篇新闻）时需要技巧。
>
> **为什么 BERT 限制 512**
>
> 1. **预训练时固定**：BERT 在 max_len=512 上预训练
> 2. **位置编码**：learned PE 超过 512 没见过
> 3. **资源消耗**：N=1024 时 attention 是 N=512 的 4 倍
> 4. **性能**：超长序列训练 / 推理慢
>
> 强行超过 512：
> - 位置编码报错（learned PE 越界）
> - 即使截断位置编码，效果也差
>
> **解决方案**
>
> **方案 1：截断（Truncation）**
>
> 最简单：取前 510 token（留 2 个给 [CLS] [SEP]）。
>
> 适用：开头信息最关键（如新闻、邮件）。
>
> 缺点：丢失后半部分。
>
> **方案 2：滑窗（Sliding Window）**
>
> 把长文档切多个重叠窗口：
> ```
> 文档：t_1 ... t_2000
>
> 窗口 1：t_1 ... t_512
> 窗口 2：t_256 ... t_768  ← 重叠 256
> 窗口 3：t_512 ... t_1024
> ...
> ```
>
> 每个窗口单独过 BERT，结果合并。
>
> **合并方式**：
> - **max pooling**：每个 token 在所有窗口中取 max
> - **mean pooling**：取平均
> - **加权平均**：按位置权重（中间权重高）
>
> 适用：NER、QA、序列标注。
>
> 缺点：计算量 = 窗口数 × 单窗口成本。
>
> **方案 3：分层文档表示（Hierarchical）**
>
> 两层处理：
> - 第一层：BERT 编码每个段落 / 窗口
> - 第二层：在段落表示上做 RNN / Attention
>
> ```
> 文档
>   ├── 段 1 → BERT → v_1
>   ├── 段 2 → BERT → v_2
>   └── 段 N → BERT → v_N
>
> [v_1, ..., v_N] → LSTM / Self-Attention → 文档表示
> ```
>
> 例：HiBERT、HIBERT。
>
> 适用：长文档分类、聚类。
>
> **方案 4：稀疏注意力（Sparse Attention）**
>
> 改造 BERT，attention 不是全连接，而是局部 + 全局：
> - **Longformer**：滑窗 + 全局 token，支持 32K+
> - **BigBird**：块状稀疏 + 全局 + 随机，理论 O(N)
> - **ETC**（Extended Transformer Construction）：把输入分组
>
> 适用：长上下文 QA、文档摘要。
>
> **方案 5：长序列预训练模型**
>
> 直接用支持长序列的模型替代 BERT：
>
> | 模型 | 最大长度 |
> |---|---|
> | Longformer | 4096 |
> | BigBird | 4096+ |
> | Reformer | 16K（LSH attention） |
> | Linformer | 数 K（线性 attention） |
> | LongT5 | 16K+ |
> | LED (Longformer Encoder-Decoder) | 16K |
> | **现代 LLM** | **32K - 1M** |
>
> **方案 6：分块 + 聚合**
>
> 把长文档分块，分别处理，聚合结果：
>
> ```python
> def predict_long_doc(text):
>     chunks = split(text, max_len=510)
>     chunk_preds = [bert_predict(c) for c in chunks]
>     return aggregate(chunk_preds)  # 投票 / 平均 / max
> ```
>
> 适用：分类、回归任务。
>
> **方案 7：抽取式先（Extractive + Abstractive）**
>
> 两阶段：
> 1. 用浅模型（如 TF-IDF、BM25）抽关键段落
> 2. 用 BERT 处理抽出的段落
>
> 适用：长文档摘要、QA。
>
> **方案 8：分块 + RAG（检索增强）**
>
> ```
> 长文档 → 切块 → embedding → 向量数据库
>                               ↓
> query → 检索 top-K 相关块 → 喂给 BERT
> ```
>
> 适用：长文档问答、知识库查询。
>
> **方案 9：动态位置编码外推**
>
> 改造 BERT 的位置编码，使其能处理超长：
> - **Position Interpolation**：缩放位置索引
> - **NTK-aware**：调整频率
> - **YaRN**：分段缩放
>
> 但 BERT 是 learned PE，这些方法主要适用于 RoPE/ALiBi（Llama 系）。
>
> **方案 10：直接用现代 LLM**
>
> 最实用的方法：用支持长上下文的 LLM 替代 BERT：
> - **GPT-4 Turbo**：128K
> - **Claude 3.5 Sonnet**：200K
> - **Gemini 1.5 Pro**：1M
> - **Llama 3 Long**：100K+
> - **Qwen 2 Long**：128K
>
> 适用：长文档问答、摘要、生成。
>
> **典型场景的推荐**
>
> | 任务 | 短文档 | 长文档 |
> |---|---|---|
> | 分类 | BERT | Longformer / 滑窗 / 分块 |
> | NER | BERT | 滑窗 / Longformer |
> | QA | BERT | 滑窗 / Longformer / LLM |
> | 摘要 | BERT | LLM / LED / 抽取式 |
> | 检索 | BERT embedding | ColBERT / LLM |
>
> **实战代码（滑窗 NER）**
>
> ```python
> def ner_long_text(text, model, tokenizer, window=510, stride=256):
>     tokens = tokenizer.tokenize(text)
>     predictions = []
>
>     for start in range(0, len(tokens), stride):
>         end = min(start + window, len(tokens))
>         chunk = tokens[start:end]
>         inputs = tokenizer(chunk, return_tensors='pt')
>         outputs = model(**inputs)
>         preds = decode_predictions(outputs)
>
>         # 重叠部分取 max 或平均
>         for i, pred in enumerate(preds):
>             global_pos = start + i
>             if global_pos < len(predictions):
>                 predictions[global_pos] = merge(predictions[global_pos], pred)
>             else:
>                 predictions.append(pred)
>
>         if end == len(tokens):
>             break
>
>     return predictions
> ```
>
> **总结**：解决 BERT 长度限制的 10 种方案：
> 1. **截断**（简单）
> 2. **滑窗 + 重叠**（通用）
> 3. **分层文档表示**
> 4. **稀疏注意力**（Longformer / BigBird）
> 5. **长序列预训练模型**
> 6. **分块 + 聚合**（分类任务）
> 7. **抽取式预处理**
> 8. **分块 + RAG**
> 9. **位置编码外推**（适合 RoPE）
> 10. **直接用现代 LLM**（最实用）
>
> **现代工程趋势**：对于长上下文，**直接用 LLM（GPT-4 / Claude / Gemini / Llama Long）** 比"扩展 BERT"更经济实用。BERT 仍是短文本理解任务的王者，长文档交给 LLM。

### [支持向量机可以用于文本分类任务吗？若可以，请说明。](https://www.mianshiya.com/bank/1906189461556076546/question/1821834676600344578)

> **答案**：
>
> **支持向量机（SVM）用于文本分类**
>
> **答案：可以**。在深度学习流行之前，SVM 是文本分类的**主流方法**，至今在某些场景仍是强 baseline。
>
> **为什么 SVM 适合文本分类**
>
> **1. 文本特征是高维稀疏**
> - Bag-of-Words、TF-IDF：几万到几百万特征
> - 大部分为 0（稀疏）
> - SVM 对高维稀疏友好（Hinge Loss + L2 正则）
>
> **2. Margin 最大化**
> - SVM 找最大间隔超平面
> - 泛化好，不易过拟合
> - 适合"特征多、样本少"
>
> **3. Kernel 灵活**
> - Linear Kernel：高效（文本任务首选）
> - RBF Kernel：非线性（少用，文本不需要）
> - 文本通常 linear 已足够
>
> **4. 数学成熟**
> - 凸优化，全局最优
> - 解释性好
> - 训练稳定
>
> **SVM 文本分类的标准流程**
>
> ```
> 文本 → 预处理 → TF-IDF 向量化 → SVM 训练 → 预测
> ```
>
> **步骤 1：预处理**
> - 分词（中文）/ tokenize（英文）
> - 去停用词、去低频词
> - stemming / lemmatization
>
> **步骤 2：特征向量化**
> - **Bag-of-Words (BoW)**：词频向量
> - **TF-IDF**：词频 × 反文档频率
> - **N-gram**：捕获短语
> - **特征维度**：通常 1万-100万
>
> **步骤 3：训练 SVM**
> - Linear SVM（最常用）
> - C 参数：正则强度（典型 1-10）
> - class_weight='balanced'：处理不平衡
>
> **步骤 4：预测**
> - 直接 predict
> - 输出概率（Platt scaling）
>
> **示例代码（Python）**
>
> ```python
> from sklearn.feature_extraction.text import TfidfVectorizer
> from sklearn.svm import LinearSVC
> from sklearn.pipeline import Pipeline
>
> # 构建流水线
> pipeline = Pipeline([
>     ('tfidf', TfidfVectorizer(
>         max_features=100000,
>         ngram_range=(1, 2),  # unigram + bigram
>         stop_words='english',
>         sublinear_tf=True
>     )),
>     ('svm', LinearSVC(
>         C=5.0,
>         class_weight='balanced',
>         max_iter=1000
>     ))
> ])
>
> # 训练
> pipeline.fit(X_train, y_train)
>
> # 预测
> predictions = pipeline.predict(X_test)
> ```
>
> **SVM 文本分类的典型性能**
>
> | 数据集 | SVM | BERT-base | 差距 |
> |---|---|---|---|
> | 20 Newsgroups | ~92% | ~95% | 小 |
> | IMDB 情感 | ~90% | ~94% | 中 |
> | AG News | ~92% | ~95% | 小 |
> | 中文情感分类 | ~88% | ~93% | 中 |
> | Yahoo Answers | ~80% | ~88% | 大 |
>
> **SVM vs BERT：何时选哪个**
>
> | 维度 | SVM | BERT |
> |---|---|---|
> | 训练数据 | 1K-100K | 10K-数 M |
> | 训练时间 | 秒 - 分钟 | 小时 - 天 |
> | 推理速度 | 极快 | 较慢 |
> | 部署成本 | 极低（CPU） | 高（GPU） |
> | 性能上限 | 中 | 高 |
> | 标注需求 | 中 | 高 |
> | 解释性 | 强 | 弱 |
> | 多任务 | 需分别训练 | 共享预训练 |
> | 上下文理解 | 弱 | 强 |
> | 多语言 | 需分别训练 | 多语言 BERT |
>
> **SVM 仍是好选择的场景**
>
> 1. **小数据集**（< 10K 样本）：SVM 性价比极高
> 2. **资源受限**：CPU 部署、嵌入式、移动端
> 3. **快速 baseline**：项目初期快速验证
> 4. **解释性需求**：金融、医疗、法律需要解释
> 5. **明确关键词**：如垃圾邮件（特定词权重高）
> 6. **特征工程做足**：领域专家已设计好特征
>
> **BERT 更好的场景**
>
> 1. **大数据集**：BERT 优势明显
> 2. **复杂语义**：上下文敏感（如讽刺、隐式情感）
> 3. **多任务**：一个模型搞定多个任务
> 4. **多语言**：mBERT 跨语言
> 5. **现代 LLM 应用**：作为基础组件
>
> **SVM 文本分类的工程技巧**
>
> **1. 特征选择**
> - 用 chi2、mutual_info 选 top-K 特征
> - 减少噪声、提速
>
> **2. 调参**
> - C：1e-3 到 1e3 log 搜索
> - ngram_range：(1,1), (1,2), (1,3) 试
> - max_df, min_df：过滤过常见 / 过罕见词
>
> **3. 多分类**
> - one-vs-rest（OVR）：每个类训练一个 SVM
> - one-vs-one（OVO）：每对类训练一个
> - LinearSVC 内置支持多分类
>
> **4. 不平衡处理**
> - `class_weight='balanced'`
> - 调整 C 让少数类权重高
> - 配合 SMOTE 等过采样
>
> **5. 集成**
> - 多个 SVM（不同特征）投票
> - 与树模型（XGBoost）集成
> - 与 BERT 集成（深度 + 浅层）
>
> **经典文献**
>
> - **Joachims 1998**《Text Categorization with Support Vector Machines》：奠基性工作
> - **Fan et al. 2008**：LibLinear（大规模线性 SVM）
> - **Facebook fastText**（2016）：基于线性分类器的现代版
>
> **fastText：现代"线性" 文本分类**
>
> fastText 本质是：
> - 浅层神经网络（embedding + 平均 + 线性分类）
> - 等价于"加 n-gram 的 SVM"
> - 极快、效果接近深度模型
> - 工业级文本分类常用
>
> **总结**：SVM 完全可以用于文本分类，**在小数据、CPU 部署、解释性需求、强 baseline 场景下仍是首选**。流程：**TF-IDF + Linear SVM**。性能：弱于 BERT（差距 3-8 个百分点），但快几个数量级。**现代实践**：项目初期用 SVM 验证可行性 → 数据多了上 BERT → 部署用 BERT-distill 或 fastText 平衡性能与成本。理解 SVM 文本分类，对工程化 NLP 系统非常有价值——不是所有任务都需要 Transformer。
