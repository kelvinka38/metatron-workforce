package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutonomySafetyServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void durableBudgetIsIdempotentAndSurvivesServiceReplacement() {
        Instant now = Instant.parse("2026-08-31T04:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        Path path = temporaryDirectory.resolve("safety.json");
        AutonomySafetyService first = new AutonomySafetyService(
                new FileAutonomySafetyStateStore(path), clock, 2.0, 2,
                Duration.ofHours(1), AutonomySafetyState.RiskLevel.HIGH);

        var charged = first.reserveDispatch("objective-1", "dispatch-1", 1, "AUTHORITY-1",
                ExecutionWorkSpec.Consequence.READ_ONLY, 1.0, now);
        var replay = first.reserveDispatch("objective-1", "dispatch-1", 1, "AUTHORITY-1",
                ExecutionWorkSpec.Consequence.READ_ONLY, 1.0, now);
        assertEquals(1.0, charged.consumedCostUnits());
        assertTrue(replay.replay());
        assertEquals(1, replay.consumedDispatchAttempts());

        AutonomySafetyService replacement = new AutonomySafetyService(
                new FileAutonomySafetyStateStore(path), clock, 100.0, 100,
                Duration.ofHours(2), AutonomySafetyState.RiskLevel.HIGH);
        assertEquals(1.0, replacement.get("objective-1").consumedCostUnits());
        replacement.reserveDispatch("objective-1", "dispatch-2", 1, "AUTHORITY-1",
                ExecutionWorkSpec.Consequence.READ_ONLY, 1.0, now.plusSeconds(1));
        assertThrows(AutonomySafetyService.SafetyGateException.class, () ->
                replacement.reserveDispatch("objective-1", "dispatch-3", 1, "AUTHORITY-1",
                        ExecutionWorkSpec.Consequence.READ_ONLY, 1.0, now.plusSeconds(2)));
    }

    @Test
    void pauseRevocationRiskDeadlineAndAttemptCeilingFailClosed() {
        Instant now = Instant.parse("2026-08-31T04:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        AutonomySafetyService safety = new AutonomySafetyService(
                new InMemoryAutonomySafetyStateStore(), clock, 100.0, 1,
                Duration.ofMinutes(5), AutonomySafetyState.RiskLevel.LOW);
        safety.ensureObjective("objective-2", now);

        safety.pause("objective-2", "AUTH-CONTROL", now);
        var paused = assertThrows(AutonomySafetyService.SafetyGateException.class, () ->
                safety.reserveDispatch("objective-2", "d-paused", 1, "AUTH-EXEC",
                        ExecutionWorkSpec.Consequence.READ_ONLY, 1.0, now));
        assertTrue(paused.reason().contains("paused"));

        safety.resume("objective-2", "AUTH-CONTROL", now);
        safety.revokeAuthority("objective-2", "AUTH-EXEC", now);
        var revoked = assertThrows(AutonomySafetyService.SafetyGateException.class, () ->
                safety.reserveDispatch("objective-2", "d-revoked", 1, "AUTH-EXEC",
                        ExecutionWorkSpec.Consequence.READ_ONLY, 1.0, now));
        assertTrue(revoked.reason().contains("authority-revoked"));

        safety.restoreAuthority("objective-2", "AUTH-RESTORE", now);
        var risk = assertThrows(AutonomySafetyService.SafetyGateException.class, () ->
                safety.reserveDispatch("objective-2", "d-risk", 1, "AUTH-EXEC",
                        ExecutionWorkSpec.Consequence.MUTATING, 1.0, now));
        assertTrue(risk.reason().contains("risk-threshold"));

        safety.reserveDispatch("objective-2", "d-1", 1, "AUTH-EXEC",
                ExecutionWorkSpec.Consequence.READ_ONLY, 1.0, now);
        var attempt = assertThrows(AutonomySafetyService.SafetyGateException.class, () ->
                safety.reserveDispatch("objective-2", "d-2", 2, "AUTH-EXEC",
                        ExecutionWorkSpec.Consequence.READ_ONLY, 1.0, now.plusSeconds(1)));
        assertTrue(attempt.reason().contains("attempt-ceiling"));

        AutonomySafetyService deadlineSafety = new AutonomySafetyService(
                new InMemoryAutonomySafetyStateStore(), clock, 100.0, 5,
                Duration.ofSeconds(1), AutonomySafetyState.RiskLevel.HIGH);
        deadlineSafety.ensureObjective("objective-deadline", now);
        var deadline = assertThrows(AutonomySafetyService.SafetyGateException.class, () ->
                deadlineSafety.reserveDispatch("objective-deadline", "d-deadline", 1, "AUTH-EXEC",
                        ExecutionWorkSpec.Consequence.READ_ONLY, 1.0, now.plusSeconds(1)));
        assertTrue(deadline.reason().contains("deadline"));
    }
}
