package com.matrix.agent.data;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.matrix.agent.core.capability.CapabilityRegistry;
import com.matrix.agent.core.identity.FallbackIntentClassifier;
import com.matrix.agent.core.identity.IntentClassifier;
import com.matrix.agent.core.identity.KeywordIntentClassifier;
import com.matrix.agent.platform.ModelApiClient;
import com.matrix.agent.platform.ModelConfig;
import com.matrix.agent.platform.ModelProviderPreset;

/**
 * V0.5.2-rev 评审 P2-2:ModelGatewayRepository.buildIntentClassifier / buildKeywordClassifier 契约。
 *
 * <p>ViewModel.saveAndApply / useDemoGateway 调用本方法,把 IntentClassifier 与 Gateway 同步切换。
 * 旧实现只切 Gateway,导致切换 Provider 后意图分类仍用旧配置 / Keyword。
 *
 * <p>本测试只验证 buildXxxClassifier 返回正确类型——SecureModelConfigStore 依赖 Android
 * SharedPreferences,JVM 无法构造,直接跳过 buildIntentClassifier 的 LlmIntentClassifier 内部
 * 构造异常(传合法 ModelConfig 即可,LlmIntentClassifier 构造器只 validate config)。
 */
public final class ModelGatewayRepositoryIntentClassifierTest {

    @Test
    public void buildIntentClassifierReturnsFallback() {
        ModelGatewayRepository repo = newRepo();
        ModelConfig config = validConfig();

        IntentClassifier classifier = repo.buildIntentClassifier(config);

        assertNotNull("buildIntentClassifier 必须返回非 null", classifier);
        assertTrue("LLM 配置 → FallbackIntentClassifier(LLM 主, Keyword 兜底)",
                classifier instanceof FallbackIntentClassifier);
    }

    @Test
    public void buildKeywordClassifierReturnsKeywordSingleton() {
        ModelGatewayRepository repo = newRepo();

        IntentClassifier classifier = repo.buildKeywordClassifier();

        assertSame("Demo 路径 → KeywordIntentClassifier 单例",
                KeywordIntentClassifier.INSTANCE, classifier);
    }

    @Test
    public void buildKeywordClassifierIdempotent() {
        ModelGatewayRepository repo = newRepo();
        IntentClassifier c1 = repo.buildKeywordClassifier();
        IntentClassifier c2 = repo.buildKeywordClassifier();
        assertSame("buildKeywordClassifier 必须每次返回同一单例", c1, c2);
    }

    private static ModelGatewayRepository newRepo() {
        return new ModelGatewayRepository(
                /* configStore */ null,
                new ModelApiClient(),
                CapabilityRegistry.createDemoRegistry(),
                /* memoryStore */ null,
                /* memoryRecaller */ null);
    }

    private static ModelConfig validConfig() {
        ModelProviderPreset preset = ModelProviderPreset.all().get(0);
        return preset.toConfig("test-api-key");
    }
}
