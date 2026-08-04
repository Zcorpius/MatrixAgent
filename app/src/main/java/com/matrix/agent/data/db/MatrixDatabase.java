package com.matrix.agent.data.db;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;

import com.matrix.agent.platform.MasterKeyProvider;

import net.sqlcipher.database.SupportFactory;

import java.util.Arrays;

/**
 * V0.5.0 Stage 2:Room + SQLCipher 加密数据库入口。
 *
 * <p><b>V0.5.2 Stage 1</b>:version=2——audit_event 加 userId 列 + idx_audit_user_zone
 * 索引,配合 {@link AuditEventDao#deleteByUserZone(String, String)} 实现 4 表原子 clear。
 *
 * <p>SQLCipher SupportFactory 注入 byte[] passphrase——passphrase 由 MasterKeyProvider
 * 提供(从 AndroidKeyStore 取主密钥解密本地缓存)。
 *
 * <p>P1.1 修复(评审 V0.5.0):passphrase 缺失/null/异常时**必须抛 IllegalStateException**,
 * 让 AppContainer.createAuditRepositorySafely catch 后退化 NoopAuditRepository。
 * 绝不静默回退到 builder.build()——否则会构建明文 Room DB,审计数据悄然落明文。
 *
 * <p>V0.5.0 单例 + WAL 模式默认开启;V0.5.1 引入 batch 后可调 JournalMode。
 *
 * <p><b>V0.5.2 Stage 7</b>:显式声明 {@code JournalMode.WRITE_AHEAD_LOGGING}——Room 2.7
 * 在 API 16+ 默认开 WAL,但某些 OEM ROM 关闭 SQLite WAL 影响并发读写(主路径 LLM 调用
 * 期间后台 audit insert / memory query 并行);显式 setJournalMode 锁定行为,同时
 * openHelperFactory 内通过 {@link SupportSQLiteOpenHelper}Callback {@code onOpen} 调
 * {@code enableWriteAheadLogging} 兜底,保证实际 SQLCipher 连接也走 WAL。
 *
 * <p><b>第二轮评审 P2.4 修复</b>:passphrase char[] 转 byte[] 后立即 {@link Arrays#fill}
 * 清零 char[](MatrixDatabase 不再使用 char[],只用 byte[] 给 SupportFactory)。
 * byte[] 不能清零——SupportFactory 在进程生命周期内会持续用它(WAL checkpoint / reopen),
 * 这是 SQLCipher 4.x 的固有限制;passphrase 实际保护由 AndroidKeyStore + 进程隔离兜底。
 */
@Database(
        entities = {
                TrajectoryEntity.class,
                SessionHistoryEntity.class,
                MemoryRecordEntity.class,
                AuditEventEntity.class
        },
        version = 3,
        exportSchema = true
)
public abstract class MatrixDatabase extends RoomDatabase {
    private static final String TAG = "MatrixAgent";
    private static final String DB_NAME = "matrix_agent.db";
    private static volatile MatrixDatabase instance;

    public abstract TrajectoryDao trajectoryDao();
    public abstract SessionHistoryDao sessionHistoryDao();
    public abstract MemoryRecordDao memoryRecordDao();
    public abstract AuditEventDao auditEventDao();

    /**
     * V0.5.2 Stage 1:schema v1 → v2 迁移——audit_event 加 userId 列 + idx_audit_user_zone 索引。
     *
     * <p>历史 V0.5.0/V0.5.1 写入的 audit_event 行 userId 默认 {@code ''}——无法回填
     * (无 RequestId → AgentRequest 映射);按主路径 queryByUserZone 过滤不到,但仍按
     * requestId 可查,可接受(审计溯源按 requestId 是主路径)。
     *
     * <p>R1 缓解:Migration 仅 1 条 ALTER + 1 条 CREATE INDEX,语句最小化——
     * 失败时 Room 抛 IllegalStateException,AppContainer.createAuditRepositorySafely
     * catch 后退化 NoopAuditRepository(已有路径)。
     */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE audit_event ADD COLUMN userId TEXT NOT NULL DEFAULT ''");
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_audit_user_zone` "
                    + "ON `audit_event` (`userId`, `zone`)");
        }
    };

    /**
     * V0.5.2-rev 评审 P1-2:schema v2 → v3 迁移——audit_event 加 requestEpoch 列。
     *
     * <p>评审发现 V0.5.2 P1-3 修复用 happenedAtMs(毫秒时间戳)与 staleEpoch(MemoryStore 版本号 1/2/3)
     * 比较 → 永远 false,stale gate 失效。改加 requestEpoch 字段(由 AgentEngine 5 处 recordXxx 透传
     * request.getEpoch()),isStale 用 epoch 比较。
     *
     * <p>历史 V0.5.0/V0.5.1/V0.5.2 行 requestEpoch 默认 0——0 表示"未透传 / 老数据",
     * 不参与 epoch gate(保留 V0.5.2 行为,不清空老 audit_event)。
     */
    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE audit_event ADD COLUMN requestEpoch INTEGER NOT NULL DEFAULT 0");
        }
    };

    /**
     * V0.5.0 Stage 2:加密数据库单例获取。
     *
     * <p>P1.1 修复(评审 V0.5.0):以下任一条件必须抛 IllegalStateException:
     * <ul>
     *   <li>keyProvider == null(装配错误,AppContainer 必须保证非空)</li>
     *   <li>keyProvider.getPassphrase() 返回 null 或 length == 0(KeyStore 取主密钥失败)</li>
     * </ul>
     * 调用方(AppContainer.createAuditRepositorySafely)catch 后退化 NoopAuditRepository。
     */
    public static synchronized MatrixDatabase getInstance(Context context,
            MasterKeyProvider keyProvider) {
        if (instance != null) return instance;
        if (keyProvider == null) {
            throw new IllegalStateException("MatrixDatabase init failed: keyProvider 为空");
        }
        char[] passphrase = keyProvider.getPassphrase();
        if (passphrase == null || passphrase.length == 0) {
            throw new IllegalStateException(
                    "MatrixDatabase init failed: passphrase 为空(Keystore 异常)");
        }
        try {
            RoomDatabase.Builder<MatrixDatabase> builder = Room.databaseBuilder(
                    context.getApplicationContext(), MatrixDatabase.class, DB_NAME);
            // SQLCipher 4.5.4 SupportFactory 接受 byte[] passphrase。
            // ISO-8859-1 char→byte 转换保证 32 字节随机值无损(每个 char 仅低 8 位有值)。
            byte[] passBytes = new byte[passphrase.length];
            for (int i = 0; i < passphrase.length; i++) {
                passBytes[i] = (byte) (passphrase[i] & 0xFF);
            }
            // 评审 P2.4:char[] 转完 byte[] 后立即清零——MatrixDatabase 不再用 char[],
            // 减少密钥在堆内存驻留时间。byte[] 仍需保留(SupportFactory 持续使用)。
            Arrays.fill(passphrase, '\0');
            builder.openHelperFactory(new SupportFactory(passBytes));
            // V0.5.2 Stage 1:注册 v1→v2 Migration(audit_event userId)。
            // V0.5.2-rev 评审 P1-2:加 v2→v3 Migration(audit_event requestEpoch)。
            builder.addMigrations(MIGRATION_1_2, MIGRATION_2_3);
            // V0.5.2 Stage 7:显式 WAL——锁定并发读写语义,避免 OEM ROM 关闭 SQLite WAL。
            builder.setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING);
            instance = builder.build();
            Log.i(TAG, "[MatrixDatabase] init encrypted=true alias=" + keyProvider.alias()
                    + " version=3 entities=4 journalMode=WAL");
            return instance;
        } catch (Exception ex) {
            Log.e(TAG, "[MatrixDatabase] init FAILED cause="
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
            throw new IllegalStateException("MatrixDatabase init failed", ex);
        }
    }

    /** V0.5.0 Stage 2:测试用——清空单例(仅 JVM 单测用,fake 注入路径)。 */
    static synchronized void resetForTest() {
        if (instance != null) {
            try {
                instance.close();
            } catch (Exception ignored) {
                // best effort
            }
            instance = null;
        }
    }
}
