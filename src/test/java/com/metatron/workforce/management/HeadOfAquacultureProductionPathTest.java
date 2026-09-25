package com.metatron.workforce.management;

import com.metatron.workforce.MetatronWorkforceApplication;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.interaction.ChannelInteractionIngressService;
import com.metatron.workforce.interaction.MetatronInteraction;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.phase3.ActorRef;
import com.metatron.workforce.runtime.WorkerResourceScopeService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * P1/P2 on the real production application context (same composition as production; no Worker cognition is
 * reached because the planning capability's execute is intercepted after dispatch).
 *
 * <p>P2: after start-up, with no Objective, the Head of Aquaculture already exists ACTIVE with its approved
 * capability bundle, domain-head profile and resource scope.</p>
 *
 * <p>P1: the Founder's Telegram Objective enters through the same channel-neutral ingress the Telegram webhook
 * uses (ChannelInteractionIngressService with the Telegram-shaped MetatronInteraction), then flows through
 * explicit Objective control → Workforce Objective intake → production planner → scheduler → staffing →
 * governed dispatch and reaches AquacultureDomainPlanningCapability.execute for WORKER-HEAD-OF-AQUACULTURE,
 * with one MUTATING governance-bound step.</p>
 */
@SpringBootTest(classes = MetatronWorkforceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HeadOfAquacultureProductionPathTest {
    private static final String HOA = AquacultureHeadAppointmentCapability.WORKER_ID;
    private static final String FOUNDER_OBJECTIVE = "Take ownership of one Objective: HOA: đọc "
            + "DOMAINS/AQUACULTURE/EXECUTION_PLAN.md, DECISIONS.md, DOMAIN_PACK/*, "
            + "KNOWLEDGE/DOMAINS/AQUACULTURE/v2/gaps.yaml và COVERAGE.md; nộp ACTION_PLAN_v1 theo §7.";
    private static final Path STATE = createState();
    private static final List<AutonomousExecutionCapability.CapabilityRequest> DISPATCHED = new CopyOnWriteArrayList<>();

    @MockitoSpyBean AquacultureDomainPlanningCapability planning;
    @Autowired ChannelInteractionIngressService ingress;
    @Autowired AutonomousManagementRunner runner;
    @Autowired ManagementAutonomyService management;
    @Autowired WorkforceCoreService core;
    @Autowired WorkerRuntimeProfileBindingService profiles;
    @Autowired WorkerResourceScopeService scopes;
    @Autowired HeadOfAquacultureBootstrapStatus bootstrap;

    private static Path createState() {
        try {
            return Files.createTempDirectory("hoa-production-path");
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @DynamicPropertySource
    static void productionShapedState(DynamicPropertyRegistry registry) {
        for (String[] entry : new String[][] {
                {"METATRON_INTELLIGENCE_CASE_PATH", "intelligence-cases"},
                {"METATRON_INTELLIGENCE_DEPTH_PATH", "intelligence-depth"},
                {"METATRON_INTELLIGENCE_ROUTING_FEEDBACK_PATH", "intelligence-routing-feedback.json"},
                {"METATRON_WORKPLACE_MEETING_PATH", "workplace/meetings"},
                {"METATRON_WORKFORCE_CORE_STATE_PATH", "workforce-core-state.json"},
                {"METATRON_WORKER_CONSTITUTION_STATE_PATH", "worker-constitution-state.json"},
                {"METATRON_WORKER_CONSTITUTION_RUNTIME_STATE_PATH", "worker-constitution-runtime-state.json"},
                {"METATRON_WORKER_ACTOR_STATE_PATH", "worker-actors"},
                {"METATRON_MANAGEMENT_STATE_PATH", "management-state.json"},
                {"METATRON_AUTONOMY_COORDINATION_STATE_PATH", "autonomy-coordination-state.json"},
                {"METATRON_AUTONOMY_SAFETY_STATE_PATH", "autonomy-safety-state.json"},
                {"METATRON_AUTONOMY_SCHEDULING_STATE_PATH", "autonomy-scheduling-state.json"},
                {"METATRON_OBSERVATION_STATE_PATH", "observation-state.json"},
                {"METATRON_EXECUTION_ATTEMPT_STATE_PATH", "execution-attempts.json"},
                {"METATRON_RUNTIME_STATE_DIR", "runtime-state"},
                {"METATRON_WORKFORCE_SCHEDULE_STATE_PATH", "work-schedules.json"},
                {"METATRON_WORKFORCE_STAFFING_STATE_PATH", "staffing-state.json"},
                {"METATRON_WORKFORCE_REVIEW_STATE_PATH", "review-state.json"},
                {"METATRON_WORKFORCE_WORK_STATE_PATH", "institutional-work.json"},
                {"METATRON_WORKPLACE_CONTINUITY_STATE_PATH", "workplace-continuity-state.json"},
                {"METATRON_RUNTIME_PROFILE_BINDINGS_PATH", "runtime-profile-bindings.tsv"},
                {"METATRON_WORKER_RESOURCE_SCOPES_PATH", "worker-resource-scopes.tsv"},
                {"METATRON_OBJECTIVE_WORKSPACE_ROOT", "objective-workspaces"},
                {"METATRON_CONVERSATION_MEMORY_PATH", "conversations"},
                {"METATRON_CONVERSATION_SURFACE_MODE_PATH", "conversation-surface-mode"},
                {"METATRON_FOUNDER_WORKER_PRODUCT_DIR", "founder-worker-products"},
                {"METATRON_INFERENCE_LEDGER_PATH", "inference-ledger.jsonl"},
                {"METATRON_COGNITION_CAPACITY_EVENT_PATH", "cognition-capacity-events.jsonl"},
                {"METATRON_COGNITIVE_ARTIFACT_PATH", "cognitive-artifacts"},
                {"METATRON_WORKER_DELIBERATION_PATH", "worker-deliberation.json"},
                {"METATRON_EXECUTION_WORKSPACE_BINDINGS_PATH", "execution-workspace-bindings.json"},
                {"METATRON_EXECUTION_WORKSPACE_ROOT", "executions"}}) {
            registry.add(entry[0], () -> STATE.resolve(entry[1]).toString());
        }
        registry.add("OPENAI_API_KEY", () -> "production-path-test-placeholder");
        registry.add("OPENAI_MODEL", () -> "production-path-test-model");
    }

    @Test
    void p2_afterStartupWithoutObjectiveHoaIsActiveCapableProfiledAndScoped() {
        assertEquals(HeadOfAquacultureBootstrapStatus.State.READY, bootstrap.snapshot().state(), bootstrap.snapshot().detail());
        assertEquals(WorkforceCoreService.WorkerStatus.ACTIVE, core.worker(HOA).status());
        Set<String> capabilities = core.capabilities(HOA).stream()
                .map(WorkforceCoreService.Capability::capabilityRef).collect(Collectors.toSet());
        assertTrue(capabilities.contains(AquacultureDomainPlanningCapability.CAPABILITY), capabilities.toString());
        assertTrue(capabilities.contains(AquacultureHeadAppointmentCapability.CAPABILITY), capabilities.toString());
        assertEquals(WorkerRuntimeProfileBindingService.DOMAIN_HEAD_PROFILE, profiles.requireBinding(HOA).profile().profileRef());
        assertEquals(AquacultureHeadStaffingPolicy.REPOSITORIES, scopes.find(HOA).orElseThrow().repositories());
    }

    @Test
    void p1_founderTelegramObjectiveReachesPlanningCapabilityForHoaAsOneMutatingGovernedStep() throws Exception {
        doAnswer(invocation -> {
            AutonomousExecutionCapability.CapabilityRequest request = invocation.getArgument(0);
            DISPATCHED.add(request);
            return new AutonomousExecutionCapability.CapabilityResult(false, request.allocatedWorkerId(),
                    request.assignmentReference(), "production-path-test", List.of("production-path-test:intercepted"),
                    "intercepted after dispatch; no Worker cognition in this test");
        }).when(planning).execute(any());

        MetatronInteraction telegram = new MetatronInteraction(
                new ActorRef("human-primary", ActorRef.ActorType.HUMAN),
                new ActorRef("metatron-workforce", ActorRef.ActorType.WORKER),
                "metatron",
                "conversation:human:human-primary",
                "telegram",
                "telegram:user:1001",
                "telegram:chat:1001",
                "telegram:update:hoa-production-path",
                FOUNDER_OBJECTIVE);
        String answer = ingress.handle(telegram).text();
        assertTrue(answer.contains("objective_id="), answer);
        String objectiveId = answer.lines().filter(line -> line.startsWith("objective_id="))
                .findFirst().orElseThrow().substring("objective_id=".length()).trim();

        Instant deadline = Instant.now().plus(Duration.ofSeconds(120));
        while (DISPATCHED.isEmpty() && Instant.now().isBefore(deadline)) {
            runner.wake();
            Thread.sleep(250);
        }
        assertFalse(DISPATCHED.isEmpty(), "planning capability never dispatched; management history: "
                + management.history(objectiveId));

        AutonomousExecutionCapability.CapabilityRequest request = DISPATCHED.getFirst();
        assertEquals(HOA, request.allocatedWorkerId());
        assertEquals(objectiveId, request.objectiveId());
        assertEquals(AquacultureDomainPlanningCapability.CAPABILITY, request.workSpec().requiredCapability());
        assertEquals(ExecutionWorkSpec.Consequence.MUTATING, request.workSpec().consequence());
        assertEquals(AquacultureDomainPlanningCapability.REPOSITORY, request.workSpec().target());
        assertTrue(request.governanceBound(), "MUTATING HOA planning must carry governance binding");
        assertEquals(AquacultureDomainPlanningCapability.AUTHORIZATION_REFERENCE, request.authorizationReference());

        List<ExecutionWorkSpec> planned = management.findAutonomousWork(objectiveId).orElseThrow().plannedWork();
        assertEquals(1, planned.size(), "HOA Objective must plan exactly one step: " + planned);
        assertEquals(AquacultureDomainPlanningCapability.CAPABILITY, planned.getFirst().requiredCapability());
    }
}
