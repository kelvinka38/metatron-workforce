package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.action.GeneralCognitiveWorkerBrainFactory;
import com.metatron.workforce.action.GeneralWorkspaceActionCatalog;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import com.metatron.workforce.operating.WorkerConstitutionService;
import com.metatron.workforce.runtime.GitHubWorkspaceProposalPublisher;
import com.metatron.workforce.runtime.ObjectiveWorkspaceService;
import com.metatron.workforce.runtime.RepositoryWorkspaceMaterializationService;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.WorkerExecutionSandboxService;
import com.metatron.workforce.runtime.WorkerResourceScopeService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import com.metatron.workforce.testing.GovernanceTestHarness;
import com.metatron.workforce.testing.LocalExecutionServers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * T6: Founder Objective → governed HOA planning capability → real CognitiveWorkerRuntime/ActionFabric loop over
 * the DOMAIN_HEAD catalog (real sandbox git, real resource-scope checks) with only the LLM boundary scripted →
 * evidence lists every required governance input read file-by-file → ACTION_PLAN_v1.md has all seven §7 parts →
 * the published (stubbed) GitHub PR contains only paths under the HOA write prefixes, unmerged.
 */
class HeadOfAquacultureActionPlanEndToEndAcceptanceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-25T16:00:00Z"), ZoneOffset.UTC);
    private static final String HOA = AquacultureHeadAppointmentCapability.WORKER_ID;
    private static final String FOUNDER_OBJECTIVE =
            "HOA: đọc DOMAINS/AQUACULTURE/EXECUTION_PLAN.md, DECISIONS.md, DOMAIN_PACK/*, "
                    + "KNOWLEDGE/DOMAINS/AQUACULTURE/v2/gaps.yaml và COVERAGE.md; nộp ACTION_PLAN_v1 theo §7.";
    private static final List<String> REQUIRED_READS = List.of(
            "DOMAINS/AQUACULTURE/EXECUTION_PLAN.md",
            "DOMAINS/AQUACULTURE/DECISIONS.md",
            "DOMAINS/AQUACULTURE/DOMAIN_PACK/README.md",
            "DOMAINS/AQUACULTURE/DOMAIN_PACK/01_CHARTER.md",
            "DOMAINS/AQUACULTURE/DOMAIN_PACK/02_CURRENT_STATE.md",
            "DOMAINS/AQUACULTURE/DOMAIN_PACK/03_KNOWLEDGE_MAP.md",
            "DOMAINS/AQUACULTURE/DOMAIN_PACK/04_TASK_FORMATS.md",
            "KNOWLEDGE/DOMAINS/AQUACULTURE/v2/gaps.yaml",
            "KNOWLEDGE/DOMAINS/AQUACULTURE/v2/COVERAGE.md");

    @TempDir Path temp;

    @Test
    void founderObjectiveProducesSevenPartActionPlanReadFileByFileAndPublishedOnlyUnderAllowedPaths() throws Exception {
        assumeTrue(LocalExecutionServers.toolAvailable("git"), "git not available on PATH");

        // Planner output for the Founder Objective: one governed MUTATING step owned by the HOA planning capability.
        ExecutionWorkSpec step = AquacultureDomainPlanningCapability.actionPlanWork("hoa-action-plan-v1", FOUNDER_OBJECTIVE);
        assertEquals(AquacultureDomainPlanningCapability.CAPABILITY, step.requiredCapability());
        assertEquals("kelvinka38/bios", step.target());
        for (String path : REQUIRED_READS) {
            assertFalse(step.objective().contains(path),
                    "file paths travel in governed memory, never in the brain-scanned work text: " + path);
        }

        GovernanceTestHarness harness = new GovernanceTestHarness(CLOCK);
        harness.plans.bindAuthorizedWork("objective:hoa-precheck", "founder", step,
                "FOUNDER", "authorization:hoa-precheck", Map.of());

        // Governed appointment through the real staffing policy.
        WorkforceCoreService core = new WorkforceCoreService();
        WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
        WorkerResourceScopeService scopes = WorkerResourceScopeService.inMemory();
        AutonomousStaffingService staffing = new AutonomousStaffingService(core,
                List.of(new AquacultureHeadStaffingPolicy()), profiles, WorkerConstitutionService.inMemory(
                com.metatron.workforce.operating.PositionRouteCatalog.of(List.of(AquacultureDomainPlanningCapability.CAPABILITY))), scopes);
        staffing.ensureStaffed(new AquacultureHeadAppointmentCapability(core, profiles, scopes), CLOCK.instant());

        LocalExecutionServers.RealProcessSandboxServer sandbox =
                new LocalExecutionServers.RealProcessSandboxServer(temp.resolve("sandbox-root"));
        LocalExecutionServers.FakeGitHubApiServer github = new LocalExecutionServers.FakeGitHubApiServer("kelvinka38/bios");
        try {
            ObjectiveWorkspaceService workspaces = new ObjectiveWorkspaceService(temp.resolve("sandbox-root"));
            ObjectMapper json = new ObjectMapper();
            HttpClient http = HttpClient.newHttpClient();
            WorkerExecutionSandboxService sandboxClient = new WorkerExecutionSandboxService(
                    http, URI.create("http://127.0.0.1:" + sandbox.port()), LocalExecutionServers.RealProcessSandboxServer.TOKEN,
                    profiles, workspaces, json);
            RepositoryWorkspaceMaterializationService repositories =
                    new RepositoryWorkspaceMaterializationService(http, "test-github-token", workspaces, json);
            GitHubWorkspaceProposalPublisher proposals = new GitHubWorkspaceProposalPublisher(
                    http, "test-github-token", workspaces, sandboxClient, json,
                    URI.create("http://127.0.0.1:" + github.port() + "/"));
            GeneralWorkspaceActionCatalog catalog = new GeneralWorkspaceActionCatalog(
                    workspaces, sandboxClient, profiles, repositories, proposals, json, scopes);
            ScriptedHoaIntelligence scripted = new ScriptedHoaIntelligence();
            AquacultureDomainPlanningCapability planning = new AquacultureDomainPlanningCapability(
                    catalog, new GeneralCognitiveWorkerBrainFactory(scripted, json), profiles, workspaces, harness.gate);

            GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                    planning, core, harness.admission, CLOCK, null, harness.attempts,
                    new RuntimeCapacityCoordinator(new RuntimeRegistry()), harness.plans, harness.attemptBindings,
                    harness.gate);
            ManagementAutonomyService management = new ManagementAutonomyService();
            governed.onAssignmentCreated((objectiveId, assignmentId) -> management.addAssignmentReference(
                    objectiveId, management.get(objectiveId).ownerWorkerId(), assignmentId, CLOCK.instant()));

            String objectiveId;
            AutonomousObjectiveWork work;
            try (AutonomousManagementRunner runner = new AutonomousManagementRunner(
                    management, (caseId, normalized, available) -> normalized.executionWorkPlan(),
                    List.of(governed), new AutonomyCoordinationService(), CLOCK,
                    "runner-hoa-action-plan", Duration.ofMinutes(5), Duration.ofSeconds(1), 1)) {
                HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                        management, List.of(governed), runner, "worker-head", CLOCK);
                var receipt = ingress.submit("human-primary", "bios", "case-hoa-action-plan",
                        "conversation-hoa-action-plan", "message-hoa-action-plan", "workplace", request(step));
                objectiveId = receipt.objectiveId();
                seedBiosCheckout(workspaces, objectiveId, github.baseSha());
                runner.runOnce();
                work = management.findAutonomousWork(objectiveId).orElseThrow();
                assertEquals(Set.of(step.stepId()), Set.copyOf(work.completedStepIds()),
                        "blocker: " + work.blocker() + " history: " + management.history(objectiveId));
            }

            // Evidence: every required governance input was read, one governed workspace.file.read per file.
            List<String> evidence = work.evidenceReferences();
            String joined = String.join("\n", evidence);
            for (String path : REQUIRED_READS) {
                assertEquals(1, evidence.stream().filter(("hoa-input-read:" + path)::equals).count(),
                        "exactly one read evidence for " + path + "\n" + joined);
            }
            assertTrue(scripted.selectionCalls() >= 1, "the LLM seam authored the plan");
            assertTrue(joined.contains("hoa-model-identity:"), joined);
            assertTrue(joined.contains("hoa-pr-url:http://127.0.0.1:" + github.port() + "/pr/1"), joined);
            assertTrue(joined.contains("github-merge-performed:false"), joined);

            // The plan file has all seven EXECUTION_PLAN §7 parts.
            Path root = workspaces.provision(objectiveId, HOA).path();
            String plan = Files.readString(root.resolve(AquacultureDomainPlanningCapability.ACTION_PLAN_V1_PATH));
            for (String part : AquacultureDomainPlanningCapability.SECTION_7_PARTS) {
                assertTrue(plan.contains("## " + part), "missing §7 part: " + part);
            }

            // The published PR carried only paths under the HOA write prefixes, and nothing was merged.
            assertEquals(1, github.pullRequestsCreated());
            assertEquals(0, github.mergeCalls());
            assertFalse(github.publishedTreePaths().isEmpty());
            for (String published : github.publishedTreePaths()) {
                assertTrue(published.startsWith("DOMAINS/AQUACULTURE/ACTION_PLANS/")
                        || published.startsWith("DOMAINS/AQUACULTURE/REPORTS/"), published);
            }
            assertTrue(sandbox.executables().stream().allMatch("git"::equals), sandbox.executables().toString());
        } finally {
            sandbox.stop();
            github.stop();
        }
    }

    private static void seedBiosCheckout(ObjectiveWorkspaceService workspaces, String objectiveId, String baseSha)
            throws Exception {
        ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces.provision(objectiveId, HOA);
        Files.writeString(workspace.path().resolve(".metatron-repository"),
                "repository=kelvinka38/bios\nrequestedRef=main\ncommitSha=" + baseSha + "\ncomponentId=primary\n");
        for (String path : REQUIRED_READS) {
            Path file = workspace.path().resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "# " + path + "\n\nfixture content for " + path + "\n");
        }
    }

    private static NormalizedRequest request(ExecutionWorkSpec step) {
        return new NormalizedRequest(
                FOUNDER_OBJECTIVE, HOA,
                List.of("execute under governed Workforce Assignment attribution"),
                com.metatron.workforce.interaction.intelligence.IntelligenceDepth.ANALYZE,
                "ACTION_PLAN_v1 proposed as an unmerged pull request",
                List.of(), List.of("do not claim completion without evidence"),
                "current", "",
                com.metatron.workforce.interaction.intelligence.IntelligenceMode.EXECUTION,
                com.metatron.workforce.interaction.intelligence.CollaborationMode.SINGLE,
                List.<com.metatron.workforce.interaction.intelligence.AnalyticalProtocolType>of(),
                com.metatron.workforce.interaction.intelligence.DeterministicCapability.NONE,
                List.of(), List.of(step), false, null,
                com.metatron.workforce.interaction.llm.LlmProvider.OLLAMA, "");
    }

    /**
     * Deterministic stand-in for the LLM boundary only: writes the §7 plan once every required input has
     * been read. Materialize, the per-file required reads, git add/commit and PR publish are selected by
     * deterministic governed preconditions (HOA capability + GeneralCognitiveWorkerBrain).
     */
    private static final class ScriptedHoaIntelligence implements WorkerIntelligenceService {
        private static final ObjectMapper JSON = new ObjectMapper();
        private static final Pattern AVAILABLE = Pattern.compile("\"availableActions\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger selections = new AtomicInteger();
        private boolean written;

        int selectionCalls() { return selections.get(); }

        @Override
        public Response reason(Request request) {
            int n = calls.incrementAndGet();
            List<String> providerEvidence = List.of(
                    "worker-cognition-evidence;provider=scripted-free-tier;model=scripted-hoa-model;latency_ms=0");
            if (request.instructions().contains("reflection brain")) {
                return new Response("scripted-" + n, "{\"decision\":\"COMPLETE\",\"summary\":\"ACTION_PLAN_v1 proposed\"}",
                        providerEvidence);
            }
            if (!request.instructions().contains("action-selection brain")) {
                throw new AssertionError("unexpected cognition request: " + request.instructions());
            }
            selections.incrementAndGet();
            Set<String> available = available(request.context());
            for (String path : REQUIRED_READS) {
                if (!request.context().contains(path)) {
                    throw new AssertionError("governed memory/history must carry required input " + path);
                }
            }
            if (!written && available.contains("workspace.file.write")) {
                written = true;
                StringBuilder plan = new StringBuilder("# ACTION_PLAN_v1 — BIOS Aquaculture (P1)\n\n");
                for (String part : AquacultureDomainPlanningCapability.SECTION_7_PARTS) {
                    plan.append("## ").append(part).append("\n\n- nội dung ").append(part).append("\n\n");
                }
                return new Response("scripted-" + n, action("workspace.file.write", Map.of(
                        "path", AquacultureDomainPlanningCapability.ACTION_PLAN_V1_PATH, "content", plan.toString())),
                        providerEvidence);
            }
            throw new AssertionError("no scripted answer; available=" + available);
        }

        private static Set<String> available(String context) {
            Matcher matcher = AVAILABLE.matcher(context);
            if (!matcher.find()) throw new AssertionError("no availableActions");
            Set<String> out = new LinkedHashSet<>();
            for (String raw : matcher.group(1).split(",")) {
                String value = raw.replace("\"", "").trim();
                if (!value.isBlank()) out.add(value);
            }
            return out;
        }

        private static String action(String actionRef, Map<String, String> inputs) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("actionRef", actionRef);
                payload.put("inputs", inputs);
                payload.put("rationale", "scripted HOA step");
                return JSON.writeValueAsString(payload);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
