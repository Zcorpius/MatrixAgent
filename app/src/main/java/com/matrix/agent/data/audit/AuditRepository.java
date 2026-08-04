package com.matrix.agent.data.audit;

import com.matrix.agent.core.agent.AgentOutcome;
import com.matrix.agent.core.identity.AgentRequest;

import java.util.List;

/**
 * V0.5.0 Stage 2:Audit 持久化入口——AgentEngine 5 个出口点统一调用。
 *
 * <p>实现策略:
 * <ul>
 *   <li>{@link RoomAuditRepository} —— APK 主路径,同步阻塞 + fail-open(失败仅 log)。</li>
 *   <li>{@link NoopAuditRepository} —— JVM 单测 / 装配失败兜底,空实现。</li>
 * </ul>
 *
 * <p>V0.5.0 仅 persist + query;V0.5.1 加 incremental(iteration) + WAL/batch。
 *
 * <p><b>第二轮评审 P2.2 修复</b>:query 路径强制传入访问域 (userId, zone)。
 *
 * <p><b>第八轮 P2.1 修复</b>:{@code queryByRequest(String requestId)} 改签名为
 * {@link #queryByRequest(String, String, String)}——UUID 全局唯一不能替代权限校验,
 * 任何 caller 都不能凭枚举 requestId 越权读他人审计。SQL WHERE 强制 userId + zone
 * 双重过滤,跨用户 / 跨 zone 查询直接返回 null。
 */
public interface AuditRepository {

    /**
     * 持久化 AgentOutcome + AgentRequest 元数据。
     *
     * <p>V0.5.0 同步阻塞单条 insert;失败必须 fail-open(仅 log,不抛)——
     * 保证 Audit 不影响主任务路径。V0.5.1 引入 WAL + 重试队列后改 fail-closed。
     */
    void persist(AgentOutcome outcome, AgentRequest request);

    /**
     * 按 (userId, zone, requestId) 查询单条记录——SQL WHERE 强制访问域,
     * 不匹配返回 null。第八轮 P2.1:UUID 唯一不能替代权限校验。
     */
    AuditRecord queryByRequest(String userId, String zone, String requestId);

    /**
     * 按 (userId, zone, sessionId) 查询最近 N 条记录(按 startedMs 倒序)。
     *
     * <p>V0.5.0 第二轮评审 P2.2:接口层强制访问域——调用方必须显式传入 userId + zone,
     * 不允许只传 sessionId。TrajectoryDao SQL 同步加 WHERE userId/zone 过滤。
     */
    List<AuditRecord> queryBySession(String userId, String zone, String sessionId, int limit);

    /**
     * V0.5.1 Stage 6 + Stage 6 P1.2:按 (userId, zone) 批量清理 Audit 落库数据,
     * 返回结构化结果 {@link ClearOutcome}。
     *
     * <p>默认返回 {@link ClearOutcome#notApplicable()},兼容 {@link NoopAuditRepository} /
     * JVM 测试 fake / V0.5.0 单参 {@link RoomAuditRepository}(无 database 引用)路径。
     *
     * <p>{@link RoomAuditRepository} 5 参构造器(持有 {@code MatrixDatabase} + 多个 DAO)覆写
     * 本方法,在 {@code database.runInTransaction} 内跨 trajectory / session_history /
     * memory_record 三表原子删除。AuditEventEntity V0.5.0 无写入路径 + 无 userId 列,不参与。
     *
     * <p><b>V0.5.1 Stage 6 P1.2 失败语义变更</b>:V0.5.1 Stage 6 初版让本方法 try/catch
     * 吞所有异常,UI 永远显示"已清空"——评审 P1.2 指出"主流程已删 / Audit 可能仍在"的语义错位。
     * 修正:本方法仍 try/catch 不抛(主流程已成功清空偏好 + 会话,不让 audit 失败回滚主流程),
     * 但失败信息封装进 {@link ClearOutcome#partialFailure(int, int, String)} /
     * {@link ClearOutcome#failure(String)},让 Repository + ViewModel 据此选择
     * "已清空" vs "上下文已清,审计删除失败,请稍后重试点击清空"文案。
     */
    default ClearOutcome clearByUserZone(String userId, String zone) {
        return ClearOutcome.notApplicable();
    }
}
