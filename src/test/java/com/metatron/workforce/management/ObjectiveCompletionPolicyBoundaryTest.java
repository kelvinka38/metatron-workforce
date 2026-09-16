package com.metatron.workforce.management;

import com.metatron.workforce.core.CompletionPolicy;
import com.metatron.workforce.interaction.intelligence.AnalyticalProtocolType;
import com.metatron.workforce.interaction.intelligence.CollaborationMode;
import com.metatron.workforce.interaction.intelligence.DeterministicCapability;
import com.metatron.workforce.interaction.intelligence.ExecutionObjectiveHandoff;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.IntelligenceDepth;
import com.metatron.workforce.interaction.intelligence.IntelligenceMode;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.interaction.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the authoritative Objective-level completion contract, starting from the real
 * HumanObjectiveIngressService boundary -- not by manually constructing an ExecutionWorkSpec with the
 * desired policy. ObjectiveCompletionPolicyResolver resolves the policy once, deterministically, from
 * already-normalized structured fields (requestedOutput / explicitProhibitions / constraints); the real
 * planner step in these tests declares no completion policy at all (matching real ExecutionWorkPlanner
 * output), and ManagementAutonomyService.recordPlan() re-applies the floor to whatever plan is actually
 * recorded, so the planner cannot omit or weaken it.
 */
class ObjectiveCompletionPolicyBoundaryTest {

    @Test
    void explicitPrOnlyObjectiveResolvesToPrRequiredAndPlannerStepIsRaisedToIt() {
        ManagementAutonomyService management = new ManagementAutonomyService();
        AtomicInteger executions = new AtomicInteger();
        AutonomousExecutionCapability capability = successfulCapability(executions);
        Clock clock = Clock.systemUTC();
        AutonomousManagementRunner runner = runner(management, List.of(capability), clock);
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(capability), runner, "worker-head", clock);

        NormalizedRequest request = executionRequest("Fix the bug and open a pull request; do not merge",
                List.of(), List.of("do not merge", "do not deploy"), defaultPlannerStep());

        ExecutionObjectiveHandoff.HandoffReceipt receipt = ingress.submit(
                "human-primary", "org-metatron", "case-pr-only", "conversation-1",
                "telegram:update:pr-1", "telegram", request);
        assertTrue(receipt.accepted());

        AutonomousObjectiveWork work = management.findAutonomousWork(receipt.objectiveId()).orElseThrow();
        assertEquals(CompletionPolicy.PR_REQUIRED, work.normalizedRequest().completionPolicy(),
                "the accepted Objective's stored NormalizedRequest must carry the resolved contract");

        runner.runOnce();

        AutonomousObjectiveWork afterPlanning = management.findAutonomousWork(receipt.objectiveId()).orElseThrow();
        assertTrue(afterPlanning.plannedWork().stream().allMatch(step -> step.completionPolicy() == CompletionPolicy.PR_REQUIRED),
                "the planner's recorded step (which declared no completion policy) must have been raised to PR_REQUIRED");
    }

    @Test
    void explicitProductionObjectiveResolvesToProductionRequiredAndPlannerOmissionCannotDowngrade() {
        ManagementAutonomyService management = new ManagementAutonomyService();
        AtomicInteger executions = new AtomicInteger();
        AutonomousExecutionCapability capability = successfulCapability(executions);
        Clock clock = Clock.systemUTC();
        AutonomousManagementRunner runner = runner(management, List.of(capability), clock);
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(capability), runner, "worker-head", clock);

        NormalizedRequest request = executionRequest("Fix the bug, deploy to production, and verify production",
                List.of(), List.of(), defaultPlannerStep());

        ExecutionObjectiveHandoff.HandoffReceipt receipt = ingress.submit(
                "human-primary", "org-metatron", "case-prod", "conversation-1",
                "telegram:update:prod-1", "telegram", request);

        AutonomousObjectiveWork work = management.findAutonomousWork(receipt.objectiveId()).orElseThrow();
        assertEquals(CompletionPolicy.PRODUCTION_REQUIRED, work.normalizedRequest().completionPolicy());

        runner.runOnce();

        AutonomousObjectiveWork afterPlanning = management.findAutonomousWork(receipt.objectiveId()).orElseThrow();
        assertTrue(afterPlanning.plannedWork().stream()
                .allMatch(step -> step.completionPolicy() == CompletionPolicy.PRODUCTION_REQUIRED),
                "the planner's omitted-policy step must be raised to PRODUCTION_REQUIRED, never left at EXECUTION_REQUIRED");
    }

    @Test
    void ordinaryExecutionOnlyObjectiveDefaultsToExecutionRequiredBackwardCompatible() {
        ManagementAutonomyService management = new ManagementAutonomyService();
        AtomicInteger executions = new AtomicInteger();
        AutonomousExecutionCapability capability = successfulCapability(executions);
        Clock clock = Clock.systemUTC();
        AutonomousManagementRunner runner = runner(management, List.of(capability), clock);
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(capability), runner, "worker-head", clock);

        NormalizedRequest request = executionRequest("Audit the repository and report findings",
                List.of(), List.of(), defaultPlannerStep());

        ExecutionObjectiveHandoff.HandoffReceipt receipt = ingress.submit(
                "human-primary", "org-metatron", "case-audit", "conversation-1",
                "telegram:update:audit-1", "telegram", request);

        AutonomousObjectiveWork work = management.findAutonomousWork(receipt.objectiveId()).orElseThrow();
        assertEquals(CompletionPolicy.EXECUTION_REQUIRED, work.normalizedRequest().completionPolicy());

        runner.runOnce();
        assertEquals(ManagementObjective.Status.COMPLETED, management.get(receipt.objectiveId()).status(),
                "ordinary execution-only work must still complete exactly as before");
    }

    @Test
    void doNotDeployProhibitionAloneNeverBecomesProductionRequiredEvenThoughWorkIsMutating() {
        ManagementAutonomyService management = new ManagementAutonomyService();
        AtomicInteger executions = new AtomicInteger();
        AutonomousExecutionCapability capability = successfulCapability(executions);
        Clock clock = Clock.systemUTC();
        AutonomousManagementRunner runner = runner(management, List.of(capability), clock);
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(capability), runner, "worker-head", clock);

        ExecutionWorkSpec mutatingStep = new ExecutionWorkSpec("step-1", "fix the bug", "target", "test.audit.read",
                List.of(), ExecutionWorkSpec.Consequence.MUTATING);
        NormalizedRequest request = executionRequest("Fix this and open/publish a PR; do not deploy",
                List.of(), List.of("do not deploy"), mutatingStep);

        ExecutionObjectiveHandoff.HandoffReceipt receipt = ingress.submit(
                "human-primary", "org-metatron", "case-no-escalate", "conversation-1",
                "telegram:update:no-escalate-1", "telegram", request);

        AutonomousObjectiveWork work = management.findAutonomousWork(receipt.objectiveId()).orElseThrow();
        assertEquals(CompletionPolicy.PR_REQUIRED, work.normalizedRequest().completionPolicy(),
                "MUTATING work with an explicit do-not-deploy prohibition must resolve to PR_REQUIRED, never silently escalate to PRODUCTION_REQUIRED");
    }

    private static AutonomousManagementRunner runner(ManagementAutonomyService management,
                                                     List<AutonomousExecutionCapability> capabilities,
                                                     Clock clock) {
        return new AutonomousManagementRunner(management,
                (caseId, request, available) -> request.executionWorkPlan(), capabilities, clock);
    }

    private static AutonomousExecutionCapability successfulCapability(AtomicInteger executions) {
        return new AutonomousExecutionCapability() {
            @Override public String capabilityRef() { return "test.audit.read"; }
            @Override public CapabilityResult execute(CapabilityRequest request) {
                executions.incrementAndGet();
                return new CapabilityResult(true, "worker-auditor", "assignment-1", "work-1",
                        List.of("evidence:test-pass"), "PASS");
            }
        };
    }

    private static ExecutionWorkSpec defaultPlannerStep() {
        return new ExecutionWorkSpec("step-1", "audit repository", "kelvinka38/metatron-workforce",
                "test.audit.read", List.of(), ExecutionWorkSpec.Consequence.READ_ONLY);
    }

    private static NormalizedRequest executionRequest(String requestedOutput, List<String> constraints,
            List<String> explicitProhibitions, ExecutionWorkSpec step) {
        return new NormalizedRequest(
                "objective text",
                "metatron-workforce",
                constraints,
                IntelligenceDepth.ANALYZE,
                requestedOutput,
                List.of(),
                explicitProhibitions,
                "current",
                "",
                IntelligenceMode.EXECUTION,
                CollaborationMode.SINGLE,
                List.<AnalyticalProtocolType>of(),
                DeterministicCapability.NONE,
                List.of(),
                List.of(step),
                false,
                null,
                LlmProvider.OPENAI,
                "");
    }
}
