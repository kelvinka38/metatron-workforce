package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Durable state boundary for observed intelligence-routing feedback. */
public interface IntelligenceRoutingFeedbackStore {
    Snapshot load();
    void save(Snapshot snapshot);

    record Snapshot(List<Aggregate> aggregates, List<FeedbackEvent> events) {
        public Snapshot {
            aggregates = aggregates == null ? List.of() : List.copyOf(aggregates);
            events = events == null ? List.of() : List.copyOf(events);
        }
        public static Snapshot empty() { return new Snapshot(List.of(), List.of()); }
    }

    record Aggregate(
            LlmProvider provider,
            ProviderCapabilityQualityRegistry.CapabilityClass capabilityClass,
            double totalWeight,
            double weightedScoreSum,
            long sampleCount,
            String lastEvidenceReference,
            Instant updatedAt) {
        public Aggregate {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(capabilityClass, "capabilityClass");
            if (!Double.isFinite(totalWeight) || totalWeight < 0) throw new IllegalArgumentException("totalWeight invalid");
            if (!Double.isFinite(weightedScoreSum) || weightedScoreSum < 0) throw new IllegalArgumentException("weightedScoreSum invalid");
            if (sampleCount < 0) throw new IllegalArgumentException("sampleCount invalid");
            lastEvidenceReference = lastEvidenceReference == null ? "" : lastEvidenceReference;
            Objects.requireNonNull(updatedAt, "updatedAt");
        }

        public double effectiveScore() {
            return (1.5d + weightedScoreSum) / (3.0d + totalWeight);
        }
    }

    record FeedbackEvent(
            String eventId,
            String caseId,
            String logicalRequestRef,
            LlmProvider provider,
            String model,
            ProviderCapabilityQualityRegistry.CapabilityClass capabilityClass,
            Source source,
            double score,
            double weight,
            String evidenceReference,
            Instant observedAt) {
        public enum Source { OBSERVATION, HUMAN }
        public FeedbackEvent {
            require(eventId, "eventId");
            caseId = caseId == null ? "" : caseId.trim();
            logicalRequestRef = logicalRequestRef == null ? "" : logicalRequestRef.trim();
            Objects.requireNonNull(provider, "provider");
            model = model == null ? "" : model.trim();
            Objects.requireNonNull(capabilityClass, "capabilityClass");
            Objects.requireNonNull(source, "source");
            if (!Double.isFinite(score) || score < 0 || score > 1) throw new IllegalArgumentException("score invalid");
            if (!Double.isFinite(weight) || weight <= 0 || weight > 1) throw new IllegalArgumentException("weight invalid");
            require(evidenceReference, "evidenceReference");
            Objects.requireNonNull(observedAt, "observedAt");
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }
}
