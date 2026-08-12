package com.matrix.agent.data.voice;

import com.matrix.agent.core.agent.AgentOutcome;
import com.matrix.agent.core.agent.StopReason;
import com.matrix.agent.core.agent.TaskState;
import com.matrix.agent.core.agent.Trajectory;
import com.matrix.agent.core.identity.CancellationToken;
import com.matrix.agent.core.voice.AsrPort;
import com.matrix.agent.core.voice.AgentRunner;
import com.matrix.agent.core.voice.ResponsePresenter;
import com.matrix.agent.core.voice.SpeakableResponse;
import com.matrix.agent.core.voice.TtsPort;
import com.matrix.agent.core.voice.VoiceSessionState;
import com.matrix.agent.core.voice.WakeWordPort;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link VoiceSessionController} JVM 测试。Voice V1 Stage 3。
 *
 * <p>fake 端口(手动触发 listener)+ lambda {@link AgentRunner}。大部分用例用同步 executor
 * ({@link #direct()} 即时执行,确定性);{@code cancel_duringThinking} 用异步 agentExecutor +
 * 阻塞 runner 验证进行中取消。
 */
public final class VoiceSessionControllerTest {
    private final ResponsePresenter presenter = new ResponsePresenter();
    private FakeWakeWordPort wake;
    private FakeAsrPort asr;
    private FakeTtsPort tts;

    private VoiceSessionController newController(AgentRunner runner, Executor stateExec, Executor agentExec) {
        wake = new FakeWakeWordPort();
        asr = new FakeAsrPort();
        tts = new FakeTtsPort();
        VoiceSessionController c = new VoiceSessionController(
                wake, asr, tts, runner, presenter, stateExec, agentExec);
        c.start();
        return c;
    }

    private static Executor direct() {
        return r -> r.run();
    }

    // ---- 正常流 ----

    @Test
    public void wakeFlow_toSpeaking_thenIdle() {
        AgentRunner runner = (text, token) -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());

        wake.fireWake();
        asr.fireFinal("开空调");
        assertEquals(VoiceSessionState.State.SPEAKING, c.currentState());
        assertEquals("已完成。", tts.lastSpoken.getText());

        tts.fireDone();
        assertEquals(VoiceSessionState.State.IDLE, c.currentState());
    }

    @Test
    public void wakeFlow_singleCapability_usesFriendlyName() {
        // SUCCEEDED + 1 个成功 capability → 友好名
        AgentRunner runner = (text, token) -> outcome(TaskState.SUCCEEDED, StopReason.DONE,
                successObs("climate.set_temperature"));
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        asr.fireFinal("开空调");
        assertEquals("已调整空调温度。", tts.lastSpoken.getText());
    }

    @Test
    public void emptyFinal_rejected_backToListening() {
        AgentRunner runner = (text, token) -> {
            throw new AssertionError("空文本不应调 runner");
        };
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        asr.fireFinal("");
        assertEquals(VoiceSessionState.State.LISTENING, c.currentState());
    }

    // ---- barge-in ----

    @Test
    public void bargeIn_duringSpeaking() {
        final CancellationToken[] tokenHolder = new CancellationToken[1];
        AgentRunner runner = (text, token) -> {
            tokenHolder[0] = token;
            return succeededOutcome();
        };
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        asr.fireFinal("开空调");              // → SPEAKING
        assertEquals(VoiceSessionState.State.SPEAKING, c.currentState());

        c.onBargeIn();                         // → BARGE_IN_LISTENING → LISTENING
        assertEquals(VoiceSessionState.State.LISTENING, c.currentState());
        assertTrue("TTS 应停止", tts.stopCalled);
        assertTrue("execute 的 token 应被 cancel", tokenHolder[0].isCancelled());
    }

    // ---- 取消(异步 runner)----

    @Test
    public void cancel_duringThinking() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch aborted = new CountDownLatch(1);
        final CancellationToken[] tokenHolder = new CancellationToken[1];

        AgentRunner runner = (text, token) -> {
            tokenHolder[0] = token;
            token.registerAbortHook(aborted::countDown);
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return cancelledOutcome();
        };
        ExecutorService agentService = Executors.newSingleThreadExecutor();
        VoiceSessionController c = newController(runner, direct(), agentService);

        wake.fireWake();
        asr.fireFinal("开空调");               // → THINKING + 异步 runner 阻塞
        assertTrue("runner 应进入", entered.await(1, TimeUnit.SECONDS));
        assertEquals(VoiceSessionState.State.THINKING, c.currentState());

        c.onCancel();                          // → CANCELLED + token.cancel → reset IDLE
        assertTrue("token.cancel 应触发 abort hook", aborted.await(1, TimeUnit.SECONDS));

        release.countDown();                   // 放行 runner → 返回 CANCELLED → onTerminal 忽略
        agentService.submit(() -> { }).get(2, TimeUnit.SECONDS);  // 等队列清空
        assertEquals(VoiceSessionState.State.IDLE, c.currentState());
        assertTrue(tokenHolder[0].isCancelled());
        agentService.shutdownNow();
    }

    // ---- 错误路径 ----

    @Test
    public void ttsError_toErrorAnnouncing_thenIdle() {
        AgentRunner runner = (text, token) -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        asr.fireFinal("开空调");               // → SPEAKING
        tts.fireError("TTS_FAIL");             // → ERROR_ANNOUNCING → cleanup → IDLE
        assertEquals(VoiceSessionState.State.IDLE, c.currentState());
    }

    @Test
    public void agentRunner_exception_recoversToIdle() {
        AgentRunner runner = (text, token) -> {
            throw new RuntimeException("boom");
        };
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        asr.fireFinal("开空调");               // → THINKING → runner 抛异常 → ERROR → IDLE
        assertEquals(VoiceSessionState.State.IDLE, c.currentState());
    }

    @Test
    public void asrError_recoversToIdle() {
        AgentRunner runner = (text, token) -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();                       // → LISTENING
        asr.fireError("ASR_FAIL");             // → ERROR_ANNOUNCING → IDLE
        assertEquals(VoiceSessionState.State.IDLE, c.currentState());
    }

    /**
     * 回归:shutdown(closed=true)后,迟到的 onTtsDone 经 runInState 跳过,不会 wakePort.start()
     * (避免销毁后重新 start 端口、踩已关 Model)。
     */
    @Test
    public void shutdown_thenLateTtsDone_doesNotRestartWake() {
        AgentRunner runner = (text, token) -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        asr.fireFinal("开空调");               // → SPEAKING(onWake 已 wakePort.stop)
        assertEquals(VoiceSessionState.State.SPEAKING, c.currentState());
        assertFalse("SPEAKING 时 wake 应已停", wake.started);

        c.shutdown(null);                      // closed=true + 串行 stop asr/wake/tts
        assertFalse("shutdown 后 wake 应停", wake.started);

        tts.fireDone();                        // 迟到的 onTtsDone
        assertFalse("迟到 onTtsDone 不应重启 wake", wake.started);
    }

    /**
     * 回归:controller.shutdown + stateExecutor.shutdown() 后,迟到的 TTS 回调再调 runInState
     * 不应抛 RejectedExecutionException(stateExecutor 已拒绝),也不应重启 wake。
     */
    @Test
    public void shutdown_thenExecutorShutdown_lateCallback_noRejected_noRestartWake() throws Exception {
        ExecutorService stateExec = Executors.newSingleThreadExecutor();
        ExecutorService agentExec = Executors.newSingleThreadExecutor();
        try {
            AgentRunner runner = (text, token) -> succeededOutcome();
            wake = new FakeWakeWordPort();
            asr = new FakeAsrPort();
            tts = new FakeTtsPort();
            VoiceSessionController c = new VoiceSessionController(
                    wake, asr, tts, runner, presenter, stateExec, agentExec);
            c.start();
            wake.fireWake();
            asr.fireFinal("开空调");
            awaitState(c, VoiceSessionState.State.SPEAKING, 2000);

            c.shutdown(null);          // closed=true
            stateExec.shutdown();      // 模拟 onCleared afterStopped 的 stateExecutor.shutdown()
            tts.fireDone();            // 迟到回调:runInState 入口 if(closed) return,不 execute、不抛
            assertFalse("迟到回调不应重启 wake", wake.started);
        } finally {
            agentExec.shutdownNow();
        }
    }

    private static void awaitState(VoiceSessionController c, VoiceSessionState.State expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (c.currentState() == expected) return;
            Thread.sleep(10);
        }
        throw new AssertionError("超时未到 " + expected + ", 当前 " + c.currentState());
    }

    // ---- outcome / observation 装配 ----

    private static AgentOutcome succeededOutcome() {
        return new AgentOutcome("req", TaskState.SUCCEEDED, StopReason.DONE, new Trajectory(), 0L);
    }

    private static AgentOutcome cancelledOutcome() {
        return new AgentOutcome("req", TaskState.CANCELLED, StopReason.CANCELLED, new Trajectory(), 0L);
    }

    private static AgentOutcome outcome(TaskState state, StopReason reason,
            com.matrix.agent.core.agent.ToolObservation... obs) {
        Trajectory t = new Trajectory();
        if (obs.length > 0) {
            t.addIteration(new com.matrix.agent.core.agent.AgentIteration(1,
                    com.matrix.agent.core.agent.AgentMessage.assistant("ok", java.util.Collections.emptyList()),
                    java.util.Collections.emptyList(),
                    java.util.Arrays.asList(obs),
                    java.util.Collections.emptyList(),
                    0L));
        }
        t.finish(reason, 0L, obs.length);
        return new AgentOutcome("req", state, reason, t, 0L);
    }

    private static com.matrix.agent.core.agent.ToolObservation successObs(String cap) {
        com.matrix.agent.core.tool.ToolResult r = new com.matrix.agent.core.tool.ToolResult(
                com.matrix.agent.core.tool.ToolResult.Status.SUCCESS, cap, "ok",
                java.util.Collections.emptyMap(), true, 0L);
        return com.matrix.agent.core.agent.ToolObservation.fromParts("call-1", cap, r, null, false);
    }

    // ---- fakes ----

    static final class FakeWakeWordPort implements WakeWordPort {
        WakeWordPort.Listener listener;
        boolean started;

        @Override
        public void setListener(WakeWordPort.Listener listener) {
            this.listener = listener;
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void feed(byte[] pcm) {
        }

        @Override
        public void stop() {
            started = false;
        }

        void fireWake() {
            if (listener != null) listener.onWake();
        }
    }

    static final class FakeAsrPort implements AsrPort {
        AsrPort.Listener listener;
        boolean started;

        @Override
        public void setListener(AsrPort.Listener listener) {
            this.listener = listener;
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void feed(byte[] pcm) {
        }

        @Override
        public void stop() {
            started = false;
        }

        void fireFinal(String text) {
            if (listener != null) listener.onFinal(text);
        }

        void fireError(String code) {
            if (listener != null) listener.onError(code);
        }
    }

    static final class FakeTtsPort implements TtsPort {
        TtsPort.Listener listener;
        SpeakableResponse lastSpoken;
        boolean stopCalled;

        @Override
        public void setListener(TtsPort.Listener listener) {
            this.listener = listener;
        }

        @Override
        public void speak(SpeakableResponse response) {
            lastSpoken = response;
            stopCalled = false;
        }

        @Override
        public void stop() {
            stopCalled = true;
        }

        void fireDone() {
            if (listener != null) listener.onDone();
        }

        void fireError(String code) {
            if (listener != null) listener.onError(code);
        }
    }
}
