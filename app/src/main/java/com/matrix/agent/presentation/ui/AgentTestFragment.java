package com.matrix.agent.presentation.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.matrix.agent.R;
import com.matrix.agent.app.MatrixAgentApplication;
import com.matrix.agent.app.AppContainer;
import com.matrix.agent.core.identity.Actor;
import com.matrix.agent.presentation.viewmodel.AgentTestViewModel;
import com.matrix.agent.presentation.viewmodel.MatrixViewModelFactory;

public final class AgentTestFragment extends Fragment {
    private AgentTestViewModel viewModel;
    private EditText commandInput;
    private Spinner actorSpinner;
    private Button executeButton;
    private TextView plannerStatus;
    private TextView outputView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_agent_test, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        AppContainer container = ((MatrixAgentApplication) requireActivity()
                .getApplication()).getContainer();
        viewModel = new ViewModelProvider(this, new MatrixViewModelFactory(container))
                .get(AgentTestViewModel.class);

        commandInput = view.findViewById(R.id.command_input);
        actorSpinner = view.findViewById(R.id.actor_spinner);
        executeButton = view.findViewById(R.id.execute_button);
        plannerStatus = view.findViewById(R.id.planner_status);
        outputView = view.findViewById(R.id.output_view);

        actorSpinner.setAdapter(new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, new String[]{"主驾", "副驾"}));
        executeButton.setOnClickListener(ignored -> execute());
        bindSample(view, R.id.quick_multi, "把主驾温度调到24度，然后导航回家");
        bindSample(view, R.id.quick_context, "副驾也一样");
        bindSample(view, R.id.quick_save, "记住我喜欢24度");
        bindSample(view, R.id.quick_read, "我喜欢多少度");
        bindSample(view, R.id.quick_security, "打开ADAS");
        bindSample(view, R.id.quick_partial, "空调调到23度，然后导航到失败测试点");
        view.findViewById(R.id.show_state_button).setOnClickListener(
                ignored -> viewModel.showState());
        view.findViewById(R.id.clear_data_button).setOnClickListener(
                ignored -> viewModel.clearData());

        viewModel.getUiState().observe(getViewLifecycleOwner(), state -> {
            executeButton.setEnabled(!state.loading);
            plannerStatus.setText(getString(R.string.current_planner, state.planner));
            outputView.setText(state.output);
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (viewModel != null) viewModel.refreshPlanner();
    }

    private void bindSample(View root, int buttonId, String command) {
        root.findViewById(buttonId).setOnClickListener(ignored -> commandInput.setText(command));
    }

    private void execute() {
        String command = commandInput.getText().toString().trim();
        if (command.isEmpty()) {
            commandInput.setError("请输入任务");
            return;
        }
        hideKeyboard();
        Actor actor = actorSpinner.getSelectedItemPosition() == 0
                ? Actor.DRIVER : Actor.PASSENGER;
        viewModel.execute(command, actor);
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) requireContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        View focused = requireActivity().getCurrentFocus();
        if (manager != null && focused != null) {
            manager.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
    }
}
