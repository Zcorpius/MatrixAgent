package com.matrix.agent.core.memory;

import java.util.List;

/**
 * V0.5.0 Stage 1:Preference Memory 层——用户偏好(memory.preference.save / get)。
 *
 * <p>默认实现 {@link LegacyPreferenceMemorySource} 包装 V0.4.3 {@link MemoryStore#getAllPreferences},
 * 行为与 V0.4.3 完全一致——保证 Preference 层不引入回归。
 */
public interface PreferenceMemorySource {
    List<MemorySnippet> recallPreference(MemoryScope scope, String userText, int maxItems);
}
