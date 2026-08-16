package com.matrix.agent.platform;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.matrix.agent.core.memory.MemoryStore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class SharedPreferencesMemoryStore implements MemoryStore {
    private static final String TAG = "MatrixAgent";
    private static final String FILE_NAME = "matrix_agent_memory";
    private static final String EPOCH_KEY = "__epoch__";
    private final SharedPreferences preferences;
    /**
     * data epoch 单一权威——内存镜像 (持久化到 SharedPreferences
     * 的 EPOCH_KEY)。启动时从 prefs 加载,bumpEpoch 时同步落盘 (commit 不 apply,防 clearUserData
     * 后立即 kill 进程丢 epoch)。Repository 不再维护独立 epochCounter,直接读本字段——
     * 跨进程重启不丢失,避免双来源失步。
     *
     * <p>本字段读写必须在 {@link #lock} 内——杜绝 check-then-act race。
     */
    private final AtomicLong epoch;

    /**
     * 私有锁,保护 epoch 自增 / epoch 校验 / clear / putPreference——所有"读 epoch + 写数据"
     * 的复合操作必须在本锁内原子完成。SharedPreferences 自身线程安全,但 epoch 校验 + putPreference
     * 是两个独立 SP 操作,中间可能被 bumpEpoch + clear 插入,导致偏好"复活"。
     */
    private final Object lock = new Object();

    public SharedPreferencesMemoryStore(Context context) {
        this.preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
        this.epoch = new AtomicLong(this.preferences.getLong(EPOCH_KEY, 0L));
    }

    /**
     * putPreference 改用 commit() 同步落盘——apply() 异步排队,与 clearUserData 的
     * clear(apply) 在 SP 队列里乱序,可能让 clear 完成后 putPreference 才落盘。
     *
     * <p>putPreference 仍是公开 API(测试 setup 直接调用),用 commit 同步落盘 + lock 保护。
     * Preference 写入频率低(每次 capability.save),commit 性能可接受。
     */
    @Override
    public void putPreference(String userId, String key, String value) {
        synchronized (lock) {
            preferences.edit().putString(storageKey(userId, key), value).commit();
        }
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

    /**
     * clear 改用 commit() 同步落盘 + lock 保护——apply() 异步排队有"clear 后写"风险。
     */
    @Override
    public void clear(String userId) {
        synchronized (lock) {
            SharedPreferences.Editor editor = preferences.edit();
            for (String key : preferences.getAll().keySet()) {
                if (key.startsWith(userId + ".")) {
                    editor.remove(key);
                }
            }
            editor.commit();
        }
    }

    // ---- data epoch 强一致清除 ----

    @Override
    public long currentEpoch() {
        return epoch.get();
    }

    /**
     * bumpEpoch 加 lock + commit 失败回滚——评审指出 commit() 失败时不能推进内存 epoch,
     * 否则 clearUserData 上层以为 epoch 已切,实际 prefs 没落盘,进程重启 epoch 丢失。
     */
    @Override
    public long bumpEpoch() {
        synchronized (lock) {
            long newEpoch = epoch.incrementAndGet();
            boolean ok = preferences.edit().putLong(EPOCH_KEY, newEpoch).commit();
            if (!ok) {
                epoch.decrementAndGet();
                Log.e(TAG, "[MemoryStore] bumpEpoch commit failed, epoch rolled back to "
                        + epoch.get());
                throw new IllegalStateException("SharedPreferences.commit failed in bumpEpoch");
            }
            Log.i(TAG, "[MemoryStore] bumpEpoch -> " + newEpoch + " (persisted)");
            return newEpoch;
        }
    }

    /**
     * 带 epoch 校验的 putPreference。
     *
     * <p>关键变化:整段在 {@link #lock} 内,且 putPreference 用 commit() 同步——确保
     * "epoch 校验 + 写数据"在同一个临界区,且与 {@link #bumpEpoch} / {@link #clearUserDataAndBump}
     * 互斥。杜绝评审 race:Thread A 校验通过 → Thread B clearUserData(commit 切 epoch + clear)
     * → Thread A putPreference(apply 异步排队,clear 完成后才落盘 → 偏好"复活")。
     */
    @Override
    public boolean putPreferenceChecked(String userId, String key, String value, long requestEpoch) {
        synchronized (lock) {
            long current = epoch.get();
            if (requestEpoch != current) {
                Log.w(TAG, "[MemoryStore] reject stale write userId=" + userId
                        + " key=" + key
                        + " requestEpoch=" + requestEpoch
                        + " currentEpoch=" + current
                        + " — clearUserData 已发生,陈旧写入被拒绝");
                return false;
            }
            boolean ok = preferences.edit().putString(storageKey(userId, key), value).commit();
            if (!ok) {
                Log.e(TAG, "[MemoryStore] putPreferenceChecked commit failed userId=" + userId
                        + " key=" + key);
                return false;
            }
            return true;
        }
    }

    /**
     * 原子"epoch 自增 + 清空两个用户数据"——单次 lock + 单次 SP Editor.commit()
     * 把三者打包为一个 SP 事务(SP edit() 的多次操作在 commit 时原子落盘)。
     *
     * <p>commit 失败回滚内存 epoch,抛 IllegalStateException 让 Repository.clearUserData 上层感知
     * (评审:"commit() 失败时不推进内存 epoch,并将清空失败反馈给上层")。
     */
    @Override
    public long clearUserDataAndBump(String userId1, String userId2) {
        synchronized (lock) {
            long newEpoch = epoch.incrementAndGet();
            SharedPreferences.Editor editor = preferences.edit();
            editor.putLong(EPOCH_KEY, newEpoch);
            int removed = 0;
            for (String key : preferences.getAll().keySet()) {
                if (key.startsWith(userId1 + ".") || key.startsWith(userId2 + ".")) {
                    editor.remove(key);
                    removed++;
                }
            }
            boolean ok = editor.commit();
            if (!ok) {
                epoch.decrementAndGet();
                Log.e(TAG, "[MemoryStore] clearUserDataAndBump commit failed, epoch rolled back to "
                        + epoch.get() + " removedPending=" + removed);
                throw new IllegalStateException(
                        "SharedPreferences.commit failed in clearUserDataAndBump");
            }
            Log.i(TAG, "[MemoryStore] clearUserDataAndBump -> epoch=" + newEpoch
                    + " clearedKeys=" + removed
                    + " (atomic commit)");
            return newEpoch;
        }
    }

    private static String storageKey(String userId, String key) {
        return userId + "." + key;
    }
}
