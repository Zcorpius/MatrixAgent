package com.matrix.agent.data.db;

import androidx.room.Entity;
import androidx.room.Index;

import androidx.annotation.NonNull;

/**
 * 四层 Memory 持久化记录——<b>预建表</b>(Entity + DAO 接口稳定,
 * 但无实际写入路径,Preference 层仍走 SharedPreferences 二元组),
 * 后续接入主路径(替换 SharedPreferences,启动期一次性迁移)。
 *
 * <p>原注释写"双写"与实际状态不符——
 * 全项目无 MemoryRecordDao 的实际调用,容易让后续按注释或 README 误判能力已落地。
 * 现统一改为"预建表 / 接入",与 AuditEventEntity / SessionHistoryEntity 状态对齐。
 *
 * <p>主键 (userId, zone, layer, key) 四元组对应 {@link com.matrix.agent.core.memory.MemoryScope#storageKey}。
 * 索引 (userId, layer) 支持按用户 + 层级批量召回(召回算法接入时按 (userId, zone, layer) 调)。
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
