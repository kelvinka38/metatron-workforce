package com.metatron.workforce.management;

import com.metatron.workforce.action.ActionJournal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkCardRendererTest {
    @Test
    void resolvesActualPerformerFromDurableAutonomousStepEvidence() {
        var performers = WorkCardRenderer.performersByStep(List.of(
                "unrelated:evidence",
                "autonomous-step:step-1:capability=repo.audit:work=work-1:worker=worker-a:dispatch=d-1:idempotency=i-1",
                "autonomous-step:step-2:capability=repo.fix:work=work-2:worker=worker-b:dispatch=d-2:idempotency=i-2"));

        assertEquals("worker-a", performers.get("step-1"));
        assertEquals("worker-b", performers.get("step-2"));
    }

    @Test
    void neverInventsPerformerWithoutExecutionAttribution() {
        var performers = WorkCardRenderer.performersByStep(List.of(
                "staffing:reused-worker=worker-a",
                "assignment:some-ref"));

        assertFalse(performers.containsKey("step-1"));
    }

    @Test
    void monitorPrefersRecentNonTerminalObjectiveOverRecentTerminalOrStaleWork() {
        ManagementAutonomyService management = mock(ManagementAutonomyService.class);
        WorkCardRenderer renderer = new WorkCardRenderer(management, null);
        Instant now = Instant.now();

        ManagementObjective staleActive = objective("stale-active", ManagementObjective.Status.BLOCKED, now.minusSeconds(3600));
        ManagementObjective recentTerminal = objective("recent-terminal", ManagementObjective.Status.COMPLETED, now.minusSeconds(10));
        ManagementObjective recentActive = objective("recent-active", ManagementObjective.Status.EXECUTING, now.minusSeconds(60));

        AutonomousObjectiveWork staleActiveWork = work("human-primary", false);
        AutonomousObjectiveWork recentTerminalWork = work("human-primary", true);
        AutonomousObjectiveWork recentActiveWork = work("human-primary", false);

        when(management.allObjectives()).thenReturn(List.of(staleActive, recentTerminal, recentActive));
        when(management.findAutonomousWork("stale-active")).thenReturn(Optional.of(staleActiveWork));
        when(management.findAutonomousWork("recent-terminal")).thenReturn(Optional.of(recentTerminalWork));
        when(management.findAutonomousWork("recent-active")).thenReturn(Optional.of(recentActiveWork));

        assertEquals(Optional.of("recent-active"), renderer.latestObjectiveIdForHuman("human-primary"));
    }

    @Test
    void recentDurableActionCycleMakesExecutingCardWorkingInsteadOfStale() {
        ManagementAutonomyService management = mock(ManagementAutonomyService.class);
        ActionJournal journal = mock(ActionJournal.class);
        WorkCardRenderer renderer = new WorkCardRenderer(management, null, journal);
        Instant old = Instant.now().minusSeconds(900);
        Instant actionAt = Instant.now().minusSeconds(5);
        ManagementObjective objective = objective("active-objective", ManagementObjective.Status.EXECUTING, old);
        AutonomousObjectiveWork work = mock(AutonomousObjectiveWork.class);
        ActionJournal.ActionRecord action = new ActionJournal.ActionRecord(
                actionAt,
                "active-objective",
                "step-1",
                "WORKER-GENERAL-ENGINEERING",
                "assignment-1",
                4,
                "workspace.file.write",
                "MUTATING",
                true,
                "workspace file written",
                java.util.Map.of("path", "src/app.java"),
                java.util.Map.of("path", "src/app.java"),
                List.of("action-fabric:action=workspace.file.write"),
                "CONTINUE",
                "continue implementation");

        when(work.humanId()).thenReturn("human-primary");
        when(work.terminal()).thenReturn(false);
        when(work.updatedAt()).thenReturn(old);
        when(work.completedStepIds()).thenReturn(List.of());
        when(work.evidenceReferences()).thenReturn(List.of());
        when(work.plannedWork()).thenReturn(List.of());
        when(work.status()).thenReturn(AutonomousObjectiveWork.Status.EXECUTING);
        when(work.blocker()).thenReturn("");
        when(management.get("active-objective")).thenReturn(objective);
        when(management.findAutonomousWork("active-objective")).thenReturn(Optional.of(work));
        when(management.history("active-objective")).thenReturn(List.of());
        when(journal.objectiveActionRecords("active-objective")).thenReturn(List.of(action));

        String card = renderer.render("active-objective");

        assertFalse(card.contains("HISTORICAL / STALE OBJECTIVE"));
        assertTrue(card.contains("STATUS     🟢 WORKING"));
        assertTrue(card.contains("ACTIVITY   cycle 4 · workspace.file.write · PASS"));
        assertTrue(card.contains("OBSERVED durable action cycle 4 · workspace.file.write"));
        assertTrue(card.contains("LAST EVENT " + actionAt));
    }

    @Test
    void staleMonitorCardIsExplicitlyLabeledHistorical() {
        ManagementAutonomyService management = mock(ManagementAutonomyService.class);
        WorkCardRenderer renderer = new WorkCardRenderer(management, null);
        Instant old = Instant.now().minusSeconds(1800);
        ManagementObjective objective = objective("stale-objective", ManagementObjective.Status.BLOCKED, old);
        AutonomousObjectiveWork work = mock(AutonomousObjectiveWork.class);

        when(work.humanId()).thenReturn("human-primary");
        when(work.terminal()).thenReturn(false);
        when(work.updatedAt()).thenReturn(old);
        when(work.completedStepIds()).thenReturn(List.of());
        when(work.evidenceReferences()).thenReturn(List.of());
        when(work.plannedWork()).thenReturn(List.of());
        when(work.status()).thenReturn(AutonomousObjectiveWork.Status.BLOCKED);
        when(work.blocker()).thenReturn("old blocker");
        when(management.get("stale-objective")).thenReturn(objective);
        when(management.findAutonomousWork("stale-objective")).thenReturn(Optional.of(work));
        when(management.history("stale-objective")).thenReturn(List.of());

        String card = renderer.render("stale-objective");

        assertTrue(card.contains("HISTORICAL / STALE OBJECTIVE"));
        assertTrue(card.contains("LAST EVENT " + old));
    }

    @Test
    void anInFlightCpuCognitionCallIsNotLabeledStale() {
        // Production 2026-09-24 (case-0e3a655f): qwen3:4b was generating at ~10 tokens/s, 5 minutes into
        // one bounded Worker cognition call, and the Work Card already read "HISTORICAL / STALE OBJECTIVE"
        // and "WAITING FOR EXECUTION PROOF" because freshness was a 90 s window.
        ManagementAutonomyService management = mock(ManagementAutonomyService.class);
        ActionJournal journal = mock(ActionJournal.class);
        WorkCardRenderer renderer = new WorkCardRenderer(management, null, journal);
        Instant lastAction = Instant.now().minusSeconds(300);
        ManagementObjective objective = objective("cpu-objective", ManagementObjective.Status.EXECUTING, lastAction);
        AutonomousObjectiveWork work = mock(AutonomousObjectiveWork.class);
        ActionJournal.ActionRecord action = new ActionJournal.ActionRecord(
                lastAction, "cpu-objective", "step-1", "WORKER-GENERAL-ENGINEERING", "assignment-1", 1,
                "workspace.repository.materialize", "MUTATING", true, "materialized",
                java.util.Map.of(), java.util.Map.of(), List.of(), "CONTINUE", "continue");

        when(work.humanId()).thenReturn("human-primary");
        when(work.terminal()).thenReturn(false);
        when(work.updatedAt()).thenReturn(lastAction);
        when(work.completedStepIds()).thenReturn(List.of());
        when(work.evidenceReferences()).thenReturn(List.of());
        when(work.plannedWork()).thenReturn(List.of());
        when(work.status()).thenReturn(AutonomousObjectiveWork.Status.EXECUTING);
        when(work.blocker()).thenReturn("");
        when(management.get("cpu-objective")).thenReturn(objective);
        when(management.findAutonomousWork("cpu-objective")).thenReturn(Optional.of(work));
        when(management.history("cpu-objective")).thenReturn(List.of());
        when(journal.objectiveActionRecords("cpu-objective")).thenReturn(List.of(action));

        String card = renderer.render("cpu-objective");

        assertFalse(card.contains("STALE"), card);
        assertTrue(card.contains("STATUS     🟢 WORKING"), card);
        assertTrue(WorkCardRenderer.FRESH_ACTIVITY_WINDOW.toMillis() > 660_000L,
                "freshness must outlast one bounded cognition call (Workforce cognition HTTP timeout)");
    }

    private static ManagementObjective objective(String id, ManagementObjective.Status status, Instant updatedAt) {
        return new ManagementObjective(
                id,
                "metatron-workforce",
                "organization:metatron",
                "test objective",
                status,
                List.of(),
                List.of(),
                updatedAt.minusSeconds(10),
                updatedAt);
    }

    private static AutonomousObjectiveWork work(String humanId, boolean terminal) {
        AutonomousObjectiveWork work = mock(AutonomousObjectiveWork.class);
        when(work.humanId()).thenReturn(humanId);
        when(work.terminal()).thenReturn(terminal);
        return work;
    }
}
