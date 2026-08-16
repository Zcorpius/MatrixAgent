package com.matrix.agent.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * SessionHistory DAO——预建表(无写入路径),引入召回时使用。
 *
 * <p>{@code queryByUser(userId, limit)} 改为
 * {@link #queryByUserZone(String, String, int)},SQL WHERE 强制 zone 过滤——
 * 同一 Android User 不同 zone(主驾屏 / 副驾屏)的历史不能被一并召回。
 *
 * <p>{@link #queryBySession(String)} 保留 sessionId-only 查询——sessionId 已是主键一部分
 * (复合主键 userId + zone + sessionId + startedAtMillis),sessionId 唯一即可定位行,
 * 不存在跨 user/zone 串扰。接入 UI 回放时,调用方仍需校验 sessionId 归属当前用户。
 */
@Dao
public interface SessionHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(SessionHistoryEntity entity);

    /**
     * 按 (userId, zone) 查询——强制访问域隔离。
     */
    @Query("SELECT * FROM session_history WHERE userId = :userId AND zone = :zone ORDER BY startedAtMillis DESC LIMIT :limit")
    List<SessionHistoryEntity> queryByUserZone(String userId, String zone, int limit);

    @Query("SELECT * FROM session_history WHERE sessionId = :sessionId")
    List<SessionHistoryEntity> queryBySession(String sessionId);

    /**
     * 按 (userId, zone) 批量删除——clearUserData 内部跨表 transaction 调用。
     */
    @Query("DELETE FROM session_history WHERE userId = :userId AND zone = :zone")
    int deleteByUserZone(String userId, String zone);
}
