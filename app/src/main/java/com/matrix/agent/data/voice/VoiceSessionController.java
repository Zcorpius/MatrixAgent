package com.matrix.agent.data.voice;

import android.util.Log;

import com.matrix.agent.core.agent.AgentOutcome;
import com.matrix.agent.core.identity.CancellationToken;
import com.matrix.agent.core.voice.AsrPort;
import com.matrix.agent.core.voice.AgentRunner;
import com.matrix.agent.core.voice.ResponsePresenter;
import com.matrix.agent.core.voice.SpeakableResponse;
import com.matrix.agent.core.voice.TtsPort;
import com.matrix.agent.core.voice.VoiceEvent;
import com.matrix.agent.core.voice.VoiceSessionListener;
import com.matrix.agent.core.voice.VoiceSessionState;
import com.matrix.agent.core.voice.WakeWordPort;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 语音会话控制器。Voice V1 Stage 3。
 *
 * <p>端口事件 → 状态迁移 → side effect。两个 executor:{@code stateExecutor}(序列化 transit + 轻量
 * side effect)、{@code agentExecutor}(跑 {@link AgentRunner#run},阻塞到终态)。
 *
 * <p><b>销毁语义</b>:用专用 {@link #shutdown(Runnable)},**不复用 onCancel**——onCancel 是"用户取消,
 * 回 IDLE 等唤醒"(会 {@code wakePort.start()}),与销毁矛盾。{@code shutdown} 先置 {@link #closed},
 * 使队列中迟到的端口回调({@code onTtsDone}/{@code onWake} 等)经 {@link #runInState} 直接 return,
 * 不会在销毁后重新 start 端口;然后串行 stop asr/wake/tts + cancel token,完成后回调 {@code afterStopped}
 * (调用方在里面关 Model,保证 Recognizer 先于 Model close)。
 *
 * <p>Demo 约束:固定身份(经 AgentRunner 注入 Actor.DRIVER);遇 USER_CONFIRM 不预检测;VadPort 不接。
 */
public final class VoiceSessionController {
    private static final String TAG = "MatrixAgent";
    private static final String LANG = "zh-CN";

    private final VoiceSessionState state = new VoiceSessionState();
    private final WakeWordPort wakePort;
    private final AsrPort asrPort;
    private final TtsPort ttsPort;
    private final AgentRunner runner;
    private final ResponsePresenter presenter;
    private final Executor stateExecutor;
    private final Executor agentExecutor;

    private final WakeWordPort.Listener wakeListener;
    private final AsrPort.Listener asrListener;
    private final TtsPort.Listener ttsListener;

    private volatile CancellationToken currentToken;
    private volatile VoiceSessionListener uiListener;
    /** shutdown 后置位:runInState 中所有迟到任务直接 return,阻止重新 start 端口。 */
    private volatile boolean closed;

    public VoiceSessionController(WakeWordPort wakePort, AsrPort asrPort, TtsPort ttsPort,
            AgentRunner runner, ResponsePresenter presenter,
            Executor stateExecutor, Executor agentExecutor) {
        if (wakePort == null) throw new IllegalArgumentException("wakePort 不能为空");
        if (asrPort == null) throw new IllegalArgumentException("asrPort 不能为空");
        if (ttsPort == null) throw new IllegalArgumentException("ttsPort 不能为空");
        if (runner == null) throw new IllegalArgumentException("runner 不能为空");
        if (presenter == null) throw new IllegalArgumentException("presenter 不能为空");
        if (stateExecutor == null) throw new IllegalArgumentException("stateExecutor 不能为空");
        if (agentExecutor == null) throw new IllegalArgumentException("agentExecutor 不能为空");
        this.wakePort = wakePort;
        this.asrPort = asrPort;
        this.ttsPort = ttsPort;
        this.runner = runner;
        this.presenter = presenter;
        this.stateExecutor = stateExecutor;
        this.agentExecutor = agentExecutor;

        this.wakeListener = new WakeWordPort.Listener() {
            @Override
            public void onWake() {
                VoiceSessionController.this.onWake();
            }

            @Override
            public void onError(String code) {
                VoiceSessionController.this.onWakeError(code);
            }
        };
        this.asrListener = new AsrPort.Listener() {
            @Override
            public void onPartial(String text) {
                VoiceSessionController.this.onPartial(text);
            }

            @Override
            public void onFinal(String text) {
                VoiceSessionController.this.onFinal(text);
            }

            @Override
            public void onError(String code) {
                VoiceSessionController.this.onAsrError(code);
            }
        };
        this.ttsListener = new TtsPort.Listener() {
            @Override
            public void onDone() {
                VoiceSessionController.this.onTtsDone();
            }

            @Override
            public void onError(String code) {
                VoiceSessionController.this.onTtsError(code);
            }
        };
    }

    public void setUiListener(VoiceSessionListener listener) {
        this.uiListener = listener;
    }

    public VoiceSessionState.State currentState() {
        return state.current();
    }

    /** 启动:注册端口回调,进入等唤醒。 */
    public void start() {
        wakePort.setListener(wakeListener);
        asrPort.setListener(asrListener);
        ttsPort.setListener(ttsListener);
        runInState(() -> {
            wakePort.start();
            Log.i(TAG, "[Voice] Controller 启动,等待唤醒");
        });
    }

    /**
     * 销毁专用(不复用 onCancel)。先置 {@code closed} 阻止队列中迟到事件 restart 端口;
     * 再串行 stop asr/wake/tts + cancel token,完成后 {@code afterStopped}(调用方在里面关 Model,
     * 保证 Recognizer 先于 Model close)。
     */
    public void shutdown(Runnable afterStopped) {
        closed = true;
        stateExecutor.execute(() -> {
            try {
                if (currentToken != null) currentToken.cancel();
                asrPort.stop();
                wakePort.stop();
                ttsPort.stop();
                uiListener = null;
            } finally {
                if (afterStopped != null) afterStopped.run();
            }
        });
    }

    /**
     * 提交到 stateExecutor。关闭后迟到的 native/TTS/Agent 回调直接丢弃:
     * ① 入口 {@code if(closed) return} 拦绝大多数;② {@code try/catch RejectedExecutionException}
     * 兜底"检查与 execute 之间发生 shutdown"的窗口(stateExecutor.shutdown() 后 execute 抛)。
     */
    private void runInState(Runnable task) {
        if (closed) return;
        try {
            stateExecutor.execute(() -> {
                if (closed) return;
                task.run();
            });
        } catch (RejectedExecutionException e) {
            if (!closed) throw e; // 仅允许因正常销毁忽略;运行期拒绝仍暴露
            Log.d(TAG, "[Voice] 忽略关闭后的 state 回调");
        }
    }

    // ---- 端口回调(均经 runInState)----

    private void onWake() {
        runInState(() -> {
            transit(VoiceEvent.wake());
            wakePort.stop();
            asrPort.start();
            transit(VoiceEvent.captureStarted());
        });
    }

    private void onPartial(String text) {
        runInState(() -> {
            if (uiListener != null) uiListener.onPartial(text);
        });
    }

    private void onFinal(String text) {
        runInState(() -> {
            VoiceSessionState.State s = transit(VoiceEvent.finalTranscript(text, 1f));
            if (s == VoiceSessionState.State.ENDPOINTING) {
                s = transit(VoiceEvent.finalTranscript(text, 1f));
            }
            if (s != VoiceSessionState.State.RECOGNIZING) {
                Log.w(TAG, "[Voice] onFinal 未到 RECOGNIZING, state=" + s);
                return;
            }
            asrPort.stop();
            String cmd = text == null ? "" : text.trim();
            if (cmd.isEmpty()) {
                transit(VoiceEvent.rejected());
                asrPort.start();
                return;
            }
            transit(VoiceEvent.accepted());
            CancellationToken token = new CancellationToken();
            currentToken = token;
            try {
                agentExecutor.execute(() -> runAgent(cmd, token));
            } catch (RejectedExecutionException e) {
                if (closed) return; // 销毁中,executor 已关
                Log.w(TAG, "[Voice] Agent executor 已关闭");
                handleRunnerError();
            }
        });
    }

    private void runAgent(String cmd, CancellationToken token) {
        AgentOutcome outcome;
        try {
            outcome = runner.run(cmd, token);
        } catch (Exception e) {
            Log.e(TAG, "[Voice] AgentRunner 异常: " + e.getMessage(), e);
            runInState(this::handleRunnerError);
            return;
        }
        final AgentOutcome o = outcome;
        runInState(() -> onTerminal(o));
    }

    private void onTerminal(AgentOutcome outcome) {
        if (state.current() != VoiceSessionState.State.THINKING) {
            Log.i(TAG, "[Voice] onTerminal 时 state=" + state.current() + ",忽略迟到 outcome");
            return;
        }
        SpeakableResponse resp = presenter.present(outcome, LANG);
        transit(VoiceEvent.terminal());
        ttsPort.speak(resp);
    }

    private void onTtsDone() {
        runInState(() -> {
            transit(VoiceEvent.ttsDone());
            wakePort.start();
        });
    }

    private void onTtsError(String code) {
        runInState(() -> {
            Log.w(TAG, "[Voice] TTS 错误: " + code);
            if (transit(VoiceEvent.error(code)) == VoiceSessionState.State.ERROR_ANNOUNCING) {
                cleanupAndIdle();
            }
        });
    }

    private void onAsrError(String code) {
        runInState(() -> {
            Log.w(TAG, "[Voice] ASR 错误: " + code);
            if (transit(VoiceEvent.asrError(code)) == VoiceSessionState.State.ERROR_ANNOUNCING) {
                cleanupAndIdle();
            }
        });
    }

    private void onWakeError(String code) {
        runInState(() -> Log.w(TAG, "[Voice] 唤醒错误: " + code + "(等待恢复)"));
    }

    private void handleRunnerError() {
        if (transit(VoiceEvent.error("AGENT_RUNNER_FAILED")) == VoiceSessionState.State.ERROR_ANNOUNCING) {
            cleanupAndIdle();
        }
    }

    private void cleanupAndIdle() {
        if (currentToken != null) currentToken.cancel();
        asrPort.stop();
        ttsPort.stop();
        transit(VoiceEvent.reset());
        wakePort.start();
    }

    // ---- 外部触发(第 4 步 Activity 调)----

    public void onBargeIn() {
        runInState(() -> {
            if (transit(VoiceEvent.bargeIn()) != VoiceSessionState.State.BARGE_IN_LISTENING) {
                Log.w(TAG, "[Voice] onBargeIn 非 SPEAKING 态,忽略");
                return;
            }
            ttsPort.stop();
            if (currentToken != null) currentToken.cancel();
            asrPort.start();
            transit(VoiceEvent.captureStarted());
        });
    }

    public void onCancel() {
        runInState(() -> {
            if (transit(VoiceEvent.cancel()) != VoiceSessionState.State.CANCELLED) {
                Log.w(TAG, "[Voice] onCancel 当前态不可取消,忽略");
                return;
            }
            if (currentToken != null) currentToken.cancel();
            asrPort.stop();
            ttsPort.stop();
            wakePort.stop();
            transit(VoiceEvent.reset());
            wakePort.start();
        });
    }

    /** 手动唤醒(按钮备用)。 */
    public void manualWake() {
        runInState(this::onWake);
    }

    private VoiceSessionState.State transit(VoiceEvent event) {
        VoiceSessionState.State to = state.transit(event);
        VoiceSessionListener l = uiListener;
        if (l != null) l.onStateChanged(to);
        return to;
    }
}
