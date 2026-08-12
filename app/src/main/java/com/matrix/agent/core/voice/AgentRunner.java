package com.matrix.agent.core.voice;

import com.matrix.agent.core.agent.AgentOutcome;
import com.matrix.agent.core.identity.CancellationToken;

/**
 * Agent 执行接口(函数式)。Voice V1。
 *
 * <p>把 {@code AgentRuntimeRepository.execute(String, Actor, CancellationToken)} 抽成单方法接口,
 * 让 {@code VoiceSessionController} 不直接耦合 Repository 类——Controller 测试可用 lambda 注入
 * 预设 outcome,无需拉起整个 Engine 栈。生产实现:
 * <pre>{@code
 * AgentRunner runner = (text, token) ->
 *     appContainer.getAgentRuntimeRepository().execute(text, Actor.DRIVER, token);
 * }</pre>
 *
 * <p>窄接口(单方法)+ 生产零成本注入(method reference),非伪通用层;仅为 Controller 可测性。
 */
@FunctionalInterface
public interface AgentRunner {
    /**
     * 执行 Agent(阻塞到终态)。
     *
     * @param text 用户指令(ASR final 文本)
     * @param token 取消令牌
     * @return Agent 终态结果
     */
    AgentOutcome run(String text, CancellationToken token);
}
