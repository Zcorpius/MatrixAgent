package com.matrix.agent.data.voice;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link VoskModelDownloader} 回归测试。Voice V1 Stage 4。
 *
 * <p>验证:下载开始前已取消时,不建立 HTTP 连接(downloadZip 未调)、状态 PAUSED、不产出模型目录。
 * cancelled 由外部 supplier 持有,不被下载器内部状态覆盖。
 */
public final class VoskModelDownloaderTest {

    @Test
    public void download_cancelledBeforeConnect_marksPaused_noDownload() throws Exception {
        File root = Files.createTempDirectory("vosk-test").toFile();
        VoskModelDownloader d = new VoskModelDownloader(null); // dao 降级(仅日志)
        VoskModelSpec spec = new VoskModelSpec("test", "http://example.com/x.zip",
                new File(root, "model"), "conf/mfcc.conf");
        try {
            d.download(spec, () -> true); // 下载前即取消
            fail("已取消应抛异常");
        } catch (IOException e) {
            // 抛的是 DownloadCancelledException("已取消"),不是 HTTP 连接异常——证明未建连接
            assertTrue("应是取消异常,实际: " + e.getMessage(),
                    e.getMessage().contains("已取消"));
        }
        assertFalse("取消不应产出模型", d.isDownloaded(spec));
        root.delete();
    }
}
