package com.matrix.agent.platform;

import android.content.Context;
import android.content.SharedPreferences;

import com.matrix.agent.core.memory.MemoryStore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SharedPreferencesMemoryStore implements MemoryStore {
    private static final String FILE_NAME = "matrix_agent_memory";
    private final SharedPreferences preferences;

    public SharedPreferencesMemoryStore(Context context) {
        this.preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public void putPreference(String userId, String key, String value) {
        preferences.edit().putString(storageKey(userId, key), value).apply();
    }

    @Override
    public String getPreference(String userId, String key) {
        return preferences.getString(storageKey(userId, key), null);
    }

    @Override
    public Map<String, String> getAllPreferences(String userId) {
        String prefix = userId + ".";
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getValue() instanceof String) {
                result.put(entry.getKey().substring(prefix.length()), (String) entry.getValue());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public void clear(String userId) {
        String prefix = userId + ".";
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(prefix)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    private static String storageKey(String userId, String key) {
        return userId + "." + key;
    }
}
