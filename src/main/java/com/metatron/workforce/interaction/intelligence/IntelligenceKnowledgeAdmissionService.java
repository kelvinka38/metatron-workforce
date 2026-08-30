package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.phase8.WorkforcePracticeCandidate;
import com.metatron.workforce.phase9.BoundaryProvenance;
import com.metatron.workforce.phase9.BoundaryRequest;
import com.metatron.workforce.phase9.BoundaryResult;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Prepares validated Workforce learning for the external Knowledge admission boundary.
 * It cannot admit Knowledge by itself and only links a Knowledge reference after a successful boundary result.
 */
public final class IntelligenceKnowledgeAdmissionService {
    private final Clock clock;
    private final InstitutionalIntelligenceReferenceBridge references;

    public IntelligenceKnowledgeAdmissionService(Clock clock) {
        this(clock, new InstitutionalIntelligenceReferenceBridge());
    }

    IntelligenceKnowledgeAdmissionService(Clock clock, InstitutionalIntelligenceReferenceBridge references) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.references = Objects.requireNonNull(references, "references");
    }

    public AdmissionRequest prepare(
            IntelligenceCase intelligenceCase,
            WorkforcePracticeCandidate candidate,
            String actorId,
            String authorityReference) {

        Objects.requireNonNull(intelligenceCase, "intelligenceCase");
        Objects.requireNonNull(candidate, "candidate");
        requireNonBlank(actorId, "actorId");
        requireNonBlank(authorityReference, "authorityReference");
        if (!candidate.validated() || candidate.validationEvidence() == null || candidate.validationEvidence().isBlank()) {
            throw new IllegalStateException("knowledge admission requires a validated Workforce learning candidate");
        }

        Instant now = Instant.now(clock);
        KnowledgeAdmissionPackage input = new KnowledgeAdmissionPackage(
                "case:" + intelligenceCase.caseId(),
                "workforce-practice:" + candidate.id(),
                candidate.pattern(),
                candidate.workerEvidence(),
                candidate.validationEvidence());
        BoundaryProvenance provenance = new BoundaryProvenance(
                "workforce-intelligence",
                candidate.validationEvidence(),
                now);
        BoundaryRequest request = new BoundaryRequest(
                "knowledge-admission-" + UUID.randomUUID(),
                InstitutionalIntelligenceReferenceBridge.KNOWLEDGE_CONTRACT,
                actorId.trim(),
                authorityReference.trim(),
                input,
                provenance);
        return new AdmissionRequest(request, input);
    }

    public IntelligenceCase linkAdmissionDecision(
            IntelligenceCase intelligenceCase,
            BoundaryResult result,
            String knowledgeReference) {
        return references.linkKnowledgeAdmission(intelligenceCase, result, knowledgeReference);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    public record KnowledgeAdmissionPackage(
            String intelligenceCaseReference,
            String learningCandidateReference,
            String candidatePattern,
            List<String> workerEvidenceReferences,
            String validationEvidenceReference) {
        public KnowledgeAdmissionPackage {
            Objects.requireNonNull(intelligenceCaseReference, "intelligenceCaseReference");
            Objects.requireNonNull(learningCandidateReference, "learningCandidateReference");
            Objects.requireNonNull(candidatePattern, "candidatePattern");
            Objects.requireNonNull(workerEvidenceReferences, "workerEvidenceReferences");
            Objects.requireNonNull(validationEvidenceReference, "validationEvidenceReference");
            workerEvidenceReferences = List.copyOf(workerEvidenceReferences);
        }
    }

    public record AdmissionRequest(BoundaryRequest boundaryRequest, KnowledgeAdmissionPackage packageToAdmit) {
        public AdmissionRequest {
            Objects.requireNonNull(boundaryRequest, "boundaryRequest");
            Objects.requireNonNull(packageToAdmit, "packageToAdmit");
        }
    }
}
