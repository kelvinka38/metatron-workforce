package com.metatron.workforce.observation;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Independently verifies durable evidence emitted by the read-only cognition assurance capability. */
@Component
public final class CognitionRuntimeAssuranceObservationVerifier implements ObservationVerifier {
    private static final String ENDPOINT_PREFIX = "metatron-cognition-endpoint:";
    private static final String MODEL_PREFIX = "metatron-cognition-model:";
    private static final String REQUEST_PREFIX = "worker-intelligence-request:";
    private static final String RESULT_PASS = "cognition-assurance:result=PASS";
    private static final String EXTERNAL_FALSE = "cognition-assurance:external-paid-provider-observed=false";
    private static final String WORKER_EVIDENCE_PREFIX = "worker-cognition-evidence;";

    @Override
    public boolean supports(ObservationRequirement requirement) {
        Objects.requireNonNull(requirement, "requirement");
        List<String> evidence = requirement.evidenceRequirements().stream()
                .map(value -> value == null ? "" : value.toLowerCase(java.util.Locale.ROOT))
                .toList();
        boolean metatronCognitionEvidence = evidence.stream()
                .anyMatch(value -> value.contains("metatron cognition"));
        boolean workerRequestEvidence = evidence.stream()
                .anyMatch(value -> value.contains("worker intelligence request"));
        return metatronCognitionEvidence && workerRequestEvidence;
    }

    @Override
    public Optional<ObservationReport> observe(
            ObservationRequirement requirement,
            List<String> executionEvidenceReferences,
            Instant at) {
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(executionEvidenceReferences, "executionEvidenceReferences");
        Objects.requireNonNull(at, "at");

        String endpoint = value(executionEvidenceReferences, ENDPOINT_PREFIX);
        String model = value(executionEvidenceReferences, MODEL_PREFIX);
        String request = value(executionEvidenceReferences, REQUEST_PREFIX);
        String workerEvidence = executionEvidenceReferences.stream()
                .filter(Objects::nonNull)
                .filter(value -> value.startsWith(WORKER_EVIDENCE_PREFIX))
                .findFirst().orElse("");
        boolean hasAssuranceResult = executionEvidenceReferences.contains(RESULT_PASS);
        boolean noExternalPaid = executionEvidenceReferences.contains(EXTERNAL_FALSE);
        if (endpoint.isBlank() || model.isBlank() || request.isBlank()
                || workerEvidence.isBlank() || !hasAssuranceResult || !noExternalPaid) {
            return Optional.empty();
        }

        String expectedModel = requirement.target().trim();
        boolean endpointOwned = endpoint.startsWith("metatron-cognition-node:");
        boolean modelMatches = expectedModel.isBlank() || expectedModel.equals(model);
        boolean providerOwned = field(workerEvidence, "provider").equals("ollama");
        boolean workerModelMatches = model.equals(field(workerEvidence, "model"));
        boolean objectiveMatches = requirement.objectiveId().equals(field(workerEvidence, "objective_id"));
        boolean assignmentPresent = !field(workerEvidence, "assignment_id").isBlank();
        boolean pass = endpointOwned && modelMatches && providerOwned
                && workerModelMatches && objectiveMatches && assignmentPresent;

        List<String> evidence = new ArrayList<>();
        evidence.add(ENDPOINT_PREFIX + endpoint);
        evidence.add(MODEL_PREFIX + model);
        evidence.add(REQUEST_PREFIX + request);
        evidence.add(workerEvidence);
        evidence.add(RESULT_PASS);
        evidence.add(EXTERNAL_FALSE);

        return Optional.of(new ObservationReport(
                "observation:cognition-assurance:"
                        + Integer.toUnsignedString(Objects.hash(requirement.requirementId(), request), 16),
                requirement.requirementId(),
                requirement.objectiveId(),
                requirement.target(),
                pass
                        ? "Metatron-owned Worker cognition evidence verifies endpoint, model and provider attribution"
                        : "Worker cognition evidence conflicts with the required endpoint/model/provider attribution",
                "durable-worker-cognition-evidence-read",
                at,
                at,
                List.copyOf(evidence),
                pass ? 1.0 : 0.99,
                ObservationReport.Quality.HIGH,
                pass ? "" : "cognition_evidence_mismatch",
                pass ? ObservationReport.CriterionResult.PASS : ObservationReport.CriterionResult.FAIL));
    }

    private static String value(List<String> evidence, String prefix) {
        return evidence.stream().filter(Objects::nonNull).filter(value -> value.startsWith(prefix))
                .map(value -> value.substring(prefix.length()).trim()).filter(value -> !value.isBlank())
                .findFirst().orElse("");
    }

    private static String field(String evidence, String key) {
        String prefix = key + "=";
        for (String part : evidence.split(";")) {
            if (part.startsWith(prefix)) return part.substring(prefix.length()).trim();
        }
        return "";
    }
}
