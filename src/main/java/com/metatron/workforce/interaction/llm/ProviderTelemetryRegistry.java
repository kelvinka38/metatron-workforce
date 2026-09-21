package com.metatron.workforce.interaction.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Live in-process provider telemetry used for adaptive routing.
 * Tracks measured concurrency/latency/success/failure, provider-reported token usage and quota hints.
 * Cost is calculated only when explicit environment pricing is configured; unknown cost is never fabricated.
 */
public final class ProviderTelemetryRegistry {
    private static final Duration RATE_LIMIT_COOLDOWN = Duration.ofSeconds(60);
    private static final Duration FAILURE_COOLDOWN = Duration.ofSeconds(20);
    private static final double EWMA_ALPHA = 0.25d;

    private final ConcurrentMap<LlmProvider, MutableState> states = new ConcurrentHashMap<>();
    private final Map<LlmProvider, Pricing> pricing;

    public ProviderTelemetryRegistry() {
        this(pricingFromEnvironment());
    }

    ProviderTelemetryRegistry(Map<LlmProvider, Pricing> pricing) {
        this.pricing = Map.copyOf(Objects.requireNonNull(pricing, "pricing"));
    }

    public long begin(LlmProvider provider) {
        MutableState state = state(provider);
        synchronized (state) {
            state.activeRequests++;
            state.totalCalls++;
        }
        return System.nanoTime();
    }

    public void success(LlmProvider provider, long startedNanos, LlmResponse response) {
        Objects.requireNonNull(response, "response");
        MutableState state = state(provider);
        long latencyMillis = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        synchronized (state) {
            state.activeRequests = Math.max(0, state.activeRequests - 1);
            state.successes++;
            state.consecutiveFailures = 0;
            state.cooldownUntil = null;
            state.ewmaLatencyMillis = state.ewmaLatencyMillis < 0
                    ? latencyMillis
                    : Math.round((EWMA_ALPHA * latencyMillis) + ((1.0d - EWMA_ALPHA) * state.ewmaLatencyMillis));
            LlmUsage usage = response.usage();
            if (usage.inputTokens() >= 0) state.totalInputTokens += usage.inputTokens();
            if (usage.outputTokens() >= 0) state.totalOutputTokens += usage.outputTokens();
            if (usage.totalTokens() >= 0) state.totalTokens += usage.totalTokens();
            long cost = estimateCostMicros(provider, usage);
            if (cost >= 0) {
                state.costKnown = true;
                state.totalEstimatedCostMicros += cost;
                state.lastEstimatedCostMicros = cost;
            }
            state.remainingRequests = parseLong(response.providerTelemetry().get("remaining_requests"), state.remainingRequests);
            state.remainingTokens = parseLong(response.providerTelemetry().get("remaining_tokens"), state.remainingTokens);
            state.lastSuccessAt = Instant.now();
            state.lastFailure = "";
        }
    }

    public void failure(LlmProvider provider, long startedNanos, RuntimeException failure) {
        Objects.requireNonNull(failure, "failure");
        MutableState state = state(provider);
        long latencyMillis = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        synchronized (state) {
            state.activeRequests = Math.max(0, state.activeRequests - 1);
            state.failures++;
            state.consecutiveFailures++;
            state.ewmaLatencyMillis = state.ewmaLatencyMillis < 0
                    ? latencyMillis
                    : Math.round((EWMA_ALPHA * latencyMillis) + ((1.0d - EWMA_ALPHA) * state.ewmaLatencyMillis));
            String message = String.valueOf(failure.getMessage());
            state.lastFailure = message;
            state.lastFailureAt = Instant.now();
            if (isRateLimit(message)) {
                state.cooldownUntil = Instant.now().plus(RATE_LIMIT_COOLDOWN);
                state.remainingRequests = 0L;
            } else if (isPermanentCreditFailure(message)) {
                // A 400 insufficient-credit or similar account-level rejection is not a useful
                // immediate retry candidate either -- it will not resolve itself within seconds,
                // unlike a transient timeout/5xx. Cool down on the very first occurrence instead of
                // waiting for two consecutive failures, reusing the exact same bounded mechanism.
                state.cooldownUntil = Instant.now().plus(RATE_LIMIT_COOLDOWN);
            } else if (state.consecutiveFailures >= 2) {
                state.cooldownUntil = Instant.now().plus(FAILURE_COOLDOWN);
            }
        }
    }

    public Snapshot snapshot(LlmProvider provider) {
        MutableState state = state(provider);
        synchronized (state) {
            boolean coolingDown = state.cooldownUntil != null && state.cooldownUntil.isAfter(Instant.now());
            return new Snapshot(
                    provider,
                    state.activeRequests,
                    state.totalCalls,
                    state.successes,
                    state.failures,
                    state.consecutiveFailures,
                    state.ewmaLatencyMillis,
                    state.totalInputTokens,
                    state.totalOutputTokens,
                    state.totalTokens,
                    state.remainingRequests,
                    state.remainingTokens,
                    state.costKnown,
                    state.totalEstimatedCostMicros,
                    state.lastEstimatedCostMicros,
                    coolingDown,
                    state.cooldownUntil,
                    state.lastSuccessAt,
                    state.lastFailureAt,
                    state.lastFailure);
        }
    }

    public Map<LlmProvider, Snapshot> snapshots() {
        EnumMap<LlmProvider, Snapshot> result = new EnumMap<>(LlmProvider.class);
        for (LlmProvider provider : LlmProvider.values()) result.put(provider, snapshot(provider));
        return Map.copyOf(result);
    }

    private MutableState state(LlmProvider provider) {
        Objects.requireNonNull(provider, "provider");
        return states.computeIfAbsent(provider, ignored -> new MutableState());
    }

    private long estimateCostMicros(LlmProvider provider, LlmUsage usage) {
        Pricing configured = pricing.get(provider);
        if (configured == null || !configured.known()) return -1L;
        if (usage.inputTokens() < 0 || usage.outputTokens() < 0) return -1L;
        BigDecimal micros = BigDecimal.valueOf(usage.inputTokens()).multiply(configured.inputUsdPerMillion())
                .add(BigDecimal.valueOf(usage.outputTokens()).multiply(configured.outputUsdPerMillion()));
        return micros.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static boolean isRateLimit(String message) {
        if (message == null) return false;
        String value = message.toLowerCase(java.util.Locale.ROOT);
        return value.contains("429") || value.contains("rate limit") || value.contains("quota");
    }

    /**
     * True for a permanent account-level rejection (insufficient/no credit, exhausted credit
     * balance) that a same-second immediate retry cannot resolve, unlike a transient timeout/5xx.
     */
    private static boolean isPermanentCreditFailure(String message) {
        if (message == null) return false;
        String value = message.toLowerCase(java.util.Locale.ROOT);
        return value.contains("insufficient credit") || value.contains("insufficient_credit")
                || value.contains("no credit") || value.contains("credit balance")
                || (value.contains("400") && value.contains("credit"));
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Long.parseLong(value.trim()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static Map<LlmProvider, Pricing> pricingFromEnvironment() {
        EnumMap<LlmProvider, Pricing> result = new EnumMap<>(LlmProvider.class);
        for (LlmProvider provider : LlmProvider.values()) {
            String prefix = "METATRON_" + provider.name() + "_";
            BigDecimal input = decimalEnv(prefix + "INPUT_USD_PER_MILLION");
            BigDecimal output = decimalEnv(prefix + "OUTPUT_USD_PER_MILLION");
            if (input != null && output != null) result.put(provider, new Pricing(input, output));
        }
        return Map.copyOf(result);
    }

    private static BigDecimal decimalEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return null;
        try {
            BigDecimal parsed = new BigDecimal(value.trim());
            return parsed.signum() >= 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public record Snapshot(
            LlmProvider provider,
            int activeRequests,
            long totalCalls,
            long successes,
            long failures,
            int consecutiveFailures,
            long ewmaLatencyMillis,
            long totalInputTokens,
            long totalOutputTokens,
            long totalTokens,
            long remainingRequests,
            long remainingTokens,
            boolean costKnown,
            long totalEstimatedCostMicros,
            long lastEstimatedCostMicros,
            boolean coolingDown,
            Instant cooldownUntil,
            Instant lastSuccessAt,
            Instant lastFailureAt,
            String lastFailure) {
        public Snapshot {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(lastFailure, "lastFailure");
        }

        public boolean quotaExhausted() {
            return remainingRequests == 0L || remainingTokens == 0L;
        }
    }

    record Pricing(BigDecimal inputUsdPerMillion, BigDecimal outputUsdPerMillion) {
        Pricing {
            Objects.requireNonNull(inputUsdPerMillion, "inputUsdPerMillion");
            Objects.requireNonNull(outputUsdPerMillion, "outputUsdPerMillion");
        }
        boolean known() { return inputUsdPerMillion.signum() >= 0 && outputUsdPerMillion.signum() >= 0; }
    }

    private static final class MutableState {
        int activeRequests;
        long totalCalls;
        long successes;
        long failures;
        int consecutiveFailures;
        long ewmaLatencyMillis = -1L;
        long totalInputTokens;
        long totalOutputTokens;
        long totalTokens;
        long remainingRequests = -1L;
        long remainingTokens = -1L;
        boolean costKnown;
        long totalEstimatedCostMicros;
        long lastEstimatedCostMicros = -1L;
        Instant cooldownUntil;
        Instant lastSuccessAt;
        Instant lastFailureAt;
        String lastFailure = "";
    }
}
