package com.matrix.agent.platform;

public enum PlannerMode {
    NATIVE_TOOL_CALLING("Native Tool Calling"),
    STRUCTURED_JSON_COMPATIBILITY("Structured JSON Compatibility");

    public final String displayName;

    PlannerMode(String displayName) {
        this.displayName = displayName;
    }
}
