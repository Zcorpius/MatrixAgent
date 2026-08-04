package com.matrix.agent.core.agent;

/**
 * V0.5.1 Stage 4:审计侧自由文本摘要策略——core 内接口,不依赖 Android Keystore,
 * JVM 与 Android 都可注入。
 *
 * <p>实现方:
 * <ul>
 *   <li>{@link Sha1AuditDigest} —— JVM 测试默认,V0.5.0 行为底座;V0.5.1 仅在
 *       {@link AuditRedactor} 未注入路径使用(JVM 单元测试零回归)。</li>
 *   <li>{@code com.matrix.agent.platform.KeystoreHmacAuditDigest} —— Android 实现,
 *       HMAC-SHA-256 (AndroidKeyStore 派生密钥) 截断 16 位 hex,生产主路径。</li>
 *   <li>{@link UnavailableAuditDigest} —— V0.5.1 Stage 6 P1.1 fail-closed 路径,
 *       KeystoreHmacAuditDigest 装配失败时退回,输出固定
 *       {@code [redacted:chars=N,digest=unavailable]},不暴露任何可比对信号。</li>
 * </ul>
 *
 * <p>注入方式:{@link AuditRedactor} 持有可变 {@code digest} 字段,
 * {@link AgentEngine#setAuditDigest(AuditDigest)} 在 AppContainer 装配期注入;
 * 未注入时退化 {@link Sha1AuditDigest}(V0.5.0 测试零回归)。
 *
 * <p><b>HMAC 失败 = fail-closed(V0.5.1 Stage 6 P1.1 修正)</b>:V0.5.1 Stage 4 初版
 * 让 HMAC 装配失败时退回 {@link Sha1AuditDigest}(fail-degrade);评审 P1.1 指出这等于
 * 让 Keystore 异常设备重暴露于 Stage 4 要修的"SHA-1 8 位低熵枚举"风险。修正:
 * HMAC 失败时退回 {@link UnavailableAuditDigest}——审计数据继续 SQLCipher 加密落盘
 * (可用性优先),但摘要字段固定不可比对(隐私优先)。与 V0.5.0 P1.1 master_key 失败时
 * SQLCipher 不可用 = 数据落不下去 = fail-closed 不同:前者是"诊断字段降级",后者是
 * "数据落盘 fail"。
 */
public interface AuditDigest {
    /**
     * 对原文做摘要,返回 hex 字符串。
     *
     * <p>实现必须满足:
     * <ul>
     *   <li>同输入同输出(稳定性,事后比对)</li>
     *   <li>不同输入极大概率不同输出(防猜测)</li>
     *   <li>不可逆推原文</li>
     * </ul>
     */
    String digest(String input);

    /**
     * 摘要前缀——出现在 {@code [redacted:chars=N,<prefix>=<hex>]} 字符串中。
     * SHA-1 路径返回 {@code "sha"},HMAC-SHA-256 路径返回 {@code "hmac"}。
     */
    String prefix();
}
