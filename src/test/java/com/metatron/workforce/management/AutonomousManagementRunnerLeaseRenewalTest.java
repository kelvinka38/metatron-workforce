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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Production incident (2026-09-22): a real Objective's VERIFY step failed identically across all of
 * its bounded local-retry attempts (workspace.dependencies.install), and each dispatch attempt
 * legitimately took minutes of real wall-clock time. The management lease acquired once at the top
 * of one process() pass was never renewed during that same pass, and was only DEFAULT_LEASE (5
 * minutes) long -- far shorter than the cumulative time a bounded-retry-exhausting pass can
 * legitimately take. Once the pass ran past that window, every remaining durable write in the SAME
 * pass (recording the next retry, and ultimately persisting the exhausted-retry blocker/escalation)
 * failed with "missing, expired, or stale management lease", which was silently swallowed by
 * processWithLease()'s outer catch. The step's exhausted state was therefore never durably recorded,
 * and the very next poll simply re-dispatched the same already-failing step again -- observed live as
 * message ids climbing by one roughly every 5 seconds with no diagnosable status ever reaching the
 * Human.
 *
 * <p>This test reproduces the exact shape (a lease shorter than the cumulative pass duration) using a
 * deterministic {@link MutableClock} that only advances when the failing capability itself simulates
 * elapsed real time, rather than real sleeps.</p>
 */
final class AutonomousManagementRunnerLeaseRenewalTest {

    @Test
    void leaseIsRenewedAcrossSlowBoundedRetriesSoExhaustionIsDurablyEscalated() {
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomyCoordinationService coordination = new AutonomyCoordinationService();
        MutableClock clock = new MutableClock(Instant.parse("2026-09-22T02:40:00Z"));
        AtomicInteger attempts = new AtomicInteger();

        // Mirrors the real production capability: a deterministic, always-failing dependency-install
        // style call that takes real minutes each time -- comfortably longer than the lease below.
        AutonomousExecutionCapability workspace = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return GeneralWorkspaceAutonomousCapability.CAPABILITY; }

            @Override public CapabilityResult execute(CapabilityRequest request) {
                int attempt = attempts.incrementAndGet();
                clock.advance(Duration.ofMinutes(3));
                throw new IllegalStateException(
                        "workspace.dependencies.install failed identically twice with no intervening "
                                + "state change; bounded retry exhausted, attempt=" + attempt);
            }
        };

        // A 5-minute lease -- the real production default -- is shorter than even two of these
        // attempts (3 minutes each) stacked in the same pass.
        AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management, (caseId, normalized, available) -> normalized.executionWorkPlan(),
                List.of(workspace), coordination, clock,
                "runner-lease-renewal", Duration.ofMinutes(5), Duration.ofSeconds(1), 1);

        management.acceptHumanObjective(
                "objective-lease-renewal", "worker-head", "org-metatron",
                "Create and deliver a small runnable web application",
                "human:founder", "request-admission:lease-renewal", "case-lease-renewal",
                "conversation-lease-renewal", "message-lease-renewal", "telegram",
                singleStepRequest(), clock.instant());

        runner.runOnce();

        assertEquals(3, attempts.get(),
                "all 3 bounded-retry attempts must actually run -- a stale lease previously aborted "
                        + "the pass early and silently, well before the bound was reached");
        assertEquals(ManagementObjective.Status.ESCALATED,
                management.get("objective-lease-renewal").status(),
                "exhausting the bound must durably escalate for Human review; a stale-lease failure "
                        + "previously left the Objective silently stuck EXECUTING forever instead");
        assertTrue(management.history("objective-lease-renewal").stream()
                        .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.ESCALATED)
                        .anyMatch(event -> event.detail().contains("bounded-local-retry-exhausted")),
                "the escalation reason must be durably recorded and diagnosable");
    }

    @Test
    void singleSlowAttemptThatAloneOutlastsTheLeaseIsStillDurablyEscalated() {
        // Production incident (2026-09-22, second occurrence): a single dispatch attempt -- one
        // cognitive-provider call, not a sequence of several -- took longer than DEFAULT_LEASE (5
        // minutes) all by itself before failing. A renewal placed only AFTER await() returns is
        // already too late in that case: by the time it runs, the lease acquired at the top of the
        // pass has already lapsed, so the renewal call itself throws the very "missing, expired, or
        // stale management lease" failure it exists to prevent -- reproducing the identical symptom
        // (an unpersisted blocker, and the next poll re-dispatching the same failing step forever)
        // that the first lease-renewal fix addressed for the multi-attempt case.
        ManagementAutonomyService management = new ManagementAutonomyService();
        AutonomyCoordinationService coordination = new AutonomyCoordinationService();
        MutableClock clock = new MutableClock(Instant.parse("2026-09-22T02:40:00Z"));
        AtomicInteger attempts = new AtomicInteger();

        AutonomousExecutionCapability workspace = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return GeneralWorkspaceAutonomousCapability.CAPABILITY; }

            @Override public CapabilityResult execute(CapabilityRequest request) {
                int attempt = attempts.incrementAndGet();
                // Longer than the 5-minute lease below, on a SINGLE attempt -- unlike the sibling test,
                // which only exceeds the lease cumulatively across several shorter attempts.
                clock.advance(Duration.ofMinutes(6));
                throw new IllegalStateException(
                        "invalid Intelligence cognitive JSON: cognitive provider returned no JSON object, attempt="
                                + attempt);
            }
        };

        AutonomousManagementRunner runner = new AutonomousManagementRunner(
                management, (caseId, normalized, available) -> normalized.executionWorkPlan(),
                List.of(workspace), coordination, clock,
                "runner-lease-renewal-single-slow", Duration.ofMinutes(5), Duration.ofSeconds(1), 1);

        management.acceptHumanObjective(
                "objective-lease-renewal-single-slow", "worker-head", "org-metatron",
                "Create and deliver a small runnable web application",
                "human:founder", "request-admission:lease-renewal-single-slow", "case-lease-renewal-single-slow",
                "conversation-lease-renewal-single-slow", "message-lease-renewal-single-slow", "telegram",
                singleStepRequest(), clock.instant());

        runner.runOnce();

        assertEquals(3, attempts.get(),
                "all 3 bounded-retry attempts must actually run -- a lease that lapses mid-await on a "
                        + "single slow attempt previously aborted the pass after only 1 attempt");
        assertEquals(ManagementObjective.Status.ESCALATED,
                management.get("objective-lease-renewal-single-slow").status(),
                "exhausting the bound must durably escalate for Human review; a single attempt that "
                        + "alone outlasts the lease previously left the Objective silently stuck EXECUTING");
        assertTrue(management.history("objective-lease-renewal-single-slow").stream()
                        .filter(event -> event.type() == ManagementAutonomyService.ManagementEvent.Type.ESCALATED)
                        .anyMatch(event -> event.detail().contains("bounded-local-retry-exhausted")),
                "the escalation reason must be durably recorded and diagnosable");
    }

    private static NormalizedRequest singleStepRequest() {
        ExecutionWorkSpec verify = new ExecutionWorkSpec(
                "verify", "Verify build and tests", "target",
                GeneralWorkspaceAutonomousCapability.CAPABILITY, List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("tests pass"), List.of("verify evidence"));
        return new NormalizedRequest(
                "Create and deliver a small runnable web application", "target",
                List.of("execute autonomously"), IntelligenceDepth.ANALYZE,
                "evidence-backed durable work product", List.of(), List.of(), "current", "",
                IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.<AnalyticalProtocolType>of(), DeterministicCapability.NONE,
                List.of(), List.of(verify), false, null, LlmProvider.OPENAI, "");
    }

    /** A fully controllable clock: time only ever moves when the test explicitly advances it. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) { this.now = start; }

        void advance(Duration by) { now = now.plus(by); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }

        @Override public Instant instant() { return now; }
    }
}
