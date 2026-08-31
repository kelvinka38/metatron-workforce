package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetyGovernedAutonomousExecutionCapabilityTest {
    @Test
    void budgetAndRevocationPreventDelegateEffect() {
        Instant now = Instant.parse("2026-08-31T04:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        AtomicInteger effects = new AtomicInteger();
        AutonomousExecutionCapability delegate = new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.read"; }
            @Override public String authorityReference() { return "AUTH-EXEC"; }
            @Override public double requiredCapacity() { return 1.0; }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                effects.incrementAndGet();
                return new CapabilityResult(true, "worker", "assignment", "work",
                        List.of("effect:evidence"), "PASS");
            }
        };
        AutonomySafetyService safety = new AutonomySafetyService(
                new InMemoryAutonomySafetyStateStore(), clock, 1.0, 4,
                Duration.ofHours(1), AutonomySafetyState.RiskLevel.HIGH);
        SafetyGovernedAutonomousExecutionCapability governed =
                new SafetyGovernedAutonomousExecutionCapability(delegate, safety, clock);
        ExecutionWorkSpec step = new ExecutionWorkSpec("step", "read", "target", "test.read",
                List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("target was read"), List.of("source reference"));
        var first = governed.execute(new AutonomousExecutionCapability.CapabilityRequest(
                "human", "org", "objective", step).withDispatch("dispatch-1", 1));
        assertEquals(1, effects.get());
        assertTrue(first.evidenceReferences().stream().anyMatch(ref -> ref.startsWith("autonomy-safety:")));

        assertThrows(AutonomySafetyService.SafetyGateException.class, () ->
                governed.execute(new AutonomousExecutionCapability.CapabilityRequest(
                        "human", "org", "objective", step).withDispatch("dispatch-2", 2)));
        assertEquals(1, effects.get());

        AutonomySafetyService revokedSafety = new AutonomySafetyService(
                new InMemoryAutonomySafetyStateStore(), clock, 10.0, 4,
                Duration.ofHours(1), AutonomySafetyState.RiskLevel.HIGH);
        revokedSafety.revokeAuthority("objective-revoked", "AUTH-EXEC", now);
        SafetyGovernedAutonomousExecutionCapability revoked =
                new SafetyGovernedAutonomousExecutionCapability(delegate, revokedSafety, clock);
        assertThrows(AutonomySafetyService.SafetyGateException.class, () ->
                revoked.execute(new AutonomousExecutionCapability.CapabilityRequest(
                        "human", "org", "objective-revoked", step).withDispatch("dispatch-r", 1)));
        assertEquals(1, effects.get());
    }
}
