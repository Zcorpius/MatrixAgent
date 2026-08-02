package com.matrix.agent.core.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ToolResult {
    public enum Status {
        SUCCESS,
        POLICY_REJECTED,
        EXECUTION_FAILED,
        VERIFICATION_FAILED,
        TIMED_OUT,
        CANCELLED
    }

    private final Status status;
    private final String capabilityName;
    private final String message;
    private final Map<String, Object> observedState;
    private final boolean verified;
    private final long durationMillis;

    public ToolResult(
            Status status,
            String capabilityName,
            String message,
            Map<String, Object> observedState,
            boolean verified,
            long durationMillis) {
        this.status = status;
        this.capabilityName = capabilityName;
        this.message = message;
        this.observedState = Collections.unmodifiableMap(new LinkedHashMap<>(observedState));
        this.verified = verified;
        this.durationMillis = durationMillis;
    }

    public static ToolResult rejected(String capabilityName, String message) {
        return new ToolResult(
                Status.POLICY_REJECTED,
                capabilityName,
                message,
                Collections.emptyMap(),
                false,
                0L);
    }

    public Status getStatus() {
        return status;
    }

    public String getCapabilityName() {
        return capabilityName;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, Object> getObservedState() {
        return observedState;
    }

    public boolean isVerified() {
        return verified;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
