package com.matrix.agent.platform;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureModelConfigStore {
    private static final String TAG = "MatrixAgent";
    private static final String FILE = "matrix_model_config";
    private static final String KEY_ALIAS = "matrix_agent_api_key";
    private final SharedPreferences preferences;

    public SecureModelConfigStore(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        Log.i(TAG, "[ConfigStore] init file=" + FILE
                + " knownKeys=" + preferences.getAll().keySet());
    }

    public void save(ModelConfig config) throws Exception {
        Log.i(TAG, "[ConfigStore] save provider=" + config.providerId
                + " model=" + config.model + " mode=" + config.plannerMode
                + " apiKeyChars=" + (config.apiKey == null ? 0 : config.apiKey.length()));
        String encryptedApiKey = encrypt(config.apiKey);
        preferences.edit()
                .putString("providerId", config.providerId)
                .putString("displayName", config.displayName)
                .putString("protocol", config.protocol.name())
                .putString("endpoint", config.endpoint)
                .putString("model", config.model)
                .putBoolean("keyRequired", config.apiKeyRequired)
                .putString("plannerMode", config.plannerMode.name())
                .putString("apiKey", encryptedApiKey)
                .apply();
        Log.d(TAG, "[ConfigStore] saved. apiKeyCipherChars=" + encryptedApiKey.length()
                + " allKeys=" + preferences.getAll().keySet());
    }

    public ModelConfig load() {
        String providerId = preferences.getString("providerId", null);
        if (providerId == null) {
            Log.i(TAG, "[ConfigStore] load -> no saved config (providerId is null)");
            return null;
        }
        Log.d(TAG, "[ConfigStore] load providerId=" + providerId
                + " hasApiKeyCipher=" + preferences.contains("apiKey")
                + " apiKeyCipherChars=" + preferences.getString("apiKey", "").length());
        try {
            ModelConfig config = new ModelConfig(
                    providerId,
                    preferences.getString("displayName", providerId),
                    ApiProtocol.valueOf(preferences.getString("protocol", ApiProtocol.OPENAI_CHAT.name())),
                    preferences.getString("endpoint", ""),
                    preferences.getString("model", ""),
                    decrypt(preferences.getString("apiKey", "")),
                    preferences.getBoolean("keyRequired", true),
                    PlannerMode.valueOf(preferences.getString("plannerMode",
                            PlannerMode.STRUCTURED_JSON_COMPATIBILITY.name())));
            Log.i(TAG, "[ConfigStore] load OK provider=" + config.providerId
                    + " model=" + config.model + " mode=" + config.plannerMode);
            return config;
        } catch (Exception error) {
            Log.e(TAG, "[ConfigStore] load FAILED, returning null. cause="
                    + error.getClass().getSimpleName() + ": " + error.getMessage(), error);
            return null;
        }
    }

    private String encrypt(String plainText) throws Exception {
        if (plainText == null || plainText.isEmpty()) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private String decrypt(String value) throws Exception {
        if (value == null || value.isEmpty()) return "";
        String[] parts = value.split("\\.", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
