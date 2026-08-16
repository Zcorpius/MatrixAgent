package com.matrix.agent.presentation.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * ModelUiState 3 参构造器 + memoryDegraded 字段。
 *
 * <p>新 3 参构造器读回 memoryDegraded=true;旧 2 参构造器 delegate default false,
 * 所有现有调用点不需改。
 */
public final class ModelUiStateMemoryDegradedTest {

    @Test
    public void threeArgConstructorReadsBackMemoryDegradedTrue() {
        ModelUiState state = new ModelUiState(true, "ok", true);
        assertTrue("memoryDegraded=true 透传", state.memoryDegraded);
        assertTrue("loading 仍工作", state.loading);
        assertEquals("status 仍工作", "ok", state.status);
    }

    @Test
    public void threeArgConstructorReadsBackMemoryDegradedFalse() {
        ModelUiState state = new ModelUiState(false, "ok", false);
        assertFalse("memoryDegraded=false 透传", state.memoryDegraded);
    }

    @Test
    public void legacyTwoArgConstructorDefaultsMemoryDegradedToFalse() {
        ModelUiState state = new ModelUiState(true, "loading");
        assertFalse("旧 2 参构造器 default memoryDegraded=false(向后兼容)",
                state.memoryDegraded);
        assertTrue("loading 仍工作", state.loading);
        assertEquals("status 仍工作", "loading", state.status);
    }
}
