package com.matrix.agent.core.prompt;

import com.matrix.agent.core.identity.AgentRequest;
import com.matrix.agent.core.memory.MemorySnippet;

import java.util.List;

/**
 * V0.5.0 Stage 4:AgentEngine.buildSystemPrompt / LlmPlanner.userPrompt 的装配契约。
 *
 * <p>V0.5.0 不强制接入 AgentEngine 主路径——仅提供能力,V0.5.1 上下文压缩 / V0.5.2 zone-aware
 * ToolSchemaView 在不改 AgentEngine 的前提下扩展(替换 V0.4.3 硬编码文案)。
 *
 * <p>V0.5.0 实现 {@link DefaultPromptBuilder} 复用 V0.4.3 文案 + 拼装 recalled memory 段,
 * 与 AgentEngine.buildSystemPrompt 当前行为等价(289 测试兼容)。
 */
public interface PromptBuilder {
    /**
     * 装配 system prompt 的有序段落。
     *
     * <p>返回 List 而非拼接字符串:caller 可按段类型独立处理(压缩、截断、redact、token 计费)。
     * V0.5.0 caller 自行 join("\n");V0.5.1 PromptComposer 加入后做预算分配。
     *
     * @param request 当前 AgentRequest(用于 zone / actor hint)
     * @param recalledMemory 已召回的 Memory snippet(可能为空)
     */
    List<PromptSegment> buildSystemPrompt(AgentRequest request, List<MemorySnippet> recalledMemory);
}
