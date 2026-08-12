package com.matrix.agent.presentation.viewmodel;

import android.app.Application;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.matrix.agent.core.identity.Actor;
import com.matrix.agent.data.AgentRuntimeRepository;
import com.matrix.agent.data.db.ModelDownloadDao;
import com.matrix.agent.data.voice.VoiceSessionController;
import com.matrix.agent.data.voice.VoskModelDownloader;
import com.matrix.agent.data.voice.VoskModelSpec;
import com.matrix.agent.core.voice.AgentRunner;
import com.matrix.agent.core.voice.ResponsePresenter;
import com.matrix.agent.core.voice.VoiceSessionListener;
import com.matrix.agent.core.voice.VoiceSessionState;
import com.matrix.agent.platform.voice.AndroidTtsAdapter;
import com.matrix.agent.platform.voice.VoiceCaptureController;
import com.matrix.agent.platform.voice.VoskAsrAdapter;
import com.matrix.agent.platform.voice.VoskAsrEngine;
import com.matrix.agent.platform.voice.VoskEndpointAdapter;
import com.matrix.agent.platform.voice.VoskModelHolder;
import com.matrix.agent.platform.voice.VoskWakeAdapter;
import com.matrix.agent.presentation.state.VoiceUiState;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * VoiceDebugFragment 的 ViewModel。Voice V1 Stage 4。
 *
 * <p><b>生命周期锁</b>:{@link #lifecycleLock} 保护"发布+启动"(buildAndStart)与"销毁"(onCleared)
 * 互斥,消除"刚清理完、后台又启动"窗口。模型加载在锁外(耗时不持锁);加载完后在锁内检查 cleared、
 * 发布字段、start。
 *
 * <p><b>native 生命周期</b>:Vosk Recognizer 必须先于 Model close。onCleared 用
 * {@link VoiceSessionController#shutdown(Runnable)}:shutdown 内串行 stop asr/wake/tts(close Recognizer),
 * afterStopped 在 Recognizer 关闭后关 tts 引擎 + holder(Model)+ stateExecutor。
 *
 * <p><b>下载取消</b>:每次 onReadyAndPermitted 增 generation,download 注入 {@code () -> cleared || gen!=generation};
 * 不被下载器内部状态覆盖,旧任务/销毁后任务即取消,保留 .tmp.zip 断点。
 */
public final class VoiceDebugViewModel extends ViewModel implements VoiceSessionListener {
    private static final String TAG = "MatrixAgent";

    private final Application app;
    private final AgentRuntimeRepository repository;
    private final ModelDownloadDao dao;

    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService stateExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService agentExecutor = Executors.newSingleThreadExecutor();
    private final ResponsePresenter presenter = new ResponsePresenter();
    private final VoskModelDownloader downloader;
    private final Object lifecycleLock = new Object();

    private final MutableLiveData<VoiceUiState> uiState = new MutableLiveData<>();
    private volatile VoiceSessionState.State lastState;
    private volatile String lastPartial = "";
    private volatile String lastStatus = "准备中…";

    private volatile boolean cleared;
    private volatile boolean built;
    private volatile long generation;

    private volatile VoskModelHolder holder;
    private volatile VoskWakeAdapter wakePort;
    private volatile VoskAsrAdapter asrPort;
    private volatile VoiceSessionController controller;
    private volatile VoiceCaptureController capture;
    private volatile AndroidTtsAdapter tts;
    private VoskModelSpec en;
    private VoskModelSpec cn;

    public VoiceDebugViewModel(Application app, AgentRuntimeRepository repository, ModelDownloadDao dao) {
        this.app = app;
        this.repository = repository;
        this.dao = dao; // 可空:数据库降级时进度降级
        this.downloader = new VoskModelDownloader(dao);
        postCurrent();
    }

    public LiveData<VoiceUiState> getUiState() {
        return uiState;
    }

    /** Fragment 在 RECORD_AUDIO 授权后调。 */
    public void onReadyAndPermitted() {
        File voskRoot = new File(app.getFilesDir(), "vosk-model");
        en = VoskModelSpec.en(voskRoot);
        cn = VoskModelSpec.cn(voskRoot);
        final long gen = ++generation; // 每次启动新 generation,作废旧任务
        final BooleanSupplier cancelled = () -> cleared || gen != generation;
        downloadExecutor.submit(() -> {
            if (cleared) return;
            try {
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
            } catch (Exception e) {
                Log.e(TAG, "[Voice] 启动失败: " + e.getMessage(), e);
                postStatus("启动失败: " + e.getMessage());
            }
        });
    }

    private void buildAndStart() {
        if (cleared || built) return;
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
            AgentRunner runner = (text, token) -> repository.execute(text, Actor.DRIVER, token);
            controller = new VoiceSessionController(
                    wake, asr, tts, runner, presenter, stateExecutor, agentExecutor);
            controller.setUiListener(this);
            capture = new VoiceCaptureController(controller, wake, asr);
        } catch (Exception e) {
            Log.e(TAG, "[Voice] 装配失败: " + e.getMessage(), e);
            postStatus("装配失败: " + e.getMessage());
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
                capture.start();
            } catch (RuntimeException e) {
                // start 抛(AudioRecord 初始化/设备):回滚 publish,避免 capture 死、controller 活的卡死态
                Log.e(TAG, "[Voice] 启动失败,回滚: " + e.getMessage(), e);
                this.holder = null;
                this.wakePort = null;
                this.asrPort = null;
                this.controller = null;
                this.capture = null;
                this.tts = null;
                built = false;
                try {
                    controller.shutdown(null); // 停已 start 的端口
                } catch (Exception ignored) {
                }
                cleanupLocal(capture, wake, asr, tts, holder);
                postStatus("启动采音失败: " + e.getMessage());
                return;
            }
        }
        postStatus("就绪:说 \"hey matrix\" 或按\"开始\"");
    }

    public void manualWake() {
        VoiceSessionController c = controller;
        if (c != null) c.manualWake();
    }

    public void cancel() {
        VoiceSessionController c = controller;
        if (c != null) c.onCancel();
    }

    /** 退后台:停采音释放麦克风。 */
    public void pause() {
        VoiceCaptureController c = capture;
        if (c != null) {
            try {
                c.stop();
            } catch (Exception e) {
                Log.w(TAG, "[Voice] pause: " + e.getMessage());
            }
        }
    }

    /** 回前台:恢复采音。 */
    public void resume() {
        if (cleared || !built) return;
        VoiceCaptureController c = capture;
        if (c != null) {
            try {
                c.start();
            } catch (Exception e) {
                postStatus("恢复采音失败: " + e.getMessage());
            }
        }
    }

    @Override
    public void onPartial(String text) {
        lastPartial = text == null ? "" : text;
        postCurrent();
    }

    @Override
    public void onStateChanged(VoiceSessionState.State state) {
        lastState = state;
        postCurrent();
    }

    private void postStatus(String status) {
        lastStatus = status;
        postCurrent();
    }

    private void postCurrent() {
        uiState.postValue(new VoiceUiState(lastState, lastPartial, lastStatus));
    }

    @Override
    protected void onCleared() {
        VoiceCaptureController cap;
        VoiceSessionController vc;
        AndroidTtsAdapter t;
        VoskModelHolder h;
        synchronized (lifecycleLock) {
            cleared = true;
            cap = capture;
            vc = controller;
            t = tts;
            h = holder;
        }
        // 锁外执行关闭。capture 先停(停止 feed)。
        if (cap != null) {
            try {
                cap.stop();
            } catch (Exception e) {
                Log.w(TAG, "[Voice] onCleared cap: " + e.getMessage());
            }
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
                        Log.w(TAG, "[Voice] onCleared tts: " + e.getMessage());
                    }
                }
                if (holderRef != null) {
                    try {
                        holderRef.close();
                    } catch (Exception e) {
                        Log.w(TAG, "[Voice] onCleared holder: " + e.getMessage());
                    }
                }
                stateExecutor.shutdown();
            });
        } else {
            if (t != null) {
                try {
                    t.shutdown();
                } catch (Exception e) {
                    Log.w(TAG, "[Voice] onCleared tts: " + e.getMessage());
                }
            }
            if (h != null) {
                try {
                    h.close();
                } catch (Exception e) {
                    Log.w(TAG, "[Voice] onCleared holder: " + e.getMessage());
                }
            }
            stateExecutor.shutdown();
        }
        agentExecutor.shutdownNow();
        downloadExecutor.shutdownNow();
    }

    /** 清理未发布的局部装配资源(Recognizers 先于 Model close)。不调 controller(未 start,无 native)。 */
    private static void cleanupLocal(VoiceCaptureController capture, VoskWakeAdapter wake,
            VoskAsrAdapter asr, AndroidTtsAdapter tts, VoskModelHolder holder) {
        if (capture != null) {
            try {
                capture.stop();
            } catch (Exception e) {
                Log.w(TAG, "[Voice] cleanup cap: " + e.getMessage());
            }
        }
        if (wake != null) {
            try {
                wake.stop();
            } catch (Exception e) {
                Log.w(TAG, "[Voice] cleanup wake: " + e.getMessage());
            }
        }
        if (asr != null) {
            try {
                asr.stop();
            } catch (Exception e) {
                Log.w(TAG, "[Voice] cleanup asr: " + e.getMessage());
            }
        }
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (Exception e) {
                Log.w(TAG, "[Voice] cleanup tts: " + e.getMessage());
            }
        }
        if (holder != null) {
            try {
                holder.close();
            } catch (Exception e) {
                Log.w(TAG, "[Voice] cleanup holder: " + e.getMessage());
            }
        }
    }
}
