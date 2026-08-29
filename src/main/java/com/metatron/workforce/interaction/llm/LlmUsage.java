package com.metatron.workforce.interaction.llm;

/** Provider-reported token usage. -1 means the provider did not report the value. */
public record LlmUsage(long inputTokens, long outputTokens, long totalTokens) {
    public static final long UNKNOWN = -1L;
    public static final LlmUsage UNKNOWN_USAGE = new LlmUsage(UNKNOWN, UNKNOWN, UNKNOWN);

    public LlmUsage {
        if (inputTokens < UNKNOWN || outputTokens < UNKNOWN || totalTokens < UNKNOWN) {
            throw new IllegalArgumentException("token usage must be >= -1");
        }
    }

    public boolean known() {
        return inputTokens >= 0 || outputTokens >= 0 || totalTokens >= 0;
    }
}
