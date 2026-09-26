package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.interaction.FounderDefinedWorkerFormationService;
import com.metatron.workforce.management.AquacultureDomainPlanningCapability;
import com.metatron.workforce.management.AquacultureHeadAppointmentCapability;
import com.metatron.workforce.management.AquacultureHeadStaffingPolicy;
import com.metatron.workforce.management.GeneralWorkspaceAutonomousCapability;
import com.metatron.workforce.operating.PositionAddressResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Position-declared addressing: each Head declares its address aliases in its Position contract and the planner
 * routes by the declared alias through PositionAddressResolver, never by a planner-hardcoded name.
 */
class PositionAddressRoutingTest {
    private static final String HOA = AquacultureHeadAppointmentCapability.WORKER_ID;
    private static final String HOF = "WORKER-HEAD-OF-FISHERIES";
    private static final String FISHERIES_PLANNING = "fisheries.domain.planning";
    private static final String FISHERIES_REPORTING = "fisheries.reporting";
    private static final String DUPLICATE_PLANNING = "duplicate.domain.planning";
    private static final String BODY = "đọc DOMAINS/AQUACULTURE/EXECUTION_PLAN.md, DECISIONS.md, DOMAIN_PACK/*, "
            + "KNOWLEDGE/DOMAINS/AQUACULTURE/v2/gaps.yaml và COVERAGE.md; nộp ACTION_PLAN_v1 theo §7.";
    private static final List<String> AVAILABLE = List.of(
            AquacultureDomainPlanningCapability.CAPABILITY,
            AquacultureHeadAppointmentCapability.CAPABILITY,
            FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY,
            GeneralWorkspaceAutonomousCapability.CAPABILITY,
            FISHERIES_PLANNING,
            FISHERIES_REPORTING,
            DUPLICATE_PLANNING);
    private static final List<ExecutionWorkSpec> FRONTIER_PLAN = List.of(new ExecutionWorkSpec(
            "frontier", "frontier-planned work", "", FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY,
            List.of(), ExecutionWorkSpec.Consequence.READ_ONLY, List.of("done"), List.of("evidence")));

    @TempDir Path temp;

    private static PositionWorkRoute route(String capability) {
        return new PositionWorkRoute() {
            @Override public String capability() { return capability; }
            @Override public ExecutionWorkSpec work(String stepId, String objective) {
                return new ExecutionWorkSpec(stepId, objective, "kelvinka38/bios", capability, List.of(),
                        ExecutionWorkSpec.Consequence.MUTATING, List.of("plan proposed"), List.of("pr-url"));
            }
        };
    }

    private static final PositionWorkRoute FISHERIES_ROUTE = route(FISHERIES_PLANNING);

    private static ExecutionPlanProposalService noFrontier() {
        return (c, r, a) -> { throw new AssertionError("frontier must not be needed for position-addressed work"); };
    }

    private static List<ExecutionWorkSpec> plan(PositionAddressFixture fixture, ExecutionPlanProposalService frontier,
                                                String objective, String target) {
        return fixture.planner(frontier).propose("case-address",
                HeadOfAquaculturePlanningRouteTest.semantic(objective, target), AVAILABLE);
    }

    @Test
    void a1_declaredAliasesRouteToHoaAsOneMutatingPlanningStep() {
        PositionAddressFixture fixture = new PositionAddressFixture(temp).appointHeadOfAquaculture();
        for (String objective : List.of(
                "HOA: " + BODY,
                "Head of Aquaculture: " + BODY,
                "   hoa : " + BODY,
                "Take ownership of one Objective: HOA: " + BODY,
                "take ownership of one objective:   head of aquaculture: " + BODY)) {
            assertEquals(Optional.of(HOA), fixture.resolver.resolve(objective), objective);
            HeadOfAquaculturePlanningRouteTest.assertSingleGovernedPlanningStep(plan(fixture, noFrontier(), objective, ""));
        }
    }

    @Test
    void a1_hoaDeclaresItsAliasesInItsPositionContract() {
        assertEquals(List.of("HOA", "Head of Aquaculture"),
                new AquacultureHeadStaffingPolicy().positionContractSpec().addressAliases());
        assertEquals(AquacultureDomainPlanningCapability.CAPABILITY,
                new AquacultureHeadStaffingPolicy().positionContractSpec().primaryCapability());
        PositionAddressFixture fixture = new PositionAddressFixture(temp).appointHeadOfAquaculture();
        assertEquals(List.of("HOA", "Head of Aquaculture"), fixture.constitution
                .contractForPosition(AquacultureHeadAppointmentCapability.POSITION_REF).orElseThrow().addressAliases());
    }

    @Test
    void a2_aliasMentionedMidSentenceDoesNotRoute() {
        PositionAddressFixture fixture = new PositionAddressFixture(temp).appointHeadOfAquaculture();
        for (String objective : List.of(
                "Summarize the week and ask HOA: what is pending?",
                "Report what the Head of Aquaculture: said yesterday.",
                "Nhắc HOA nộp báo cáo tuần.",
                "HOA review the backlog")) {
            assertEquals(Optional.empty(), fixture.resolver.resolve(objective), objective);
            AtomicInteger frontierCalls = new AtomicInteger();
            List<ExecutionWorkSpec> plan = plan(fixture, (c, r, a) -> {
                frontierCalls.incrementAndGet();
                return FRONTIER_PLAN;
            }, objective, "");
            assertEquals(1, frontierCalls.get(), objective);
            assertTrue(plan.stream().noneMatch(s -> AquacultureDomainPlanningCapability.CAPABILITY.equals(s.requiredCapability())),
                    objective);
        }
    }

    @Test
    void a3_newPositionDeclaringAnAliasIsRoutableWithoutPlannerChanges() {
        PositionAddressFixture fixture = new PositionAddressFixture(temp).route(FISHERIES_ROUTE).appointHeadOfAquaculture()
                .appoint(PositionAddressFixture.position(HOF, "position:head-of-fisheries", "ROLE-HEAD-OF-FISHERIES",
                        FISHERIES_PLANNING, List.of("HOF")));

        assertEquals(Optional.of(HOF), fixture.resolver.resolve("HOF: prepare the fisheries plan"));
        List<ExecutionWorkSpec> plan = plan(fixture, noFrontier(), "HOF: prepare the fisheries plan", "");
        assertEquals(1, plan.size(), plan.toString());
        assertEquals(FISHERIES_PLANNING, plan.getFirst().requiredCapability());
        assertEquals(ExecutionWorkSpec.Consequence.MUTATING, plan.getFirst().consequence());

        // The HOA keeps its own route alongside the new Head.
        HeadOfAquaculturePlanningRouteTest.assertSingleGovernedPlanningStep(plan(fixture, noFrontier(), "HOA: " + BODY, ""));
    }

    @Test
    void a4_aliasDeclaredByTwoActiveWorkersIsAmbiguousAndNothingIsPlanned() {
        PositionAddressFixture fixture = new PositionAddressFixture(temp).route(route(DUPLICATE_PLANNING))
                .appointHeadOfAquaculture()
                .appoint(PositionAddressFixture.position("WORKER-HOA-DUPLICATE", "position:hoa-duplicate",
                        "ROLE-HOA-DUPLICATE", DUPLICATE_PLANNING, List.of("hoa")));

        PositionAddressResolver.AmbiguousAddressException resolved = assertThrows(
                PositionAddressResolver.AmbiguousAddressException.class, () -> fixture.resolver.resolve("HOA: " + BODY));
        assertTrue(resolved.getMessage().startsWith("AMBIGUOUS_ADDRESS"), resolved.getMessage());
        assertTrue(resolved.getMessage().contains(HOA) && resolved.getMessage().contains("WORKER-HOA-DUPLICATE"),
                resolved.getMessage());

        AtomicInteger frontierCalls = new AtomicInteger();
        PositionAddressResolver.AmbiguousAddressException planned = assertThrows(
                PositionAddressResolver.AmbiguousAddressException.class,
                () -> plan(fixture, (c, r, a) -> {
                    frontierCalls.incrementAndGet();
                    return FRONTIER_PLAN;
                }, "HOA: " + BODY, ""));
        assertTrue(planned.getMessage().startsWith("AMBIGUOUS_ADDRESS"), planned.getMessage());
        assertEquals(0, frontierCalls.get(), "an ambiguous address must not be guessed by the frontier planner");
    }

    @Test
    void a5_aliasOfRetiredOrInactiveWorkerDoesNotRoute() {
        PositionAddressFixture retired = new PositionAddressFixture(temp).appointHeadOfAquaculture();
        retired.core.setWorkerStatus(HOA, WorkforceCoreService.WorkerStatus.RETIRED);
        PositionAddressFixture suspended = new PositionAddressFixture(temp).appointHeadOfAquaculture();
        suspended.core.setParticipationStatus(new AquacultureHeadStaffingPolicy().formationSpec().participationId(),
                WorkforceCoreService.ParticipationStatus.SUSPENDED);

        for (PositionAddressFixture fixture : List.of(retired, suspended)) {
            assertEquals(Optional.empty(), fixture.resolver.resolve("HOA: " + BODY));
            AtomicInteger frontierCalls = new AtomicInteger();
            List<ExecutionWorkSpec> plan = plan(fixture, (c, r, a) -> {
                frontierCalls.incrementAndGet();
                return FRONTIER_PLAN;
            }, "HOA: " + BODY, "");
            assertEquals(1, frontierCalls.get());
            assertEquals(FRONTIER_PLAN, plan);
        }
    }

    @Test
    void a6_explicitWorkerIdAndRoleTargetWinOverAlias() {
        PositionAddressFixture fixture = new PositionAddressFixture(temp).route(FISHERIES_ROUTE).appointHeadOfAquaculture()
                .appoint(PositionAddressFixture.position(HOF, "position:head-of-fisheries", "ROLE-HEAD-OF-FISHERIES",
                        FISHERIES_PLANNING, List.of("HOF")));

        // Alias says HOA, explicit Worker id names another Worker: the explicit Worker wins.
        List<ExecutionWorkSpec> explicitWorker = plan(fixture, noFrontier(),
                "HOA: " + BODY + " Giao cho WORKER-COMPOSER-ARTIST.", "");
        assertEquals(1, explicitWorker.size(), explicitWorker.toString());
        assertEquals(FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY, explicitWorker.getFirst().requiredCapability());
        assertEquals("WORKER-COMPOSER-ARTIST", explicitWorker.getFirst().target());

        // Alias says HOF, explicit Worker id is the HOA: the HOA's route wins.
        HeadOfAquaculturePlanningRouteTest.assertSingleGovernedPlanningStep(
                plan(fixture, noFrontier(), "HOF: " + BODY, HOA));

        // Alias says HOF, target is the HOA role: the role wins.
        HeadOfAquaculturePlanningRouteTest.assertSingleGovernedPlanningStep(
                plan(fixture, noFrontier(), "HOF: " + BODY, AquacultureHeadAppointmentCapability.ROLE_REF));
    }

    @Test
    void routeFollowsDeclaredPrimaryCapabilityNotCapabilityOrder() {
        for (List<String> capabilities : List.of(
                List.of(FISHERIES_REPORTING, FISHERIES_PLANNING),
                List.of(FISHERIES_PLANNING, FISHERIES_REPORTING))) {
            PositionAddressFixture fixture = new PositionAddressFixture(temp)
                    .route(route(FISHERIES_REPORTING)).route(FISHERIES_ROUTE)
                    .appoint(PositionAddressFixture.position(HOF, "position:head-of-fisheries", "ROLE-HEAD-OF-FISHERIES",
                            capabilities, FISHERIES_PLANNING, List.of("HOF")));
            List<ExecutionWorkSpec> plan = plan(fixture, noFrontier(), "HOF: prepare the fisheries plan", "");
            assertEquals(1, plan.size(), capabilities + " -> " + plan);
            assertEquals(FISHERIES_PLANNING, plan.getFirst().requiredCapability(), capabilities.toString());
        }
    }

    @Test
    void a8_plannerCarriesNoHardcodedHeadNames() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/metatron/workforce/interaction/intelligence/FounderWorkerExecutionPlanProposalService.java"));
        assertFalse(source.contains("HOA"), "planner must not hardcode the HOA short name");
        assertFalse(source.contains("Head of Aquaculture"), "planner must not hardcode the HOA long name");
        assertFalse(source.contains("Aquaculture"), "planner must not depend on any Aquaculture type or name");
    }
}
