package com.metatron.workforce.action;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.runtime.ObjectiveWorkspaceService;
import com.metatron.workforce.runtime.RepositoryWorkspaceMaterializationService;
import com.metatron.workforce.runtime.RepositoryWorkspaceMaterializationState;
import com.metatron.workforce.runtime.GitHubWorkspaceProposalPublisher;
import com.metatron.workforce.runtime.GeneratedWorkspaceArtifactPolicy;
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
        add(profile, actions, fileSearch(workerId, authorizationReference, workspace));
        add(profile, actions, filePatch(workerId, authorizationReference, workspace));
        add(profile, actions, fileWrite(workerId, authorizationReference, workspace));
        add(profile, actions, projectPrepare(workerId, authorizationReference, workspace));
        add(profile, actions, dependenciesInstall(workerId, authorizationReference, objectiveId, workspace));
        add(profile, actions, process(workerId, authorizationReference, objectiveId, workspace));
        add(profile, actions, shell(workerId, authorizationReference, objectiveId, workspace));
        add(profile, actions, gitStatus(workerId, authorizationReference, objectiveId));
        add(profile, actions, gitDiff(workerId, authorizationReference, objectiveId));
        add(profile, actions, gitRun(workerId, authorizationReference, objectiveId, workspace));
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
            boolean createIfMissing = "true".equalsIgnoreCase(request.inputs().getOrDefault("createIfMissing", "false"));
            RepositoryWorkspaceMaterializationService.MaterializedRepository materialized =
                    existingMaterialization(workspaces, workspace, repository, ref);
            boolean reused = materialized != null;
            if (!reused) {
                materialized = repositories.materialize(worker, objectiveId, repository, ref, createIfMissing);
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

    private ActionFabric.Action fileSearch(String worker, String auth, ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        return action("workspace.file.search", ActionFabric.Consequence.READ_ONLY, worker, auth, request -> {
            String query = input(request, "query");
            String path = request.inputs().getOrDefault("path", "").trim();
            int maxMatches;
            try {
                maxMatches = Integer.parseInt(request.inputs().getOrDefault("maxMatches", "100"));
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("maxMatches must be an integer", invalid);
            }
            if (maxMatches < 1 || maxMatches > 500) throw new IllegalArgumentException("maxMatches must be between 1 and 500");
            Path start = path.isBlank() ? workspace.path() : workspaces.resolve(workspace, path);
            if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("workspace search path is not a directory: " + path);
            }
            List<Map<String, Object>> matches = new ArrayList<>();
            try (var stream = Files.walk(start, 12)) {
                for (Path candidate : stream
                        .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                        .filter(p -> !Files.isSymbolicLink(p))
                        .toList()) {
                    if (matches.size() >= maxMatches) break;
                    String relative = workspace.path().relativize(candidate).toString().replace('\\', '/');
                    if (relative.equals(".git") || relative.startsWith(".git/")
                            || GeneratedWorkspaceArtifactPolicy.isGeneratedUntrackedPath(relative)) continue;
                    if (Files.size(candidate) > 1_000_000L) continue;
                    List<String> lines;
                    try {
                        lines = Files.readAllLines(candidate);
                    } catch (java.nio.charset.MalformedInputException binary) {
                        continue;
                    }
                    for (int i = 0; i < lines.size() && matches.size() < maxMatches; i++) {
                        String line = lines.get(i);
                        if (!line.contains(query)) continue;
                        String excerpt = line.strip();
                        if (excerpt.length() > 500) excerpt = excerpt.substring(0, 500);
                        matches.add(Map.of("path", relative, "line", i + 1, "excerpt", excerpt));
                    }
                }
            } catch (IOException failure) {
                throw new IllegalStateException("workspace search failed", failure);
            }
            return observation(request.actionRef(), true, "workspace text search completed",
                    Map.of("query", query, "matchCount", Integer.toString(matches.size()), "matchesJson", write(matches)),
                    workspaceEvidence(workspace, request.actionRef()));
        });
    }

    private ActionFabric.Action filePatch(String worker, String auth, ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        return action("workspace.file.patch", ActionFabric.Consequence.MUTATING, worker, auth, request -> {
            String path = input(request, "path");
            String oldText = input(request, "oldText");
            String newText = request.inputs().getOrDefault("newText", "");
            int expected;
            try {
                expected = Integer.parseInt(request.inputs().getOrDefault("expectedOccurrences", "1"));
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("expectedOccurrences must be an integer", invalid);
            }
            if (expected < 1 || expected > 100) {
                throw new IllegalArgumentException("expectedOccurrences must be between 1 and 100");
            }
            String content = workspaces.read(workspace, path);
            int actual = 0;
            for (int offset = 0; (offset = content.indexOf(oldText, offset)) >= 0; offset += oldText.length()) actual++;
            if (actual != expected) {
                throw new IllegalStateException("workspace patch expected " + expected + " occurrences but found " + actual);
            }
            String updated = content.replace(oldText, newText);
            workspaces.write(workspace, path, updated);
            return observation(request.actionRef(), true, "workspace file patch applied",
                    Map.of("path", path, "occurrences", Integer.toString(actual),
                            "bytes", Integer.toString(updated.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)),
                    workspaceEvidence(workspace, request.actionRef()));
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

    private ActionFabric.Action projectPrepare(String worker, String auth,
                                                     ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        return action("workspace.project.prepare", ActionFabric.Consequence.MUTATING, worker, auth, request -> {
            ProjectScaffold prepared = prepareProjectScaffold(workspace);
            Map<String, String> outputs = new LinkedHashMap<>();
            outputs.put("projectKind", prepared.projectKind());
            outputs.put("manifestPath", prepared.manifestPath());
            outputs.put("writtenPathsJson", write(prepared.writtenPaths()));
            outputs.put("reused", Boolean.toString(prepared.reused()));
            List<String> evidence = new ArrayList<>(workspaceEvidence(workspace, request.actionRef()));
            evidence.add("workspace-project-kind:" + prepared.projectKind());
            evidence.add("workspace-project-manifest:" + prepared.manifestPath());
            prepared.writtenPaths().forEach(path -> evidence.add("workspace-project-prepared-path:" + path));
            return observation(request.actionRef(), true,
                    prepared.reused()
                            ? "existing supported project scaffold reused"
                            : "minimal supported project scaffold prepared",
                    outputs, evidence);
        });
    }

    private ProjectScaffold prepareProjectScaffold(ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        String existing = existingProjectManifest(workspace.path());
        if (!existing.isBlank()) {
            return new ProjectScaffold(projectKindForManifest(workspace, existing), existing, List.of(), true);
        }

        SourceFile source = primarySourceFile(workspace);
        if (source == null) throw new IllegalStateException("project preparation requires a supported source file");
        String lowerPath = source.path().toLowerCase(java.util.Locale.ROOT);
        String lowerContent = source.content().toLowerCase(java.util.Locale.ROOT);
        boolean javascript = lowerPath.endsWith(".js") || lowerPath.endsWith(".jsx")
                || lowerPath.endsWith(".ts") || lowerPath.endsWith(".tsx");
        boolean react = javascript && (lowerContent.contains("from 'react'")
                || lowerContent.contains("from \"react\"")
                || lowerContent.contains("require('react')")
                || lowerContent.contains("require(\"react\")")
                || lowerPath.endsWith(".jsx") || lowerPath.endsWith(".tsx"));

        if (react) return prepareReactScaffold(workspace, source);
        if (javascript) return prepareNodeScaffold(workspace, source);
        if (lowerPath.endsWith(".py")) return preparePythonScaffold(workspace, source);
        throw new IllegalStateException("unsupported project scaffold for source: " + source.path());
    }

    private ProjectScaffold prepareReactScaffold(ObjectiveWorkspaceService.ObjectiveWorkspace workspace,
                                                   SourceFile source) {
        List<String> written = new ArrayList<>();
        writeIfMissing(workspace, ".gitignore", GeneratedWorkspaceArtifactPolicy.scaffoldGitignore(), written);
        String componentPath = source.path();
        String lower = componentPath.toLowerCase(java.util.Locale.ROOT);
        boolean jsxSyntax = source.content().contains("<") && source.content().contains(">");
        if (jsxSyntax && (lower.endsWith(".js") || lower.endsWith(".ts"))) {
            componentPath = componentPath.substring(0, componentPath.lastIndexOf('.'))
                    + (lower.endsWith(".ts") ? ".tsx" : ".jsx");
            writeIfMissing(workspace, componentPath, source.content(), written);
        }

        Path component = Path.of(componentPath);
        String importPath = Path.of("src").relativize(component).toString().replace('\\', '/');
        if (!importPath.startsWith(".")) importPath = "./" + importPath;
        writeIfMissing(workspace, "src/main.jsx",
                "import React from 'react';\n"
                        + "import { createRoot } from 'react-dom/client';\n"
                        + "import App from '" + importPath + "';\n\n"
                        + "createRoot(document.getElementById('root')).render(\n"
                        + "  <React.StrictMode><App /></React.StrictMode>\n"
                        + ");\n", written);
        writeIfMissing(workspace, "index.html",
                "<!doctype html>\n<html><head><meta charset=\"UTF-8\"/>"
                        + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\"/>"
                        + "<title>Metatron Application</title></head>"
                        + "<body><div id=\"root\"></div><script type=\"module\" src=\"/src/main.jsx\"></script></body></html>\n",
                written);
        String escapedComponent = componentPath.replace("\\", "\\\\").replace("'", "\\'");
        writeIfMissing(workspace, "test/scaffold.test.js",
                "import test from 'node:test';\n"
                        + "import assert from 'node:assert/strict';\n"
                        + "import { existsSync, readFileSync } from 'node:fs';\n\n"
                        + "test('generated web application has a runnable scaffold', () => {\n"
                        + "  assert.ok(existsSync('index.html'));\n"
                        + "  assert.ok(existsSync('src/main.jsx'));\n"
                        + "  assert.ok(existsSync('" + escapedComponent + "'));\n"
                        + "  assert.ok(readFileSync('" + escapedComponent + "', 'utf8').trim().length > 0);\n"
                        + "});\n", written);
        writeIfMissing(workspace, "package.json",
                "{\n"
                        + "  \"name\": \"metatron-generated-web-app\",\n"
                        + "  \"version\": \"1.0.0\",\n"
                        + "  \"private\": true,\n"
                        + "  \"type\": \"module\",\n"
                        + "  \"scripts\": {\n"
                        + "    \"build\": \"vite build\",\n"
                        + "    \"test\": \"node --test test/*.test.js\",\n"
                        + "    \"start\": \"vite --host 0.0.0.0 --port 3000\"\n"
                        + "  },\n"
                        + "  \"dependencies\": {\n"
                        + "    \"react\": \"^18.3.1\",\n"
                        + "    \"react-dom\": \"^18.3.1\"\n"
                        + "  },\n"
                        + "  \"devDependencies\": { \"vite\": \"^5.4.0\" }\n"
                        + "}\n", written);
        return new ProjectScaffold("node-react", "package.json", List.copyOf(written), false);
    }

    private ProjectScaffold prepareNodeScaffold(ObjectiveWorkspaceService.ObjectiveWorkspace workspace,
                                                  SourceFile source) {
        List<String> written = new ArrayList<>();
        writeIfMissing(workspace, ".gitignore", GeneratedWorkspaceArtifactPolicy.scaffoldGitignore(), written);
        String escaped = source.path().replace("\\", "\\\\").replace("\"", "\\\"");
        String escapedSingle = source.path().replace("\\", "\\\\").replace("'", "\\'");
        writeIfMissing(workspace, "test/scaffold.test.js",
                "import test from 'node:test';\n"
                        + "import assert from 'node:assert/strict';\n"
                        + "import { existsSync } from 'node:fs';\n"
                        + "test('generated source exists', () => assert.ok(existsSync('"
                        + escapedSingle + "')));\n", written);
        writeIfMissing(workspace, "package.json",
                "{\n"
                        + "  \"name\": \"metatron-generated-node-app\",\n"
                        + "  \"version\": \"1.0.0\",\n"
                        + "  \"private\": true,\n"
                        + "  \"type\": \"module\",\n"
                        + "  \"scripts\": {\n"
                        + "    \"build\": \"node --check " + escaped + "\",\n"
                        + "    \"test\": \"node --test test/*.test.js\",\n"
                        + "    \"start\": \"node " + escaped + "\"\n"
                        + "  }\n"
                        + "}\n", written);
        return new ProjectScaffold("node", "package.json", List.copyOf(written), false);
    }

    private ProjectScaffold preparePythonScaffold(ObjectiveWorkspaceService.ObjectiveWorkspace workspace,
                                                    SourceFile source) {
        List<String> written = new ArrayList<>();
        writeIfMissing(workspace, ".gitignore", GeneratedWorkspaceArtifactPolicy.scaffoldGitignore(), written);
        String escaped = source.path().replace("\\", "\\\\").replace("'", "\\'");
        writeIfMissing(workspace, "tests/test_scaffold.py",
                "import py_compile\n\n"
                        + "def test_generated_source_compiles():\n"
                        + "    py_compile.compile('" + escaped + "', doraise=True)\n", written);
        writeIfMissing(workspace, "requirements.txt", "pytest>=8,<9\n", written);
        return new ProjectScaffold("python", "requirements.txt", List.copyOf(written), false);
    }

    private void writeIfMissing(ObjectiveWorkspaceService.ObjectiveWorkspace workspace,
                                String path,
                                String content,
                                List<String> written) {
        Path target = workspaces.resolve(workspace, path);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
                throw new IllegalStateException("project scaffold path is not a regular file: " + path);
            }
            return;
        }
        workspaces.write(workspace, path, content);
        written.add(path);
    }

    private SourceFile primarySourceFile(ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        try (var stream = Files.walk(workspace.path(), 5)) {
            List<Path> candidates = stream
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> {
                        String rel = workspace.path().relativize(path).toString().replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
                        return (rel.endsWith(".js") || rel.endsWith(".jsx") || rel.endsWith(".ts")
                                || rel.endsWith(".tsx") || rel.endsWith(".py"))
                                && !rel.startsWith("test/") && !rel.startsWith("tests/")
                                && !GeneratedWorkspaceArtifactPolicy.isGeneratedUntrackedPath(rel);
                    })
                    .sorted()
                    .toList();
            for (Path path : candidates) {
                String relative = workspace.path().relativize(path).toString().replace('\\', '/');
                return new SourceFile(relative, workspaces.read(workspace, relative));
            }
            return null;
        } catch (IOException e) {
            throw new IllegalStateException("cannot inspect Objective workspace source", e);
        }
    }

    private static String existingProjectManifest(Path root) {
        for (String path : List.of("package.json", "requirements.txt", "pyproject.toml", "pom.xml", "build.gradle", "build.gradle.kts")) {
            Path candidate = root.resolve(path).normalize();
            if (candidate.startsWith(root) && Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(candidate)) return path;
        }
        return "";
    }

    private String projectKindForManifest(ObjectiveWorkspaceService.ObjectiveWorkspace workspace, String manifest) {
        if (manifest.equals("package.json")) {
            String body = workspaces.read(workspace, manifest).toLowerCase(java.util.Locale.ROOT);
            return body.contains("react") || body.contains("vite") ? "node-react" : "node";
        }
        if (manifest.equals("requirements.txt") || manifest.equals("pyproject.toml")) return "python";
        if (manifest.equals("pom.xml")) return "maven";
        return "gradle";
    }

    private record SourceFile(String path, String content) {}
    private record ProjectScaffold(String projectKind, String manifestPath, List<String> writtenPaths, boolean reused) {}

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

    private ActionFabric.Action process(String worker, String auth, String objectiveId,
                                        ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        return action("workspace.process.run", ActionFabric.Consequence.MUTATING, worker, auth, request -> {
            String executable = input(request, "executable");
            List<String> args = stringList(request.inputs().getOrDefault("argsJson", "[]"));
            String workingDirectory = workingDirectory(workspace, request);
            return sandboxObservation(request.actionRef(),
                    sandbox.run(worker, objectiveId, workingDirectory, executable, args));
        });
    }

    private ActionFabric.Action shell(String worker, String auth, String objectiveId,
                                      ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        return action("workspace.shell.run", ActionFabric.Consequence.MUTATING, worker, auth, request -> {
            String command = input(request, "command");
            String workingDirectory = workingDirectory(workspace, request);
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

    private ActionFabric.Action gitRun(String worker, String auth, String objectiveId,
                                       ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        return action("workspace.git.run", ActionFabric.Consequence.MUTATING, worker, auth, request -> {
            List<String> args = stringList(input(request, "argsJson"));
            if (args.isEmpty()) throw new IllegalArgumentException("git args required");
            WorkerExecutionSandboxService.SandboxResult result = isBroadGitAdd(args)
                    ? stageGovernedSourceDelta(worker, objectiveId, workspace)
                    : sandbox.run(worker, objectiveId, "git", args);
            if (result.success() && !args.isEmpty() && "init".equals(args.get(0))) {
                // Deterministic infrastructure, not a cognitive decision: a freshly initialized repository
                // has no author identity, so the very next `git commit` fails identically every time until
                // someone configures it. A fresh isolated Objective workspace (no prior materialization
                // baseline) never gets this identity from anywhere else, so it must be established here,
                // in the same action call, at zero extra cognitive-cycle cost.
                configureGitIdentity(worker, objectiveId);
            }
            return sandboxObservation(request.actionRef(), result);
        });
    }

    /**
     * A broad governed source commit must not equate every physical workspace path with deliverable
     * source. Update every tracked path first (Git tracking remains authoritative even for a path named
     * dist/build/target), then add only non-generated untracked paths. The local exclude block keeps the
     * verified workspace clean even for an existing project that has no .gitignore; generated scaffolds
     * additionally receive a source-controlled .gitignore for normal project hygiene.
     */
    private WorkerExecutionSandboxService.SandboxResult stageGovernedSourceDelta(
            String worker,
            String objectiveId,
            ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        installGeneratedArtifactExcludes(worker, objectiveId, workspace);
        WorkerExecutionSandboxService.SandboxResult result = runOrThrow(
                worker, objectiveId, "git", List.of("add", "-u", "--", "."), "git tracked-source add");
        String untracked = runOrThrow(
                worker, objectiveId, "git",
                List.of("ls-files", "--others", "--exclude-standard", "-z"),
                "git untracked-source discovery").output();

        List<String> sourcePaths = new ArrayList<>();
        for (String path : untracked.split("\\u0000", -1)) {
            if (path.isBlank() || path.equals(".metatron-workspace") || path.equals(".metatron-repository")
                    || path.equals(".git") || path.startsWith(".git/")
                    || GeneratedWorkspaceArtifactPolicy.isGeneratedUntrackedPath(path)) continue;
            sourcePaths.add(path);
        }
        for (int offset = 0; offset < sourcePaths.size(); offset += 100) {
            List<String> args = new ArrayList<>(List.of("add", "--"));
            args.addAll(sourcePaths.subList(offset, Math.min(sourcePaths.size(), offset + 100)));
            result = runOrThrow(worker, objectiveId, "git", args, "git new-source add");
        }
        return result;
    }

    private void installGeneratedArtifactExcludes(
            String worker,
            String objectiveId,
            ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        String gitPath = runOrThrow(
                worker, objectiveId, "git", List.of("rev-parse", "--git-path", "info/exclude"),
                "git exclude-path discovery").output().trim();
        if (gitPath.isBlank()) throw new IllegalStateException("git exclude path is blank");
        Path exclude = Path.of(gitPath);
        if (!exclude.isAbsolute()) exclude = workspace.path().resolve(exclude);
        exclude = exclude.normalize();
        Path root = workspace.path().toAbsolutePath().normalize();
        if (!exclude.toAbsolutePath().normalize().startsWith(root)) {
            throw new SecurityException("git exclude path escaped Objective workspace");
        }
        try {
            Files.createDirectories(exclude.getParent());
            String existing = Files.exists(exclude, LinkOption.NOFOLLOW_LINKS)
                    ? Files.readString(exclude) : "";
            String block = GeneratedWorkspaceArtifactPolicy.localGitExcludeBlock();
            if (!existing.contains(GeneratedWorkspaceArtifactPolicy.EXCLUDE_MARKER)) {
                String separator = existing.isEmpty() || existing.endsWith("\n") ? "" : "\n";
                Files.writeString(exclude, separator + block,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("cannot install generated-artifact Git excludes", failure);
        }
    }

    private static boolean isBroadGitAdd(List<String> args) {
        if (args.isEmpty() || !"add".equals(args.get(0))) return false;
        if (args.stream().anyMatch(arg -> "-A".equals(arg) || "--all".equals(arg))) return true;
        List<String> operands = args.stream().skip(1).filter(arg -> !"--".equals(arg)).toList();
        return operands.equals(List.of(".")) || operands.equals(List.of(":/"));
    }

    private void configureGitIdentity(String worker, String objectiveId) {
        WorkerExecutionSandboxService.SandboxResult name =
                sandbox.run(worker, objectiveId, "git", List.of("config", "user.name", "Metatron Workforce"));
        if (!name.success()) {
            throw new IllegalStateException("failed to configure git user.name after init: " + name.output());
        }
        WorkerExecutionSandboxService.SandboxResult email =
                sandbox.run(worker, objectiveId, "git", List.of("config", "user.email", "workforce@metatron.local"));
        if (!email.success()) {
            throw new IllegalStateException("failed to configure git user.email after init: " + email.output());
        }
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
        throw new IllegalStateException("workspace build system not detected"
                + diagnosticSuffix(workspace, workingDirectory));
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
        throw new IllegalStateException("workspace dependency system not detected"
                + diagnosticSuffix(workspace, workingDirectory));
    }

    /**
     * Diagnostic-only detail (2026-09-23): a real production Objective (case-6419a010) hit this exact
     * "not detected" failure on a resumed VERIFY dispatch even though the preceding PREPARE step had
     * already written a manifest for the same objective+worker -- and every hypothesis tested so far
     * (ephemeral storage, workspace-init volume handling, execution-attempt persistence, attempt-scoped
     * carry-forward across a real actor-lane thread hop) was individually disproven with a live
     * reproduction. This appends the resolved working directory and a bounded listing of what the
     * workspace actually contains to the exception message, which the repeated-failure circuit breaker
     * (CognitiveWorkerRuntime.repeatsFailingWithoutStateChange) already surfaces verbatim as "Last
     * failure detail" into the Human-visible BLOCKER text -- so the next real occurrence carries the
     * exact evidence needed to find the true root cause, instead of requiring another round of
     * container-log archaeology.
     */
    private String diagnosticSuffix(ObjectiveWorkspaceService.ObjectiveWorkspace workspace, String workingDirectory) {
        String resolvedDirectory = workingDirectory == null || workingDirectory.isBlank() ? "<workspace-root>" : workingDirectory;
        String entries;
        try {
            List<String> listed = workspaces.list(workspace, "");
            entries = listed.isEmpty() ? "<empty>" : String.join(";", listed.subList(0, Math.min(listed.size(), 20)));
        } catch (RuntimeException unavailable) {
            entries = "<listing-unavailable:" + unavailable.getClass().getSimpleName() + ">";
        }
        return ":workingDirectory=" + resolvedDirectory + ":workspaceEntries=" + entries;
    }

    private static final List<String> PROJECT_MANIFEST_FILENAMES = List.of(
            "package.json", "requirements.txt", "pyproject.toml", "setup.py",
            "pom.xml", "build.gradle", "build.gradle.kts", "gradlew", "mvnw");

    /**
     * The single canonical deterministic project-root resolver for every dependency/build/test/runtime
     * action. Root-cause fix (production incidents, 2026-09-22, cases 5fd21db7 and 757e8972): cognition is
     * not authoritative for filesystem/project-root identity, so a cognition-supplied "workingDirectory"
     * input is honored only when it is relative, resolves inside the workspace, and the directory it names
     * actually contains a real project manifest -- otherwise (blank, absolute, a path that doesn't exist or
     * escapes the workspace, or a directory with no manifest at all) it always falls back to
     * {@link #detectProjectDirectory}. Earlier (#503) only the absolute/invalid case fell back; a blank
     * input still returned the workspace root unconditionally, so a blank cognition response for a nested
     * real project (e.g. web/package.json) still reached dependencyCommand()/buildCommand() with the wrong
     * root and failed with "workspace dependency system not detected". This is now one resolver reused by
     * dependencies.install, build.run, test.run and process.run/shell.run (the mechanism used for runtime
     * verification), so all four phases of VERIFY agree on the same project directory. The absolute-path,
     * traversal and symlink security checks in ObjectiveWorkspaceService.resolve() are unchanged.
     */
    private String workingDirectory(ObjectiveWorkspaceService.ObjectiveWorkspace workspace,
                                    ActionFabric.ActionRequest request) {
        String requested = request.inputs().getOrDefault("workingDirectory", "").trim();
        if (!requested.isBlank() && !Path.of(requested).isAbsolute()) {
            try {
                Path resolved = workspaces.resolve(workspace, requested);
                if (Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)
                        && !existingProjectManifest(resolved).isEmpty()) {
                    return workspace.path().relativize(resolved).toString().replace('\\', '/');
                }
            } catch (RuntimeException invalid) {
                // falls through to canonical detection below
            }
        }
        return detectProjectDirectory(workspace);
    }

    private String detectProjectDirectory(ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        if (!existingProjectManifest(workspace.path()).isEmpty()) return "";
        String shallowest = null;
        for (String path : workspaces.list(workspace, "")) {
            String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
            if (!PROJECT_MANIFEST_FILENAMES.contains(name)) continue;
            if (shallowest == null || pathDepth(path) < pathDepth(shallowest)) shallowest = path;
        }
        if (shallowest == null) return "";
        int lastSlash = shallowest.lastIndexOf('/');
        return lastSlash < 0 ? "" : shallowest.substring(0, lastSlash);
    }

    private static int pathDepth(String path) {
        return (int) path.chars().filter(c -> c == '/').count();
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
