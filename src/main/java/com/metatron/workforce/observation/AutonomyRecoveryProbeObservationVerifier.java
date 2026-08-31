package com.metatron.workforce.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.management.AutonomyRecoveryProbeCapability;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Independent Observation adapter for the bounded P10 recovery probe domain. */
@Component
public final class AutonomyRecoveryProbeObservationVerifier implements ObservationVerifier {
    private static final Duration OBSERVATION_SETTLE_WINDOW = Duration.ofSeconds(4);

    private final ObjectMapper json;
    private final Path root;

    @Autowired
    public AutonomyRecoveryProbeObservationVerifier(ObjectMapper json) {
        this(json, Path.of(System.getenv().getOrDefault(
                "METATRON_RECOVERY_PROBE_DIR", AutonomyRecoveryProbeCapability.DEFAULT_ROOT)));
    }

    AutonomyRecoveryProbeObservationVerifier(ObjectMapper json, Path root) {
        this.json = json;
        this.root = root;
    }

    @Override
    public boolean supports(ObservationRequirement requirement) {
        return requirement.target() != null
                && requirement.target().startsWith(AutonomyRecoveryProbeCapability.TARGET_PREFIX);
    }

    @Override
    public Optional<ObservationReport> observe(ObservationRequirement requirement,
                                               List<String> executionEvidenceReferences,
                                               Instant at) {
        Path marker = AutonomyRecoveryProbeCapability.markerPath(
                root, requirement.objectiveId(), requirement.target());
        if (!Files.isRegularFile(marker)) {
            return Optional.of(inconclusive(requirement, at,
                    "durable recovery marker not yet present",
                    List.of("recovery-probe-observation-marker:missing")));
        }
        try {
            JsonNode node = json.readTree(Files.readString(marker));
            String objectiveId = node.path("objectiveId").asText();
            String target = node.path("target").asText();
            int dispatchAttempt = node.path("dispatchAttempt").asInt(0);
            Instant completedAt = Instant.parse(node.path("completedAt").asText());
            boolean identityMatches = requirement.objectiveId().equals(objectiveId)
                    && requirement.target().equals(target);
            boolean retryBoundarySatisfied = target.contains("/observation-retry/")
                    ? dispatchAttempt >= 1 : dispatchAttempt >= 2;

            List<String> evidence = List.of(
                    "recovery-probe-observation-marker:" + marker.getFileName(),
                    "recovery-probe-observation-objective-match:" + identityMatches,
                    "recovery-probe-observation-dispatch-attempt:" + dispatchAttempt,
                    "recovery-probe-observation-method:fresh-durable-marker-read");

            if (!identityMatches || !retryBoundarySatisfied) {
                return Optional.of(new ObservationReport(
                        "observation:p10-recovery:" + requirement.requirementId() + ":failed",
                        requirement.requirementId(), requirement.objectiveId(), requirement.target(),
                        "recovery probe marker violates expected identity or retry boundary",
                        "fresh-durable-recovery-marker-read", at, completedAt, evidence,
                        0.99, ObservationReport.Quality.HIGH,
                        "identityMatches=" + identityMatches + ",dispatchAttempt=" + dispatchAttempt,
                        ObservationReport.CriterionResult.FAIL));
            }

            if (target.contains("/observation-retry/")
                    && Duration.between(completedAt, at).compareTo(OBSERVATION_SETTLE_WINDOW) < 0) {
                return Optional.of(inconclusive(requirement, at,
                        "recovery marker exists but evidence-settle window is intentionally incomplete",
                        evidence));
            }

            return Optional.of(new ObservationReport(
                    "observation:p10-recovery:" + requirement.requirementId() + ":pass:" + dispatchAttempt,
                    requirement.requirementId(), requirement.objectiveId(), requirement.target(),
                    "bounded recovery probe independently verified after canonical retry/restart boundary",
                    "fresh-durable-recovery-marker-read", at, completedAt, evidence,
                    0.99, ObservationReport.Quality.HIGH, "",
                    ObservationReport.CriterionResult.PASS));
        } catch (Exception failure) {
            return Optional.of(inconclusive(requirement, at,
                    "recovery marker could not yet be authoritatively evaluated: " + failure.getClass().getSimpleName(),
                    List.of("recovery-probe-observation-error:" + failure.getClass().getSimpleName())));
        }
    }

    private static ObservationReport inconclusive(ObservationRequirement requirement, Instant at,
                                                   String state, List<String> evidence) {
        return new ObservationReport(
                "observation:p10-recovery:" + requirement.requirementId() + ":inconclusive:" + at.toEpochMilli(),
                requirement.requirementId(), requirement.objectiveId(), requirement.target(),
                state, "fresh-durable-recovery-marker-read", at, at, evidence,
                0.50, ObservationReport.Quality.INSUFFICIENT, state,
                ObservationReport.CriterionResult.INCONCLUSIVE);
    }
}
