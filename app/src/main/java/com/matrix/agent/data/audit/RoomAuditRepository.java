package com.matrix.agent.data.audit;

import android.util.Log;

import com.matrix.agent.core.agent.AgentOutcome;
import com.matrix.agent.core.agent.Trajectory;
import com.matrix.agent.core.identity.ActorUsers;
import com.matrix.agent.core.identity.AgentRequest;
import com.matrix.agent.data.db.AuditEventDao;
import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.db.SessionHistoryDao;
import com.matrix.agent.data.db.TrajectoryCodec;
import com.matrix.agent.data.db.TrajectoryDao;
import com.matrix.agent.data.db.TrajectoryEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * V0.5.0 Stage 2:Room-backed AuditRepository 实现——APK 主路径。
 *
 * <p>P1.3 修复(评审 V0.5.0):persist 改为**同步阻塞**单条 insert——
 * 与 README/Code-Review 文档"同步阻塞 insert、所有终态可回放"承诺对齐,
 * 让"任务返回后立即查询"无竞态。
 *
 * <p>fail-open 不变:insert 抛异常时仅 log,不向上传播——保证主任务路径不被 Audit 拖累。
 *
 * <p>V0.5.1 优化为 WAL + batch + 异步队列时再切回 Executor 模型,届时补:
 * <ul>
 *   <li>flush/shutdown 生命周期(Application.onTerminate / CarLifecycleListener);</li>
 *   <li>队列满策略(block / drop-oldest / spill-to-file);</li>
 *   <li>进程退出未落盘场景的恢复策略;</li>
 *   <li>真实异步测试(验证队列入队 + 后台线程消费 + 终态一致性)。</li>
 * </ul>
 *
 * <p><b>V0.5.2 Stage 1 三构造器</b>:
 * <ul>
 *   <li>单参 {@code (TrajectoryDao)} —— V0.5.0 兼容,JVM Contract test 验证 trajectory
 *       删除语义。clearByUserZone 仅删 trajectory 表(无 database 引用,非原子)。</li>
 *   <li>4 参 {@code (MatrixDatabase, TrajectoryDao, SessionHistoryDao, MemoryRecordDao)}
 *       —— V0.5.1 APK 主路径(已被 V0.5.2 替换为 5 参,但保留作向后兼容)。
 *       clearByUserZone 跨 3 表原子删除,audit_event 不参与(AuditEventDao=null)。</li>
 *   <li>5 参 {@code (MatrixDatabase, TrajectoryDao, SessionHistoryDao, MemoryRecordDao,
 *       AuditEventDao)} —— V0.5.2 APK 主路径,持有 database + 4 DAO,clearByUserZone 在
 *       {@code database.runInTransaction} 内跨 4 表原子删除(audit_event userId 列已就位)。</li>
 * </ul>
 */
public final class RoomAuditRepository implements AuditRepository {
    private static final String TAG = "MatrixAgent";

    private final TrajectoryDao trajectoryDao;
    /** V0.5.1 Stage 6:跨表 transaction 入口;单参构造时为 null。 */
    private final MatrixDatabase database;
    private final SessionHistoryDao sessionHistoryDao;
    private final MemoryRecordDao memoryRecordDao;
    /** V0.5.2 Stage 1:audit_event DAO;null 时不参与 clearByUserZone 跨表删除。 */
    private final AuditEventDao auditEventDao;

    /** V0.5.0 兼容路径——仅 trajectoryDao 可用。 */
    public RoomAuditRepository(TrajectoryDao trajectoryDao) {
        this(null, trajectoryDao, null, null, null);
    }

    /**
     * V0.5.1 Stage 6:4 参构造器——持有 database + 3 DAO。
     *
     * <p>V0.5.2 保留作向后兼容(V0.5.1 测试 / 装配路径);APK 主路径切 5 参构造。
     * 不传 AuditEventDao → clearByUserZone 仅跨 3 表。
     */
    public RoomAuditRepository(MatrixDatabase database, TrajectoryDao trajectoryDao,
            SessionHistoryDao sessionHistoryDao, MemoryRecordDao memoryRecordDao) {
        this(database, trajectoryDao, sessionHistoryDao, memoryRecordDao, null);
    }

    /**
     * V0.5.2 Stage 1:APK 主路径构造器——持有 database + 4 DAO,
     * 让 clearByUserZone 跨 4 表(含 audit_event)原子删除。
     */
    public RoomAuditRepository(MatrixDatabase database, TrajectoryDao trajectoryDao,
            SessionHistoryDao sessionHistoryDao, MemoryRecordDao memoryRecordDao,
            AuditEventDao auditEventDao) {
        if (trajectoryDao == null) throw new IllegalArgumentException("trajectoryDao 不能为空");
        this.trajectoryDao = trajectoryDao;
        this.database = database;
        this.sessionHistoryDao = sessionHistoryDao;
        this.memoryRecordDao = memoryRecordDao;
        this.auditEventDao = auditEventDao;
    }

    @Override
    public void persist(AgentOutcome outcome, AgentRequest request) {
        if (outcome == null || request == null) return;
        try {
            doPersist(outcome, request);
        } catch (Exception ex) {
            // V0.5.0 fail-open:仅 log,不抛——保证主任务路径不被 Audit 拖累
            Log.e(TAG, "[Audit] persist FAILED req=" + outcome.getRequestId()
                    + " cause=" + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
        }
    }

    private void doPersist(AgentOutcome outcome, AgentRequest request) {
        Trajectory trajectory = outcome.getTrajectory();
        TrajectoryEntity entity = new TrajectoryEntity();
        entity.requestId = outcome.getRequestId();
        entity.sessionId = request.getSessionId();
        entity.arbitrationKey = request.getArbitrationKey();
        entity.actor = request.getActor() == null ? "" : request.getActor().name();
        entity.zone = request.getOccupantZone() == null ? "" : request.getOccupantZone().name();
        entity.userId = ActorUsers.userIdOf(request);
        entity.startedMs = trajectory.getStartedAtMillis();
        entity.durationMs = outcome.getDurationMillis();
        entity.iterationCount = trajectory.getIterations().size();
        entity.totalToolCalls = trajectory.getTotalToolCalls();
        entity.successToolCalls = trajectory.countSuccessfulToolCalls();
        entity.stopReason = outcome.getStopReason().name();
        entity.finalState = outcome.getFinalState().name();
        entity.trajectoryJson = TrajectoryCodec.encode(outcome);
        entity.createdAtMs = System.currentTimeMillis();
        trajectoryDao.insert(entity);
        Log.i(TAG, "[Audit] persist req=" + entity.requestId
                + " state=" + entity.finalState + " iterations=" + entity.iterationCount
                + " durationMs=" + entity.durationMs);
    }

    @Override
    public AuditRecord queryByRequest(String userId, String zone, String requestId) {
        if (requestId == null || userId == null || zone == null) return null;
        try {
            TrajectoryEntity entity = trajectoryDao.queryByRequestScoped(userId, zone, requestId);
            return entity == null ? null : toRecord(entity);
        } catch (Exception ex) {
            Log.e(TAG, "[Audit] queryByRequest FAILED user=" + userId + " zone=" + zone
                    + " req=" + requestId + " cause=" + ex.getClass().getSimpleName(), ex);
            return null;
        }
    }

    @Override
    public List<AuditRecord> queryBySession(String userId, String zone, String sessionId, int limit) {
        if (sessionId == null || userId == null || zone == null) return new ArrayList<>();
        try {
            List<TrajectoryEntity> entities = trajectoryDao.queryBySessionScoped(
                    userId, zone, sessionId, limit);
            List<AuditRecord> records = new ArrayList<>(entities.size());
            for (TrajectoryEntity entity : entities) {
                AuditRecord record = toRecord(entity);
                if (record != null) records.add(record);
            }
            return records;
        } catch (Exception ex) {
            Log.e(TAG, "[Audit] queryBySession FAILED user=" + userId + " zone=" + zone
                    + " session=" + sessionId + " cause=" + ex.getClass().getSimpleName(), ex);
            return new ArrayList<>();
        }
    }

    private static AuditRecord toRecord(TrajectoryEntity entity) {
        if (entity == null) return null;
        return new AuditRecord(
                entity.requestId,
                entity.sessionId,
                entity.actor,
                entity.zone,
                entity.userId,
                entity.startedMs,
                entity.durationMs,
                entity.stopReason,
                entity.finalState,
                entity.iterationCount,
                entity.totalToolCalls,
                entity.successToolCalls,
                entity.trajectoryJson);
    }

    /**
     * V0.5.1 Stage 6 + Stage 6 P1.2 + V0.5.2 Stage 1:clearUserData 内部按 (userId, zone)
     * 批量清理 Audit 数据,返回结构化 {@link ClearOutcome}。
     *
     * <p>路径分支:
     * <ul>
     *   <li>5 参构造(database + 4 DAO 全非 null):跨 trajectory / session_history /
     *       memory_record / audit_event 4 表在 {@code database.runInTransaction} 内原子删除,
     *       事务回滚视为 PARTIAL_FAILURE。tablesAttempted=4。</li>
     *   <li>4 参构造(database + 3 DAO 非 null,auditEventDao=null):跨 3 表原子删除
     *       (V0.5.1 兼容),tablesAttempted=3。</li>
     *   <li>单参构造(database == null):仅删 trajectory 表(V0.5.0 兼容路径,
     *       主要为 JVM Contract test 验证 trajectory 删除语义),tablesAttempted=1。</li>
     * </ul>
     *
     * <p><b>失败语义</b>(P1.2 修正):仍 try/catch 不抛(主流程已清空偏好 + 会话,不让
     * audit 失败回滚主流程),但失败信息封装进 {@link ClearOutcome},Repository + ViewModel
     * 据此选择 UI 文案,不再让"audit 仍残留"伪装成"完整成功"。
     */
    @Override
    public ClearOutcome clearByUserZone(String userId, String zone) {
        if (userId == null || zone == null) {
            Log.w(TAG, "[Audit] clearByUserZone skip: userId/zone 为空");
            return ClearOutcome.failure("userId/zone 为空");
        }
        try {
            if (database != null && sessionHistoryDao != null && memoryRecordDao != null) {
                return clearFourTablesAtomic(userId, zone);
            }
            int deleted = trajectoryDao.deleteByUserZone(userId, zone);
            Log.i(TAG, "[Audit] clearByUserZone single-table user=" + userId
                    + " zone=" + zone + " trajectoryDeleted=" + deleted);
            // 单参路径只尝试 1 张表(trajectory),成功 1 张,行数 = deleted。
            return ClearOutcome.success(1, 1, deleted);
        } catch (Exception ex) {
            Log.e(TAG, "[Audit] clearByUserZone FAILED user=" + userId + " zone=" + zone
                    + " cause=" + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
            return ClearOutcome.failure(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    /**
     * V0.5.2 Stage 1:跨 4 表(或 3 表,auditEventDao=null 时)原子删除。
     *
     * <p>audit_event userId 列默认 {@code ""}——历史 V0.5.0/V0.5.1 行 userId='' 按
     * (userId, zone) 过滤不到,deleteByUserZone 返回 0(不视为失败)。
     */
    private ClearOutcome clearFourTablesAtomic(String userId, String zone) {
        boolean hasAuditEvent = auditEventDao != null;
        int tableCount = hasAuditEvent ? 4 : 3;
        // 用 int[4] 在 lambda 内累积删除条数(effective-final 容器)
        final int[] counts = new int[4];
        try {
            database.runInTransaction(() -> {
                counts[0] = trajectoryDao.deleteByUserZone(userId, zone);
                counts[1] = sessionHistoryDao.deleteByUserZone(userId, zone);
                counts[2] = memoryRecordDao.deleteByUserZone(userId, zone);
                if (hasAuditEvent) {
                    counts[3] = auditEventDao.deleteByUserZone(userId, zone);
                }
            });
        } catch (Exception ex) {
            // runInTransaction 抛 → Room 已 rollback,表都没动
            Log.e(TAG, "[Audit] clearByUserZone transaction FAILED user=" + userId
                    + " zone=" + zone + " cause=" + ex.getClass().getSimpleName(), ex);
            return ClearOutcome.failure("transaction rolled back: " + ex.getClass().getSimpleName());
        }
        int totalCleared = counts[0] + counts[1] + counts[2] + counts[3];
        Log.i(TAG, "[Audit] clearByUserZone atomic user=" + userId + " zone=" + zone
                + " tables=" + tableCount
                + " trajectory=" + counts[0]
                + " sessionHistory=" + counts[1]
                + " memoryRecord=" + counts[2]
                + " auditEvent=" + counts[3]);
        // transaction 提交 = 全部表成功;totalCleared 是行数。
        return ClearOutcome.success(tableCount, tableCount, totalCleared);
    }
}
