package com.matrix.agent.core.memory;

import android.util.Log;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryMemoryStore implements MemoryStore {
    private static final String TAG = "MatrixAgent";
    private final Map<String, Map<String, String>> values = new LinkedHashMap<>();
    /**
     * 第八轮 P1.3 / 第九轮 P1.1:data epoch——{@link #bumpEpoch()} 自增,
     * {@link #putPreferenceChecked} 严格校验。InMemoryMemoryStore 不持久化,重启回到 epoch=0
     * (与 Repository 重建后二者同步,无失步风险——生产路径用 {@link SharedPreferencesMemoryStore}
     * 跨进程持久化)。
     */
    private final AtomicLong epoch = new AtomicLong(0L);

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

    // ---- 第八轮 P1.3: data epoch 强一致清除 ----

    @Override
    public long currentEpoch() {
        return epoch.get();
    }

    /**
     * 第十轮 P1 修正:bumpEpoch 加 synchronized——与 {@link #putPreferenceChecked} / {@link #clear}
     * 共用同一把锁,消除 check-then-act race(旧任务在 putPreferenceChecked 内 epoch 校验通过后,
     * bumpEpoch 自增 epoch + clear 清数据,旧任务 putPreference 仍会写入已清的 values)。
     */
    @Override
    public synchronized long bumpEpoch() {
        long newEpoch = epoch.incrementAndGet();
        Log.i(TAG, "[MemoryStore] bumpEpoch -> " + newEpoch);
        return newEpoch;
    }

    /**
     * 第十轮 P1:原子"清空两个用户数据 + epoch 自增"——单次 synchronized 内完成,杜绝 race。
     */
    @Override
    public synchronized long clearUserDataAndBump(String userId1, String userId2) {
        long newEpoch = epoch.incrementAndGet();
        values.remove(userId1);
        values.remove(userId2);
        Log.i(TAG, "[MemoryStore] clearUserDataAndBump -> epoch=" + newEpoch
                + " clearedUsers=[" + userId1 + ", " + userId2 + "]");
        return newEpoch;
    }

    /**
     * 第八轮 P1.3 / 第九轮 P1.1:带 epoch 校验的 putPreference。
     *
     * <p>若 {@code requestEpoch != currentEpoch()},说明调用方在 clearUserData 之前启动,
     * 此时写入会把刚清的数据写回——拒绝,返回 false。
     *
     * <p>第九轮 P1.1 修正:删除"epoch=0 兼容路径"——评审指出 0L 静默放行会让安全语义被绕过
     * (任何调用方传 0 都接受)。Repository 必须读 {@link #currentEpoch()} 显式注入当前值。
     */
    @Override
    public synchronized boolean putPreferenceChecked(String userId, String key, String value,
            long requestEpoch) {
        long current = epoch.get();
        if (requestEpoch != current) {
            Log.w(TAG, "[MemoryStore] reject stale write userId=" + userId
                    + " key=" + key
                    + " requestEpoch=" + requestEpoch
                    + " currentEpoch=" + current
                    + " — clearUserData 已发生,陈旧写入被拒绝");
            return false;
        }
        putPreference(userId, key, value);
        return true;
    }
}
