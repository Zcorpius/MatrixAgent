package com.matrix.agent.core.agent;

/**
 * Agent Loop 的模型决策网关。每次调用对应一轮 LLM 推理。
 *
 * 实现方负责:
 * - 把 Provider 无关的 {@link ModelTurnRequest} 转成具体协议(OpenAI/Anthropic/Gemini/Ollama)
 * - 处理 PlannerMode(Native Tool Calling vs Structured JSON Compatibility)
 * - 把模型响应归一化为 {@link ModelTurn}
 *
 * 调用方(Agent Loop)负责:
 * - 拼装 conversation(含上轮 Observation)
 * - 处理 deadline 和 cancel(通过 {@link ModelCallExecutor})
 * - 解析 toolCalls、走 Policy、执行、把 Observation 拼回 conversation
 */
public interface ModelGateway {
    ModelTurn decide(ModelTurnRequest request);
}
