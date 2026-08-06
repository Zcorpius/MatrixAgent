package com.matrix.agent.data.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.matrix.agent.MainActivity;
import com.matrix.agent.app.AppContainer;
import com.matrix.agent.app.MatrixAgentApplication;
import com.matrix.agent.data.db.ModelDownloadDao;
import com.matrix.agent.data.db.ModelDownloadEntity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 端侧模型下载前台服务——在专用线程池跑 {@link ModelDownloadManager#download}，通知栏显示
 * 进度文字（百分比）。下载完成/失败后 {@link #stopSelf}。
 *
 * <p><b>线程模型</b>：用专用 {@link Executors#newFixedThreadPool}(2) 而非共享 ioPool。
 * 下载是分钟级长任务，占用 ioPool 线程会与 ModelCallExecutor / ToolExecutor 竞争，可能触发
 * AbortPolicy 拒绝 Agent I/O 子任务。专用池与 AppContainer 的 scheduler/io 池物理隔离，与
 * V0.5.2 评审 P1-1 "调度池与 I/O 池隔离" 同一原则。两个线程分别跑：
 * <ol>
 *   <li>下载线程：阻塞调 {@code manager.download(entry)}；</li>
 *   <li>轮询线程：周期性读 DAO 进度，更新通知文字，直到终态。</li>
 * </ol>
 *
 * <p><b>foregroundServiceType</b>：manifest 已声明 {@code dataSync}，targetSdk 36 要求
 * startForeground 显式传 {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_DATA_SYNC}（API 30+ 走
 * 3 参重载，API 28/29 走 2 参重载，类型由 manifest 提供）。
 */
public final class DownloadService extends Service {
    private static final String TAG = "MatrixAgent";

    public static final String ACTION_DOWNLOAD = "com.matrix.agent.action.DOWNLOAD";
    public static final String EXTRA_MODEL_NAME = "model_name";
    public static final String EXTRA_MODEL_SCOPE_REPO = "model_scope_repo";
    public static final String EXTRA_DESCRIPTION = "description";
    public static final String EXTRA_SIZE_GB = "size_gb";

    static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "model_download";
    private static final long POLL_INTERVAL_MS = 800L;

    private ExecutorService workExec;
    private volatile boolean active;
    private volatile String currentModel;

    @Nullable
    @Override
    public IBinder onBind(@Nullable Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        workExec = Executors.newFixedThreadPool(2, new DaemonThreadFactory());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_DOWNLOAD.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String modelName = intent.getStringExtra(EXTRA_MODEL_NAME);
        String repo = intent.getStringExtra(EXTRA_MODEL_SCOPE_REPO);
        if (modelName == null || modelName.isEmpty() || repo == null || repo.isEmpty()) {
            Log.w(TAG, "[DownloadService] missing modelName/repo, stop");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        AppContainer container = appContainer();
        ModelDownloadManager manager = container != null ? container.getModelDownloadManager() : null;
        ModelDownloadDao dao = container != null ? container.getModelDownloadDao() : null;
        if (manager == null || dao == null) {
            // database=null（SQLCipher/KeyStore 不可用）的降级路径——下载功能依赖 DAO 落进度。
            Log.w(TAG, "[DownloadService] manager/dao unavailable (DB degraded), stop");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String desc = intent.getStringExtra(EXTRA_DESCRIPTION);
        double sizeGb = intent.getDoubleExtra(EXTRA_SIZE_GB, 0d);
        ModelMarketClient.ModelEntry entry = new ModelMarketClient.ModelEntry(
                modelName, desc != null ? desc : modelName, sizeGb, repo);

        currentModel = modelName;
        active = true;
        startForegroundCompat(buildNotification(modelName, 0, "准备下载"));

        workExec.execute(() -> pollProgress(modelName, dao));
        workExec.execute(() -> runDownload(entry, startId, manager));
        return START_NOT_STICKY;
    }

    /** 周期性读 DAO 进度，更新通知文字。见到终态即退出。 */
    private void pollProgress(String modelName, ModelDownloadDao dao) {
        while (active) {
            try {
                ModelDownloadEntity e = dao.getByName(modelName);
                if (e != null) {
                    int pct = computePct(e);
                    notifyProgress(modelName, pct, subTextForStatus(e.status, modelName, pct));
                    if (isTerminal(e.status)) {
                        active = false;
                        break;
                    }
                }
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                Log.w(TAG, "[DownloadService] poll error: " + e.getMessage());
            }
        }
    }

    /** 实际下载任务——阻塞调 manager.download，结束后置终态通知 + stopSelf。 */
    private void runDownload(ModelMarketClient.ModelEntry entry, int startId, ModelDownloadManager manager) {
        try {
            manager.download(entry);
        } catch (Exception e) {
            Log.e(TAG, "[DownloadService] download failed: " + entry.modelName
                    + " — " + e.getMessage(), e);
        } finally {
            active = false;
            ModelDownloadDao dao = getDaoSafely();
            if (dao != null) {
                try {
                    ModelDownloadEntity e = dao.getByName(entry.modelName);
                    if (e != null) {
                        int pct = computePct(e);
                        notifyProgress(entry.modelName, pct,
                                subTextForStatus(e.status, entry.modelName, pct));
                    }
                } catch (Exception ignored) {
                    // 终态通知失败不掩盖下载结果
                }
            }
            stopSelf(startId);
        }
    }

    @Override
    public void onDestroy() {
        active = false;
        // 服务销毁时清理活动下载（用户划掉通知 / 系统回收）——置取消标志，下载循环下个 chunk 抛 cancelled。
        if (currentModel != null) {
            try {
                ModelDownloadManager manager = appContainer() != null
                        ? appContainer().getModelDownloadManager() : null;
                if (manager != null) manager.cancel(currentModel);
            } catch (Exception ignored) {
                // cancel 必须不抛
            }
        }
        if (workExec != null) {
            workExec.shutdownNow();
        }
        super.onDestroy();
    }

    // ===== 通知 =====

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "模型下载", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("端侧大模型下载进度");
            channel.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    /** API 30+ 走 3 参重载显式指定类型；API 28/29 走 2 参（类型由 manifest 提供）。 */
    @SuppressWarnings("deprecation")
    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void notifyProgress(String modelName, int pct, String subText) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.notify(NOTIFICATION_ID, buildNotification(modelName, pct, subText));
    }

    private Notification buildNotification(String modelName, int pct, String subText) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("模型下载")
                .setContentText(subText)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(mainContentIntent())
                .build();
    }

    @Nullable
    private PendingIntent mainContentIntent() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            return PendingIntent.getActivity(this, 0, intent, flags);
        } catch (Exception e) {
            return null;
        }
    }

    // ===== 辅助 =====

    @Nullable
    private AppContainer appContainer() {
        try {
            return ((MatrixAgentApplication) getApplication()).getContainer();
        } catch (ClassCastException e) {
            return null;
        }
    }

    @Nullable
    private ModelDownloadDao getDaoSafely() {
        AppContainer c = appContainer();
        return c != null ? c.getModelDownloadDao() : null;
    }

    private static int computePct(ModelDownloadEntity e) {
        if (e == null || e.totalBytes <= 0) return 0;
        long d = Math.max(0, e.downloadedBytes);
        long pct = d * 100 / e.totalBytes;
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        return (int) pct;
    }

    private static boolean isTerminal(String status) {
        return ModelDownloadManager.STATUS_COMPLETED.equals(status)
                || ModelDownloadManager.STATUS_FAILED.equals(status);
    }

    private static String subTextForStatus(String status, String modelName, int pct) {
        if (ModelDownloadManager.STATUS_COMPLETED.equals(status)) {
            return "下载完成：" + modelName;
        } else if (ModelDownloadManager.STATUS_FAILED.equals(status)) {
            return "下载失败：" + modelName;
        } else if (ModelDownloadManager.STATUS_DOWNLOADING.equals(status)) {
            return "下载 " + modelName + " " + pct + "%";
        } else {
            return "等待下载：" + modelName;
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger count = new AtomicInteger(0);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "DownloadService-" + count.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
