package com.metatron.workforce.action;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.runtime.ObjectiveWorkspaceService;
import com.metatron.workforce.runtime.RepositoryWorkspaceMaterializationService;
import com.metatron.workforce.runtime.WorkerExecutionSandboxService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** General governed repository/file/process/shell/Git/build/test actions scoped to one Objective workspace. */
public final class GeneralWorkspaceActionCatalog {
    private final ObjectiveWorkspaceService workspaces;
    private final WorkerExecutionSandboxService sandbox;
    private final WorkerRuntimeProfileBindingService profiles;
    private final RepositoryWorkspaceMaterializationService repositories;
    private final ObjectMapper json;

    public GeneralWorkspaceActionCatalog(ObjectiveWorkspaceService workspaces,
                                         WorkerExecutionSandboxService sandbox,
                                         WorkerRuntimeProfileBindingService profiles,
                                         RepositoryWorkspaceMaterializationService repositories,
                                         ObjectMapper json) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.json = Objects.requireNonNull(json, "json");
    }

    public List<ActionFabric.Action> actions(String workerId, String authorizationReference, String objectiveId) {
        WorkerRuntimeProfileBindingService.ToolProfile profile = profiles.requireBinding(workerId).profile();
        ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces.provision(objectiveId, workerId);
        List<ActionFabric.Action> actions = new ArrayList<>();
        add(profile, actions, repositoryMaterialize(workerId, authorizationReference, objectiveId, workspace));
        add(profile, actions, fileRead(workerId, authorizationReference, workspace));
        add(profile, actions, fileList(workerId, authorizationReference, workspace));
        add(profile, actions, fileWrite(workerId, authorizationReference, workspace));
        add(profile, actions, process(workerId, authorizationReference, objectiveId));
        add(profile, actions, shell(workerId, authorizationReference, objectiveId));
        add(profile, actions, gitStatus(workerId, authorizationReference, objectiveId));
        add(profile, actions, gitDiff(workerId, authorizationReference, objectiveId));
        add(profile, actions, gitRun(workerId, authorizationReference, objectiveId));
        add(profile, actions, build(workerId, authorizationReference, objectiveId, workspace));
        add(profile, actions, test(workerId, authorizationReference, objectiveId, workspace));
        return List.copyOf(actions);
    }

    private static void add(WorkerRuntimeProfileBindingService.ToolProfile profile,
                            List<ActionFabric.Action> actions,
                            ActionFabric.Action action) {
        if (profile.actionRefs().contains(action.actionRef())) actions.add(action);
    }

    private ActionFabric.Action repositoryMaterialize(String worker, String auth, String objectiveId,
                                                       ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        // Materialization reads an immutable remote snapshot and prepares only the isolated Objective workspace.
        // It must remain available to READ_ONLY repository work; external/remote state is never mutated here.
        return action("workspace.repository.materialize", ActionFabric.Consequence.READ_ONLY, worker, auth, request -> {
            String repository = input(request, "repository");
            String ref = request.inputs().getOrDefault("ref", "main");
            RepositoryWorkspaceMaterializationService.MaterializedRepository materialized =
                    repositories.materialize(worker, objectiveId, repository, ref);

            runOrThrow(worker, objectiveId, "git", List.of("init", "-q"), "git init");
            runOrThrow(worker, objectiveId, "git", List.of("config", "user.name", "Metatron Workforce"), "git user.name");
            runOrThrow(worker, objectiveId, "git", List.of("config", "user.email", "workforce@metatron.local"), "git user.email");
            runOrThrow(worker, objectiveId, "git", List.of("add", "-A"), "git baseline add");
            runOrThrow(worker, objectiveId, "git", List.of("commit", "-q", "-m", "metatron materialized baseline"), "git baseline commit");
            WorkerExecutionSandboxService.SandboxResult head = runOrThrow(
                    worker, objectiveId, "git", List.of("rev-parse", "HEAD"), "git baseline head");
            String localHead = head.output().trim();
            if (!localHead.matches("[0-9a-f]{40}")) throw new IllegalStateException("local materialized baseline missing Git SHA");

            Map<String, String> outputs = new LinkedHashMap<>();
            outputs.put("repository", materialized.repository());
            outputs.put("requestedRef", materialized.requestedRef());
            outputs.put("sourceCommitSha", materialized.resolvedCommitSha());
            outputs.put("localBaselineCommitSha", localHead);
            outputs.put("materializedFiles", Integer.toString(materialized.files()));
            outputs.put("materializedBytes", Long.toString(materialized.bytes()));
            outputs.put("workspaceRef", workspace.workspaceRef());
            return observation(request.actionRef(), true, "private repository materialized into isolated Objective workspace",
                    outputs, List.of(
                            "repository-materialized:" + materialized.repository() + "@" + materialized.resolvedCommitSha(),
                            "objective-workspace:" + workspace.workspaceKey() + ":baseline=" + localHead));
        });
    }

    private WorkerExecutionSandboxService.SandboxResult runOrThrow(String worker, String objectiveId,
                                                                    String executable, List<String> args,
                                                                    String operation) {
        WorkerExecutionSandboxService.SandboxResult result = sandbox.run(worker, objectiveId, executable, args);
        if (!result.success()) {
            throw new IllegalStateException(operation + " failed: " + abbreviate(result.output()));
        }
        return result;
    }

    private ActionFabric.Action fileRead(String worker, String auth, ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        return action("workspace.file.read", ActionFabric.Consequence.READ_ONLY, worker, auth, request -> {
            String path = input(request, "path");
            String content = workspaces.read(workspace, path);
            return observation(request.actionRef(), true, "workspace file read",
                    Map.of("path", path, "content", content), workspaceEvidence(workspace, request.actionRef()));
        });
    }

    private ActionFabric.Action fileList(String worker, String auth, ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        return action("workspace.file.list", ActionFabric.Consequence.READ_ONLY, worker, auth, request -> {
            String path = request.inputs().getOrDefault("path", "");
            List<String> files = workspaces.list(workspace, path);
            return observation(request.actionRef(), true, "workspace listed",
                    Map.of("path", path, "filesJson", write(files)), workspaceEvidence(workspace, request.actionRef()));
        });
    }

    private ActionFabric.Action fileWrite(String worker, String auth, ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        return action("workspace.file.write", ActionFabric.Consequence.MUTATING, worker, auth, request -> {
            String path = input(request, "path");
            String content = request.inputs().getOrDefault("content", "");
            workspaces.write(workspace, path, content);
            return observation(request.actionRef(), true, "workspace file written",
                    Map.of("path", path, "bytes", Integer.toString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)),
                    workspaceEvidence(workspace, request.actionRef()));
        });
    }

    private ActionFabric.Action process(String worker, String auth, String objectiveId) {
        return action("workspace.process.run", ActionFabric.Consequence.MUTATING, worker, auth, request -> {
            String executable = input(request, "executable");
            List<String> args = stringList(request.inputs().getOrDefault("argsJson", "[]"));
            return sandboxObservation(request.actionRef(), sandbox.run(worker, objectiveId, executable, args));
        });
    }

    private ActionFabric.Action shell(String worker, String auth, String objectiveId) {
        return action("workspace.shell.run", ActionFabric.Consequence.MUTATING, worker, auth, request -> {
            String command = input(request, "command");
            return sandboxObservation(request.actionRef(), sandbox.run(worker, objectiveId, "sh", List.of("-lc", command)));
        });
    }

    private ActionFabric.Action gitStatus(String worker, String auth, String objectiveId) {
        return action("workspace.git.status", ActionFabric.Consequence.READ_ONLY, worker, auth,
                request -> sandboxObservation(request.actionRef(),
                        sandbox.run(worker, objectiveId, "git", List.of("status", "--short", "--branch"))));
    }

    private ActionFabric.Action gitDiff(String worker, String auth, String objectiveId) {
        return action("workspace.git.diff", ActionFabric.Consequence.READ_ONLY, worker, auth,
                request -> sandboxObservation(request.actionRef(),
                        sandbox.run(worker, objectiveId, "git", List.of("diff", "--no-ext-diff"))));
    }

    private ActionFabric.Action gitRun(String worker, String auth, String objectiveId) {
        return action("workspace.git.run", ActionFabric.Consequence.MUTATING, worker, auth, request -> {
            List<String> args = stringList(input(request, "argsJson"));
            if (args.isEmpty()) throw new IllegalArgumentException("git args required");
            return sandboxObservation(request.actionRef(), sandbox.run(worker, objectiveId, "git", args));
        });
    }

    private ActionFabric.Action build(String worker, String auth, String objectiveId,
                                      ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        return action("workspace.build.run", ActionFabric.Consequence.MUTATING, worker, auth, request -> {
            BuildCommand command = buildCommand(workspace, request.inputs().getOrDefault("tasksJson", "[]"), false);
            return sandboxObservation(request.actionRef(), sandbox.run(worker, objectiveId, command.executable(), command.args()));
        });
    }

    private ActionFabric.Action test(String worker, String auth, String objectiveId,
                                     ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        return action("workspace.test.run", ActionFabric.Consequence.MUTATING, worker, auth, request -> {
            BuildCommand command = buildCommand(workspace, request.inputs().getOrDefault("tasksJson", "[]"), true);
            return sandboxObservation(request.actionRef(), sandbox.run(worker, objectiveId, command.executable(), command.args()));
        });
    }

    private BuildCommand buildCommand(ObjectiveWorkspaceService.ObjectiveWorkspace workspace,
                                      String tasksJson,
                                      boolean test) {
        List<String> tasks = stringList(tasksJson);
        if (Files.exists(workspaces.resolve(workspace, "gradlew"))) {
            List<String> args = new ArrayList<>();
            args.add("--no-daemon");
            args.addAll(tasks.isEmpty() ? List.of(test ? "test" : "build") : tasks);
            return new BuildCommand("./gradlew", args);
        }
        if (Files.exists(workspaces.resolve(workspace, "build.gradle"))
                || Files.exists(workspaces.resolve(workspace, "build.gradle.kts"))) {
            return new BuildCommand("gradle", tasks.isEmpty() ? List.of(test ? "test" : "build") : tasks);
        }
        if (Files.exists(workspaces.resolve(workspace, "mvnw"))) {
            return new BuildCommand("./mvnw", tasks.isEmpty() ? List.of(test ? "test" : "verify") : tasks);
        }
        if (Files.exists(workspaces.resolve(workspace, "pom.xml"))) {
            return new BuildCommand("mvn", tasks.isEmpty() ? List.of(test ? "test" : "verify") : tasks);
        }
        throw new IllegalStateException("workspace build system not detected");
    }

    private ActionFabric.Action action(String ref,
                                       ActionFabric.Consequence consequence,
                                       String worker,
                                       String auth,
                                       java.util.function.Function<ActionFabric.ActionRequest, ActionFabric.ActionObservation> invocation) {
        return new ActionFabric.Action() {
            @Override public String actionRef() { return ref; }
            @Override public ActionFabric.Consequence consequence() { return consequence; }
            @Override public Set<String> allowedWorkers() { return Set.of(worker); }
            @Override public Set<String> acceptedAuthorizations() { return Set.of(auth); }
            @Override public ActionFabric.ActionObservation invoke(ActionFabric.ActionRequest request) {
                return invocation.apply(request);
            }
        };
    }

    private ActionFabric.ActionObservation sandboxObservation(String actionRef, WorkerExecutionSandboxService.SandboxResult result) {
        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("exitCode", Integer.toString(result.exitCode()));
        outputs.put("timedOut", Boolean.toString(result.timedOut()));
        outputs.put("outputTruncated", Boolean.toString(result.outputTruncated()));
        outputs.put("output", result.output());
        outputs.put("workspaceKey", result.workspaceKey());
        outputs.put("executable", result.executable());
        outputs.put("durationMillis", Long.toString(result.durationMillis()));
        List<String> evidence = List.of("worker-sandbox:workspace=" + result.workspaceKey()
                + ":executable=" + result.executable()
                + ":exit=" + result.exitCode()
                + ":timeout=" + result.timedOut());
        return observation(actionRef, result.success(), result.success() ? "sandbox command completed" : "sandbox command failed",
                outputs, evidence);
    }

    private static List<String> workspaceEvidence(ObjectiveWorkspaceService.ObjectiveWorkspace workspace, String actionRef) {
        return List.of("objective-workspace:" + workspace.workspaceKey() + ":action=" + actionRef);
    }

    private ActionFabric.ActionObservation observation(String ref, boolean success, String summary,
                                                       Map<String, String> outputs, List<String> evidence) {
        return new ActionFabric.ActionObservation(ref, success, summary, outputs, evidence, java.time.Instant.now());
    }

    private List<String> stringList(String value) {
        try {
            return json.readValue(value, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("expected JSON string array", e);
        }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("cannot serialize action output", e); }
    }

    private static String input(ActionFabric.ActionRequest request, String key) {
        String value = request.inputs().get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("action input required: " + key);
        return value;
    }

    private static String abbreviate(String value) {
        String clean = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= 300 ? clean : clean.substring(0, 300);
    }

    private record BuildCommand(String executable, List<String> args) {}
}
