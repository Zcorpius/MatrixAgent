# 部署运维配置层

> **本模块为原创补写，非面试鸭题库爬取内容**。基于公开论文与工程实践整理，覆盖原 14 个模块未深入的「部署运维配置层」领域，共 8 题。完整索引见 [bank-llm-by-topic.md](../bank-llm-by-topic.md)。

---

> 共 8 题

### vLLM 架构深度解析：PagedAttention + Continuous Batching 的工程实现

> **答案**：
>
> vLLM 是 2023 年 UC Berkeley 推出的开源 LLM 推理引擎，目前是**开源 LLM 部署的事实标准**。吞吐比 HuggingFace Transformers 高 10-20×。
>
> **一、vLLM 的核心组件**
>
> ```
> ┌─────────────────────────────────────────────────────┐
> │                    vLLM 架构                        │
>                                                     │
>   ┌──────────────┐
>   │  LLMEngine   │  ← 协调所有组件
>   └──────┬───────┘
>          │
>   ┌──────┴───────┬─────────────┬──────────────┐
>   │              │             │              │
>   ▼              ▼             ▼              ▼
> ┌──────┐  ┌───────────┐  ┌─────────┐  ┌──────────┐
> │Scheduler│ │ModelRunner│  │BlockSpace│  │  Cache   │
> │         │ │           │  │Manager  │  │ Engine   │
> └──────┘  └───────────┘  └─────────────┘  └──────────┘
>                                                │
>                                          ┌─────┴─────┐
>                                          │  GPU HBM  │
>                                          │  KV Cache │
>                                          │  (Paged)  │
>                                          └───────────┘
> ```
>
> 1. **LLMEngine**：顶层调度，接收请求、调度、返回结果。
> 2. **Scheduler**：决定下一步执行哪些 sequence，何时 preempt。
> 3. **ModelRunner**：执行 GPU 上的 attention / FFN 前向计算。
> 4. **BlockSpaceManager**：管理 KV cache 的分页分配。
> 5. **Cache Engine**：实际存储 KV cache 的 GPU 内存。
>
> **二、PagedAttention（前面已介绍原理）**
>
> vLLM 把 KV cache 切成固定大小的 block（如 16 token 一页），通过 block table 管理。
>
> **关键配置**：
>
> ```python
> # vLLM 启动参数
> --block-size 16                  # 每 block 含 16 个 token
> --gpu-memory-utilization 0.9     # GPU 显存使用率上限
> --swap-space 4                   # CPU swap 空间（GB）
> --max-num-batched-tokens 8192    # 单 batch 最大 token 数
> --max-num-seqs 256               # 同时并发的 sequence 数
> ```
>
> **三、Continuous Batching（连续批处理）**
>
> **传统 Static Batching**：
>
> ```
> batch=[req1, req2, req3, req4]
> - req1 输出 10 token 后结束
> - req2 输出 50 token 后结束
> - req3 输出 200 token 后结束
> - req4 输出 30 token 后结束
>
> 整个 batch 必须等所有请求结束才能开始下一批。
> → req1 早早结束后，GPU 空闲等 req3，资源浪费。
> ```
>
> **Continuous Batching（Iteration-Level Scheduling）**：
>
> ```
> 时间步 1: batch = [req1, req2, req3, req4]
> 时间步 2: req1 结束，新请求 req5 加入 → batch = [req2, req3, req4, req5]
> 时间步 3: req2 结束，新请求 req6 加入 → batch = [req3, req4, req5, req6]
> ...
>
> 每个 time step 检查：
> 1. 哪些 sequence 结束了？→ 移出 batch，释放 KV cache。
> 2. 队列里有哪些等待？→ 加入 batch。
> 3. 显存够吗？→ 不够就 preempt（暂停）某些 sequence。
> ```
>
> **优势**：
>
> - GPU 永远满载，无空闲。
> - 短请求不被长请求拖累。
> - 吞吐提升 5-10×。
>
> **四、调度策略**
>
> vLLM Scheduler 支持：
>
> 1. **FCFS（First Come First Serve）**：默认。
> 2. **Preemption**：显存不足时，淘汰或 swap 到 CPU。
>    - **Recomputation**：丢弃 KV，需要时重算（慢但简单）。
>    - **Swap**：把 KV swap 到 CPU，需要时 swap 回来（快但占内存）。
>
> **五、Prefix Caching（前缀缓存）**
>
> vLLM 自动检测共享前缀（如 system prompt），在多个请求间复用 KV：
>
> ```python
> --enable-prefix-caching   # 启用前缀缓存
> ```
>
> **例**：
>
> ```
> 请求 1: [system_prompt] + [user_msg_1]
> 请求 2: [system_prompt] + [user_msg_2]
> 请求 3: [system_prompt] + [user_msg_3]
>
> system_prompt 的 KV 只算一次，后续请求复用。
> ```
>
> **收益**：
>
> - 节省 30-80% 的 prefill 计算（取决于共享前缀比例）。
> - 降低 TTFT（首 token 延迟）。
>
> **六、典型部署架构**
>
> ```
> [Client]
>    │
>    ▼
> [Load Balancer] (Nginx / HAProxy)
>    │
>    ├──▶ [vLLM Server 1] (GPU 0-7)
>    ├──▶ [vLLM Server 2] (GPU 8-15)
>    └──▶ [vLLM Server N]
>
> 每个 vLLM Server:
>    - 单机 8 GPU（TP=8）
>    - 多并发 sequence（max-num-seqs=256）
>    - 启用 PagedAttention + Continuous Batching + Prefix Caching
> ```
>
> **七、关键启动参数详解**
>
> ```bash
> python -m vllm.entrypoints.openai.api_server \
>   --model meta-llama/Llama-3-8B-Instruct \
>   --tensor-parallel-size 8 \                    # 单机 8 卡 TP
>   --gpu-memory-utilization 0.9 \                 # 90% 显存
>   --max-model-len 8192 \                         # 最大上下文长度
>   --max-num-seqs 256 \                           # 并发 sequence 数
>   --max-num-batched-tokens 8192 \                # 单 batch 最大 token
>   --enable-prefix-caching \                      # 启用前缀缓存
>   --swap-space 4 \                               # CPU swap 空间
>   --quantization awq \                           # 量化方案
>   --dtype bfloat16 \                             # 计算精度
>   --enforce-eager                                # 不用 CUDA Graph（调试）
> ```
>
> **调参经验**：
>
> - `gpu-memory-utilization`：先 0.9，OOM 时降。
> - `max-num-seqs`：根据显存调，目标是 GPU 满载。
> - `max-num-batched-tokens`：影响吞吐和延迟，trade-off。
> - `quantization`：awq / gptq 省显存，但精度略损。
>
> **八、性能指标**
>
> | 指标                       | 意义                       | 优化方向              |
> |----------------------------|----------------------------|-----------------------|
> | TTFT                       | 首 token 延迟              | Prefix Caching、TP    |
> | TPOT                       | 每 token 生成时间          | 量化、Continuous Batch|
> | Throughput (tokens/s/GPU)  | 单 GPU 吞吐                | Batching、Quant       |
> | MFU                        | GPU 算力利用率             | Batching 大、TP 小    |
> | 显存占用                   | 总显存使用                 | PagedAttention、Quant |
>
> **典型性能（A100 80GB × 8，Llama 3 70B）**：
>
> - TTFT：~1s（4K input）。
> - TPOT：~30ms（生成阶段）。
> - 吞吐：~3000 tokens/s/GPU。
> - 并发：支持 100+ 请求。
>
> **九、vLLM 的局限**
>
> 1. **首 token 延迟较高**：相比 TensorRT-LLM 优化少。
> 2. **量化支持有限**：AWQ、GPTQ、FP8 支持，但动态量化弱。
> 3. **多模态较新**：1.0 后支持，但仍不如专用方案。
> 4. **超长上下文（1M+）**：单机难支撑，需分布式。
>
> **十、与其他推理引擎对比**
>
> | 引擎            | 优势                          | 劣势                          |
> |-----------------|-------------------------------|-------------------------------|
> | **vLLM**        | 开源 SOTA、易用、生态完善     | 性能略逊 TensorRT            |
> | **TensorRT-LLM**| NVIDIA 官方，性能最强         | 编译复杂，闭源友好度低        |
> | **SGLang**      | RadixAttention，复杂应用快    | 较新，生态发展                |
> | **TGI**         | HuggingFace 出品，简单        | 性能中等                      |
> | **llama.cpp**   | CPU 友好，边缘部署            | 大模型性能差                  |
> | **MLC-LLM**     | 移动端友好                    | 生态小                        |
>
> **总结**：vLLM 是 2024-2026 LLM 部署的事实标准——**PagedAttention + Continuous Batching + Prefix Caching** 三大核心创新让开源 LLM 服务化变得简单。掌握 vLLM 的启动参数、调优方法、性能指标，是部署运维 LLM 应用的基础。理解 vLLM 架构也是面试高频考点——它能区分"用过 LLM"和"懂 LLM 工程栈"的候选人。

### TensorRT-LLM vs vLLM：什么场景选哪个？

> **答案**：
>
> TensorRT-LLM（NVIDIA 官方）和 vLLM（UC Berkeley 开源）是当前两大主流 LLM 推理引擎，定位不同，选型需匹配场景。
>
> **一、定位对比**
>
> | 维度          | TensorRT-LLM               | vLLM                          |
> |---------------|----------------------------|-------------------------------|
> | 开发方        | NVIDIA 官方                | UC Berkeley + 社区            |
> | 优化方向      | 极致性能                   | 易用 + 通用                   |
> | 部署门槛      | 高（需编译）               | 低（pip install）             |
> | 模型支持      | NVIDIA GPU only            | 多硬件（NVIDIA、AMD、TPU）    |
> | 量化          | 强（FP8、INT4、INT8）      | 中（AWQ、GPTQ）               |
> | 生态          | 闭源但免费                 | 开源 Apache 2.0               |
> | 典型客户      | 大厂、追求极致性能         | 中小厂、研究机构、个人        |
>
> **二、TensorRT-LLM 的核心优势**
>
> 1. **NVIDIA 官方优化**：直接对接 CUDA / cuDNN / TensorRT，深度优化。
> 2. **In-Flight Batching**（NVIDIA 版 Continuous Batching）：比 vLLM 更激进。
> 3. **FP8 支持**（Hopper GPU）：vLLM 较新才支持，TensorRT-LLM 成熟。
> 4. **Plugin 系统**：自定义 kernel、attention 变体。
> 5. **多 GPU 编译优化**：TP + PP + CP 自动编排。
>
> **三、vLLM 的核心优势**
>
> 1. **易用**：`pip install vllm` 即用，无需编译。
> 2. **开源生态**：社区活跃，模型 / 量化 / 工具链更新快。
> 3. **多硬件**：支持 NVIDIA + AMD ROCm + TPU + CPU。
> 4. **OpenAI API 兼容**：直接 drop-in 替换。
> 5. **模型支持广**：几乎所有开源模型首发支持。
>
> **四、性能对比**
>
> **基准测试**（Llama 3 70B，A100 80GB × 8，2024 数据）：
>
> | 指标           | TensorRT-LLM (FP8) | vLLM (FP16) | vLLM (AWQ INT4) |
> |----------------|--------------------|-------------|-----------------|
> | 吞吐（tok/s/GPU）| ~6000              | ~3000       | ~4500           |
> | TTFT（ms）     | ~500               | ~1000       | ~800            |
> | 显存占用       | 较低（FP8）        | 高（FP16）  | 中（INT4）      |
> | 精度损失       | <1%                | 0%          | 1-3%            |
>
> TensorRT-LLM 在 FP8 下吞吐约为 vLLM FP16 的 2×，但 vLLM 量化后差距缩小。
>
> **五、易用性对比**
>
> **vLLM 部署**（5 分钟）：
>
> ```bash
> pip install vllm
> python -m vllm.entrypoints.openai.api_server --model meta-llama/Llama-3-8B-Instruct
> ```
>
> **TensorRT-LLM 部署**（数小时到数天）：
>
> ```bash
> # 1. 安装
> pip install tensorrt-llm
>
> # 2. 转换模型为 TensorRT engine
> python convert_checkpoint.py --model_dir ./llama-3-8b \
>     --output_dir ./llama-3-8b-trt \
>     --dtype bfloat16
>
> # 3. 编译 engine（耗时 30-60 分钟）
> trtllm-build --checkpoint_dir ./llama-3-8b-trt \
>     --output_dir ./trt_engines \
>     --gemm_plugin bfloat16 \
>     --max_batch_size 256 \
>     --max_input_len 8192 \
>     --max_output_len 1024
>
> # 4. 启动 server
> python -m tensorrt_llm.run --engine_dir ./trt_engines
> ```
>
> **代价**：
>
> - 模型升级（如 Llama 3 → Llama 4）需要重新编译。
> - 改 prompt 长度、batch size 都可能需要重编。
> - vLLM 改参数即时生效。
>
> **六、选型决策树**
>
> ```
> 你有 NVIDIA H100 / H200 GPU 吗？
>     │
>     ├─ 是 → 追求极致性能？
>     │       │
>     │       ├─ 是 → 团队有 TensorRT 经验？
>     │       │       │
>     │       │       ├─ 是 → TensorRT-LLM（FP8 收益最大）
>     │       │       └─ 否 → vLLM（投入回报比更好）
>     │       │
>     │       └─ 否 → vLLM（默认选择）
>     │
>     └─ 否（AMD / TPU / CPU）→ vLLM
> ```
>
> **七、典型场景**
>
> **场景 A：大厂核心服务**
>
> - 模型：Llama 3 70B / GPT-4 级别。
> - 硬件：H100 集群。
> - 优化目标：极致吞吐、低延迟。
> - **选 TensorRT-LLM**：FP8 + In-Flight Batching，吞吐比 vLLM 高 50-100%。
>
> **场景 B：中小厂 / 创业公司**
>
> - 模型：Llama 3 8B / Qwen 2.5 7B。
> - 硬件：A100 / 4090。
> - 优化目标：性价比、快速迭代。
> - **选 vLLM**：易用、社区支持、量化够用。
>
> **场景 C：研究 / 实验**
>
> - 模型：频繁切换、自定义模型。
> - 优化目标：灵活、快速试错。
> - **选 vLLM**：开箱即用。
>
> **场景 D：边缘部署**
>
> - 模型：小模型（< 7B）。
> - 硬件：消费级 GPU / Mac / 手机。
> - **选 llama.cpp / MLC-LLM**（不是 TensorRT 或 vLLM）。
>
> **场景 E：AMD GPU**
>
> - 硬件：MI300X 等 AMD GPU。
> - **选 vLLM**（ROCm 支持）或 vLLM fork（ROCm 优化版）。
>
> **八、混合部署策略**
>
> 大厂常见模式：
>
> ```
> [流量路由]
>     │
>     ├─ 高 QPS / 长请求 → TensorRT-LLM 集群
>     ├─ 低 QPS / 短请求 → vLLM 集群
>     └─ 实验性流量    → 各引擎对比
> ```
>
> 根据流量特征路由，最大化 ROI。
>
> **九、其他推理引擎**
>
> | 引擎            | 特点                                  | 适用场景                  |
> |-----------------|---------------------------------------|---------------------------|
> | **SGLang**      | RadixAttention，结构化输出强          | Agent、复杂应用           |
> | **TGI**         | HuggingFace 出品，简单                | 中小规模部署              |
> | **DeepSpeed-FastGen**| 微软，Dynamic Splitfuse          | 研究、特定场景            |
> | **LightLLM**    | 国产，部分场景超过 vLLM              | 性价比场景                |
> | **LMDeploy**    | 上海 AI 实验室，国产模型支持好        | Qwen / DeepSeek 部署      |
> | **mlc-llm**     | TVM 后端，跨硬件                      | 移动端、边缘              |
> | **llama.cpp**   | C++ 实现，CPU/GPU 通用                | 个人、边缘、GGUF          |
>
> **十、实战建议**
>
> 1. **默认选 vLLM**：80% 场景够用，易上手。
> 2. **预算允许且追求性能再上 TensorRT-LLM**：投入产出比要看清楚。
> 3. **国产模型用 LMDeploy**：Qwen / DeepSeek 优化好。
> 4. **结构化输出多选 SGLang**：RadixAttention + Tool Use 优化。
> 5. **监控指标统一**：TTFT、TPOT、吞吐、显存——任何引擎都用同样指标评估。
>
> **总结**：vLLM 和 TensorRT-LLM 是当前 LLM 推理的两大主流——**vLLM 易用、开源、通用，TensorRT-LLM 性能最强但门槛高**。选型核心看「**硬件 + 团队 + 性能要求**」三要素：H100 + 大厂团队 + 极致性能选 TensorRT-LLM；其他场景默认 vLLM。理解这两个引擎的差异和适用场景，是 LLM 应用部署运维的关键决策点。

### SGLang / TGI / LMDeploy：其他推理引擎的定位与选择

> **答案**：
>
> 除了 vLLM 和 TensorRT-LLM，还有几个值得关注的推理引擎，各有定位：
>
> **一、SGLang：结构化生成 + Agent 优化**
>
> **核心特色**：
>
> 1. **RadixAttention**：用 Radix Tree（基数树）管理 KV cache，自动复用共享前缀。
>    - 比 vLLM 的 Prefix Caching 更精细。
>    - 适合 Agent、Few-shot、多轮对话。
>
> 2. **结构化输出**：原生支持 JSON Schema、正则约束。
>    - 比约束生成（constrained decoding）快 10×。
>
> 3. **SGLang DSL**：DSL 写复杂应用（Agent 工作流、Multi-turn）。
>
> 4. **Program-Level Optimization**：跨请求优化（如 fork 多个并行序列）。
>
> **优势场景**：
>
> - Agent 应用（多步调用、工具使用）。
> - 结构化输出（JSON、function calling）。
> - Few-shot learning（多示例共享前缀）。
>
> **示例**：
>
> ```python
> import sglang as sgl
>
> @sgl.function
> def multi_step_qa(s, question):
>     s += system("You are a helpful assistant.")
>     s += user(question)
>     s += assistant(s.gen("answer", max_tokens=256))
>     s += user("Explain your reasoning.")
>     s += assistant(s.gen("reasoning", max_tokens=512))
>
> # 启动 server
> # python -m sglang.launch_server --model-path meta-llama/Llama-3-8B-Instruct
> ```
>
> **vs vLLM**：
>
> - **吞吐**：Agent / 结构化场景，SGLang 快 2-5×。
> - **通用场景**：差距小。
> - **生态**：vLLM 更成熟，SGLang 发展快。
>
> **二、TGI（Text Generation Inference）**
>
> **核心特色**：
>
> - HuggingFace 官方出品，与 HF 生态深度集成。
> - 部署简单：`docker run`。
> - 支持 Flash Attention、PagedAttention、Quantization。
> - 与 HuggingFace Inference Endpoints、Hub 深度整合。
>
> **优势场景**：
>
> - HuggingFace 生态深度用户。
> - 简单部署（docker 一行）。
> - 中小规模服务。
>
> **示例**：
>
> ```bash
> docker run --gpus all -p 8080:80 \
>   -v $PWD/data:/data \
>   ghcr.io/huggingface/text-generation-inference:latest \
>   --model-id meta-llama/Llama-3-8B-Instruct \
>   --num-shard 4
> ```
>
> **vs vLLM**：
>
> - **性能**：vLLM 略优（vLLM 调度更激进）。
> - **易用**：TGI 略优（Docker 部署）。
> - **生态**：vLLM 更广，TGI 与 HF 深度绑定。
> - **更新速度**：vLLM 更快。
>
> **现状**：TGI 市场份额被 vLLM 蚕食，但仍是 HuggingFace 用户的默认选择。
>
> **三、LMDeploy**
>
> **核心特色**：
>
> - 上海 AI 实验室（OpenRobot）出品。
> - 国产模型支持最好（Qwen、DeepSeek、InternLM）。
> - TurboMind 引擎（C++）+ PyTorch 后端。
> - 支持 W4A16 量化（4bit weight + 16bit activation）。
>
> **优势场景**：
>
> - 国产模型部署（Qwen / DeepSeek）。
> - W4A16 量化场景（比 AWQ 更省显存）。
> - 中文场景优化。
>
> **示例**：
>
> ```bash
> pip install lmdeploy
>
> # 转换模型
> lmdeploy convert --model-name llama3 --model-path ./llama-3-8b --dst-path ./lmdeploy
>
> # 启动 server
> lmdeploy serve api_server ./lmdeploy --server-port 8000
> ```
>
> **vs vLLM**：
>
> - **国产模型性能**：LMDeploy 略优（针对 Qwen / DeepSeek 优化）。
> - **量化**：W4A16 LMDeploy 更激进。
> - **生态**：vLLM 更广。
>
> **四、DeepSpeed-FastGen**
>
> 微软出品，核心创新 **Dynamic Splitfuse**：
>
> - 把长 prompt 分块，慢慢融入生成过程。
> - 比 Continuous Batching 在长 prompt 场景更优。
> - 但生态较小，主要用于研究。
>
> **五、LightLLM**
>
> 国产开源推理引擎：
>
> - 部分场景性能超过 vLLM。
> - 支持多种模型架构。
> - 文档相对少。
> - 适合追求极致性能的中小团队。
>
> **六、推理引擎对比表**
>
> | 引擎            | 易用 | 性能 | 国产模型 | 结构化输出 | 量化支持 | 适用场景                |
> |-----------------|------|------|----------|------------|----------|-------------------------|
> | **vLLM**        | ★★★★★ | ★★★★ | ★★★★    | ★★★        | ★★★★     | 通用首选                |
> | **TensorRT-LLM**| ★★   | ★★★★★| ★★★      | ★★         | ★★★★★    | 大厂、H100、极致性能    |
> | **SGLang**      | ★★★  | ★★★★ | ★★★★    | ★★★★★      | ★★★★     | Agent、结构化输出       |
> | **TGI**         | ★★★★★ | ★★★  | ★★★     | ★★         | ★★★      | HuggingFace 生态        |
> | **LMDeploy**    | ★★★  | ★★★★ | ★★★★★   | ★★★        | ★★★★★    | 国产模型                |
> | **LightLLM**    | ★★   | ★★★★ | ★★★★    | ★★         | ★★★      | 性价比                  |
> | **DeepSpeed-FastGen**| ★★★ | ★★★★ | ★★★ | ★★      | ★★★      | 研究                    |
> | **llama.cpp**   | ★★★  | ★★   | ★★★     | ★          | ★★★★★    | 边缘、CPU               |
>
> **七、按场景选择**
>
> **场景 A：通用 LLM 服务**
>
> - 推荐：**vLLM**
> - 理由：易用、生态成熟、性能足够。
>
> **场景 B：Agent 应用**
>
> - 推荐：**SGLang**
> - 理由：RadixAttention + 结构化输出优化。
>
> **场景 C：大厂核心服务（H100 集群）**
>
> - 推荐：**TensorRT-LLM**
> - 理由：FP8 + In-Flight Batching，吞吐最高。
>
> **场景 D：HuggingFace 生态深度用户**
>
> - 推荐：**TGI**
> - 理由：docker 一行部署，HF Hub 集成。
>
> **场景 E：Qwen / DeepSeek 部署**
>
> - 推荐：**LMDeploy** 或 **vLLM**
> - 理由：国产模型优化。
>
> **场景 F：CPU / 边缘部署**
>
> - 推荐：**llama.cpp**
> - 理由：GGUF 格式 + CPU 优化。
>
> **八、迁移与多引擎支持**
>
> **OpenAI API 协议**已成为事实标准：
>
> - vLLM、TGI、SGLang、LMDeploy 都兼容 OpenAI API。
> - 客户端代码无需修改，只需改 base_url。
>
> ```python
> from openai import OpenAI
>
> # vLLM
> client = OpenAI(base_url="http://localhost:8000/v1", api_key="EMPTY")
>
> # TGI
> client = OpenAI(base_url="http://localhost:8080/v1", api_key="EMPTY")
>
> # SGLang
> client = OpenAI(base_url="http://localhost:30000/v1", api_key="EMPTY")
> ```
>
> **价值**：随时切换引擎，不被锁定。
>
> **九、性能基准（Llama 3 8B，A100 80GB，2024）**
>
> | 引擎            | 吞吐（tok/s） | TTFT（ms） | 显存（GB） |
> |-----------------|---------------|------------|------------|
> | vLLM            | 4500          | 200        | 18         |
> | TensorRT-LLM    | 5500          | 150        | 16         |
> | SGLang          | 4800          | 180        | 18         |
> | TGI             | 3800          | 250        | 19         |
> | LMDeploy        | 4700          | 190        | 17         |
>
> 差距不大，国产模型场景 LMDeploy 略优。
>
> **十、实战建议**
>
> 1. **默认 vLLM**：80% 场景够用。
> 2. **Agent 用 SGLang**：复杂工作流明显优势。
> 3. **国产模型用 LMDeploy**：性能 + 国产生态。
> 4. **保持 OpenAI API 兼容**：便于切换。
> 5. **关注基准但自己测**：官方数字 vs 你的场景可能有差异。
>
> **总结**：除 vLLM / TensorRT-LLM 外，**SGLang（Agent / 结构化）、TGI（HF 生态）、LMDeploy（国产模型）** 是三大值得关注的引擎。选型核心看场景匹配度：通用选 vLLM，Agent 选 SGLang，国产模型选 LMDeploy。所有引擎都兼容 OpenAI API → 不被锁死，随时可切换。理解这个生态，能在不同业务场景选对工具，是部署运维 LLM 应用的工程能力。

### 量化部署实战：GPTQ / AWQ / GGUF / INT4 在 vLLM 中怎么用？精度损失如何？

> **答案**：
>
> **一、为什么需要量化**
>
> LLM 推理显存压力大：
>
> - 70B FP16：140GB（单卡 80GB 装不下）。
> - 70B INT4：35GB（单卡可装）。
> - 8B FP16：16GB（消费级 4090 可装）。
> - 8B INT4：4GB（手机 / 边缘设备可装）。
>
> 量化让大模型部署成为可能。**精度损失 1-3% 换显存 / 算力降低 4×**——通常是划算的。
>
> **二、量化方法分类**
>
> | 类别          | 代表方法                | 特点                                |
> |---------------|-------------------------|-------------------------------------|
> | **Weight-Only** | AWQ、GPTQ             | 只量化 weight，activation 保留 FP16 |
> | **Weight + Activation** | SmoothQuant    | 两者都量化                          |
> | **FP8**       | NVIDIA FP8             | H100 原生支持                       |
> | **INT4 / INT8** | 各种                  | 主流量化精度                        |
> | **1-bit / 2-bit** | BitNet、BitDistiller | 极致压缩，实验中                    |
>
> **三、主流量化方法详解**
>
> **1. GPTQ（Generalized Post-Training Quantization）**
>
> - **论文**：Frantar et al. 2022《GPTQ: Accurate Post-Training Quantization》。
> - **思想**：基于二阶信息（Hessian）的逐层量化。
> - **流程**：
>   1. 用少量校准数据（128 sample）。
>   2. 对每层 weight 量化，用 Hessian 信息补偿误差。
>   3. 输出 INT4（或 INT3、INT8）量化模型。
>
> **优点**：
> - 精度损失小（INT4 < 1%）。
> - 压缩比高（4×）。
> - 推理加速明显。
>
> **缺点**：
> - 量化过程慢（70B 可能要数小时）。
> - 校准数据敏感（领域不匹配会降质）。
>
> **代表**：GPTQ 论文、AutoGPTQ 库。
>
> **2. AWQ（Activation-aware Weight Quantization）**
>
> - **论文**：Lin et al. 2023《AWQ: Activation-aware Weight Quantization for LLM Compression》。
> - **思想**：识别 weight 中的"重要"通道（基于 activation），保护它们不量化。
> - **流程**：
>   1. 用 activation 找出重要 weight（前 1%）。
>   2. 重要 weight 保持 FP16。
>   3. 其余 weight INT4 量化。
>   4. 加 scaling factor。
>
> **优点**：
> - 精度损失比 GPTQ 更小。
> - 推理更快（mixed precision 优化）。
> - 不需要反向传播。
>
> **缺点**：
> - 校准数据需要代表性。
> - 显存略大于 GPTQ（mixed precision）。
>
> **代表**：MIT-HAN-Lab 开源。
>
> **3. SmoothQuant**
>
> - **论文**：Xiao et al. 2022《SmoothQuant: Accurate and Efficient Post-Training Quantization》。
> - **思想**：平滑 activation 的"离群值"（outliers），让 weight + activation 都可 INT8 量化。
> - **流程**：
>   1. 识别 activation 中的 outliers。
>   2. 把 outliers 的"难度"从 activation 转移到 weight。
>   3. 两者都 INT8 量化。
>
> **优点**：
> - W8A8（weight + activation 都 INT8）。
> - 推理加速大（INT8 tensor core）。
>
> **缺点**：
> - 压缩比不如 W4A16。
> - 主要用于 INT8，不是 INT4。
>
> **4. GGUF（前 GGML）**
>
> - **来源**：llama.cpp 项目。
> - **特点**：CPU 友好的量化格式。
> - **量化级别**：
>   - Q4_0、Q4_1、Q5_0、Q5_1、Q8_0（不同精度 / 速度平衡）。
>   - K-quants：Q4_K_S、Q4_K_M、Q5_K_M 等（更细粒度）。
> - **优点**：
>   - CPU 推理（Mac M1/M2/M3、消费级 GPU）。
>   - 文件小（70B INT4 ~35GB）。
>   - 易部署（llama.cpp 单文件）。
> - **缺点**：
>   - 大模型性能不如 vLLM。
>   - 生态相对独立。
>
> **5. FP8（Hopper GPU 专属）**
>
> - **来源**：NVIDIA Hopper 架构原生。
> - **特点**：
>   - E4M3 / E5M2 两种格式。
>   - 硬件级支持，速度无损。
>   - 精度损失 < 1%。
> - **优点**：
>   - 几乎无精度损失。
>   - 性能最强。
> - **缺点**：
>   - 需要 H100 / H200。
>   - A100 不支持。
>
> **四、量化精度损失对比**
>
> | 方法          | 精度      | 显存（70B） | MMLU 损失 | GSM8K 损失 |
> |---------------|-----------|-------------|-----------|------------|
> | FP16（基线）  | 16 bit    | 140GB       | 0         | 0          |
> | SmoothQuant   | W8A8      | 70GB        | <0.5%     | <0.5%      |
> | GPTQ          | INT4      | 35GB        | 0.5-1%    | 0.5-1%     |
> | AWQ           | INT4      | 36GB        | 0.3-0.8%  | 0.3-0.7%   |
> | GGUF Q4_K_M   | INT4-5    | 40GB        | 1-2%      | 1-2%       |
> | FP8           | 8 bit     | 70GB        | <0.3%     | <0.3%      |
> | BitDistiller  | 1.58 bit  | 14GB        | 3-5%      | 5-10%      |
>
> **AWQ 和 GPTQ 是 INT4 主流选择**，精度损失都在 1% 内。
>
> **五、在 vLLM 中使用量化**
>
> **AWQ 模型**：
>
> ```bash
> # 启动 AWQ 量化模型
> vllm serve TheBloke/Llama-2-7B-AWQ \
>   --quantization awq \
>   --dtype half
> ```
>
> **GPTQ 模型**：
>
> ```bash
> vllm serve TheBloke/Llama-2-7B-GPTQ \
>   --quantization gptq \
>   --dtype half
> ```
>
> **FP8 模型（H100）**：
>
> ```bash
> vllm serve neuralmagic/Meta-Llama-3-70B-Instruct-FP8 \
>   --quantization fp8 \
>   --kv-cache-dtype fp8
> ```
>
> **自己量化模型（AWQ）**：
>
> ```python
> from awq import AutoAWQForCausalLM
> from transformers import AutoConfig, AutoTokenizer
>
> model_path = "./llama-3-8b"
> quant_path = "./llama-3-8b-awq"
> quant_config = { "zero_point": True, "q_group_size": 128, "w_bit": 4 }
>
> model = AutoAWQForCausalLM.from_pretrained(model_path)
> tokenizer = AutoTokenizer.from_pretrained(model_path)
>
> # 量化
> model.quantize(tokenizer, quant_config=quant_config)
> model.save_quantized(quant_path)
> ```
>
> **六、选型决策**
>
> ```
> 部署在什么硬件？
>     │
>     ├─ H100 / H200 → FP8（精度损失最小）
>     │
>     ├─ A100 / 4090 →
>     │       │
>     │       ├─ 单卡能装原始大小？→ FP16 / BF16
>     │       └─ 装不下 → AWQ 或 GPTQ INT4
>     │
>     ├─ 消费级 GPU（3090、4090）→ AWQ INT4
>     │
>     ├─ Mac M1/M2/M3 → GGUF
>     │
>     └─ CPU → GGUF
> ```
>
> **七、典型部署场景**
>
> **场景 A：8B 模型消费级 GPU**
>
> ```bash
> # 4090 24GB 跑 8B AWQ
> vllm serve TheBloke/Llama-3-8B-Instruct-AWQ \
>   --quantization awq \
>   --gpu-memory-utilization 0.9
> # 占用 ~10GB，吞吐 ~100 tok/s
> ```
>
> **场景 B：70B 模型单机 8 卡**
>
> ```bash
> # 8× A100 80GB
> vllm serve meta-llama/Llama-3-70B-Instruct \
>   --tensor-parallel-size 8 \
>   --gpu-memory-utilization 0.9
> # FP16 直接装下
> ```
>
> **场景 C：70B 模型单机 4 卡**
>
> ```bash
> # 4× A100 80GB（FP16 装不下）
> vllm serve casperhansen/llama-3-70b-instruct-awq \
>   --tensor-parallel-size 4 \
>   --quantization awq
> # INT4 装下
> ```
>
> **场景 D：Mac 本地**
>
> ```bash
> # 下载 GGUF
> wget https://huggingface.co/QuantFactory/Meta-Llama-3-8B-Instruct.GGUF/resolve/main/Meta-Llama-3-8B-Instruct.Q4_K_M.gguf
>
> # llama.cpp 启动
> ./main -m Meta-Llama-3-8B-Instruct.Q4_K_M.gguf -p "Hello" -n 512
> ```
>
> **八、量化对性能的影响**
>
> **显存**：INT4 量化让模型显存减少 ~4×。
>
> **速度**：
>
> - **Weight-only（AWQ、GPTQ）**：推理速度可能略慢或持平（mixed precision 开销）。
> - **W8A8（SmoothQuant）**：速度提升 1.5-2×（INT8 tensor core）。
> - **FP8**：速度提升 2×（H100 原生）。
>
> **精度**：
>
> - AWQ / GPTQ INT4：损失 < 1%，业务上几乎无感。
> - GGUF Q4：损失 1-2%，边缘部署可接受。
> - W8A8：损失 < 0.5%。
> - FP8：损失 < 0.3%。
>
> **九、量化的边界**
>
> 1. **微调后量化**：先 SFT 再量化，效果通常优于先量化再 SFT。
> 2. **量化感知训练（QAT）**：训练时模拟量化，效果最佳，但成本高。
> 3. **某些层不量化**：Embedding、LM Head、最后一层 FFN 通常保留高精度。
> 4. **领域敏感**：医疗 / 法律等高精度场景，量化前必测业务指标。
>
> **十、实战建议**
>
> 1. **优先 AWQ 或 GPTQ INT4**：精度损失小，部署成熟。
> 2. **H100 用 FP8**：最优解。
> 3. **Mac / CPU 用 GGUF**：唯一选择。
> 4. **量化后必测业务 Golden Set**：benchmark 损失小，业务指标可能敏感。
> 5. **保留 FP16 fallback**：关键场景用 FP16，普通场景用量化。
> 6. **关注量化版本**：TheBloke、casperhansen 等社区有大量预量化模型，直接下载。
>
> **总结**：量化是 LLM 部署的核心优化——**AWQ / GPTQ（INT4）、FP8（Hopper）、GGUF（CPU/Mac）、SmoothQuant（W8A8）** 是四大主流方案。INT4 量化让 70B 模型从 8 卡降到 2 卡，让 8B 模型装进消费级 GPU——这是开源 LLM 普及的关键。理解量化的原理、选型、部署、精度损失，是 LLM 应用部署运维的必备能力。

### Speculative Decoding（投机解码）：小模型 + 大模型如何 2-3× 加速推理？

> **答案**：
>
> **一、Speculative Decoding 的核心思想**
>
> **Leviathan et al. 2022《Fast Inference from Transformers via Speculative Decoding》** 和 **Chen et al. 2023《Accelerating Large Language Model Decoding with Speculative Sampling》** 同期提出。
>
> **核心命题**：**自回归生成慢，是因为每步都要跑一次完整大模型前向**。能不能让小模型先"猜"多个 token，大模型一次性校验？
>
> **二、原理**
>
> ```
> 标准自回归：
>   Step 1: 大模型(LLM) → token_1
>   Step 2: 大模型(LLM) → token_2
>   ...
>   Step N: 大模型(LLM) → token_N
>   N 次大模型前向
>
> Speculative Decoding：
>   Step 1: 小模型(Draft) → 猜 K 个 token [d_1, d_2, ..., d_K]
>   Step 2: 大模型(Target) 一次前向 → 校验 [t_1, t_2, ..., t_K]
>           接受的保留，拒绝的从拒绝点重新采样
>   每次循环可能产出 K 个 token（如果都接受）
>   或产出 0-K 个 token（部分接受）
> ```
>
> **关键**：大模型一次前向计算 K 个 token 的成本，与计算 1 个 token 相差不大（parallel）。
>
> **三、接受 / 拒绝策略**
>
> **目标**：保证 Speculative Decoding 输出分布与原始大模型**完全一致**（无精度损失）。
>
> **算法**（基于拒绝采样）：
>
> ```
> For i in 1..K:
>   draft_prob = small_model.prob(d_i)
>   target_prob = large_model.prob(d_i)
>
>   if target_prob >= draft_prob:
>       accept d_i （概率 1）
>   else:
>       accept d_i with probability (target_prob / draft_prob)
>
>   if rejected:
>       resample from (target_prob - draft_prob)_+
>       break
>
> 如果全部接受，bonus sample 一个 token（用大模型分布）。
> ```
>
> **数学保证**：最终输出分布 = 大模型分布（无偏）。
>
> **四、加速效果**
>
> **理论加速比**：
>
> - 假设小模型与大模型接受率 = p。
> - 每次循环产出 1/(1-p) 个 token 平均。
> - 大模型前向 K+1 个 token vs 1 个 token 时间比 = α（通常 1.5-2×，因为 parallel）。
> - 加速比 ≈ (1/(1-p)) / α。
>
> **实际加速**：
>
> - 接受率 70%：加速 2-3×。
> - 接受率 50%：加速 1.5×。
> - 接受率 30%：加速不如 baseline（小模型太差）。
>
> **五、Draft Model 选择**
>
> 1. **小模型同族**：
>    - 大：Llama 3 70B；小：Llama 3 8B。
>    - 大：Qwen 2.5 72B；小：Qwen 2.5 7B。
>    - 同族 tokenizer 一致，最简单。
>
> 2. **蒸馏小模型**：
>    - 用大模型生成数据训练小模型。
>    - 接受率更高（小模型模仿大模型）。
>
> 3. **N-gram / Retrieval**：
>    - 不用神经网络，用 N-gram 匹配或检索。
>    - 适合重复性强的任务（代码、模板）。
>    - Prompt Lookup Decoding 是代表。
>
> 4. **Early Exit**：
>    - 大模型中间层提前退出作为 draft。
>    - 节省显存，但实现复杂。
>
> **六、Speculative Decoding 在 vLLM 中**
>
> ```bash
> # vLLM 启动 speculative decoding
> vllm serve meta-llama/Llama-3-70B-Instruct \
>   --tensor-parallel-size 4 \
>   --speculative-model meta-llama/Llama-3-8B-Instruct \  # draft model
>   --num-speculative-tokens 5 \                            # 每次猜 5 个
>   --speculative-draft-tensor-parallel-size 1              # draft model 用 1 卡
> ```
>
> **配置要点**：
>
> - `speculative-model`：draft 模型路径。
> - `num-speculative-tokens`：每次猜几个 token（5 是经验值）。
> - draft 模型可以用更少 GPU（小模型显存小）。
>
> **七、典型场景**
>
> **场景 A：长生成**
>
> - 代码生成、文档生成、长答案。
> - 加速 2-3×（接受率高）。
>
> **场景 B：流式对话**
>
> - 用户感知首 token 后的输出速度。
> - Speculative Decoding 显著降低 TPOT。
>
> **场景 C：批量服务**
>
> - 高并发场景，每个请求都加速。
> - 整体吞吐提升。
>
> **不适合的场景**：
>
> - **短输出**（10-20 token）：开销大于收益。
> - **创意写作**（高 temperature）：接受率低。
> - **小模型已经够快**：Speculative Decoding 主要加速大模型。
>
> **八、其他投机变体**
>
> 1. **Medusa**：
>    - 不用单独 draft 模型，在大模型上加多个"头"。
>    - 每个头预测后续第 i 个 token。
>    - 训练成本低，效果接近。
>
> 2. **EAGLE**：
>    - 用 hidden state 而非 token 预测。
>    - 接受率更高。
>
> 3. **Lookahead Decoding**：
>    - 基于 Jacobi iteration，无需训练。
>    - 适合通用加速。
>
> 4. **Self-Speculative**：
>    - 大模型跳层作为 draft。
>    - 不需要额外模型。
>
> **九、效果实测**
>
> **Llama 3 70B + Llama 3 8B draft**（vLLM 实测）：
>
> - 接受率：65-75%。
> - 加速：2-2.5×。
> - 显存：+16GB（draft model）。
> - 精度：与原模型完全一致（无偏）。
>
> **代码生成场景**：
>
> - 接受率：80%+（代码 token 可预测性高）。
> - 加速：3×+。
>
> **创意写作场景**：
>
> - 接受率：40-50%。
> - 加速：1.3-1.5×。
>
> **十、实战建议**
>
> 1. **大模型服务必加 Speculative Decoding**：免费 2-3× 加速。
> 2. **draft 选同族小模型**：tokenizer 一致，实现简单。
> 3. **接受率监控**：低于 50% 时考虑换 draft 或关掉。
> 4. **配合 Continuous Batching**：vLLM 等已支持。
> 5. **不要在短输出场景用**：开销大于收益。
>
> **总结**：Speculative Decoding 是大模型推理的"免费午餐"——**用小模型猜 + 大模型校验，2-3× 加速且无精度损失**。理解原理（拒绝采样保证无偏）+ 配置（draft model、num_speculative_tokens）+ 适用场景（长生成好、创意写作差），是部署高性能 LLM 服务的进阶能力。vLLM、TensorRT-LLM、SGLang 都已原生支持，**生产部署应该默认开启**。

### LLM 推理服务的弹性伸缩：冷启动、Batch 调度、KV Cache 复用

> **答案**：
>
> 生产级 LLM 服务面临的运维挑战：**流量波动大、单请求资源消耗差异大、冷启动慢**。弹性伸缩是降本的关键。
>
> **一、LLM 服务的特殊性**
>
> | 维度          | 传统 Web 服务             | LLM 服务                          |
> |---------------|---------------------------|-----------------------------------|
> | 单请求资源    | 固定（CPU + 内存）        | 差异大（短 query vs 长上下文）    |
> | 单请求时长    | 秒级                      | 秒到分钟级                        |
> | 冷启动        | < 1s                      | 数十秒到数分钟（加载大模型）      |
> | GPU 利用率    | 中（CPU 任务）            | 必须高（GPU 贵）                  |
> | 优先级        | 不常需要                  | 常需要（VIP / 实时 / 离线）       |
>
> **关键挑战**：**冷启动慢是 LLM 服务弹性伸缩的最大障碍**。
>
> **二、冷启动优化**
>
> 1. **模型预加载**：
>    - Worker 启动时即加载模型到 GPU。
>    - 即使空闲，模型常驻显存。
>    - 优势：无冷启动。
>    - 劣势：空闲时显存浪费。
>
> 2. **模型预热**：
>    - 新 worker 上线前，跑若干 dummy 请求。
>    - CUDA kernel 编译、KV cache 分配、CUDA Graph 构建完成。
>    - 上线后无首次请求延迟峰值。
>
> 3. **Keepalive Pool**：
>    - 维护一个"热"worker 池（最小实例数）。
>    - 流量增加时启动备用 worker（仍需预热）。
>    - 流量减少时缩小到 keepalive。
>
> 4. **模型分片预热**：
>    - 大模型 TP=8，启动时所有 GPU 同步加载。
>    - 用 NCCL barrier 保证一致。
>    - 优化：并行加载多 shard。
>
> 5. **快照恢复**：
>    - 把模型加载状态保存为快照。
>    - 启动时直接 mmap，秒级启动。
>    - NVIDIA TRT-LLM 的 TRT-LLM Manager 支持。
>
> **三、Batch 调度策略**
>
> 1. **静态 Batching**：
>    - 凑 N 个请求一起处理。
>    - 优势：实现简单。
>    - 劣势：等待 + 资源浪费（见 Continuous Batching 模块）。
>
> 2. **Continuous Batching**（vLLM、SGLang 默认）：
>    - 每个 time step 检查、调度。
>    - 优势：吞吐高。
>    - 劣势：调度复杂。
>
> 3. **Priority Batching**：
>    - 高优先级请求优先调度。
>    - 适合多租户、VIP 用户。
>
> 4. **Latency-Aware Batching**：
>    - 根据 SLA 调度，超时风险高的优先。
>    - 适合实时场景（语音对话）。
>
> 5. **Cost-Aware Batching**：
>    - 短请求和长请求混合，平衡吞吐。
>    - 避免长请求拖慢整体。
>
> 6. **Dynamic Batch Size**：
>    - 根据显存动态调整 batch 大小。
>    - 显存满载时缩小 batch，避免 OOM。
>
> **四、KV Cache 复用**
>
> **场景**：多用户用同一 system prompt（客服、RAG）。
>
> **方案 1：Prefix Caching**
>
> - vLLM、SGLang 自动检测共享前缀。
> - 共享 system prompt 的 KV 只算一次。
> - 节省 30-80% 的 prefill 计算。
>
> **方案 2：Session Affinity**
>
> - 同用户多轮对话路由到同一 worker。
> - 复用该 worker 已缓存的 KV。
> - 适合长对话场景。
>
> **方案 3：Prompt Cache 共享池**
>
> - 把常用 prompt 的 KV 放到共享池（Redis-like）。
> - Worker 启动时加载，避免冷启动重算。
> - Anthropic Prompt Cache、OpenAI Prompt Cache 都是这种模式。
>
> **方案 4：Disk Cache**
>
> - 把 KV cache 序列化到磁盘。
> - 重启时加载，恢复上下文。
> - 适合 long-running session。
>
> **五、流量路由策略**
>
> 1. **基于 prompt 长度路由**：
>    - 短 prompt → 快实例（高 throughput 优先）。
>    - 长 prompt → 大显存实例。
>
> 2. **基于模型路由**：
>    - 简单 query → 小模型（便宜快）。
>    - 复杂 query → 大模型（贵但强）。
>    - LLM Gateway 自动路由。
>
> 3. **基于用户路由**：
>    - VIP / 付费用户 → 高优先级队列。
>    - 免费用户 → 普通队列。
>
> 4. **基于延迟路由**：
>    - 实时场景（语音、对话）→ 低延迟实例。
>    - 离线场景（批处理）→ 高吞吐实例。
>
> 5. **基于地域路由**：
>    - 就近部署（CDN 模式）。
>    - 降低网络延迟。
>
> **六、Auto Scaling 策略**
>
> **传统 HPA（Horizontal Pod Autoscaler）**：
>
> - 基于 CPU / 内存使用率。
> - 不适合 LLM（GPU 才是瓶颈）。
>
> **LLM 专用 Auto Scaling**：
>
> 1. **基于 GPU 利用率**：
>    - 阈值 70% 扩容，30% 缩容。
>    - 监控 MFU（Model FLOPs Utilization）。
>
> 2. **基于队列长度**：
>    - 待处理请求 > N 时扩容。
>    - 队列空时缩容。
>
> 3. **基于延迟**：
>    - P95 延迟 > SLA 时扩容。
>    - 延迟低时缩容。
>
> 4. **基于成本**：
>    - 预算内最大化 QPS。
>    - 流量低时主动缩容省钱。
>
> 5. **预测性扩展**：
>    - 根据历史流量预测（业务周期）。
>    - 提前预热实例。
>
> **七、典型部署架构**
>
> ```
> [Client]
>    │
>    ▼
> [API Gateway]
>    │
>    ├─ Auth / Rate Limit / Billing
>    │
>    ▼
> [LLM Router] ─── [Model Registry]
>    │            [Prompt Cache Pool]
>    │
>    ├─▶ [Pool A: Small Model (8B) × 4 workers]
>    ├─▶ [Pool B: Large Model (70B) × 8 workers]
>    └─▶ [Pool C: Reasoning Model (o1) × 2 workers]
>
>    Each worker:
>    - vLLM + TP=8
>    - PagedAttention
>    - Continuous Batching
>    - Prefix Caching
>
> [Auto Scaler] ─── [Metrics: QPS, latency, GPU util, queue]
> ```
>
> **八、监控指标**
>
> **业务指标**：
>
> - QPS（每秒请求数）。
> - 单请求 token 数（input + output）。
> - 错误率。
> - 用户满意度。
>
> **性能指标**：
>
> - TTFT（首 token 延迟）。
> - TPOT（每 token 延迟）。
> - E2E latency（端到端延迟）。
> - Throughput（吞吐）。
>
> **资源指标**：
>
> - GPU 利用率（MFU）。
> - 显存利用率。
> - KV cache 占用。
> - 显存碎片。
>
> **运维指标**：
>
> - 队列长度。
> - 冷启动次数。
> - 实例数（current / desired）。
> - 单实例成本。
>
> **九、成本优化**
>
> 1. **Spot Instance**：
>    - 用云厂商竞价实例（便宜 70%）。
>    - 风险：可能被回收。
>    - 适合：离线批处理、非关键任务。
>
> 2. **Reserved Instance**：
>    - 长期租用（1-3 年），便宜 40-60%。
>    - 适合：稳定基线流量。
>
> 3. **混合部署**：
>    - 基线用 Reserved Instance。
>    - 峰值用 Spot Instance。
>    - 离线任务填空隙。
>
> 4. **Spot + Queue**：
>    - 实时请求用 Reserved，Spot 处理可延迟任务。
>    - 失败时转移到 Reserved。
>
> 5. **多区域部署**：
>    - 不同区域电费 / GPU 价格不同。
>    - 流量路由到便宜区域（合规允许时）。
>
> **十、实战建议**
>
> 1. **建立监控基线**：先看真实流量 / 延迟 / 成本，再调优。
> 2. **从保守扩缩开始**：阈值留 buffer，避免频繁抖动。
> 3. **冷启动优化优先**：模型预热、keepalive 池、快照恢复。
> 4. **Prefix Caching 必开**：节省 30-80% prefill 成本。
> 5. **路由策略分层**：先按用户优先级，再按 prompt 长度 / 模型。
> 6. **成本监控到每请求**：token 数 × 单价，按用户归因。
> 7. **A/B 测试 Scaling 策略**：不同策略对业务影响大。
>
> **总结**：LLM 推理服务的弹性伸缩比传统 Web 服务复杂——**冷启动慢、资源差异大、GPU 成本高**。核心要点：**预热 + Continuous Batching + KV Cache 复用 + 智能路由 + 成本优化**。掌握这套工程栈，才能在保证 SLA 的同时把成本压到最低——这是大模型应用工程师区别于普通后端工程师的关键能力，也是面试高频考点。

### 多模态模型部署的特殊考量：Vision Tower、Image Token、跨模态 Batch

> **答案**：
>
> 多模态 LLM（如 LLaVA、Qwen-VL、GPT-4o）部署比纯文本复杂——视觉编码器、图像 token、跨模态注意力都需特殊处理。
>
> **一、多模态 LLM 的典型架构**
>
> ```
> [Image Input]
>     │
>     ▼
> [Vision Encoder (CLIP / SigLIP / ViT)]    ← Vision Tower
>     │
>     ▼
> [Projection Layer (MLP / Q-Former)]        ← 把视觉特征对齐到 LLM 空间
>     │
>     ▼
> [Image Tokens (192-576 个)]                ← 视觉信息转成 token
>     │
>     ▼
> ┌─────────────────────────────────────┐
> │   LLM (Llama / Qwen / DeepSeek)     │
> │   [Image Tokens] + [Text Tokens]    │
> └─────────────────────────────────────┘
>     │
>     ▼
> [Text Output]
> ```
>
> **关键组件**：
>
> 1. **Vision Tower（视觉编码器）**：CLIP / SigLIP / ViT，提取图像特征。
> 2. **Projection**：把视觉特征映射到 LLM embedding 空间。
> 3. **Image Tokens**：图像转成 N 个虚拟 token（192 / 576 / 1024+）。
> 4. **LLM 主干**：处理混合 token。
>
> **二、主流多模态架构**
>
> | 模型            | Vision Tower    | Image Tokens | 架构特点                  |
> |-----------------|-----------------|--------------|---------------------------|
> | LLaVA 1.5       | CLIP-ViT-L/14   | 576          | Projection (MLP)          |
> | LLaVA-NeXT      | CLIP-ViT-L/14   | Variable     | Dynamic resolution        |
> | Qwen-VL         | ViT-bigG        | 256+         | Native resolution         |
> | Qwen 2.5 VL     | ViT             | Dynamic      | Window attention          |
> | InternVL        | InternViT-6B    | 256-3200     | 适配任何 LLM              |
> | GPT-4o          | 闭源            | Native       | 原生多模态                |
> | Gemini 1.5/2.0  | 闭源            | Native       | 原生多模态                |
> | Claude 3.5      | 闭源            | Native       | 视觉理解强                |
>
> **三、部署挑战**
>
> **挑战 1：双模型加载**
>
> - LLM 主干（如 7B）。
> - Vision Tower（如 CLIP-ViT-L，300M）。
> - 显存总占用：LLM + Vision Tower + Projection。
>
> **挑战 2：图像预处理**
>
> - 解码（JPEG / PNG）。
> - Resize / Crop / Pad。
> - Normalize。
> - 性能瓶颈：CPU 密集，可能成为推理瓶颈。
>
> **挑战 3：Variable Image Tokens**
>
> - LLaVA-NeXT / Qwen-VL 支持任意分辨率。
> - 不同图像 token 数不同（192-3200+）。
> - Batch 处理复杂。
>
> **挑战 4：跨模态 Batch**
>
> - 同一 batch 可能纯文本 + 图文混合。
> - 不同样本 token 长度差异大。
> - Padding / Attention Mask 处理复杂。
>
> **挑战 5：视频理解**
>
> - 视频抽帧（每秒 1 帧 / 关键帧检测）。
> - 多帧 token 暴增（1 分钟视频可能 10K+ token）。
> - 显存 / 延迟挑战。
>
> **四、vLLM 多模态部署**
>
> vLLM 从 0.5+ 版本开始支持多模态：
>
> ```bash
> # 启动 LLaVA
> vllm serve llava-hf/llava-1.5-7b-hf \
>   --tensor-parallel-size 1 \
>   --trust-remote-code
>
> # 启动 Qwen-VL
> vllm serve Qwen/Qwen2-VL-7B-Instruct \
>   --tensor-parallel-size 1 \
>   --limit-mm-per-prompt image=5  # 每请求最多 5 张图
> ```
>
> **API 调用**：
>
> ```python
> from openai import OpenAI
>
> client = OpenAI(base_url="http://localhost:8000/v1", api_key="EMPTY")
>
> response = client.chat.completions.create(
>     model="llava-hf/llava-1.5-7b-hf",
>     messages=[{
>         "role": "user",
>         "content": [
>             {"type": "text", "text": "描述这张图"},
>             {"type": "image_url", "image_url": {"url": "https://..."}}
>         ]
>     }]
> )
> ```
>
> **五、性能优化**
>
> **1. Vision Tower 量化**：
>
> - CLIP-ViT 通常 FP16 即可（小模型）。
> - 大 Vision Tower（InternViT 6B）可量化。
>
> **2. Image Token 压缩**：
>
> - Token Pooling：把 576 → 192 token。
> - Token Pruning：丢弃不重要的 token。
> - 改进方法：Q-Former、ToMe。
>
> **3. 图像预处理加速**：
>
> - GPU 解码（NVIDIA DALI）。
> - 异步预处理（CPU 与 GPU 并行）。
> - 缓存常用图像的预处理结果。
>
> **4. 跨模态 Batch 优化**：
>
> - 相同 image token 数的样本组 batch。
> - 动态 padding（按 batch 内最长）。
> - Bucket batching（按长度分桶）。
>
> **5. Prefix Caching for Images**：
>
> - 同一图像多次 query（多轮对话）。
> - Image token 的 KV cache 复用。
> - vLLM、SGLang 支持。
>
> **六、显存管理**
>
> **典型显存占用**（LLaVA 1.5 7B）：
>
> ```
> LLM 主干 (7B FP16)         : 14 GB
> Vision Tower (CLIP-ViT-L)  : 1.5 GB
> Projection Layer           : 0.1 GB
> KV Cache (max-len 4K)      : 4 GB
> ─────────────────────────────
> 总计                       : ~20 GB
> ```
>
> 单卡 24GB（4090）能跑 LLaVA 7B。
>
> **视频理解场景**：
>
> - 1 分钟视频（30 fps × 1 frame/s = 30 frames）。
> - 每帧 576 token × 30 = 17K+ token。
> - KV cache 显存爆炸。
> - 解决：减少抽帧、Token 压缩、Sliding Window。
>
> **七、典型场景部署**
>
> **场景 A：图像问答（OCR、识别）**
>
> - 单张图像 + 短 query。
> - 启动：vLLM + LLaVA / Qwen-VL。
> - 性能：~2-3s/请求。
>
> **场景 B：文档分析（PDF、扫描件）**
>
> - 多页文档 → 多张图像。
> - 用 Dynamic Resolution（LLaVA-NeXT / Qwen 2 VL）。
> - 显存压力大，建议 70B + 多卡。
>
> **场景 C：视频理解**
>
> - 长视频抽帧。
> - 模型：Qwen 2.5 VL、Video-LLaVA。
> - 部署：长上下文（128K+）+ KV cache 优化。
>
> **场景 D：实时多模态对话**
>
> - 用户实时上传图像。
> - 流式输出。
> - 性能：低延迟（< 1s 首 token）。
>
> **八、跨模态推理的 Batch 策略**
>
> **问题**：同一 batch 内，请求 token 数差异大。
>
> - 请求 A：1 张图（576 token）+ 10 token 文本。
> - 请求 B：3 张图（1728 token）+ 50 token 文本。
> - 请求 C：纯文本 100 token。
>
> **传统 padding**：按最长 padding → 大量 padding token 浪费。
>
> **优化策略**：
>
> 1. **Bucket Batching**：按 token 数分桶，同桶组 batch。
> 2. **Variable-Length Attention**：FlashAttention 支持变长，无 padding。
> 3. **Modality-Aware Routing**：纯文本 / 图文分不同 worker。
>
> **九、多模态部署的边界**
>
> 1. **闭源多模态模型（GPT-4o、Gemini）优势大**：
>    - 原生多模态（早期融合），效果更好。
>    - 工程优化极致（延迟低）。
>
> 2. **开源多模态仍在追赶**：
>    - LLaVA、Qwen-VL 在特定任务（OCR、文档）已接近。
>    - 复杂推理（多图、视频）仍有差距。
>
> 3. **专用 Vision Tower 优势**：
>    - 文档：用 Donut、Nougat 等专用 OCR 模型。
>    - 医学：MedCLIP 等。
>    - 在专业场景超过通用多模态模型。
>
> **十、实战建议**
>
> 1. **优先 Qwen-VL / LLaVA**：开源生态最完善。
> 2. **使用 vLLM 多模态支持**：从 0.5+ 版本。
> 3. **限制 image-per-prompt**：避免 OOM。
> 4. **开启 Prefix Caching**：多轮对话显著降本。
> 5. **预处理放 GPU**：DALI、NVIDIA nvJPEG。
> 6. **视频场景慎用大模型**：显存爆炸，考虑专用视频模型。
> 7. **监控 image token 消耗**：成本可能与文本不同。
> 8. **专用场景用专用模型**：OCR 用 PaddleOCR，医学用专用模型。
>
> **总结**：多模态 LLM 部署比纯文本复杂——**双模型加载、图像预处理、Variable Tokens、跨模态 Batch** 是四大挑战。vLLM、SGLang 等现代推理框架已支持，但配置和优化仍需工程经验。理解这套部署栈，才能在生产环境稳定运行 LLaVA、Qwen-VL、InternVL 等多模态模型。这是 2024-2026 LLM 应用的重要方向，也是面试中"多模态部署经验"的展示机会。

### LLM 应用的成本核算与路由策略：Token 单价、Prompt Cache、Batch API

> **答案**：
>
> LLM 应用的成本核算是部署运维的核心——**token 计费、prompt cache、batch API、模型路由**四大手段组合，可以让成本降低 5-10×。
>
> **一、主流模型定价对比（2026）**
>
> | 模型              | Input ($/1M) | Output ($/1M) | 备注                  |
> |-------------------|--------------|---------------|-----------------------|
> | GPT-4o            | $2.5         | $10           | 通用强                |
> | GPT-4o-mini       | $0.15        | $0.6          | 性价比之王            |
> | GPT-4 Turbo       | $10          | $30           | 旧版                  |
> | o1                | $15          | $60           | 推理模型              |
> | o1-mini           | $3           | $12           | 推理 mini             |
> | o3-mini           | $1.1         | $4.4          | 推理性价比            |
> | Claude 3.5 Sonnet | $3           | $15           | 综合强                |
> | Claude 3.5 Haiku  | $0.8         | $4            | 便宜版                |
> | Claude 3 Opus    | $15          | $75           | 旗舰                  |
> | Gemini 2.0 Flash  | $0.1         | $0.4          | 极便宜                |
> | Gemini 2.0 Pro    | $1.25        | $5            | 中档                  |
> | DeepSeek V3       | $0.14        | $0.28         | 开源 API 最便宜       |
> | DeepSeek R1       | $0.55        | $2.19         | 推理 + 便宜           |
> | Qwen 2.5 Max      | $1.4         | $5.6          | 阿里旗舰              |
>
> **关键观察**：
>
> - **Input < Output**：输出 token 单价通常是输入的 4×。
> - **闭源贵**：GPT-4o 比 DeepSeek V3 贵 20×。
> - **推理模型贵**：o1 比 GPT-4o 贵 6×。
> - **国产性价比**：DeepSeek、Qwen 是最便宜的主流模型。
>
> **二、成本构成拆解**
>
> 单次请求成本：
>
> ```
> 总成本 = Input Cost + Output Cost
>
> Input Cost = input_tokens × input_price
> Output Cost = output_tokens × output_price
>
> 完整版（含缓存）：
> 总成本 = cached_input_tokens × cached_price +
>          new_input_tokens × input_price +
>          output_tokens × output_price +
>          reasoning_tokens × output_price   # o1/R1 思考 token
> ```
>
> **例**：GPT-4o 单次请求：
>
> - Input: 5000 token × $2.5/1M = $0.0125
> - Output: 1000 token × $10/1M = $0.01
> - 总：$0.0225
>
> **三、隐藏成本**
>
> 1. **History Token**：多轮对话历史每轮累积。
>    - 10 轮对话后，input 可能 50K+ token。
>    - 必须监控 + 滚动摘要。
>
> 2. **Tool Schema**：Function Calling 的 schema 也算 input。
>    - 复杂 schema 可能 1000+ token。
>
> 3. **System Prompt**：固定但每次输入。
>    - 长 system prompt 可能 2-5K token。
>
> 4. **Reasoning Token**（推理模型）：o1/R1 思考链也计费。
>    - 简单问题可能 1000 token 思考。
>    - 复杂问题可能 50K+ token 思考。
>
> 5. **Image Token**：图像 token 单独计费。
>    - GPT-4o：一张图 ~85 token。
>    - 高分辨率更多。
>
> 6. **Retries**：网络 / 限流重试。
>    - 实际成本可能是理论的 1.2-2×。
>
> 7. **Embedding**：RAG 系统的 embedding 也计费。
>    - 通常便宜（$0.1/1M），但量大累积。
>
> 8. **Fine-tuning**：训练 token 也计费。
>    - GPT-4o fine-tune：$8/1M training token。
>
> **四、成本优化策略**
>
> **策略 1：Prompt Caching（最高 ROI）**
>
> - **机制**：相同前缀的 prompt 缓存，下次调用只算新增部分。
> - **价格**：cached input 通常是 input 的 25-50%。
> - **典型场景**：
>   - 固定 system prompt。
>   - RAG：知识库 chunks 前缀。
>   - Few-shot examples。
>   - 长对话历史。
>
> **Anthropic Prompt Cache**：
>
> ```python
> response = client.messages.create(
>     model="claude-3-5-sonnet-20241022",
>     system=[{
>         "type": "text",
>         "text": "<very long system prompt>",
>         "cache_control": {"type": "ephemeral"}   # 标记缓存
>     }],
>     messages=[...]
> )
> # 后续调用 system prompt 部分按 cached price 计费（1/10 价格）
> ```
>
> **OpenAI 自动缓存**：
>
> - OpenAI 自动缓存最近前缀（无需显式 cache_control）。
> - cached input 价格为 input 的 50%。
>
> **节省**：固定 prompt + 高频调用可省 50-90%。
>
> **策略 2：Batch API**
>
> - **机制**：异步批处理，24 小时内返回。
> - **价格**：通常是实时 API 的 50%。
> - **场景**：
>   - 离线数据处理（标注、分类）。
>   - 非实时分析。
>   - 历史数据处理。
>
> ```python
> # OpenAI Batch API
> from openai import OpenAI
>
> client = OpenAI()
> batch = client.batches.create(
>     input_file_id="file-abc123",
>     endpoint="/v1/chat/completions",
>     completion_window="24h",
>     metadata={"job": "nightly eval"}
> )
> ```
>
> **节省**：50% input + output。适合非实时任务。
>
> **策略 3：模型路由**
>
> - 简单 query → 小模型（GPT-4o-mini / Haiku）。
> - 复杂 query → 大模型（GPT-4o / Sonnet）。
> - 推理任务 → o1 / R1。
>
> **路由判据**：
>
> - Prompt 长度（短 → 小模型）。
> - 任务类型（事实 → 小，推理 → 大）。
> - 用户优先级（VIP → 大）。
> - 业务关键性（高价值 → 大）。
>
> **节省**：综合可省 50-70%。
>
> ```python
> def route_model(query):
>     if len(query) < 100 and is_simple_question(query):
>         return "gpt-4o-mini"     # $0.15/$0.6
>     elif is_reasoning_task(query):
>         return "o3-mini"          # $1.1/$4.4
>     else:
>         return "gpt-4o"           # $2.5/$10
> ```
>
> **策略 4：上下文压缩**
>
> - **历史摘要**：长对话累积摘要，保留最近 N 轮。
> - **RAG 截断**：只取 top-K chunks。
> - **Tool 结果裁剪**：工具返回截断、压缩。
> - **Prompt 精简**：删冗余指令、用更紧凑格式。
>
> **节省**：30-50% input token。
>
> **策略 5：Speculative / 小模型预筛**
>
> - 用小模型先答，不确定再升级大模型。
> - 适合分类 / 路由场景。
>
> **策略 6：自部署开源模型**
>
> - 高频简单任务（客服 FAQ）：自部署 Llama 3 8B + vLLM。
> - 单 token 成本接近 0（除 GPU 折旧）。
> - 阈值：月调用 1B+ token 时自部署划算。
>
> **五、成本监控架构**
>
> ```
> [Client] → [API Gateway]
>                │
>                ├─ Token Counter（每次调用 input/output 计数）
>                ├─ Cost Calculator（按 model price 计算）
>                ├─ Per-User Attribution（按 user/api_key 归因）
>                ├─ Budget Alert（超阈值告警）
>                └─ Anomaly Detection（异常调用检测）
>
> [Logs] → [Analytics Dashboard]
>                │
>                ├─ Daily / Monthly 成本趋势
>                ├─ 按用户 / 模型 / 端点拆分
>                ├─ P50 / P95 单次成本
>                └─ Top 消耗用户 / 端点
> ```
>
> **关键指标**：
>
> - **月度总成本**：跟踪趋势。
> - **每用户成本**：识别异常 / 高价值用户。
> - **每模型成本**：路由是否合理。
> - **缓存命中率**：Prompt Cache 是否生效。
> - **平均成本 / 请求**：性价比指标。
> - **成本 / 任务完成**：业务 ROI。
>
> **六、典型成本优化案例**
>
> **案例 1：客服聊天机器人**
>
> - 原方案：GPT-4 + 长历史 → 月 $10k。
> - 优化：
>   - 历史 10 轮后摘要 → 省 40%。
>   - Prompt Cache system prompt → 省 30%。
>   - 简单 FAQ 路由到 GPT-4o-mini → 省 50% 那部分。
> - 结果：月 $3k（省 70%）。
>
> **案例 2：RAG 文档问答**
>
> - 原方案：GPT-4o + 每次召回 20 chunks → 月 $5k。
> - 优化：
>   - Rerank 后只取 top-5 → 省 60% input。
>   - 知识库 chunks 缓存 → 省 50%。
>   - Batch API 处理历史 query → 省 50%。
> - 结果：月 $1.5k（省 70%）。
>
> **案例 3：代码生成**
>
> - 原方案：所有用 GPT-4o。
> - 优化：
>   - 简单补全 → DeepSeek V3 API。
>   - 复杂重构 → GPT-4o。
>   - 路由：基于代码长度 + 复杂度。
> - 结果：省 60%。
>
> **七、Budget 控制**
>
> 1. **用户级配额**：
>    - 每用户月度 token / 美元上限。
>    - 超限限流或拒服务。
>
> 2. **API Key 级配额**：
>    - 按业务线 / 团队分配。
>    - 监控异常使用。
>
> 3. **告警机制**：
>    - 日成本超过阈值告警。
>    - 单用户消耗激增告警。
>    - 缓存命中率下降告警。
>
> 4. **Hard Cap**：
>    - 月度预算硬上限。
>    - 接近上限自动降级（路由到便宜模型）。
>
> **八、成本核算工具**
>
> 1. **LLM Gateway**（LiteLLM、Portkey、Helicone）：
>    - 统一 API、统一计费、统一监控。
>    - 支持多模型路由、缓存、限流。
>
> 2. **OpenTelemetry**：
>    - 标准化追踪 LLM 调用。
>    - 与现有可观测性栈集成。
>
> 3. **LangSmith / Phoenix**：
>    - LangChain 应用追踪。
>    - 含成本 / 延迟监控。
>
> 4. **Cloud Provider Billing**：
>    - AWS / Azure / GCP 原生成本监控。
>    - 自部署 GPU 的成本核算。
>
> **九、实战建议**
>
> 1. **建立 token 计数器**：每请求精确计数。
> 2. **按用户归因**：成本到 user / api_key / endpoint。
> 3. **设预算 + 告警**：日 / 月预算硬限制。
> 4. **优先 Prompt Cache**：最高 ROI 优化。
> 5. **离线任务用 Batch API**：自动省 50%。
> 6. **模型路由必备**：小模型 + 大模型混合。
> 7. **历史滚动摘要**：长对话必备。
> 8. **每月评估替代模型**：新模型可能便宜 5-10×。
> 9. **建立单位成本基线**：成本 / 请求、成本 / 任务。
> 10. **不要"为省钱降低体验"**：成本优化不应损害核心功能。
>
> **总结**：LLM 应用成本核算是部署运维的核心——**Token 计费精确化 + Prompt Cache + Batch API + 模型路由 + 上下文压缩**五大手段组合可降本 5-10×。生产级 LLM 服务必须建立完整的成本监控 + 优化 + 预算控制体系。这是 LLM 应用工程师的关键工程能力——**懂技术的工程师让模型跑起来，懂成本的工程师让模型跑得起**。理解这套优化栈，能在面试中讲清楚"如何把月度 LLM 成本从 $10k 降到 $2k"，是工程经验的硬实力展示。
