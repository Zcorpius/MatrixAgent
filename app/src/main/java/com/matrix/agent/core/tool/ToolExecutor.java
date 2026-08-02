package com.matrix.agent.core.tool;

import android.util.Log;

import com.matrix.agent.core.capability.*;
import com.matrix.agent.core.identity.*;


import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Enforces per-capability timeout and propagates cancellation around provider calls. */
public final class ToolExecutor {
    private static final String TAG = "MatrixAgent";
    private static final long CANCELLATION_POLL_MILLIS = 50L;
    private final ExecutorService workers;

    public ToolExecutor(int parallelism) {
        if (parallelism <= 0) throw new IllegalArgumentException("parallelism 必须大于 0");
        workers = Executors.newFixedThreadPool(parallelism, new ToolThreadFactory());
        Log.i(TAG, "[Tool] init parallelism=" + parallelism);
    }

    public ToolResult execute(CapabilityProvider provider, CapabilityDefinition definition,
            AgentRequest request, ToolCall call) {
        long started = System.nanoTime();
        long budgetMillis = Math.min(definition.getTimeoutMillis(), request.remainingMillis());
        if (budgetMillis <= 0) {
            Log.w(TAG, "[Tool] pre-call deadline passed cap=" + call.getCapabilityName()
                    + " id=" + call.getStepId());
            return timedOut(call, "任务已超过截止时间", started);
        }

        Log.d(TAG, "[Tool] submit cap=" + call.getCapabilityName()
                + " id=" + call.getStepId()
                + " budgetMs=" + budgetMillis
                + " (capability=" + definition.getTimeoutMillis()
                + " request=" + request.remainingMillis() + ")");
        Future<ToolResult> future = workers.submit(() -> provider.execute(request, call));
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        try {
            while (true) {
                if (request.isCancelled()) {
                    future.cancel(true);
                    Log.w(TAG, "[Tool] cancellation observed cap=" + call.getCapabilityName()
                            + " id=" + call.getStepId() + " -> CANCELLED");
                    return cancelled(call, started);
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    future.cancel(true);
                    Log.w(TAG, "[Tool] capability timeout cap=" + call.getCapabilityName()
                            + " id=" + call.getStepId() + " budgetMs=" + budgetMillis
                            + " -> TIMED_OUT");
                    return timedOut(call, "Capability 执行超过 " + budgetMillis + " ms", started);
                }
                long waitNanos = Math.min(remainingNanos,
                        TimeUnit.MILLISECONDS.toNanos(CANCELLATION_POLL_MILLIS));
                try {
                    ToolResult result = future.get(waitNanos, TimeUnit.NANOSECONDS);
                    Log.d(TAG, "[Tool] <- cap=" + call.getCapabilityName()
                            + " id=" + call.getStepId()
                            + " status=" + result.getStatus()
                            + " verified=" + result.isVerified()
                            + " durationMs=" + result.getDurationMillis());
                    return result;
                } catch (TimeoutException ignored) {
                    // Poll cancellation until the capability or request deadline is reached.
                }
            }
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            Log.w(TAG, "[Tool] worker thread interrupted cap=" + call.getCapabilityName()
                    + " -> CANCELLED");
            return cancelled(call, started);
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause() == null ? execution : execution.getCause();
            Log.e(TAG, "[Tool] provider threw cap=" + call.getCapabilityName()
                    + " id=" + call.getStepId() + " " + cause.getClass().getSimpleName()
                    + ": " + safeMessage(cause) + " -> EXECUTION_FAILED", execution);
            return new ToolResult(ToolResult.Status.EXECUTION_FAILED, call.getCapabilityName(),
                    "Capability 执行异常：" + safeMessage(cause), Collections.emptyMap(), false,
                    elapsedMillis(started));
        }
    }

    private static ToolResult timedOut(ToolCall call, String message, long started) {
        return new ToolResult(ToolResult.Status.TIMED_OUT, call.getCapabilityName(), message,
                Collections.emptyMap(), false, elapsedMillis(started));
    }

    private static ToolResult cancelled(ToolCall call, long started) {
        return new ToolResult(ToolResult.Status.CANCELLED, call.getCapabilityName(), "Capability 已取消",
                Collections.emptyMap(), false, elapsedMillis(started));
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static final class ToolThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "matrix-tool-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
