package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.phase3.ExecutionHandoffRequest;
import com.metatron.workforce.phase8.LearningService;
import com.metatron.workforce.phase8.WorkforcePracticeCandidate;
import com.metatron.workforce.phase9.BoundaryResult;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Persistent correlation service for externally owned institutional events that belong to an
 * Intelligence Case. It owns only Case persistence/correlation. Authorization, Gateway,
 * Execution, Observation, Outcome and Knowledge semantics remain in their owning domains.
 */
public final class IntelligenceCaseLifecycleService {
    private final IntelligenceCaseStore store;
    private final InstitutionalIntelligenceReferenceBridge references;
    private final IntelligenceExecutionAdmissionService executionAdmission;
    private final IntelligenceOutcomeLearningBridge outcomeLearning;
    private final IntelligenceKnowledgeAdmissionService knowledgeAdmission;

    public IntelligenceCaseLifecycleService(IntelligenceCaseStore store, Clock clock) {
        this(store, clock, null);
    }

    public IntelligenceCaseLifecycleService(
            IntelligenceCaseStore store,
            Clock clock,
            IntelligenceRoutingFeedbackService routingFeedback) {
        InstitutionalIntelligenceReferenceBridge refs = new InstitutionalIntelligenceReferenceBridge();
        this.store = Objects.requireNonNull(store, "store");
        this.references = refs;
        this.executionAdmission = new IntelligenceExecutionAdmissionService(Objects.requireNonNull(clock, "clock"));
        this.outcomeLearning = new IntelligenceOutcomeLearningBridge(new LearningService(), refs, routingFeedback);
        this.knowledgeAdmission = new IntelligenceKnowledgeAdmissionService(clock);
    }

    IntelligenceCaseLifecycleService(
            IntelligenceCaseStore store,
            InstitutionalIntelligenceReferenceBridge references,
            IntelligenceExecutionAdmissionService executionAdmission,
            IntelligenceOutcomeLearningBridge outcomeLearning,
            IntelligenceKnowledgeAdmissionService knowledgeAdmission) {
        this.store = Objects.requireNonNull(store, "store");
        this.references = Objects.requireNonNull(references, "references");
        this.executionAdmission = Objects.requireNonNull(executionAdmission, "executionAdmission");
        this.outcomeLearning = Objects.requireNonNull(outcomeLearning, "outcomeLearning");
        this.knowledgeAdmission = Objects.requireNonNull(knowledgeAdmission, "knowledgeAdmission");
    }

    public IntelligenceCase requireCase(String caseId) {
        return store.findByCaseId(requireNonBlank(caseId, "caseId"))
                .orElseThrow(() -> new IllegalArgumentException("intelligence case not found: " + caseId));
    }

    public IntelligenceCase recordAuthorization(
            String caseId,
            BoundaryResult authorizationBoundary,
            String authorizationReference) {
        IntelligenceCase updated = references.linkAuthorization(
                requireCase(caseId), authorizationBoundary, authorizationReference);
        return save(updated);
    }

    public IntelligenceCase recordGateway(
            String caseId,
            BoundaryResult gatewayBoundary,
            String gatewayReference,
            List<String> evidenceReferences) {
        IntelligenceCase updated = references.linkGateway(
                requireCase(caseId), gatewayBoundary, gatewayReference,
                evidenceReferences == null ? List.of() : evidenceReferences);
        return save(updated);
    }

    public ExecutionAdmissionReceipt admitExecution(
            String caseId,
            String workerId,
            String assignmentId,
            String workPackageId,
            String executionId,
            String authorizationReference,
            String gatewayReference,
            BoundaryResult authorizationBoundary,
            BoundaryResult gatewayBoundary) {
        IntelligenceExecutionAdmissionService.AdmissionResult admitted = executionAdmission.admit(
                requireCase(caseId), workerId, assignmentId, workPackageId, executionId,
                authorizationReference, gatewayReference, authorizationBoundary, gatewayBoundary);
        IntelligenceCase persisted = save(admitted.intelligenceCase());
        return new ExecutionAdmissionReceipt(persisted, admitted.handoff(), admitted.gatewayReference());
    }

    public IntelligenceCase recordExecution(
            String caseId,
            BoundaryResult executionBoundary,
            String executionReference,
            List<String> executionEvidenceReferences) {
        IntelligenceCase updated = references.linkExecution(
                requireCase(caseId), executionBoundary, executionReference,
                executionEvidenceReferences == null ? List.of() : executionEvidenceReferences);
        return save(updated);
    }

    public IntelligenceOutcomeLearningBridge.FeedbackResult recordObservedOutcome(
            String caseId,
            BoundaryResult observationBoundary,
            String executionId,
            String observationId,
            String outcomeId,
            String sourceReference,
            String expectedVsActualStatement,
            Instant observedAt) {
        IntelligenceOutcomeLearningBridge.FeedbackResult feedback = outcomeLearning.recordObservedOutcome(
                requireCase(caseId), observationBoundary, executionId, observationId, outcomeId,
                sourceReference, expectedVsActualStatement, observedAt);
        IntelligenceCase persisted = save(feedback.intelligenceCase());
        return new IntelligenceOutcomeLearningBridge.FeedbackResult(
                persisted, feedback.learningEvidence(), feedback.experience());
    }

    public IntelligenceKnowledgeAdmissionService.AdmissionRequest prepareKnowledgeAdmission(
            String caseId,
            WorkforcePracticeCandidate candidate,
            String actorId,
            String authorityReference) {
        return knowledgeAdmission.prepare(requireCase(caseId), candidate, actorId, authorityReference);
    }

    public IntelligenceCase recordKnowledgeAdmission(
            String caseId,
            BoundaryResult knowledgeBoundary,
            String knowledgeReference) {
        IntelligenceCase updated = knowledgeAdmission.linkAdmissionDecision(
                requireCase(caseId), knowledgeBoundary, knowledgeReference);
        return save(updated);
    }

    private IntelligenceCase save(IntelligenceCase intelligenceCase) {
        store.save(intelligenceCase);
        return intelligenceCase;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public record ExecutionAdmissionReceipt(
            IntelligenceCase intelligenceCase,
            ExecutionHandoffRequest handoff,
            String gatewayReference) {
        public ExecutionAdmissionReceipt {
            Objects.requireNonNull(intelligenceCase, "intelligenceCase");
            Objects.requireNonNull(handoff, "handoff");
            Objects.requireNonNull(gatewayReference, "gatewayReference");
        }
    }
}
