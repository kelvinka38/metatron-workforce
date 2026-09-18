package com.metatron.workforce.management;

import com.metatron.workforce.action.ActionJournal;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkObservabilityActionHeartbeatTest {

    @Test
    void actionJournalActivityDrivesFreshnessRunningStateAndLiveEventProjection() {
        ManagementAutonomyService management = mock(ManagementAutonomyService.class);
        ActionJournal journal = mock(ActionJournal.class);
        WorkObservabilityController controller = new WorkObservabilityController(management, null, journal);

        Instant old = Instant.now().minusSeconds(900);
        Instant actionAt = Instant.now().minusSeconds(3);
        ManagementObjective objective = new ManagementObjective(
                "objective-live",
                "metatron-workforce",
                "organization:metatron",
                "execute governed work",
                ManagementObjective.Status.EXECUTING,
                List.of(),
                List.of(),
                old.minusSeconds(10),
                old);
        AutonomousObjectiveWork work = mock(AutonomousObjectiveWork.class);
        ExecutionWorkSpec step = new ExecutionWorkSpec(
                "step-1",
                "implement bounded work",
                "repository:kelvinka38/example",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("work product exists"),
                List.of("durable execution evidence"));
        ActionJournal.ActionRecord action = new ActionJournal.ActionRecord(
                actionAt,
                "objective-live",
                "step-1",
                "WORKER-GENERAL-ENGINEERING",
                "assignment-live",
                2,
                "workspace.file.write",
                "MUTATING",
                true,
                "workspace file written",
                Map.of("path", "src/main.txt"),
                Map.of("path", "src/main.txt"),
                List.of("action-fabric:action=workspace.file.write"),
                "CONTINUE",
                "continue implementation");

        when(work.humanId()).thenReturn("human-primary");
        when(work.status()).thenReturn(AutonomousObjectiveWork.Status.EXECUTING);
        when(work.terminal()).thenReturn(false);
        when(work.plannedWork()).thenReturn(List.of(step));
        when(work.completedStepIds()).thenReturn(List.of());
        when(work.evidenceReferences()).thenReturn(List.of());
        when(work.blocker()).thenReturn("");
        when(work.createdAt()).thenReturn(old);
        when(work.version()).thenReturn(3);
        when(management.get("objective-live")).thenReturn(objective);
        when(management.findAutonomousWork("objective-live")).thenReturn(Optional.of(work));
        when(management.history("objective-live")).thenReturn(List.of());
        when(journal.objectiveActionRecords("objective-live")).thenReturn(List.of(action));

        WorkObservabilityController.ObjectiveMonitorView view =
                controller.objective("objective-live", "human-primary");

        assertEquals("WORKING", view.humanStatus());
        assertEquals(actionAt, view.lastActivityAt());
        assertEquals("RUNNING", view.workItems().getFirst().status());
        assertEquals("EXECUTION_ACTIVITY_OBSERVED", view.executionProof().get("state"));
        assertEquals("ACTION_JOURNAL", view.executionProof().get("activity_source"));
        assertEquals("workspace.file.write", view.executionProof().get("latest_action"));
        assertEquals("2", view.executionProof().get("latest_action_cycle"));
        assertTrue(view.recentEvents().stream().anyMatch(event ->
                "ACTION_CYCLE".equals(event.type())
                        && event.detail().contains("action=workspace.file.write")
                        && event.detail().contains("result=PASS")));
    }
}
