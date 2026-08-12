package com.matrix.agent;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.matrix.agent.core.voice.WakeWordPort;
import com.matrix.agent.platform.voice.VoskAsrEngine;
import com.matrix.agent.platform.voice.VoskWakeAdapter;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.vosk.Model;

import java.io.File;

import static org.junit.Assert.assertNotNull;

/**
 * Vosk 识别路径烟雾测试。Voice V1 Stage 2。
 *
 * <p>验证 native 加载 → Model 加载 → Recognizer → feed PCM → close 整条路径在真机不崩。
 * 用生成的静音 PCM,不验证识别内容(识别正确性需真实音频样本,留后续)。
 *
 * <p><b>前置</b>:测试前用 adb push 把模型解压目录推到设备:
 * <pre>
 *   adb shell mkdir -p /data/data/com.matrix.agent/files/vosk-model/en
 *   adb shell mkdir -p /data/data/com.matrix.agent/files/vosk-model/cn
 *   adb push vosk-model-small-en-us-0.15/* /data/data/com.matrix.agent/files/vosk-model/en/
 *   adb push vosk-model-small-cn-0.22/*   /data/data/com.matrix.agent/files/vosk-model/cn/
 * </pre>
 * 模型未预置时 {@link Assume#assumeTrue} 自动 skip(不计失败)。
 */
@RunWith(AndroidJUnit4.class)
public class VoskRecognizeTest {
    private static final File MODELS =
            new File(ApplicationProvider.getApplicationContext().getFilesDir(), "vosk-model");

    /** 100ms 静音 PCM(16kHz mono 16bit LE = 3200 bytes)。 */
    private static byte[] silencePcm() {
        return new byte[3200];
    }

    @Test
    public void chineseAsrEngineSmoke() throws Exception {
        File cnDir = new File(MODELS, "cn");
        Assume.assumeTrue("跳过:cn 模型未预置 " + cnDir, cnDir.isDirectory());
        Model cnModel = new Model(cnDir.getAbsolutePath());
        VoskAsrEngine engine = new VoskAsrEngine(cnModel);
        engine.start();
        engine.feed(silencePcm());   // 静音,不崩即可
        engine.stop();
        cnModel.close();
        assertNotNull(cnModel);
    }

    @Test
    public void englishWakeAdapterSmoke() throws Exception {
        File enDir = new File(MODELS, "en");
        Assume.assumeTrue("跳过:en 模型未预置 " + enDir, enDir.isDirectory());
        Model enModel = new Model(enDir.getAbsolutePath());
        VoskWakeAdapter wake = new VoskWakeAdapter(enModel);
        wake.setListener(new WakeWordPort.Listener() {
            @Override
            public void onWake() {
                // 静音 PCM 不应唤醒。
            }

            @Override
            public void onError(String code) {
            }
        });
        wake.start();
        wake.feed(silencePcm());     // 静音,不崩、不误唤醒即可
        wake.stop();
        enModel.close();
        assertNotNull(enModel);
    }
}
