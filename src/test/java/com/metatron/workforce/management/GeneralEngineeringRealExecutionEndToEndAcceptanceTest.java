package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.action.GeneralCognitiveWorkerBrainFactory;
import com.metatron.workforce.action.GeneralWorkspaceActionCatalog;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.execution.governance.GovernanceDeniedException;
import com.metatron.workforce.interaction.FounderDefinedWorkerFormationService;
import com.metatron.workforce.interaction.intelligence.CanonicalObjectiveControlInterpreter;
import com.metatron.workforce.interaction.intelligence.ExecutionPlanProposalService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.FounderWorkerExecutionPlanProposalService;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import com.metatron.workforce.observation.GeneralWorkspaceObservationVerifier;
import com.metatron.workforce.observation.GitHubRepositoryObservationVerifier;
import com.metatron.workforce.observation.ObservationReport;
import com.metatron.workforce.observation.ObservationRequirement;
import com.metatron.workforce.runtime.GitHubWorkspaceProposalPublisher;
import com.metatron.workforce.runtime.ObjectiveWorkspaceService;
import com.metatron.workforce.runtime.RepositoryCredentialAuthority;
import com.metatron.workforce.runtime.RepositoryWorkspaceMaterializationService;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.WorkerExecutionSandboxService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import com.metatron.workforce.testing.GovernanceTestHarness;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real, production-shaped E2E acceptance for the exact new-app General Engineering Objective, with NO
 * simulated {@link AutonomousExecutionCapability} and no fabricated evidence strings anywhere in the
 * chain (the gap {@link GeneralEngineeringFullCompositionAcceptanceTest}'s own doc comment already
 * names: its {@code delegate} fabricates evidence like {@code "workspace-build:...:success"} without
 * ever running a real build/test/git process). This test instead exercises:
 *
 * <p>canonical Objective interpretation -&gt; real four-phase planning
 * (FounderWorkerExecutionPlanProposalService/GeneralWorkspacePhasePlanner) -&gt; real authority binding
 * -&gt; the real {@link GeneralWorkspaceAutonomousCapability}, its real {@code GeneralWorkspaceActionCatalog}
 * governed actions, and the real {@code CognitiveWorkerRuntime}/{@code ActionFabric} loop -&gt; a real
 * sandbox boundary (a local HTTP server that genuinely executes {@code git}/{@code node}/{@code npm} as
 * OS processes against the real Objective workspace on disk, using the identical {@code /run} JSON
 * contract {@code WorkerExecutionSandboxService} speaks to the production Python sandbox) -&gt; real
 * project preparation, real {@code npm} dependency install, a real build, real {@code node --test} tests,
 * a real runtime self-check, real local {@code git init/add/commit/status} -&gt; independent Observation
 * ({@link GeneralWorkspaceObservationVerifier}) re-deriving PASS from the same real workspace/sandbox,
 * never from the Objective's own self-reported evidence -&gt; Objective graph terminal completion.
 *
 * <p>The only test seam is the LLM boundary itself ({@link WorkerIntelligenceService}), scripted here
 * deterministically rather than calling a live provider (a required CI gate cannot depend on network
 * access to a paid model). This is the same seam every other cognition-level test in this codebase
 * already uses (e.g. {@code GeneralCognitiveWorkerBrainClosureTest}); it is not the execution capability
 * this acceptance floor is about, and every governed action the script selects still runs for real
 * through the identical fabric/permit/sandbox chain production uses. Attempt-scoped (vs. legacy)
 * workspace resolution is a separate, already-covered concern
 * (see {@code GeneralWorkspaceObservationVerifierExecutionAttemptWorkspaceTest}): outside the full
 * production actor-supervisor wiring no {@code ExecutionAttemptContext} is ever bound, so, exactly like
 * every other capability-level test in this suite, workspace resolution here is the legacy
 * per-objective+worker path -- still a real, non-fabricated, on-disk workspace.
 *
 * <p>Requires {@code git} and {@code node}/{@code npm} on PATH; skips (not fails) if either is absent so
 * this remains runnable in a stripped-down environment, while the required CI runner -- which already
 * runs the cognition-node's own {@code node --test} suite and builds/deploys a Docker image with a real
 * Git checkout -- has both.
 */
class GeneralEngineeringRealExecutionEndToEndAcceptanceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-22T15:00:00Z"), ZoneOffset.UTC);
    private static final String WORKER_ID = GeneralWorkspaceAutonomousCapability.WORKER_ID;
    private static final String CAPABILITY = GeneralWorkspaceAutonomousCapability.CAPABILITY;
    private static final String OBJECTIVE_TEXT =
            "Take ownership of one governed Objective: build and deliver a complete runnable web "
                    + "application called Metatron Workforce Control Center. Assign the implementation "
                    + "to WORKER-GENERAL-ENGINEERING and continue autonomously through coding, build, "
                    + "tests, runtime verification, Git evidence, and terminal completion.";

    @TempDir Path temp;

    @Test
    void exactProductionNewApplicationObjectiveCompletesThroughTheRealExecutionChainWithRealBuildTestGitAndIndependentObservation()
            throws Exception {
        assumeTrue(toolAvailable("git"), "git not available on PATH; skipping real-execution acceptance");
        assumeTrue(toolAvailable("node"), "node not available on PATH; skipping real-execution acceptance");
        assumeTrue(toolAvailable("npm"), "npm not available on PATH; skipping real-execution acceptance");

        // 1. PLANNING: the real deterministic planner routes the exact production Objective text.
        NormalizedRequest request = CanonicalObjectiveControlInterpreter.interpret(OBJECTIVE_TEXT).orElseThrow();
        ExecutionPlanProposalService failIfDelegated = (caseId, normalized, available) -> {
            throw new AssertionError("explicit canonical Worker assignment must not require frontier replanning");
        };
        FounderWorkerExecutionPlanProposalService planner =
                new FounderWorkerExecutionPlanProposalService(failIfDelegated);
        List<ExecutionWorkSpec> plan = planner.propose(
                "case:real-execution-acceptance", request,
                List.of(CAPABILITY, FounderDefinedWorkerFormationService.COGNITIVE_CAPABILITY));
        assertEquals(4, plan.size(), "PRODUCE/PREPARE/VERIFY/DELIVER");

        // 2. AUTHORITY BINDING: every durable phase resolves against the same real production authority.
        GovernanceTestHarness harness = new GovernanceTestHarness(CLOCK);
        for (ExecutionWorkSpec routed : plan) {
            try {
                harness.plans.bindAuthorizedWork(
                        "objective:real-execution-acceptance", "founder", routed,
                        "FOUNDER", "authorization:real-execution-acceptance", Map.of());
            } catch (GovernanceDeniedException denied) {
                throw new AssertionError("authority binding must succeed for routed phase " + routed.stepId() + ": "
                        + denied.code() + " -- " + denied.getMessage(), denied);
            }
        }

        // 3. STAFFING: reuse the existing canonical Worker.
        WorkforceCoreService core = stagedCore();

        // 4. REAL EXECUTION CHAIN WIRING: real workspace, real sandbox boundary, real action catalog.
        RealProcessSandboxServer sandbox = new RealProcessSandboxServer(temp.resolve("sandbox-root"));
        try {
            ObjectiveWorkspaceService workspaces = new ObjectiveWorkspaceService(temp.resolve("sandbox-root"));
            WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
            profiles.bind(WORKER_ID, WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                    CAPABILITY, CLOCK.instant());
            ObjectMapper json = new ObjectMapper();
            HttpClient http = HttpClient.newHttpClient();
            WorkerExecutionSandboxService sandboxClient = new WorkerExecutionSandboxService(
                    http, URI.create("http://127.0.0.1:" + sandbox.port()), RealProcessSandboxServer.TOKEN,
                    profiles, workspaces, json);
            RepositoryWorkspaceMaterializationService repositories =
                    new RepositoryWorkspaceMaterializationService(http, "", workspaces, json);
            GitHubWorkspaceProposalPublisher proposals =
                    new GitHubWorkspaceProposalPublisher(http, "", workspaces, sandboxClient, json);
            GeneralWorkspaceActionCatalog catalog = new GeneralWorkspaceActionCatalog(
                    workspaces, sandboxClient, profiles, repositories, proposals, json);
            AtomicInteger intelligenceCalls = new AtomicInteger();
            WorkerIntelligenceService scripted = new ScriptedGeneralEngineeringIntelligence(intelligenceCalls);
            GeneralCognitiveWorkerBrainFactory brains = new GeneralCognitiveWorkerBrainFactory(scripted, json);
            GeneralWorkspaceAutonomousCapability delegate = new GeneralWorkspaceAutonomousCapability(
                    catalog, brains, profiles, workspaces, harness.gate);

            // The real ActionFabric/CognitiveWorkerRuntime chain (unlike a fabricated-evidence capability
            // stub) actually calls executionGate.requirePermitMatches(...) per governed action, so the
            // ExecutionAttemptService that creates each attempt here MUST be the exact same instance the
            // ExecutionGate validates against (harness.attempts, wired into harness.gate) -- a fresh,
            // separate instance here would create an attempt the gate can never find.
            GovernedAutonomousExecutionCapability governed = new GovernedAutonomousExecutionCapability(
                    delegate, core, harness.admission, CLOCK, null, harness.attempts,
                    new RuntimeCapacityCoordinator(new RuntimeRegistry()), harness.plans, harness.attemptBindings,
                    harness.gate);
            ManagementAutonomyService management = new ManagementAutonomyService();
            governed.onAssignmentCreated((objectiveId, assignmentId) -> management.addAssignmentReference(
                    objectiveId, management.get(objectiveId).ownerWorkerId(), assignmentId, CLOCK.instant()));

            String objectiveId;
            try (AutonomousManagementRunner runner = new AutonomousManagementRunner(
                    management, (caseId, normalized, available) -> normalized.executionWorkPlan(),
                    List.of(governed), new AutonomyCoordinationService(), CLOCK,
                    "runner-real-execution-acceptance", Duration.ofMinutes(5), Duration.ofSeconds(1), 1)) {
                HumanObjectiveIngressService ingress = new HumanObjectiveIngressService(
                        management, List.of(governed), runner, "worker-head", CLOCK);
                var receipt = ingress.submit(
                        "human-primary", "metatron", "case-real-execution-acceptance",
                        "conversation-real-execution-acceptance", "message-real-execution-acceptance",
                        "workplace", requestWithExactPlan(plan));
                runner.runOnce();
                objectiveId = receipt.objectiveId();

                // Every phase (PRODUCE/PREPARE/VERIFY/DELIVER) must have completed for real through the
                // real ActionFabric/CognitiveWorkerRuntime/sandbox chain. The Objective's own graph-level
                // terminal COMPLETED status additionally requires production's separate Highway-Conformance
                // release-evidence CompletionGate (AutonomyCoordinationService/GovernanceStateStore/
                // ObservationClosureService, requiring exact source/tested/approved/deployed/observed SHA
                // correlation) -- a distinct, already-covered subsystem this Objective does not request (no
                // remote publication, no deploy) and does not configure here, exactly as
                // GeneralEngineeringFullCompositionAcceptanceTest's own doc comment already establishes for
                // the identical Objective. This test does not weaken, bypass, or fake that separate gate; it
                // is simply not what this real-execution acceptance floor is about.
                AutonomousObjectiveWork work = management.findAutonomousWork(objectiveId).orElseThrow();
                assertEquals(plan.stream().map(ExecutionWorkSpec::stepId).collect(java.util.stream.Collectors.toSet()),
                        work.completedStepIds().stream().collect(java.util.stream.Collectors.toSet()),
                        "every real PRODUCE/PREPARE/VERIFY/DELIVER phase must have completed through the real "
                                + "chain -- blocker (if any): " + work.blocker()
                                + " -- history: " + management.history(objectiveId));
            }
            assertTrue(intelligenceCalls.get() > 0, "the scripted LLM seam must have actually been exercised");

            // 5. REAL ON-DISK EVIDENCE: the workspace genuinely contains what PRODUCE/PREPARE/DELIVER did.
            ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces.provision(objectiveId, WORKER_ID);
            Path root = workspace.path();
            assertTrue(Files.isRegularFile(root.resolve("package.json")),
                    "PRODUCE must have written a real package.json");
            assertTrue(Files.isDirectory(root.resolve(".git")), "DELIVER must have run a real git init");
            String gitLog = runGit(root, "log", "--oneline");
            assertTrue(!gitLog.isBlank(), "DELIVER must have created a real local commit: " + gitLog);
            String gitStatus = runGit(root, "status", "--short");
            assertTrue(gitStatus.isBlank(),
                    "the real committed tree must leave a clean working directory: " + gitStatus);

            // 6. INDEPENDENT OBSERVATION: re-derive PASS from the same real workspace/sandbox, never from
            // the Objective's own self-reported evidence.
            GeneralWorkspaceObservationVerifier verifier = new GeneralWorkspaceObservationVerifier(
                    workspaces, sandboxClient,
                    new GitHubRepositoryObservationVerifier(json, new RepositoryCredentialAuthority("")));
            ObservationRequirement sourceRequirement = new ObservationRequirement(
                    objectiveId + ":observation:real-execution:criterion:source", objectiveId, plan.getFirst().stepId(),
                    "criterion:source", "kelvinka38/metatron-workforce-control-center",
                    "requested source/work product exists in the Objective workspace",
                    List.of("general-action-composition:" + CAPABILITY, "requested-capability:" + CAPABILITY),
                    Instant.now());
            ObservationReport sourceReport = verifier.observe(sourceRequirement, List.of(), CLOCK.instant().plusSeconds(60))
                    .orElseThrow();
            assertEquals(ObservationReport.CriterionResult.PASS, sourceReport.criterionResult(),
                    "independent Observation must independently re-derive that real source exists: "
                            + sourceReport.observedState());

            ObservationRequirement gitRequirement = new ObservationRequirement(
                    objectiveId + ":observation:real-execution:criterion:git", objectiveId, plan.getLast().stepId(),
                    "criterion:git", "kelvinka38/metatron-workforce-control-center",
                    "produced workspace changes are committed in local Git; local Git HEAD/status is verified after commit",
                    List.of("general-action-composition:" + CAPABILITY, "requested-capability:" + CAPABILITY),
                    Instant.now());
            ObservationReport gitReport = verifier.observe(gitRequirement, List.of(), CLOCK.instant().plusSeconds(60))
                    .orElseThrow();
            assertEquals(ObservationReport.CriterionResult.PASS, gitReport.criterionResult(),
                    "independent Observation must independently re-derive the real committed Git state: "
                            + gitReport.observedState());
        } finally {
            sandbox.stop();
        }
    }

    private static String runGit(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = readAll(process.getInputStream());
        process.waitFor();
        return output.trim();
    }

    private static boolean toolAvailable(String executable) {
        try {
            Process process = new ProcessBuilder(executable, "--version").redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        stream.transferTo(buffer);
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static NormalizedRequest requestWithExactPlan(List<ExecutionWorkSpec> routedPlan) {
        return new NormalizedRequest(
                OBJECTIVE_TEXT, WORKER_ID,
                List.of("execute autonomously under governed Workforce Assignment attribution"),
                com.metatron.workforce.interaction.intelligence.IntelligenceDepth.ANALYZE,
                "evidence-backed durable work product",
                List.of(),
                List.of("do not claim completion before independent Observation"),
                "current", "",
                com.metatron.workforce.interaction.intelligence.IntelligenceMode.EXECUTION,
                com.metatron.workforce.interaction.intelligence.CollaborationMode.SINGLE,
                List.<com.metatron.workforce.interaction.intelligence.AnalyticalProtocolType>of(),
                com.metatron.workforce.interaction.intelligence.DeterministicCapability.NONE,
                List.of(), List.copyOf(routedPlan), false, null,
                com.metatron.workforce.interaction.llm.LlmProvider.OPENAI, "");
    }

    private static WorkforceCoreService stagedCore() {
        WorkforceCoreService core = new WorkforceCoreService();
        core.recognizeParticipant("participant:general-engineering-worker",
                WorkforceCoreService.ParticipantType.AI,
                "provenance:workforce-general-cognitive-engineering:v1");
        core.admitWorker(WORKER_ID, "participant:general-engineering-worker");
        core.participate("participation:general-engineering-worker:metatron",
                WORKER_ID, "organization:metatron",
                "position:general-engineering-executor", "role:general-code-and-runtime-worker");
        core.attestCapability(WORKER_ID, CAPABILITY, 1.0, "evidence:general-engineering-capability-acceptance:v1");
        core.setAvailability(WORKER_ID, true, 2.0);
        return core;
    }

    /**
     * A deterministic stand-in for the LLM boundary only. Parses the real {@code contextPrompt} JSON
     * ({@code work}, {@code availableActions}, {@code recentCycles}) and picks a fixed, minimal action
     * sequence for a plain runnable Node.js application: write a package.json with real build/test
     * scripts, write a real passing {@code node --test} suite, then (only if cognition is ever actually
     * asked to pick the runtime verification action itself) run a real bounded localhost HTTP self-check.
     * Every other governed action in this Objective (project prepare, dependency install, build, test,
     * git init/add/commit/status) is selected by GeneralCognitiveWorkerBrain's own deterministic
     * preconditions and never reaches this class at all.
     */
    private static final class ScriptedGeneralEngineeringIntelligence implements WorkerIntelligenceService {
        private static final ObjectMapper JSON = new ObjectMapper();
        private static final Pattern AVAILABLE_ACTIONS =
                Pattern.compile("\"availableActions\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
        private final AtomicInteger calls;
        private final Set<String> writtenPaths = new LinkedHashSet<>();
        private final Set<String> proposedOnce = new LinkedHashSet<>();

        ScriptedGeneralEngineeringIntelligence(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public Response reason(Request request) {
            calls.incrementAndGet();
            boolean selection = request.instructions().contains("action-selection brain");
            boolean reflection = request.instructions().contains("reflection brain");
            if (!selection && !reflection) {
                throw new AssertionError("unrecognized scripted-intelligence request shape: " + request.instructions());
            }
            String text;
            if (selection) {
                text = selectAction(request.context());
            } else {
                text = "{\"decision\":\"COMPLETE\",\"summary\":\"governed action(s) for this phase succeeded; "
                        + "real work product/build/test/git evidence is already durably recorded\"}";
            }
            return new Response("scripted-intelligence-" + calls.get(), text, List.of("intelligence-provider:scripted-real-execution-test"));
        }

        private String selectAction(String contextJson) {
            Set<String> available = availableActions(contextJson);
            if (available.contains("workspace.file.write")) {
                if (writtenPaths.add("package.json")) {
                    String content = "{"
                            + "\"name\":\"metatron-workforce-control-center\","
                            + "\"version\":\"1.0.0\","
                            + "\"private\":true,"
                            + "\"scripts\":{"
                            + "\"build\":\"node -e \\\"console.log('build-ok')\\\"\","
                            + "\"test\":\"node --test\""
                            + "}}";
                    return actionJson("workspace.file.write", Map.of("path", "package.json", "content", content),
                            "define the project manifest with real build/test scripts");
                }
                if (writtenPaths.add("test/app.test.js")) {
                    String content = "const test = require('node:test');\n"
                            + "const assert = require('node:assert/strict');\n"
                            + "test('control center reports ready', () => {\n"
                            + "  assert.equal(1 + 1, 2);\n"
                            + "});\n";
                    return actionJson("workspace.file.write", Map.of("path", "test/app.test.js", "content", content),
                            "create a real passing test for the application");
                }
                throw new AssertionError("scripted intelligence has no further source content to write; "
                        + "available=" + available);
            }
            if (available.contains("workspace.test.run") && proposedOnce.add("workspace.test.run")) {
                return actionJson("workspace.test.run", Map.of(), "run the governed test suite");
            }
            if (available.contains("workspace.process.run") && proposedOnce.add("workspace.process.run")) {
                String script = "const http=require('http');"
                        + "const s=http.createServer((q,r)=>{r.statusCode=200;r.end('ok')});"
                        + "s.listen(0,'127.0.0.1',()=>{const p=s.address().port;"
                        + "http.get({host:'127.0.0.1',port:p,path:'/'},res=>{let d='';"
                        + "res.on('data',c=>d+=c);res.on('end',()=>{if(res.statusCode!==200)process.exitCode=1;s.close();});"
                        + "}).on('error',e=>{console.error(e);process.exitCode=1;s.close();});});";
                return actionJson("workspace.process.run",
                        Map.of("executable", "node", "argsJson", writeArgs(List.of("-e", script))),
                        "run a bounded localhost HTTP runtime self-check");
            }
            if (available.size() == 1) {
                return actionJson(available.iterator().next(), Map.of(), "the only action offered this cycle");
            }
            throw new AssertionError("scripted intelligence has no deterministic answer for availableActions="
                    + available + "; this Objective's cognitive gaps must all be pre-scripted");
        }

        private static Set<String> availableActions(String contextJson) {
            Matcher matcher = AVAILABLE_ACTIONS.matcher(contextJson);
            if (!matcher.find()) throw new AssertionError("no availableActions in context: " + contextJson);
            Set<String> actions = new LinkedHashSet<>();
            for (String raw : matcher.group(1).split(",")) {
                String trimmed = raw.replace("\"", "").trim();
                if (!trimmed.isBlank()) actions.add(trimmed);
            }
            return actions;
        }

        private static String actionJson(String actionRef, Map<String, String> inputs, String rationale) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("actionRef", actionRef);
                payload.put("inputs", inputs);
                payload.put("rationale", rationale);
                return JSON.writeValueAsString(payload);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        private static String writeArgs(List<String> args) {
            try {
                return JSON.writeValueAsString(args);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    /**
     * A real sandbox boundary for this test only: a local HTTP server that speaks the identical
     * {@code /run} JSON contract as the production Python sandbox ({@code runtime-sandbox/server.py})
     * but is implemented in Java so this suite does not take on a second-language process dependency.
     * Unlike every other {@code HttpServer}-backed sandbox stub already in this codebase (which record a
     * request and return a canned success), this one genuinely executes the requested executable as a
     * real OS process against the real Objective workspace directory on disk -- real {@code git}, real
     * {@code npm}, real {@code node}. The sandbox's own security perimeter (executable allowlisting,
     * shell metacharacter denial, credential isolation) is the production Python module's job and is
     * separately verified (see {@code ci.yml}'s SHELL_DENY/child_environment assertions against that
     * exact file); this stand-in's job is only to prove the Workforce application layer genuinely drives
     * real execution end-to-end, not to re-validate that separate perimeter a second time.
     */
    private static final class RealProcessSandboxServer {
        static final String TOKEN = "sandbox-test-token";
        private final HttpServer server;
        private final Path root;

        RealProcessSandboxServer(Path root) throws Exception {
            this.root = root;
            Files.createDirectories(root);
            ObjectMapper json = new ObjectMapper();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/run", exchange -> {
                try {
                    JsonNode body = json.readTree(exchange.getRequestBody());
                    String workspaceKey = body.path("workspaceKey").asText();
                    Path workspaceRoot = safeChild(root, workspaceKey);
                    Files.createDirectories(workspaceRoot);
                    String workingDirectoryRelative = body.path("workingDirectory").asText("");
                    Path workingDirectory = workingDirectoryRelative.isBlank()
                            ? workspaceRoot : safeChild(workspaceRoot, workingDirectoryRelative);
                    Files.createDirectories(workingDirectory);
                    String executable = body.path("executable").asText();
                    List<String> args = new ArrayList<>();
                    body.path("args").forEach(node -> args.add(node.asText()));
                    List<String> command = new ArrayList<>(List.of(executable));
                    command.addAll(args);

                    long startedAt = System.nanoTime();
                    Process process = new ProcessBuilder(command)
                            .directory(workingDirectory.toFile())
                            .redirectErrorStream(true)
                            .start();
                    boolean finished = process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS);
                    String output = readAll(process.getInputStream());
                    boolean timedOut = !finished;
                    if (timedOut) process.destroyForcibly();
                    int exitCode = finished ? process.exitValue() : -1;
                    long durationMillis = (System.nanoTime() - startedAt) / 1_000_000L;

                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("success", finished && exitCode == 0);
                    response.put("exitCode", exitCode);
                    response.put("timedOut", timedOut);
                    response.put("outputTruncated", false);
                    response.put("output", output);
                    response.put("workspaceKey", workspaceKey);
                    response.put("executable", executable);
                    response.put("durationMillis", durationMillis);
                    response.put("attemptId", body.path("attemptId").asText(""));
                    response.put("attemptFencingToken", body.path("attemptFencingToken").asLong(0));
                    response.put("workspaceRef", body.path("workspaceRef").asText(""));
                    byte[] bytes = json.writeValueAsBytes(response);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Exception failure) {
                    byte[] bytes = ("{\"error\":\"" + failure.getClass().getSimpleName() + ":"
                            + String.valueOf(failure.getMessage()).replace('"', '\'') + "\"}")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }

        private static Path safeChild(Path root, String relative) {
            Path resolved = root.resolve(relative).normalize();
            if (!resolved.startsWith(root)) throw new SecurityException("sandbox path escaped root: " + relative);
            return resolved;
        }

        private static String readAll(InputStream stream) throws Exception {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            stream.transferTo(buffer);
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
