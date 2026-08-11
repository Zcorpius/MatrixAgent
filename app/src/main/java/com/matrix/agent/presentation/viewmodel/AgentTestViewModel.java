package com.matrix.agent.presentation.viewmodel;

import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.matrix.agent.core.agent.AgentIteration;
import com.matrix.agent.core.agent.AgentMessage;
import com.matrix.agent.core.agent.AgentOutcome;
import com.matrix.agent.core.agent.ToolObservation;
import com.matrix.agent.core.agent.Trajectory;
import com.matrix.agent.core.identity.Actor;
import com.matrix.agent.core.identity.CancellationToken;
import com.matrix.agent.core.identity.VehicleState;
import com.matrix.agent.core.identity.VehicleStateSource;
import com.matrix.agent.core.policy.PolicyDecision;
import com.matrix.agent.data.AgentRuntimeRepository;
import com.matrix.agent.data.ClearUserDataOutcome;
import com.matrix.agent.presentation.state.AgentUiState;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public final class AgentTestViewModel extends ViewModel {
    private static final String TAG = "MatrixAgent";
    private final AgentRuntimeRepository repository;
    /**
     * V0.4.3 Round 3 P2:车辆运动状态源——demo UI 切换 gear 时调 setGear,
     * 让 PolicyEngine 的 PARKED_ONLY 等前置约束在 APK 中可手动验证。
     * 后续版本接真车后,此字段移除或保留为只读(真实 state 由 CarPropertyManager 推)。
     */
    private final VehicleStateSource vehicleStateSource;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final MutableLiveData<AgentUiState> uiState = new MutableLiveData<>();
    private final AtomicLong operationSequence = new AtomicLong();

    /**
     * V0.4.3 Round 4 P2:按 Actor 维护独立 token / future。
     *
     * <p>Round 3 旧实现 execute() 每次新任务都先 cancelActiveOperation() 取消旧任务——
     * 单 ViewModel 只能保一个活跃任务,主副驾无法并存,demo 中无法手工验证"副驾运行中、主驾抢占"。
     *
     * <p>Round 4 改为按 Actor 隔离:主驾 execute 不会取消副驾的活跃任务,反过来亦然。
     * 这样副驾"查电量"运行中,主驾"查胎压"提交,TaskScheduler 的抢占协议可被肉眼看到——
     * logcat 出现 "[Scheduler] preempt ... by driver",uiState 显示主驾 SUCCEEDED,
     * 副驾的 PREEMPTED outcome 异步 post 时被主驾覆盖(用户可从 logcat 看完整轨迹)。
     */
    private final Map<Actor, ActiveOperation> activeOps = new EnumMap<>(Actor.class);

    public AgentTestViewModel(AgentRuntimeRepository repository, VehicleStateSource vehicleStateSource) {
        this.repository = repository;
        this.vehicleStateSource = vehicleStateSource;
        uiState.setValue(new AgentUiState(false, repository.getActiveModelGateway(),
                "READY\n\n当前车辆能力为有状态 Mock。"));
        Log.i(TAG, "[ViewModel] init gateway=" + repository.getActiveModelGateway());
    }

    public LiveData<AgentUiState> getUiState() {
        return uiState;
    }

    /**
     * V0.4.3 Round 3 P2:demo UI 切换车辆档位,gear=D 时 PARKED_ONLY 车控写被 PolicyEngine 拒绝。
     * 只在 DefaultVehicleStateSource(以及 MockVehicleStateSource)支持——真实车 后续版本由
     * CarPropertyManager 推送,demo 不再可手动切换。
     */
    public void setGear(VehicleState.Gear gear) {
        if (vehicleStateSource instanceof com.matrix.agent.core.identity.DefaultVehicleStateSource) {
            ((com.matrix.agent.core.identity.DefaultVehicleStateSource) vehicleStateSource).setGear(gear);
            Log.i(TAG, "[ViewModel] setGear=" + gear);
        } else if (vehicleStateSource instanceof com.matrix.agent.core.identity.MockVehicleStateSource) {
            ((com.matrix.agent.core.identity.MockVehicleStateSource) vehicleStateSource).setGear(gear);
            Log.i(TAG, "[ViewModel] setGear=" + gear);
        } else {
            Log.w(TAG, "[ViewModel] setGear ignored — unsupported source: "
                    + (vehicleStateSource == null ? "null" : vehicleStateSource.getClass().getSimpleName()));
        }
    }

    /**
     * V0.4.3 Round 4 P2:按 Actor 提交任务,不再取消另一 Actor 的活跃任务。
     */
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
        cancelActorOperation(actor);
        CancellationToken token = new CancellationToken();
        Future<?> future = executor.submit(() -> {
            try {
                AgentOutcome outcome = repository.execute(normalized, actor, token);
                Log.i(TAG, "[ViewModel] render outcome actor=" + actor
                        + " iterations=" + outcome.getTrajectory().getIterations().size());
                postIfCurrent(operationId, new AgentUiState(false, repository.getActiveModelGateway(),
                        render(actor, outcome)));
            } catch (Exception error) {
                Log.e(TAG, "[ViewModel] execute failed actor=" + actor + " " + safeMessage(error), error);
                postIfCurrent(operationId, new AgentUiState(false, repository.getActiveModelGateway(),
                        "执行失败 [" + actor + "]\n" + safeMessage(error)));
            }
        });
        activeOps.put(actor, new ActiveOperation(token, future, operationId));
        uiState.setValue(new AgentUiState(true, repository.getActiveModelGateway(),
                "[" + actor + "] 正在规划和执行…"));
    }

    public void showState() {
        AgentUiState current = uiState.getValue();
        uiState.setValue(new AgentUiState(current != null && current.loading,
                repository.getActiveModelGateway(),
                "MOCK STATE\n" + repository.getVehicleState()
                        + "\n\nSESSION\n" + repository.getSessionTurns()));
    }

    /**
     * 第十一轮 P1 + V0.5.1 Stage 6 P1.2:clearData 必须走后台 executor + try/catch,
     * 并根据 audit 维度清理结果选择"已清空" vs "上下文已清,审计删除失败"文案。
     *
     * <p>原实现 L132-139 直接在调用线程(主线程)调 {@link AgentRuntimeRepository#clearUserData()},
     * 内部最多等 1 秒 in-flight token 收敛 + 同步 SharedPreferences.commit() → UI 卡顿;
     * 更严重的是 SP commit 失败时 Repository 抛 IllegalStateException,UI 主线程直接承接 → App crash。
     *
     * <p>第十一轮修复:主线程先 bump sequence + cancelActiveOps + 显"正在清空" → 后台 executor 跑
     * clearUserDataDetailed → 成功 postValue "已清空",主流程失败 postValue "清空失败:[原因]"。
     *
     * <p>V0.5.1 Stage 6 P1.2 修复:V0.5.1 Stage 6 初版让 audit clearByUserZone 内部吞异常,
     * 主流程已删 / Audit 可能仍在,但 UI 永远显示"已清空"。修正:clearUserDataDetailed 返回
     * {@link ClearUserDataOutcome},audit 维度失败时 ViewModel 显示
     * "上下文与偏好已清空,但审计日志删除失败,请稍后再次点击清空。"——用户重试点按钮即重试。
     * 主流程(MemoryStore / SteerMailbox / Session)失败仍走"清空失败 [...]"路径。
     */
    public void clearData() {
        Log.i(TAG, "[ViewModel] clearData");
        long operationId = operationSequence.incrementAndGet();
        cancelAllOperations();
        uiState.setValue(new AgentUiState(true, repository.getActiveModelGateway(),
                "正在清空上下文与记忆…"));
        executor.submit(() -> {
            try {
                ClearUserDataOutcome outcome = repository.clearUserDataDetailed();
                if (outcome.auditFailed()) {
                    Log.w(TAG, "[ViewModel] clearData: context cleared but audit delete failed "
                            + outcome.summary());
                    postIfCurrent(operationId, new AgentUiState(false, repository.getActiveModelGateway(),
                            "上下文与偏好已清空,但审计日志删除失败,请稍后再次点击清空。"));
                } else {
                    postIfCurrent(operationId, new AgentUiState(false, repository.getActiveModelGateway(),
                            "已清空上下文与用户记忆。"));
                }
            } catch (RuntimeException error) {
                Log.e(TAG, "[ViewModel] clearData failed " + safeMessage(error), error);
                postIfCurrent(operationId, new AgentUiState(false, repository.getActiveModelGateway(),
                        "清空失败 [" + safeMessage(error) + "]"));
            }
        });
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
                .append(" (success=").append(trajectory.countSuccessfulToolCalls()).append(")\n");
        // 端侧推理性能：仅当 gateway 是 OnDeviceModelGateway 时 repository 返回非空，
        // 云端 / Demo 不显示。任务多轮迭代时反映最后一次 generate 的快照。
        String onDeviceStats = repository.getLastOnDeviceStats();
        if (onDeviceStats != null && !onDeviceStats.isEmpty()) {
            text.append("ONDEVICE ").append(onDeviceStats).append('\n');
        }
        text.append('\n');

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

    private synchronized void cancelActorOperation(Actor actor) {
        ActiveOperation op = activeOps.remove(actor);
        if (op != null) {
            if (op.token != null) op.token.cancel();
            if (op.future != null) op.future.cancel(true);
        }
    }

    private synchronized void cancelAllOperations() {
        for (ActiveOperation op : activeOps.values()) {
            if (op.token != null) op.token.cancel();
            if (op.future != null) op.future.cancel(true);
        }
        activeOps.clear();
    }

    @Override
    protected void onCleared() {
        Log.i(TAG, "[ViewModel] onCleared, shutting down executor");
        cancelAllOperations();
        executor.shutdownNow();
    }

    /** V0.4.3 Round 4 P2:per-Actor 活跃任务句柄。 */
    private static final class ActiveOperation {
        final CancellationToken token;
        final Future<?> future;
        final long operationId;

        ActiveOperation(CancellationToken token, Future<?> future, long operationId) {
            this.token = token;
            this.future = future;
            this.operationId = operationId;
        }
    }
}
