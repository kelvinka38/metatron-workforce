package com.metatron.workforce.observation;

import com.metatron.workforce.interaction.intelligence.GeneralActionComposingExecutionPlanProposalService;
import com.metatron.workforce.management.GeneralWorkspaceAutonomousCapability;
import com.metatron.workforce.runtime.ObjectiveWorkspaceService;
import com.metatron.workforce.runtime.WorkerExecutionSandboxService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Independent Observation adapter for general workspace execution.
 * It never converts execution evidence into truth. Instead it re-opens the durable Objective workspace,
 * observes Git/file state and, when acceptance requires build/test, independently re-executes verification.
 */
@Component
public final class GeneralWorkspaceObservationVerifier implements ObservationVerifier {
    private final ObjectiveWorkspaceService workspaces;
    private final WorkerExecutionSandboxService sandbox;
    private final GitHubRepositoryObservationVerifier github;

    @Autowired
    public GeneralWorkspaceObservationVerifier(ObjectiveWorkspaceService workspaces,
                                               WorkerExecutionSandboxService sandbox,
                                               GitHubRepositoryObservationVerifier github) {
        this.workspaces = java.util.Objects.requireNonNull(workspaces, "workspaces");
        this.sandbox = java.util.Objects.requireNonNull(sandbox, "sandbox");
        this.github = java.util.Objects.requireNonNull(github, "github");
    }

    GeneralWorkspaceObservationVerifier(ObjectiveWorkspaceService workspaces,
                                        WorkerExecutionSandboxService sandbox) {
        this.workspaces = java.util.Objects.requireNonNull(workspaces, "workspaces");
        this.sandbox = java.util.Objects.requireNonNull(sandbox, "sandbox");
        this.github = null;
    }

    @Override
    public boolean supports(ObservationRequirement requirement) {
        return requirement.evidenceRequirements().stream().anyMatch(value ->
                value.startsWith("general-action-composition:")
                        || value.startsWith("requested-capability:")
                        || value.equals(GeneralActionComposingExecutionPlanProposalService.GENERAL_RUNTIME_MARKER));
    }

    @Override
    public Optional<ObservationReport> observe(ObservationRequirement requirement,
                                               List<String> executionEvidenceReferences,
                                               Instant at) {
        if (generalRemoteProposalRequirement(requirement, executionEvidenceReferences) && github != null) {
            return github.observe(requirement, executionEvidenceReferences, at);
        }
        if (researchRequirement(requirement)) {
            return Optional.of(observeResearch(requirement, executionEvidenceReferences, at));
        }
        ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces
                .resolveExecuted(requirement.objectiveId(), requirement.stepId(), GeneralWorkspaceAutonomousCapability.WORKER_ID)
                .orElseGet(() -> workspaces.provision(
                        requirement.objectiveId(), GeneralWorkspaceAutonomousCapability.WORKER_ID));
        List<String> paths = workspaces.list(workspace, "").stream()
                .filter(path -> !path.equals(".metatron-workspace"))
                .toList();
        if (paths.isEmpty()) {
            return Optional.of(report(requirement, at,
                    "Objective workspace exists but contains no work product",
                    "independent-workspace-inspection", List.of(
                            "observation-workspace:" + workspace.workspaceRef() + ":nonIdentityEntries=0"),
                    ObservationReport.Quality.HIGH, ObservationReport.CriterionResult.FAIL));
        }

        String criterion = requirement.criterion().toLowerCase(Locale.ROOT);
        String requested = requestedCapability(requirement.evidenceRequirements()).toLowerCase(Locale.ROOT);
        boolean testRequired = criterion.contains("test") || requested.contains("test");
        boolean buildRequired = criterion.contains("build") || criterion.contains("compile")
                || requested.contains("build") || requested.contains("compile");

        List<String> evidence = new ArrayList<>();
        evidence.add("observation-workspace:" + workspace.workspaceRef() + ":entries=" + paths.size());
        List<String> changedPaths = gitChangedPaths(workspace, requirement.objectiveId());
        if (!changedPaths.isEmpty()) {
            evidence.add("observation-changed-paths:" + String.join(",", changedPaths.stream().limit(50).toList()));
        }

        if (testRequired || buildRequired) {
            VerificationCommand command = detectVerification(workspace, testRequired, changedPaths);
            if (command == null) {
                return Optional.of(report(requirement, at,
                        "Build/test acceptance requested but no supported build system exists in workspace",
                        "independent-workspace-build-system-detection", evidence,
                        ObservationReport.Quality.HIGH, ObservationReport.CriterionResult.FAIL));
            }
            WorkerExecutionSandboxService.SandboxResult result = sandbox.run(
                    workspace, GeneralWorkspaceAutonomousCapability.WORKER_ID,
                    requirement.objectiveId(), command.workingDirectory(), command.executable(), command.args());
            evidence.add("observation-sandbox-verification:workspace=" + result.workspaceKey()
                    + ":workingDirectory=" + command.workingDirectory()
                    + ":executable=" + result.executable()
                    + ":exit=" + result.exitCode()
                    + ":timedOut=" + result.timedOut());
            ObservationReport.CriterionResult verdict = result.success()
                    ? ObservationReport.CriterionResult.PASS : ObservationReport.CriterionResult.FAIL;
            return Optional.of(report(requirement, at,
                    result.success()
                            ? "Independent " + (testRequired ? "test" : "build") + " verification succeeded"
                            : "Independent " + (testRequired ? "test" : "build") + " verification failed: "
                                    + abbreviate(result.output()),
                    "independent-sandbox-" + (testRequired ? "test" : "build") + "-rerun",
                    evidence, ObservationReport.Quality.HIGH, verdict));
        }

        // For code/file/git outcomes, inspect durable Git state. A clean working tree can still contain
        // a valid committed work product, so inspect the latest commit's own changed paths before falling
        // back to working-tree state.
        if (Files.exists(workspaces.resolve(workspace, ".git"))) {
            WorkerExecutionSandboxService.SandboxResult head = sandbox.run(
                    workspace, GeneralWorkspaceAutonomousCapability.WORKER_ID,
                    requirement.objectiveId(), "git", List.of("rev-parse", "HEAD"));
            if (!head.success() || !head.output().trim().matches("[0-9a-f]{40}")) {
                return Optional.of(report(requirement, at,
                        "Independent Git HEAD inspection failed: " + abbreviate(head.output()),
                        "independent-git-workspace-inspection", evidence,
                        ObservationReport.Quality.MEDIUM, ObservationReport.CriterionResult.INCONCLUSIVE));
            }
            evidence.add("observation-git-head:workspace=" + head.workspaceKey() + ":sha=" + head.output().trim());

            WorkerExecutionSandboxService.SandboxResult commitCount = sandbox.run(
                    workspace, GeneralWorkspaceAutonomousCapability.WORKER_ID,
                    requirement.objectiveId(), "git", List.of("rev-list", "--count", "HEAD"));
            int commits = parsePositiveInt(commitCount.output());
            String committedDelta = "";
            // Root-cause fix (2026-09-22): a genuinely fresh workspace's very first commit has no HEAD^,
            // so "diff HEAD^ HEAD" always failed/produced nothing here, and independent Observation
            // reported the real, just-created single commit as having no observable change at all --
            // exactly the same class of fresh-new-application gap already fixed elsewhere in this codebase
            // (materialization, the repeated-failure circuit breaker). "diff-tree ... --root HEAD" reports
            // the same changed-path list either way: against the parent for an ordinary commit, and against
            // the empty tree for the root commit, so one command now covers both cases.
            if (commitCount.success() && commits >= 1) {
                WorkerExecutionSandboxService.SandboxResult delta = sandbox.run(
                        workspace, GeneralWorkspaceAutonomousCapability.WORKER_ID,
                        requirement.objectiveId(), "git",
                        List.of("diff-tree", "--no-commit-id", "--name-only", "-r", "--root", "HEAD"));
                if (delta.success()) {
                    committedDelta = delta.output().trim();
                    evidence.add("observation-git-commit-delta:workspace=" + delta.workspaceKey()
                            + ":changed=" + !committedDelta.isBlank()
                            + ":paths=" + abbreviate(committedDelta));
                }
            }

            WorkerExecutionSandboxService.SandboxResult status = sandbox.run(
                    workspace, GeneralWorkspaceAutonomousCapability.WORKER_ID,
                    requirement.objectiveId(), "git", List.of("status", "--short"));
            if (!status.success()) {
                return Optional.of(report(requirement, at,
                        "Independent Git workspace inspection failed: " + abbreviate(status.output()),
                        "independent-git-workspace-inspection", evidence,
                        ObservationReport.Quality.MEDIUM, ObservationReport.CriterionResult.INCONCLUSIVE));
            }
            String workingDelta = status.output().trim();
            evidence.add("observation-git-status:workspace=" + status.workspaceKey()
                    + ":exit=" + status.exitCode()
                    + ":changed=" + !workingDelta.isBlank());

            boolean mutationExpected = requested.contains("patch") || requested.contains("write")
                    || requested.contains("code") || requested.contains("file") || requested.contains("git")
                    || criterion.contains("change") || criterion.contains("modify") || criterion.contains("write")
                    || criterion.contains("commit") || criterion.contains("patch") || criterion.contains("code")
                    || criterion.contains("file");
            if (mutationExpected && committedDelta.isBlank() && workingDelta.isBlank()) {
                return Optional.of(report(requirement, at,
                        "Workspace is a Git repository but no committed or working-tree file change is independently observable",
                        "independent-git-workspace-inspection", evidence,
                        ObservationReport.Quality.HIGH, ObservationReport.CriterionResult.FAIL));
            }
        }

        evidence.add("observation-workspace-paths:" + String.join(",", paths.stream().limit(40).toList()));
        return Optional.of(report(requirement, at,
                "Durable Objective workspace contains independently observable work product",
                "independent-workspace-state-inspection", evidence,
                ObservationReport.Quality.MEDIUM, ObservationReport.CriterionResult.PASS));
    }

    private static boolean generalRemoteProposalRequirement(
            ObservationRequirement requirement,
            List<String> executionEvidenceReferences) {
        boolean published = executionEvidenceReferences != null
                && executionEvidenceReferences.stream().anyMatch("github-general-proposal:true"::equals);
        if (!published) return false;
        String criterion = requirement.criterion().toLowerCase(Locale.ROOT);
        return criterion.contains("pull request")
                || criterion.contains("proposal branch")
                || criterion.contains("remote proposal")
                || criterion.contains("reviewable pr");
    }

    private static boolean researchRequirement(ObservationRequirement requirement) {
        String requested = requestedCapability(requirement.evidenceRequirements()).toLowerCase(Locale.ROOT);
        if (requested.contains("research") || requested.contains("web.search")
                || requested.contains("internet.search")) return true;
        return requirement.evidenceRequirements().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains("research-action:research.web.search"));
    }

    private static ObservationReport observeResearch(ObservationRequirement requirement,
                                                     List<String> executionEvidenceReferences,
                                                     Instant at) {
        List<String> refs = executionEvidenceReferences == null ? List.of() : executionEvidenceReferences;
        List<String> sources = refs.stream()
                .filter(value -> value != null && (value.startsWith("https://") || value.startsWith("http://")))
                .distinct()
                .toList();
        String output = refs.stream()
                .filter(value -> value != null && value.startsWith("general-work-output:"))
                .map(value -> value.substring("general-work-output:".length()).trim())
                .filter(value -> !value.isBlank())
                .findFirst().orElse("");

        List<String> evidence = new ArrayList<>();
        evidence.add("observation-research-source-count:" + sources.size());
        sources.stream().limit(20).forEach(source -> evidence.add("observation-research-source:" + source));
        evidence.add("observation-research-output-present:" + !output.isBlank()
                + ":chars=" + output.length());

        if (sources.isEmpty()) {
            return report(requirement, at,
                    "Research Work produced no externally attributable source reference",
                    "independent-research-evidence-inspection", evidence,
                    ObservationReport.Quality.HIGH, ObservationReport.CriterionResult.FAIL);
        }
        if (output.isBlank()) {
            return report(requirement, at,
                    "Research sources exist but the substantive Work output was not preserved",
                    "independent-research-output-inspection", evidence,
                    ObservationReport.Quality.HIGH, ObservationReport.CriterionResult.FAIL);
        }
        return report(requirement, at,
                "Research Work preserves substantive output with " + sources.size()
                        + " externally attributable source reference(s)",
                "independent-research-evidence-inspection", evidence,
                ObservationReport.Quality.HIGH, ObservationReport.CriterionResult.PASS);
    }

    private List<String> gitChangedPaths(ObjectiveWorkspaceService.ObjectiveWorkspace workspace,
                                         String objectiveId) {
        if (!Files.isDirectory(workspaces.resolve(workspace, ".git"), LinkOption.NOFOLLOW_LINKS)) return List.of();
        WorkerExecutionSandboxService.SandboxResult count = sandbox.run(
                workspace, GeneralWorkspaceAutonomousCapability.WORKER_ID,
                objectiveId, "git", List.of("rev-list", "--count", "HEAD"));
        if (count.success() && parsePositiveInt(count.output()) >= 1) {
            // Same root-cause fix as observe(): --root makes this correct for a fresh workspace's first
            // commit (no HEAD^ to diff against) as well as any later commit.
            WorkerExecutionSandboxService.SandboxResult delta = sandbox.run(
                    workspace, GeneralWorkspaceAutonomousCapability.WORKER_ID,
                    objectiveId, "git",
                    List.of("diff-tree", "--no-commit-id", "--name-only", "-r", "--root", "HEAD"));
            if (delta.success() && !delta.output().isBlank()) {
                return delta.output().lines().map(String::trim)
                        .filter(value -> !value.isBlank()).distinct().limit(50).toList();
            }
        }
        WorkerExecutionSandboxService.SandboxResult status = sandbox.run(
                workspace, GeneralWorkspaceAutonomousCapability.WORKER_ID,
                objectiveId, "git", List.of("status", "--porcelain"));
        if (!status.success() || status.output().isBlank()) return List.of();
        return status.output().lines()
                .map(line -> line.length() > 3 ? line.substring(3).trim() : "")
                .filter(value -> !value.isBlank() && !value.contains(" -> "))
                .distinct().limit(50).toList();
    }

    private VerificationCommand detectVerification(ObjectiveWorkspaceService.ObjectiveWorkspace workspace,
                                                   boolean test,
                                                   List<String> changedPaths) {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        for (String changedPath : changedPaths == null ? List.<String>of() : changedPaths) {
            Path changed;
            try {
                changed = workspaces.resolve(workspace, changedPath);
            } catch (RuntimeException unsafe) {
                continue;
            }
            Path directory = Files.isDirectory(changed, LinkOption.NOFOLLOW_LINKS) ? changed : changed.getParent();
            while (directory != null && directory.startsWith(workspace.path())) {
                candidates.add(directory);
                if (directory.equals(workspace.path())) break;
                directory = directory.getParent();
            }
        }
        candidates.add(workspace.path());
        for (Path candidate : candidates) {
            VerificationCommand command = verificationAt(workspace, candidate, test);
            if (command != null) return command;
        }
        return null;
    }

    private VerificationCommand verificationAt(ObjectiveWorkspaceService.ObjectiveWorkspace workspace,
                                               Path root,
                                               boolean test) {
        String workingDirectory = root.equals(workspace.path())
                ? ""
                : workspace.path().relativize(root).toString().replace('\\', '/');
        if (Files.isRegularFile(root.resolve("gradlew"), LinkOption.NOFOLLOW_LINKS)) {
            return new VerificationCommand(workingDirectory, "./gradlew",
                    List.of("--no-daemon", "--max-workers=1", test ? "test" : "build"));
        }
        if (Files.isRegularFile(root.resolve("build.gradle"), LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(root.resolve("build.gradle.kts"), LinkOption.NOFOLLOW_LINKS)) {
            return new VerificationCommand(workingDirectory, "gradle",
                    List.of("--no-daemon", "--max-workers=1", test ? "test" : "build"));
        }
        if (Files.isRegularFile(root.resolve("mvnw"), LinkOption.NOFOLLOW_LINKS)) {
            return new VerificationCommand(workingDirectory, "./mvnw", List.of(test ? "test" : "verify"));
        }
        if (Files.isRegularFile(root.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)) {
            return new VerificationCommand(workingDirectory, "mvn", List.of(test ? "test" : "verify"));
        }
        if (Files.isRegularFile(root.resolve("package.json"), LinkOption.NOFOLLOW_LINKS)) {
            String manager = Files.isRegularFile(root.resolve("pnpm-lock.yaml"), LinkOption.NOFOLLOW_LINKS)
                    ? "pnpm"
                    : Files.isRegularFile(root.resolve("yarn.lock"), LinkOption.NOFOLLOW_LINKS) ? "yarn" : "npm";
            return new VerificationCommand(workingDirectory, manager, List.of("run", test ? "test" : "build"));
        }
        boolean python = Files.isRegularFile(root.resolve("pyproject.toml"), LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(root.resolve("requirements.txt"), LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(root.resolve("setup.py"), LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(root.resolve("pytest.ini"), LinkOption.NOFOLLOW_LINKS)
                || Files.isDirectory(root.resolve("tests"), LinkOption.NOFOLLOW_LINKS);
        if (python) {
            return test
                    ? new VerificationCommand(workingDirectory, "python3", List.of("-m", "pytest", "-q"))
                    : new VerificationCommand(workingDirectory, "python3", List.of("-m", "compileall", "-q", "."));
        }
        return null;
    }

    private static String requestedCapability(List<String> evidenceRequirements) {
        return evidenceRequirements.stream()
                .filter(value -> value.startsWith("requested-capability:"))
                .map(value -> value.substring("requested-capability:".length()))
                .findFirst().orElse(GeneralWorkspaceAutonomousCapability.CAPABILITY);
    }

    private static int parsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            return Math.max(parsed, 0);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static ObservationReport report(ObservationRequirement requirement,
                                            Instant at,
                                            String state,
                                            String method,
                                            List<String> evidence,
                                            ObservationReport.Quality quality,
                                            ObservationReport.CriterionResult result) {
        return new ObservationReport(
                "report:general-workspace:" + requirement.requirementId() + ":" + at.toEpochMilli(),
                requirement.requirementId(), requirement.objectiveId(), requirement.target(),
                state, method, at, at, evidence, result == ObservationReport.CriterionResult.INCONCLUSIVE ? 0.6 : 0.95,
                quality, "", result);
    }

    private static String abbreviate(String value) {
        String clean = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= 300 ? clean : clean.substring(0, 300);
    }

    private record VerificationCommand(String workingDirectory, String executable, List<String> args) {}
}
