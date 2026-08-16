package com.matrix.agent.presentation.viewmodel;

import android.util.Log;

import com.matrix.agent.BuildConfig;

/**
 * 反射加载 Debug voice 入口提供者。
 *
 * <p>main 编译期不可见 {@code DebugVoiceEntryProvider}(它在 {@code src/debug}),用反射按类名加载,
 * 避免 main 对 debug 源集的编译期依赖。仅 {@link BuildConfig#DEBUG} 时尝试加载(Release 直接返回 null,
 * 也不触发反射)。加载结果缓存(页面切换不重复反射)。
 */
public final class VoiceEntryProviders {
    private static final String TAG = "MatrixAgent";
    private static volatile VoiceEntryProvider cached;

    private VoiceEntryProviders() {}

    /** @return debug 构建时返回 Debug voice 入口提供者;release 或加载失败返回 null。 */
    public static VoiceEntryProvider get() {
        if (!BuildConfig.DEBUG) return null;
        VoiceEntryProvider p = cached;
        if (p != null) return p;
        try {
            p = (VoiceEntryProvider) Class.forName("com.matrix.agent.debug.DebugVoiceEntryProvider")
                    .getDeclaredConstructor().newInstance();
            cached = p;
        } catch (Exception e) {
            Log.w(TAG, "[Voice] Debug voice 入口不可用: " + e.getMessage());
        }
        return p;
    }
}
