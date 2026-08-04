package com.matrix.agent.data.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * V0.5.0 Stage 2:Audit 事件——V0.5.0 <b>预建表</b>(Entity + DAO 接口稳定,
 * 但 V0.5.0 Audit 落库走 TrajectoryEntity,本表无实际写入路径)。
 *
 * <p>V0.5.1 接入主路径:AgentEngine 5 个出口点先写 TrajectoryEntity(同 V0.5.0),
 * 同时把 PRE_TOOL / POST_TOOL / POLICY / STEER 等增量事件类型写入本表,
 * 用 incremental annotationProcessor + Room Migration 加新行 / 新 type。
 *
 * <p><b>V0.5.2 Stage 1:加 userId 列</b>——schema v2。clearByUserZone 跨 4 表原子
 * 删除(audit_event 也按 userId+zone 清)依赖此列;queryByUserZone 也依赖此列。
 *
 * <p>历史 V0.5.0/V0.5.1 行 userId 默认 {@code ""}(空串)——无法回填
 * (无 RequestId → AgentRequest 映射),按主路径查询过滤不到,但仍按 requestId 可查。
 *
 * <p><b>第二轮评审 P2.3 修复</b>:原注释写"V0.5.0 只写 TERMINAL"与实际状态不符——
 * V0.5.0 全项目无 AuditEventDao 的实际调用。统一改为"预建表 / V0.5.1 接入"。
 */
@Entity(tableName = "audit_event",
        indices = {
                @Index(value = {"requestId"}, name = "idx_audit_request"),
                @Index(value = {"userId", "zone"}, name = "idx_audit_user_zone")
        })
public final class AuditEventEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String requestId;

    /** 事件类型:V0.5.0 仅 TERMINAL;V0.5.1 加 PRE_TOOL/POST_TOOL/POLICY/STEER。 */
    public String type;

    public String actor;
    public String zone;
    public long happenedAtMs;
    public String payloadJson;

    /**
     * V0.5.2 Stage 1:用户维度——按 userId 清理 / 查询 audit 事件。
     * 历史 V0.5.0/V0.5.1 行 userId 默认 {@code ""}(Migration v1→v2 ALTER DEFAULT '')。
     */
    @NonNull
    public String userId = "";

    /**
     * V0.5.2-rev 评审 P1-2:AgentRequest.epoch——任务启动时捕获的 MemoryStore 版本号。
     *
     * <p>clearUserDataDetailed 推进 epoch 后,AuditEventRecorder 用此字段判断事件是否 stale:
     * entity.requestEpoch < currentEpoch → drop(不入队 / 不 insert)。
     *
     * <p>历史 V0.5.0/V0.5.1/V0.5.2 行 requestEpoch 默认 0(Migration v2→v3 ALTER DEFAULT 0)——
     * 0 表示"未透传 / 老数据",不参与 epoch gate(保留 V0.5.2 行为)。
     */
    public long requestEpoch = 0L;
}
