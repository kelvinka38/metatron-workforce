package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.workers.audit.RepositoryAuditExecutionService;
import org.springframework.stereotype.Component;

/** Founder-approved bounded formation policy for the read-only repository auditor. */
@Component
public final class RepositoryAuditStaffingPolicy implements AutonomousStaffingPolicy {
    @Override public String capabilityRef() { return RepositoryAuditAutonomousCapability.CAPABILITY; }

    @Override
    public FormationSpec formationSpec() {
        return new FormationSpec(
                true,
                "participant:repository-auditor",
                WorkforceCoreService.ParticipantType.AI,
                "provenance:workforce-governed-repository-auditor:v1",
                RepositoryAuditExecutionService.WORKER_ID,
                "organization:metatron",
                "participation:repository-auditor:metatron",
                "position:repository-auditor",
                "role:read-only-auditor",
                1.0,
                "evidence:repository-audit-capability-acceptance:v1",
                "qualification:repository-audit-read-only:v1",
                "evidence:repository-audit-qualification:v1",
                RepositoryAuditAutonomousCapability.AUTHORITY_REFERENCE,
                4.0,
                "runtime-profile:repository-audit-worker:v1",
                "cost-limit:repository-audit-bounded:v1",
                "lifecycle:repository-audit-worker:v1");
    }
}
