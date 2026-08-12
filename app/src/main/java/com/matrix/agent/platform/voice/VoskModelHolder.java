package com.matrix.agent.platform.voice;

import org.vosk.Model;

import java.io.File;
import java.io.IOException;

/**
 * Vosk 模型持有者(懒加载单例)。Voice V1 Stage 2。
 *
 * <p>持有英文唤醒模型(en)与中文 ASR 模型(cn)的 {@link Model} 实例。{@link Model} 加载重
 * (数百 ms ~ 秒级),按需懒加载、复用。模型目录约定:{@code <root>/en}、{@code <root>/cn}
 * (root 通常为 {@code filesDir/vosk-model},由 {@code VoskModelDownloader} 准备)。
 *
 * <p>线程安全:加载方法 synchronized。Model 本身 Vosk 保证线程安全可被多 Recognizer 共享。
 */
public final class VoskModelHolder {
    private final File enDir;
    private final File cnDir;
    private Model enModel;
    private Model cnModel;

    public VoskModelHolder(File voskModelRoot) {
        if (voskModelRoot == null) throw new IllegalArgumentException("voskModelRoot 不能为空");
        this.enDir = new File(voskModelRoot, "en");
        this.cnDir = new File(voskModelRoot, "cn");
    }

    /** 英文唤醒模型(首次调用加载)。 */
    public synchronized Model enModel() throws IOException {
        if (enModel == null) enModel = new Model(enDir.getAbsolutePath());
        return enModel;
    }

    /** 中文 ASR 模型(首次调用加载)。 */
    public synchronized Model cnModel() throws IOException {
        if (cnModel == null) cnModel = new Model(cnDir.getAbsolutePath());
        return cnModel;
    }

    /** 是否已加载(不触发加载)。 */
    public synchronized boolean isEnLoaded() { return enModel != null; }

    public synchronized boolean isCnLoaded() { return cnModel != null; }

    /** 释放模型。进程退出或不再使用语音时调用。 */
    public synchronized void close() {
        if (enModel != null) { enModel.close(); enModel = null; }
        if (cnModel != null) { cnModel.close(); cnModel = null; }
    }
}
