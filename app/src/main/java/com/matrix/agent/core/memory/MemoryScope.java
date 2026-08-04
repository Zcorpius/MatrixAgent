package com.matrix.agent.core.memory;

import com.matrix.agent.core.identity.VehicleZone;

import java.util.Objects;

/**
 * V0.5.0 Stage 1:Memory 双维度隔离键——(userId, zone)。
 *
 * <p>V0.4.3 MemoryStore 主键是单维度 userId("demo-driver" / "demo-passenger");
 * V0.5.0 引入 zone 维度后,同一 userId 可在不同 zone(主驾屏 / 副驾屏)有独立 Memory。
 *
 * <p>兼容契约:{@link #ofLegacy(String)} 把 zone 设为 GLOBAL,等价于 V0.4.3 二元组行为——
 * PreferenceMemorySource 走此路径,旧 SharedPreferences 主键(userId + "." + key)不变。
 *
 * <p>持久化层(Room MemoryRecordEntity)用 {@link #storageKey(MemoryLayer, String)} 作主键,
 * 与 SharedPreferences 二元组不冲突——V0.5.0 双写、V0.5.1 一次性迁移。
 *
 * <p><b>双维度隔离边界(评审 P2.1)</b>:V0.5.0 真正生效的隔离只有 <b>userId</b>——
 * <ul>
 *   <li>PREFERENCE 层({@link LegacyPreferenceMemorySource}):走 V0.4.3 SharedPreferences
 *       二元组查询,zone 字段是软标记,不参与过滤。</li>
 *   <li>WORKING 层({@link SessionContextWorkingMemory}):按 sessionId 隔离,sessionId
 *       已在 V0.4.3 拆为 "demo-driver" / "demo-passenger",等价于 userId 隔离。</li>
 *   <li>EPISODIC / SEMANTIC:V0.5.0 占位返回空,V0.5.1 接 Room 时启用 zone 维度。</li>
 * </ul>
 * 真正 zone 维度隔离(同一 userId 在主驾屏 / 副驾屏独立 Memory)要等 V0.5.1
 * Room MemoryRecordEntity 主键 (userId, zone, layer, key) 接入。
 */
public final class MemoryScope {
    private final String userId;
    private final VehicleZone zone;

    public MemoryScope(String userId, VehicleZone zone) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        this.userId = userId;
        this.zone = zone == null ? VehicleZone.GLOBAL : zone;
    }

    /** V0.4.3 兼容路径:zone=GLOBAL,等价于旧 MemoryStore 二元组语义。 */
    public static MemoryScope ofLegacy(String userId) {
        return new MemoryScope(userId, VehicleZone.GLOBAL);
    }

    public String getUserId() {
        return userId;
    }

    public VehicleZone getZone() {
        return zone;
    }

    /**
     * Room MemoryRecordEntity 主键格式。
     *
     * <p>格式稳定——V0.5.1 持久化层与迁移脚本依赖此格式,变更需要 Migration。
     */
    public String storageKey(MemoryLayer layer, String key) {
        return userId + "@" + zone.wireValue() + "#" + layer.wireValue() + "/"
                + (key == null ? "" : key);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MemoryScope)) return false;
        MemoryScope that = (MemoryScope) o;
        return userId.equals(that.userId) && zone == that.zone;
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, zone);
    }

    @Override
    public String toString() {
        return "MemoryScope{userId=" + userId + ", zone=" + zone + "}";
    }
}
