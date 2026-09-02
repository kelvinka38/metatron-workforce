package com.metatron.workforce.observation;

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
 * It never converts execution evidence into truth. Instead it re-opens the durable Objective workspace
 * and, when acceptance requires build/test, independently re-executes the verification command.
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
                        || value.startsWith("requested-capability:"));
    }

    @Override
    public Optional<ObservationReport> observe(ObservationRequirement requirement,
                                               List<String> executionEvidenceReferences,
                                               Instant at) {
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

        // For code/file/git outcomes, inspect the durable state rather than accepting the execution journal.
        if (Files.exists(workspaces.resolve(workspace, ".git"))) {
            WorkerExecutionSandboxService.SandboxResult diff = sandbox.run(
                    GeneralWorkspaceAutonomousCapability.WORKER_ID,
                    requirement.objectiveId(), "git", List.of("status", "--short"));
            evidence.add("observation-git-status:workspace=" + diff.workspaceKey()
                    + ":exit=" + diff.exitCode()
                    + ":changed=" + !diff.output().isBlank());
            if (!diff.success()) {
                return Optional.of(report(requirement, at,
                        "Independent Git workspace inspection failed: " + abbreviate(diff.output()),
                        "independent-git-workspace-inspection", evidence,
                        ObservationReport.Quality.MEDIUM, ObservationReport.CriterionResult.INCONCLUSIVE));
            }
            boolean mutationExpected = requested.contains("patch") || requested.contains("write")
                    || requested.contains("code") || requested.contains("file")
                    || criterion.contains("change") || criterion.contains("modify") || criterion.contains("write");
            if (mutationExpected && diff.output().isBlank()) {
                return Optional.of(report(requirement, at,
                        "Workspace is a Git repository but no file change is independently observable",
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
