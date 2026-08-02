package com.matrix.agent.core.agent;

import android.util.Log;

import com.matrix.agent.core.identity.AgentRequest;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** 用请求 deadline 和 cancel 令牌包裹 ModelGateway 调用,沿用 polling 模式。 */
public final class ModelCallExecutor {
    private static final String TAG = "MatrixAgent";
    private static final long CANCELLATION_POLL_MILLIS = 50L;
    private final ExecutorService workers;

    public ModelCallExecutor(int parallelism) {
        if (parallelism <= 0) throw new IllegalArgumentException("parallelism 必须大于 0");
        workers = Executors.newFixedThreadPool(parallelism, new ModelThreadFactory());
        Log.i(TAG, "[ModelCall] init parallelism=" + parallelism);
    }

    public Result decide(ModelGateway gateway, ModelTurnRequest request) {
        AgentRequest agentRequest = request.getAgentRequest();
        long budgetMillis = agentRequest.remainingMillis();
        if (budgetMillis <= 0) {
            Log.w(TAG, "[ModelCall] pre-call deadline already passed, terminal=TIMEOUT");
            return Result.terminal(StopReason.TIMEOUT, "调用模型前已超过截止时间");
        }

        long callStarted = System.nanoTime();
        Log.d(TAG, "[ModelCall] submit gateway=" + gateway.getClass().getSimpleName()
                + " budgetMs=" + budgetMillis
                + " req=" + agentRequest.getRequestId());
        Future<ModelTurn> future = workers.submit(() -> gateway.decide(request));
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        try {
            while (true) {
                if (agentRequest.isCancelled()) {
                    future.cancel(true);
                    Log.w(TAG, "[ModelCall] cancellation observed, terminal=CANCELLED costMs="
                            + elapsedMillis(callStarted));
                    return Result.terminal(StopReason.CANCELLED, "模型调用已取消");
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    future.cancel(true);
                    Log.w(TAG, "[ModelCall] deadline reached, terminal=TIMEOUT costMs="
                            + elapsedMillis(callStarted));
                    return Result.terminal(StopReason.TIMEOUT, "模型调用超过请求截止时间");
                }
                long waitNanos = Math.min(remainingNanos,
                        TimeUnit.MILLISECONDS.toNanos(CANCELLATION_POLL_MILLIS));
                try {
                    ModelTurn turn = future.get(waitNanos, TimeUnit.NANOSECONDS);
                    Log.d(TAG, "[ModelCall] gateway returned turn hasToolCalls=" + turn.hasToolCalls()
                            + " toolCalls=" + (turn.hasToolCalls() ? turn.getToolCalls().size() : 0)
                            + " costMs=" + elapsedMillis(callStarted));
                    return Result.success(turn);
                } catch (TimeoutException ignored) {
                    // Poll cancellation until gateway returns or deadline reached.
                }
            }
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            Log.w(TAG, "[ModelCall] worker thread interrupted, terminal=CANCELLED");
            return Result.terminal(StopReason.CANCELLED, "模型调用线程已中断");
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause() == null ? execution : execution.getCause();
            Log.e(TAG, "[ModelCall] gateway threw " + cause.getClass().getSimpleName()
                    + ": " + safeMessage(cause) + " -> terminal=POLICY_HALT costMs="
                    + elapsedMillis(callStarted), execution);
            return Result.terminal(StopReason.POLICY_HALT,
                    "模型调用异常:" + safeMessage(cause));
        }
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    public static final class Result {
        private final ModelTurn turn;
        private final StopReason terminalReason;
        private final String message;

        private Result(ModelTurn turn, StopReason terminalReason, String message) {
            this.turn = turn;
            this.terminalReason = terminalReason;
            this.message = message;
        }

        public static Result success(ModelTurn turn) { return new Result(turn, null, ""); }
        public static Result terminal(StopReason reason, String message) {
            return new Result(null, reason, message);
        }
        public boolean isSuccess() { return turn != null; }
        public ModelTurn getTurn() { return turn; }
        public StopReason getTerminalReason() { return terminalReason; }
        public String getMessage() { return message; }
    }

    private static final class ModelThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "matrix-model-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
