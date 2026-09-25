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
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    /**
     * Every host state location the application declares (a METATRON_* property defaulting under /var, /opt or /srv)
     * is redirected into this test's temp directory. Discovered from the production sources rather than listed, so
     * state added later cannot leak: CI runs on the production host, where the real state directories are not
     * writable by the runner and must never be touched by a test.
     */
    private static final Pattern HOST_STATE_PROPERTY = Pattern.compile(
            "\\$\\{(METATRON_[A-Z0-9_]+):(?=(?:\\$\\{METATRON_[A-Z0-9_]+:)?/(?:var|opt|srv)/)");

    static Set<String> hostStateProperties() {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> sources = Files.walk(Path.of("src/main"))) {
            for (Path source : sources.filter(Files::isRegularFile).toList()) {
                Matcher matcher = HOST_STATE_PROPERTY.matcher(Files.readString(source));
                while (matcher.find()) names.add(matcher.group(1));
            }
        } catch (Exception failure) {
            throw new IllegalStateException("cannot discover host state properties", failure);
        }
        return names;
    }

    @DynamicPropertySource
    static void productionShapedState(DynamicPropertyRegistry registry) {
        Set<String> names = hostStateProperties();
        if (!names.contains("METATRON_WORKFORCE_CORE_STATE_PATH") || !names.contains("METATRON_EXECUTION_RESOURCE_STATE_PATH")) {
            throw new IllegalStateException("host state property discovery is broken: " + names);
        }
        for (String name : names) {
            registry.add(name, () -> STATE.resolve(name.toLowerCase(java.util.Locale.ROOT)).toString());
        }
        registry.add("OPENAI_API_KEY", () -> "production-path-test-placeholder");
        registry.add("OPENAI_MODEL", () -> "production-path-test-model");
    }

    @Test
    void hostStateIsFullyRedirected() {
        Set<String> names = hostStateProperties();
        assertTrue(names.size() >= 40, names.toString());
        assertTrue(names.containsAll(Set.of("METATRON_TELEGRAM_INGRESS_PATH", "METATRON_TELEGRAM_MEMORY_PATH",
                "METATRON_CONVERSATION_MEMORY_PATH", "METATRON_SOT_GOVERNANCE_STATE_PATH",
                "METATRON_EXECUTION_RESOURCE_QUEUE_PATH")), names.toString());
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
