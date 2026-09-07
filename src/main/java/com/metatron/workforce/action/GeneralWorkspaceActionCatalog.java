package com.metatron.workforce.action;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.runtime.ObjectiveWorkspaceService;
import com.metatron.workforce.runtime.RepositoryWorkspaceMaterializationService;
import com.metatron.workforce.runtime.RepositoryWorkspaceMaterializationState;
import com.metatron.workforce.runtime.GitHubWorkspaceProposalPublisher;
import com.metatron.workforce.runtime.WorkerExecutionSandboxService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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
    private final GitHubWorkspaceProposalPublisher proposals;
    private final ObjectMapper json;

    public GeneralWorkspaceActionCatalog(ObjectiveWorkspaceService workspaces,
                                         WorkerExecutionSandboxService sandbox,
                                         WorkerRuntimeProfileBindingService profiles,
                                         RepositoryWorkspaceMaterializationService repositories,
                                         GitHubWorkspaceProposalPublisher proposals,
                                         ObjectMapper json) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.repositories = Objects.requireNonNull(repositories, "repositories");
        this.proposals = Objects.requireNonNull(proposals, "proposals");
        this.json = Objects.requireNonNull(json, "json");
    }

    public List<ActionFabric.Action> actions(String workerId, String authorizationReference, String objectiveId) {
        WorkerRuntimeProfileBindingService.ToolProfile profile = profiles.requireBinding(workerId).profile();
        ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces.provision(objectiveId, workerId);
        List<ActionFabric.Action> actions = new ArrayList<>();
        add(profile, actions, new GeneralWebResearchAction(workerId, authorizationReference));
        add(profile, actions, repositoryMaterialize(workerId, authorizationReference, objectiveId, workspace));
        add(profile, actions, fileRead(workerId, authorizationReference, workspace));
        add(profile, actions, fileList(workerId, authorizationReference, workspace));
        add(profile, actions, fileWrite(workerId, authorizationReference, workspace));
        add(profile, actions, dependenciesInstall(workerId, authorizationReference, objectiveId, workspace));
        add(profile, actions, process(workerId, authorizationReference, objectiveId));
        add(profile, actions, shell(workerId, authorizationReference, objectiveId));
        add(profile, actions, gitStatus(workerId, authorizationReference, objectiveId));
        add(profile, actions, gitDiff(workerId, authorizationReference, objectiveId));
        add(profile, actions, gitRun(workerId, authorizationReference, objectiveId));
        add(profile, actions, githubProposal(workerId, authorizationReference, objectiveId));
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
        // Materialization changes only the isolated Objective scratch workspace. It does not mutate the
        // governed source repository or any external target, so it must remain available to READ_ONLY Work.
        return action("workspace.repository.materialize", ActionFabric.Consequence.READ_ONLY, worker, auth, request -> {
            String repository = input(request, "repository").trim();
            String ref = request.inputs().getOrDefault("ref", "main").trim();
            RepositoryWorkspaceMaterializationService.MaterializedRepository materialized =
                    existingMaterialization(workspaces, workspace, repository, ref);
            boolean reused = materialized != null;
            if (!reused) {
                materialized = repositories.materialize(worker, objectiveId, repository, ref);
            }

            String localBaseline = RepositoryWorkspaceMaterializationState.completedBaselineSha(workspaces, workspace);
            if (localBaseline.isBlank()) {
                localBaseline = establishOrRecoverMaterializationBaseline(worker, objectiveId);
            }
            if (!localBaseline.matches("[0-9a-f]{40}")) {
                throw new IllegalStateException("local materialized baseline missing Git SHA");
            }
            String verifiedBaseline = runOrThrow(
                    worker, objectiveId, "git",
                    List.of("rev-parse", RepositoryWorkspaceMaterializationState.BASELINE_REF),
                    "git materialization baseline ref").output().trim();
            if (!localBaseline.equals(verifiedBaseline)) {
                throw new IllegalStateException("materialization baseline ref verification mismatch");
            }

            Map<String, String> outputs = new LinkedHashMap<>();
            outputs.put("repository", materialized.repository());
            outputs.put("requestedRef", materialized.requestedRef());
            outputs.put("sourceCommitSha", materialized.resolvedCommitSha());
            outputs.put("localBaselineCommitSha", localBaseline);
            outputs.put("materializedFiles", Integer.toString(materialized.files()));
            outputs.put("materializedBytes", Long.toString(materialized.bytes()));
            outputs.put("workspaceRef", workspace.workspaceRef());
            outputs.put("reused", Boolean.toString(reused));
            String evidencePrefix = reused ? "repository-materialization-reused:" : "repository-materialized:";
            return observation(request.actionRef(), true,
                    reused ? "existing immutable repository materialization reused" : "private repository materialized into isolated Objective workspace",
                    outputs, List.of(
                            evidencePrefix + materialized.repository() + "@" + materialized.resolvedCommitSha(),
                            "objective-workspace:" + workspace.workspaceKey() + ":baseline=" + localBaseline,
                            "repository-materialization-complete-ref:" + RepositoryWorkspaceMaterializationState.BASELINE_REF
                                    + "=" + localBaseline));
        });
    }

    private String establishOrRecoverMaterializationBaseline(String worker, String objectiveId) {
        WorkerExecutionSandboxService.SandboxResult existingHead =
                sandbox.run(worker, objectiveId, "git", List.of("rev-parse", "HEAD"));
        String baseline;
        if (!existingHead.success() || !existingHead.output().trim().matches("[0-9a-f]{40}")) {
            runOrThrow(worker, objectiveId, "git", List.of("init", "-q"), "git init");
            runOrThrow(worker, objectiveId, "git", List.of("config", "user.name", "Metatron Workforce"), "git user.name");
            runOrThrow(worker, objectiveId, "git", List.of("config", "user.email", "workforce@metatron.local"), "git user.email");
            runOrThrow(worker, objectiveId, "git", List.of("add", "-A"), "git baseline add");
            runOrThrow(worker, objectiveId, "git",
                    List.of("commit", "-q", "-m", "metatron materialized baseline"), "git baseline commit");
            baseline = runOrThrow(
                    worker, objectiveId, "git", List.of("rev-parse", "HEAD"), "git baseline head").output().trim();
        } else {
            baseline = existingHead.output().trim();
            String subject = runOrThrow(
                    worker, objectiveId, "git", List.of("log", "-1", "--pretty=%s"), "git baseline recovery subject")
                    .output().trim();
            String parents = runOrThrow(
                    worker, objectiveId, "git", List.of("rev-list", "--parents", "-n", "1", "HEAD"),
                    "git baseline recovery parents").output().trim();
            String status = runOrThrow(
                    worker, objectiveId, "git", List.of("status", "--porcelain"), "git baseline recovery status")
                    .output().trim();
            int fields = parents.isBlank() ? 0 : parents.split("\\s+").length;
            if (!"metatron materialized baseline".equals(subject) || fields != 1 || !status.isBlank()) {
                throw new IllegalStateException(
                        "partial repository materialization contains non-baseline Git history; refusing to bless Work product as baseline");
            }
        }
        if (!baseline.matches("[0-9a-f]{40}")) {
            throw new IllegalStateException("materialization baseline commit missing immutable SHA");
        }
        runOrThrow(worker, objectiveId, "git",
                List.of("update-ref", RepositoryWorkspaceMaterializationState.BASELINE_REF, baseline),
                "git materialization completion ref");
        String status = runOrThrow(
                worker, objectiveId, "git", List.of("status", "--porcelain"), "git materialization completion status")
                .output().trim();
        if (!status.isBlank()) {
            throw new IllegalStateException("materialization completion requires a clean baseline workspace");
        }
        return baseline;
    }

    /** Same Objective + same repository/ref may safely retry materialization without destroying Work product. */
    static RepositoryWorkspaceMaterializationService.MaterializedRepository existingMaterialization(
            ObjectiveWorkspaceService workspaces,
            ObjectiveWorkspaceService.ObjectiveWorkspace workspace,
            String repository,
            String ref) {
        Path provenance = workspaces.resolve(workspace, ".metatron-repository");
        if (!Files.isRegularFile(provenance, LinkOption.NOFOLLOW_LINKS)) return null;
        Map<String, String> fields = keyValueLines(workspaces.read(workspace, ".metatron-repository"));
        String existingRepository = fields.getOrDefault("repository", "");
        String existingRef = fields.getOrDefault("requestedRef", "");
        String existingSha = fields.getOrDefault("commitSha", "");
        String requestedRef = ref == null || ref.isBlank() ? "main" : ref.trim();
        if (!repository.trim().equals(existingRepository)
                || !(requestedRef.equals(existingRef) || requestedRef.equals(existingSha))) {
            throw new IllegalStateException("objective workspace repository materialization conflict");
        }
        if (!existingSha.matches("[0-9a-f]{40}")) {
            throw new IllegalStateException("objective workspace repository provenance is invalid");
        }
        WorkspaceStats stats = workspaceStats(workspace);
        return new RepositoryWorkspaceMaterializationService.MaterializedRepository(
                existingRepository, existingRef, existingSha, workspace.workspaceRef(), stats.files(), stats.bytes());
    }

    private static WorkspaceStats workspaceStats(ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        int files = 0;
        long bytes = 0;
        try (var stream = Files.walk(workspace.path())) {
            for (Path path : stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path)).toList()) {
                String relative = workspace.path().relativize(path).toString().replace('\\', '/');
                if (relative.equals(".metatron-workspace") || relative.equals(".metatron-repository")
                        || relative.equals(".git") || relative.startsWith(".git/")) continue;
                files++;
                bytes += Files.size(path);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("cannot inspect existing materialized workspace", failure);
        }
        if (files < 1 || bytes < 1) throw new IllegalStateException("existing repository materialization has no source content");
        return new WorkspaceStats(files, bytes);
    }

    private static Map<String, String> keyValueLines(String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (body != null) {
            for (String line : body.lines().toList()) {
                int split = line.indexOf('=');
                if (split > 0) fields.put(line.substring(0, split).trim(), line.substring(split + 1).trim());
            }
        }
        return fields;
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

    private ActionFabric.Action dependenciesInstall(String worker, String auth, String objectiveId,
                                                     ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        // Dependency installation mutates only the isolated Objective workspace/cache; it does not mutate the governed target.
        return action("workspace.dependencies.install", ActionFabric.Consequence.READ_ONLY, worker, auth, request -> {
            String workingDirectory = workingDirectory(workspace, request);
            BuildCommand command = dependencyCommand(workspace, workingDirectory);
            return sandboxObservation(request.actionRef(),
                    sandbox.run(worker, objectiveId, workingDirectory, command.executable(), command.args()));
        });
    }

    private ActionFabric.Action process(String worker, String auth, String objectiveId) {
        return action("workspace.process.run", ActionFabric.Consequence.MUTATING, worker, auth, request -> {
            String executable = input(request, "executable");
            List<String> args = stringList(request.inputs().getOrDefault("argsJson", "[]"));
            String workingDirectory = request.inputs().getOrDefault("workingDirectory", "").trim();
            return sandboxObservation(request.actionRef(),
                    sandbox.run(worker, objectiveId, workingDirectory, executable, args));
        });
    }

    private ActionFabric.Action shell(String worker, String auth, String objectiveId) {
        return action("workspace.shell.run", ActionFabric.Consequence.MUTATING, worker, auth, request -> {
            String command = input(request, "command");
            String workingDirectory = request.inputs().getOrDefault("workingDirectory", "").trim();
            return sandboxObservation(request.actionRef(),
                    sandbox.run(worker, objectiveId, workingDirectory, "sh", List.of("-lc", command)));
        });
    }

    private ActionFabric.Action gitStatus(String worker, String auth, String objectiveId) {
        return action("workspace.git.status", ActionFabric.Consequence.READ_ONLY, worker, auth, request -> {
            WorkerExecutionSandboxService.SandboxResult status = runOrThrow(
                    worker, objectiveId, "git", List.of("status", "--short", "--branch"), "git status");
            WorkerExecutionSandboxService.SandboxResult head = runOrThrow(
                    worker, objectiveId, "git", List.of("rev-parse", "HEAD"), "git rev-parse HEAD");
            WorkerExecutionSandboxService.SandboxResult log = runOrThrow(
                    worker, objectiveId, "git", List.of("log", "-n", "20", "--pretty=format:%H%x09%s"), "git log");
            String headSha = head.output().trim();
            if (!headSha.matches("[0-9a-f]{40}")) throw new IllegalStateException("git HEAD is not an immutable SHA");
            Map<String, String> outputs = new LinkedHashMap<>();
            outputs.put("status", status.output());
            outputs.put("headSha", headSha);
            outputs.put("recentLog", log.output());
            outputs.put("workspaceKey", status.workspaceKey());
            return observation(request.actionRef(), true, "local Git status, HEAD and recent history inspected",
                    outputs, List.of(
                            "worker-sandbox:workspace=" + status.workspaceKey() + ":git-read-only-inspection",
                            "objective-git-head:" + headSha));
        });
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

    private ActionFabric.Action githubProposal(String worker, String auth, String objectiveId) {
        return action("workspace.github.pr.publish", ActionFabric.Consequence.MUTATING, worker, auth, request -> {
            GitHubWorkspaceProposalPublisher.Publication publication = proposals.publish(
                    worker,
                    objectiveId,
                    request.inputs().getOrDefault("title", ""),
                    request.inputs().getOrDefault("body", ""));
            Map<String, String> outputs = new LinkedHashMap<>();
            outputs.put("repository", publication.repository());
            outputs.put("baseBranch", publication.baseBranch());
            outputs.put("sourceCommitSha", publication.sourceCommitSha());
            outputs.put("localHeadSha", publication.localHeadSha());
            outputs.put("branch", publication.branch());
            outputs.put("remoteCommitSha", publication.remoteCommitSha());
            outputs.put("pullRequestNumber", Integer.toString(publication.pullRequestNumber()));
            outputs.put("pullRequestUrl", publication.pullRequestUrl());
            outputs.put("changedPathsJson", write(publication.changedPaths()));

            List<String> evidence = new ArrayList<>();
            evidence.add("github-general-proposal:true");
            evidence.add("github-pr:" + publication.pullRequestUrl());
            evidence.add("github-pr-number:" + publication.pullRequestNumber());
            evidence.add("github-repository:" + publication.repository());
            evidence.add("github-base-branch:" + publication.baseBranch());
            evidence.add("github-source-sha:" + publication.sourceCommitSha());
            evidence.add("github-local-head:" + publication.localHeadSha());
            evidence.add("github-branch:" + publication.branch());
            evidence.add("github-remote-commit:" + publication.remoteCommitSha());
            publication.changedPaths().forEach(path -> evidence.add("github-changed-path:" + path));
            evidence.add("github-merge-performed:false");
            return observation(request.actionRef(), true,
                    "committed Objective workspace published as reviewable unmerged GitHub Pull Request",
                    outputs, evidence);
        });
    }

    private ActionFabric.Action build(String worker, String auth, String objectiveId,
                                      ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        // Build outputs are confined to the Objective scratch workspace. Building verifies the source
        // but does not mutate the governed external target, so READ_ONLY Work must be able to invoke it.
        return action("workspace.build.run", ActionFabric.Consequence.READ_ONLY, worker, auth, request -> {
            String workingDirectory = workingDirectory(workspace, request);
            BuildCommand command = buildCommand(
                    workspace, workingDirectory, request.inputs().getOrDefault("tasksJson", "[]"), false);
            return sandboxObservation(request.actionRef(),
                    sandbox.run(worker, objectiveId, workingDirectory, command.executable(), command.args()));
        });
    }

    private ActionFabric.Action test(String worker, String auth, String objectiveId,
                                     ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        // Test outputs are likewise local verification artifacts rather than mutations of the governed target.
        return action("workspace.test.run", ActionFabric.Consequence.READ_ONLY, worker, auth, request -> {
            String workingDirectory = workingDirectory(workspace, request);
            BuildCommand command = buildCommand(
                    workspace, workingDirectory, request.inputs().getOrDefault("tasksJson", "[]"), true);
            return sandboxObservation(request.actionRef(),
                    sandbox.run(worker, objectiveId, workingDirectory, command.executable(), command.args()));
        });
    }

    private BuildCommand buildCommand(ObjectiveWorkspaceService.ObjectiveWorkspace workspace,
                                      String workingDirectory,
                                      String tasksJson,
                                      boolean test) {
        List<String> tasks = stringList(tasksJson);
        Path root = workingRoot(workspace, workingDirectory);
        if (Files.exists(root.resolve("gradlew"), LinkOption.NOFOLLOW_LINKS)) {
            List<String> args = new ArrayList<>();
            args.add("--no-daemon");
            args.add("--max-workers=1");
            args.addAll(tasks.isEmpty() ? List.of(test ? "test" : "build") : tasks);
            return new BuildCommand("./gradlew", args);
        }
        if (Files.exists(root.resolve("build.gradle"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(root.resolve("build.gradle.kts"), LinkOption.NOFOLLOW_LINKS)) {
            List<String> args = new ArrayList<>();
            args.add("--no-daemon");
            args.add("--max-workers=1");
            args.addAll(tasks.isEmpty() ? List.of(test ? "test" : "build") : tasks);
            return new BuildCommand("gradle", args);
        }
        if (Files.exists(root.resolve("mvnw"), LinkOption.NOFOLLOW_LINKS)) {
            return new BuildCommand("./mvnw", tasks.isEmpty() ? List.of(test ? "test" : "verify") : tasks);
        }
        if (Files.exists(root.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)) {
            return new BuildCommand("mvn", tasks.isEmpty() ? List.of(test ? "test" : "verify") : tasks);
        }
        if (Files.exists(root.resolve("package.json"), LinkOption.NOFOLLOW_LINKS)) {
            String script = tasks.isEmpty() ? (test ? "test" : "build") : singleTask(tasks, "Node package script");
            Set<String> scripts = nodeScripts(workspace, workingDirectory);
            if (!scripts.contains(script)) {
                throw new IllegalStateException("package.json does not define requested script: " + script);
            }
            return new BuildCommand(nodePackageManager(root), List.of("run", script));
        }
        if (isPythonWorkspace(root)) {
            List<String> args = new ArrayList<>();
            if (test) {
                args.add("-m");
                args.add("pytest");
                args.add("-q");
                args.addAll(tasks);
            } else {
                args.add("-m");
                args.add("compileall");
                args.add("-q");
                args.addAll(tasks.isEmpty() ? List.of(".") : tasks);
            }
            return new BuildCommand("python3", args);
        }
        throw new IllegalStateException("workspace build system not detected");
    }

    private BuildCommand dependencyCommand(ObjectiveWorkspaceService.ObjectiveWorkspace workspace,
                                           String workingDirectory) {
        Path root = workingRoot(workspace, workingDirectory);
        if (Files.exists(root.resolve("package.json"), LinkOption.NOFOLLOW_LINKS)) {
            String manager = nodePackageManager(root);
            if ("pnpm".equals(manager)) return new BuildCommand("pnpm", List.of("install", "--frozen-lockfile"));
            if ("yarn".equals(manager)) return new BuildCommand("yarn", List.of("install", "--frozen-lockfile"));
            if (Files.exists(root.resolve("package-lock.json"), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(root.resolve("npm-shrinkwrap.json"), LinkOption.NOFOLLOW_LINKS)) {
                return new BuildCommand("npm", List.of("ci"));
            }
            return new BuildCommand("npm", List.of("install"));
        }
        if (Files.exists(root.resolve("requirements.txt"), LinkOption.NOFOLLOW_LINKS)) {
            return new BuildCommand("python3",
                    List.of("-m", "pip", "install", "--disable-pip-version-check", "-r", "requirements.txt"));
        }
        if (Files.exists(root.resolve("pyproject.toml"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(root.resolve("setup.py"), LinkOption.NOFOLLOW_LINKS)) {
            return new BuildCommand("python3",
                    List.of("-m", "pip", "install", "--disable-pip-version-check", "-e", "."));
        }
        throw new IllegalStateException("workspace dependency system not detected");
    }

    private String workingDirectory(ObjectiveWorkspaceService.ObjectiveWorkspace workspace,
                                    ActionFabric.ActionRequest request) {
        String requested = request.inputs().getOrDefault("workingDirectory", "").trim();
        if (requested.isBlank()) return "";
        Path resolved = workspaces.resolve(workspace, requested);
        if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("workspace working directory not found: " + requested);
        }
        return workspace.path().relativize(resolved).toString().replace('\\', '/');
    }

    private Path workingRoot(ObjectiveWorkspaceService.ObjectiveWorkspace workspace, String workingDirectory) {
        if (workingDirectory == null || workingDirectory.isBlank()) return workspace.path();
        Path root = workspaces.resolve(workspace, workingDirectory);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("workspace working directory not found: " + workingDirectory);
        }
        return root;
    }

    private Set<String> nodeScripts(ObjectiveWorkspaceService.ObjectiveWorkspace workspace,
                                    String workingDirectory) {
        String path = workingDirectory == null || workingDirectory.isBlank()
                ? "package.json" : workingDirectory + "/package.json";
        try {
            JsonNode scripts = json.readTree(workspaces.read(workspace, path)).path("scripts");
            if (!scripts.isObject()) return Set.of();
            Set<String> names = new java.util.LinkedHashSet<>();
            scripts.fieldNames().forEachRemaining(names::add);
            return Set.copyOf(names);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot inspect package.json scripts", failure);
        }
    }

    private static String nodePackageManager(Path root) {
        if (Files.exists(root.resolve("pnpm-lock.yaml"), LinkOption.NOFOLLOW_LINKS)) return "pnpm";
        if (Files.exists(root.resolve("yarn.lock"), LinkOption.NOFOLLOW_LINKS)) return "yarn";
        return "npm";
    }

    private static boolean isPythonWorkspace(Path root) {
        return Files.exists(root.resolve("pyproject.toml"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(root.resolve("requirements.txt"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(root.resolve("setup.py"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(root.resolve("pytest.ini"), LinkOption.NOFOLLOW_LINKS)
                || Files.isDirectory(root.resolve("tests"), LinkOption.NOFOLLOW_LINKS);
    }

    private static String singleTask(List<String> tasks, String label) {
        if (tasks.size() != 1 || tasks.getFirst().isBlank()) {
            throw new IllegalArgumentException(label + " requires exactly one script name");
        }
        return tasks.getFirst();
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
    private record WorkspaceStats(int files, long bytes) {}
}
