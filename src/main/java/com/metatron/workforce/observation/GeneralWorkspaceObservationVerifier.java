package com.metatron.workforce.observation;

import com.metatron.workforce.interaction.intelligence.GeneralActionComposingExecutionPlanProposalService;
import com.metatron.workforce.management.GeneralWorkspaceAutonomousCapability;
import com.metatron.workforce.runtime.ObjectiveWorkspaceService;
import com.metatron.workforce.runtime.WorkerExecutionSandboxService;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
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

    public GeneralWorkspaceObservationVerifier(ObjectiveWorkspaceService workspaces,
                                               WorkerExecutionSandboxService sandbox) {
        this.workspaces = java.util.Objects.requireNonNull(workspaces, "workspaces");
        this.sandbox = java.util.Objects.requireNonNull(sandbox, "sandbox");
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
        if (researchRequirement(requirement)) {
            return Optional.of(observeResearch(requirement, executionEvidenceReferences, at));
        }
        ObjectiveWorkspaceService.ObjectiveWorkspace workspace = workspaces.provision(
                requirement.objectiveId(), GeneralWorkspaceAutonomousCapability.WORKER_ID);
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

        if (testRequired || buildRequired) {
            VerificationCommand command = detectVerification(workspace, testRequired);
            if (command == null) {
                return Optional.of(report(requirement, at,
                        "Build/test acceptance requested but no supported build system exists in workspace",
                        "independent-workspace-build-system-detection", evidence,
                        ObservationReport.Quality.HIGH, ObservationReport.CriterionResult.FAIL));
            }
            WorkerExecutionSandboxService.SandboxResult result = sandbox.run(
                    GeneralWorkspaceAutonomousCapability.WORKER_ID,
                    requirement.objectiveId(), command.executable(), command.args());
            evidence.add("observation-sandbox-verification:workspace=" + result.workspaceKey()
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
        // a valid committed work product, so inspect HEAD^..HEAD before falling back to working-tree state.
        if (Files.exists(workspaces.resolve(workspace, ".git"))) {
            WorkerExecutionSandboxService.SandboxResult head = sandbox.run(
                    GeneralWorkspaceAutonomousCapability.WORKER_ID,
                    requirement.objectiveId(), "git", List.of("rev-parse", "HEAD"));
            if (!head.success() || !head.output().trim().matches("[0-9a-f]{40}")) {
                return Optional.of(report(requirement, at,
                        "Independent Git HEAD inspection failed: " + abbreviate(head.output()),
                        "independent-git-workspace-inspection", evidence,
                        ObservationReport.Quality.MEDIUM, ObservationReport.CriterionResult.INCONCLUSIVE));
            }
            evidence.add("observation-git-head:workspace=" + head.workspaceKey() + ":sha=" + head.output().trim());

            WorkerExecutionSandboxService.SandboxResult commitCount = sandbox.run(
                    GeneralWorkspaceAutonomousCapability.WORKER_ID,
                    requirement.objectiveId(), "git", List.of("rev-list", "--count", "HEAD"));
            int commits = parsePositiveInt(commitCount.output());
            String committedDelta = "";
            if (commitCount.success() && commits >= 2) {
                WorkerExecutionSandboxService.SandboxResult delta = sandbox.run(
                        GeneralWorkspaceAutonomousCapability.WORKER_ID,
                        requirement.objectiveId(), "git", List.of("diff", "--name-only", "HEAD^", "HEAD", "--"));
                if (delta.success()) {
                    committedDelta = delta.output().trim();
                    evidence.add("observation-git-commit-delta:workspace=" + delta.workspaceKey()
                            + ":changed=" + !committedDelta.isBlank()
                            + ":paths=" + abbreviate(committedDelta));
                }
            }

            WorkerExecutionSandboxService.SandboxResult status = sandbox.run(
                    GeneralWorkspaceAutonomousCapability.WORKER_ID,
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

    private VerificationCommand detectVerification(ObjectiveWorkspaceService.ObjectiveWorkspace workspace,
                                                   boolean test) {
        if (Files.isRegularFile(workspaces.resolve(workspace, "gradlew"))) {
            return new VerificationCommand("./gradlew", List.of("--no-daemon", test ? "test" : "build"));
        }
        if (Files.isRegularFile(workspaces.resolve(workspace, "build.gradle"))
                || Files.isRegularFile(workspaces.resolve(workspace, "build.gradle.kts"))) {
            return new VerificationCommand("gradle", List.of(test ? "test" : "build"));
        }
        if (Files.isRegularFile(workspaces.resolve(workspace, "mvnw"))) {
            return new VerificationCommand("./mvnw", List.of(test ? "test" : "verify"));
        }
        if (Files.isRegularFile(workspaces.resolve(workspace, "pom.xml"))) {
            return new VerificationCommand("mvn", List.of(test ? "test" : "verify"));
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

    private record VerificationCommand(String executable, List<String> args) {}
}
