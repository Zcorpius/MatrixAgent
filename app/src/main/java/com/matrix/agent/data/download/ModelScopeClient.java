package com.matrix.agent.data.download;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * ModelScope API 客户端——列仓库文件 + 构造下载 URL。
 * API 参考 Operit MnnModelDownloadManager。
 */
public final class ModelScopeClient {
    private static final String BASE = "https://modelscope.cn/api/v1/models";

    /** 列出 owner/repo 仓库的所有文件（Recursive）。返回 List<FileInfo>（name/path/size）。 */
    public static List<FileInfo> listFiles(String ownerRepo) throws Exception {
        URL url = new URL(BASE + "/" + ownerRepo + "/repo/files?Recursive=1");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "MatrixAgent/1.0");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            JSONObject data = root.optJSONObject("Data");
            JSONArray files = data != null ? data.optJSONArray("Files") : null;
            List<FileInfo> result = new ArrayList<>();
            if (files != null) {
                for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.optJSONObject(i);
                    if (f == null) continue;
                    String type = f.optString("Type", "");
                    if ("tree".equals(type)) continue; // 跳过目录
                    result.add(new FileInfo(
                            f.optString("Name", ""),
                            f.optString("Path", ""),
                            f.optLong("Size", 0)));
                }
            }
            return result;
        } finally {
            conn.disconnect();
        }
    }

    /** 构造单文件下载 URL（支持 Range）。 */
    public static String downloadUrl(String ownerRepo, String filePath) {
        return BASE + "/" + ownerRepo + "/repo?FilePath=" + filePath;
    }

    public static final class FileInfo {
        public final String name;
        public final String path;
        public final long size;

        public FileInfo(String name, String path, long size) {
            this.name = name;
            this.path = path;
            this.size = size;
        }
    }
}
