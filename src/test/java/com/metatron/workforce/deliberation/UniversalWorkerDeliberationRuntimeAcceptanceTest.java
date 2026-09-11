package com.metatron.workforce.deliberation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class UniversalWorkerDeliberationRuntimeAcceptanceTest {
    @TempDir Path temp;

    @Test
    void underspecifiedComplexObjectiveDoesNotCollapseToFinalDelivery() {
        WorkerDeliberationRuntime runtime = runtime();
        WorkerDeliberationRuntime.Directive directive = runtime.prepare("worker-new-role", "Write a song", "");

        assertEquals(WorkerNextMove.CLARIFY, directive.state().nextMove());
        assertEquals(WorkerWorkStage.CLARIFYING, directive.state().stage());
        assertEquals("INSUFFICIENT", directive.state().contextSufficiency());
        assertTrue(directive.instructions().contains("do NOT produce the final deliverable"));
    }

    @Test
    void simpleDirectWorkAvoidsClarificationCeremony() {
        WorkerDeliberationRuntime runtime = runtime();
        WorkerDeliberationRuntime.Directive directive = runtime.prepare(
                "worker-any-role", "Rewrite this sentence to be friendlier: We rejected it.", "");

        assertEquals(WorkerNextMove.ACT, directive.state().nextMove());
        assertEquals("SUFFICIENT", directive.state().contextSufficiency());
    }

    @Test
    void detailedComplexObjectiveCanProceedAutonomouslyWithReviewAndVerification() {
        WorkerDeliberationRuntime runtime = runtime();
        String objective = "Design a product launch strategy. Target: existing SME customers; audience: operations heads; "
                + "budget: 50000 USD; deadline: 90 days; requirements: three channels, measurable conversion targets, "
                + "risks, owner cadence, and a final recommendation with explicit assumptions and constraints.";
        WorkerDeliberationRuntime.Directive directive = runtime.prepare("worker-brand-new-role", objective, "");

        assertEquals(WorkerNextMove.PLAN, directive.state().nextMove());
        assertEquals("SUFFICIENT", directive.state().contextSufficiency());
        assertTrue(directive.instructions().contains("critique it against objective/constraints"));
        assertTrue(directive.instructions().contains("verify before presenting a final deliverable"));
    }

    @Test
    void humanFollowUpContinuesSameObjectiveInsteadOfResetting() {
        WorkerDeliberationRuntime runtime = runtime();
        runtime.prepare("worker-continuity", "Write a song", "");
        runtime.completeTurn("worker-continuity", WorkerNextMove.CLARIFY);

        WorkerDeliberationRuntime.Directive followUp = runtime.prepare(
                "worker-continuity", "Make it dark, intimate, male vocal, around 90 BPM", "");

        assertEquals(WorkerNextMove.REVISE, followUp.state().nextMove());
        assertEquals("FEEDBACK", followUp.state().intent());
        assertEquals("Write a song", followUp.state().objectiveSummary());
    }

    @Test
    void stateSurvivesRuntimeRestartAndProviderSessionIndependence() {
        Path state = temp.resolve("deliberation.json");
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        WorkerDeliberationRuntime first = new WorkerDeliberationRuntime(state, json);
        first.prepare("worker-restart", "Build a system", "");
        first.completeTurn("worker-restart", WorkerNextMove.CLARIFY);

        WorkerDeliberationRuntime restarted = new WorkerDeliberationRuntime(state, json);
        WorkerDeliberationState restored = restarted.state("worker-restart");
        assertEquals(WorkerWorkStage.WAITING_FOR_HUMAN, restored.stage());
        assertEquals("Build a system", restored.objectiveSummary());
    }

    @Test
    void decoratorIsRoleAgnosticAndUsesExistingIntelligenceBoundary() {
        WorkerDeliberationRuntime runtime = runtime();
        AtomicReference<WorkerIntelligenceService.Request> observed = new AtomicReference<>();
        WorkerIntelligenceService provider = request -> {
            observed.set(request);
            return new WorkerIntelligenceService.Response("req-1", "I need the intended audience and mood first.", List.of("provider:test"));
        };
        DeliberatingWorkerIntelligenceService service = new DeliberatingWorkerIntelligenceService(provider, runtime);

        WorkerIntelligenceService.Response response = service.reason(new WorkerIntelligenceService.Request(
                "worker-role-created-tomorrow",
                "worker.live.conversation",
                "Act as your canonical institutional role.",
                "HUMAN MESSAGE\nWrite a song\n\nCONVERSATION CONTEXT\n",
                List.of("worker:test")));

        assertNotNull(observed.get());
        assertTrue(observed.get().instructions().contains("UNIVERSAL WORKER DELIBERATION CONTROL"));
        assertTrue(observed.get().instructions().contains("next_move=CLARIFY"));
        assertTrue(response.evidenceReferences().stream().anyMatch(v -> v.equals("worker-deliberation:next=CLARIFY")));
    }

    @Test
    void nonConversationWorkerCognitionIsNotReplacedByASecondIntelligenceStack() {
        WorkerDeliberationRuntime runtime = runtime();
        AtomicReference<WorkerIntelligenceService.Request> observed = new AtomicReference<>();
        WorkerIntelligenceService provider = request -> {
            observed.set(request);
            return new WorkerIntelligenceService.Response("req-2", "done", List.of());
        };
        DeliberatingWorkerIntelligenceService service = new DeliberatingWorkerIntelligenceService(provider, runtime);
        WorkerIntelligenceService.Request request = new WorkerIntelligenceService.Request(
                "worker-code", "worker.cognition", "inspect and reason", "repo context", List.of());

        service.reason(request);
        assertSame(request, observed.get());
    }

    private WorkerDeliberationRuntime runtime() {
        return new WorkerDeliberationRuntime(temp.resolve("state-" + System.nanoTime() + ".json"),
                new ObjectMapper().findAndRegisterModules());
    }
}
