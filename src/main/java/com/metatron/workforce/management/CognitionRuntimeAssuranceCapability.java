package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded proof that institutional Worker cognition is actually served by the Metatron-owned
 * cognition substrate and by the exact model requested by the Objective.
 *
 * <p>This capability is deliberately read-only. A successful exact-model observation proves that
 * conditional reconciliation is unnecessary for the current Objective. A mismatch fails truthfully
 * so Management may replan to a separately governed reconciliation capability rather than inventing
 * a host mutation.</p>
 */
@Component
public final class CognitionRuntimeAssuranceCapability implements AutonomousExecutionCapability {
    public static final String CAPABILITY = "worker.cognition.assure";
    public static final String WORKER_ID = GeneralWorkspaceAutonomousCapability.WORKER_ID;
    public static final String AUTHORITY_REFERENCE = GeneralWorkspaceAutonomousCapability.AUTHORITY_REFERENCE;
    public static final String AUTHORIZATION_REFERENCE = GeneralWorkspaceAutonomousCapability.AUTHORIZATION_REFERENCE;
    private static final String COGNITION_REQUEST_CAPABILITY = "worker.cognition";

    private final WorkerIntelligenceService intelligence;

    public CognitionRuntimeAssuranceCapability(WorkerIntelligenceService intelligence) {
        this.intelligence = Objects.requireNonNull(intelligence, "intelligence");
    }

    @Override public String capabilityRef() { return CAPABILITY; }
    @Override public String capabilityDescription() {
        return CAPABILITY + " — read-only Worker cognition assurance through the Metatron-owned cognition path with exact model attribution";
    }
    @Override public String authorityReference() { return AUTHORITY_REFERENCE; }
    @Override public String authorizationReference() { return AUTHORIZATION_REFERENCE; }
    @Override public double minimumCapabilityLevel() { return 1.0; }
    @Override public double requiredCapacity() { return 1.0; }
    @Override public boolean supportsWorker(String workerId) { return WORKER_ID.equals(workerId); }

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.allocated()) throw new SecurityException("governed allocation required for cognition assurance");
        if (!WORKER_ID.equals(request.allocatedWorkerId())) throw new SecurityException("cognition assurance worker mismatch");
        if (!AUTHORIZATION_REFERENCE.equals(request.authorizationReference())) {
            throw new SecurityException("cognition assurance authorization mismatch");
        }
        if (!CAPABILITY.equals(request.workSpec().requiredCapability())) {
            throw new SecurityException("cognition assurance capability mismatch");
        }
        if (request.workSpec().consequence() != ExecutionWorkSpec.Consequence.READ_ONLY) {
            throw new SecurityException("cognition assurance is read-only");
        }

        String expectedModel = request.workSpec().target() == null ? "" : request.workSpec().target().trim();
        if (expectedModel.isBlank()) throw new IllegalArgumentException("cognition assurance expected model missing");

        List<String> seedEvidence = List.of(
                "cognition-assurance:expected-model=" + expectedModel,
                "cognition-assurance:required-compute-owner=METATRON_OWNED");
        WorkerIntelligenceService.Response response = intelligence.reason(new WorkerIntelligenceService.Request(
                request.allocatedWorkerId(),
                COGNITION_REQUEST_CAPABILITY,
                "Perform one bounded cognition probe. Return a short acknowledgement only. Do not request web research or any external paid provider.",
                "COGNITION RUNTIME ASSURANCE\nexpected_model=" + expectedModel
                        + "\nobjective_id=" + request.objectiveId()
                        + "\nassignment=" + request.assignmentReference()
                        + "\nstep=" + request.workSpec().stepId(),
                seedEvidence,
                request.allocatedWorkerId(),
                request.objectiveId(),
                request.assignmentReference(),
                request.workSpec().stepId(),
                request.executionAttemptId()));

        List<String> evidence = new ArrayList<>(response.evidenceReferences());
        String endpoint = evidenceValue(evidence, "metatron-cognition-endpoint:");
        String observedModel = evidenceValue(evidence, "metatron-cognition-model:");
        boolean externalPaidProviderObserved = evidence.stream()
                .anyMatch(ref -> ref != null && ref.startsWith("worker-intelligence-provider:"));
        boolean endpointObserved = !endpoint.isBlank();
        boolean exactModel = expectedModel.equals(observedModel);
        boolean success = endpointObserved && exactModel && !externalPaidProviderObserved;

        evidence.add("cognition-assurance:observed-endpoint=" + nonBlank(endpoint, "missing"));
        evidence.add("cognition-assurance:observed-model=" + nonBlank(observedModel, "missing"));
        evidence.add("cognition-assurance:external-paid-provider-observed=" + externalPaidProviderObserved);
        evidence.add("cognition-assurance:result=" + (success ? "PASS" : "FAIL"));

        String summary;
        if (success) {
            summary = "COGNITION RUNTIME ASSURANCE PASS endpoint=" + endpoint
                    + " model=" + observedModel + " external_paid_provider=false";
        } else if (!endpointObserved) {
            summary = "COGNITION RUNTIME ASSURANCE BLOCKED reason=METATRON_OWNED_ENDPOINT_EVIDENCE_MISSING";
        } else if (!exactModel) {
            summary = "COGNITION RUNTIME ASSURANCE BLOCKED reason=MODEL_MISMATCH expected="
                    + expectedModel + " observed=" + nonBlank(observedModel, "missing")
                    + " reconciliation_required=true";
        } else {
            summary = "COGNITION RUNTIME ASSURANCE BLOCKED reason=EXTERNAL_PAID_PROVIDER_OBSERVED";
        }

        return new CapabilityResult(
                success,
                request.allocatedWorkerId(),
                request.assignmentReference(),
                "cognition-assurance:" + request.objectiveId() + ":" + request.workSpec().stepId(),
                List.copyOf(evidence),
                summary);
    }

    private static String evidenceValue(List<String> evidence, String prefix) {
        return evidence.stream()
                .filter(Objects::nonNull)
                .filter(ref -> ref.startsWith(prefix))
                .map(ref -> ref.substring(prefix.length()).trim())
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
