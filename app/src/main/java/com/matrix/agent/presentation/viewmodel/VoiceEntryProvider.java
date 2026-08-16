package com.matrix.agent.presentation.viewmodel;

import android.app.Application;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;

import com.matrix.agent.data.AgentRuntimeRepository;
import com.matrix.agent.data.db.ModelDownloadDao;

/**
 * Debug 语音入口提供者(仅 debug 构建有实现)。
 *
 * <p>main 源集不直接引用 voice Demo 实现({@code VoiceDebugFragment} / {@code VoiceDebugViewModel}),
 * 经本接口解耦——Release 编译时 main 无 voice 实现依赖,Vosk 依赖降级 {@code debugImplementation},
 * Release APK 不含 {@code libvosk.so} 与 voice Demo 类。
 *
 * <p>实现由 {@code src/debug} 的 {@code DebugVoiceEntryProvider} 提供;main 运行时反射加载
 * (见 {@link VoiceEntryProviders#get()},仅 {@code BuildConfig.DEBUG})。
 */
public interface VoiceEntryProvider {
    /** Debug 语音页 Fragment。 */
    Fragment createVoiceFragment();

    /** Debug 语音 ViewModel 的类型(Factory 路由判断用)。 */
    Class<? extends ViewModel> voiceViewModelClass();

    /** 创建 Debug 语音 ViewModel。 */
    ViewModel createVoiceViewModel(Application app, AgentRuntimeRepository repository, ModelDownloadDao dao);
}
