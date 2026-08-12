package com.matrix.agent.presentation.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.matrix.agent.BuildConfig;
import com.matrix.agent.app.MatrixAgentApplication;
import com.matrix.agent.R;
import com.matrix.agent.app.AppContainer;
import com.matrix.agent.presentation.state.VoiceUiState;
import com.matrix.agent.presentation.viewmodel.MatrixViewModelFactory;
import com.matrix.agent.presentation.viewmodel.VoiceDebugViewModel;

/**
 * 语音闭环 Demo 界面。Voice V1 Stage 4。
 *
 * <p>进页面即申请 RECORD_AUDIO,授权后 ViewModel 下载模型 + 装配 + 启动采音。
 */
public final class VoiceDebugFragment extends Fragment {
    private static final String TAG = "MatrixAgent";
    private static final int REQUEST_RECORD_AUDIO = 1002;

    private VoiceDebugViewModel viewModel;
    private TextView stateView;
    private TextView partialView;
    private TextView statusView;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        Log.i(TAG, "[Voice] Fragment onCreateView");
        return inflater.inflate(R.layout.fragment_voice_debug, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.i(TAG, "[Voice] Fragment onViewCreated, BuildConfig.DEBUG=" + BuildConfig.DEBUG);
        AppContainer appContainer = ((MatrixAgentApplication) requireActivity().getApplication()).getContainer();
        viewModel = new ViewModelProvider(this, new MatrixViewModelFactory(appContainer))
                .get(VoiceDebugViewModel.class);

        stateView = view.findViewById(R.id.voice_state);
        partialView = view.findViewById(R.id.voice_partial);
        statusView = view.findViewById(R.id.voice_status);
        view.findViewById(R.id.voice_start).setOnClickListener(v -> viewModel.manualWake());
        view.findViewById(R.id.voice_stop).setOnClickListener(v -> viewModel.cancel());

        if (!BuildConfig.DEBUG) {
            Log.i(TAG, "[Voice] release 构建,不装配/不采音");
            statusView.setText("语音闭环仅 Debug 构建可用(release 不开放)");
            return;
        }
        viewModel.getUiState().observe(getViewLifecycleOwner(), this::render);
        ensureMicPermissionAndStart();
    }

    private void render(VoiceUiState s) {
        if (s == null) return;
        stateView.setText(s.state == null ? "—" : s.state.name());
        partialView.setText(s.partial == null || s.partial.isEmpty() ? "(无)" : s.partial);
        statusView.setText(s.status == null ? "" : s.status);
    }

    private void ensureMicPermissionAndStart() {
        boolean granted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        Log.i(TAG, "[Voice] ensureMicPermissionAndStart, RECORD_AUDIO granted=" + granted);
        if (granted) {
            viewModel.onReadyAndPermitted();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO) return;
        boolean granted = grantResults.length == 1
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Log.i(TAG, "[Voice] onRequestPermissionsResult, granted=" + granted);
        if (granted) {
            viewModel.onReadyAndPermitted();
        } else if (statusView != null) {
            statusView.setText("需要麦克风权限才能运行语音闭环");
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (viewModel != null) viewModel.pause();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (viewModel != null) viewModel.resume();
    }
}
