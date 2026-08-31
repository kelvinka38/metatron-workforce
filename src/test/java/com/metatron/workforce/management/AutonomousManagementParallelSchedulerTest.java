package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.AnalyticalProtocolType;
import com.metatron.workforce.interaction.intelligence.CollaborationMode;
import com.metatron.workforce.interaction.intelligence.DeterministicCapability;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.IntelligenceDepth;
import com.metatron.workforce.interaction.intelligence.IntelligenceMode;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.interaction.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutonomousManagementParallelSchedulerTest {
    @Test
    void independentBranchesActuallyOverlapAndJoinExecutesOnceAfterBoth() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-31T02:00:00Z"), ZoneOffset.UTC);
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomyCoordinationService coordination = new AutonomyCoordinationService();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger joinRuns = new AtomicInteger();

        AutonomousExecutionCapability capability = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.parallel"; }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                if (request.workSpec().stepId().equals("join")) {
                    joinRuns.incrementAndGet();
                    return result(request.workSpec().stepId());
                }
                int current = active.incrementAndGet();
                maxActive.accumulateAndGet(current, Math::max);
                try { Thread.sleep(120); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                finally { active.decrementAndGet(); }
                return result(request.workSpec().stepId());
            }
            private CapabilityResult result(String step) {
                return new CapabilityResult(true, "worker-" + step, "assignment-" + step,
                        "work-" + step, List.of("evidence:" + step), "PASS");
            }
        };

        List<ExecutionWorkSpec> plan = List.of(
                step("branch-a", List.of()), step("branch-b", List.of()), step("join", List.of("branch-a", "branch-b")));
        NormalizedRequest request = request(plan);
        management.acceptHumanObjective("objective-parallel", "worker-head", "org-metatron", "parallel",
                "human:primary", "request-admission", "case-parallel", "conversation-parallel",
                "message-parallel", "api", request, clock.instant());

        try (AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management, (caseId, normalized, available) -> normalized.executionWorkPlan(),
                List.of(capability), coordination, clock, "runner-parallel",
                Duration.ofMinutes(5), Duration.ofSeconds(5), 4)) {
            runner.runOnce();
        }

        assertTrue(maxActive.get() >= 2, "independent branches must execute concurrently");
        assertEquals(1, joinRuns.get());
        assertEquals(ManagementObjective.Status.COMPLETED, management.get("objective-parallel").status());
        DurableWorkGraph graph = coordination.activeGraph("objective-parallel").orElseThrow();
        assertEquals(DurableWorkGraph.Status.COMPLETED, graph.status());
        assertEquals(3, coordination.dispatches().size());
    }

    private static ExecutionWorkSpec step(String id, List<String> dependencies) {
        return new ExecutionWorkSpec(id, id, "target", "test.parallel", dependencies,
                ExecutionWorkSpec.Consequence.READ_ONLY);
    }

    private static NormalizedRequest request(List<ExecutionWorkSpec> plan) {
        return new NormalizedRequest("Parallel objective", "target", List.of(), IntelligenceDepth.ANALYZE,
                "evidence", List.of(), List.of(), "current", "", IntelligenceMode.EXECUTION,
                CollaborationMode.SINGLE, List.<AnalyticalProtocolType>of(), DeterministicCapability.NONE,
                List.of(), plan, false, null, LlmProvider.OPENAI, "");
    }
}
