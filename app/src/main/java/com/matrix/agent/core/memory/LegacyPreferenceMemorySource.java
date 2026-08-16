package com.matrix.agent.core.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Preference Memory 默认实现——桥接旧版 {@link MemoryStore}。
 *
 * <p>调 {@link MemoryStore#getAllPreferences(String)} 取 userId 全部偏好,按 Map 迭代顺序
 * (InMemoryMemoryStore / SharedPreferencesMemoryStore 都是 LinkedHashMap,按 put 顺序)取前 maxItems 条。
 *
 * <p>行为兼容旧版 LlmPlanner.savedKeysFor——返回的 snippet.key 与原"已保存的偏好 key 列表"一致。
 *
 * <p><b>双维度隔离边界</b>:Preference 层仍是 <b>legacy user 维度</b>——
 * 查询旧偏好只走 {@code userId},结果 snippet 的 scope 直接复用调用方传入的 scope(可能含 zone)。
 * 真正 zone 维度隔离要等 Room {@code MemoryRecordEntity} 主键
 * {@code (userId, zone, layer, key)} 接入后才生效。在文档中,MemoryScope 的 zone 字段
 * 对 Preference 层是"软标记",不参与查询过滤。
 */
public final class LegacyPreferenceMemorySource implements PreferenceMemorySource {
    private final MemoryStore memoryStore;

    public LegacyPreferenceMemorySource(MemoryStore memoryStore) {
        if (memoryStore == null) throw new IllegalArgumentException("memoryStore 不能为空");
        this.memoryStore = memoryStore;
    }

    @Override
    public List<MemorySnippet> recallPreference(MemoryScope scope, String userText, int maxItems) {
        if (maxItems <= 0) return Collections.emptyList();
        Map<String, String> prefs = memoryStore.getAllPreferences(scope.getUserId());
        if (prefs.isEmpty()) return Collections.emptyList();
        List<MemorySnippet> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : prefs.entrySet()) {
            if (result.size() >= maxItems) break;
            result.add(new MemorySnippet(MemoryLayer.PREFERENCE, scope,
                    entry.getKey(), entry.getValue(), 1.0, 0L, null));
        }
        return Collections.unmodifiableList(result);
    }
}
