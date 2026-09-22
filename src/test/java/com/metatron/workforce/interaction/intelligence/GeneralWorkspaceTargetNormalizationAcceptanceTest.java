package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.execution.governance.AuthorityManifestCatalog;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance coverage for the production incident on Telegram update 103337959: the frontier planner
 * independently selected {@code execution.general.workspace} for a fresh-application Objective --
 * without the Human ever naming WORKER-GENERAL-ENGINEERING -- and guessed an invalid generic target
 * ("workspace"), which {@link AuthorityManifestCatalog} correctly rejected with AUTHORITY_UNRESOLVED
 * before any work began. {@link ExecutionWorkPlanner#normalizeGeneralWorkspaceTargets} (exercised
 * through the public {@code plan(...)} entry point) must derive/preserve a governed
 * {@code repository:owner/repo} target for every {@code execution.general.workspace} step, reusing
 * {@link FounderWorkerExecutionPlanProposalService#governedRepositoryTarget(String)}, or reject the
 * plan outright as a planning defect when no governed target can be derived at all.
 */
final class GeneralWorkspaceTargetNormalizationAcceptanceTest {
    private static final String EXACT_PRODUCTION_OBJECTIVE_103337959 =
            "Create and deliver a small runnable web application called \"Runtime Acceptance App\"";

    @Test
    void frontierSelectedGeneralWorkspaceStepWithVagueTargetIsNormalizedToFreshApplicationRepository() {
        // Exact runtime-planned shape from the production incident: step-1 execution.general.workspace
        // (MUTATING, target="workspace"), step-2/3/4 host.commander.execute, step-5
        // repository.pr.propose -- reproduced verbatim, without any Worker name in the Objective.
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                return new LlmResponse(LlmProvider.GOOGLE, "planner-test", """
                        {"execution_work_plan":[
                          {"step_id":"step-1","objective":"Create the Runtime Acceptance App source and files","target":"workspace","required_capability":"execution.general.workspace","depends_on":[],"consequence":"MUTATING","acceptance_criteria":["application source exists"],"evidence_requirements":["general-workspace-execution durable work product/evidence"]},
                          {"step_id":"step-2","objective":"Prepare host runtime for verification","target":"host:metatron-production","required_capability":"host.commander.execute","depends_on":["step-1"],"consequence":"MUTATING","acceptance_criteria":["host prepared"],"evidence_requirements":["host-commander broker execution evidence"]},
                          {"step_id":"step-3","objective":"Run the application on the host","target":"host:metatron-production","required_capability":"host.commander.execute","depends_on":["step-2"],"consequence":"MUTATING","acceptance_criteria":["application runs"],"evidence_requirements":["host-commander broker execution evidence"]},
                          {"step_id":"step-4","objective":"Verify the running application responds","target":"host:metatron-production","required_capability":"host.commander.execute","depends_on":["step-3"],"consequence":"READ_ONLY","acceptance_criteria":["application responds"],"evidence_requirements":["host-commander broker execution evidence"]},
                          {"step_id":"step-5","objective":"Propose a pull request for the delivered application","target":"workspace","required_capability":"repository.pr.propose","depends_on":["step-4"],"consequence":"MUTATING","acceptance_criteria":["pull request opened"],"evidence_requirements":["durable pull request evidence"]}
                        ]}
                        """, "planner-ref");
            }
        };
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(google)), provider -> "planner-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());
        NormalizedRequest normalized = new NormalizedRequest(
                EXACT_PRODUCTION_OBJECTIVE_103337959,
                "", List.of(), IntelligenceDepth.DEEP, "a delivered runnable web application", List.of(), List.of(),
                "", "", IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.of(), DeterministicCapability.NONE, List.of(), List.of(),
                false, null, LlmProvider.GOOGLE, "");

        List<ExecutionWorkSpec> plan = planner.plan(
                "case:runtime-acceptance-103337959", normalized,
                List.of("execution.general.workspace", "host.commander.execute", "repository.pr.propose"));

        List<ExecutionWorkSpec> generalWorkspaceSteps = plan.stream()
                .filter(step -> "execution.general.workspace".equals(step.requiredCapability())).toList();
        assertTrue(!generalWorkspaceSteps.isEmpty(), "planner selected General Workspace as expected");
        assertTrue(generalWorkspaceSteps.stream().noneMatch(step -> "workspace".equals(step.target())),
                "no General Workspace step must keep the invalid generic target \"workspace\"");
        assertTrue(generalWorkspaceSteps.stream().allMatch(step ->
                        "repository:kelvinka38/runtime-acceptance-app".equals(step.target())),
                "fresh-app repository target must become repository:kelvinka38/runtime-acceptance-app");
        assertTrue(generalWorkspaceSteps.stream().allMatch(step ->
                        step.evidenceRequirements().stream()
                                .anyMatch("workspace-source:fresh-new-application"::equalsIgnoreCase)),
                "fresh-new-application marker must be present for a brand-new named application");

        // The real production AuthorityManifestCatalog must actually resolve this derived target --
        // proving no AUTHORITY_UNRESOLVED, not merely that the string looks plausible.
        AuthorityManifestCatalog catalog = AuthorityManifestCatalog.classpath();
        assertEquals("repository:metatron-canonical-four",
                catalog.resolve("repository:kelvinka38/runtime-acceptance-app").targetScope());

        // Untouched steps: host.commander.execute and repository.pr.propose keep their own targets and
        // ordering unmodified by this fix.
        assertEquals(5, plan.size());
        assertTrue(plan.stream()
                .filter(step -> "host.commander.execute".equals(step.requiredCapability()))
                .allMatch(step -> "host:metatron-production".equals(step.target())));
    }

    @Test
    void existingExplicitRepositoryGeneralWorkspaceTargetIsPreservedUnchanged() {
        // When the frontier planner already emits a valid canonical repository target, normalization
        // must preserve it verbatim rather than re-deriving a different destination from the Objective.
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                return new LlmResponse(LlmProvider.GOOGLE, "planner-test", """
                        {"execution_work_plan":[
                          {"step_id":"step-1","objective":"Fix the failing build against kelvinka38/metatron-workforce","target":"repository:kelvinka38/metatron-workforce","required_capability":"execution.general.workspace","depends_on":[],"consequence":"MUTATING","acceptance_criteria":["build fixed"],"evidence_requirements":["general-workspace-execution durable work product/evidence"]}
                        ]}
                        """, "planner-ref");
            }
        };
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(google)), provider -> "planner-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());
        NormalizedRequest normalized = new NormalizedRequest(
                "Fix the failing build against kelvinka38/metatron-workforce",
                "kelvinka38/metatron-workforce", List.of(), IntelligenceDepth.ANALYZE, "a fixed build",
                List.of(), List.of(), "", "", IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.of(), DeterministicCapability.NONE, List.of(), List.of(),
                false, null, LlmProvider.GOOGLE, "");

        List<ExecutionWorkSpec> plan = planner.plan(
                "case:general-workspace-explicit-repo", normalized,
                List.of("execution.general.workspace"));

        assertEquals(1, plan.size());
        assertEquals("repository:kelvinka38/metatron-workforce", plan.getFirst().target());
        assertTrue(plan.getFirst().evidenceRequirements().stream()
                        .noneMatch("workspace-source:fresh-new-application"::equalsIgnoreCase),
                "an explicitly named existing repository must not carry the fresh-new-application marker");
    }

    @Test
    void plannerRejectsGeneralWorkspaceStepWhenNoGovernedTargetCanBeDerivedAtAll() {
        // Invalid/ambiguous case: the frontier planner selects General Workspace with a vague target,
        // but the Objective names neither an explicit repository nor a new application ("called/named
        // X"), so no governed target can be safely derived. This must fail planning outright rather
        // than record a graph guaranteed to hit AUTHORITY_UNRESOLVED -- never weaken governance by
        // admitting a bare "workspace" authority target.
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                return new LlmResponse(LlmProvider.GOOGLE, "planner-test", """
                        {"execution_work_plan":[
                          {"step_id":"step-1","objective":"Clean up the workspace and get organized","target":"workspace","required_capability":"execution.general.workspace","depends_on":[],"consequence":"MUTATING","acceptance_criteria":["workspace organized"],"evidence_requirements":["general-workspace-execution durable work product/evidence"]}
                        ]}
                        """, "planner-ref");
            }
        };
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(google)), provider -> "planner-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());
        NormalizedRequest normalized = new NormalizedRequest(
                "Clean up the workspace and get organized",
                "", List.of(), IntelligenceDepth.ANALYZE, "an organized workspace", List.of(), List.of(),
                "", "", IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.of(), DeterministicCapability.NONE, List.of(), List.of(),
                false, null, LlmProvider.GOOGLE, "");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> planner.plan(
                "case:general-workspace-no-derivable-target", normalized,
                List.of("execution.general.workspace")));

        assertTrue(List.of(failure.getSuppressed()).stream()
                        .anyMatch(suppressed -> suppressed.getMessage() != null
                                && suppressed.getMessage().contains("execution_planning_defect")),
                "the planning defect must be exposed, not silently swallowed into an invalid plan");
    }

    @Test
    void ordinaryNonGeneralWorkspacePlansAreUnaffectedByThisNormalizer() {
        // Regression guard: a plan that never selects execution.general.workspace at all must pass
        // through completely unaffected by this normalizer.
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                return new LlmResponse(LlmProvider.GOOGLE, "planner-test", """
                        {"execution_work_plan":[{
                          "step_id":"audit-repo",
                          "objective":"audit repository and preserve evidence",
                          "target":"kelvinka38/bios",
                          "required_capability":"repository.audit.read",
                          "depends_on":[],
                          "consequence":"READ_ONLY",
                          "acceptance_criteria":["repository audit is complete and evidence-backed"],
                          "evidence_requirements":["repository contents and cited audit evidence"]
                        }]}
                        """, "planner-ref");
            }
        };
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(google)), provider -> "planner-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());
        NormalizedRequest normalized = new NormalizedRequest(
                "execute governed repository audit", "kelvinka38/bios", List.of("preserve evidence", "read-only"),
                IntelligenceDepth.ANALYZE, "terminal audit result", List.of(), List.of("do not mutate"),
                "current", "", IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.of(AnalyticalProtocolType.AUDIT), DeterministicCapability.NONE, List.of(), List.of(),
                false, null, LlmProvider.GOOGLE, "");

        List<ExecutionWorkSpec> plan = planner.plan("case:unrelated-audit", normalized,
                List.of("repository.audit.read"));

        assertEquals(1, plan.size());
        assertEquals("kelvinka38/bios", plan.getFirst().target());
    }
}
