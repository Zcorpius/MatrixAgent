package com.matrix.agent;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.vosk.LibVosk;
import org.vosk.LogLevel;

/**
 * Vosk native 加载验证。Voice V1 Stage 2。
 *
 * <p>仿 {@code OnDeviceNativeLoadTest}:验证 vosk-android aar 的 native .so 在目标设备能加载,
 * 不抛 {@link UnsatisfiedLinkError}。{@link LibVosk} 的 static 块负责加载,调 {@code setLogLevel}
 * 即触发。
 */
@RunWith(AndroidJUnit4.class)
public class VoskNativeLoadTest {
    private static final String TAG = "MatrixAgent";

    @Test
    public void voskNativeLibraryLoads() {
        // LibVosk 的 static{} 加载 native lib;若加载失败此处抛 UnsatisfiedLinkError。
        LibVosk.setLogLevel(LogLevel.INFO);
        Log.i(TAG, "[Voice] Vosk native 库加载成功");
    }
}
