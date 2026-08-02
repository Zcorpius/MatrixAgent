package com.matrix.agent.core.memory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class InMemoryMemoryStore implements MemoryStore {
    private final Map<String, Map<String, String>> values = new LinkedHashMap<>();

    @Override
    public synchronized void putPreference(String userId, String key, String value) {
        values.computeIfAbsent(userId, ignored -> new LinkedHashMap<>()).put(key, value);
    }

    @Override
    public synchronized String getPreference(String userId, String key) {
        Map<String, String> userValues = values.get(userId);
        return userValues == null ? null : userValues.get(key);
    }

    @Override
    public synchronized Map<String, String> getAllPreferences(String userId) {
        Map<String, String> userValues = values.get(userId);
        if (userValues == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(userValues));
    }

    @Override
    public synchronized void clear(String userId) {
        values.remove(userId);
    }
}
