package com.matrix.agent.core.token;

/**
 * V0.5.0 Stage 5:Char-based fallback Tokenizer——纯 text.length(),无 native 依赖。
 *
 * <p>V0.5.0 默认装配实现。与 V0.4.x AgentEngine.estimateConversationChars 行为等价,
 * 不改变 289 V0.4.3 测试的预算判断结果。
 *
 * <p>V0.5.1 切换为 JtokkitTokenizer 时,本类保留作 fallback——provider unknown 或
 * JtokkitTokenizer 初始化失败时退化为 char-based。
 */
public final class CharFallbackTokenizer implements Tokenizer {
    public static final CharFallbackTokenizer INSTANCE = new CharFallbackTokenizer();

    private CharFallbackTokenizer() {}

    @Override
    public int count(String text) {
        return text == null ? 0 : text.length();
    }

    @Override
    public String encoding() {
        return "char-fallback";
    }

    @Override
    public String encodeAndBack(String text) {
        return text == null ? "" : text;
    }
}
