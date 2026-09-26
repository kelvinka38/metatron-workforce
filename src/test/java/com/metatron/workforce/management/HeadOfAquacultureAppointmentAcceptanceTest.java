package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.action.ActionFabric;
import com.metatron.workforce.action.GeneralWorkspaceActionCatalog;
import com.metatron.workforce.core.FileWorkforceCoreStateStore;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.governance.AuthorityManifestCatalog;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.operating.WorkerConstitutionService;
import com.metatron.workforce.runtime.GitHubWorkspaceProposalPublisher;
import com.metatron.workforce.runtime.ObjectiveWorkspaceService;
import com.metatron.workforce.runtime.RepositoryWorkspaceMaterializationService;
import com.metatron.workforce.runtime.WorkerExecutionSandboxService;
import com.metatron.workforce.runtime.WorkerResourceScopeService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import com.metatron.workforce.testing.LocalExecutionServers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Acceptance T1–T5 and T7 for the Head of Aquaculture (HOA) Worker: durable governed appointment,
 * narrow DOMAIN_HEAD runtime profile, per-Worker repository/path resource scope, and no merge/deploy.
 * T6 lives in {@link HeadOfAquacultureActionPlanEndToEndAcceptanceTest}; T8 is the unchanged existing suite.
 */
class HeadOfAquacultureAppointmentAcceptanceTest {
    private static final String HOA = AquacultureHeadAppointmentCapability.WORKER_ID;
    private static final Instant T0 = Instant.parse("2026-09-25T00:00:00Z");

    @TempDir Path temp;

    private record Durable(WorkforceCoreService core,
                           WorkerRuntimeProfileBindingService profiles,
                           WorkerResourceScopeService scopes,
                           AutonomousStaffingService staffing,
                           AquacultureHeadAppointmentCapability appointment) {}

    private Durable durable() {
        WorkforceCoreService core = new WorkforceCoreService(new FileWorkforceCoreStateStore(temp.resolve("core.json")));
        WorkerRuntimeProfileBindingService profiles = new WorkerRuntimeProfileBindingService(temp.resolve("bindings.tsv"));
        WorkerResourceScopeService scopes = new WorkerResourceScopeService(temp.resolve("scopes.tsv"));
        AutonomousStaffingService staffing = new AutonomousStaffingService(
                core, List.of(new AquacultureHeadStaffingPolicy()), profiles, WorkerConstitutionService.inMemory(
                com.metatron.workforce.operating.PositionRouteCatalog.of(List.of(AquacultureDomainPlanningCapability.CAPABILITY))), scopes);
        return new Durable(core, profiles, scopes, staffing,
                new AquacultureHeadAppointmentCapability(core, profiles, scopes));
    }

    // ---------------------------------------------------------------- T1

    @Test
    void t1_staffingIsIdempotentAndSurvivesRebuildFromPersistence() {
        Durable first = durable();
        var a = first.staffing().ensureStaffed(first.appointment(), T0);
        var b = first.staffing().ensureStaffed(first.appointment(), T0.plusSeconds(1));
        assertTrue(a.staffed());
        assertEquals(HOA, a.workerId());
        assertEquals(a.workerId(), b.workerId());
        long hoaWorkers = first.core().allWorkers().stream().filter(w -> w.workerId().equals(HOA)).count();
        assertEquals(1, hoaWorkers);

        // Rebuild every service from the same durable state (process restart).
        Durable restarted = durable();
        assertEquals(WorkforceCoreService.WorkerStatus.ACTIVE, restarted.core().worker(HOA).status());
        assertEquals(WorkerRuntimeProfileBindingService.DOMAIN_HEAD_PROFILE,
                restarted.profiles().requireBinding(HOA).profile().profileRef());
        assertTrue(restarted.scopes().find(HOA).isPresent(), "resource scope must be durable");
        var again = restarted.staffing().ensureStaffed(restarted.appointment(), T0.plusSeconds(60));
        assertEquals(HOA, again.workerId());
        assertEquals(1, restarted.core().allWorkers().stream().filter(w -> w.workerId().equals(HOA)).count());
        assertEquals(1, restarted.core().participations(HOA).stream()
                .filter(p -> p.status() == WorkforceCoreService.ParticipationStatus.ACTIVE)
                .filter(p -> AquacultureHeadAppointmentCapability.ROLE_REF.equals(p.roleRef()))
                .count());
    }

    // ---------------------------------------------------------------- T2

    @Test
    void t2_domainHeadProfileHasNoShellProcessBuildTestOrDependencyActions() throws Exception {
        WorkerRuntimeProfileBindingService.ToolProfile profile = WorkerRuntimeProfileBindingService.inMemory()
                .profile(WorkerRuntimeProfileBindingService.DOMAIN_HEAD_PROFILE, AquacultureHeadAppointmentCapability.CAPABILITY);
        assertEquals(Set.of(
                "research.web.search",
                "workspace.repository.materialize",
                "workspace.file.read", "workspace.file.list", "workspace.file.search",
                "workspace.file.write", "workspace.file.patch",
                "workspace.git.status", "workspace.git.diff", "workspace.git.run",
                "workspace.github.pr.publish"), profile.actionRefs());
        for (String forbidden : List.of("workspace.shell.run", "workspace.process.run", "workspace.dependencies.install",
                "workspace.build.run", "workspace.test.run", "workspace.project.prepare")) {
            assertFalse(profile.actionRefs().contains(forbidden), forbidden);
        }
        assertEquals(Set.of("git"), profile.allowedExecutables());
        assertTrue(profile.writableWorkspace());

        Harness h = harness(null);
        List<String> offered = h.catalog().actions(HOA, AquacultureDomainPlanningCapability.AUTHORIZATION_REFERENCE, "objective-t2")
                .stream().map(ActionFabric.Action::actionRef).toList();
        assertFalse(offered.contains("workspace.shell.run"));
        ActionFabric fabric = new ActionFabric(h.catalog().actions(
                HOA, AquacultureDomainPlanningCapability.AUTHORIZATION_REFERENCE, "objective-t2"));
        RuntimeException denied = assertThrows(RuntimeException.class, () -> fabric.execute(new ActionFabric.ActionRequest(
                "workspace.shell.run", HOA, "assignment-t2", AquacultureDomainPlanningCapability.AUTHORIZATION_REFERENCE,
                "objective-t2", "step-t2", "idem-t2", true, Map.of("command", "echo hi"))));
        assertTrue(denied instanceof IllegalArgumentException || denied instanceof SecurityException, denied.toString());
        // Even below the fabric, the sandbox refuses every executable except git for this profile.
        assertThrows(SecurityException.class, () -> h.sandboxClient().run(HOA, "objective-t2", "sh", List.of("-c", "true")));
        assertThrows(SecurityException.class, () -> h.sandboxClient().run(HOA, "objective-t2", "./gradlew", List.of("test")));
    }

    // ---------------------------------------------------------------- T3

    @Test
    void t3_hoaMayMaterializeOnlyKelvinkaBios() throws Exception {
        assumeTrue(LocalExecutionServers.toolAvailable("git"), "git not available");
        LocalExecutionServers.RealProcessSandboxServer sandbox =
                new LocalExecutionServers.RealProcessSandboxServer(temp.resolve("sandbox-root"));
        try {
            Harness h = harness(sandbox);
            assertDoesNotThrow(() -> h.scopes().requireRepository(HOA, "kelvinka38/bios"));
            assertThrows(SecurityException.class, () -> h.scopes().requireRepository(HOA, "kelvinka38/metatron-workforce"));

            // Through the governed catalog action: bios materialization (pre-seeded provenance reuse path) succeeds.
            seedProvenance(h.workspaces(), "objective-t3", "kelvinka38/bios", Map.of("README.md", "# BIOS\n"));
            ActionFabric.Action materialize = action(h, "objective-t3", "workspace.repository.materialize");
            ActionFabric.ActionObservation ok = materialize.invoke(request("objective-t3", "workspace.repository.materialize",
                    Map.of("repository", "kelvinka38/bios")));
            assertTrue(ok.success(), ok.summary());

            // Any other repository is refused before any network or workspace effect.
            ActionFabric.Action other = action(h, "objective-t3b", "workspace.repository.materialize");
            SecurityException refused = assertThrows(SecurityException.class, () -> other.invoke(request(
                    "objective-t3b", "workspace.repository.materialize", Map.of("repository", "kelvinka38/metatron-workforce"))));
            assertTrue(refused.getMessage().contains("kelvinka38/metatron-workforce"), refused.getMessage());
        } finally {
            sandbox.stop();
        }
    }

    // ---------------------------------------------------------------- T4

    @Test
    void t4_writesAndProposalPathsAreConfinedToActionPlansAndReports() throws Exception {
        assumeTrue(LocalExecutionServers.toolAvailable("git"), "git not available");
        LocalExecutionServers.RealProcessSandboxServer sandbox =
                new LocalExecutionServers.RealProcessSandboxServer(temp.resolve("sandbox-root"));
        LocalExecutionServers.FakeGitHubApiServer github = new LocalExecutionServers.FakeGitHubApiServer("kelvinka38/bios");
        try {
            Harness h = harness(sandbox, github);
            String objective = "objective-t4";
            seedProvenance(h.workspaces(), objective, "kelvinka38/bios", Map.of(
                    "DOMAINS/AQUACULTURE/EXECUTION_PLAN.md", "# plan\n",
                    "README.md", "# BIOS\n"));
            assertTrue(action(h, objective, "workspace.repository.materialize").invoke(request(objective,
                    "workspace.repository.materialize", Map.of("repository", "kelvinka38/bios"))).success());

            ActionFabric.Action write = action(h, objective, "workspace.file.write");
            ActionFabric.Action patch = action(h, objective, "workspace.file.patch");
            // Outside both prefixes → denied, and nothing is written.
            assertThrows(SecurityException.class, () -> write.invoke(request(objective, "workspace.file.write",
                    Map.of("path", "DOMAINS/AQUACULTURE/EXECUTION_PLAN.md", "content", "tampered"))));
            assertThrows(SecurityException.class, () -> write.invoke(request(objective, "workspace.file.write",
                    Map.of("path", "bios_runtime/x.py", "content", "x"))));
            assertThrows(SecurityException.class, () -> write.invoke(request(objective, "workspace.file.write",
                    Map.of("path", "DOMAINS/AQUACULTURE/ACTION_PLANS/../EXECUTION_PLAN.md", "content", "x"))));
            assertThrows(SecurityException.class, () -> patch.invoke(request(objective, "workspace.file.patch",
                    Map.of("path", "README.md", "oldText", "BIOS", "newText", "X"))));
            Path root = h.workspaces().provision(objective, HOA).path();
            assertEquals("# plan\n", Files.readString(root.resolve("DOMAINS/AQUACULTURE/EXECUTION_PLAN.md")));

            // Inside the prefixes → allowed.
            assertTrue(write.invoke(request(objective, "workspace.file.write", Map.of(
                    "path", "DOMAINS/AQUACULTURE/ACTION_PLANS/ACTION_PLAN_v1.md", "content", "# plan v1\n"))).success());
            assertTrue(write.invoke(request(objective, "workspace.file.write", Map.of(
                    "path", "DOMAINS/AQUACULTURE/REPORTS/week-1.md", "content", "# week 1\n"))).success());

            // A changed path outside the prefixes that reached the tree by other means (git.run) blocks publish.
            Files.writeString(root.resolve("README.md"), "# BIOS changed via git-level edit\n");
            ActionFabric.Action git = action(h, objective, "workspace.git.run");
            assertTrue(git.invoke(request(objective, "workspace.git.run", Map.of("argsJson", "[\"add\",\"-A\"]"))).success());
            assertTrue(git.invoke(request(objective, "workspace.git.run",
                    Map.of("argsJson", "[\"commit\",\"-m\",\"hoa\"]"))).success());
            ActionFabric.Action publish = action(h, objective, "workspace.github.pr.publish");
            SecurityException blocked = assertThrows(SecurityException.class, () -> publish.invoke(request(objective,
                    "workspace.github.pr.publish", Map.of("title", "x", "body", "y"))));
            assertTrue(blocked.getMessage().contains("README.md"), blocked.getMessage());
            assertEquals(0, github.pullRequestsCreated(), "refused before any publish");
            assertTrue(github.publishedTreePaths().isEmpty(), "refused before any GitHub mutation");

            // Scope with only valid changed paths → the check passes (unit level).
            assertDoesNotThrow(() -> h.scopes().requireProposalPaths(HOA, List.of(
                    "DOMAINS/AQUACULTURE/ACTION_PLANS/ACTION_PLAN_v1.md", "DOMAINS/AQUACULTURE/REPORTS/week-1.md")));
            assertThrows(SecurityException.class, () -> h.scopes().requireProposalPaths(HOA, List.of(
                    "DOMAINS/AQUACULTURE/ACTION_PLANS/ACTION_PLAN_v1.md", "DOMAINS/AQUACULTURE/DECISIONS.md")));
        } finally {
            sandbox.stop();
            github.stop();
        }
    }

    @Test
    void t4_workersWithoutDeclaredScopeKeepExistingBehaviour() {
        WorkerResourceScopeService scopes = WorkerResourceScopeService.inMemory();
        assertDoesNotThrow(() -> scopes.requireRepository("WORKER-GENERAL-ENGINEERING", "kelvinka38/metatron-workforce"));
        assertDoesNotThrow(() -> scopes.requireWritablePath("WORKER-GENERAL-ENGINEERING", "src/main/App.java"));
        assertDoesNotThrow(() -> scopes.requireProposalPaths("WORKER-GENERAL-ENGINEERING", List.of("anything.txt")));
        assertTrue(scopes.find("WORKER-GENERAL-ENGINEERING").isEmpty());
    }

    // ---------------------------------------------------------------- T5

    @Test
    void t5_appointmentCapabilityProvesWorkerParticipationCapabilitiesProfileAndScope() {
        Durable d = durable();
        d.staffing().ensureStaffed(d.appointment(), T0);

        AutonomousExecutionCapability.CapabilityResult result = d.appointment().execute(appointmentRequest("human-primary"));
        assertTrue(result.success());
        assertEquals(HOA, result.workerId());
        List<String> evidence = result.evidenceReferences();
        assertTrue(evidence.contains("worker:" + HOA + ":status=ACTIVE"), evidence.toString());
        assertTrue(evidence.stream().anyMatch(e -> e.startsWith("participation:")
                && e.contains(":role=" + AquacultureHeadAppointmentCapability.ROLE_REF)
                && e.contains(":position=" + AquacultureHeadAppointmentCapability.POSITION_REF)), evidence.toString());
        for (String capability : List.of(
                AquacultureHeadAppointmentCapability.CAPABILITY,
                "aquaculture.domain.planning", "aquaculture.reporting", "aquaculture.delegation",
                "research.web.read", "repository.bios.proposal")) {
            assertTrue(evidence.contains("capability:" + capability), capability + " in " + evidence);
        }
        assertTrue(evidence.contains("runtime-profile-bound:" + WorkerRuntimeProfileBindingService.DOMAIN_HEAD_PROFILE));
        assertTrue(evidence.contains("resource-scope:repositories=[kelvinka38/bios]"), evidence.toString());
        assertTrue(evidence.contains("resource-scope:write-prefixes=[DOMAINS/AQUACULTURE/ACTION_PLANS/, DOMAINS/AQUACULTURE/REPORTS/]"),
                evidence.toString());
        assertTrue(evidence.contains("organization:bios"), evidence.toString());

        WorkforceCoreService.Participation participation = d.core().participations(HOA).stream()
                .filter(p -> AquacultureHeadAppointmentCapability.ROLE_REF.equals(p.roleRef())).findFirst().orElseThrow();
        assertEquals("bios", participation.organizationRef());
        assertTrue(d.core().availability(HOA).orElseThrow().available());

        // Position contract: mission, escalation to human-primary, no merge/deploy.
        AutonomousStaffingPolicy.PositionContractSpec contract = new AquacultureHeadStaffingPolicy().positionContractSpec();
        assertTrue(contract.mission().contains("DOMAINS/AQUACULTURE/EXECUTION_PLAN.md"));
        assertTrue(contract.reportingLines().stream().anyMatch(r ->
                "REPORTS_TO".equals(r.relationshipType()) && "human-primary".equals(r.targetRef())));
        Set<String> escalations = contract.escalationRoutes().stream()
                .filter(route -> "human-primary".equals(route.targetRef()))
                .map(AutonomousStaffingPolicy.EscalationRouteSpec::category).collect(Collectors.toSet());
        assertEquals(Set.of("OUT_OF_ENVELOPE", "TWO_FAILED_ATTEMPTS", "CAPABILITY_REQUIRED"), escalations);
        assertTrue(contract.successMeasures().stream().anyMatch(m -> m.description().contains("§7")));
        assertTrue(contract.successMeasures().stream().anyMatch(m -> m.target().startsWith("0 ")));

        // Governed appointment authority resolves for the HOA role and position.
        AuthorityManifestCatalog catalog = AuthorityManifestCatalog.classpath();
        assertEquals("metatron-founder-aquaculture-head-appointment",
                catalog.resolve(AquacultureHeadAppointmentCapability.ROLE_REF).manifestId());
        assertEquals("metatron-founder-aquaculture-head-appointment",
                catalog.resolve(AquacultureHeadAppointmentCapability.POSITION_REF).manifestId());
    }

    @Test
    void t5_onlyHumanPrimaryMayAppoint() {
        Durable d = durable();
        d.staffing().ensureStaffed(d.appointment(), T0);
        assertThrows(SecurityException.class, () -> d.appointment().execute(appointmentRequest("human-secondary")));
        assertThrows(SecurityException.class, () -> d.appointment().execute(appointmentRequest("WORKER-GATEWAY-DIRECTOR")));
        assertEquals("policy:founder-aquaculture-head-appointment:v1", d.appointment().authorityReference());
    }

    // ---------------------------------------------------------------- T7

    @Test
    void t7_hoaHasNoMergeOrDeployAuthority() throws Exception {
        Durable d = durable();
        d.staffing().ensureStaffed(d.appointment(), T0);
        Set<String> actions = d.profiles().requireBinding(HOA).profile().actionRefs();
        assertTrue(actions.stream().noneMatch(a -> a.toLowerCase(Locale.ROOT).contains("merge")), actions.toString());
        assertTrue(actions.stream().noneMatch(a -> a.toLowerCase(Locale.ROOT).contains("deploy")), actions.toString());
        assertTrue(actions.stream().noneMatch(a -> a.toLowerCase(Locale.ROOT).contains("release")), actions.toString());
        Set<String> capabilities = d.core().capabilities(HOA).stream()
                .map(WorkforceCoreService.Capability::capabilityRef).collect(Collectors.toSet());
        assertTrue(capabilities.stream().noneMatch(c -> c.contains("merge") || c.contains("deploy") || c.contains("release")),
                capabilities.toString());
        assertFalse(d.profiles().requireBinding(HOA).profile().allowedExecutables().contains("docker"));

        Harness h = harness(null);
        List<String> offered = h.catalog().actions(HOA, AquacultureDomainPlanningCapability.AUTHORIZATION_REFERENCE, "objective-t7")
                .stream().map(ActionFabric.Action::actionRef).toList();
        assertTrue(offered.stream().noneMatch(a -> a.contains("merge") || a.contains("deploy")), offered.toString());

        AutonomousStaffingPolicy.PositionContractSpec contract = new AquacultureHeadStaffingPolicy().positionContractSpec();
        assertTrue(contract.decisionRights().stream().anyMatch(r -> r.contains("never merge") && r.contains("deploy")),
                contract.decisionRights().toString());
        assertTrue(contract.resourceScopes().stream().anyMatch(r ->
                "tool-effects".equals(r.resourceRef()) && r.limitRef().contains("no-merge") && r.limitRef().contains("no-deploy")));
    }

    // ---------------------------------------------------------------- helpers

    private record Harness(ObjectiveWorkspaceService workspaces,
                           WorkerRuntimeProfileBindingService profiles,
                           WorkerResourceScopeService scopes,
                           WorkerExecutionSandboxService sandboxClient,
                           GeneralWorkspaceActionCatalog catalog) {}

    private Harness harness(LocalExecutionServers.RealProcessSandboxServer sandbox) throws Exception {
        return harness(sandbox, null);
    }

    private Harness harness(LocalExecutionServers.RealProcessSandboxServer sandbox,
                            LocalExecutionServers.FakeGitHubApiServer github) throws Exception {
        ObjectiveWorkspaceService workspaces = new ObjectiveWorkspaceService(temp.resolve("sandbox-root"));
        WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
        profiles.bind(HOA, WorkerRuntimeProfileBindingService.DOMAIN_HEAD_PROFILE,
                AquacultureHeadAppointmentCapability.CAPABILITY, T0);
        WorkerResourceScopeService scopes = WorkerResourceScopeService.inMemory();
        scopes.declare(HOA, AquacultureHeadStaffingPolicy.REPOSITORIES, AquacultureHeadStaffingPolicy.WRITE_PATH_PREFIXES, T0);
        ObjectMapper json = new ObjectMapper();
        HttpClient http = HttpClient.newHttpClient();
        URI sandboxUri = URI.create("http://127.0.0.1:" + (sandbox == null ? 9 : sandbox.port()));
        WorkerExecutionSandboxService sandboxClient = new WorkerExecutionSandboxService(
                http, sandboxUri, LocalExecutionServers.RealProcessSandboxServer.TOKEN, profiles, workspaces, json);
        RepositoryWorkspaceMaterializationService repositories =
                new RepositoryWorkspaceMaterializationService(http, "test-github-token", workspaces, json);
        URI githubUri = URI.create("http://127.0.0.1:" + (github == null ? 9 : github.port()) + "/");
        GitHubWorkspaceProposalPublisher proposals = new GitHubWorkspaceProposalPublisher(
                http, "test-github-token", workspaces, sandboxClient, json, githubUri);
        GeneralWorkspaceActionCatalog catalog = new GeneralWorkspaceActionCatalog(
                workspaces, sandboxClient, profiles, repositories, proposals, json, scopes);
        return new Harness(workspaces, profiles, scopes, sandboxClient, catalog);
    }

    private static ActionFabric.Action action(Harness h, String objectiveId, String actionRef) {
        return h.catalog().actions(HOA, AquacultureDomainPlanningCapability.AUTHORIZATION_REFERENCE, objectiveId).stream()
                .filter(a -> a.actionRef().equals(actionRef)).findFirst().orElseThrow(() -> new AssertionError(actionRef));
    }

    private static ActionFabric.ActionRequest request(String objectiveId, String actionRef, Map<String, String> inputs) {
        return new ActionFabric.ActionRequest(actionRef, HOA, "assignment-" + objectiveId,
                AquacultureDomainPlanningCapability.AUTHORIZATION_REFERENCE, objectiveId, "step-" + objectiveId,
                "idem-" + objectiveId, true, inputs);
    }

    static void seedProvenance(ObjectiveWorkspaceService workspaces, String objectiveId, String repository,
                               Map<String, String> files) throws Exception {
        ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces.provision(objectiveId, HOA);
        Files.writeString(workspace.path().resolve(".metatron-repository"),
                "repository=" + repository + "\nrequestedRef=main\ncommitSha=" + fakeBaseSha()
                        + "\ncomponentId=primary\n");
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path target = workspace.path().resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
    }

    /** Same base commit SHA LocalExecutionServers.FakeGitHubApiServer reports for the default branch. */
    private static String fakeBaseSha() throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest("fake-base-commit".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(hash).substring(0, 40);
    }

    private static AutonomousExecutionCapability.CapabilityRequest appointmentRequest(String humanId) {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                "appoint-hoa", "Appoint the Head of Aquaculture", AquacultureHeadAppointmentCapability.ROLE_REF,
                AquacultureHeadAppointmentCapability.CAPABILITY, List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("HOA appointed"), List.of("worker/participation/capability/profile/scope evidence"), null);
        return new AutonomousExecutionCapability.CapabilityRequest(
                humanId, "bios", "objective-appoint-hoa", work, HOA, "assignment-appoint-hoa",
                AquacultureHeadAppointmentCapability.AUTHORIZATION_REFERENCE, "dispatch-appoint-hoa", 1);
    }
}
