package com.matrix.agent.core.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ToolDefinition {
    private final String modelName;
    private final String capabilityName;
    private final String description;
    private final List<ToolParameterDefinition> parameters;

    public ToolDefinition(String modelName, String capabilityName, String description,
            List<ToolParameterDefinition> parameters) {
        this.modelName = modelName;
        this.capabilityName = capabilityName;
        this.description = description == null ? "" : description;
        this.parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
    }

    public String getModelName() { return modelName; }
    public String getCapabilityName() { return capabilityName; }
    public String getDescription() { return description; }
    public List<ToolParameterDefinition> getParameters() { return parameters; }
}
