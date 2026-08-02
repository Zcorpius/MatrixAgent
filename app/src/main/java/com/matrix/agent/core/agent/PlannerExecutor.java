package com.matrix.agent.core.agent;
import com.matrix.agent.core.identity.*;
import com.matrix.agent.core.session.*;


import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @deprecated V0.4.0 起由 {@link ModelCallExecutor} 取代。PlannerExecutor 仅作为
 * {@code LlmPlanner} 兼容路径的并发包装保留,后续版本会删除。
 */
@Deprecated
public final class PlannerExecutor {
    private static final long CANCELLATION_POLL_MILLIS = 50L;
    private final ExecutorService workers;

    public PlannerExecutor(int parallelism) {
        if (parallelism <= 0) throw new IllegalArgumentException("parallelism 必须大于 0");
        workers = Executors.newFixedThreadPool(parallelism, new PlannerThreadFactory());
    }

    public Result plan(Planner planner, AgentRequest request, SessionContext context) {
        long budgetMillis = request.remainingMillis();
        if (budgetMillis <= 0) return Result.terminal(TaskState.TIMED_OUT, "规划前已超过截止时间");
        Future<TaskPlan> future = workers.submit(() -> planner.plan(request, context));
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        try {
            while (true) {
                if (request.isCancelled()) {
                    future.cancel(true);
                    return Result.terminal(TaskState.CANCELLED, "规划已取消");
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    future.cancel(true);
                    return Result.terminal(TaskState.TIMED_OUT, "规划超过请求截止时间");
                }
                try {
                    TaskPlan plan = future.get(Math.min(remainingNanos,
                            TimeUnit.MILLISECONDS.toNanos(CANCELLATION_POLL_MILLIS)), TimeUnit.NANOSECONDS);
                    return Result.success(plan);
                } catch (TimeoutException ignored) {
                    // Continue polling cancellation/deadline.
                }
            }
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return Result.terminal(TaskState.CANCELLED, "规划线程已中断");
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause() == null ? execution : execution.getCause();
            return Result.terminal(TaskState.FAILED, "规划异常：" + safeMessage(cause));
        }
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    public static final class Result {
        private final TaskPlan plan;
        private final TaskState terminalState;
        private final String message;

        private Result(TaskPlan plan, TaskState terminalState, String message) {
            this.plan = plan;
            this.terminalState = terminalState;
            this.message = message;
        }

        public static Result success(TaskPlan plan) { return new Result(plan, null, ""); }
        public static Result terminal(TaskState state, String message) { return new Result(null, state, message); }
        public boolean isSuccess() { return plan != null; }
        public TaskPlan getPlan() { return plan; }
        public TaskState getTerminalState() { return terminalState; }
        public String getMessage() { return message; }
    }

    private static final class PlannerThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "matrix-planner-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
