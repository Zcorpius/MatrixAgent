package com.matrix.agent.data.memory;

import android.util.Log;

import com.matrix.agent.core.memory.MemoryStore;
import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.db.MemoryRecordEntity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Room-backed MemoryStore 主路径实现。
 *
 * <p>替换旧版 {@link com.matrix.agent.platform.SharedPreferencesMemoryStore} 二元组存储,
 * 把 preference 数据搬到 memory_record 表(layer="preference"),与 SQLCipher 加密 + 4 表原子
 * clearUserData 协同。
 *
 * <p><b>设计决策</b>:
 * <ul>
 *   <li>epoch 持久化到特殊行 ({@code __system__}/__system__/preference/__epoch__),与
 *       preference 行同表同层,跨进程重启不丢——与 SP EPOCH_KEY 语义对齐。</li>
 *   <li>历史 zone-less preference 的 zone 默认 "global"(与 {@link com.matrix.agent.core.memory.MemoryScope#ofLegacy}
 *       语义对齐;真正 zone 维度隔离留后续版本)。</li>
 *   <li>{@code synchronized(lock)} 保护"epoch 校验 + 写数据"复合操作,与 SP 实现同款 check-then-act
 *       race 修复。</li>
 *   <li>{@link #clearUserDataAndBump(String, String)} 走 {@link TransactionRunner}(生产装配
 *       {@code database::runInTransaction})——bumpEpoch + delete×2 在同一 Room transaction
 *       原子完成,失败回滚 epoch + 抛异常反馈上层。</li>
 * </ul>
 *
 * <p><b>fail-open</b>:本类不在装配期 catch Room 异常——AppContainer.createMemoryStoreSafely
 * catch 后退回 SharedPreferencesMemoryStore(已有路径)。
 */
public final class RoomMemoryStore implements MemoryStore {
    private static final String TAG = "MatrixAgent";

    public static final String SYSTEM_USER = "__system__";
    public static final String SYSTEM_ZONE = "__system__";
    public static final String PREFERENCE_LAYER = "preference";
    public static final String DEFAULT_ZONE = "global";
    public static final String EPOCH_KEY = "__epoch__";

    /** 函数式事务包装器——生产传 {@code database::runInTransaction},测试传 {@code Runnable::run}。 */
    @FunctionalInterface
    public interface TransactionRunner {
        void runInTransaction(Runnable body);
    }

    private final MemoryRecordDao dao;
    private final TransactionRunner transactionRunner;
    private final AtomicLong epoch;
    private final Object lock = new Object();

    /** 单参构造器——无事务包装器(clearUserDataAndBump 退化为 best-effort 顺序执行)。 */
    public RoomMemoryStore(MemoryRecordDao dao) {
        this(dao, null);
    }

    public RoomMemoryStore(MemoryRecordDao dao, TransactionRunner transactionRunner) {
        this.dao = dao;
        this.transactionRunner = transactionRunner;
        long loaded = loadEpochFromRow(dao);
        this.epoch = new AtomicLong(loaded);
        Log.i(TAG, "[RoomMemoryStore] init loadedEpoch=" + loaded
                + " txRunner=" + (transactionRunner != null ? "present" : "absent"));
    }

    private static long loadEpochFromRow(MemoryRecordDao dao) {
        try {
            MemoryRecordEntity row = dao.queryByKey(
                    SYSTEM_USER, SYSTEM_ZONE, PREFERENCE_LAYER, EPOCH_KEY);
            if (row == null || row.value == null) return 0L;
            return Long.parseLong(row.value);
        } catch (Exception ex) {
            Log.w(TAG, "[RoomMemoryStore] loadEpoch FAILED, default 0L cause="
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return 0L;
        }
    }

    @Override
    public void putPreference(String userId, String key, String value) {
        synchronized (lock) {
            upsertPreferenceLocked(userId, key, value);
        }
    }

    private void upsertPreferenceLocked(String userId, String key, String value) {
        MemoryRecordEntity entity = new MemoryRecordEntity();
        entity.userId = userId;
        entity.zone = DEFAULT_ZONE;
        entity.layer = PREFERENCE_LAYER;
        entity.key = key;
        entity.value = value;
        entity.capturedAtMs = System.currentTimeMillis();
        dao.upsert(entity);
    }

    @Override
    public String getPreference(String userId, String key) {
        MemoryRecordEntity row = dao.queryByKey(userId, DEFAULT_ZONE, PREFERENCE_LAYER, key);
        return row == null ? null : row.value;
    }

    @Override
    public Map<String, String> getAllPreferences(String userId) {
        List<MemoryRecordEntity> rows = dao.queryByUserZoneLayer(userId, DEFAULT_ZONE, PREFERENCE_LAYER);
        Map<String, String> result = new LinkedHashMap<>();
        for (MemoryRecordEntity row : rows) {
            if (EPOCH_KEY.equals(row.key)) continue;  // 永不暴露 epoch 行给 caller
            result.put(row.key, row.value);
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public void clear(String userId) {
        synchronized (lock) {
            dao.deleteByUser(userId);
        }
    }

    @Override
    public long currentEpoch() {
        return epoch.get();
    }

    @Override
    public long bumpEpoch() {
        synchronized (lock) {
            long newEpoch = epoch.incrementAndGet();
            try {
                upsertEpochRowLocked(newEpoch);
                Log.i(TAG, "[RoomMemoryStore] bumpEpoch -> " + newEpoch + " (persisted)");
                return newEpoch;
            } catch (Exception ex) {
                epoch.decrementAndGet();
                Log.e(TAG, "[RoomMemoryStore] bumpEpoch FAILED, rolled back to " + epoch.get()
                        + " cause=" + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                throw new IllegalStateException("RoomMemoryStore.bumpEpoch persist failed", ex);
            }
        }
    }

    private void upsertEpochRowLocked(long newEpoch) {
        MemoryRecordEntity entity = new MemoryRecordEntity();
        entity.userId = SYSTEM_USER;
        entity.zone = SYSTEM_ZONE;
        entity.layer = PREFERENCE_LAYER;
        entity.key = EPOCH_KEY;
        entity.value = Long.toString(newEpoch);
        entity.capturedAtMs = System.currentTimeMillis();
        dao.upsert(entity);
    }

    @Override
    public boolean putPreferenceChecked(String userId, String key, String value, long requestEpoch) {
        synchronized (lock) {
            long current = epoch.get();
            if (requestEpoch != current) {
                Log.w(TAG, "[RoomMemoryStore] reject stale write userId=" + userId
                        + " key=" + key
                        + " requestEpoch=" + requestEpoch
                        + " currentEpoch=" + current
                        + " — clearUserData 已发生,陈旧写入被拒绝");
                return false;
            }
            try {
                upsertPreferenceLocked(userId, key, value);
                return true;
            } catch (Exception ex) {
                Log.e(TAG, "[RoomMemoryStore] putPreferenceChecked FAILED userId=" + userId
                        + " key=" + key + " cause=" + ex.getClass().getSimpleName()
                        + ": " + ex.getMessage());
                return false;
            }
        }
    }

    @Override
    public long clearUserDataAndBump(String userId1, String userId2) {
        synchronized (lock) {
            final long newEpoch = epoch.incrementAndGet();
            try {
                Runnable body = () -> {
                    upsertEpochRowLocked(newEpoch);
                    dao.deleteByUser(userId1);
                    dao.deleteByUser(userId2);
                };
                if (transactionRunner != null) {
                    transactionRunner.runInTransaction(body);
                } else {
                    body.run();
                }
                Log.i(TAG, "[RoomMemoryStore] clearUserDataAndBump -> epoch=" + newEpoch
                        + (transactionRunner != null ? " (atomic transaction)" : " (best-effort)"));
                return newEpoch;
            } catch (Exception ex) {
                epoch.decrementAndGet();
                Log.e(TAG, "[RoomMemoryStore] clearUserDataAndBump FAILED, epoch rolled back to "
                        + epoch.get() + " cause=" + ex.getClass().getSimpleName()
                        + ": " + ex.getMessage());
                throw new IllegalStateException(
                        "RoomMemoryStore.clearUserDataAndBump transaction failed", ex);
            }
        }
    }
}
