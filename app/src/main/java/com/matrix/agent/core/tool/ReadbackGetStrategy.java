package com.matrix.agent.core.tool;

/**
 * V0.4.3 Stage D:回读 memory preference verify 策略——
 * {@link com.matrix.agent.core.capability.VerifyMethod#READBACK_GET} 对应实现。
 *
 * <p>对应 capability:memory.preference.save / memory.preference.get。
 * 语义:命令下发后从 memoryStore 重读 preference,与 expected 比对(non-null 即视为成功)。
 *
 * <p>key 来自 call.argument(keyArgName),user 来自 ctx.userId()。
 */
public final class ReadbackGetStrategy implements VerifyStrategy {
    private final String keyArgName;

    public ReadbackGetStrategy(String keyArgName) {
        if (keyArgName == null || keyArgName.isEmpty()) {
            throw new IllegalArgumentException("keyArgName 不能为空");
        }
        this.keyArgName = keyArgName;
    }

    @Override
    public boolean verify(ProviderContext ctx) {
        Object rawKey = ctx.getCall().argument(keyArgName);
        if (rawKey == null) return false;
        String key = String.valueOf(rawKey);
        String value = ctx.getMemoryStore().getPreference(ctx.userId(), key);
        return value != null;
    }
}
