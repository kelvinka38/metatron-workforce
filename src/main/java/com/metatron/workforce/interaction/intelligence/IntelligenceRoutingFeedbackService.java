package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.ProviderCallTraceRegistry;
import com.metatron.workforce.observation.ObservationReport;
import com.metatron.workforce.phase9.BoundaryResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Closes observed provider outcomes back into evidence-backed provider quality used by AUTO routing.
 *
 * <p>Automatic learning is intentionally conservative. Only authoritative PASS/FAIL Observation with
 * exactly one successful provider call in the latest logical request is auto-attributed. Multi-model
 * outcomes remain unattributed rather than teaching the wrong provider. Human assessment is explicit.</p>
 */
public final class IntelligenceRoutingFeedbackService {
    private static final int MAX_EVENTS = 5_000;

    private final ProviderCapabilityQualityRegistry qualityRegistry;
    private final ProviderCallTraceRegistry callTrace;
    private final IntelligenceRoutingFeedbackStore store;
    private final Map<Key, IntelligenceRoutingFeedbackStore.Aggregate> aggregates = new LinkedHashMap<>();
    private final List<IntelligenceRoutingFeedbackStore.FeedbackEvent> events = new ArrayList<>();

    public IntelligenceRoutingFeedbackService(
            ProviderCapabilityQualityRegistry qualityRegistry,
            ProviderCallTraceRegistry callTrace,
            IntelligenceRoutingFeedbackStore store) {
        this.qualityRegistry = Objects.requireNonNull(qualityRegistry, "qualityRegistry");
        this.callTrace = Objects.requireNonNull(callTrace, "callTrace");
        this.store = Objects.requireNonNull(store, "store");
        IntelligenceRoutingFeedbackStore.Snapshot snapshot = store.load();
        snapshot.aggregates().forEach(aggregate -> aggregates.put(
                new Key(aggregate.provider(), aggregate.capabilityClass()), aggregate));
        events.addAll(snapshot.events());
        aggregates.values().forEach(this::applyAggregateToRouting);
    }

    public synchronized Result recordObservedOutcome(
            String caseId,
            BoundaryResult observationBoundary,
            Instant observedAt) {
        require(caseId, "caseId");
        Objects.requireNonNull(observationBoundary, "observationBoundary");
        Objects.requireNonNull(observedAt, "observedAt");
        if (!InstitutionalIntelligenceReferenceBridge.OBSERVATION_CONTRACT.equals(observationBoundary.contractId())) {
            throw new IllegalArgumentException("observation boundary contract required");
        }
        if (!observationBoundary.succeeded()) return Result.skipped("observation-boundary-not-successful");
        if (!(observationBoundary.output() instanceof ObservationReport report)) {
            return Result.skipped("observation-output-not-typed-report");
        }
        if (report.criterionResult() == ObservationReport.CriterionResult.INCONCLUSIVE
                || report.quality() == ObservationReport.Quality.INSUFFICIENT) {
            return Result.skipped("observation-not-learning-grade");
        }

        List<ProviderCallTraceRegistry.ProviderCallTrace> caseCalls = callTrace.forCaseRef(caseId).stream()
                .filter(call -> !call.completedAt().isAfter(observedAt))
                .toList();
        if (caseCalls.isEmpty()) return Result.skipped("no-provider-trace-for-case");

        String latestLogical = caseCalls.stream()
                .filter(call -> call.logicalRequestRef() != null && !call.logicalRequestRef().isBlank())
                .max(Comparator.comparing(ProviderCallTraceRegistry.ProviderCallTrace::completedAt))
                .map(ProviderCallTraceRegistry.ProviderCallTrace::logicalRequestRef)
                .orElse("");
        if (latestLogical.isBlank()) return Result.skipped("provider-trace-missing-logical-request");

        List<ProviderCallTraceRegistry.ProviderCallTrace> successful = caseCalls.stream()
                .filter(call -> latestLogical.equals(call.logicalRequestRef()))
                .filter(ProviderCallTraceRegistry.ProviderCallTrace::success)
                .toList();
        if (successful.size() != 1) {
            return Result.skipped(successful.isEmpty()
                    ? "latest-logical-request-has-no-successful-provider"
                    : "multi-provider-attribution-ambiguous");
        }

        ProviderCallTraceRegistry.ProviderCallTrace trace = successful.getFirst();
        String capability = capabilityFromPurpose(trace.purpose());
        double score = observationScore(report);
        double weight = observationWeight(report.quality());
        if (weight <= 0) return Result.skipped("observation-weight-zero");
        String evidence = "observation-report:" + report.reportId();
        IntelligenceRoutingFeedbackStore.FeedbackEvent event = new IntelligenceRoutingFeedbackStore.FeedbackEvent(
                "routing-feedback:" + UUID.randomUUID(), caseId, latestLogical,
                trace.provider(), trace.model(), ProviderCapabilityQualityRegistry.classify(capability),
                IntelligenceRoutingFeedbackStore.FeedbackEvent.Source.OBSERVATION,
                score, weight, evidence, observedAt);
        IntelligenceRoutingFeedbackStore.Aggregate aggregate = apply(event);
        return Result.applied(event, aggregate);
    }

    public synchronized Result recordHumanAssessment(
            String caseId,
            String logicalRequestRef,
            LlmProvider provider,
            String model,
            String requiredCapability,
            boolean accepted,
            double confidence,
            String evidenceReference,
            Instant assessedAt) {
        require(caseId, "caseId");
        require(logicalRequestRef, "logicalRequestRef");
        Objects.requireNonNull(provider, "provider");
        require(requiredCapability, "requiredCapability");
        require(evidenceReference, "evidenceReference");
        Objects.requireNonNull(assessedAt, "assessedAt");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be within [0,1]");
        }
        double score = accepted ? (0.5d + 0.5d * confidence) : (0.5d * (1.0d - confidence));
        double weight = 0.5d + 0.5d * confidence;
        IntelligenceRoutingFeedbackStore.FeedbackEvent event = new IntelligenceRoutingFeedbackStore.FeedbackEvent(
                "routing-feedback:" + UUID.randomUUID(), caseId, logicalRequestRef,
                provider, model == null ? "" : model,
                ProviderCapabilityQualityRegistry.classify(requiredCapability),
                IntelligenceRoutingFeedbackStore.FeedbackEvent.Source.HUMAN,
                score, weight, evidenceReference, assessedAt);
        IntelligenceRoutingFeedbackStore.Aggregate aggregate = apply(event);
        return Result.applied(event, aggregate);
    }

    public synchronized List<IntelligenceRoutingFeedbackStore.Aggregate> aggregates() {
        return aggregates.values().stream()
                .sorted(Comparator.comparing((IntelligenceRoutingFeedbackStore.Aggregate a) -> a.provider().name())
                        .thenComparing(a -> a.capabilityClass().name()))
                .toList();
    }

    public synchronized List<IntelligenceRoutingFeedbackStore.FeedbackEvent> recentEvents() {
        return List.copyOf(events);
    }

    private IntelligenceRoutingFeedbackStore.Aggregate apply(IntelligenceRoutingFeedbackStore.FeedbackEvent event) {
        Key key = new Key(event.provider(), event.capabilityClass());
        IntelligenceRoutingFeedbackStore.Aggregate previous = aggregates.get(key);
        double totalWeight = (previous == null ? 0 : previous.totalWeight()) + event.weight();
        double weightedScore = (previous == null ? 0 : previous.weightedScoreSum()) + event.score() * event.weight();
        long samples = (previous == null ? 0 : previous.sampleCount()) + 1;
        IntelligenceRoutingFeedbackStore.Aggregate next = new IntelligenceRoutingFeedbackStore.Aggregate(
                event.provider(), event.capabilityClass(), totalWeight, weightedScore, samples,
                event.evidenceReference(), event.observedAt());
        aggregates.put(key, next);
        events.add(event);
        if (events.size() > MAX_EVENTS) events.subList(0, events.size() - MAX_EVENTS).clear();
        persist();
        applyAggregateToRouting(next);
        return next;
    }

    private void applyAggregateToRouting(IntelligenceRoutingFeedbackStore.Aggregate aggregate) {
        qualityRegistry.record(
                aggregate.provider(),
                aggregate.capabilityClass().name(),
                aggregate.effectiveScore(),
                "routing-feedback:" + aggregate.lastEvidenceReference());
    }

    private void persist() {
        store.save(new IntelligenceRoutingFeedbackStore.Snapshot(
                List.copyOf(aggregates.values()), List.copyOf(events)));
    }

    private static double observationScore(ObservationReport report) {
        return report.criterionResult() == ObservationReport.CriterionResult.PASS
                ? 0.5d + 0.5d * report.confidence()
                : 0.5d * (1.0d - report.confidence());
    }

    private static double observationWeight(ObservationReport.Quality quality) {
        return switch (quality) {
            case HIGH -> 1.0d;
            case MEDIUM -> 0.65d;
            case LOW -> 0.35d;
            case INSUFFICIENT -> 0.0d;
        };
    }

    static String capabilityFromPurpose(String purpose) {
        String value = purpose == null ? "" : purpose.trim();
        if (value.startsWith("intelligence-reasoning:")) return value.substring("intelligence-reasoning:".length());
        return switch (value) {
            case "worker-intelligence" -> "worker.intelligence";
            case "execution-planning-or-reasoning" -> "planning";
            case "multi-model-normalization" -> "semantic-normalization";
            case "multi-model-challenge" -> "analysis";
            default -> value.isBlank() ? "general" : value;
        };
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }

    private record Key(LlmProvider provider, ProviderCapabilityQualityRegistry.CapabilityClass capabilityClass) { }

    public record Result(
            boolean applied,
            String reason,
            IntelligenceRoutingFeedbackStore.FeedbackEvent event,
            IntelligenceRoutingFeedbackStore.Aggregate aggregate) {
        static Result skipped(String reason) { return new Result(false, reason, null, null); }
        static Result applied(IntelligenceRoutingFeedbackStore.FeedbackEvent event,
                              IntelligenceRoutingFeedbackStore.Aggregate aggregate) {
            return new Result(true, "applied", event, aggregate);
        }
    }
}
