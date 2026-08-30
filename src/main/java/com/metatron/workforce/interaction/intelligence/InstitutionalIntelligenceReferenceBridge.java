package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.phase9.BoundaryResult;
import com.metatron.workforce.work.InstitutionalWork;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Links externally owned institutional results back into an Intelligence Case by reference only.
 *
 * This bridge does not execute work, observe reality, admit Knowledge, or create authority.
 * It accepts already-produced canonical boundary/work records and preserves their references.
 */
public final class InstitutionalIntelligenceReferenceBridge {
    public static final String EXECUTION_CONTRACT = "INT-WORKFORCE-EXECUTION";
    public static final String OBSERVATION_CONTRACT = "INT-WORKFORCE-OBSERVATION";
    public static final String KNOWLEDGE_CONTRACT = "INT-WORKFORCE-KNOWLEDGE";
    public static final String AUTHORIZATION_CONTRACT = "INT-WORKFORCE-AUTHORIZATION";
    public static final String GATEWAY_CONTRACT = "INT-WORKFORCE-GATEWAY";

    public IntelligenceCase linkAuthorization(IntelligenceCase intelligenceCase,
                                              BoundaryResult boundary,
                                              String authorizationReference) {
        requireSuccessful(boundary, AUTHORIZATION_CONTRACT);
        return intelligenceCase.withExternalReferences(
                List.of(requireNonBlank(authorizationReference, "authorizationReference")),
                List.of(),
                IntelligenceCaseStatus.WAITING_ON_EXTERNAL_STATE);
    }

    public IntelligenceCase linkGateway(IntelligenceCase intelligenceCase,
                                        BoundaryResult boundary,
                                        String gatewayReference,
                                        List<String> evidenceReferences) {
        requireSuccessful(boundary, GATEWAY_CONTRACT);
        return intelligenceCase.withExternalReferences(
                List.of(requireNonBlank(gatewayReference, "gatewayReference")),
                evidenceReferences,
                IntelligenceCaseStatus.REASSESSMENT);
    }

    public IntelligenceCase linkExecution(IntelligenceCase intelligenceCase,
                                          BoundaryResult boundary,
                                          String executionReference,
                                          List<String> executionEvidenceReferences) {
        requireSuccessful(boundary, EXECUTION_CONTRACT);
        return intelligenceCase.withExternalReferences(
                List.of(requireNonBlank(executionReference, "executionReference")),
                executionEvidenceReferences,
                IntelligenceCaseStatus.WAITING_ON_EXTERNAL_STATE);
    }

    public IntelligenceCase linkObservation(IntelligenceCase intelligenceCase,
                                            BoundaryResult boundary,
                                            String observationReference,
                                            List<String> observationEvidenceReferences) {
        requireSuccessful(boundary, OBSERVATION_CONTRACT);
        return intelligenceCase.withExternalReferences(
                List.of(requireNonBlank(observationReference, "observationReference")),
                observationEvidenceReferences,
                IntelligenceCaseStatus.REASSESSMENT);
    }

    public IntelligenceCase linkKnowledgeAdmission(IntelligenceCase intelligenceCase,
                                                   BoundaryResult boundary,
                                                   String knowledgeReference) {
        requireSuccessful(boundary, KNOWLEDGE_CONTRACT);
        return intelligenceCase.withExternalReferences(
                List.of(requireNonBlank(knowledgeReference, "knowledgeReference")),
                List.of(),
                IntelligenceCaseStatus.REASSESSMENT);
    }

    /**
     * Links a completed Workforce work record back to the originating Case. Outcome/evidence remain
     * owned by the work/observation domains; the Case stores only their canonical references.
     */
    public IntelligenceCase linkCompletedWork(IntelligenceCase intelligenceCase, InstitutionalWork work) {
        Objects.requireNonNull(intelligenceCase, "intelligenceCase");
        Objects.requireNonNull(work, "work");
        if (work.status() != InstitutionalWork.Status.COMPLETED) {
            throw new IllegalStateException("only completed institutional work can contribute an outcome reference");
        }
        if (work.outcomeRef() == null || work.outcomeRef().isBlank()) {
            throw new IllegalStateException("completed institutional work requires outcomeRef");
        }
        List<String> institutionalRefs = new ArrayList<>();
        institutionalRefs.add("work:" + work.workId());
        if (work.assignmentRef() != null && !work.assignmentRef().isBlank()) institutionalRefs.add(work.assignmentRef());
        institutionalRefs.add(work.outcomeRef());
        return intelligenceCase.withExternalReferences(
                institutionalRefs,
                work.evidenceRefs(),
                IntelligenceCaseStatus.REASSESSMENT);
    }

    private static void requireSuccessful(BoundaryResult boundary, String expectedContract) {
        Objects.requireNonNull(boundary, "boundary");
        if (!expectedContract.equals(boundary.contractId())) {
            throw new IllegalArgumentException("boundary contract mismatch: expected " + expectedContract
                    + " but was " + boundary.contractId());
        }
        if (!boundary.succeeded()) {
            throw new IllegalStateException("institutional boundary did not succeed: " + boundary.status());
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
