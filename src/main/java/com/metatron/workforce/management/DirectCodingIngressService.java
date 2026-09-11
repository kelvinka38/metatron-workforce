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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Authenticated direct MCP adapter onto the canonical Workforce coding substrate. */
@Component
public final class DirectCodingIngressService {
    private static final Pattern REPOSITORY = Pattern.compile("^kelvinka38/[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final Pattern OBJECTIVE = Pattern.compile("^direct-mcp:(chatgpt|claude|gemini):[0-9a-fA-F-]{36}$");
    private static final Set<String> CLIENTS = Set.of("chatgpt", "claude", "gemini");
    private static final Set<String> ACTIONS = Set.of(
            "workspace.repository.materialize",
            "workspace.file.list", "workspace.file.search", "workspace.file.read",
            "workspace.file.patch", "workspace.file.write",
            "workspace.dependencies.install", "workspace.process.run", "workspace.shell.run",
            "workspace.git.status", "workspace.git.diff", "workspace.git.run",
            "workspace.build.run", "workspace.test.run", "workspace.github.pr.publish");
    private static final Duration LEASE = Duration.ofMinutes(5);

    private final GeneralWorkspaceActionCatalog catalog;
    private final WorkerRuntimeProfileBindingService profiles;
    private final ObjectiveWorkspaceService workspaces;
    private final GovernancePlanService governancePlans;
    private final GovernanceAttemptBindingService governanceAttempts;
    private final ExecutionAttemptService attempts;
    private final ExecutionGate gate;

    public DirectCodingIngressService(GeneralWorkspaceActionCatalog catalog,
                                      WorkerRuntimeProfileBindingService profiles,
                                      ObjectiveWorkspaceService workspaces,
                                      GovernancePlanService governancePlans,
                                      GovernanceAttemptBindingService governanceAttempts,
                                      ExecutionAttemptService attempts,
                                      ExecutionGate gate) {
        this.catalog = Objects.requireNonNull(catalog);
        this.profiles = Objects.requireNonNull(profiles);
        this.workspaces = Objects.requireNonNull(workspaces);
        this.governancePlans = Objects.requireNonNull(governancePlans);
        this.governanceAttempts = Objects.requireNonNull(governanceAttempts);
        this.attempts = Objects.requireNonNull(attempts);
        this.gate = Objects.requireNonNull(gate);
    }

    public Result execute(String client, Command command) {
        String c = validateClient(client);
        Objects.requireNonNull(command, "command");
        String objective = validateObjective(c, command.objectiveId());
        String repository = validateRepository(command.repository());
        String action = validateAction(command.actionRef());
        String idempotency = require(command.idempotencyKey(), "idempotencyKey", 512);
        Map<String, String> inputs = command.inputs() == null ? Map.of() : Map.copyOf(command.inputs());

        profiles.bind(GeneralWorkspaceAutonomousCapability.WORKER_ID,
                WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                GeneralWorkspaceAutonomousCapability.CAPABILITY, Instant.now());
        ObjectiveWorkspaceService.ObjectiveWorkspace workspace =
                workspaces.provision(objective, GeneralWorkspaceAutonomousCapability.WORKER_ID);
        requireRepositoryContinuity(workspace, repository, action, inputs);

        ActionFabric fabric = new ActionFabric(catalog.actions(
                GeneralWorkspaceAutonomousCapability.WORKER_ID,
                GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE,
                objective), gate);
        ActionFabric.Consequence consequence = fabric.consequenceOf(action);
        String step = "direct-coding:" + digest(idempotency + "|" + action).substring(0, 24);
        String assignment = "assignment:direct-mcp:" + c + ":" + objective;
        ActionFabric.ActionRequest request = new ActionFabric.ActionRequest(
                action, GeneralWorkspaceAutonomousCapability.WORKER_ID, assignment,
                GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE,
                objective, step, idempotency,
                consequence == ActionFabric.Consequence.MUTATING, inputs);

        ActionFabric.ActionObservation observation;
        String permitRef = "";
        String planRef = "";
        if (consequence == ActionFabric.Consequence.READ_ONLY) {
            observation = fabric.execute(request);
        } else {
            Mutation mutation = authorizeMutation(c, repository, action, objective, step, assignment, idempotency, inputs);
            permitRef = mutation.permit().permitId();
            planRef = mutation.bound().plan().planId() + "@" + mutation.bound().plan().version();
            try {
                observation = fabric.execute(request, mutation.permit());
                if (observation.success()) {
                    attempts.succeed(mutation.attempt().attemptId(), mutation.attempt().fencingToken(), Instant.now());
                } else {
                    attempts.fail(mutation.attempt().attemptId(), mutation.attempt().fencingToken(),
                            "direct-coding-action-failed:" + action, Instant.now());
                }
            } catch (RuntimeException failure) {
                try {
                    attempts.fail(mutation.attempt().attemptId(), mutation.attempt().fencingToken(),
                            "direct-coding-exception:" + failure.getClass().getSimpleName(), Instant.now());
                } catch (RuntimeException ignored) { }
                throw failure;
            }
        }
        return new Result(observation.success(), objective, repository, action, observation.summary(),
                observation.outputs(), observation.evidenceReferences(), permitRef, planRef, observation.observedAt());
    }

    private Mutation authorizeMutation(String client, String repository, String action, String objective,
                                       String step, String assignment, String idempotency,
                                       Map<String, String> inputs) {
        ExecutionWorkSpec work = new ExecutionWorkSpec(
                step,
                "Execute governed direct coding action " + action + " for " + repository,
                repository,
                GeneralWorkspaceAutonomousCapability.CAPABILITY,
                List.of(), ExecutionWorkSpec.Consequence.MUTATING,
                List.of("requested repository coding action completes inside the isolated Objective workspace"),
                List.of("action-observation:" + action));
        GovernancePlanService.BoundPlan bound = governancePlans.bindAuthorizedWork(
                objective, GeneralWorkspaceAutonomousCapability.WORKER_ID, work,
                "FOUNDER", GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE, Map.of());
        Instant now = Instant.now();
        ExecutionAttempt attempt = attempts.begin(
                "dispatch:direct-mcp:" + digest(idempotency).substring(0, 24), objective, step,
                GeneralWorkspaceAutonomousCapability.WORKER_ID, assignment,
                GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE,
                "runtime:direct-mcp:" + client, 1, LEASE, now);
        governanceAttempts.bind(attempt, bound);
        ExecutionPermit permit = gate.authorize(new ExecutionIntent(
                objective, attempt.attemptId(), attempt.fencingToken(),
                GeneralWorkspaceAutonomousCapability.WORKER_ID, assignment,
                GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE,
                bound.plan().planId(), bound.plan().version(), step, action,
                ActionFabric.Consequence.MUTATING, repository,
                bound.snapshot().snapshotId(), bound.derivation().receiptId(), inputs, now));
        return new Mutation(bound, attempt, permit);
    }

    private void requireRepositoryContinuity(ObjectiveWorkspaceService.ObjectiveWorkspace workspace,
                                             String repository, String action, Map<String, String> inputs) {
        if ("workspace.repository.materialize".equals(action)) {
            if (!repository.equals(validateRepository(inputs.get("repository")))) {
                throw new SecurityException("direct_repository_mismatch");
            }
            return;
        }
        Path provenance = workspaces.resolve(workspace, ".metatron-repository");
        if (!Files.isRegularFile(provenance, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(provenance)) {
            throw new IllegalStateException("direct_repository_session_not_open");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : workspaces.read(workspace, ".metatron-repository").lines().toList()) {
            int split = line.indexOf('=');
            if (split > 0) fields.put(line.substring(0, split).trim(), line.substring(split + 1).trim());
        }
        if (!repository.equals(fields.get("repository"))) throw new SecurityException("direct_repository_session_mismatch");
    }

    static String validateClient(String value) {
        String v = require(value, "client", 32).toLowerCase(Locale.ROOT);
        if (!CLIENTS.contains(v)) throw new SecurityException("direct_client_not_allowed");
        return v;
    }

    static String validateRepository(String value) {
        String v = require(value, "repository", 160);
        if (!REPOSITORY.matcher(v).matches()) throw new SecurityException("direct_repository_not_allowed");
        return v;
    }

    static String validateObjective(String client, String value) {
        String v = require(value, "objectiveId", 128);
        if (!OBJECTIVE.matcher(v).matches() || !v.startsWith("direct-mcp:" + client + ":")) {
            throw new SecurityException("direct_objective_not_allowed");
        }
        return v;
    }

    static String validateAction(String value) {
        String v = require(value, "actionRef", 160);
        if (!ACTIONS.contains(v)) throw new SecurityException("direct_action_not_allowed");
        return v;
    }

    private static String require(String value, String field, int max) {
        if (value == null) throw new IllegalArgumentException(field + " required");
        String v = value.trim();
        if (v.isEmpty() || v.length() > max || v.indexOf('\0') >= 0 || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return v;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Command(String objectiveId, String repository, String actionRef,
                          String idempotencyKey, Map<String, String> inputs) { }

    public record Result(boolean ok, String objectiveId, String repository, String actionRef,
                         String summary, Map<String, String> outputs, List<String> evidenceReferences,
                         String executionPermit, String governancePlan, Instant observedAt) {
        public Result {
            outputs = outputs == null ? Map.of() : Map.copyOf(outputs);
            evidenceReferences = evidenceReferences == null ? List.of() : List.copyOf(evidenceReferences);
        }
    }

    private record Mutation(GovernancePlanService.BoundPlan bound, ExecutionAttempt attempt, ExecutionPermit permit) { }
}
