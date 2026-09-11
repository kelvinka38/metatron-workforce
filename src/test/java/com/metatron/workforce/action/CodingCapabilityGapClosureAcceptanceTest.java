package com.metatron.workforce.action;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C1/C5 acceptance for the Codex-equivalent Worker coding lane on the existing Execution substrate. */
class CodingCapabilityGapClosureAcceptanceTest {
    private static final String PATH = "src/main/java/example/App.java";

    @Test
    void generalEngineeringProfileOwnsCodingLoopButNotReleaseAuthority() {
        WorkerRuntimeProfileBindingService.ToolProfile profile = WorkerRuntimeProfileBindingService.inMemory().profile(
                WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE, "execution.general.workspace");
        for (String required : List.of(
                "workspace.repository.materialize", "workspace.file.list", "workspace.file.search",
                "workspace.file.read", "workspace.file.patch", "workspace.file.write",
                "workspace.dependencies.install", "workspace.process.run", "workspace.shell.run",
                "workspace.build.run", "workspace.test.run", "workspace.git.status", "workspace.git.diff",
                "workspace.git.run", "workspace.github.pr.publish")) {
            assertTrue(profile.actionRefs().contains(required), "missing coding primitive: " + required);
        }
        assertFalse(profile.actionRefs().contains(InstitutionalActionAdapters.GITHUB_DEPLOY_DISPATCH),
                "coding profile must not self-grant production release authority");
    }

    @Test
    void workerCodingLoopRecoversAndCannotCompleteBeforeVerifiedProposal() {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "coding-gap-closure",
                "Inspect an unfamiliar defect, repair source, run tests, diagnose failures, retry after state change, "
                        + "create one local Git commit and publish a reviewable unmerged pull request. Do not merge or deploy.",
                "kelvinka38/example",
                "execution.general.workspace", List.of(), ExecutionWorkSpec.Consequence.MUTATING,
                List.of("defect repaired", "tests pass after latest source mutation", "reviewable pull request exists"),
                List.of("governed workspace.test.run evidence", "fresh authoritative GitHub API Observation"));
        List<String> actions = List.of(
                "workspace.repository.materialize", "workspace.file.search", "workspace.file.read",
                "workspace.file.patch", "workspace.file.write", "workspace.build.run", "workspace.test.run",
                "workspace.git.diff", "workspace.git.status", "workspace.git.run", "workspace.github.pr.publish");
        List<CognitiveWorkerRuntime.Cycle> history = new ArrayList<>();

        CognitiveWorkerRuntime.Thought materialize = GeneralCognitiveWorkerBrain.repositoryMaterializationPrecondition(
                context(work, actions, history));
        assertEquals("workspace.repository.materialize", materialize.actionRef());
        history.add(success(1, materialize.actionRef(), materialize.inputs()));

        history.add(success(2, "workspace.file.search", Map.of("query", "broken")));
        history.add(success(3, "workspace.file.read", Map.of("path", PATH)));
        history.add(success(4, "workspace.file.patch", Map.of(
                "path", PATH, "oldText", "broken", "newText", "attempt-one")));
        assertNull(GeneralCognitiveWorkerBrain.governedTestPrecondition(context(work, actions, history)),
                "unfamiliar/nested engineering leaves project scope to cognition");

        CognitiveWorkerRuntime.Cycle failedTest = failure(5, "workspace.test.run",
                Map.of("workingDirectory", "sample"), "tests still fail");
        history.add(failedTest);
        assertEquals(CognitiveWorkerRuntime.Decision.CONTINUE,
                GeneralCognitiveWorkerBrain.deterministicNonTerminalReflection(
                        context(work, actions, history), failedTest.observation()).decision());
        assertNull(GeneralCognitiveWorkerBrain.governedTestPrecondition(context(work, actions, history)),
                "identical failed test must not be blindly repeated");

        history.add(success(6, "workspace.file.read", Map.of("path", PATH)));
        history.add(success(7, "workspace.file.patch", Map.of(
                "path", PATH, "oldText", "attempt-one", "newText", "fixed")));
        history.add(success(8, "workspace.test.run", Map.of("workingDirectory", "sample")));

        CognitiveWorkerRuntime.Thought add = GeneralCognitiveWorkerBrain.governedGitPrecondition(
                context(work, actions, history));
        assertEquals("workspace.git.run", add.actionRef());
        assertTrue(add.inputs().get("argsJson").contains("add"));
        history.add(success(9, add.actionRef(), add.inputs()));

        CognitiveWorkerRuntime.Thought commit = GeneralCognitiveWorkerBrain.governedGitPrecondition(
                context(work, actions, history));
        assertEquals("workspace.git.run", commit.actionRef());
        assertTrue(commit.inputs().get("argsJson").contains("commit"));
        history.add(success(10, commit.actionRef(), commit.inputs()));

        CognitiveWorkerRuntime.Thought publish = GeneralCognitiveWorkerBrain.governedRemoteProposalPrecondition(
                context(work, actions, history));
        assertEquals("workspace.github.pr.publish", publish.actionRef());

        CognitiveWorkerRuntime.Reflection premature = GeneralCognitiveWorkerBrain.enforceRequiredActionCompletion(
                context(work, actions, history),
                ActionFabric.ActionObservation.success("workspace.git.status", "verified", Map.of(), List.of()),
                CognitiveWorkerRuntime.Reflection.complete("done"));
        assertEquals(CognitiveWorkerRuntime.Decision.CONTINUE, premature.decision());

        ActionFabric.ActionObservation published = ActionFabric.ActionObservation.success(
                "workspace.github.pr.publish", "published",
                Map.of("pullRequestUrl", "https://github.com/example/repo/pull/1"),
                List.of("github-general-proposal:true", "github-merge-performed:false"));
        CognitiveWorkerRuntime.Reflection required = GeneralCognitiveWorkerBrain.governedRequiredActionReflection(
                context(work, actions, history), published);
        assertEquals(CognitiveWorkerRuntime.Decision.COMPLETE,
                GeneralCognitiveWorkerBrain.enforceRequiredActionCompletion(
                        context(work, actions, history), published, required).decision());
    }

    private static CognitiveWorkerRuntime.CognitiveContext context(
            ExecutionWorkSpec work, List<String> actions, List<CognitiveWorkerRuntime.Cycle> history) {
        return new CognitiveWorkerRuntime.CognitiveContext(
                "worker", "assignment", "authorization", "objective", work, "idempotency",
                actions, List.copyOf(history), Map.of("workspaceMaterialized", history.isEmpty() ? "false" : "true"));
    }

    private static CognitiveWorkerRuntime.Cycle success(int number, String action, Map<String, String> inputs) {
        return new CognitiveWorkerRuntime.Cycle(number,
                new CognitiveWorkerRuntime.Thought(action, inputs, "acceptance"),
                ActionFabric.ActionObservation.success(action, "success", Map.of(), List.of("evidence:" + action)),
                CognitiveWorkerRuntime.Reflection.continueWith("continue"));
    }

    private static CognitiveWorkerRuntime.Cycle failure(
            int number, String action, Map<String, String> inputs, String summary) {
        return new CognitiveWorkerRuntime.Cycle(number,
                new CognitiveWorkerRuntime.Thought(action, inputs, "acceptance"),
                ActionFabric.ActionObservation.failure(action, summary, List.of("evidence:" + action + ":failure")),
                CognitiveWorkerRuntime.Reflection.continueWith("recover"));
    }
}
