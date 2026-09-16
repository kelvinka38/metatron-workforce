package com.metatron.workforce.management;

import com.metatron.workforce.core.CompletionPolicy;
import com.metatron.workforce.interaction.intelligence.AnalyticalProtocolType;
import com.metatron.workforce.interaction.intelligence.CollaborationMode;
import com.metatron.workforce.interaction.intelligence.DeterministicCapability;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.IntelligenceDepth;
import com.metatron.workforce.interaction.intelligence.IntelligenceMode;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.interaction.llm.LlmProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the resolver's negative-signal semantics: a prohibition ("do not merge"/"do not deploy") is a
 * ceiling, never by itself a deliverable requirement. Exercised through the real HumanObjectiveIngressService
 * boundary, same as ObjectiveCompletionPolicyBoundaryTest.
 */
class ObjectiveCompletionPolicyResolverNegativeSignalTest {

    @Test
    void auditOnlyWithDoNotDeployProhibitionAndNoPrDeliverableStaysExecutionRequired() {
        ManagementAutonomyService management = new ManagementAutonomyService();
        AtomicInteger executions = new AtomicInteger();
        AutonomousExecutionCapability capability = successfulCapability(executions);
        var clock = java.time.Clock.systemUTC();
        AutonomousManagementRunner runner = new AutonomousManagementRunner(management,
                (caseId, request, available) -> request.executionWorkPlan(), List.of(capability), clock);
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(capability), runner, "worker-head", clock);

        NormalizedRequest request = executionRequest("Audit production configuration only",
                List.of(), List.of("do not deploy anything"));

        var receipt = ingress.submit("human-primary", "org-metatron", "case-audit-only", "conversation-1",
                "telegram:update:audit-only-1", "telegram", request);

        AutonomousObjectiveWork work = management.findAutonomousWork(receipt.objectiveId()).orElseThrow();
        assertEquals(CompletionPolicy.EXECUTION_REQUIRED, work.normalizedRequest().completionPolicy(),
                "a prohibition alone, with no explicit PR/deliverable request, must not create PR_REQUIRED");
    }

    @Test
    void modifyAndOpenPrWithDoNotMergeProhibitionResolvesPrRequired() {
        ManagementAutonomyService management = new ManagementAutonomyService();
        AtomicInteger executions = new AtomicInteger();
        AutonomousExecutionCapability capability = successfulCapability(executions);
        var clock = java.time.Clock.systemUTC();
        AutonomousManagementRunner runner = new AutonomousManagementRunner(management,
                (caseId, request, available) -> request.executionWorkPlan(), List.of(capability), clock);
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(capability), runner, "worker-head", clock);

        NormalizedRequest request = executionRequest("Fix the bug and open a pull request",
                List.of(), List.of("do not merge", "do not deploy"));

        var receipt = ingress.submit("human-primary", "org-metatron", "case-pr", "conversation-1",
                "telegram:update:pr-2", "telegram", request);

        AutonomousObjectiveWork work = management.findAutonomousWork(receipt.objectiveId()).orElseThrow();
        assertEquals(CompletionPolicy.PR_REQUIRED, work.normalizedRequest().completionPolicy());
    }

    @Test
    void deployAndVerifyProductionResolvesProductionRequired() {
        ManagementAutonomyService management = new ManagementAutonomyService();
        AtomicInteger executions = new AtomicInteger();
        AutonomousExecutionCapability capability = successfulCapability(executions);
        var clock = java.time.Clock.systemUTC();
        AutonomousManagementRunner runner = new AutonomousManagementRunner(management,
                (caseId, request, available) -> request.executionWorkPlan(), List.of(capability), clock);
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(capability), runner, "worker-head", clock);

        NormalizedRequest request = executionRequest("Deploy to production and verify production",
                List.of(), List.of());

        var receipt = ingress.submit("human-primary", "org-metatron", "case-prod", "conversation-1",
                "telegram:update:prod-2", "telegram", request);

        AutonomousObjectiveWork work = management.findAutonomousWork(receipt.objectiveId()).orElseThrow();
        assertEquals(CompletionPolicy.PRODUCTION_REQUIRED, work.normalizedRequest().completionPolicy());
    }

    @Test
    void contradictoryDeployAndDoNotDeployNeverSilentlySelectsProductionRequired() {
        ManagementAutonomyService management = new ManagementAutonomyService();
        AtomicInteger executions = new AtomicInteger();
        AutonomousExecutionCapability capability = successfulCapability(executions);
        var clock = java.time.Clock.systemUTC();
        AutonomousManagementRunner runner = new AutonomousManagementRunner(management,
                (caseId, request, available) -> request.executionWorkPlan(), List.of(capability), clock);
        HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                management, List.of(capability), runner, "worker-head", clock);

        NormalizedRequest request = executionRequest("Deploy to production and verify production",
                List.of(), List.of("do not deploy"));

        var receipt = ingress.submit("human-primary", "org-metatron", "case-contradictory", "conversation-1",
                "telegram:update:contradictory-1", "telegram", request);

        AutonomousObjectiveWork work = management.findAutonomousWork(receipt.objectiveId()).orElseThrow();
        assertNotEquals(CompletionPolicy.PRODUCTION_REQUIRED, work.normalizedRequest().completionPolicy(),
                "a contradictory request must never silently resolve to the strongest policy");
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

    private static NormalizedRequest executionRequest(String requestedOutput, List<String> constraints,
            List<String> explicitProhibitions) {
        ExecutionWorkSpec step = new ExecutionWorkSpec("step-1", "do the work", "kelvinka38/metatron-workforce",
                "test.audit.read", List.of(), ExecutionWorkSpec.Consequence.READ_ONLY);
        return new NormalizedRequest(
                "objective text", "metatron-workforce", constraints, IntelligenceDepth.ANALYZE,
                requestedOutput, List.of(), explicitProhibitions, "current", "",
                IntelligenceMode.EXECUTION, CollaborationMode.SINGLE, List.<AnalyticalProtocolType>of(),
                DeterministicCapability.NONE, List.of(), List.of(step), false, null, LlmProvider.OPENAI, "");
    }
}
