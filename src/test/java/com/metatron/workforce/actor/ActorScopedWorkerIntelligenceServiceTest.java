package com.metatron.workforce.actor;

import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActorScopedWorkerIntelligenceServiceTest {
    @Test
    void sharedIntelligenceIsScopedThroughCanonicalWorkerActor() {
        try (WorkerActorRuntime actors = new WorkerActorRuntime(new InMemoryWorkerActorStateStore(), 4)) {
            WorkerIntelligenceService delegate = request -> new WorkerIntelligenceService.Response(
                    "provider-request:" + request.requester(),
                    "answer for " + request.requester(),
                    List.of("provider:test"));
            WorkerIntelligenceService scoped = new ActorScopedWorkerIntelligenceService(delegate, actors);

            WorkerIntelligenceService.Response composer = scoped.reason(new WorkerIntelligenceService.Request(
                    "WORKER-COMPOSER", "worker.cognition", "compose", "objective_id=OBJ-C\nassignment_reference=ASG-C\nstep_id=S1", List.of()));
            WorkerIntelligenceService.Response analyst = scoped.reason(new WorkerIntelligenceService.Request(
                    "WORKER-ANALYST", "worker.cognition", "analyze", "objective_id=OBJ-A\nassignment_reference=ASG-A\nstep_id=S2", List.of()));

            assertEquals("answer for WORKER-COMPOSER", composer.text());
            assertEquals("answer for WORKER-ANALYST", analyst.text());
            assertNotEquals(actors.requireActor("WORKER-COMPOSER").actorId(), actors.requireActor("WORKER-ANALYST").actorId());
            assertEquals(1, actors.mailbox("WORKER-COMPOSER").size());
            assertEquals(1, actors.mailbox("WORKER-ANALYST").size());
            assertEquals("OBJ-C", actors.mailbox("WORKER-COMPOSER").getFirst().objectiveId());
            assertEquals("ASG-C", actors.mailbox("WORKER-COMPOSER").getFirst().assignmentId());
            assertEquals(WorkerActorMessage.Status.COMPLETED, actors.mailbox("WORKER-COMPOSER").getFirst().status());
        }
    }
}
