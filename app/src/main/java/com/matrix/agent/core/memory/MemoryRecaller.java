package com.matrix.agent.core.memory;

import java.util.List;

/**
 * V0.5.0 Stage 1:Memory 召回入口,AgentEngine.buildSystemPrompt + LlmPlanner.savedKeysFor 注入。
 *
 * <p>V0.5.0 默认实现 {@link MemoryRouter} 按 layer 优先级(Working > Episodic > Semantic >
 * Preference)合并 4 层 source 结果,截断到 maxItems。
 *
 * <p>参数说明:
 * <ul>
 *   <li>{@code sessionId} — Working 层查 SessionContext recentTurns 必需;null 时跳过 Working 层。</li>
 *   <li>{@code userText} — V0.5.0 不参与相似度计算;V0.5.1 引入 embedding 后用于 semantic 召回。</li>
 *   <li>{@code maxItems} — 上限;MemoryRouter 内部按 layer 优先级填充。</li>
 * </ul>
 */
public interface MemoryRecaller {
    List<MemorySnippet> recall(MemoryScope scope, String sessionId, String userText, int maxItems);
}
