package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.execution.governance.PlanEffectPolicy;
import com.metatron.workforce.interaction.FounderDefinedWorkerFormationService;
import com.metatron.workforce.management.AquacultureDomainPlanningCapability;
import com.metatron.workforce.management.AquacultureHeadAppointmentCapability;
import com.metatron.workforce.management.GeneralWorkspaceAutonomousCapability;
import com.metatron.workforce.testing.GovernanceTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3/P4: a Founder Objective addressed to the Head of Aquaculture plans to exactly ONE governed MUTATING
 * aquaculture.domain.planning step on kelvinka38/bios, whether it arrives as explicit Objective control,
 * as a semantic interpretation naming the HOA, or as the two-step frontier plan observed in production
 * (aquaculture.domain.planning/READ_ONLY + worker.cognitive.work). Planner composition is the production one.
 */
class HeadOfAquaculturePlanningRouteTest {
    static final String FOUNDER_TEXT =
            "HOA: đọc DOMAINS/AQUACULTURE/EXECUTION_PLAN.md, DECISIONS.md, DOMAIN_PACK/*, "
                    + "KNOWLEDGE/DOMAINS/AQUACULTURE/v2/gaps.yaml và COVERAGE.md; nộp ACTION_PLAN_v1 theo §7.";
    static final List<String> AVAILABLE = List.of(
            AquacultureDomainPlanningCapability.CAPABILITY,
            AquacultureHeadAppointmentCapability.CAPABILITY,
            FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY,
            GeneralWorkspaceAutonomousCapability.CAPABILITY,
            "research.web.search");

    @TempDir Path temp;

    /**
     * Production composition from ExecutionPlanningConfiguration, with the frontier planner as the seam and the
     * HOA appointed so its Position-declared address resolves.
     */
    private ExecutionPlanProposalService productionPlanner(ExecutionPlanProposalService frontier) {
        return new PositionAddressFixture(temp).appointHeadOfAquaculture().planner(frontier);
    }

    @Test
    void explicitObjectiveControlAddressedToHoaIsOneMutatingPlanningStepWithoutFrontier() {
        NormalizedRequest request = CanonicalObjectiveControlInterpreter
                .interpret("Take ownership of one Objective: " + FOUNDER_TEXT).orElseThrow();
        AtomicInteger frontierCalls = new AtomicInteger();
        List<ExecutionWorkSpec> plan = productionPlanner((c, r, a) -> {
            frontierCalls.incrementAndGet();
            return List.of();
        }).propose("case-hoa", request, AVAILABLE);
        assertSingleGovernedPlanningStep(plan);
        assertEquals(0, frontierCalls.get(), "HOA routing is deterministic; no frontier call");
    }

    @Test
    void semanticRequestNamingTheHoaWorkerIsOneMutatingPlanningStepNotGenericCognitiveWork() {
        for (NormalizedRequest request : List.of(
                semantic(FOUNDER_TEXT, AquacultureHeadAppointmentCapability.WORKER_ID),
                semantic("Assign WORKER-HEAD-OF-AQUACULTURE: " + FOUNDER_TEXT.substring(5), ""),
                semantic("Head of Aquaculture: prepare ACTION_PLAN_v1 per EXECUTION_PLAN §7.", ""))) {
            List<ExecutionWorkSpec> plan = productionPlanner((c, r, a) -> {
                throw new AssertionError("frontier must not be needed for explicit HOA work");
            }).propose("case-hoa", request, AVAILABLE);
            assertSingleGovernedPlanningStep(plan);
        }
    }

    @Test
    void productionObservedTwoStepFrontierPlanCollapsesToOneMutatingPlanningStep() {
        // Exactly the production failure: frontier produced aquaculture.domain.planning/READ_ONLY + worker.cognitive.work.
        NormalizedRequest request = semantic("Prepare the aquaculture ACTION_PLAN_v1 per EXECUTION_PLAN §7.", "");
        List<ExecutionWorkSpec> frontierPlan = List.of(
                new ExecutionWorkSpec("step-1", "Read the governance documents and plan", "kelvinka38/bios",
                        AquacultureDomainPlanningCapability.CAPABILITY, List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                        List.of("plan read"), List.of("evidence")),
                new ExecutionWorkSpec("step-2", "Write ACTION_PLAN_v1", "",
                        FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY, List.of("step-1"),
                        ExecutionWorkSpec.Consequence.READ_ONLY, List.of("plan written"), List.of("evidence")));
        List<ExecutionWorkSpec> plan = productionPlanner((c, r, a) -> frontierPlan).propose("case-hoa", request, AVAILABLE);
        assertSingleGovernedPlanningStep(plan);
    }

    @Test
    void unrelatedWorkIsNotRoutedToTheHoa() {
        AtomicInteger frontierCalls = new AtomicInteger();
        List<ExecutionWorkSpec> expected = List.of(new ExecutionWorkSpec("x", "Summarize the week", "",
                FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY, List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("done"), List.of("evidence")));
        List<ExecutionWorkSpec> plan = productionPlanner((c, r, a) -> {
            frontierCalls.incrementAndGet();
            return expected;
        }).propose("case-other", semantic("Summarize the week for the Founder.", ""), AVAILABLE);
        assertEquals(1, frontierCalls.get());
        assertTrue(plan.stream().noneMatch(s -> AquacultureDomainPlanningCapability.CAPABILITY.equals(s.requiredCapability())));
    }

    @Test
    void planningStepBindsUnderGovernanceAndPrPublishIsInThePlanEffectSet() {
        NormalizedRequest request = CanonicalObjectiveControlInterpreter
                .interpret("Take ownership of one Objective: " + FOUNDER_TEXT).orElseThrow();
        ExecutionWorkSpec step = productionPlanner((c, r, a) -> List.of()).propose("case-hoa", request, AVAILABLE).getFirst();

        assertTrue(PlanEffectPolicy.allowedActions(step).contains("workspace.github.pr.publish"));
        assertTrue(PlanEffectPolicy.allowedActions(step).contains("workspace.file.write"));
        assertFalse(PlanEffectPolicy.allowedActions(step).contains("workspace.shell.run"));

        Clock clock = Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC);
        GovernanceTestHarness harness = new GovernanceTestHarness(clock);
        GovernanceTestHarness.BoundMutation bound = harness.bind("objective-hoa", "human-primary",
                AquacultureHeadAppointmentCapability.WORKER_ID, "assignment-hoa",
                AquacultureDomainPlanningCapability.AUTHORIZATION_REFERENCE, "runtime-hoa", step);
        assertDoesNotThrow(() -> harness.permit(bound, "objective-hoa", AquacultureHeadAppointmentCapability.WORKER_ID,
                "assignment-hoa", AquacultureDomainPlanningCapability.AUTHORIZATION_REFERENCE, step.stepId(),
                "workspace.github.pr.publish", Map.of()));
    }

    static void assertSingleGovernedPlanningStep(List<ExecutionWorkSpec> plan) {
        assertEquals(1, plan.size(), "exactly one step: " + plan);
        ExecutionWorkSpec step = plan.getFirst();
        assertEquals(AquacultureDomainPlanningCapability.CAPABILITY, step.requiredCapability());
        assertEquals(ExecutionWorkSpec.Consequence.MUTATING, step.consequence());
        assertEquals(AquacultureDomainPlanningCapability.REPOSITORY, step.target());
        assertTrue(step.dependsOn().isEmpty());
    }

    static NormalizedRequest semantic(String objective, String target) {
        return new NormalizedRequest(
                objective, target, List.of(objective), IntelligenceDepth.ANALYZE,
                "ACTION_PLAN_v1 proposed as an unmerged pull request", List.of(), List.of(), "current", "",
                IntelligenceMode.EXECUTION, CollaborationMode.SINGLE, List.<AnalyticalProtocolType>of(),
                DeterministicCapability.NONE, List.of(), List.of(), false, null,
                com.metatron.workforce.interaction.llm.LlmProvider.OLLAMA, "");
    }
}
