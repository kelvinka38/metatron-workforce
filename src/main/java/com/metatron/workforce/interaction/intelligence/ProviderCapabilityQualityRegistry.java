package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Evidence-bearing quality fit used by provider routing.
 *
 * <p>No provider is assumed better by brand. Unknown capability quality is neutral. Production may
 * seed measured values through environment variables such as METATRON_OPENAI_QUALITY_CODING=0.92;
 * runtime/evaluation systems may record newer measured scores with an evidence reference.</p>
 */
public final class ProviderCapabilityQualityRegistry {
    public enum CapabilityClass { GENERAL, SEMANTIC, ANALYSIS, PLANNING, CODING, CREATIVE }

    private final ConcurrentMap<Key, Measurement> measurements = new ConcurrentHashMap<>();

    public ProviderCapabilityQualityRegistry() {
        this(System.getenv());
    }

    ProviderCapabilityQualityRegistry(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        for (LlmProvider provider : LlmProvider.values()) {
            for (CapabilityClass capabilityClass : CapabilityClass.values()) {
                String name = "METATRON_" + provider.name() + "_QUALITY_" + capabilityClass.name();
                String raw = environment.get(name);
                if (raw == null || raw.isBlank()) continue;
                try {
                    double score = Double.parseDouble(raw.trim());
                    if (Double.isFinite(score) && score >= 0.0d && score <= 1.0d) {
                        measurements.put(new Key(provider, capabilityClass),
                                new Measurement(score, "environment:" + name));
                    }
                } catch (NumberFormatException ignored) { }
            }
        }
    }

    public void record(LlmProvider provider, String requiredCapability, double score, String evidenceReference) {
        Objects.requireNonNull(provider, "provider");
        if (!Double.isFinite(score) || score < 0.0d || score > 1.0d) {
            throw new IllegalArgumentException("quality score must be within [0,1]");
        }
        String evidence = evidenceReference == null ? "" : evidenceReference.trim();
        if (evidence.isBlank()) throw new IllegalArgumentException("quality evidence reference required");
        measurements.put(new Key(provider, classify(requiredCapability)), new Measurement(score, evidence));
    }

    public Snapshot snapshot(LlmProvider provider, String requiredCapability) {
        Objects.requireNonNull(provider, "provider");
        CapabilityClass capabilityClass = classify(requiredCapability);
        Measurement measurement = measurements.get(new Key(provider, capabilityClass));
        if (measurement == null && capabilityClass != CapabilityClass.GENERAL) {
            measurement = measurements.get(new Key(provider, CapabilityClass.GENERAL));
        }
        return measurement == null
                ? new Snapshot(provider, capabilityClass, false, 0.5d, "")
                : new Snapshot(provider, capabilityClass, true, measurement.score(), measurement.evidenceReference());
    }

    public static CapabilityClass classify(String requiredCapability) {
        String value = requiredCapability == null ? "" : requiredCapability.trim().toLowerCase(Locale.ROOT);
        if (value.contains("semantic") || value.contains("normaliz")) return CapabilityClass.SEMANTIC;
        if (value.contains("code") || value.contains("coding") || value.contains("software")
                || value.contains("repository") || value.contains("engineering")) return CapabilityClass.CODING;
        if (value.contains("plan") || value.contains("orchestrat") || value.contains("schedule")) return CapabilityClass.PLANNING;
        if (value.contains("creative") || value.contains("composer") || value.contains("artist")
                || value.contains("copywriter") || value.contains("design")) return CapabilityClass.CREATIVE;
        if (value.contains("analysis") || value.contains("analy") || value.contains("reason")
                || value.contains("research") || value.contains("audit") || value.contains("cognit")) {
            return CapabilityClass.ANALYSIS;
        }
        return CapabilityClass.GENERAL;
    }

    public Map<LlmProvider, Snapshot> snapshots(String requiredCapability) {
        EnumMap<LlmProvider, Snapshot> result = new EnumMap<>(LlmProvider.class);
        for (LlmProvider provider : LlmProvider.values()) result.put(provider, snapshot(provider, requiredCapability));
        return Map.copyOf(result);
    }

    public record Snapshot(
            LlmProvider provider,
            CapabilityClass capabilityClass,
            boolean known,
            double score,
            String evidenceReference) {
        public Snapshot {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(capabilityClass, "capabilityClass");
            evidenceReference = evidenceReference == null ? "" : evidenceReference;
        }
    }

    private record Key(LlmProvider provider, CapabilityClass capabilityClass) { }
    private record Measurement(double score, String evidenceReference) { }
}
