package com.matrix.agent.presentation.ui;

import android.os.Bundle;
import android.text.InputType;
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
import com.matrix.agent.platform.ModelConfig;
import com.matrix.agent.platform.ModelProviderPreset;
import com.matrix.agent.platform.PlannerMode;
import com.matrix.agent.presentation.viewmodel.MatrixViewModelFactory;
import com.matrix.agent.presentation.viewmodel.ModelApiViewModel;

import java.util.List;

public final class ModelApiFragment extends Fragment {
    private static final String TAG = "MatrixAgent";
    private ModelApiViewModel viewModel;
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
        viewModel = new ViewModelProvider(this, new MatrixViewModelFactory(container))
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
            apiStatus.setText(status);
        });
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
    }

    private void fillPreset(ModelProviderPreset preset, String apiKey) {
        displayedProviderId = preset.id;
        endpointInput.setText(preset.endpoint);
        modelInput.setText(preset.model);
        keyInput.setText(apiKey);
        apiStatus.setText(getString(preset.apiKeyRequired
                ? R.string.protocol_key_required : R.string.protocol_key_optional, preset.protocol));
    }

    /**
     * 切换 API Key 输入框的可见性。默认隐藏(textPassword + ic_eye_hidden),
     * 点一下变可见(visible password + ic_eye_visible),再点切回去。
     * 切换后保留光标位置,避免跑到末尾。
     */
    private void toggleKeyVisibility() {
        boolean hidden = (keyInput.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD)
                == InputType.TYPE_TEXT_VARIATION_PASSWORD;
        int selection = keyInput.getSelectionEnd();
        if (hidden) {
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
        Log.d(TAG, "[ModelApi] key visibility toggled -> " + (hidden ? "visible" : "hidden"));
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
    }

    private ModelProviderPreset findPresetById(String id) {
        if (id == null) return null;
        for (ModelProviderPreset preset : presets) {
            if (preset.id.equals(id)) return preset;
        }
        return null;
    }
}
