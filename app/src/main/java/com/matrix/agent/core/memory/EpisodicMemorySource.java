package com.matrix.agent.core.memory;

import java.util.List;

/**
 * V0.5.0 Stage 1:Episodic Memory 层——历史会话事件(SessionHistory 表)。
 *
 * <p>V0.5.0 默认实现 {@link EmptyEpisodicMemorySource} 占位返回空;V0.5.1 接 SessionHistoryDao,
 * 按时间倒序取同 userId 最近 N 条历史 session。
 */
public interface EpisodicMemorySource {
    List<MemorySnippet> recallEpisodic(MemoryScope scope, String userText, int maxItems);
}
