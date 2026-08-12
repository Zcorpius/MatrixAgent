package com.matrix.agent.platform.voice;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.util.Log;

import com.matrix.agent.core.voice.AsrPort;
import com.matrix.agent.core.voice.VoiceSessionState;
import com.matrix.agent.core.voice.WakeWordPort;
import com.matrix.agent.data.voice.VoiceSessionController;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 音频采集控制器。Voice V1 Stage 4。
 *
 * <p>按 {@link VoiceSessionController#currentState()} 分发 PCM:IDLE→wakePort,LISTENING/BARGE_IN→asrPort,
 * SPEAKING→RMS 能量检测→onBargeIn,其它态不喂。
 *
 * <p><b>会话隔离 + 无锁并发</b>:每次 {@link #start()} 创建独立 {@link Session}(独占 AudioRecord/AEC/
 * 线程/running),{@link #current} 是 {@link AtomicReference}。并发控制全靠 CAS,无 synchronized:
 * <ul>
 *   <li>{@code start}:{@code compareAndSet(null, s)} 抢创建权——双 start(buildAndStart 后台线程 +
 *       onResume 主线程)只有一个赢,另一个 {@link Session#release()} 自己刚建的、直接返回。</li>
 *   <li>{@code stop} / {@code captureLoop finally}:{@code compareAndSet(s, null)} 清场——语义正是
 *       "只有当 current 还是我时才置 null",新会话不会被旧线程/旧 stop 误清。</li>
 * </ul>
 * 这样 stop 超时(清 current 让 start 可建新)后,旧线程 finally 释放的是**旧** session 的 AudioRecord,
 * CAS 看到已是新 session 则不清;旧线程用自己 session 的 running,新 start 设的是新 session 的 running,
 * 互不影响——彻底消除"stop 超时 + resume/start"竞态,且全程无锁、无死锁。
 *
 * <p>PCM 约定:16kHz、单声道、16bit LE。barge-in 阈值/hold 真机调。
 */
public final class VoiceCaptureController {
    private static final String TAG = "MatrixAgent";
    private static final int SAMPLE_RATE = 16_000;
    private static final int FRAME_BYTES = 1600; // 50ms @ 16kHz mono 16bit

    /** barge-in 触发的 RMS 阈值(16bit PCM 振幅均方根)。真机调。 */
    static final int BARGE_IN_RMS_THRESHOLD = 1500;
    /** RMS 超阈值需持续的毫秒数,避免瞬态噪声误触发。真机调。 */
    static final int BARGE_IN_HOLD_MS = 250;

    private final VoiceSessionController controller;
    private final WakeWordPort wakePort;
    private final AsrPort asrPort;

    /** 当前会话(CAS 更新:start null→s;stop/finally s→null)。 */
    private final AtomicReference<Session> current = new AtomicReference<>();

    public VoiceCaptureController(VoiceSessionController controller, WakeWordPort wakePort, AsrPort asrPort) {
        if (controller == null) throw new IllegalArgumentException("controller 不能为空");
        if (wakePort == null) throw new IllegalArgumentException("wakePort 不能为空");
        if (asrPort == null) throw new IllegalArgumentException("asrPort 不能为空");
        this.controller = controller;
        this.wakePort = wakePort;
        this.asrPort = asrPort;
    }

    /**
     * 开始采音。CAS 抢创建权:双 start 只有一个赢,另一个释放自己刚建的资源、返回。
     * 需先授予 RECORD_AUDIO。
     */
    public void start() {
        if (current.get() != null) return; // 快速路径:已有会话
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, FRAME_BYTES * 4);
        AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
        AcousticEchoCanceler aec = null;
        Session s = null;
        try {
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord 初始化失败(权限或设备问题)");
            }
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(ar.getAudioSessionId());
                if (aec != null) {
                    aec.setEnabled(true);
                    Log.i(TAG, "[Voice] 已启用 AEC(回声消除)");
                }
            }
            ar.startRecording();
            final Session session = new Session(ar, aec);
            s = session;
            Thread t = new Thread(() -> captureLoop(session), "voice-capture");
            session.thread = t;
            if (!current.compareAndSet(null, session)) {
                // 另一个 start 已占 current,释放我刚创建的,避免泄漏。
                session.release();
                return;
            }
            t.start();
            Log.i(TAG, "[Voice] 采音启动");
        } catch (RuntimeException e) {
            // 发布前异常时外层 cleanup 看不到 ar/aec,必须在这里回收。
            // 极端情况下 Thread.start() 失败,Session 已发布,先撤槽再走幂等 release。
            if (s != null) {
                current.compareAndSet(s, null);
                s.release();
            } else {
                releaseUnpublished(ar, aec);
            }
            throw e;
        }
    }

    /** 回收尚未包装为 Session 的启动期资源。 */
    private static void releaseUnpublished(AudioRecord ar, AcousticEchoCanceler aec) {
        if (aec != null) {
            try {
                aec.release();
            } catch (Exception e) {
                Log.w(TAG, "[Voice] AEC.release: " + e.getMessage());
            }
        }
        try {
            ar.release();
        } catch (Exception e) {
            Log.w(TAG, "[Voice] AudioRecord.release: " + e.getMessage());
        }
    }

    private void captureLoop(Session s) {
        try {
            byte[] pcm = new byte[FRAME_BYTES];
            long loudSince = 0;
            while (s.running) {
                int n = s.audioRecord.read(pcm, 0, pcm.length);
                if (n <= 0) continue;
                VoiceSessionState.State state = controller.currentState();
                switch (state) {
                    case IDLE:
                        wakePort.feed(copyExact(pcm, n));
                        break;
                    case LISTENING:
                    case BARGE_IN_LISTENING:
                        asrPort.feed(copyExact(pcm, n));
                        break;
                    case SPEAKING:
                        if (rms(pcm, n) > BARGE_IN_RMS_THRESHOLD) {
                            if (loudSince == 0) {
                                loudSince = System.currentTimeMillis();
                            } else if (System.currentTimeMillis() - loudSince >= BARGE_IN_HOLD_MS) {
                                loudSince = 0;
                                Log.i(TAG, "[Voice] 检测到 barge-in(能量)");
                                controller.onBargeIn();
                            }
                        } else {
                            loudSince = 0;
                        }
                        break;
                    default:
                        // WAKE_ACCEPTED / ENDPOINTING / RECOGNIZING / THINKING / CONFIRMING / CANCELLED / ERROR_ANNOUNCING
                        break;
                }
            }
        } finally {
            // 只释放本会话自己的资源;CAS 清 current(仅在还是自己时,不误清新会话)
            s.release();
            current.compareAndSet(s, null);
            Log.i(TAG, "[Voice] 采音线程退出");
        }
    }

    /**
     * 停止采音。释放安全:线程已退出则 release;超时未退出则 CAS 清 current(让 start 可建新),
     * 交由 {@link #captureLoop} 的 finally 在线程真正退出时释放**本会话**资源。全程 CAS,无锁。
     */
    public void stop() {
        Session s = current.get();
        if (s == null) return;
        s.running = false;
        try {
            if (s.audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                s.audioRecord.stop(); // 促阻塞的 read() 返回
            }
        } catch (Exception e) {
            Log.w(TAG, "[Voice] AudioRecord.stop: " + e.getMessage());
        }
        Thread t = s.thread;
        if (t != null) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) {
                // 线程未退出:它的 finally 会 s.release()(自己的资源,安全);CAS 清 current 让 start 可建新
                current.compareAndSet(s, null);
                Log.w(TAG, "[Voice] 采音线程未及时退出,延迟由线程 finally 释放");
                return;
            }
        }
        s.release(); // 线程已退出,stop 释放(幂等)
        current.compareAndSet(s, null);
        Log.i(TAG, "[Voice] 采音停止");
    }

    /** 单次采音会话:独占 AudioRecord/AEC/线程/running。captureLoop 持本对象,finally 只释放自己。 */
    private static final class Session {
        final AudioRecord audioRecord;
        final AcousticEchoCanceler aec;
        volatile Thread thread;
        volatile boolean running = true;
        private final AtomicBoolean released = new AtomicBoolean();

        Session(AudioRecord audioRecord, AcousticEchoCanceler aec) {
            this.audioRecord = audioRecord;
            this.aec = aec;
        }

        /** 幂等释放(AtomicBoolean CAS)。captureLoop finally 或 stop() 调用,只执行一次。 */
        void release() {
            if (!released.compareAndSet(false, true)) return;
            try {
                audioRecord.release();
            } catch (Exception e) {
                Log.w(TAG, "[Voice] AudioRecord.release: " + e.getMessage());
            }
            if (aec != null) {
                try {
                    aec.release();
                } catch (Exception e) {
                    Log.w(TAG, "[Voice] AEC.release: " + e.getMessage());
                }
            }
        }
    }

    /** 复制 pcm 前 len 字节,避免短帧(read 返回 n<FRAME_BYTES)尾部残留送入 Vosk。 */
    private static byte[] copyExact(byte[] src, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, 0, out, 0, len);
        return out;
    }

    /** 16bit PCM 的 RMS 振幅均方根。 */
    private static double rms(byte[] pcm, int len) {
        long sum = 0;
        int samples = len / 2;
        for (int i = 0; i + 1 < len; i += 2) {
            short sh = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            sum += (long) sh * sh;
        }
        return samples > 0 ? Math.sqrt((double) sum / samples) : 0;
    }
}
