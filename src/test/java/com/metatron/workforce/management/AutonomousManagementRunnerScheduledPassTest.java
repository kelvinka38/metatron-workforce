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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutonomousManagementRunnerScheduledPassTest {
    @Test
    void aLongRunningLaneNeverHoldsBackAnotherObjectivesScheduledTurn() throws Exception {
        // Production 2026-09-24/25: after each restart an Objective whose management lease had expired sat
        // untouched for 40-100 minutes, because every scheduled pass waited for all of its lanes and one
        // lane could legitimately run for nodeExecutionTimeout.
        Clock clock = Clock.systemUTC();
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomyCoordinationService coordination = new AutonomyCoordinationService();
        CountDownLatch slowRelease = new CountDownLatch(1);
        CountDownLatch fastDone = new CountDownLatch(1);
        AtomicInteger slowExecutions = new AtomicInteger();

        AutonomousExecutionCapability slow = capability("test.slow.read", () -> {
            slowExecutions.incrementAndGet();
            try { slowRelease.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        AutonomousExecutionCapability fast = capability("test.fast.read", fastDone::countDown);

        try (AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management, (caseId, request, available) -> request.executionWorkPlan(),
                List.of(slow, fast), coordination, clock,
                "runner-scheduled-pass", Duration.ofMinutes(5), Duration.ofMillis(50), 4)) {
            management.acceptHumanObjective(
                    "objective-slow", "worker-head", "org-metatron", "Slow read",
                    "human:founder", "request-admission:slow", "case-slow", "conversation-slow",
                    "message-slow", "telegram", request("test.slow.read"), clock.instant());
            runner.start();
            waitUntil(() -> slowExecutions.get() == 1);

            // Admitted while the slow lane is still running: must not wait for it.
            management.acceptHumanObjective(
                    "objective-fast", "worker-head", "org-metatron", "Fast read",
                    "human:founder", "request-admission:fast", "case-fast", "conversation-fast",
                    "message-fast", "telegram", request("test.fast.read"), clock.instant());

            assertTrue(fastDone.await(5, TimeUnit.SECONDS),
                    "a second Objective is processed while another Objective's lane is still running");
            assertEquals(1, slowExecutions.get(), "an in-flight Objective is never dispatched twice");
            slowRelease.countDown();
            waitUntil(() -> management.get("objective-slow").status() == ManagementObjective.Status.COMPLETED);
            assertEquals(1, slowExecutions.get());
        } finally {
            slowRelease.countDown();
        }
    }

    private static AutonomousExecutionCapability capability(String ref, Runnable body) {
        return new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return ref; }

            @Override public CapabilityResult execute(CapabilityRequest request) {
                body.run();
                return new CapabilityResult(true, "worker", "assignment-" + ref, "work-" + ref,
                        List.of("evidence:" + ref), "PASS");
            }
        };
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not reached within 5 s");
            Thread.sleep(20);
        }
    }

    private static NormalizedRequest request(String capability) {
        ExecutionWorkSpec step = new ExecutionWorkSpec(
                "step-1", "Read", "target", capability, List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY, List.of("read completes"), List.of("evidence"));
        return new NormalizedRequest(
                "Read", "target", List.of("read-only"), IntelligenceDepth.ANALYZE,
                "evidence-backed result", List.of(), List.of("do not mutate"), "current", "",
                IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.<AnalyticalProtocolType>of(), DeterministicCapability.NONE,
                List.of(), List.of(step), false, null, LlmProvider.OPENAI, "");
    }
}
