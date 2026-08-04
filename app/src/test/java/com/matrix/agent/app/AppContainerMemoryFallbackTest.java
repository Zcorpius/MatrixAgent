package com.matrix.agent.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com.matrix.agent.core.memory.InMemoryMemoryStore;
import com.matrix.agent.core.memory.MemoryStore;
import com.matrix.agent.platform.SharedPreferencesMemoryStore;

/**
 * V0.5.3 评审 P1-3:database=null 时 createMemoryStoreSafely 退到 InMemoryMemoryStore
 * (非持久化)+ memoryDegraded=true,不再退到 SharedPreferencesMemoryStore(明文 XML)。
 *
 * <p>本测试用 package-private 静态方法直接调用,无需实例化 AppContainer(避免 Context 依赖)。
 * database=null 路径不调 SharedPreferences,可 JVM 测;Room 正常路径需 Android Robolectric,
 * 不在 V0.5.3 范围(由 androidTest MemorySemanticSaveIntegrationTest 间接覆盖)。
 */
public final class AppContainerMemoryFallbackTest {

    /**
     * database=null → 返回 InMemoryMemoryStore 实例 + memoryDegradedRef.get()==true。
     */
    @Test
    public void nullDatabaseReturnsInMemoryMemoryStoreAndSetsFlag() {
        AtomicBoolean degradedRef = new AtomicBoolean(false);
        MemoryStore store = AppContainer.createMemoryStoreSafely(null, null, degradedRef);
        assertTrue("database=null 必须返回 InMemoryMemoryStore,actual=" + store.getClass(),
                store instanceof InMemoryMemoryStore);
        assertTrue("database=null 必须设置 memoryDegraded=true", degradedRef.get());
    }

    /**
     * database=null → 返回的 store **不**是 SharedPreferencesMemoryStore(明文 XML)。
     * 这是 V0.5.3 P1-3 的核心修复——SQLCipher 加密的目的不能被静默绕过。
     */
    @Test
    public void nullDatabaseDoesNotReturnSharedPreferencesMemoryStore() {
        AtomicBoolean degradedRef = new AtomicBoolean(false);
        MemoryStore store = AppContainer.createMemoryStoreSafely(null, null, degradedRef);
        assertTrue("database=null 不应返回 SharedPreferencesMemoryStore(明文 XML)",
                !(store instanceof SharedPreferencesMemoryStore));
    }

    /**
     * database=null → memoryDegradedRef 未传入(false)时,调用后必须变 true。
     * 验证 set 操作真发生,而不是默认 true。
     */
    @Test
    public void nullDatabaseFlipsFlagFromFalseToTrue() {
        AtomicBoolean degradedRef = new AtomicBoolean(false);
        assertEquals("初始 false", false, degradedRef.get());
        AppContainer.createMemoryStoreSafely(null, null, degradedRef);
        assertEquals("调用后必须 true", true, degradedRef.get());
    }

    /**
     * 旧 2 参重载(@Deprecated)仍工作,内部新建 AtomicBoolean 丢弃。
     * 向后兼容验证——V0.5.2-rev 测试可能仍调旧重载。
     */
    @Test
    public void deprecatedTwoArgOverloadStillWorks() {
        // 旧 2 参重载不暴露 degradedRef,只验证不抛 + 返回 InMemoryMemoryStore
        MemoryStore store = AppContainer.createMemoryStoreSafely(null, null);
        assertTrue("旧 2 参重载也应返回 InMemoryMemoryStore",
                store instanceof InMemoryMemoryStore);
    }
}
