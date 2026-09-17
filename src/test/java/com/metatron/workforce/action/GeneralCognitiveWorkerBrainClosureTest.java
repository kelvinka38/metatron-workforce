package com.metatron.workforce.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GeneralCognitiveWorkerBrainClosureTest {
    @Test
    void intelligenceBackedBrainUsesActualRuntimeCatalogAndCanReachCompleteReflection() {
        AtomicInteger calls = new AtomicInteger();
        WorkerIntelligenceService intelligence = request -> {
            int call = calls.incrementAndGet();
            assertEquals("worker.cognition", request.capability());
            if (call == 1) {
                assertTrue(request.instructions().contains("action-selection"));
                assertTrue(request.context().contains("workspace.file.write"));
                assertTrue(request.context().contains("repair defect"));
                return new WorkerIntelligenceService.Response(
                        "intelligence-think",
                        "{\"actionRef\":\"workspace.file.write\",\"inputs\":{\"path\":\"result.txt\",\"content\":\"fixed\"},\"rationale\":\"write required work product\"}",
                        List.of("intelligence-provider:test"));
            }
            assertTrue(request.context().contains("workspace file written"));
            return new WorkerIntelligenceService.Response(
                    "intelligence-reflect",
                    "{\"decision\":\"COMPLETE\",\"summary\":\"required work product is now observed\"}",
                    List.of("intelligence-provider:test"));
        };
        GeneralCognitiveWorkerBrain brain = new GeneralCognitiveWorkerBrain(intelligence, new ObjectMapper());
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "step-1", "repair defect", "workspace", "execution.general.workspace",
                List.of(), ExecutionWorkSpec.Consequence.MUTATING,
                List.of("result exists"), List.of("workspace-state"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker-1", "assignment-1", "auth-1", "objective-1", work, "idem-1",
                List.of("workspace.file.read", "workspace.file.write"), List.of(), Map.of());

        CognitiveWorkerRuntime.Thought thought = brain.think(context);
        assertEquals("workspace.file.write", thought.actionRef());
        assertEquals("result.txt", thought.inputs().get("path"));

        ActionFabric.ActionObservation observation = new ActionFabric.ActionObservation(
                "workspace.file.write", true, "workspace file written",
                Map.of("path", "result.txt"), List.of("objective-workspace:evidence"), Instant.now());
        CognitiveWorkerRuntime.Reflection reflection = brain.reflect(context, observation);
        assertEquals(CognitiveWorkerRuntime.Decision.COMPLETE, reflection.decision());
        assertEquals(2, calls.get());
        assertTrue(brain.evidenceReferences().stream().anyMatch(value ->
                value.startsWith("cognitive-intelligence-request:")));
    }

    @Test
    void successfulIntermediateActionUsesDeterministicContinueWhenCompletionGuardProvesMoreEvidenceIsRequired() {
        WorkerIntelligenceService intelligence = request -> {
            throw new AssertionError("non-terminal reflection must not spend Intelligence");
        };
        GeneralCognitiveWorkerBrain brain = new GeneralCognitiveWorkerBrain(intelligence, new ObjectMapper());
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "repair-and-publish",
                "Repair the defect, run tests, create one local Git commit, and publish a reviewable unmerged pull request",
                "kelvinka38/metatron-workforce",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("tests pass after the requested source change",
                        "reviewable pull request exists for the committed work product",
                        "remote proposal remains unmerged"),
                List.of("governed test action evidence", "fresh authoritative GitHub API Observation"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker-1", "assignment-1", "auth-1", "objective-1", work, "idem-1",
                List.of("workspace.file.read", "workspace.file.patch", "workspace.test.run",
                        "workspace.git.run", "workspace.github.pr.publish"),
                List.of(), Map.of());

        CognitiveWorkerRuntime.Reflection reflection = brain.reflect(
                context,
                ActionFabric.ActionObservation.success(
                        "workspace.file.read", "source inspected",
                        Map.of("path", "slugify.py"), List.of("workspace-read:evidence")));

        assertEquals(CognitiveWorkerRuntime.Decision.CONTINUE, reflection.decision());
        assertTrue(reflection.summary().contains("mandatory completion evidence is still missing"));
    }

    @Test
    void failedActionUsesDeterministicRecoveryReflectionWithoutSpendingIntelligence() {
        WorkerIntelligenceService intelligence = request -> {
            throw new AssertionError("failed action recovery reflection must not spend Intelligence");
        };
        GeneralCognitiveWorkerBrain brain = new GeneralCognitiveWorkerBrain(intelligence, new ObjectMapper());
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "repair",
                "Inspect an unfamiliar defect, repair it, and run tests until they pass",
                "kelvinka38/example",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("tests pass after the latest source mutation"),
                List.of("governed workspace.test.run evidence"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker-1", "assignment-1", "auth-1", "objective-1", work, "idem-1",
                List.of("workspace.file.search", "workspace.file.read", "workspace.file.patch", "workspace.test.run"),
                List.of(), Map.of());

        CognitiveWorkerRuntime.Reflection reflection = brain.reflect(
                context,
                ActionFabric.ActionObservation.failure(
                        "workspace.test.run", "tests failed with assertion mismatch", List.of("test-run:failed")));

        assertEquals(CognitiveWorkerRuntime.Decision.CONTINUE, reflection.decision());
        assertTrue(reflection.summary().contains("tests failed with assertion mismatch"));
    }

    @Test
    void generalBrainTreatsUnrequestedProcessFailureAsOptionalButExplicitBuildFailureAsRequired() {
        GeneralCognitiveWorkerBrain brain = new GeneralCognitiveWorkerBrain(
                request -> { throw new AssertionError("classification must not call Intelligence"); },
                new ObjectMapper());
        ExecutionWorkSpec repair = new ExecutionWorkSpec(
                "repair",
                "Find the root cause, repair the source, run tests, commit, and publish an unmerged pull request",
                "kelvinka38/metatron-workforce",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("tests pass", "reviewable pull request exists"),
                List.of("governed test action evidence"));
        CognitiveWorkerRuntime.CognitiveContext repairContext = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "auth", "objective", repair, "idem",
                List.of("workspace.process.run", "workspace.test.run", "workspace.github.pr.publish"),
                List.of(), Map.of());

        assertFalse(brain.blocksCompletionForUnresolvedFailure(repairContext, "workspace.process.run"));
        assertTrue(brain.blocksCompletionForUnresolvedFailure(repairContext, "workspace.test.run"));
        assertTrue(brain.blocksCompletionForUnresolvedFailure(repairContext, "workspace.github.pr.publish"));

        ExecutionWorkSpec buildRequired = new ExecutionWorkSpec(
                "build",
                "Repair the project and build the verified artifact",
                "kelvinka38/metatron-workforce",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("build succeeds"), List.of("build evidence"));
        CognitiveWorkerRuntime.CognitiveContext buildContext = new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "auth", "objective-build", buildRequired, "idem-build",
                List.of("workspace.build.run"), List.of(), Map.of());
        assertTrue(brain.blocksCompletionForUnresolvedFailure(buildContext, "workspace.build.run"));
    }

    @Test
    void malformedIntelligenceResponseFailsClosedAtWorkerBoundary() {
        WorkerIntelligenceService intelligence = request -> new WorkerIntelligenceService.Response(
                "intelligence-malformed", "not-json", List.of("intelligence-provider:test"));
        GeneralCognitiveWorkerBrain brain = new GeneralCognitiveWorkerBrain(intelligence, new ObjectMapper());
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "step-1", "repair defect", "workspace", "execution.general.workspace",
                List.of(), ExecutionWorkSpec.Consequence.MUTATING,
                List.of("defect repaired"), List.of("workspace evidence"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker-1", "assignment-1", "auth-1", "objective-1", work, "idem-1",
                List.of("workspace.file.read", "workspace.file.patch"), List.of(), Map.of());

        assertThrows(IllegalStateException.class, () -> brain.think(context));
    }

    @Test
    void topNResearchCannotCompleteWithNarrativeClaimOrTooFewObservedSources() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "general-external-research",
                "Find and shortlist exactly 5 useful Vietnam Mother & Baby regulatory research items",
                "Vietnam consumer protection influencer trust risk scoring",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of(
                        "Exactly 5 substantive candidates",
                        "Each candidate ends in KEEP, TEST, CHANGE, or REJECT"),
                List.of("research-action:research.web.search", "externally attributable source URLs"));

        List<CognitiveWorkerRuntime.Cycle> history = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String url = "https://authority.example/source-" + i;
            history.add(new CognitiveWorkerRuntime.Cycle(
                    i,
                    new CognitiveWorkerRuntime.Thought(
                            GeneralWebResearchAction.ACTION_REF, Map.of("query", "Vietnam research " + i), "research"),
                    ActionFabric.ActionObservation.success(
                            GeneralWebResearchAction.ACTION_REF, "research result",
                            Map.of("researchResult", "result " + i), List.of(url)),
                    CognitiveWorkerRuntime.Reflection.continueWith("more evidence required")));
        }
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker-1", "assignment-1", "auth-1", "objective-1", work, "idem-1",
                List.of(GeneralWebResearchAction.ACTION_REF), history, Map.of());
        ActionFabric.ActionObservation latest = ActionFabric.ActionObservation.success(
                GeneralWebResearchAction.ACTION_REF, "research result",
                Map.of("researchResult", "result 4"), List.of("https://authority.example/source-4"));

        List<String> problems = GeneralCognitiveWorkerBrain.researchCompletionQualityProblems(
                context, latest,
                "We successfully identified and synthesized five candidates with KEEP, TEST, CHANGE, REJECT, KEEP.");

        assertTrue(problems.stream().anyMatch(value -> value.contains("5 distinct attributable research sources")));
        assertTrue(problems.stream().anyMatch(value -> value.contains("enumerated 5-item")));
        assertTrue(problems.stream().anyMatch(value -> value.contains("5 observed source URLs")));
    }

    @Test
    void topNResearchCompletionPassesOnlyWithEnumeratedObservedSourcesAndDecisions() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "general-external-research",
                "Find exactly 5 research items",
                "Vietnam Mother & Baby consumer protection",
                "execution.general.workspace",
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("Each candidate ends in KEEP, TEST, CHANGE, or REJECT"),
                List.of("research-action:research.web.search"));

        List<CognitiveWorkerRuntime.Cycle> history = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            String url = "https://authority.example/source-" + i;
            history.add(new CognitiveWorkerRuntime.Cycle(
                    i,
                    new CognitiveWorkerRuntime.Thought(
                            GeneralWebResearchAction.ACTION_REF, Map.of("query", "Vietnam research " + i), "research"),
                    ActionFabric.ActionObservation.success(
                            GeneralWebResearchAction.ACTION_REF, "research result",
                            Map.of("researchResult", "result " + i), List.of(url)),
                    CognitiveWorkerRuntime.Reflection.continueWith("more evidence required")));
        }
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker-1", "assignment-1", "auth-1", "objective-1", work, "idem-1",
                List.of(GeneralWebResearchAction.ACTION_REF), history, Map.of());
        ActionFabric.ActionObservation latest = ActionFabric.ActionObservation.success(
                GeneralWebResearchAction.ACTION_REF, "research result",
                Map.of("researchResult", "result 5"), List.of("https://authority.example/source-5"));

        String summary = """
                1. Item one
                Source: https://authority.example/source-1
                Decision: KEEP
                2. Item two
                Source: https://authority.example/source-2
                Decision: TEST
                3. Item three
                Source: https://authority.example/source-3
                Decision: CHANGE
                4. Item four
                Source: https://authority.example/source-4
                Decision: KEEP
                5. Item five
                Source: https://authority.example/source-5
                Decision: REJECT
                Highest-potential experiment: test item two.
                """;

        assertTrue(GeneralCognitiveWorkerBrain.researchCompletionQualityProblems(context, latest, summary).isEmpty());
    }


    @Test
    void actionSelectionReceivesMaterializedWorkerConstitutionFromRuntimeMemory() {
        WorkerIntelligenceService intelligence = request -> {
            assertTrue(request.instructions().contains("workerConstitution"));
            assertTrue(request.instructions().contains("assignment-scoped cognitive projection"));
            assertTrue(request.instructions().contains("Role or runtime profile never self-grants authority"));
            assertTrue(request.context().contains("\"workerConstitution\""));
            assertTrue(request.context().contains("WORKER_COGNITION_CONTEXT_V1"));
            assertTrue(request.context().contains("authority=policy:bounded:test"));
            return new WorkerIntelligenceService.Response(
                    "intelligence-constitution-grounded",
                    "{\"actionRef\":\"workspace.file.read\",\"inputs\":{\"path\":\"README.md\"},\"rationale\":\"inspect within bound constitution\"}",
                    List.of("intelligence-provider:test"));
        };
        GeneralCognitiveWorkerBrain brain = new GeneralCognitiveWorkerBrain(intelligence, new ObjectMapper());
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "inspect", "Inspect the requested source before changing it", "workspace",
                "execution.general.workspace", List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("source inspected"), List.of("read evidence"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker-1", "assignment-1", "authorization-1", "objective-1", work, "idem-1",
                List.of("workspace.file.read"), List.of(),
                Map.of("workerConstitution",
                        "WORKER_COGNITION_CONTEXT_V1=assignment-scoped bounded projection\n"
                                + "source_snapshot_ref=worker-constitution-runtime:test\n"
                                + "assignment=assignment-1:authority=policy:bounded:test"));

        CognitiveWorkerRuntime.Thought thought = brain.think(context);

        assertEquals("workspace.file.read", thought.actionRef());
        assertEquals("README.md", thought.inputs().get("path"));
    }

    @Test
    void oversizedCognitiveContextFailsClosedBeforeCallingIntelligence() {
        AtomicInteger calls = new AtomicInteger();
        WorkerIntelligenceService intelligence = request -> {
            calls.incrementAndGet();
            throw new AssertionError("oversized context must be rejected before Intelligence/provider invocation");
        };
        GeneralCognitiveWorkerBrain brain = new GeneralCognitiveWorkerBrain(intelligence, new ObjectMapper());
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "step-budget", "repair defect", "workspace", "execution.general.workspace",
                List.of(), ExecutionWorkSpec.Consequence.MUTATING,
                List.of("defect repaired"), List.of("workspace evidence"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker-1", "assignment-1", "auth-1", "objective-1", work, "idem-1",
                List.of("workspace.file.read"), List.of(),
                Map.of("workerConstitution", "x".repeat(GeneralCognitiveWorkerBrain.MAX_CONTEXT_PROMPT_CHARS + 1)));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> brain.think(context));

        assertTrue(failure.getMessage().startsWith("worker-cognition-request-context-budget-exceeded:"));
        assertEquals(0, calls.get());
    }

    @Test
    void recentCycleOutputsAreExplicitlyCompactedInsidePromptBudget() {
        WorkerIntelligenceService intelligence = request -> {
            assertTrue(request.context().contains("EXPLICITLY_COMPACTED"));
            assertTrue(request.context().length() <= GeneralCognitiveWorkerBrain.MAX_CONTEXT_PROMPT_CHARS);
            return new WorkerIntelligenceService.Response(
                    "intelligence-compacted-history",
                    "{\"actionRef\":\"workspace.file.read\",\"inputs\":{\"path\":\"README.md\"},\"rationale\":\"inspect current state\"}",
                    List.of("intelligence-provider:test"));
        };
        GeneralCognitiveWorkerBrain brain = new GeneralCognitiveWorkerBrain(intelligence, new ObjectMapper());
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "step-history", "inspect after large observation", "workspace", "execution.general.workspace",
                List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("source inspected"), List.of("read evidence"));
        CognitiveWorkerRuntime.Cycle prior = new CognitiveWorkerRuntime.Cycle(
                1,
                new CognitiveWorkerRuntime.Thought("workspace.file.read", Map.of("path", "large.txt"), "inspect"),
                ActionFabric.ActionObservation.success(
                        "workspace.file.read", "large observation", Map.of("content", "y".repeat(25_000)), List.of("read:evidence")),
                CognitiveWorkerRuntime.Reflection.continueWith("inspect more"));
        CognitiveWorkerRuntime.CognitiveContext context = new CognitiveWorkerRuntime.CognitiveContext(
                "worker-1", "assignment-1", "auth-1", "objective-1", work, "idem-1",
                List.of("workspace.file.read"), List.of(prior), Map.of());

        CognitiveWorkerRuntime.Thought thought = brain.think(context);

        assertEquals("workspace.file.read", thought.actionRef());
    }

}
