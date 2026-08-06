package com.matrix.agent.presentation.viewmodel;

import android.app.Application;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.matrix.agent.app.MatrixAgentApplication;
import com.matrix.agent.data.db.ModelDownloadDao;
import com.matrix.agent.data.db.ModelDownloadEntity;
import com.matrix.agent.data.download.DownloadService;
import com.matrix.agent.data.download.ModelDownloadManager;
import com.matrix.agent.data.download.ModelMarketClient;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 端侧模型下载页面 ViewModel——桥接模型市场列表 / 下载状态 / 启动 DownloadService。
 *
 * <p>构造器约定（由 {@link MatrixViewModelFactory} 装配）：{@code (Application, ModelDownloadManager,
 * ModelDownloadDao)}。manager/dao 可为 null（AppContainer database=null 的降级路径），此时
 * startDownload/refreshDownloads 退化为只写 notice，不 NPE。
 *
 * <p>后台执行：优先复用 AppContainer 共享 ioPool（与 ModelCallExecutor/ToolExecutor 同池，但本
 * ViewModel 仅做 fetchModels/getAll 这种秒级 I/O，不会长占线程）；取不到则 fallback 到自带
 * single-thread executor（{@link #onCleared()} 关闭）。
 */
public final class ModelDownloadViewModel extends ViewModel {
    private static final String TAG = "MatrixAgent";
    private static final String MARKET_CACHE = "model_market_cache.json";

    private final Application app;
    @Nullable private final ModelDownloadManager manager;
    @Nullable private final ModelDownloadDao dao;
    private final ExecutorService io;
    private final boolean ownedExecutor;

    private final MutableLiveData<List<ModelMarketClient.ModelEntry>> marketModels = new MutableLiveData<>();
    private final MutableLiveData<List<ModelDownloadEntity>> downloads = new MutableLiveData<>();
    private final MutableLiveData<String> notice = new MutableLiveData<>();

    public ModelDownloadViewModel(Application app,
            @Nullable ModelDownloadManager manager,
            @Nullable ModelDownloadDao dao) {
        this.app = app;
        this.manager = manager;
        this.dao = dao;
        marketModels.setValue(Collections.emptyList());
        downloads.setValue(Collections.emptyList());
        ExecutorService shared = sharedIoPool();
        if (shared != null) {
            io = shared;
            ownedExecutor = false;
        } else {
            io = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ModelDownloadVM-io");
                t.setDaemon(true);
                return t;
            });
            ownedExecutor = true;
        }
        if (manager == null || dao == null) {
            notice.setValue("数据库不可用（加密初始化失败），模型下载功能不可用");
        }
    }

    public LiveData<List<ModelMarketClient.ModelEntry>> getMarketModels() { return marketModels; }
    public LiveData<List<ModelDownloadEntity>> getDownloads() { return downloads; }
    public LiveData<String> getNotice() { return notice; }
    @Nullable public ModelDownloadManager getManager() { return manager; }

    /** 拉取模型市场 JSON（带 filesDir 缓存，网络失败用缓存）。 */
    public void refreshMarket() {
        io.execute(() -> {
            try {
                File cache = new File(app.getFilesDir(), MARKET_CACHE);
                List<ModelMarketClient.ModelEntry> list = ModelMarketClient.fetchModels(cache);
                marketModels.postValue(list != null ? list : Collections.emptyList());
                if (list == null || list.isEmpty()) {
                    notice.postValue("未获取到模型（市场为空或网络失败且无缓存）");
                }
            } catch (Exception e) {
                Log.e(TAG, "[ModelDownload] refreshMarket failed: " + e.getMessage(), e);
                marketModels.postValue(Collections.emptyList());
                notice.postValue("拉取模型市场失败：" + safeMsg(e));
            }
        });
    }

    /** 刷新下载状态列表（DAO getAll）。dao=null 时 no-op。 */
    public void refreshDownloads() {
        if (dao == null) return;
        io.execute(() -> {
            try {
                downloads.postValue(dao.getAll());
            } catch (Exception e) {
                Log.w(TAG, "[ModelDownload] refreshDownloads failed: " + e.getMessage());
            }
        });
    }

    /** 启动前台下载服务。manager=null 或 entry 无 ModelScope 源时只写 notice。 */
    public void startDownload(ModelMarketClient.ModelEntry entry) {
        if (manager == null) {
            notice.setValue("数据库不可用，无法下载");
            return;
        }
        if (entry == null || entry.modelScopeRepo == null || entry.modelScopeRepo.isEmpty()) {
            notice.setValue((entry != null ? entry.modelName : "模型") + " 无 ModelScope 源，无法下载");
            return;
        }
        Intent intent = new Intent(app, DownloadService.class);
        intent.setAction(DownloadService.ACTION_DOWNLOAD);
        intent.putExtra(DownloadService.EXTRA_MODEL_NAME, entry.modelName);
        intent.putExtra(DownloadService.EXTRA_MODEL_SCOPE_REPO, entry.modelScopeRepo);
        intent.putExtra(DownloadService.EXTRA_DESCRIPTION, entry.description);
        intent.putExtra(DownloadService.EXTRA_SIZE_GB, entry.sizeGb);
        try {
            ContextCompat.startForegroundService(app, intent);
            notice.setValue("已开始下载：" + entry.modelName);
        } catch (Exception e) {
            notice.setValue("启动下载失败：" + safeMsg(e));
        }
    }

    public void cancelDownload(String modelName) {
        if (manager == null || modelName == null) return;
        io.execute(() -> {
            try {
                manager.cancel(modelName);
            } catch (Exception e) {
                Log.w(TAG, "[ModelDownload] cancel failed: " + e.getMessage());
            } finally {
                refreshDownloads();
            }
        });
    }

    @Nullable
    private ExecutorService sharedIoPool() {
        try {
            return ((MatrixAgentApplication) app).getContainer().getIoPool().asExecutorService();
        } catch (Exception e) {
            return null;
        }
    }

    private static String safeMsg(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    @Override
    protected void onCleared() {
        if (ownedExecutor) io.shutdownNow();
    }
}
