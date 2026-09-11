package com.metatron.workforce.management;

import com.metatron.workforce.action.ActionFabric;
import com.metatron.workforce.action.GeneralWorkspaceActionCatalog;
import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.execution.governance.ExecutionGate;
import com.metatron.workforce.execution.governance.ExecutionIntent;
import com.metatron.workforce.execution.governance.ExecutionPermit;
import com.metatron.workforce.execution.governance.GovernanceAttemptBindingService;
import com.metatron.workforce.execution.governance.GovernancePlanService;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.runtime.ObjectiveWorkspaceService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Canonical direct coding ingress adapter.
 *
 * <p>This class intentionally does not implement repository, file, process, build, Git or GitHub
 * operations itself. It adapts an authenticated direct MCP request onto the same
 * {@link GeneralWorkspaceActionCatalog}, Objective workspace, sandbox and Action Fabric used by the
 * Workforce coding capability. External publication remains the governed GitHub proposal action;
 * release/deploy is not part of this surface.</p>
 */
@Component
public final class DirectCodingIngressService {
    private static final Pattern REPOSITORY = Pattern.compile("^kelvinka38/[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final Pattern OBJECTIVE = Pattern.compile("^direct-mcp:(chatgpt|claude|gemini):[0-9a-fA-F-]{36}$");
    private static final Set<String> CLIENTS = Set.of("chatgpt", "claude", "gemini");
    private static final Set<String> DIRECT_ACTIONS = Set.of(
            "workspace.repository.materialize",
            "workspace.file.list",
            "workspace.file.search",
            "workspace.file.read",
            "workspace.file.patch",
            "workspace.file.write",
            "workspace.dependencies.install",
            "workspace.process.run",
            "workspace.shell.run",
            "workspace.git.status",
            "workspace.git.diff",
            "workspace.git.run",
            "workspace.build.run",
            "workspace.test.run",
            "workspace.github.pr.publish"
    );
    private static final Duration ATTEMPT_LEASE = Duration.ofMinutes(5);

    private final GeneralWorkspaceActionCatalog actions;
    private final WorkerRuntimeProfileBindingService profiles;
    private final ObjectiveWorkspaceService workspaces;
    private final GovernancePlanService governancePlans;
    private final GovernanceAttemptBindingService governanceAttempts;
    private final ExecutionAttemptService executionAttempts;
    private final ExecutionGate executionGate;

    public DirectCodingIngressService(GeneralWorkspaceActionCatalog actions,
                                      WorkerRuntimeProfileBindingService profiles,
                                      ObjectiveWorkspaceService workspaces,
                                      GovernancePlanService governancePlans,
                                      GovernanceAttemptBindingService governanceAttempts,
                                      ExecutionAttemptService executionAttempts,
                                      ExecutionGate executionGate) {
        this.actions = Objects.requireNonNull(actions);
        this.profiles = Objects.requireNonNull(profiles);
        this.workspaces = Objects.requireNonNull(workspaces);
        this.governancePlans = Objects.requireNonNull(governancePlans);
        this.governanceAttempts = Objects.requireNonNull(governanceAttempts);
        this.executionAttempts = Objects.requireNonNull(executionAttempts);
        this.executionGate = Objects.requireNonNull(executionGate);
    }

    public DirectCodingResult execute(String client, DirectCodingCommand command) {
        String normalizedClient = validateClient(client);
        Objects.requireNonNull(command, "command");
        String objectiveId = validateObjective(normalizedClient, command.objectiveId());
        String repository = validateRepository(command.repository());
        String actionRef = validateAction(command.actionRef());
        String idempotencyKey = require(command.idempotencyKey(), "idempotencyKey", 512);
        Map<String, String> inputs = command.inputs() == null ? Map.of() : Map.copyOf(command.inputs());

        profiles.bind(
                GeneralWorkspaceAutonomousCapability.WORKER_ID,
                WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                GeneralWorkspaceAutonomousCapability.CAPABILITY,
                Instant.now());

        ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces.provision(
                objectiveId, GeneralWorkspaceAutonomousCapability.WORKER_ID);
        requireRepositoryContinuity(workspace, repository, actionRef, inputs);

        ActionFabric fabric = new ActionFabric(actions.actions(
                GeneralWorkspaceAutonomousCapability.WORKER_ID,
                GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE,
                objectiveId), executionGate);
        ActionFabric.Consequence consequence = fabric.consequenceOf(actionRef);
        String stepId = "direct-coding:" + digest(idempotencyKey + "|" + actionRef).substring(0, 24);
        String assignmentRef = "assignment:direct-mcp:" + normalizedClient + ":" + objectiveId;
        ActionFabric.ActionRequest request = new ActionFabric.ActionRequest(
                actionRef,
                GeneralWorkspaceAutonomousCapability.WORKER_ID,
                assignmentRef,
                GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE,
                objectiveId,
                stepId,
                idempotencyKey,
                consequence == ActionFabric.Consequence.MUTATING,
                inputs);

        ActionFabric.ActionObservation observation;
        String permitId = "";
        String planRef = "";
        if (consequence == ActionFabric.Consequence.READ_ONLY) {
            observation = fabric.execute(request);
        } else {
            MutationContext governed = authorizeMutation(
                    normalizedClient, repository, actionRef, objectiveId, stepId,
                    assignmentRef, idempotencyKey, inputs);
            permitId = governed.permit().permitId();
            planRef = governed.bound().plan().planId() + "@" + governed.bound().plan().version();
            try {
                observation = fabric.execute(request, governed.permit());
                if (observation.success()) {
                    executionAttempts.succeed(governed.attempt().attemptId(), governed.attempt().fencingToken(), Instant.now());
                } else {
                    executionAttempts.fail(governed.attempt().attemptId(), governed.attempt().fencingToken(),
                            "action-observation-failed:" + actionRef, Instant.now());
                }
            } catch (RuntimeException failure) {
                try {
                    executionAttempts.fail(governed.attempt().attemptId(), governed.attempt().fencingToken(),
                            "direct-coding-action-exception:" + failure.getClass().getSimpleName(), Instant.now());
                } catch (RuntimeException ignored) {
                    // Preserve the original governed action failure.
                }
                throw failure;
            }
        }

        return new DirectCodingResult(
                observation.success(),
                objectiveId,
                repository,
                actionRef,
                observation.summary(),
                observation.outputs(),
                observation.evidenceReferences(),
                permitId,
                planRef,
                observation.observedAt());
    }

    private MutationContext authorizeMutation(String client,
                                               String repository,
                                               String actionRef,
                                               String objectiveId,
                                               String stepId,
                                               String assignmentRef,
                                               String idempotencyKey,
                                               Map<String, String> inputs) {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                stepId,
                "Execute governed direct coding action " + actionRef + " for " + repository,
                repository,
                GeneralWorkspaceAutonomousCapability.CAPABILITY,
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("requested repository coding action completes inside the isolated Objective workspace"),
                List.of("action-observation:" + actionRef));

        GovernancePlanService.BoundPlan bound = governancePlans.bindAuthorizedWork(
                objectiveId,
                GeneralWorkspaceAutonomousCapability.WORKER_ID,
                work,
                "FOUNDER",
                GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE,
                Map.of());

        Instant now = Instant.now();
        ExecutionAttempt attempt = executionAttempts.begin(
                "dispatch:direct-mcp:" + digest(idempotencyKey).substring(0, 24),
                objectiveId,
                stepId,
                GeneralWorkspaceAutonomousCapability.WORKER_ID,
                assignmentRef,
                GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE,
                "runtime:direct-mcp:" + client,
                1,
                ATTEMPT_LEASE,
                now);
        governanceAttempts.bind(attempt, bound);

        ExecutionPermit permit = executionGate.authorize(new ExecutionIntent(
                objectiveId,
                attempt.attemptId(),
                attempt.fencingToken(),
                GeneralWorkspaceAutonomousCapability.WORKER_ID,
                assignmentRef,
                GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE,
                bound.plan().planId(),
                bound.plan().version(),
                stepId,
                actionRef,
                ActionFabric.Consequence.MUTATING,
                repository,
                bound.snapshot().snapshotId(),
                bound.derivation().receiptId(),
                inputs,
                now));
        return new MutationContext(bound, attempt, permit);
    }

    private void requireRepositoryContinuity(ObjectiveWorkspaceService.ObjectiveWorkspace workspace,
                                             String repository,
                                             String actionRef,
                                             Map<String, String> inputs) {
        if ("workspace.repository.materialize".equals(actionRef)) {
            String requested = validateRepository(inputs.get("repository"));
            if (!repository.equals(requested)) throw new SecurityException("direct_repository_mismatch");
            return;
        }
        Path provenance = workspaces.resolve(workspace, ".metatron-repository");
        if (!Files.isRegularFile(provenance, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(provenance)) {
            throw new IllegalStateException("direct_repository_session_not_open");
        }
        Map<String, String> fields = keyValueLines(workspaces.read(workspace, ".metatron-repository"));
        if (!repository.equals(fields.get("repository"))) {
            throw new SecurityException("direct_repository_session_mismatch");
        }
    }

    static String validateClient(String value) {
        String client = require(value, "client", 32).toLowerCase(java.util.Locale.ROOT);
        if (!CLIENTS.contains(client)) throw new SecurityException("direct_client_not_allowed");
        return client;
    }

    static String validateRepository(String value) {
        String repository = require(value, "repository", 160);
        if (!REPOSITORY.matcher(repository).matches()) throw new SecurityException("direct_repository_not_allowed");
        return repository;
    }

    static String validateObjective(String client, String value) {
        String objective = require(value, "objectiveId", 128);
        if (!OBJECTIVE.matcher(objective).matches() || !objective.startsWith("direct-mcp:" + client + ":")) {
            throw new SecurityException("direct_objective_not_allowed");
        }
        return objective;
    }

    static String validateAction(String value) {
        String action = require(value, "actionRef", 160);
        if (!DIRECT_ACTIONS.contains(action)) throw new SecurityException("direct_action_not_allowed");
        return action;
    }

    private static Map<String, String> keyValueLines(String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (body != null) {
            for (String line : body.lines().toList()) {
                int split = line.indexOf('=');
                if (split > 0) fields.put(line.substring(0, split).trim(), line.substring(split + 1).trim());
            }
        }
        return Map.copyOf(fields);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String require(String value, String field, int max) {
        if (value == null) throw new IllegalArgumentException(field + " required");
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > max || normalized.indexOf('\0') >= 0
                || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return normalized;
    }

    public record DirectCodingCommand(
            String objectiveId,
            String repository,
            String actionRef,
            String idempotencyKey,
            Map<String, String> inputs) {}

    public record DirectCodingResult(
            boolean ok,
            String objectiveId,
            String repository,
            String actionRef,
            String summary,
            Map<String, String> outputs,
            List<String> evidenceReferences,
            String executionPermit,
            String governancePlan,
            Instant observedAt) {
        public DirectCodingResult {
            outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
            evidenceReferences = evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences);
        }
    }

    private record MutationContext(
            GovernancePlanService.BoundPlan bound,
            ExecutionAttempt attempt,
            ExecutionPermit permit) {}
}
