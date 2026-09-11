package com.metatron.workforce.actor;

import com.metatron.workforce.core.WorkforceCoreService;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class WorkerActorAssignmentReconcilerTest {
    @Test
    void canonicalAssignmentsProjectIntoIndependentActorMailboxesAndStates() {
        WorkforceCoreService core = new WorkforceCoreService();
        admit(core, "WORKER-A", "participant:a", "participation:a");
        admit(core, "WORKER-B", "participant:b", "participation:b");
        core.assign("ASG-A", "OBJ-A", "WORKER-A", "participation:a", "authority:a", "authorization:a", "work A");
        core.assign("ASG-B", "OBJ-B", "WORKER-B", "participation:b", "authority:b", "authorization:b", "work B");

        try (WorkerActorRuntime actors = new WorkerActorRuntime(new InMemoryWorkerActorStateStore(), 4);
             WorkerActorAssignmentReconciler reconciler = new WorkerActorAssignmentReconciler(core, actors, Duration.ofSeconds(1))) {
            reconciler.reconcileNow();

            assertEquals(WorkerActorState.READY, actors.requireActor("WORKER-A").state());
            assertEquals(WorkerActorState.READY, actors.requireActor("WORKER-B").state());
            assertEquals("ASG-A", actors.mailbox("WORKER-A").getFirst().assignmentId());
            assertEquals("ASG-B", actors.mailbox("WORKER-B").getFirst().assignmentId());
            assertEquals(1, actors.mailboxDepth("WORKER-A"));
            assertEquals(1, actors.mailboxDepth("WORKER-B"));

            core.transitionAssignment("ASG-A", WorkforceCoreService.AssignmentStatus.BLOCKED);
            reconciler.reconcileNow();
            assertEquals(WorkerActorState.BLOCKED, actors.requireActor("WORKER-A").state());
            assertEquals(WorkerActorState.READY, actors.requireActor("WORKER-B").state(),
                    "one blocked Worker must not block another actor");

            core.transitionAssignment("ASG-B", WorkforceCoreService.AssignmentStatus.COMPLETED);
            reconciler.reconcileNow();
            assertEquals(WorkerActorState.IDLE, actors.requireActor("WORKER-B").state());
            assertEquals(0, actors.mailboxDepth("WORKER-B"));
        }
    }

    private static void admit(WorkforceCoreService core, String workerId, String participantId, String participationId) {
        core.recognizeParticipant(participantId, WorkforceCoreService.ParticipantType.AI, "test:" + participantId);
        core.admitWorker(workerId, participantId);
        core.participate(participationId, workerId, "metatron", "position:" + workerId, "role:" + workerId);
        core.setAvailability(workerId, true, 1.0);
    }
}
