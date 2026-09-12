package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;
import com.metatron.workforce.interaction.llm.ProviderCallTraceRegistry;
import com.metatron.workforce.interaction.llm.ProviderTelemetryRegistry;
import com.metatron.workforce.observation.ObservationReport;
import com.metatron.workforce.phase9.BoundaryProvenance;
import com.metatron.workforce.phase9.BoundaryResult;
import com.metatron.workforce.phase9.BoundaryStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class IntelligenceRoutingFeedbackLoopAcceptanceTest {
    private final Instant now = Instant.parse("2026-09-12T00:00:00Z");

    @Test
    void authoritativeObservationClosesLoopBackIntoAutoRouting() {
        ProviderCapabilityQualityRegistry quality = new ProviderCapabilityQualityRegistry(Map.of());
        ProviderCallTraceRegistry traces = new ProviderCallTraceRegistry();
        IntelligenceRoutingFeedbackService feedback = new IntelligenceRoutingFeedbackService(
                quality, traces, new InMemoryIntelligenceRoutingFeedbackStore());

        InMemoryIntelligenceCaseStore cases = new InMemoryIntelligenceCaseStore();
        IntelligenceCase intelligenceCase = cases.openOrUpdate(
                "conversation:feedback-loop", "human-primary", normalized());
        recordSuccess(traces, LlmProvider.OPENAI, "openai-analysis", intelligenceCase.caseId(),
                "logical:feedback-loop", "intelligence-reasoning:coding");
        Instant observedAt = afterLatestTrace(traces, intelligenceCase.caseId());

        IntelligenceCaseLifecycleService lifecycle = new IntelligenceCaseLifecycleService(
                cases, Clock.fixed(now, ZoneOffset.UTC), feedback);
        lifecycle.recordObservedOutcome(
                intelligenceCase.caseId(), observationBoundary(passReport(intelligenceCase.caseId())),
                "execution-1", "observation-1", "outcome-1",
                "observation-evidence:1", "expected=accepted actual=accepted", observedAt);

        var learned = quality.snapshot(LlmProvider.OPENAI, "coding");
        assertTrue(learned.known());
        assertTrue(learned.score() > 0.5d);
        assertTrue(learned.evidenceReference().contains("observation-report:"));

        AdaptiveProviderRoutingPolicy policy = new AdaptiveProviderRoutingPolicy(
                List.of(LlmProvider.GOOGLE, LlmProvider.OPENAI), new ProviderTelemetryRegistry(), quality);
        assertEquals(List.of(LlmProvider.OPENAI), policy.select(request("coding")));
        assertEquals(1, feedback.aggregates().size());
        assertEquals(1, feedback.recentEvents().size());
    }

    @Test
    void failedObservedOutcomeLowersQualityWithoutInventingProviderFailure() {
        ProviderCapabilityQualityRegistry quality = new ProviderCapabilityQualityRegistry(Map.of());
        ProviderCallTraceRegistry traces = new ProviderCallTraceRegistry();
        recordSuccess(traces, LlmProvider.OPENAI, "openai-analysis", "case-fail",
                "logical:fail", "intelligence-reasoning:coding");
        Instant observedAt = afterLatestTrace(traces, "case-fail");
        IntelligenceRoutingFeedbackService feedback = new IntelligenceRoutingFeedbackService(
                quality, traces, new InMemoryIntelligenceRoutingFeedbackStore());

        var result = feedback.recordObservedOutcome(
                "case-fail", observationBoundary(failReport("case-fail")), observedAt);

        assertTrue(result.applied());
        assertTrue(quality.snapshot(LlmProvider.OPENAI, "coding").score() < 0.5d);
        AdaptiveProviderRoutingPolicy policy = new AdaptiveProviderRoutingPolicy(
                List.of(LlmProvider.GOOGLE, LlmProvider.OPENAI), new ProviderTelemetryRegistry(), quality);
        assertEquals(List.of(LlmProvider.GOOGLE), policy.select(request("coding")));
    }

    @Test
    void ambiguousMultiModelOutcomeDoesNotTeachWrongProvider() {
        ProviderCapabilityQualityRegistry quality = new ProviderCapabilityQualityRegistry(Map.of());
        ProviderCallTraceRegistry traces = new ProviderCallTraceRegistry();
        recordSuccess(traces, LlmProvider.OPENAI, "openai", "case-multi", "logical:multi", "analysis");
        recordSuccess(traces, LlmProvider.ANTHROPIC, "claude", "case-multi", "logical:multi", "analysis");
        Instant observedAt = afterLatestTrace(traces, "case-multi");
        IntelligenceRoutingFeedbackService feedback = new IntelligenceRoutingFeedbackService(
                quality, traces, new InMemoryIntelligenceRoutingFeedbackStore());

        var result = feedback.recordObservedOutcome(
                "case-multi", observationBoundary(passReport("case-multi")), observedAt);

        assertFalse(result.applied());
        assertEquals("multi-provider-attribution-ambiguous", result.reason());
        assertFalse(quality.snapshot(LlmProvider.OPENAI, "analysis").known());
        assertFalse(quality.snapshot(LlmProvider.ANTHROPIC, "analysis").known());
        assertTrue(feedback.recentEvents().isEmpty());
    }

    @Test
    void explicitHumanAssessmentFeedsSameDurableQualityLoop() {
        ProviderCapabilityQualityRegistry quality = new ProviderCapabilityQualityRegistry(Map.of());
        IntelligenceRoutingFeedbackService feedback = new IntelligenceRoutingFeedbackService(
                quality, new ProviderCallTraceRegistry(), new InMemoryIntelligenceRoutingFeedbackStore());

        var result = feedback.recordHumanAssessment(
                "case-human", "logical:human", LlmProvider.ANTHROPIC, "claude-model",
                "creative.composer", true, 1.0d, "human-acceptance:founder:1", now);

        assertTrue(result.applied());
        assertTrue(quality.snapshot(LlmProvider.ANTHROPIC, "creative.composer").score() > 0.5d);
        assertEquals(IntelligenceRoutingFeedbackStore.FeedbackEvent.Source.HUMAN, result.event().source());
    }

    @Test
    void learnedQualitySurvivesProcessRestart(@TempDir Path temp) {
        Path file = temp.resolve("routing-feedback.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ProviderCapabilityQualityRegistry firstQuality = new ProviderCapabilityQualityRegistry(Map.of());
        IntelligenceRoutingFeedbackService first = new IntelligenceRoutingFeedbackService(
                firstQuality, new ProviderCallTraceRegistry(),
                new FileIntelligenceRoutingFeedbackStore(file, mapper));
        first.recordHumanAssessment(
                "case-restart", "logical:restart", LlmProvider.OPENAI, "model-a",
                "planning", true, 0.9d, "human-acceptance:restart:1", now);
        double before = firstQuality.snapshot(LlmProvider.OPENAI, "planning").score();

        ProviderCapabilityQualityRegistry restartedQuality = new ProviderCapabilityQualityRegistry(Map.of());
        IntelligenceRoutingFeedbackService restarted = new IntelligenceRoutingFeedbackService(
                restartedQuality, new ProviderCallTraceRegistry(),
                new FileIntelligenceRoutingFeedbackStore(file, mapper));

        assertEquals(before, restartedQuality.snapshot(LlmProvider.OPENAI, "planning").score(), 0.0000001d);
        assertEquals(1, restarted.aggregates().size());
        assertEquals(1, restarted.recentEvents().size());
    }

    @Test
    void untypedObservationCannotMutateRoutingQuality() {
        ProviderCapabilityQualityRegistry quality = new ProviderCapabilityQualityRegistry(Map.of());
        ProviderCallTraceRegistry traces = new ProviderCallTraceRegistry();
        recordSuccess(traces, LlmProvider.OPENAI, "model", "case-untyped", "logical:untyped", "analysis");
        IntelligenceRoutingFeedbackService feedback = new IntelligenceRoutingFeedbackService(
                quality, traces, new InMemoryIntelligenceRoutingFeedbackStore());

        BoundaryResult untyped = new BoundaryResult(
                "obs-untyped", InstitutionalIntelligenceReferenceBridge.OBSERVATION_CONTRACT,
                BoundaryStatus.SUCCESS, "just text", "authority:observation",
                new BoundaryProvenance("test", "evidence:untyped", now));
        assertFalse(feedback.recordObservedOutcome("case-untyped", untyped, now).applied());
        assertFalse(quality.snapshot(LlmProvider.OPENAI, "analysis").known());
    }

    private Instant afterLatestTrace(ProviderCallTraceRegistry traces, String caseId) {
        return traces.forCaseRef(caseId).stream()
                .map(ProviderCallTraceRegistry.ProviderCallTrace::completedAt)
                .max(Instant::compareTo)
                .orElseThrow()
                .plusMillis(1);
    }

    private void recordSuccess(ProviderCallTraceRegistry traces, LlmProvider provider, String model,
                               String caseId, String logical, String purpose) {
        LlmRequest request = new LlmRequest(provider, model, "system", "user", logical, caseId, purpose);
        traces.success(request, System.nanoTime(), new LlmResponse(provider, model, "answer", "provider-ref"));
    }

    private BoundaryResult observationBoundary(ObservationReport report) {
        return new BoundaryResult(
                "observation-boundary:" + report.reportId(),
                InstitutionalIntelligenceReferenceBridge.OBSERVATION_CONTRACT,
                BoundaryStatus.SUCCESS, report, "authority:observation",
                new BoundaryProvenance("observation-test", "observation-evidence:" + report.reportId(), now));
    }

    private ObservationReport passReport(String objectiveId) {
        return new ObservationReport(
                "report-pass-" + objectiveId, "requirement-1", objectiveId, "target",
                "acceptance satisfied", "independent-test", now, now,
                List.of("verified-evidence:1"), 0.95d, ObservationReport.Quality.HIGH,
                "", ObservationReport.CriterionResult.PASS);
    }

    private ObservationReport failReport(String objectiveId) {
        return new ObservationReport(
                "report-fail-" + objectiveId, "requirement-1", objectiveId, "target",
                "acceptance failed", "independent-test", now, now,
                List.of("verified-evidence:failure"), 0.95d, ObservationReport.Quality.HIGH,
                "expected mismatch", ObservationReport.CriterionResult.FAIL);
    }

    private IntelligenceRequest request(String capability) {
        return new IntelligenceRequest(
                "request-routing", "WORKER-TEST", IntelligenceMode.REASONING, CollaborationMode.SINGLE,
                "complete work", "case_id=case-routing", List.of(), capability,
                "READ_ONLY", "standard", "standard", "", "answer", List.of(), 1, false);
    }

    private NormalizedRequest normalized() {
        return new NormalizedRequest(
                "improve provider routing from observed outcomes", "intelligence", List.of(), IntelligenceDepth.ANALYZE,
                "evidence-backed result", List.of(), List.of(), "current", "",
                IntelligenceMode.REASONING, CollaborationMode.SINGLE,
                List.of(AnalyticalProtocolType.IMPROVEMENT), DeterministicCapability.NONE, false,
                null, null, "");
    }
}
