package com.matrix.agent.data.download;

import android.content.Context;

import androidx.annotation.NonNull;

import com.matrix.agent.data.db.ModelDownloadDao;
import com.matrix.agent.data.db.ModelDownloadEntity;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 端侧 MNN 模型下载管理器——串行下载、断点续传、原子落盘。
 *
 * <p>模型最终落到 {@code filesDir/models/mnn/<modelName>/}。下载过程中文件先写入
 * {@code filesDir/models/mnn/.tmp_<modelName>/}，全部文件下完且 {@code llm_config.json}
 * 校验通过后，rename 为正式目录（原子完成）。校验失败或中途异常 → 删除 tmp 目录 +
 * 状态置 {@link #STATUS_FAILED}。
 *
 * <p><b>线程模型</b>：所有方法在调用方线程同步执行，内部不创建线程池。
 * 由 {@code DownloadService} 或上层 {@code ioPool} 调度。进度通过 {@link ModelDownloadDao#update}
 * 落库，调用方可轮询 {@link ModelDownloadDao#getAll()} 观察。
 *
 * <p>下载可被 {@link #cancel(String)} 中断：每个模型一个 volatile 标志，下载循环每块读取后检查。
 *
 * <p>status 取值：{@link #STATUS_IDLE} / {@link #STATUS_DOWNLOADING} /
 * {@link #STATUS_PAUSED} / {@link #STATUS_COMPLETED} / {@link #STATUS_FAILED}。
 */
public class ModelDownloadManager {

    /** status 常量。 */
    public static final String STATUS_IDLE = "IDLE";
    public static final String STATUS_DOWNLOADING = "DOWNLOADING";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    /** models 根目录（相对 filesDir）。 */
    private static final String MODELS_REL = "models/mnn";
    /** 下载中临时目录前缀，最终目录名为 {@code .tmp_<modelName>}。 */
    private static final String TMP_PREFIX = ".tmp_";
    /** MNN 模型配置文件名，存在即视为模型目录完整的前提。 */
    private static final String CONFIG_FILE = "llm_config.json";
    /** llm_config.json 中引用的文件字段（实际 MNN 字段名）。 */
    private static final String[] CONFIG_REF_FIELDS = {
            "llm_model", "llm_weight", "embedding_file", "tokenizer_file"
    };

    private static final int BUFFER_SIZE = 8192;
    private static final long PROGRESS_UPDATE_THRESHOLD = 512L * 1024L; // 512KB
    private static final int MAX_ATTEMPTS = 3;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final double SPACE_HEADROOM = 1.1; // 预留 10% 余量

    private final Context appContext;
    private final ModelDownloadDao dao;

    /** 每个模型一个取消标志；cancel() 置 true，下载循环检查后中断。 */
    private final ConcurrentHashMap<String, Boolean> cancelFlags = new ConcurrentHashMap<>();

    public ModelDownloadManager(@NonNull Context appContext, @NonNull ModelDownloadDao dao) {
        this.appContext = appContext.getApplicationContext();
        this.dao = dao;
    }

    // ===== 公开 API =====

    /**
     * 下载一个模型。串行下载仓库内所有文件到 {@code .tmp_<modelName>/}，全部完成后原子
     * rename 为正式目录。中途任何失败（网络/磁盘/校验/取消）→ 删 tmp + 状态 FAILED。
     *
     * @param entry 模型市场条目，必须携带 ModelScope repo
     * @throws IOException          下载失败（含取消，message 含 "cancelled"）
     * @throws IllegalArgumentException entry 无 ModelScope repo
     */
    public void download(@NonNull ModelMarketClient.ModelEntry entry) throws IOException {
        if (entry == null) throw new IllegalArgumentException("entry == null");
        String modelName = entry.modelName;
        String repo = entry.modelScopeRepo;
        if (repo == null || repo.isEmpty()) {
            throw new IllegalArgumentException(
                    "entry has no ModelScope repo: " + modelName);
        }

        // 清理可能残留的旧 cancel 标志（上次 cancel 时无活动下载 → 标志遗留）
        cancelFlags.remove(modelName);

        File modelsDir = getModelsDir();
        File tmpDir = new File(modelsDir, TMP_PREFIX + modelName);
        File finalDir = new File(modelsDir, modelName);

        ModelDownloadEntity entity = null;
        try {
            // 1. 尽早建/更新 entity → DOWNLOADING（确保 catch 中 entity 非空）
            entity = dao.getByName(modelName);
            boolean existed = entity != null;
            if (!existed) {
                entity = new ModelDownloadEntity();
                entity.modelName = modelName;
                entity.createdAt = System.currentTimeMillis();
            }
            entity.displayName = entry.description;
            entity.sourceRepo = repo;
            entity.status = STATUS_DOWNLOADING;
            entity.updatedAt = System.currentTimeMillis();
            if (existed) dao.update(entity); else dao.upsert(entity);

            // 2. 列出仓库所有文件
            List<ModelScopeClient.FileInfo> files = ModelScopeClient.listFiles(repo);
            if (files.isEmpty()) {
                throw new IOException("no files listed for " + repo);
            }

            // 3. 计算总大小
            long totalBytes = 0;
            for (ModelScopeClient.FileInfo fi : files) totalBytes += fi.size;
            if (totalBytes == 0 && entry.sizeGb > 0) {
                totalBytes = (long) (entry.sizeGb * 1_000_000_000L);
            }
            entity.totalBytes = totalBytes;
            entity.updatedAt = System.currentTimeMillis();
            dao.update(entity);

            // 4. 磁盘预检
            if (!hasEnoughSpace(totalBytes)) {
                throw new IOException("insufficient disk space for " + modelName
                        + " (need ~" + totalBytes + " bytes)");
            }

            // 5. 准备 tmp 目录
            if (tmpDir.exists()) {
                // 残留 tmp 保留（断点续传），但若已无 entity 记录此处不会走到
            } else if (!tmpDir.mkdirs() && !tmpDir.exists()) {
                throw new IOException("cannot create tmp dir: " + tmpDir);
            }

            // 6. 串行下载（断点续传 + 失败重试）
            AtomicLong cumulative = new AtomicLong(0);
            for (ModelScopeClient.FileInfo fi : files) {
                if (isCancelled(modelName)) {
                    throw new IOException("cancelled: " + modelName);
                }
                File tmpFile = new File(tmpDir, fi.path);
                ensureWithinDir(tmpFile, tmpDir); // 禁止 path 逃逸
                File parent = tmpFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("cannot create parent dir: " + parent);
                }
                String url = ModelScopeClient.downloadUrl(repo, fi.path);
                downloadFileWithResume(entity, url, tmpFile, fi.size, cumulative, modelName);
            }

            // 7. 校验 llm_config.json + 引用文件
            if (!validateModelDir(tmpDir)) {
                throw new IOException("invalid model dir (llm_config.json check failed): "
                        + modelName);
            }

            // 8. 原子 rename → 正式目录
            if (finalDir.exists()) deleteRecursive(finalDir);
            if (!tmpDir.renameTo(finalDir)) {
                throw new IOException("rename failed: " + tmpDir + " -> " + finalDir);
            }

            // 9. COMPLETED
            entity.downloadedBytes = entity.totalBytes;
            entity.status = STATUS_COMPLETED;
            entity.updatedAt = System.currentTimeMillis();
            dao.update(entity);

        } catch (Exception e) {
            // 失败：删 tmp + 状态 FAILED
            deleteRecursive(tmpDir);
            try {
                if (entity == null) {
                    // 理论上不会走到（entity 在 try 顶部即创建），防御性处理
                    entity = dao.getByName(modelName);
                }
                if (entity == null) {
                    entity = new ModelDownloadEntity();
                    entity.modelName = modelName;
                    entity.displayName = entry.description;
                    entity.sourceRepo = repo;
                    entity.createdAt = System.currentTimeMillis();
                }
                entity.status = STATUS_FAILED;
                entity.updatedAt = System.currentTimeMillis();
                if (dao.getByName(modelName) != null) dao.update(entity);
                else dao.upsert(entity);
            } catch (Exception ignored) {
                // 状态落库失败不掩盖原始下载失败
            }
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("download failed: " + modelName + " — " + e.getMessage(), e);
        } finally {
            cancelFlags.remove(modelName);
        }
    }

    /**
     * 取消下载：置取消标志（中断活动下载循环）+ 删 tmp 目录 + 状态 FAILED。
     * 若无对应 entity 记录，则仅清磁盘。
     */
    public void cancel(@NonNull String modelName) {
        cancelFlags.put(modelName, Boolean.TRUE);
        try {
            File tmpDir = new File(getModelsDir(), TMP_PREFIX + modelName);
            if (tmpDir.exists()) deleteRecursive(tmpDir);
        } finally {
            try {
                ModelDownloadEntity entity = dao.getByName(modelName);
                if (entity != null) {
                    entity.status = STATUS_FAILED;
                    entity.updatedAt = System.currentTimeMillis();
                    dao.update(entity);
                }
            } catch (Exception ignored) {
                // cancel 必须不抛
            }
        }
    }

    /**
     * 列出本地已下载完成的模型名（{@code filesDir/models/mnn/} 下含 {@code llm_config.json}
     * 的子目录，跳过 {@code .tmp_} 前缀）。字典序排序。
     */
    @NonNull
    public List<String> listLocalModels() {
        File modelsDir = getModelsDir();
        File[] children = modelsDir.listFiles();
        if (children == null) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (File f : children) {
            if (!f.isDirectory()) continue;
            if (f.getName().startsWith(TMP_PREFIX)) continue;
            if (new File(f, CONFIG_FILE).exists()) {
                result.add(f.getName());
            }
        }
        Collections.sort(result);
        return result;
    }

    /**
     * 模型是否已下载完整（正式目录存在 + {@link #validateModelDir(File)} 通过）。
     */
    public boolean isDownloaded(@NonNull String modelName) {
        File finalDir = new File(getModelsDir(), modelName);
        return finalDir.exists() && validateModelDir(finalDir);
    }

    // ===== 内部实现 =====

    /** 下载单个文件（断点续传 + 失败重试）。cumulative 累计所有文件已下载字节，用于进度。 */
    private void downloadFileWithResume(@NonNull ModelDownloadEntity entity,
                                        @NonNull String downloadUrl,
                                        @NonNull File tmpFile,
                                        long expectedSize,
                                        @NonNull AtomicLong cumulative,
                                        @NonNull String modelName) throws IOException {
        // 已下载完整 → 直接跳过（避免 Range unsatisfiable 416）
        if (expectedSize > 0 && tmpFile.exists() && tmpFile.length() >= expectedSize) {
            return;
        }

        // 统计本文件 resume 基线（已存在字节）一次
        long fileBaseline = tmpFile.exists() ? tmpFile.length() : 0;
        if (fileBaseline > 0) {
            cumulative.addAndGet(fileBaseline);
            entity.downloadedBytes = cumulative.get();
            entity.updatedAt = System.currentTimeMillis();
            dao.update(entity);
        }

        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (isCancelled(modelName)) throw new IOException("cancelled: " + modelName);

            long existing = tmpFile.exists() ? tmpFile.length() : 0;
            HttpURLConnection conn = (HttpURLConnection) new URL(downloadUrl).openConnection();
            try {
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", "MatrixAgent/1.0");
                if (existing > 0) {
                    conn.setRequestProperty("Range", "bytes=" + existing + "-");
                }

                int code = conn.getResponseCode();
                // 416 Range Not Satisfiable 且已有字节 → 视为已完成
                if (code == 416 && existing > 0) {
                    return;
                }
                if (code != 200 && code != 206) {
                    throw new IOException("HTTP " + code + " for " + downloadUrl);
                }
                boolean append = (code == 206);
                if (!append && existing > 0) {
                    // 服务端忽略 Range，整文件重下：修正之前计入的基线，避免重复累计
                    cumulative.addAndGet(-existing);
                }

                try (InputStream is = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(tmpFile, append)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    long sinceUpdate = 0;
                    while ((read = is.read(buffer)) != -1) {
                        if (isCancelled(modelName)) {
                            throw new IOException("cancelled: " + modelName);
                        }
                        fos.write(buffer, 0, read);
                        cumulative.addAndGet(read);
                        sinceUpdate += read;
                        if (sinceUpdate >= PROGRESS_UPDATE_THRESHOLD) {
                            entity.downloadedBytes = cumulative.get();
                            entity.updatedAt = System.currentTimeMillis();
                            dao.update(entity);
                            sinceUpdate = 0;
                        }
                    }
                    fos.flush();
                }

                // 本文件完成 → flush 进度
                entity.downloadedBytes = cumulative.get();
                entity.updatedAt = System.currentTimeMillis();
                dao.update(entity);
                return; // 成功
            } catch (IOException e) {
                last = e;
                if (isCancelled(modelName)) throw e;
                // 否则进入下一次重试（resume 从 tmpFile.length() 继续）
            } finally {
                conn.disconnect();
            }
        }
        throw last;
    }

    /**
     * 校验模型目录：{@code llm_config.json} 存在且其引用文件均存在、路径未逃逸。
     * 任何异常 → false（不抛）。
     */
    private boolean validateModelDir(@NonNull File dir) {
        File configFile = new File(dir, CONFIG_FILE);
        if (!configFile.exists() || !configFile.isFile()) return false;
        try {
            ensureWithinDir(configFile, dir);
            String text = new String(Files.readAllBytes(configFile.toPath()), "UTF-8");
            JSONObject config = new JSONObject(text);
            for (String field : CONFIG_REF_FIELDS) {
                if (!config.has(field) || config.isNull(field)) continue;
                String value = config.getString(field);
                if (value == null || value.isEmpty()) continue;
                File referenced = new File(dir, value);
                if (!referenced.exists()) return false;
                ensureWithinDir(referenced, dir); // 禁止 .. 逃逸
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 磁盘可用空间是否大于 requiredBytes * 1.1（10% 余量）。 */
    private boolean hasEnoughSpace(long requiredBytes) {
        long available = appContext.getFilesDir().getUsableSpace();
        return available > (long) (requiredBytes * SPACE_HEADROOM);
    }

    @NonNull
    private File getModelsDir() {
        return new File(appContext.getFilesDir(), MODELS_REL);
    }

    private boolean isCancelled(@NonNull String modelName) {
        return Boolean.TRUE.equals(cancelFlags.get(modelName));
    }

    /** child 必须位于 baseDir 内部（canonical 比较，禁止 {@code ..} 逃逸）。 */
    private static void ensureWithinDir(@NonNull File child, @NonNull File baseDir)
            throws IOException {
        String c = child.getCanonicalPath();
        String b = baseDir.getCanonicalPath();
        if (!c.equals(b) && !c.startsWith(b + File.separator)) {
            throw new IOException("path escapes base dir: " + child + " (base=" + baseDir + ")");
        }
    }

    /** 递归删除文件/目录。 */
    private static void deleteRecursive(@NonNull File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
