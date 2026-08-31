package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;

import java.util.Objects;

/**
 * Explicit governance input for autonomous staffing. A staffing gap is never sufficient authority
 * to create a Participant or Worker; a matching policy must already authorize the formation path.
 */
public interface AutonomousStaffingPolicy {
    String capabilityRef();
    FormationSpec formationSpec();

    record FormationSpec(
            boolean formationPermitted,
            String participantId,
            WorkforceCoreService.ParticipantType participantType,
            String participantProvenanceRef,
            String workerId,
            String organizationRef,
            String participationId,
            String positionRef,
            String roleRef,
            double capabilityLevel,
            String capabilityEvidenceRef,
            String qualificationRef,
            String qualificationEvidenceRef,
            String authorityEnvelopeRef,
            double capacity,
            String runtimeProfileRef,
            String costLimitRef,
            String lifecycleRef) {
        public FormationSpec {
            Objects.requireNonNull(participantType, "participantType");
            require(participantId, "participantId");
            require(participantProvenanceRef, "participantProvenanceRef");
            require(workerId, "workerId");
            require(organizationRef, "organizationRef");
            require(participationId, "participationId");
            require(positionRef, "positionRef");
            require(roleRef, "roleRef");
            require(capabilityEvidenceRef, "capabilityEvidenceRef");
            require(qualificationRef, "qualificationRef");
            require(qualificationEvidenceRef, "qualificationEvidenceRef");
            require(authorityEnvelopeRef, "authorityEnvelopeRef");
            require(runtimeProfileRef, "runtimeProfileRef");
            require(costLimitRef, "costLimitRef");
            require(lifecycleRef, "lifecycleRef");
            if (!Double.isFinite(capabilityLevel) || capabilityLevel <= 0) throw new IllegalArgumentException("capabilityLevel must be positive");
            if (!Double.isFinite(capacity) || capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        }
        private static void require(String value, String field) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        }
    }
}
