package com.matrix.agent.data.db;

import androidx.room.Entity;
import androidx.room.Index;

import androidx.annotation.NonNull;

/**
 * V0.5.0 Stage 2:四层 Memory 持久化记录——V0.5.0 <b>预建表</b>(Entity + DAO 接口稳定,
 * 但 V0.5.0 无实际写入路径,Preference 层仍走 V0.4.3 SharedPreferences 二元组),
 * V0.5.1 接入主路径(替换 SharedPreferences,启动期一次性迁移)。
 *
 * <p><b>第二轮评审 P2.3 修复</b>:原注释写"V0.5.0 双写"与实际状态不符——V0.5.0
 * 全项目无 MemoryRecordDao 的实际调用,容易让后续按注释或 README 误判能力已落地。
 * 现统一改为"预建表 / V0.5.1 接入",与 AuditEventEntity / SessionHistoryEntity 状态对齐。
 *
 * <p>主键 (userId, zone, layer, key) 四元组对应 {@link com.matrix.agent.core.memory.MemoryScope#storageKey}。
 * 索引 (userId, layer) 支持按用户 + 层级批量召回(V0.5.1 召回算法接入时按 (userId, zone, layer) 调)。
 */
@Entity(tableName = "memory_record",
        primaryKeys = {"userId", "zone", "layer", "key"},
        indices = {@Index(value = {"userId", "layer"}, name = "idx_memory_user_layer")})
public final class MemoryRecordEntity {
    @NonNull
    public String userId;
    @NonNull
    public String zone;
    @NonNull
    public String layer;  // MemoryLayer.wireValue(): working/episodic/semantic/preference
    @NonNull
    public String key;

    public String value;
    public double score;
    public long capturedAtMs;
    public String sourceSessionId;
}
