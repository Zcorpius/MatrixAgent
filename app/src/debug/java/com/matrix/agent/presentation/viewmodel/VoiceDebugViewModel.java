package com.matrix.agent.presentation.viewmodel;

import android.app.Application;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.matrix.agent.core.voice.VoiceSessionListener;
import com.matrix.agent.core.voice.VoiceSessionState;
import com.matrix.agent.data.AgentRuntimeRepository;
import com.matrix.agent.data.db.ModelDownloadDao;
import com.matrix.agent.data.voice.VoiceRuntime;
import com.matrix.agent.presentation.state.VoiceUiState;

/**
 * VoiceDebugFragment 的 ViewModel。
 *
 * <p>降为 <b>observer + 生命周期触发</b>:voice 资源装配/回收/下载/控制全部在
 * {@link VoiceRuntime},本类只持 uiState + {@link VoiceSessionListener}/{@link VoiceRuntime.StatusListener}
 * 回调,转发生命周期(onReadyAndPermitted/manualWake/cancel/pause/resume/onCleared)到 Runtime。
 */
public final class VoiceDebugViewModel extends ViewModel
        implements VoiceSessionListener, VoiceRuntime.StatusListener {

    private final VoiceRuntime runtime;

    private final MutableLiveData<VoiceUiState> uiState = new MutableLiveData<>();
    private volatile VoiceSessionState.State lastState;
    private volatile String lastPartial = "";
    private volatile String lastStatus = "准备中…";

    public VoiceDebugViewModel(Application app, AgentRuntimeRepository repository, ModelDownloadDao dao) {
        this.runtime = new VoiceRuntime(app, repository, dao);
        postCurrent();
    }

    public LiveData<VoiceUiState> getUiState() {
        return uiState;
    }

    /** Fragment 在 RECORD_AUDIO 授权后调。 */
    public void onReadyAndPermitted() {
        runtime.start(this, this); // this = VoiceSessionListener + StatusListener
    }

    public void manualWake() {
        runtime.manualWake();
    }

    public void cancel() {
        runtime.cancel();
    }

    /** 退后台:停采音释放麦克风。 */
    public void pause() {
        runtime.pause();
    }

    /** 回前台:恢复采音。 */
    public void resume() {
        runtime.resume();
    }

    @Override
    public void onPartial(String text) {
        lastPartial = text == null ? "" : text;
        postCurrent();
    }

    @Override
    public void onStateChanged(VoiceSessionState.State state) {
        lastState = state;
        if (state != VoiceSessionState.State.IDLE) lastStatus = ""; // 进入会话(含手动唤醒成功),清除"唤醒不可用"等提示
        postCurrent();
    }

    @Override
    public void onStatus(String status) {
        lastStatus = status;
        postCurrent();
    }

    @Override
    public void onError(String code) {
        // 唤醒不可用等不可恢复错误 → status 文案提示用户(手动唤醒仍可用,进入会话后清除)。
        lastStatus = "语音唤醒不可用,可手动开始";
        postCurrent();
    }

    @Override
    protected void onCleared() {
        runtime.shutdown();
    }

    private void postCurrent() {
        uiState.postValue(new VoiceUiState(lastState, lastPartial, lastStatus));
    }
}
