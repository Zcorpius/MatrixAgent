package com.matrix.agent.data.voice;

import android.app.Application;

import com.matrix.agent.core.agent.AgentOutcome;
import com.matrix.agent.core.agent.StopReason;
import com.matrix.agent.core.agent.TaskState;
import com.matrix.agent.core.agent.TaskScheduler;
import com.matrix.agent.core.agent.Trajectory;
import com.matrix.agent.core.capability.CapabilityRegistry;
import com.matrix.agent.core.identity.KeywordIntentClassifier;
import com.matrix.agent.core.identity.MockVehicleStateSource;
import com.matrix.agent.core.session.SessionLockManager;
import com.matrix.agent.core.voice.AsrPort;
import com.matrix.agent.core.voice.AudioFocusPort;
import com.matrix.agent.core.voice.NoopVoiceMetrics;
import com.matrix.agent.core.voice.ResponsePresenter;
import com.matrix.agent.core.voice.TtsPort;
import com.matrix.agent.core.voice.VadPort;
import com.matrix.agent.core.voice.VoiceCapturePort;
import com.matrix.agent.core.voice.VoicePolicyConfig;
import com.matrix.agent.core.voice.WakeWordPort;
import com.matrix.agent.data.AgentRuntimeRepository;
import com.matrix.agent.data.audit.NoopAuditRepository;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link VoiceRuntime} JVM 测试:两层。
 *
 * <p>① 纯逻辑 seam:采音启动屏障 {@link VoiceRuntime#captureStartAllowed} 表驱动。
 *
 * <p>② 端到端恢复路径(publishForTest 注入真 Runtime + 真 Controller(假端口)+ 假 capture,
 * 真 executor/scheduler,重试延迟为真实 500/1000/1500ms):
 * <ul>
 *   <li>异常清理 pending 期间 pause→resume 不得 start(恢复屏障,清理完成后才恢复);</li>
 *   <li>清理成功后采音恰好重启一次(不重复);</li>
 *   <li>清理失败(端口 stop 抛异常)后不再自动重试,resume 也不得绕过;</li>
 *   <li>busy 重试有界(3 次耗尽报失败),pause 取消重试链。</li>
 * </ul>
 */
public final class VoiceRuntimeTest {

    // ---- 屏障表驱动 ----

    @Test
    public void captureStartAllowed_allClear_true() {
        assertTrue(VoiceRuntime.captureStartAllowed(false, true, false, false, false));
    }

    @Test
    public void captureStartAllowed_blockedByTerminalStates() {
        assertFalse("已销毁不得启动", VoiceRuntime.captureStartAllowed(true, true, false, false, false));
        assertFalse("后台不得启动", VoiceRuntime.captureStartAllowed(false, false, false, false, false));
        assertFalse("已有采音不得重复启动", VoiceRuntime.captureStartAllowed(false, true, true, false, false));
    }

    @Test
    public void captureStartAllowed_blockedByRecoveryBarrier() {
        // P2 核心:异常清理排队期间(pending),即使前台且无采音,resume/重试也不得启动新采音
        assertFalse("清理未完成不得启动(pause→resume 不得绕过)",
                VoiceRuntime.captureStartAllowed(false, true, false, true, false));
        // 清理失败:端口状态未知,停自动恢复,等用户重进页面
        assertFalse("清理失败不得自动恢复",
                VoiceRuntime.captureStartAllowed(false, true, false, false, true));
    }

    // ---- 端到端恢复路径 ----

    @Test
    public void recoveryPending_pauseResume_doesNotStartCapture() throws Exception {
        try (Fixture f = new Fixture()) {
            f.runtime.resume(); // 置前台(captureStarted=true → 不 start)
            CountDownLatch gate = new CountDownLatch(1);
            f.stateExec.submit(() -> awaitQuiet(gate)); // 占住 stateExecutor:异常清理任务将排队(pending)
            f.capture.listener.onTerminated(1L, "CAPTURE_READ_ERROR_-3"); // 错误终止
            f.runtime.pause();  // 退后台(取消重试链/取消会话)
            f.runtime.resume(); // 回前台:pending 屏障必须挡住 cap.start()
            Thread.sleep(800);  // 给 lifecycleExecutor 的 resume 任务留执行窗口
            assertEquals("异常清理未完成时 pause→resume 不得启动采音", 0, f.capture.startCount);

            gate.countDown(); // 放行清理 → 成功回调 → retryCapture(500ms) → 重启
            awaitStart(f.capture, 1, 5000);
        }
    }

    @Test
    public void cleanupSuccess_restartsCaptureExactlyOnce() throws Exception {
        try (Fixture f = new Fixture()) {
            f.runtime.resume(); // 前台
            f.capture.listener.onTerminated(1L, "CAPTURE_READ_ERROR_-3");
            awaitStart(f.capture, 1, 5000); // 清理成功回调 → 500ms 退避 → 重启(sid=2)
            Thread.sleep(1500); // 再等一个重试周期,确认不二次重启(captureStarted=true 屏障)
            assertEquals("清理成功后采音应恰好重启一次", 1, f.capture.startCount);
        }
    }

    @Test
    public void cleanupFails_noAutoRetry_andResumeBlocked() throws Exception {
        try (Fixture f = new Fixture()) {
            f.asr.failOnStop = true; // 清理路径 invalidateAndStopAsr → asrPort.stop() 抛 → 回调 success=false
            f.runtime.resume();
            f.capture.listener.onTerminated(1L, "CAPTURE_PIPELINE_ERROR");
            assertTrue("清理失败应上报恢复失败状态",
                    awaitStatus(f.statuses, "语音恢复失败", 3000));
            Thread.sleep(1500); // 超过首次重试延迟(500ms)
            assertEquals("清理失败后不得自动重试", 0, f.capture.startCount);
            f.runtime.resume(); // 前台重试:failed 屏障必须挡住
            Thread.sleep(800);
            assertEquals("清理失败后 resume 不得绕过屏障启动", 0, f.capture.startCount);
        }
    }

    @Test
    public void busyRetry_bounded_exhaustsAfterThreeAttempts() throws Exception {
        try (Fixture f = new Fixture()) {
            f.capture.busyStarts = 10; // 一直 busy
            f.runtime.resume(); // 前台
            f.capture.listener.onTerminated(1L, "STOPPED"); // 正常停止 → 立即排重试链
            assertTrue("busy 重试 3 次耗尽应报失败状态",
                    awaitStatus(f.statuses, "采音失败,请重进语音页面重试", 8000));
            assertEquals("退避重试应有界(3 次)", 3, f.capture.startCount);
        }
    }

    @Test
    public void busyRetry_cancelledByPause_noFurtherAttempts() throws Exception {
        try (Fixture f = new Fixture()) {
            f.capture.busyStarts = 10;
            f.runtime.resume();
            f.capture.listener.onTerminated(1L, "STOPPED"); // 排首次重试(+500ms)
            Thread.sleep(150);
            f.runtime.pause(); // 取消 captureRetryFuture + 后台屏障
            Thread.sleep(2000);
            assertEquals("pause 应取消 busy 重试链", 0, f.capture.startCount);
        }
    }

    // ---- harness ----

    /** 端到端夹具:真 Runtime(Application stub)+ 真 Controller(假端口)+ 假 capture。 */
    private static final class Fixture implements AutoCloseable {
        final VoiceRuntime runtime;
        final FakeWakePort wake = new FakeWakePort();
        final FakeAsrPort asr = new FakeAsrPort();
        final FakeTtsPort tts = new FakeTtsPort();
        final FakeCapture capture = new FakeCapture();
        final ExecutorService stateExec = Executors.newSingleThreadExecutor();
        /** 夹具自建的 Controller watchdog 调度器(close 时随夹具回收,不泄漏线程)。 */
        final ScheduledExecutorService controllerScheduler =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "runtime-test-timeout");
                    t.setDaemon(true);
                    return t;
                });
        final List<String> statuses = new CopyOnWriteArrayList<>();

        Fixture() {
            runtime = new VoiceRuntime(new Application() { }, dummyRepository(), null);
            VoiceSessionController controller = new VoiceSessionController(
                    wake, asr, tts, new FakeVadPort(), new FakeFocusPort(),
                    req -> new AgentOutcome("req", TaskState.SUCCEEDED, StopReason.DONE, new Trajectory(), 0L),
                    new ResponsePresenter(), VoicePolicyConfig.defaults(),
                    stateExec, Runnable::run, controllerScheduler, NoopVoiceMetrics.INSTANCE);
            runtime.publishForTest(controller, capture, statuses::add);
        }

        @Override public void close() {
            runtime.shutdown(); // 停 controller/capture(其收尾任务已入 stateExec 队列)
            // stateExec 传给了 Controller,runtime.shutdown 只关自己的 executor——由夹具回收:
            // graceful shutdown 排干队列(含 controller 收尾),超时强停
            stateExec.shutdown();
            try {
                if (!stateExec.awaitTermination(2, TimeUnit.SECONDS)) {
                    stateExec.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stateExec.shutdownNow();
            }
            controllerScheduler.shutdownNow();
        }
    }

    /** repository 仅满足构造非空校验,恢复路径不触达(engine/gateway 仅占位,runner 不被调用)。 */
    private static AgentRuntimeRepository dummyRepository() {
        return new AgentRuntimeRepository(gw -> null, null, null, null,
                req -> com.matrix.agent.core.agent.ModelTurn.directAnswer("done"),
                "runtime-recovery-test", null,
                new TaskScheduler(1, new SessionLockManager()), new MockVehicleStateSource(),
                CapabilityRegistry.createDemoRegistry(),
                KeywordIntentClassifier.INSTANCE, NoopAuditRepository.INSTANCE);
    }

    private static void awaitQuiet(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException ignored) { }
    }

    private static void awaitStart(FakeCapture capture, int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && capture.startCount < expected) {
            Thread.sleep(50);
        }
        assertTrue("等待 capture.start() 达 " + expected + " 次超时(实际 " + capture.startCount + ")",
                capture.startCount >= expected);
    }

    private static boolean awaitStatus(List<String> statuses, String prefix, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (String s : statuses) {
                if (s.startsWith(prefix)) return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    // ---- fakes(自包含,不与 VoiceSessionControllerTest 耦合) ----

    static final class FakeWakePort implements WakeWordPort {
        WakeWordPort.Listener listener;
        long epoch;
        private long epochSeq;
        @Override public void setListener(WakeWordPort.Listener listener) { this.listener = listener; }
        @Override public WakeStartResult start() { epoch = ++epochSeq; return new WakeStartResult(epoch, null); }
        @Override public void feed(byte[] pcm, int len) { }
        @Override public void stop() { epoch = ++epochSeq; }
    }

    static final class FakeAsrPort implements AsrPort {
        AsrPort.Listener listener;
        boolean failOnStop;
        long sid;
        @Override public void setListener(AsrPort.Listener listener) { this.listener = listener; }
        @Override public AsrStartResult start() { return new AsrStartResult(++sid, null); }
        @Override public void feed(byte[] pcm, int len) { }
        @Override public void stop() { if (failOnStop) throw new IllegalStateException("close failed"); }
        @Override public void finish() { }
    }

    static final class FakeTtsPort implements TtsPort {
        @Override public void setListener(TtsPort.Listener listener) {
            if (listener != null) listener.onReady();
        }
        @Override public void speak(com.matrix.agent.core.voice.SpeakableResponse response, String utteranceId) { }
        @Override public void stop() { }
    }

    static final class FakeVadPort implements VadPort {
        @Override public void setListener(VadPort.Listener listener) { }
        @Override public void feed(byte[] pcm, int len) { }
        @Override public void reset() { }
    }

    static final class FakeFocusPort implements AudioFocusPort {
        @Override public void setListener(AudioFocusPort.Listener listener) { }
        @Override public boolean request() { return true; }
        @Override public void release() { }
    }

    /** 假采音:start 计数、可控 busy、终终权经测试线程手动触发。 */
    static final class FakeCapture implements VoiceCapturePort {
        int startCount;
        int busyStarts; // >0 时前 N 次 start 返回 -1(模拟 AudioRecord 短暂占用)
        long nextSid = 2; // publishForTest 固定 captureSessionId=1,重试从 2 起
        volatile VoiceCapturePort.TerminationListener listener;
        @Override public long start() {
            startCount++;
            return startCount <= busyStarts ? -1 : nextSid++;
        }
        @Override public void stop() { }
        @Override public void setTerminationListener(VoiceCapturePort.TerminationListener l) { listener = l; }
    }
}
