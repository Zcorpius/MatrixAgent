package com.matrix.agent.core.memory;

import java.util.Collections;
import java.util.List;

/**
 * V0.5.0 Stage 1:Semantic Memory 占位实现——V0.5.1 引入 embedding 后替换。
 */
public final class EmptySemanticMemorySource implements SemanticMemorySource {
    @Override
    public List<MemorySnippet> recallSemantic(MemoryScope scope, String userText, int maxItems) {
        return Collections.emptyList();
    }
}
