package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Explicit governance input for autonomous staffing. A staffing gap is never sufficient authority
 * to create a Participant or Worker; a matching policy must already authorize the formation path.
 *
 * A formation policy also defines the standing Position operating contract required for a Worker
 * to be institutionally usable. Runtime/tool access alone is insufficient.
 */
public interface AutonomousStaffingPolicy {
    String capabilityRef();
    FormationSpec formationSpec();

    /** Additional governed capabilities attested as part of this approved formation contract. */
    default List<CapabilityGrant> additionalCapabilities() { return List.of(); }

    /**
     * Standing Position constitution. Specific institutional Heads SHOULD override this with the
     * richer domain contract ratified by their canonical SOT. The default remains explicit and
     * bounded so every formed Worker has a real mission/accountability envelope rather than a
     * provider prompt plus tool profile.
     */
    default PositionContractSpec positionContractSpec() {
        FormationSpec spec = formationSpec();
        List<String> capabilityRequirements = new ArrayList<>();
        capabilityRequirements.add(capabilityRef());
        additionalCapabilities().forEach(grant -> capabilityRequirements.add(grant.capabilityRef()));

        return new PositionContractSpec(
                "Perform governed institutional work requiring " + capabilityRef()
                        + " within " + spec.roleRef() + " at " + spec.positionRef() + ".",
                List.of(
                        "Understand assigned work and its acceptance/evidence requirements before acting.",
                        "Operate only within granted capability, authority, authorization, resource and capacity scope.",
                        "Use governed runtime actions when execution is required and preserve attributable evidence.",
                        "Report blockers, uncertainty, exceptions and completion truthfully."),
                List.of(new ReportingLineSpec("REPORTS_TO", "objective-owner",
                        "Assigned Objective/Work accountability and exception reporting")),
                List.copyOf(capabilityRequirements),
                List.of(spec.authorityEnvelopeRef()),
                List.of(
                        new ResourceScopeSpec("capacity", "max:" + spec.capacity()),
                        new ResourceScopeSpec("runtime-profile", spec.runtimeProfileRef()),
                        new ResourceScopeSpec("cost", spec.costLimitRef())),
                List.of(
                        new EscalationRouteSpec("INSUFFICIENT_AUTHORITY", "workforce-management",
                                "Required action exceeds the standing/assignment authority envelope"),
                        new EscalationRouteSpec("BLOCKED_OR_MATERIAL_RISK", "objective-owner",
                                "Bounded recovery cannot legitimately resolve the blocker or material risk")),
                List.of(
                        new SuccessMeasureSpec("evidence-backed-completion",
                                "Assigned work closes only when acceptance is supported by attributable evidence",
                                "100% of claimed completions evidence-backed"),
                        new SuccessMeasureSpec("authority-compliance",
                                "No execution outside granted authority/authorization",
                                "0 unauthorized executions"),
                        new SuccessMeasureSpec("capacity-compliance",
                                "Assignments and runtime work remain within bounded capacity/resource scope",
                                "0 unapproved overcommitments")),
                List.of(
                        "Analyze and recommend within role scope.",
                        "Execute assigned work only after required authority/authorization is established.",
                        "Request resources/staffing or escalate when current envelope is insufficient."),
                "ASSIGNMENT_DRIVEN_BOUNDED",
                "UTC",
                Math.max(1, (int)Math.ceil(spec.capacity())));
    }

    record CapabilityGrant(String capabilityRef, double level, String evidenceRef) {
        public CapabilityGrant {
            require(capabilityRef, "capabilityRef");
            require(evidenceRef, "evidenceRef");
            if (!Double.isFinite(level) || level <= 0) {
                throw new IllegalArgumentException("capability grant level must be positive");
            }
        }
    }

    record ReportingLineSpec(String relationshipType, String targetRef, String scope) {
        public ReportingLineSpec {
            require(relationshipType, "relationshipType");
            require(targetRef, "targetRef");
            require(scope, "scope");
        }
    }

    record ResourceScopeSpec(String resourceRef, String limitRef) {
        public ResourceScopeSpec {
            require(resourceRef, "resourceRef");
            require(limitRef, "limitRef");
        }
    }

    record EscalationRouteSpec(String category, String targetRef, String trigger) {
        public EscalationRouteSpec {
            require(category, "category");
            require(targetRef, "targetRef");
            require(trigger, "trigger");
        }
    }

    record SuccessMeasureSpec(String measureRef, String description, String target) {
        public SuccessMeasureSpec {
            require(measureRef, "measureRef");
            require(description, "description");
            require(target, "target");
        }
    }

    record PositionContractSpec(
            String mission,
            List<String> responsibilities,
            List<ReportingLineSpec> reportingLines,
            List<String> capabilityRequirements,
            List<String> authorityScopes,
            List<ResourceScopeSpec> resourceScopes,
            List<EscalationRouteSpec> escalationRoutes,
            List<SuccessMeasureSpec> successMeasures,
            List<String> decisionRights,
            String operatingCoverage,
            String workingTimeZone,
            int maxConcurrentAssignments) {
        public PositionContractSpec {
            require(mission, "mission");
            responsibilities = nonEmpty(responsibilities, "responsibilities");
            reportingLines = nonEmpty(reportingLines, "reportingLines");
            capabilityRequirements = nonEmpty(capabilityRequirements, "capabilityRequirements");
            authorityScopes = nonEmpty(authorityScopes, "authorityScopes");
            resourceScopes = nonEmpty(resourceScopes, "resourceScopes");
            escalationRoutes = nonEmpty(escalationRoutes, "escalationRoutes");
            successMeasures = nonEmpty(successMeasures, "successMeasures");
            decisionRights = nonEmpty(decisionRights, "decisionRights");
            require(operatingCoverage, "operatingCoverage");
            require(workingTimeZone, "workingTimeZone");
            if (maxConcurrentAssignments < 1) throw new IllegalArgumentException("maxConcurrentAssignments must be positive");
        }
    }

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
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }

    private static <T> List<T> nonEmpty(List<T> values, String field) {
        Objects.requireNonNull(values, field);
        List<T> copy = List.copyOf(values);
        if (copy.isEmpty()) throw new IllegalArgumentException(field + " required");
        return copy;
    }
}
