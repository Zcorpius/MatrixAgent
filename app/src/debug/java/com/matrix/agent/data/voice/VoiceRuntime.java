package com.matrix.agent.data.voice;

import android.app.Application;
import android.util.Log;

import com.matrix.agent.core.voice.AgentRunner;
import com.matrix.agent.core.voice.ModelInstallLock;
import com.matrix.agent.core.voice.RetryPolicy;
import com.matrix.agent.core.voice.NoopVoiceMetrics;
import com.matrix.agent.core.voice.ResponsePresenter;
import com.matrix.agent.core.voice.VoiceCapturePort;
import com.matrix.agent.core.voice.VoicePolicyConfig;
import com.matrix.agent.core.voice.VoiceSessionListener;
import com.matrix.agent.data.AgentRuntimeRepository;
import com.matrix.agent.data.db.ModelDownloadDao;
import com.matrix.agent.platform.voice.AndroidAudioFocusAdapter;
import com.matrix.agent.platform.voice.AndroidTtsAdapter;
import com.matrix.agent.platform.voice.VoiceCaptureController;
import com.matrix.agent.platform.voice.VoskAsrAdapter;
import com.matrix.agent.platform.voice.VoskAsrEngine;
import com.matrix.agent.platform.voice.VoskEndpointAdapter;
import com.matrix.agent.platform.voice.VoskModelHolder;
import com.matrix.agent.platform.voice.VoskWakeAdapter;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BooleanSupplier;

/**
 * 语音运行时。
 *
 * <p>统一持有全部 voice 资源(模型 holder / 各端口 / Controller / Capture / 3+1 executor / presenter /
 * downloader),从 {@code VoiceDebugViewModel} 收敛而来。ViewModel 降为 observer + 生命周期触发,
 * 系统级语音入口(阶段 3 OEM/System Wake)可直接持有本对象,不依赖 Fragment。
 *
 * <p><b>生命周期锁</b>:{@link #lifecycleLock} 保护"装配+启动"(buildAndStart)与"销毁"(shutdown)
 * 互斥。模型加载在锁外(耗时不持锁);加载完后在锁内检查 cleared、发布字段、start。
 *
 * <p><b>native 生命周期</b>:Vosk Recognizer 必须先于 Model close。{@link #shutdown} 用
 * {@link VoiceSessionController#shutdown(Runnable)}:shutdown 内串行 stop asr/wake/tts(close Recognizer),
 * afterStopped 在 Recognizer 关闭后关 tts 引擎 + holder(Model)+ stateExecutor。
 *
 * <p><b>下载取消</b>:每次 {@link #start} 增 generation,download 注入 {@code () -> cleared || gen!=generation};
 * 旧任务/销毁后任务即取消,保留 .tmp.zip 断点。
 */
public final class VoiceRuntime {

    /** 进度/状态文案回调(下载/装配/就绪/错误)。 */
    public interface StatusListener {
        void onStatus(String status);
    }

    private static final String TAG = "MatrixAgent";

    private final Application app;
    private final AgentRuntimeRepository repository;
    private final ModelDownloadDao dao;

    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService stateExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService agentExecutor = Executors.newSingleThreadExecutor();
    /** 会话超时 watchdog(仿 AuditEventRecorder 的 daemon ScheduledExecutorService)。 */
    private final ScheduledExecutorService timeoutScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "voice-timeout");
                t.setDaemon(true);
                return t;
            });
    /** pause/resume 的 capture.start/stop 串行执行器(单线程 FIFO);主线程只设意图标志 + 提交,
     *  cap.stop 的 join 在此线程,不阻塞主线程(onPause/onResume)。 */
    private final ExecutorService lifecycleExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "voice-lifecycle");
                t.setDaemon(true);
                return t;
            });
    private final ResponsePresenter presenter = new ResponsePresenter();
    private final VoskModelDownloader downloader;
    private final Object lifecycleLock = new Object();

    private volatile boolean cleared;
    private volatile boolean built;
    /** start 进行中(下载/装配),防 Fragment 重建重复 start 递增 generation / 卡"装配中"。 */
    private volatile boolean startInFlight;
    /** 前台活跃标志。退后台 false → buildAndStart 不装配 + pause 取消会话。默认 false,等 onResume 设 true。 */
    private volatile boolean foregroundActive = false;
    /** 模型已就绪但装配时退后台 → 待回前台 resume 重调 buildAndStart。 */
    private volatile boolean pendingBuild;
    /** capture 是否已 start(装配时退后台则不 start,resume 回前台补)。 */
    private volatile boolean captureStarted;
    /** 当前 capture 会话的 sid(= capture.start 返回值),onCaptureTerminated 校验防旧会话。 */
    private volatile long captureSessionId;
    /** capture 重试延迟任务句柄;成功 start/pause/shutdown 取消,防旧重试链误判 -1 为失败。 */
    private volatile ScheduledFuture<?> captureRetryFuture;
    /** 异常采音死亡时间窗口熔断:30s 内 >MAX_CAPTURE_FAILURE 次停止重试(防 DEAD_OBJECT 等无限循环)。 */
    private volatile int captureFailureBudget;
    private volatile long captureFirstFailureMs;
    private static final int MAX_CAPTURE_FAILURE = 3;
    /** 异常清理进行中屏障:提交采音异常清理前置 true——resume/busy 重试在清理完成前不得启动新采音(P2)。 */
    private volatile boolean captureRecoveryPending;
    /** 异常清理失败(端口 stop/KWS 重建抛异常):端口状态未知,停自动恢复,等用户重进页面重建。 */
    private volatile boolean captureRecoveryFailed;
    /** start 传入的 stateListener,供 resume 重调 buildAndStart 复用。 */
    private volatile VoiceSessionListener pendingStateListener;
    private volatile long generation;

    private volatile VoskModelHolder holder;
    private volatile VoskWakeAdapter wakePort;
    private volatile VoskAsrAdapter asrPort;
    private volatile VoiceSessionController controller;
    /** 依赖接口(测试注入假件驱动恢复路径;生产为 VoiceCaptureController)。 */
    private volatile VoiceCapturePort capture;
    private volatile AndroidTtsAdapter tts;

    private volatile StatusListener statusListener;

    public VoiceRuntime(Application app, AgentRuntimeRepository repository, ModelDownloadDao dao) {
        if (app == null) throw new IllegalArgumentException("app 不能为空");
        if (repository == null) throw new IllegalArgumentException("repository 不能为空");
        this.app = app;
        this.repository = repository;
        this.dao = dao; // 可空:数据库降级时进度降级
        this.downloader = new VoskModelDownloader(dao);
    }

    /**
     * 开始:下载模型 + 装配 + 启动。{@code stateListener} 收状态/partial,{@code statusListener} 收进度文案。
     * 内部 generation/cleared 控制(cleared 后或新 start 作废旧下载)。
     */
    public void start(VoiceSessionListener stateListener, StatusListener statusListener) {
        synchronized (lifecycleLock) {
            this.statusListener = statusListener;
            this.pendingStateListener = stateListener; // resume 重调 buildAndStart 时复用
            if (cleared) return;
            if (built) {
                // 已就绪:更新 listener(替换观察者),发布就绪(不重复装配)。
                if (controller != null) controller.setUiListener(stateListener);
                postStatus("就绪:说 \"hey matrix\" 或按\"开始\"");
                return;
            }
            if (startInFlight) {
                // 下载/装配中:只更新 listener,不重复提交(防递增 generation 让旧任务报"已取消")。
                return;
            }
            startInFlight = true;
        }
        File voskRoot = new File(app.getFilesDir(), "vosk-model");
        final VoskModelSpec en = VoskModelSpec.en(voskRoot);
        final VoskModelSpec cn = VoskModelSpec.cn(voskRoot);
        final long gen = ++generation; // 首次启动才递增(startInFlight 保证不重复)
        final BooleanSupplier cancelled = () -> cleared || gen != generation;
        downloadExecutor.submit(() -> {
            if (cleared) return;
            try {
                // migrateLegacy 已在 download 统一锁内执行(P1-5b),此处不再单独调。
                if (!downloader.isDownloaded(en)) {
                    postStatus("下载英文唤醒模型(~40MB)…");
                    downloader.download(en, cancelled);
                }
                if (cleared) return;
                if (!downloader.isDownloaded(cn)) {
                    postStatus("下载中文识别模型(~42MB)…");
                    downloader.download(cn, cancelled);
                }
                if (cleared) return;
                postStatus("模型就绪,装配中…");
                buildAndStart();
            } catch (ModelInstallLock.LockBusyException e) {
                // P1-1: 锁竞争→退避重试 download(自己接管缺失模型,不要求另一实例下完两个)
                if (gen != generation || cleared) return;
                Log.i(TAG, "[Voice] 模型正在被另一实例安装,退避重试: " + e.getMessage());
                postStatus("模型正在安装,等待后重试…");
                retryDownloadWithBackoff(en, cn, gen, cancelled);
            } catch (Exception e) {
                if (gen != generation || cleared) return; // 旧 generation 取消(理论 startInFlight 已防),静默不报失败
                Log.e(TAG, "[Voice] 启动失败: " + e.getClass().getSimpleName(), e);
                // P3: UI 只输出稳定文案+异常类型,原始 message 可能含模型绝对路径
                postStatus("启动失败(" + e.getClass().getSimpleName() + "),请重试");
            } finally {
                synchronized (lifecycleLock) { startInFlight = false; }
            }
        });
    }

    /** P2-1/P2-2: LockBusy 后 60s 有界退避重试 download(接管缺失模型);经 RetryPolicy(可注入 clock/sleeper 测试)。 */
    private void retryDownloadWithBackoff(VoskModelSpec en, VoskModelSpec cn, long gen, BooleanSupplier cancelled) {
        boolean ok;
        try {
            ok = RetryPolicy.retry(System::nanoTime, Thread::sleep,
                    System.nanoTime() + 60_000_000_000L, new long[]{500, 1000, 2000, 2000},
                    () -> gen != generation || cleared || cancelled.getAsBoolean(),
                    () -> {
                        try {
                            if (!downloader.isDownloaded(en)) downloader.download(en, cancelled);
                            if (gen != generation || cleared) throw new RetryPolicy.RetryableFailure();
                            if (!downloader.isDownloaded(cn)) downloader.download(cn, cancelled);
                            if (!(downloader.isDownloaded(en) && downloader.isDownloaded(cn))) {
                                throw new RetryPolicy.RetryableFailure(); // 仍未完成,继续退避
                            }
                        } catch (ModelInstallLock.LockBusyException be) {
                            throw new RetryPolicy.RetryableFailure(); // 锁竞争,继续退避
                        }
                    });
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        } catch (java.io.IOException e) {
            // P3: UI 只输出异常类型,原始 message 可能含模型绝对路径
            if (gen == generation && !cleared) postStatus("启动失败(" + e.getClass().getSimpleName() + "),请重试");
            return;
        }
        if (ok && gen == generation && !cleared) {
            postStatus("模型就绪,装配中…");
            buildAndStart();
        } else if (gen == generation && !cleared) {
            postStatus("模型安装重试超时,请重试");
        }
    }

    private void buildAndStart() {
        VoiceSessionListener stateListener = this.pendingStateListener; // 读最新(替换观察者生效)
        if (cleared || built) return;
        if (!foregroundActive) { // 退后台不装配(防下载完成后台启动麦)
            pendingBuild = true; // 记待装配,resume 回前台时重调本方法
            Log.i(TAG, "[Voice] 退后台,暂停装配,回前台重试");
            postStatus("语音暂停(退后台),回页面重试");
            return;
        }
        pendingBuild = false;
        // 模型加载在锁外(耗时不持锁);装配后到锁内发布+启动
        VoskModelHolder holder = null;
        VoskAsrEngine engine = null;
        VoskAsrAdapter asr = null;
        VoskWakeAdapter wake = null;
        AndroidTtsAdapter tts = null;
        VoiceSessionController controller = null;
        VoiceCaptureController capture = null;
        try {
            holder = new VoskModelHolder(new File(app.getFilesDir(), "vosk-model"));
            engine = new VoskAsrEngine(holder.cnModel());
            asr = new VoskAsrAdapter(engine);
            VoskEndpointAdapter endpoint = new VoskEndpointAdapter(engine);
            wake = new VoskWakeAdapter(holder.enModel());
            tts = new AndroidTtsAdapter(app);
            // 语音请求经 execute(VoiceAgentRequest) 贯通 VOICE 元数据。
            AgentRunner runner = req -> repository.execute(req);
            // VoskEndpointAdapter 作 vadPort;AndroidAudioFocusAdapter 作 focusPort。
            AndroidAudioFocusAdapter focusPort = new AndroidAudioFocusAdapter(app);
            controller = new VoiceSessionController(
                    wake, asr, tts, endpoint, focusPort, runner, presenter,
                    VoicePolicyConfig.defaults(), stateExecutor, agentExecutor, timeoutScheduler,
                    NoopVoiceMetrics.INSTANCE);
            controller.setUiListener(stateListener);
            capture = new VoiceCaptureController(controller, wake, asr, VoicePolicyConfig.defaults(), app);
            capture.setTerminationListener(this::onCaptureTerminated); // #5:采音错误退出通知
        } catch (Exception e) {
            Log.e(TAG, "[Voice] 装配失败: " + e.getClass().getSimpleName(), e);
            // P3: UI 只输出异常类型(Vosk/文件系统异常 message 可能含模型绝对路径)
            postStatus("装配失败(" + e.getClass().getSimpleName() + "),请重试");
            cleanupLocal(capture, wake, asr, tts, holder);
            return;
        }
        synchronized (lifecycleLock) {
            if (cleared || built) {
                cleanupLocal(capture, wake, asr, tts, holder);
                return;
            }
            this.holder = holder;
            this.wakePort = wake;
            this.asrPort = asr;
            this.controller = controller;
            this.capture = capture;
            this.tts = tts;
            built = true;
            try {
                controller.start();
                // 锁内 capture.start 前复查 foregroundActive——模型加载在锁外,
                // 加载期间可能已 pause,此时不能后台启动麦;built=true 但 captureStarted=false,
                // 由 resume 在回前台时补 capture.start()。
                if (foregroundActive) {
                    long sid = capture.start();
                    if (sid >= 0) {
                        captureStarted = true;
                        captureSessionId = sid;
                    } else {
                        captureStarted = false;
                        // P2: busy(STOPPING 残留/AudioRecord 短暂占用)有界退避重试,
                        // 不再等下一次生命周期事件,页面保持前台也能恢复
                        Log.w(TAG, "[Voice] capture.start 返回 busy,安排退避重试");
                        retryCapture(0);
                    }
                } else {
                    captureStarted = false;
                    Log.i(TAG, "[Voice] 装配完成但已退后台,采音待回前台启动");
                }
            } catch (SecurityException e) {
                // P2: 权限类失败与初始化失败区分——提示用户授予后重进,不盲目重试
                Log.e(TAG, "[Voice] 麦克风权限缺失,无法启动采音", e);
                rollbackPublish(controller, capture, wake, asr, tts, holder);
                postStatus("缺少麦克风权限,请在系统设置授予后重进语音页");
                return;
            } catch (RuntimeException e) {
                // start 抛(AudioRecord 初始化/设备):回滚 publish,避免 capture 死、controller 活的卡死态
                Log.e(TAG, "[Voice] 启动失败,回滚: " + e.getClass().getSimpleName(), e);
                rollbackPublish(controller, capture, wake, asr, tts, holder);
                postStatus("启动采音失败(" + e.getClass().getSimpleName() + "),请重试");
                return;
            }
        }
        postStatus("就绪:说 \"hey matrix\" 或按\"开始\"");
    }

    /**
     * 测试注入(JVM 驱动恢复路径,绕过 buildAndStart 的 Vosk/Android 装配):等价 buildAndStart
     * 锁内发布段——发布 controller/capture、置 built/captureStarted、注册终止监听、controller.start()。
     * captureSessionId 固定 1(对齐假 capture 首个 sid)。仅同包测试使用。
     */
    void publishForTest(VoiceSessionController controller, VoiceCapturePort capture,
            StatusListener statusListener) {
        synchronized (lifecycleLock) {
            this.controller = controller;
            this.capture = capture;
            this.statusListener = statusListener;
            this.built = true;
            this.captureStarted = true;
            this.captureSessionId = 1L;
            controller.start();
            capture.setTerminationListener(this::onCaptureTerminated);
        }
    }

    public void manualWake() {
        VoiceSessionController c = controller;
        if (c != null) c.manualWake();
    }

    public void cancel() {
        VoiceSessionController c = controller;
        if (c != null) c.onCancel();
    }

    /** 退后台:停采音释放麦克风。foregroundActive 写与 buildAndStart 互斥(同一 lifecycleLock)。 */
    public void pause() {
        synchronized (lifecycleLock) {
            foregroundActive = false; // 主线程立即标记非前台(buildAndStart 据此不后台开麦)
            captureStarted = false;
            cancelFuture(captureRetryFuture);
            captureRetryFuture = null;
        }
        VoiceSessionController c = controller;
        if (c != null) c.onCancel(); // 取消 THINKING/SPEAKING(锁外,不持 lifecycleLock)
        VoiceCapturePort cap = capture;
        if (cap != null) {
            // cap.stop 内部 join 采音线程(≤1s);投 lifecycle executor 串行执行,不阻塞主线程(onPause)。
            // 与 resume 的 start 同一 executor,单线程 FIFO 保证 pause→resume 时 stop 先于 start。
            lifecycleExecutor.execute(() -> {
                try { cap.stop(); } catch (Exception e) { Log.w(TAG, "[Voice] pause: " + e.getMessage()); }
            });
        }
    }

    /** 回前台:恢复采音。 */
    public void resume() {
        synchronized (lifecycleLock) {
            foregroundActive = true; // 主线程立即标记前台
            if (cleared) return;
            if (!built && pendingBuild) {
                // 模型已就绪但装配时退后台,回前台重调装配。
                Log.i(TAG, "[Voice] 回前台,重试装配");
                downloadExecutor.submit(() -> buildAndStart());
                return;
            }
            if (built && !captureStarted && capture != null) {
                final VoiceCapturePort cap = capture;
                // capture.start 投 lifecycle executor:与 pause 的 stop 同一单线程 FIFO,
                // 保证 pause→resume 的 stop 先于 start;主线程不持锁做 AudioRecord 构造。
                // executor 内重获锁复查(pause/shutdown 抢先时 foregroundActive/cleared/capture 已变 → 不开麦)。
                lifecycleExecutor.execute(() -> {
                    synchronized (lifecycleLock) {
                        if (capture != cap || !captureStartAllowed(cleared, foregroundActive,
                                captureStarted, captureRecoveryPending, captureRecoveryFailed)) {
                            return; // 含异常清理未完成/失败的恢复屏障(P2)
                        }
                        try {
                            long sid = cap.start();
                            if (sid >= 0) {
                                captureStarted = true;
                                captureSessionId = sid;
                                cancelFuture(captureRetryFuture);
                                captureRetryFuture = null;
                            } else {
                                // P2: busy(STOPPING 残留/AudioRecord 短暂占用)不再只打日志等生命周期——
                                // 有界退避重试,页面保持前台也能恢复采音
                                Log.w(TAG, "[Voice] resume capture.start busy,安排退避重试");
                                retryCapture(0);
                            }
                        } catch (Exception e) {
                            postStatus("恢复采音失败(" + e.getClass().getSimpleName() + "),请重进语音页");
                        }
                    }
                });
            }
        }
    }

    /**
     * 采音启动屏障(单一判定,resume/busy 重试/清理回调共用):cleared、后台、已有采音、
     * 异常清理未完成(pending)或已失败(failed)时一律不得启动新采音——防止 pause→resume 或
     * busy 重试绕过恢复流程,在端口清理完成前向旧(可能已损坏)端口喂音频(P2)。独立静态方法供 JVM 测试。
     */
    static boolean captureStartAllowed(boolean cleared, boolean foregroundActive, boolean captureStarted,
            boolean recoveryPending, boolean recoveryFailed) {
        return !cleared && foregroundActive && !captureStarted && !recoveryPending && !recoveryFailed;
    }

    /** #5:采音线程退出(错误或正常 stop)→ 进 lifecycleLock 与 pause/resume 串行,sid 校验防旧会话。
     * P1:采音线程不直连 Controller——错误经 sid 校验通过后才转交,防迟到错误影响 pause→resume 恢复后的新会话。
     * P2:异常路径提交清理前置 captureRecoveryPending=true(resume/重试被屏障);重试只挂清理成功回调,
     * 回调内经 captureStartAllowed 复查;清理失败(failed)取消自动恢复,提示重进页面。 */
    private void onCaptureTerminated(long sid, String reason) {
        synchronized (lifecycleLock) {
            Log.w(TAG, "[Voice] 采音终止: sid=" + sid + " reason=" + reason);
            if (sid != captureSessionId) return; // 旧会话回调,先校验再改状态
            captureStarted = false;
            if ("STOPPED".equals(reason)) {
                if (!captureStartAllowed(cleared, foregroundActive, captureStarted,
                        captureRecoveryPending, captureRecoveryFailed)) return;
                captureFailureBudget = 0; // 正常 stop(pause/超时),前台恢复不算异常死亡
                retryCapture(0);
                return;
            }
            // 异常 termination(精确 CAPTURE_* 码):sid 校验通过才转交 Controller 收敛状态。
            // 故障预算先算(熔断语义不变);清理统一带回调(熔断/后台路径也需清 pending 屏障)。
            VoiceSessionController c = controller;
            if (c == null) return;
            long now = System.currentTimeMillis();
            if (now - captureFirstFailureMs > 30_000) {
                captureFailureBudget = 0; // 窗口过期,重新计数
                captureFirstFailureMs = now;
            }
            final boolean exhausted = ++captureFailureBudget > MAX_CAPTURE_FAILURE;
            if (exhausted) {
                Log.e(TAG, "[Voice] 采音异常连续死亡熔断(30s 内 >" + MAX_CAPTURE_FAILURE + " 次),停止重试");
                postStatus("采音持续失败,请重进语音页面");
            }
            captureRecoveryPending = true;
            c.onCaptureError(reason, success -> {
                synchronized (lifecycleLock) {
                    captureRecoveryPending = false;
                    if (!success) {
                        // P2:清理路径抛异常,端口状态未知——取消自动恢复,等用户重进页面重建
                        captureRecoveryFailed = true;
                        Log.e(TAG, "[Voice] 采音清理失败,停止自动恢复");
                        postStatus("语音恢复失败,请重进语音页面");
                        return;
                    }
                    // 清理完成后再校验:期间可能已 pause/shutdown、被 resume 恢复或熔断,均不再重启
                    if (exhausted || captureSessionId != sid
                            || !captureStartAllowed(cleared, foregroundActive, captureStarted,
                                    captureRecoveryPending, captureRecoveryFailed)) {
                        return;
                    }
                    retryCapture(0);
                }
            });
        }
    }

    private void retryCapture(int attempt) {
        if (attempt >= 3) {
            Log.e(TAG, "[Voice] 采音重试耗尽,标记失败");
            postStatus("采音失败,请重进语音页面重试");
            return;
        }
        // 屏障:已恢复(resume)/后台/销毁/异常清理未完成或失败,静默不排(后台不再误报"采音失败")
        if (!captureStartAllowed(cleared, foregroundActive, captureStarted,
                captureRecoveryPending, captureRecoveryFailed)) return;
        final int next = attempt + 1;
        try {
            // 退避:每次延迟递增,避免同毫秒 3 次一起失败(设备短暂 busy/DEAD_OBJECT 恢复期)。
            captureRetryFuture = timeoutScheduler.schedule(() -> {
                synchronized (lifecycleLock) { doRetryCapture(next); }
            }, 500L * (attempt + 1), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            if (!cleared) Log.w(TAG, "[Voice] 采音重试 scheduler 拒绝");
        }
    }

    private void doRetryCapture(int attempt) {
        VoiceCapturePort c = capture;
        if (c == null) return;
        if (!captureStartAllowed(cleared, foregroundActive, captureStarted,
                captureRecoveryPending, captureRecoveryFailed)) return; // 已恢复(如 resume)/恢复屏障
        try {
            long sid = c.start();
            if (sid >= 0) {
                captureStarted = true;
                captureSessionId = sid;
                cancelFuture(captureRetryFuture);
                captureRetryFuture = null;
                Log.i(TAG, "[Voice] 采音重试成功(第 " + attempt + " 次,sid=" + sid + ")");
            } else {
                retryCapture(attempt);
            }
        } catch (Exception e) {
            Log.w(TAG, "[Voice] 采音重试异常: " + e.getClass().getSimpleName());
            retryCapture(attempt);
        }
    }

    /**
     * 销毁:回收所有 voice 资源(顺序:capture.stop → controller.shutdown → afterStopped
     * tts.shutdown + holder.close + stateExecutor.shutdown → agent/download/timeout shutdownNow)。
     */
    public void shutdown() {
        VoiceCapturePort cap;
        VoiceSessionController vc;
        AndroidTtsAdapter t;
        VoskModelHolder h;
        synchronized (lifecycleLock) {
            cleared = true;
            cancelFuture(captureRetryFuture);
            captureRetryFuture = null;
            cap = capture;
            vc = controller;
            t = tts;
            h = holder;
        }
        // capture 先停(停止 feed);cap.stop 内部 join 采音线程(≤1s),投 lifecycleExecutor 异步执行,
        // 不阻塞主线程(onCleared)——与 pause/resume 对齐(P2-2)。末尾 lifecycleExecutor.shutdown()
        // 拒绝新任务,已提交的 cap.stop 继续异步完成。
        if (cap != null) {
            lifecycleExecutor.execute(() -> {
                try {
                    cap.stop();
                } catch (Exception e) {
                    Log.w(TAG, "[Voice] shutdown cap: " + e.getMessage());
                }
            });
        }
        if (vc != null) {
            // shutdown 内串行 stop asr/wake/tts(close Recognizer)+ cancel token;
            // afterStopped 在 Recognizer 关闭后关 tts 引擎 + Model(顺序保证)+ stateExecutor。
            final AndroidTtsAdapter ttsRef = t;
            final VoskModelHolder holderRef = h;
            vc.shutdown(() -> {
                if (ttsRef != null) {
                    try {
                        ttsRef.shutdown();
                    } catch (Exception e) {
                        Log.w(TAG, "[Voice] shutdown tts: " + e.getMessage());
                    }
                }
                if (holderRef != null) {
                    try {
                        holderRef.close();
                    } catch (Exception e) {
                        Log.w(TAG, "[Voice] shutdown holder: " + e.getMessage());
                    }
                }
                stateExecutor.shutdown();
            });
        } else {
            if (t != null) {
                try {
                    t.shutdown();
                } catch (Exception e) {
                    Log.w(TAG, "[Voice] shutdown tts: " + e.getMessage());
                }
            }
            if (h != null) {
                try {
                    h.close();
                } catch (Exception e) {
                    Log.w(TAG, "[Voice] shutdown holder: " + e.getMessage());
                }
            }
            stateExecutor.shutdown();
        }
        agentExecutor.shutdownNow();
        downloadExecutor.shutdownNow();
        timeoutScheduler.shutdownNow();
        lifecycleExecutor.shutdown(); // 等 pending pause/resume 任务完成(它们复查 cleared 后 no-op)
    }

    private void postStatus(String status) {
        StatusListener l = statusListener;
        if (l != null) l.onStatus(status);
    }

    private static void cancelFuture(ScheduledFuture<?> f) {
        if (f != null) f.cancel(false);
    }

    /** 回滚已发布的装配字段并停已 start 的端口(启动采音失败/权限缺失),防 capture 死、controller 活的卡死态。 */
    private void rollbackPublish(VoiceSessionController controller, VoiceCapturePort capture,
            VoskWakeAdapter wake, VoskAsrAdapter asr, AndroidTtsAdapter tts, VoskModelHolder holder) {
        this.holder = null;
        this.wakePort = null;
        this.asrPort = null;
        this.controller = null;
        this.capture = null;
        this.tts = null;
        built = false;
        captureStarted = false;
        captureRecoveryPending = false;
        captureRecoveryFailed = false;
        try {
            controller.shutdown(null); // 停已 start 的端口
        } catch (Exception ignored) {
        }
        cleanupLocal(capture, wake, asr, tts, holder);
    }

    /** 清理未发布的局部装配资源(Recognizers 先于 Model close)。不调 controller(未 start,无 native)。 */
    private static void cleanupLocal(VoiceCapturePort capture, VoskWakeAdapter wake,
            VoskAsrAdapter asr, AndroidTtsAdapter tts, VoskModelHolder holder) {
        if (capture != null) {
            try { capture.stop(); } catch (Exception e) { Log.w(TAG, "[Voice] cleanup cap: " + e.getMessage()); }
        }
        if (wake != null) {
            try { wake.stop(); } catch (Exception e) { Log.w(TAG, "[Voice] cleanup wake: " + e.getMessage()); }
        }
        if (asr != null) {
            try { asr.stop(); } catch (Exception e) { Log.w(TAG, "[Voice] cleanup asr: " + e.getMessage()); }
        }
        if (tts != null) {
            try { tts.shutdown(); } catch (Exception e) { Log.w(TAG, "[Voice] cleanup tts: " + e.getMessage()); }
        }
        if (holder != null) {
            try { holder.close(); } catch (Exception e) { Log.w(TAG, "[Voice] cleanup holder: " + e.getMessage()); }
        }
    }
}
