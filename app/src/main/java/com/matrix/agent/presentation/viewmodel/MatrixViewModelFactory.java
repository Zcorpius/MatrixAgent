package com.matrix.agent.presentation.viewmodel;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.matrix.agent.app.AppContainer;

public final class MatrixViewModelFactory implements ViewModelProvider.Factory {
    private final AppContainer container;

    public MatrixViewModelFactory(AppContainer container) {
        this.container = container;
    }

    @NonNull
    @Override
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        ViewModel viewModel;
        if (modelClass.isAssignableFrom(AgentTestViewModel.class)) {
            viewModel = new AgentTestViewModel(container.getAgentRuntimeRepository());
        } else if (modelClass.isAssignableFrom(ModelApiViewModel.class)) {
            viewModel = new ModelApiViewModel(container.getAgentRuntimeRepository(),
                    container.getModelGatewayRepository());
        } else {
            throw new IllegalArgumentException("Unknown ViewModel: " + modelClass.getName());
        }
        return modelClass.cast(viewModel);
    }
}
