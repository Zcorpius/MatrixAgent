package com.matrix.agent.presentation.ui;

import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.matrix.agent.R;
import com.matrix.agent.app.MatrixAgentApplication;
import com.matrix.agent.app.AppContainer;
import com.matrix.agent.data.download.ModelDownloadManager;
import com.matrix.agent.platform.ApiProtocol;
import com.matrix.agent.platform.ModelConfig;
import com.matrix.agent.platform.ModelProviderPreset;
import com.matrix.agent.platform.PlannerMode;
import com.matrix.agent.presentation.viewmodel.MatrixViewModelFactory;
import com.matrix.agent.presentation.viewmodel.ModelApiViewModel;

import java.util.List;

public final class ModelApiFragment extends Fragment {
    private static final String TAG = "MatrixAgent";
    /** 跨页跳转参数：ModelDownloadFragment 的"选用"按钮传递端侧模型目录名，进入后自动切端侧 + 填 model。 */
    public static final String ARG_ON_DEVICE_MODEL = "on_device_model";
    private ModelApiViewModel viewModel;
    /** 端侧已下载模型枚举（用于 apiStatus 提示）。database=null 时为 null。 */
    private ModelDownloadManager downloadManager;
    private List<ModelProviderPreset> presets;
    private Spinner providerSpinner;
    private Spinner plannerModeSpinner;
    private EditText endpointInput;
    private EditText modelInput;
    private EditText keyInput;
    private ImageButton keyVisibilityToggle;
    private Button testButton;
    private Button saveButton;
    private Button offlineButton;
    private TextView apiStatus;
    private String displayedProviderId;
    /** API Key 是否明文显示。用独立字段跟踪，避免 inputType bit 判断歧义
     *（VISIBLE_PASSWORD 0x90 & PASSWORD 0x80 == 0x80，显示后会被误判为仍隐藏）。 */
    private boolean keyVisible = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_model_api, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        AppContainer container = ((MatrixAgentApplication) requireActivity()
                .getApplication()).getContainer();
        downloadManager = container.getModelDownloadManager();
        // Activity scope：MainActivity.showPage 用 replace+new Fragment 切换页面，若 ViewModel 绑 Fragment
        // 会在 replace 时 onCleared→executor.shutdownNow，中断正在跑的 save/load，且 drafts 草稿丢失。
        // 绑 Activity 后，Fragment 重建不影响 ViewModel——saveAndApply 跑完、provider 切换草稿保留。
        viewModel = new ViewModelProvider(requireActivity(), new MatrixViewModelFactory(container))
                .get(ModelApiViewModel.class);

        providerSpinner = view.findViewById(R.id.provider_spinner);
        plannerModeSpinner = view.findViewById(R.id.planner_mode_spinner);
        endpointInput = view.findViewById(R.id.endpoint_input);
        modelInput = view.findViewById(R.id.model_input);
        keyInput = view.findViewById(R.id.key_input);
        keyVisibilityToggle = view.findViewById(R.id.key_visibility_toggle);
        testButton = view.findViewById(R.id.test_connection_button);
        saveButton = view.findViewById(R.id.save_apply_button);
        offlineButton = view.findViewById(R.id.offline_button);
        apiStatus = view.findViewById(R.id.api_status);

        presets = viewModel.getProviderPresets();
        String[] names = new String[presets.size()];
        for (int index = 0; index < presets.size(); index++) names[index] = presets.get(index).name;
        providerSpinner.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, names));
        PlannerMode[] modes = PlannerMode.values();
        String[] modeNames = new String[modes.length];
        for (int index = 0; index < modes.length; index++) modeNames[index] = modes[index].displayName;
        plannerModeSpinner.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, modeNames));
        restoreForm();
        providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View selected, int position, long id) {
                ModelProviderPreset newPreset = presets.get(position);
                if (newPreset.id.equals(displayedProviderId)) return;
                // 切换前:把当前表单(属于旧 displayedProviderId)的输入存为草稿
                saveCurrentDraftForDisplayed();
                // 切换后:优先用草稿,没草稿才用 preset 默认值
                ModelConfig draft = viewModel.loadDraft(newPreset.id);
                if (draft != null) {
                    Log.d(TAG, "[ModelApi] restore draft for provider=" + newPreset.id);
                    applyDraftToForm(draft, newPreset);
                } else {
                    Log.d(TAG, "[ModelApi] no draft for provider=" + newPreset.id
                            + " -> fillPreset default");
                    fillPreset(newPreset, "");
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        testButton.setOnClickListener(ignored -> viewModel.testConnection(readConfig()));
        saveButton.setOnClickListener(ignored -> viewModel.saveAndApply(readConfig()));
        offlineButton.setOnClickListener(ignored -> viewModel.useDemoGateway());
        keyVisibilityToggle.setOnClickListener(ignored -> toggleKeyVisibility());
        viewModel.getUiState().observe(getViewLifecycleOwner(), state -> {
            testButton.setEnabled(!state.loading);
            saveButton.setEnabled(!state.loading);
            offlineButton.setEnabled(!state.loading);
            // V0.5.3 评审 P1-3:memoryDegraded=true(SQLCipher/Room 不可用)时,在 apiStatus 顶部
            // 显示 banner,提醒用户"记忆已降级到内存模式,重启后丢失"。fail-closed 文案——
            // 不暴露具体失败原因,避免给攻击者调试信号。
            String status = state.status;
            if (state.memoryDegraded) {
                status = "⚠️ 记忆已降级到内存模式,重启后丢失\n\n" + status;
            }
            // 端侧模式：status 末尾列出已下载模型，方便用户对照 modelInput 填目录名。
            status = appendOnDeviceHint(status);
            apiStatus.setText(status);
        });

        // 跨页跳转：ModelDownloadFragment "选用" 按钮通过 arguments 传模型目录名。
        applyOnDeviceArgs();
    }

    private void restoreForm() {
        ModelConfig saved = viewModel.getSavedConfig();
        if (saved == null) {
            fillPreset(presets.get(0), "");
            return;
        }
        int selectedIndex = 0;
        for (int index = 0; index < presets.size(); index++) {
            if (presets.get(index).id.equals(saved.providerId)) selectedIndex = index;
        }
        providerSpinner.setSelection(selectedIndex);
        plannerModeSpinner.setSelection(saved.plannerMode.ordinal());
        displayedProviderId = saved.providerId;
        endpointInput.setText(saved.endpoint);
        modelInput.setText(saved.model);
        keyInput.setText(saved.apiKey);
        applyOnDeviceMode(findPresetById(saved.providerId));
    }

    private void fillPreset(ModelProviderPreset preset, String apiKey) {
        displayedProviderId = preset.id;
        endpointInput.setText(preset.endpoint);
        modelInput.setText(preset.model);
        keyInput.setText(apiKey);
        apiStatus.setText(getString(preset.apiKeyRequired
                ? R.string.protocol_key_required : R.string.protocol_key_optional, preset.protocol));
        applyOnDeviceMode(preset);
    }

    /**
     * 端侧模式（ON_DEVICE）：隐藏云端字段（endpoint/key/test），model 输入框提示填模型目录路径；
     * plannerMode 固定 STRUCTURED_JSON_COMPATIBILITY。云端模式恢复字段可见。
     */
    private void applyOnDeviceMode(ModelProviderPreset preset) {
        boolean onDevice = preset.protocol == ApiProtocol.ON_DEVICE;
        int cloudVis = onDevice ? View.GONE : View.VISIBLE;
        View root = getView();
        if (root == null) return;
        endpointInput.setVisibility(cloudVis);
        root.findViewById(R.id.endpoint_label).setVisibility(cloudVis);
        root.findViewById(R.id.api_key_label).setVisibility(cloudVis);
        root.findViewById(R.id.key_row).setVisibility(cloudVis);   // 含 key_input + 可见性 toggle
        // testButton 始终可见：云端="测试连接"(HTTP)，端侧="测试加载"(load+推理验证)
        testButton.setVisibility(View.VISIBLE);
        testButton.setText(onDevice ? "测试加载" : "测试连接");
        if (onDevice) {
            modelInput.setHint("模型目录名（filesDir/models/mnn/<name>）");
            plannerModeSpinner.setSelection(PlannerMode.STRUCTURED_JSON_COMPATIBILITY.ordinal());
            plannerModeSpinner.setEnabled(false); // P2-20: 端侧固定 Structured，不允许改 NATIVE
            // 端侧：apiStatus 直接显示已下载模型清单（切换 provider 时 uiState 不变，observer 不会刷新）。
            apiStatus.setText(onDeviceHint());
        } else {
            plannerModeSpinner.setEnabled(true); // 还原
        }
    }

    /** 端侧已下载模型清单文案。downloadManager=null（DB 降级）时提示不可用。 */
    private String onDeviceHint() {
        if (downloadManager == null) return "已下载模型：（数据库不可用）";
        List<String> models = downloadManager.listLocalModels();
        if (models.isEmpty()) return "已下载模型：（暂无，去「模型下载」页面下载）";
        return "已下载模型：" + TextUtils.join(", ", models);
    }

    /** uiState observer 用：当前 provider 为端侧时，把已下载清单追加到 status 末尾。 */
    private String appendOnDeviceHint(String status) {
        ModelProviderPreset cur = findPresetById(displayedProviderId);
        if (cur != null && cur.protocol == ApiProtocol.ON_DEVICE) {
            return status + "\n\n" + onDeviceHint();
        }
        return status;
    }

    /**
     * 跨页跳转入口：ModelDownloadFragment 的"选用"按钮通过 Fragment arguments 传模型目录名。
     * 进入后切到端侧 provider + 填 modelInput + applyOnDeviceMode。
     */
    private void applyOnDeviceArgs() {
        Bundle args = getArguments();
        if (args == null) return;
        String model = args.getString(ARG_ON_DEVICE_MODEL);
        if (model == null || model.isEmpty()) return;
        // 找端侧 preset 的下标，切 spinner（触发 fillPreset → applyOnDeviceMode 隐藏云端字段）。
        int onDeviceIndex = 0;
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).protocol == ApiProtocol.ON_DEVICE) {
                onDeviceIndex = i;
                break;
            }
        }
        providerSpinner.setSelection(onDeviceIndex);
        // onItemSelected 同步触发后会清空 modelInput（on-device preset 默认 model=""），这里覆盖。
        modelInput.setText(model);
        Log.d(TAG, "[ModelApi] applyOnDeviceArgs model=" + model);
    }

    /**
     * 切换 API Key 输入框的可见性。默认隐藏(textPassword + ic_eye_hidden),
     * 点一下变可见(visible password + ic_eye_visible),再点切回去。
     * 切换后保留光标位置,避免跑到末尾。
     */
    private void toggleKeyVisibility() {
        keyVisible = !keyVisible;
        int selection = keyInput.getSelectionEnd();
        if (keyVisible) {
            keyInput.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            keyVisibilityToggle.setImageResource(R.drawable.ic_eye_visible);
        } else {
            keyInput.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            keyVisibilityToggle.setImageResource(R.drawable.ic_eye_hidden);
        }
        keyInput.setTypeface(keyInput.getTypeface());
        if (selection >= 0 && selection <= keyInput.getText().length()) {
            keyInput.setSelection(selection);
        }
        Log.d(TAG, "[ModelApi] key visibility -> " + (keyVisible ? "visible" : "hidden"));
    }

    private ModelConfig readConfig() {
        ModelProviderPreset preset = presets.get(providerSpinner.getSelectedItemPosition());
        return new ModelConfig(preset.id, preset.name, preset.protocol,
                endpointInput.getText().toString().trim(),
                modelInput.getText().toString().trim(),
                keyInput.getText().toString().trim(), preset.apiKeyRequired,
                PlannerMode.values()[plannerModeSpinner.getSelectedItemPosition()]);
    }

    /**
     * 把当前表单(属于 displayedProviderId 这个 provider)拍快照存进 ViewModel 草稿,
     * 这样用户切到别的 provider 试一试再切回来时,之前输入的 endpoint/model/apiKey 不会丢。
     */
    private void saveCurrentDraftForDisplayed() {
        ModelProviderPreset oldPreset = findPresetById(displayedProviderId);
        if (oldPreset == null) return;
        ModelConfig draft = new ModelConfig(oldPreset.id, oldPreset.name, oldPreset.protocol,
                endpointInput.getText().toString().trim(),
                modelInput.getText().toString().trim(),
                keyInput.getText().toString().trim(),
                oldPreset.apiKeyRequired,
                PlannerMode.values()[plannerModeSpinner.getSelectedItemPosition()]);
        viewModel.saveDraft(oldPreset.id, draft);
    }

    /** 把草稿还原到表单。跟 fillPreset 不同,这里用草稿里的真实 endpoint/model/apiKey,
     *  而不是 preset 的默认值。plannerMode 也一并回显。 */
    private void applyDraftToForm(ModelConfig draft, ModelProviderPreset preset) {
        displayedProviderId = preset.id;
        endpointInput.setText(draft.endpoint);
        modelInput.setText(draft.model);
        keyInput.setText(draft.apiKey);
        plannerModeSpinner.setSelection(draft.plannerMode.ordinal());
        apiStatus.setText(getString(preset.apiKeyRequired
                ? R.string.protocol_key_required : R.string.protocol_key_optional, preset.protocol));
        applyOnDeviceMode(preset);
    }

    private ModelProviderPreset findPresetById(String id) {
        if (id == null) return null;
        for (ModelProviderPreset preset : presets) {
            if (preset.id.equals(id)) return preset;
        }
        return null;
    }
}
