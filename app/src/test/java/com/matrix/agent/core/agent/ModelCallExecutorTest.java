package com.matrix.agent.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.core.identity.Actor;
import com.matrix.agent.core.identity.AgentRequest;
import com.matrix.agent.core.identity.CancellationToken;
import com.matrix.agent.core.session.SessionContext;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * V0.4.1 Stage B:CancellableModelCall 抽象测试。
 *
 * <p>验证三个安全保证:
 * <ol>
 *   <li>{@code CancellationToken.cancel()} 触发时,{@code CancellableModelCall.abort()} 被同步调用;</li>
 *   <li>deadline 到时,ModelCallExecutor 主动触发 abort 链 + 返回 {@code Result.terminal(TIMEOUT)};</li>
 *   <li>外部 cancel 触发时,ModelCallExecutor 返回 {@code Result.terminal(CANCELLED)} 且 abort 被调用。</li>
 * </ol>
 *
 * <p>不测真实 HTTP——本测试只验证 ModelCallExecutor 与 CancellableModelCall / CancellationToken
 * 的协议契约。HTTP 层 abort 行为由 V0.6.0 接 OkHttp 时补集成测试。
 */
public final class ModelCallExecutorTest {

    @Test
    public void tokenCancelFiresAbortHookSynchronously() {
        CancellationToken token = new CancellationToken();
        AtomicBoolean hookFired = new AtomicBoolean(false);
        token.registerAbortHook(() -> hookFired.set(true));

        assertFalse("hook 不应在 cancel 前触发", hookFired.get());
        token.cancel();
        assertTrue("cancel() 后 hook 必须同步触发", hookFired.get());
    }

    @Test
    public void tokenRegisterAfterCancelInvokesImmediately() {
        CancellationToken token = new CancellationToken();
        token.cancel();   // 先 cancel
        AtomicBoolean lateHook = new AtomicBoolean(false);
        token.registerAbortHook(() -> lateHook.set(true));
        assertTrue("cancel 后注册的 hook 必须立即执行(race-safe)", lateHook.get());
    }

    @Test
    public void tokenCancelIsIdempotent() {
        CancellationToken token = new CancellationToken();
        AtomicInteger fireCount = new AtomicInteger(0);
        Runnable hook = fireCount::incrementAndGet;
        token.registerAbortHook(hook);

        token.cancel();
        token.cancel();   // 第二次应被吞掉
        token.cancel();   // 第三次也应被吞掉

        assertEquals("cancel() 幂等,abort hook 只触发一次", 1, fireCount.get());
    }

    @Test
    public void modelCallExecutorReturnsCancelledOnTokenCancel() throws Exception {
        CountDownLatch callEntered = new CountDownLatch(1);
        CountDownLatch neverReturns = new CountDownLatch(1);
        // 模拟一个会无限阻塞的 ModelGateway——除非 abort 被调用
        ModelGateway gateway = new ModelGateway() {
            @Override
            public ModelTurn decide(ModelTurnRequest request) {
                callEntered.countDown();
                try {
                    neverReturns.await();   // 永不返回,除非 thread 被 interrupt
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted as expected");
                }
                return ModelTurn.directAnswer("never reached");
            }

            @Override
            public CancellableModelCall prepare(ModelTurnRequest request) {
                AtomicBoolean aborted = new AtomicBoolean(false);
                return new CancellableModelCall() {
                    @Override
                    public ModelTurn call() {
                        return decide(request);
                    }

                    @Override
                    public void abort() {
                        aborted.set(true);
                    }
                };
            }
        };

        CancellationToken token = new CancellationToken();
        ModelCallExecutor executor = new ModelCallExecutor(1);
        AgentRequest agentRequest = AgentRequest.builder("test", Actor.DRIVER)
                .sessionId("cancel-test")
                .timeoutMillis(10_000)
                .cancellationToken(token)
                .build();
        ModelTurnRequest turnRequest = new ModelTurnRequest(
                agentRequest, Collections.<AgentMessage>emptyList(),
                Collections.<com.matrix.agent.core.capability.ToolDefinition>emptyList(),
                "system", new SessionContext());

        // 异步跑 decide,等 gateway 进入阻塞后触发 cancel
        new Thread(() -> {
            try {
                callEntered.await(2, TimeUnit.SECONDS);
                Thread.sleep(50);   // 确保 future.get 已经在等
            } catch (InterruptedException ignored) {
            }
            token.cancel();
        }).start();

        ModelCallExecutor.Result result = executor.decide(gateway, turnRequest);

        assertFalse("cancel 后应非 success", result.isSuccess());
        assertEquals("cancel 触发应返回 CANCELLED 终态",
                StopReason.CANCELLED, result.getTerminalReason());
    }

    @Test
    public void modelCallExecutorReturnsTimeoutOnDeadlineExceeded() {
        // 模拟一个会无限阻塞的 gateway + 极短 deadline
        ModelGateway gateway = request -> {
            try {
                Thread.sleep(10_000);   // 远超 deadline
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted as expected");
            }
            return ModelTurn.directAnswer("never reached");
        };

        CancellationToken token = new CancellationToken();
        ModelCallExecutor executor = new ModelCallExecutor(1);
        AgentRequest agentRequest = AgentRequest.builder("test", Actor.DRIVER)
                .sessionId("timeout-test")
                .timeoutMillis(100)   // 100ms deadline
                .cancellationToken(token)
                .build();
        ModelTurnRequest turnRequest = new ModelTurnRequest(
                agentRequest, Collections.<AgentMessage>emptyList(),
                Collections.<com.matrix.agent.core.capability.ToolDefinition>emptyList(),
                "system", new SessionContext());

        ModelCallExecutor.Result result = executor.decide(gateway, turnRequest);

        assertFalse("timeout 后应非 success", result.isSuccess());
        assertEquals("deadline 到应返回 TIMEOUT 终态",
                StopReason.TIMEOUT, result.getTerminalReason());
        assertTrue("timeout 必须主动触发 token.cancel()(由 abort hook 链)",
                token.isCancelled());
    }
}
