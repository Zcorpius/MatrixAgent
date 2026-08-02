package com.matrix.agent.core.agent;

/** 终止原因:Agent Loop 退出时由 Runtime 给出的结构化判定。 */
public enum StopReason {
    /** 模型选择直接答复用户,正常完成。 */
    DONE,
    /** 模型返回不带 tool_call,等同于 DONE,独立列出便于诊断。 */
    NO_TOOL_CALL,
    /** 超过最大迭代数。 */
    MAX_ITERATIONS,
    /** 超过累计 tool call 上限。 */
    MAX_TOOL_CALLS,
    /** 请求 deadline 到。 */
    TIMEOUT,
    /** 用户取消。 */
    CANCELLED,
    /** 能力级拒绝反复出现或安全检查点强制终止。 */
    POLICY_HALT,
    /** 字符预算(总输入字符上限)耗尽。 */
    BUDGET_EXHAUSTED,
    /**
     * 模型输出因 {@code max_tokens} 截断(Provider finish_reason=length /
     * Anthropic stop_reason=max_tokens)。Observation 可能不完整,不能视为成功。
     */
    LENGTH_EXCEEDED,
    /**
     * Provider 协议不一致——{@code finish_reason}/{@code stop_reason} 缺失或未知
     * (映射为 {@link FinishReason#NONE}),或 STOP 但 content 为空。Runtime 不能信任
     * 这样的输出,直接终止任务,不允许被错判为正常完成。
     *
     * <p>第五轮 P2-1:旧实现把 NONE 一律当作 NO_TOOL_CALL → 可能 SUCCEEDED,
     * 把协议错误掩盖成正常完成。
     */
    PROTOCOL_ERROR
}
