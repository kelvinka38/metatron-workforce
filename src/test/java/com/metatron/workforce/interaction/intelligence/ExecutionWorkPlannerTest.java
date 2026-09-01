package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExecutionWorkPlannerTest {
    @Test
    void plannerReceivesCaseAndNormalizedRequestNotRawHumanUtterance() {
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }

            @Override public LlmResponse complete(LlmRequest request) {
                assertTrue(request.userInput().contains("case-123"));
                assertTrue(request.userInput().contains("objective=execute governed repository audit"));
                assertTrue(request.userInput().contains("repository.audit.read"));
                assertFalse(request.userInput().contains("Đụ má audit cái repo này giùm tao"));
                return planResponse(LlmProvider.GOOGLE);
            }
        };
        ObjectMapper mapper = new ObjectMapper();
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(google)), provider -> "planner-test",
                List.of(LlmProvider.GOOGLE), mapper);
        NormalizedRequest normalized = normalizedExecution(null);

        List<ExecutionWorkSpec> plan = planner.plan("case-123", normalized, List.of("repository.audit.read"));

        assertEquals(1, plan.size());
        assertEquals("repository.audit.read", plan.getFirst().requiredCapability());
        assertEquals(ExecutionWorkSpec.Consequence.READ_ONLY, plan.getFirst().consequence());
        assertTrue(plan.getFirst().verifiable());
    }

    @Test
    void plannerRejectsExecutionPlanWithoutCriterionLevelVerificationRequirementsWhenNoSafeFallbackApplies() {
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                return new LlmResponse(LlmProvider.GOOGLE, "planner-test", """
                        {"execution_work_plan":[{
                          "step_id":"audit-repo",
                          "objective":"audit repository",
                          "target":"kelvinka38/bios",
                          "required_capability":"repository.audit.read",
                          "depends_on":[],
                          "consequence":"READ_ONLY"
                        }]}
                        """, "planner-ref");
            }
        };
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(google)), provider -> "planner-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> planner.plan("case-123", normalizedExecution(null), List.of("unrelated.read")));

        assertTrue(failure.getMessage().contains("all execution planning providers failed"));
        assertTrue(List.of(failure.getSuppressed()).stream()
                .anyMatch(suppressed -> suppressed.getMessage().contains("criterion-level verification requirements")));
    }

    @Test
    void plannerUsesLiveTelemetryToAvoidKnownDegradedProviderInAutoMode() {
        AtomicInteger googleCalls = new AtomicInteger();
        AtomicInteger anthropicCalls = new AtomicInteger();
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                googleCalls.incrementAndGet();
                throw new IllegalStateException("should not call degraded provider first");
            }
        };
        LlmProviderClient anthropic = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.ANTHROPIC; }
            @Override public LlmResponse complete(LlmRequest request) {
                anthropicCalls.incrementAndGet();
                return planResponse(LlmProvider.ANTHROPIC);
            }
        };
        LlmProviderRouter router = new LlmProviderRouter(List.of(google, anthropic));
        long started = router.telemetry().begin(LlmProvider.GOOGLE);
        router.telemetry().failure(LlmProvider.GOOGLE, started,
                new IllegalStateException("google_request_failed:429:quota exceeded"));
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                router, provider -> "planner-test", List.of(LlmProvider.GOOGLE, LlmProvider.ANTHROPIC),
                new ObjectMapper());

        List<ExecutionWorkSpec> plan = planner.plan("case-123", normalizedExecution(null),
                List.of("repository.audit.read"));

        assertEquals(1, plan.size());
        assertEquals(0, googleCalls.get());
        assertEquals(1, anthropicCalls.get());
    }

    @Test
    void explicitPlannerProviderIsPreservedDespiteTelemetryPrediction() {
        AtomicInteger googleCalls = new AtomicInteger();
        AtomicInteger anthropicCalls = new AtomicInteger();
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                googleCalls.incrementAndGet();
                return planResponse(LlmProvider.GOOGLE);
            }
        };
        LlmProviderClient anthropic = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.ANTHROPIC; }
            @Override public LlmResponse complete(LlmRequest request) {
                anthropicCalls.incrementAndGet();
                return planResponse(LlmProvider.ANTHROPIC);
            }
        };
        LlmProviderRouter router = new LlmProviderRouter(List.of(google, anthropic));
        long started = router.telemetry().begin(LlmProvider.GOOGLE);
        router.telemetry().failure(LlmProvider.GOOGLE, started,
                new IllegalStateException("google_request_failed:429:quota exceeded"));
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                router, provider -> "planner-test", List.of(LlmProvider.GOOGLE, LlmProvider.ANTHROPIC),
                new ObjectMapper());

        List<ExecutionWorkSpec> plan = planner.plan("case-123", normalizedExecution(LlmProvider.GOOGLE),
                List.of("repository.audit.read"));

        assertEquals(1, plan.size());
        assertEquals(1, googleCalls.get());
        assertEquals(0, anthropicCalls.get());
    }

    @Test
    void boundedSingleRepositoryAuditCanPlanWithoutExternalProvider() {
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of()), provider -> "unused", List.of(), new ObjectMapper());

        List<ExecutionWorkSpec> plan = planner.plan(
                "case-123", normalizedExecution(null), List.of("repository.audit.read"));

        assertEquals(1, plan.size());
        assertEquals("repository.audit.read", plan.getFirst().requiredCapability());
        assertEquals("kelvinka38/bios", plan.getFirst().target());
        assertEquals(ExecutionWorkSpec.Consequence.READ_ONLY, plan.getFirst().consequence());
        assertTrue(plan.getFirst().verifiable());
    }

    @Test
    void providerlessPlannerStillFailsClosedOutsideBoundedAuditShape() {
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of()), provider -> "unused", List.of(), new ObjectMapper());
        NormalizedRequest mutating = new NormalizedRequest(
                "fix code and open pull request", "kelvinka38/bios", List.of("mutation required"),
                IntelligenceDepth.ANALYZE, "pull request", List.of(), List.of(),
                "current", "", IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.of(AnalyticalProtocolType.AUDIT), DeterministicCapability.NONE, List.of(), List.of(),
                false, null, LlmProvider.GOOGLE, "");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> planner.plan("case-mutating", mutating, List.of("repository.audit.read")));

        assertEquals("execution_planning_provider_required", failure.getMessage());
    }

    private static LlmResponse planResponse(LlmProvider provider) {
        return new LlmResponse(provider, "planner-test", """
                {"execution_work_plan":[{
                  "step_id":"audit-repo",
                  "objective":"audit repository and preserve evidence",
                  "target":"kelvinka38/bios",
                  "required_capability":"repository.audit.read",
                  "depends_on":[],
                  "consequence":"READ_ONLY",
                  "acceptance_criteria":["repository audit is complete and evidence-backed"],
                  "evidence_requirements":["repository contents and cited audit evidence"]
                }]}
                """, "planner-ref");
    }

    private static NormalizedRequest normalizedExecution(LlmProvider explicitlyRequestedProvider) {
        return new NormalizedRequest(
                "execute governed repository audit", "kelvinka38/bios", List.of("preserve evidence", "read-only"),
                IntelligenceDepth.ANALYZE, "terminal audit result", List.of(), List.of("do not mutate"),
                "current", "", IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.of(AnalyticalProtocolType.AUDIT), DeterministicCapability.NONE, List.of(), List.of(),
                false, explicitlyRequestedProvider, LlmProvider.GOOGLE, "");
    }
}
