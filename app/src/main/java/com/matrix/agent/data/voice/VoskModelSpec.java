package com.matrix.agent.data.voice;

import java.io.File;

/**
 * Vosk 模型下载规格。Voice V1 Stage 4。
 *
 * <p>描述一个 Vosk 模型的 zip URL、解压目标目录与完成标志文件。zip 解压后顶层目录
 * (如 {@code vosk-model-small-cn-0.22/})会被 flatten 到 {@link #targetDir}(内容直接落在 targetDir 下,
 * 不带顶层目录名),供 {@code VoskModelHolder} 以 {@code new Model(targetDir)} 加载。
 */
public final class VoskModelSpec {
    /** 进度记录用的模型名(也是 DAO 主键),如 {@code "vosk-en"}。 */
    public final String name;
    /** zip 下载地址。 */
    public final String zipUrl;
    /** 解压目标目录(filesDir/vosk-model/{en,cn})。 */
    public final File targetDir;
    /** 完成标志文件(相对 targetDir),如 {@code "conf/mfcc.conf"}。 */
    public final String marker;

    public VoskModelSpec(String name, String zipUrl, File targetDir, String marker) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("name 不能为空");
        if (zipUrl == null || zipUrl.isEmpty()) throw new IllegalArgumentException("zipUrl 不能为空");
        if (targetDir == null) throw new IllegalArgumentException("targetDir 不能为空");
        if (marker == null || marker.isEmpty()) throw new IllegalArgumentException("marker 不能为空");
        this.name = name;
        this.zipUrl = zipUrl;
        this.targetDir = targetDir;
        this.marker = marker;
    }

    /** 英文唤醒小模型(vosk-model-small-en-us-0.15)。 */
    public static VoskModelSpec en(File voskRoot) {
        return new VoskModelSpec("vosk-en",
                "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
                new File(voskRoot, "en"), "conf/mfcc.conf");
    }

    /** 中文 ASR 小模型(vosk-model-small-cn-0.22)。 */
    public static VoskModelSpec cn(File voskRoot) {
        return new VoskModelSpec("vosk-cn",
                "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip",
                new File(voskRoot, "cn"), "conf/mfcc.conf");
    }
}
