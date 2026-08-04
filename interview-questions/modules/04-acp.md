# ACP / 协议对比与多 Agent 协作

> 来源：[面试鸭 · 最全 AI 大模型面试题库（含详细答案）](https://www.mianshiya.com/bank/1906189461556076546)
> 本模块共 1 题。完整索引见 [bank-llm-by-topic.md](../bank-llm-by-topic.md)。

---



> 共 1 题

### [什么是 ACP 协议？它有哪两个不同的含义？](https://www.mianshiya.com/bank/1906189461556076546/question/2052284825611546625)

> **答案**：
>
> **ACP 在 AI Agent 领域有两种主流含义，分属不同发起方、解决不同问题**：
>
> **1. Agent Communication Protocol（IBM / BeeAI）**
>
> - **发起方**：IBM Research 主导，最初通过 BeeAI 项目孵化，现已移交 Linux Foundation 开放治理
> - **核心目标**：**异构框架 Agent 之间的互操作**——让用 LangChain、CrewAI、AutoGen、 BeeAI 等不同框架构建的 Agent 能互相通信、协作完成任务
> - **协议格式**：基于 **JSON-RPC 2.0**，异步消息传递
> - **关键能力**：
>   - 统一 Agent 注册与发现（Envelopes）
>   - 跨框架任务委派（Task delegation）
>   - 标准化消息结构（Message、Task、State）
>   - 支持同步、异步、流式三种交互模式
> - **官网**：agentcommunicationprotocol.dev
>
> **2. Agent Connect Protocol（Cisco / AGNTCY）**
>
> - **发起方**：Cisco 旗下 Outshift，通过 AGNTCY 联合项目（Linux Foundation 协作）推进
> - **核心目标**：**远程 Agent 的标准化 API 调用**——把 Agent 当成可远程调用的"服务"，标准化其暴露、发现、调用接口
> - **协议格式**：基于 **OpenAPI** 规范
> - **关键能力**：
>   - 标准 invoke / config 接口
>   - 与 AGNTCY 的 OASF（Open Agent Schema Framework）配合，描述 Agent 元数据
>   - 强调 Agent 作为生产级服务的可运维性
> - **仓库**：github.com/agntcy/acp-spec
>
> **对比**：
>
> | 维度 | Agent **Communication** Protocol (IBM) | Agent **Connect** Protocol (Cisco) |
> |------|---------------------------------------|------------------------------------|
> | **关注层** | Agent-to-Agent 协作（横向） | Agent-as-a-Service 调用（纵向） |
> | **格式** | JSON-RPC | OpenAPI / REST |
> | **场景** | 多 Agent 编排、跨框架协作 | 单 Agent 的远程封装与发现 |
> | **与 MCP 关系** | 互补（MCP 管 Agent↔Tool，ACP 管 Agent↔Agent） | 互补 |
>
> **社区消歧**：因缩写撞车，社区有人用 **AComP** vs **AConP** 区分。
>
> **和其他协议的定位关系**：
>
> - **MCP**（Anthropic）：Agent ↔ Tool / Resource（垂直接入工具与数据）
> - **A2A**（Google）：Agent ↔ Agent（跨组织 Agent 协作）
> - **ACP（IBM）**：跨框架 Agent ↔ Agent（同系统内 Agent 协作）
> - **ACP（Cisco）**：Agent ↔ 调用方（Agent 服务化）
>
> 四者并不互斥，实际大型 Agent 系统可能同时用：MCP 接工具、ACP（Cisco）暴露 Agent 服务、ACP（IBM）或 A2A 做协作。
