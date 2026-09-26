package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.action.GeneralCognitiveWorkerBrainFactory;
import com.metatron.workforce.action.GeneralWorkspaceActionCatalog;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import com.metatron.workforce.operating.PositionRouteCatalog;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs one Founder ACTION_PLAN Objective through the production HOA path — HumanObjectiveIngressService →
 * AutonomousManagementRunner → GovernedAutonomousExecutionCapability → AquacultureDomainPlanningCapability →
 * real CognitiveWorkerRuntime/ActionFabric over the DOMAIN_HEAD catalog with real sandbox git and a fake GitHub
 * API — with only the LLM boundary ({@link WorkerIntelligenceService}) supplied by the test.
 */
final class HoaPlanningHarness {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-26T03:00:00Z"), ZoneOffset.UTC);
    static final String HOA = AquacultureHeadAppointmentCapability.WORKER_ID;
    static final String FOUNDER_OBJECTIVE =
            "HOA: đọc DOMAINS/AQUACULTURE/EXECUTION_PLAN.md, DECISIONS.md, DOMAIN_PACK/*, "
                    + "KNOWLEDGE/DOMAINS/AQUACULTURE/v2/gaps.yaml và COVERAGE.md; nộp ACTION_PLAN_v1 theo §7.";

    /** Character sizes of the nine required inputs on kelvinka38/bios main (8028d51), in reading order. */
    static final Map<String, Integer> BIOS_MAIN_INPUT_SIZES = sizes();

    record Run(AutonomousObjectiveWork work, List<String> evidence, String plan, List<String> publishedPaths,
               int pullRequests, int merges, String history) {}

    private HoaPlanningHarness() {}

    private static Map<String, Integer> sizes() {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        sizes.put("DOMAINS/AQUACULTURE/EXECUTION_PLAN.md", 10_660);
        sizes.put("DOMAINS/AQUACULTURE/DECISIONS.md", 6_195);
        sizes.put("DOMAINS/AQUACULTURE/DOMAIN_PACK/01_CHARTER.md", 1_979);
        sizes.put("DOMAINS/AQUACULTURE/DOMAIN_PACK/02_CURRENT_STATE.md", 3_306);
        sizes.put("DOMAINS/AQUACULTURE/DOMAIN_PACK/03_KNOWLEDGE_MAP.md", 2_864);
        sizes.put("DOMAINS/AQUACULTURE/DOMAIN_PACK/04_TASK_FORMATS.md", 3_542);
        sizes.put("DOMAINS/AQUACULTURE/DOMAIN_PACK/README.md", 879);
        sizes.put("KNOWLEDGE/DOMAINS/AQUACULTURE/v2/gaps.yaml", 12_839);
        sizes.put("KNOWLEDGE/DOMAINS/AQUACULTURE/v2/COVERAGE.md", 22_717);
        return java.util.Collections.unmodifiableMap(sizes);
    }

    /** Deterministic governance-like Vietnamese Markdown/YAML of exactly {@code chars} characters. */
    static String governanceText(String path, int chars) {
        StringBuilder text = new StringBuilder("# ").append(path).append("\n\n");
        int n = 0;
        while (text.length() < chars) {
            n++;
            if (n % 12 == 1) text.append("\n## Mục ").append(n / 12 + 1).append(" — cổng, envelope và luật\n\n");
            text.append("- G-AQ-").append(String.format("%03d", n)).append(": trạng thái ")
                    .append(n % 3 == 0 ? "OPEN" : n % 3 == 1 ? "DECLARED" : "CLOSED")
                    .append("; nguồn đã mở: tài liệu ").append(n)
                    .append("; không có số liệu nếu chưa có nguồn đã mở.\n");
        }
        return text.substring(0, chars);
    }

    static Map<String, String> biosMainSizedInputs() {
        Map<String, String> files = new LinkedHashMap<>();
        BIOS_MAIN_INPUT_SIZES.forEach((path, size) -> files.put(path, governanceText(path, size)));
        return files;
    }

    static Run run(Path temp, Map<String, String> inputs, WorkerIntelligenceService intelligence) throws Exception {
        ExecutionWorkSpec step = AquacultureDomainPlanningCapability.actionPlanWork("hoa-action-plan-v1", FOUNDER_OBJECTIVE);
        GovernanceTestHarness harness = new GovernanceTestHarness(CLOCK);
        harness.plans.bindAuthorizedWork("objective:hoa-precheck", "founder", step,
                "FOUNDER", "authorization:hoa-precheck", Map.of());

        WorkforceCoreService core = new WorkforceCoreService();
        WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
        WorkerResourceScopeService scopes = WorkerResourceScopeService.inMemory();
        AutonomousStaffingService staffing = new AutonomousStaffingService(core,
                List.of(new AquacultureHeadStaffingPolicy()), profiles,
                WorkerConstitutionService.inMemory(PositionRouteCatalog.of(List.of(AquacultureDomainPlanningCapability.CAPABILITY))),
                scopes);
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
            AquacultureDomainPlanningCapability planning = new AquacultureDomainPlanningCapability(
                    catalog, new GeneralCognitiveWorkerBrainFactory(intelligence, json), profiles, workspaces, harness.gate);
            GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                    planning, core, harness.admission, CLOCK, null, harness.attempts,
                    new RuntimeCapacityCoordinator(new RuntimeRegistry()), harness.plans, harness.attemptBindings,
                    harness.gate);
            ManagementAutonomyService management = new ManagementAutonomyService();
            governed.onAssignmentCreated((objectiveId, assignmentId) -> management.addAssignmentReference(
                    objectiveId, management.get(objectiveId).ownerWorkerId(), assignmentId, CLOCK.instant()));

            String objectiveId;
            try (AutonomousManagementRunner runner = new AutonomousManagementRunner(
                    management, (caseId, normalized, available) -> normalized.executionWorkPlan(),
                    List.of(governed), new AutonomyCoordinationService(), CLOCK,
                    "runner-hoa-budget", Duration.ofMinutes(5), Duration.ofSeconds(1), 1)) {
                HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                        management, List.of(governed), runner, "worker-head", CLOCK);
                objectiveId = ingress.submit("human-primary", "bios", "case-hoa-budget",
                        "conversation-hoa-budget", "message-hoa-budget", "workplace", request(step)).objectiveId();
                seed(workspaces, objectiveId, github.baseSha(), inputs);
                runner.runOnce();
            }
            AutonomousObjectiveWork work = management.findAutonomousWork(objectiveId).orElseThrow();
            Path plan = workspaces.provision(objectiveId, HOA).path().resolve(AquacultureDomainPlanningCapability.ACTION_PLAN_V1_PATH);
            return new Run(work, work.evidenceReferences(), Files.isRegularFile(plan) ? Files.readString(plan) : "",
                    List.copyOf(github.publishedTreePaths()), github.pullRequestsCreated(), github.mergeCalls(),
                    String.valueOf(management.history(objectiveId)));
        } finally {
            sandbox.stop();
            github.stop();
        }
    }

    private static void seed(ObjectiveWorkspaceService workspaces, String objectiveId, String baseSha,
                             Map<String, String> inputs) throws Exception {
        ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces.provision(objectiveId, HOA);
        Files.writeString(workspace.path().resolve(".metatron-repository"),
                "repository=kelvinka38/bios\nrequestedRef=main\ncommitSha=" + baseSha + "\ncomponentId=primary\n");
        for (Map.Entry<String, String> input : inputs.entrySet()) {
            Path file = workspace.path().resolve(input.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, input.getValue());
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
}
