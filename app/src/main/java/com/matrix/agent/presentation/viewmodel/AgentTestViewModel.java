package com.matrix.agent.presentation.viewmodel;

import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.matrix.agent.core.agent.AgentIteration;
import com.matrix.agent.core.agent.AgentMessage;
import com.matrix.agent.core.agent.AgentOutcome;
import com.matrix.agent.core.agent.StopReason;
import com.matrix.agent.core.agent.ToolObservation;
import com.matrix.agent.core.agent.Trajectory;
import com.matrix.agent.core.identity.Actor;
import com.matrix.agent.core.identity.CancellationToken;
import com.matrix.agent.core.policy.PolicyDecision;
import com.matrix.agent.data.AgentRuntimeRepository;
import com.matrix.agent.presentation.state.AgentUiState;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public final class AgentTestViewModel extends ViewModel {
    private static final String TAG = "MatrixAgent";
    private final AgentRuntimeRepository repository;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final MutableLiveData<AgentUiState> uiState = new MutableLiveData<>();
    private final AtomicLong operationSequence = new AtomicLong();
    private Future<?> activeFuture;
    private CancellationToken activeToken;

    public AgentTestViewModel(AgentRuntimeRepository repository) {
        this.repository = repository;
        uiState.setValue(new AgentUiState(false, repository.getActiveModelGateway(),
                "READY\n\n当前车辆能力为有状态 Mock。"));
        Log.i(TAG, "[ViewModel] init gateway=" + repository.getActiveModelGateway());
    }

    public LiveData<AgentUiState> getUiState() {
        return uiState;
    }

    public void execute(String command, Actor actor) {
        String normalized = command == null ? "" : command.trim();
        if (normalized.isEmpty()) {
            Log.w(TAG, "[ViewModel] execute rejected: empty command");
            uiState.setValue(new AgentUiState(false, repository.getActiveModelGateway(), "请输入任务。"));
            return;
        }
        // 第七轮 P2-4:用户输入绝不原文进 logcat,只暴露字符数 + 元数据。
        Log.i(TAG, "[ViewModel] execute actor=" + actor
                + " command=" + com.matrix.agent.core.agent.SafeLog.USER_INPUT_PLACEHOLDER
                + " commandChars=" + normalized.length());
        long operationId = operationSequence.incrementAndGet();
        cancelActiveOperation();
        CancellationToken token = new CancellationToken();
        activeToken = token;
        uiState.setValue(new AgentUiState(true, repository.getActiveModelGateway(), "正在规划和执行…"));
        activeFuture = executor.submit(() -> {
            try {
                AgentOutcome outcome = repository.execute(normalized, actor, token);
                Log.i(TAG, "[ViewModel] render outcome iterations="
                        + outcome.getTrajectory().getIterations().size());
                postIfCurrent(operationId, new AgentUiState(false, repository.getActiveModelGateway(),
                        render(actor, outcome)));
            } catch (Exception error) {
                Log.e(TAG, "[ViewModel] execute failed: " + safeMessage(error), error);
                postIfCurrent(operationId, new AgentUiState(false, repository.getActiveModelGateway(),
                        "执行失败\n" + safeMessage(error)));
            }
        });
    }

    public void showState() {
        AgentUiState current = uiState.getValue();
        uiState.setValue(new AgentUiState(current != null && current.loading,
                repository.getActiveModelGateway(),
                "MOCK STATE\n" + repository.getVehicleState()
                        + "\n\nSESSION\n" + repository.getSessionTurns()));
    }

    public void clearData() {
        Log.i(TAG, "[ViewModel] clearData");
        operationSequence.incrementAndGet();
        cancelActiveOperation();
        repository.clearUserData();
        uiState.setValue(new AgentUiState(false, repository.getActiveModelGateway(),
                "已清空上下文与用户记忆。"));
    }

    public void refreshPlanner() {
        AgentUiState current = uiState.getValue();
        String output = current == null ? "READY" : current.output;
        boolean loading = current != null && current.loading;
        uiState.setValue(new AgentUiState(loading, repository.getActiveModelGateway(), output));
    }

    private String render(Actor actor, AgentOutcome outcome) {
        Trajectory trajectory = outcome.getTrajectory();
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);

        StringBuilder text = new StringBuilder("RESULT   ").append(outcome.getFinalState())
                .append("\nGATEWAY  ").append(repository.getActiveModelGateway())
                .append("\nACTOR    ").append(actor)
                .append("\nSTOP     ").append(outcome.getStopReason())
                .append("\nTIME     ").append(outcome.getDurationMillis()).append(" ms")
                .append("\nCALLS    ").append(trajectory.getTotalToolCalls())
                .append(" (success=").append(trajectory.countSuccessfulToolCalls()).append(")\n\n");

        for (AgentIteration iteration : trajectory.getIterations()) {
            text.append("=== Iteration ").append(iteration.getIteration())
                    .append("  (").append(iteration.getDurationMillis()).append(" ms) ===\n");
            AgentMessage assistant = iteration.getAssistantMessage();
            if (assistant.getContent() != null && !assistant.getContent().isEmpty()) {
                text.append("[assistant] ").append(assistant.getContent()).append('\n');
            }
            List<AgentIteration.ToolCallSnapshot> calls = iteration.getToolCalls();
            List<PolicyDecision> decisions = iteration.getPolicyDecisions();
            List<ToolObservation> observations = iteration.getObservations();
            for (int i = 0; i < calls.size(); i++) {
                AgentIteration.ToolCallSnapshot call = calls.get(i);
                PolicyDecision decision = i < decisions.size() ? decisions.get(i) : null;
                ToolObservation observation = i < observations.size() ? observations.get(i) : null;
                text.append("  • ").append(call.getCapabilityName())
                        .append(" args=").append(call.getArguments())
                        .append(" id=").append(call.getToolCallId()).append('\n');
                if (decision != null) {
                    text.append("    policy: ").append(decision.isAllowed() ? "ALLOW"
                            : decision.getRejectionType() + " - " + decision.getReason()).append('\n');
                }
                if (observation != null) {
                    text.append("    observation: ").append(observation.toToolContent()).append('\n');
                }
            }
            if (calls.isEmpty() && observations.isEmpty()) {
                text.append("  (无 Tool Call)\n");
            }
            text.append('\n');
        }

        text.append("STARTED  ").append(format.format(new Date(trajectory.getStartedAtMillis())))
                .append('\n');
        return text.toString();
    }

    private static String safeMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private void postIfCurrent(long operationId, AgentUiState state) {
        if (operationSequence.get() == operationId) uiState.postValue(state);
    }

    private synchronized void cancelActiveOperation() {
        if (activeToken != null) activeToken.cancel();
        if (activeFuture != null) activeFuture.cancel(true);
        activeToken = null;
        activeFuture = null;
    }

    @Override
    protected void onCleared() {
        Log.i(TAG, "[ViewModel] onCleared, shutting down executor");
        cancelActiveOperation();
        executor.shutdownNow();
    }
}
