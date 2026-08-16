package com.matrix.agent.voicecert;

import android.Manifest;
import android.content.Context;
import android.os.Build;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import com.matrix.agent.core.agent.AgentOutcome;
import com.matrix.agent.core.agent.StopReason;
import com.matrix.agent.core.agent.TaskState;
import com.matrix.agent.core.agent.Trajectory;
import com.matrix.agent.core.voice.AsrPort;
import com.matrix.agent.core.voice.AudioFocusPort;
import com.matrix.agent.core.voice.NoopVoiceMetrics;
import com.matrix.agent.core.voice.ResponsePresenter;
import com.matrix.agent.core.voice.TtsPort;
import com.matrix.agent.core.voice.VadPort;
import com.matrix.agent.core.voice.VoicePolicyConfig;
import com.matrix.agent.core.voice.WakeWordPort;
import com.matrix.agent.data.voice.VoiceSessionController;
import com.matrix.agent.platform.voice.VoiceCaptureController;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link VoiceCaptureController} 仪器测试(voicecert 门禁:需真机 + RECORD_AUDIO,见
 * {@link VoiceCertification}——常规 connected 任务跳过,connectedVoiceCertificationAndroidTest 放行)。
 *
 * <p>覆盖评审点名的关键 Android 路径:启停循环(sid 递增/资源可复用)、真实麦克风 PCM 到
 * KWS 端口的路由(IDLE 喂 wakePort)、运行时权限撤销 → start() fail-fast 抛 SecurityException
 * (不静默死)。Controller 用 no-op 假端口装配,验证采音层本身,不依赖 Vosk 模型。
 *
 * <p>VoiceRuntime 恢复路径(异常清理屏障/pause→resume/busy 重试)已由 JVM {@code VoiceRuntimeTest}
 * 经 VoiceCapturePort 假件端到端覆盖;真机全链路(模型装配+真并发)列入 M4 真机验收清单。
 */
@RunWith(AndroidJUnit4.class)
public class VoiceCaptureInstrumentedTest {
    @Rule
    public GrantPermissionRule recordAudio = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO);

    /** 计数 KWS 喂入字节的假 wake 端口(验证真实麦克风 → IDLE 路由)。 */
    private static final class CountingWakePort implements WakeWordPort {
        final AtomicInteger fedBytes = new AtomicInteger();
        long epochSeq;
        @Override public void setListener(WakeWordPort.Listener listener) { }
        @Override public WakeStartResult start() { return new WakeStartResult(++epochSeq, null); }
        @Override public void feed(byte[] pcm, int len) { fedBytes.addAndGet(len); }
        @Override public void stop() { epochSeq++; }
    }

    private static final class NoopAsrPort implements AsrPort {
        @Override public void setListener(AsrPort.Listener listener) { }
        @Override public AsrStartResult start() { return new AsrStartResult(1, null); }
        @Override public void feed(byte[] pcm, int len) { }
        @Override public void stop() { }
        @Override public void finish() { }
    }

    private static final class NoopTtsPort implements TtsPort {
        @Override public void setListener(TtsPort.Listener listener) { }
        @Override public void speak(com.matrix.agent.core.voice.SpeakableResponse response, String utteranceId) { }
        @Override public void stop() { }
    }

    private static final class NoopVadPort implements VadPort {
        @Override public void setListener(VadPort.Listener listener) { }
        @Override public void feed(byte[] pcm, int len) { }
        @Override public void reset() { }
    }

    private static final class NoopFocusPort implements AudioFocusPort {
        @Override public void setListener(AudioFocusPort.Listener listener) { }
        @Override public boolean request() { return true; }
        @Override public void release() { }
    }

    private VoiceCaptureController newCapture(CountingWakePort wake) {
        Context context = ApplicationProvider.getApplicationContext();
        Executor direct = Runnable::run;
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "test-voice-timeout");
                    t.setDaemon(true);
                    return t;
                });
        VoiceSessionController controller = new VoiceSessionController(
                wake, new NoopAsrPort(), new NoopTtsPort(), new NoopVadPort(), new NoopFocusPort(),
                req -> new AgentOutcome("req", TaskState.SUCCEEDED, StopReason.DONE, new Trajectory(), 0L),
                new ResponsePresenter(), VoicePolicyConfig.defaults(), direct, direct, scheduler,
                NoopVoiceMetrics.INSTANCE);
        controller.start();
        return new VoiceCaptureController(controller, wake, new NoopAsrPort(),
                VoicePolicyConfig.defaults(), context);
    }

    @Test
    public void startStopRestart_cycle_sidsIncrement_andFeedsWakeInIdle() throws Exception {
        Assume.assumeTrue(VoiceCertification.skipReason(), VoiceCertification.enabled());
        CountingWakePort wake = new CountingWakePort();
        VoiceCaptureController capture = newCapture(wake);
        long sid1 = capture.start();
        assertTrue("首次 start 应成功", sid1 >= 1);
        // IDLE 态真实麦克风 PCM 应路由到 wakePort(等待最多 2s,50ms 帧)
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && wake.fedBytes.get() == 0) {
            Thread.sleep(50);
        }
        assertTrue("IDLE 期间 KWS 端口应收到麦克风 PCM(fed=" + wake.fedBytes.get() + ")",
                wake.fedBytes.get() > 0);
        capture.stop();
        long sid2 = capture.start();
        assertTrue("stop 后再次 start 应成功且 sid 递增(会话隔离)", sid2 > sid1);
        capture.stop();
    }

    @Test
    public void permissionRevoked_startThrowsSecurityException() throws Exception {
        Assume.assumeTrue(VoiceCertification.skipReason(), VoiceCertification.enabled());
        Assume.assumeTrue("revokeRuntimePermission 需 API 29+", Build.VERSION.SDK_INT >= 29);
        CountingWakePort wake = new CountingWakePort();
        VoiceCaptureController capture = newCapture(wake);
        String pkg = ApplicationProvider.getApplicationContext().getPackageName();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .revokeRuntimePermission(pkg, Manifest.permission.RECORD_AUDIO);
        try {
            capture.start(); // 权限撤销后必须 fail-fast 抛出,不静默死/不返回 busy
            fail("权限已撤销,start() 应抛 SecurityException");
        } catch (SecurityException expected) {
            // 预期:权限类错误显式上抛(Runtime 据此提示用户,不盲目重试)
        } finally {
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .grantRuntimePermission(pkg, Manifest.permission.RECORD_AUDIO);
        }
    }
}
