package com.matrix.agent.core.memory;

import java.util.List;

/**
 * V0.5.0 Stage 1:Semantic Memory 层——抽象知识库(如车型手册、常见问答)。
 *
 * <p>V0.5.0 默认实现 {@link EmptySemanticMemorySource} 占位返回空;
 * V0.5.1 引入 embedding + 向量检索后接入。
 */
public interface SemanticMemorySource {
    List<MemorySnippet> recallSemantic(MemoryScope scope, String userText, int maxItems);
}
