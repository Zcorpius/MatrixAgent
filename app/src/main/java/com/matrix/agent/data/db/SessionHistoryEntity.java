package com.matrix.agent.data.db;

import androidx.room.Entity;

import androidx.annotation.NonNull;

/**
 * Session 维度历史记录——给 episodic memory 召回提供数据源。
 *
 * <p><b>预建表</b>(Entity + DAO 接口稳定,无写入路径);引入
 * SessionHistoryDao 召回时,从 TrajectoryEntity 按 sessionId 聚合填入。
 */
@Entity(tableName = "session_history",
        primaryKeys = {"userId", "zone", "sessionId", "startedAtMillis"})
public final class SessionHistoryEntity {
    @NonNull
    public String userId;
    @NonNull
    public String zone;
    @NonNull
    public String sessionId;
    public long startedAtMillis;

    public String actor;
    public String finalState;
    public String stopReason;
    public long durationMs;
    public int turnCount;
    public String trajectoryJson;
}
