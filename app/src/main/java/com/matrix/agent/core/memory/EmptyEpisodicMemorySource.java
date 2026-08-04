package com.matrix.agent.core.memory;

import java.util.Collections;
import java.util.List;

/**
 * V0.5.0 Stage 1:Episodic Memory 占位实现——V0.5.1 接 SessionHistoryDao 后替换。
 */
public final class EmptyEpisodicMemorySource implements EpisodicMemorySource {
    @Override
    public List<MemorySnippet> recallEpisodic(MemoryScope scope, String userText, int maxItems) {
        return Collections.emptyList();
    }
}
