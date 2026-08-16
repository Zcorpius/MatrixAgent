package com.matrix.agent.debug;

import android.app.Application;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;

import com.matrix.agent.data.AgentRuntimeRepository;
import com.matrix.agent.data.db.ModelDownloadDao;
import com.matrix.agent.presentation.ui.VoiceDebugFragment;
import com.matrix.agent.presentation.viewmodel.VoiceDebugViewModel;
import com.matrix.agent.presentation.viewmodel.VoiceEntryProvider;

/**
 * Debug voice 入口实现:创建 {@link VoiceDebugFragment} / {@link VoiceDebugViewModel}(仅 debug 源集可见)。
 *
 * <p>本类位于 {@code src/debug},main 反射加载({@code VoiceEntryProviders} 按全限定类名),
 * 避免 main 对 voice Demo 的编译期依赖。Release 不编译本类,也不含其引用的 Vosk 实现。
 */
public final class DebugVoiceEntryProvider implements VoiceEntryProvider {
    @Override
    public Fragment createVoiceFragment() {
        return new VoiceDebugFragment();
    }

    @Override
    public Class<? extends ViewModel> voiceViewModelClass() {
        return VoiceDebugViewModel.class;
    }

    @Override
    public ViewModel createVoiceViewModel(Application app, AgentRuntimeRepository repository, ModelDownloadDao dao) {
        return new VoiceDebugViewModel(app, repository, dao);
    }
}
