package com.matrix.agent.core.memory;

import java.util.Map;

public interface MemoryStore {
    void putPreference(String userId, String key, String value);

    String getPreference(String userId, String key);

    Map<String, String> getAllPreferences(String userId);

    void clear(String userId);
}
