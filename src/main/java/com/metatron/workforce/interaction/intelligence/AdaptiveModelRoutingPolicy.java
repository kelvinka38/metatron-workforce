package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Request-aware model selection within an already selected provider.
 *
 * <p>Production can configure FAST / ANALYZE / DEEP models independently per provider. Missing
 * tiers fall back to the currently configured provider model, so enabling this policy never invents
 * an unavailable model name.</p>
 */
public final class AdaptiveModelRoutingPolicy implements IntelligenceModelRoutingPolicy {
    public enum Tier { FAST, ANALYZE, DEEP }

    private final Map<LlmProvider, ModelProfile> profiles;

    public AdaptiveModelRoutingPolicy(Map<LlmProvider, ModelProfile> profiles) {
        Objects.requireNonNull(profiles, "profiles");
        EnumMap<LlmProvider, ModelProfile> copy = new EnumMap<>(LlmProvider.class);
        profiles.forEach((provider, profile) -> copy.put(
                Objects.requireNonNull(provider, "provider"), Objects.requireNonNull(profile, "profile")));
        this.profiles = Map.copyOf(copy);
    }

    public static AdaptiveModelRoutingPolicy fromEnvironment(Function<LlmProvider, String> defaultModelSelector) {
        Objects.requireNonNull(defaultModelSelector, "defaultModelSelector");
        EnumMap<LlmProvider, ModelProfile> profiles = new EnumMap<>(LlmProvider.class);
        for (LlmProvider provider : LlmProvider.values()) {
            String fallback = requireModel(defaultModelSelector.apply(provider));
            String prefix = environmentPrefix(provider);
            String fast = modelEnv(prefix + "_FAST_MODEL", fallback);
            String analyze = modelEnv(prefix + "_ANALYZE_MODEL", fallback);
            String deep = modelEnv(prefix + "_DEEP_MODEL", fallback);
            profiles.put(provider, new ModelProfile(fast, analyze, deep));
        }
        return new AdaptiveModelRoutingPolicy(profiles);
    }

    @Override
    public String select(LlmProvider provider, IntelligenceRequest request) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(request, "request");
        ModelProfile profile = profiles.get(provider);
        if (profile == null) throw new IllegalStateException("model profile is not configured for provider: " + provider);
        return switch (tier(request)) {
            case FAST -> profile.fastModel();
            case ANALYZE -> profile.analyzeModel();
            case DEEP -> profile.deepModel();
        };
    }

    public Tier tier(IntelligenceRequest request) {
        Objects.requireNonNull(request, "request");
        String latency = lower(request.latencyBudget());
        String cost = lower(request.costBudget());

        if (latency.contains("extended") || latency.contains("deep")
                || cost.contains("deep") || cost.contains("max-quality") || cost.contains("quality-first")) {
            return Tier.DEEP;
        }

        boolean explicitFast = latency.contains("fast") || latency.contains("low-latency")
                || cost.contains("cheap") || cost.contains("economy") || cost.contains("minimal-cost");
        if (explicitFast) return Tier.FAST;

        if (latency.contains("analysis") || cost.contains("expanded")
                || minimumAnalyzeCapability(request.requiredCapability())) {
            return Tier.ANALYZE;
        }
        return Tier.FAST;
    }

    public ModelProfile profile(LlmProvider provider) {
        ModelProfile profile = profiles.get(provider);
        if (profile == null) throw new IllegalArgumentException("model profile not configured: " + provider);
        return profile;
    }

    private static boolean minimumAnalyzeCapability(String capability) {
        ProviderCapabilityQualityRegistry.CapabilityClass capabilityClass =
                ProviderCapabilityQualityRegistry.classify(capability);
        return switch (capabilityClass) {
            case ANALYSIS, PLANNING, CODING, CREATIVE -> true;
            case GENERAL, SEMANTIC -> false;
        };
    }

    private static String environmentPrefix(LlmProvider provider) {
        return switch (provider) {
            case OPENAI -> "OPENAI";
            case GOOGLE -> "GEMINI";
            case ANTHROPIC -> "ANTHROPIC";
            case OLLAMA -> "OLLAMA";
        };
    }


    private static String modelEnv(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String requireModel(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("default model must not be blank");
        return value.trim();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public record ModelProfile(String fastModel, String analyzeModel, String deepModel) {
        public ModelProfile {
            fastModel = requireModel(fastModel);
            analyzeModel = requireModel(analyzeModel);
            deepModel = requireModel(deepModel);
        }
    }
}
