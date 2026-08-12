package com.matrix.agent.data.voice;

import android.util.Log;

import com.matrix.agent.data.db.ModelDownloadDao;
import com.matrix.agent.data.db.ModelDownloadEntity;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Vosk 模型下载器。Voice V1 Stage 4。
 *
 * <p>{@code ModelDownloadManager} 与 MNN/ModelScope 强绑定无法复用;本类独立实现 Vosk zip 下载 + 解压,
 * 复用 {@link ModelDownloadEntity}/{@link ModelDownloadDao} 记录进度。
 *
 * <p><b>取消语义</b>:{@link #download(VoskModelSpec, BooleanSupplier)} 接受外部 {@code cancelled}
 * supplier(调用方持有 {@code cleared/generation}),连接前 / 每次读循环 / 解压前后均检查。
 * 取消抛 {@link DownloadCancelledException}——**保留** {@code .tmp.zip} 断点(状态 PAUSED,下次 Range 续传),
 * 只删解压中途目录;只有真失败(网络/解压/校验)才删临时文件(FAILED)。
 *
 * <p>不再按模型名复用布尔标记(避免 {@code put(name,false)} 覆盖调用方的取消信号)。
 *
 * <p><b>dao 可空</b>:数据库降级时进度降级为仅日志,不崩。
 */
public final class VoskModelDownloader {
    private static final String TAG = "MatrixAgent";
    private static final int PROGRESS_THRESHOLD = 512 * 1024;
    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT = 30_000;

    private final ModelDownloadDao dao; // 可空

    public VoskModelDownloader(ModelDownloadDao dao) {
        this.dao = dao;
    }

    public boolean isDownloaded(VoskModelSpec spec) {
        return new File(spec.targetDir, spec.marker).exists();
    }

    /** 下载并解压一个模型。cancelled 由调用方持有(cleared/generation)。 */
    public void download(VoskModelSpec spec, BooleanSupplier cancelled) throws IOException {
        File parent = spec.targetDir.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建模型目录: " + parent);
        }
        File tmpZip = new File(parent, ".tmp_" + spec.name + ".zip");
        File tmpDir = new File(parent, ".tmp_" + spec.name);
        setStatus(spec, "DOWNLOADING", tmpZip.exists() ? tmpZip.length() : 0, 0);
        try {
            checkCancelled(cancelled);
            downloadZip(spec, tmpZip, cancelled);
            checkCancelled(cancelled);
            unzipFlatten(tmpZip, tmpDir);
            checkCancelled(cancelled);
            verifyMarker(tmpDir, spec.marker);
            if (spec.targetDir.exists()) deleteRecursive(spec.targetDir);
            if (!tmpDir.renameTo(spec.targetDir)) {
                throw new IOException("rename " + tmpDir + " -> " + spec.targetDir + " 失败");
            }
            tmpZip.delete();
            setStatus(spec, "COMPLETED", spec.targetDir.length(), spec.targetDir.length());
            Log.i(TAG, "[Voice] Vosk 模型就绪: " + spec.name);
        } catch (Exception e) {
            // 取消信号:checkCancelled 抛的 DownloadCancelledException、外部 cancelled supplier、
            // 或 shutdownNow 中断(read 抛 InterruptedIOException)——都保留 .tmp.zip 断点(PAUSED),
            // 不删;只有真正的失败(网络/解压/校验异常)才删(FAILED)。
            boolean cancelledNow = (e instanceof DownloadCancelledException)
                    || cancelled.getAsBoolean()
                    || Thread.currentThread().isInterrupted();
            long have = tmpZip.exists() ? tmpZip.length() : 0;
            if (cancelledNow) {
                setStatus(spec, "PAUSED", have, 0);
                deleteRecursive(tmpDir); // 解压中途不完整,删;保留 .tmp.zip 断点
                Log.i(TAG, "[Voice] 下载已暂停(保留断点 .tmp.zip," + have + " B): " + spec.name);
            } else {
                Log.e(TAG, "[Voice] Vosk 模型下载失败 " + spec.name + ": " + e.getMessage(), e);
                setStatus(spec, "FAILED", 0, 0);
                tmpZip.delete();
                deleteRecursive(tmpDir);
            }
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws DownloadCancelledException {
        if (cancelled.getAsBoolean()) throw new DownloadCancelledException();
    }

    private void downloadZip(VoskModelSpec spec, File tmpZip, BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        HttpURLConnection conn = (HttpURLConnection) new URL(spec.zipUrl).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        try {
            long existing = tmpZip.exists() ? tmpZip.length() : 0;
            if (existing > 0) conn.setRequestProperty("Range", "bytes=" + existing + "-");
            int code = conn.getResponseCode();
            boolean append = code == 206 && existing > 0;
            if (!append && tmpZip.exists() && !tmpZip.delete()) {
                throw new IOException("无法删除残留 .tmp.zip");
            }
            long contentLen = conn.getContentLength();
            long fullTotal = append && contentLen > 0 ? existing + contentLen : contentLen;
            long downloaded = append ? existing : 0;
            setStatus(spec, "DOWNLOADING", downloaded, fullTotal);
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmpZip, append)) {
                byte[] buf = new byte[8192];
                int n;
                long lastReported = downloaded;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled.getAsBoolean()) throw new DownloadCancelledException();
                    out.write(buf, 0, n);
                    downloaded += n;
                    if (downloaded - lastReported >= PROGRESS_THRESHOLD) {
                        setStatus(spec, "DOWNLOADING", downloaded, fullTotal);
                        lastReported = downloaded;
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private void unzipFlatten(File zip, File destDir) throws IOException {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("无法创建解压目录: " + destDir);
        }
        String base = destDir.getCanonicalPath();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                int slash = name.indexOf('/');
                String relative = slash >= 0 ? name.substring(slash + 1) : name;
                if (relative.isEmpty()) {
                    zis.closeEntry();
                    continue;
                }
                File out = new File(destDir, relative);
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(base + File.separator)) {
                    throw new IOException("路径逃逸: " + canonical);
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("无法创建: " + parent);
                    }
                    try (FileOutputStream fo = new FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = zis.read(buf)) > 0) fo.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private void verifyMarker(File dir, String marker) throws IOException {
        if (!new File(dir, marker).exists()) {
            throw new IOException("模型标志缺失: " + marker + "(解压目录: " + dir + ")");
        }
    }

    private void setStatus(VoskModelSpec spec, String status, long downloaded, long total) {
        String mb = (total > 0 ? (downloaded >> 20) + "/" + (total >> 20) + " MiB"
                : (downloaded >> 20) + " MiB");
        Log.i(TAG, "[Voice] " + spec.name + " " + status + " " + mb);
        if (dao == null) return;
        ModelDownloadEntity e = new ModelDownloadEntity();
        e.modelName = spec.name;
        e.displayName = spec.name;
        e.sourceRepo = spec.zipUrl;
        e.downloadedBytes = downloaded;
        e.totalBytes = total;
        e.status = status;
        long now = System.currentTimeMillis();
        e.createdAt = now;
        e.updatedAt = now;
        try {
            dao.upsert(e);
        } catch (Exception ex) {
            Log.w(TAG, "[Voice] 进度写入失败: " + ex.getMessage());
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }

    /** 取消信号触发的异常;catch 后保留断点、标 PAUSED。 */
    static final class DownloadCancelledException extends IOException {
        DownloadCancelledException() {
            super("已取消");
        }
    }
}
