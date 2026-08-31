package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import org.springframework.stereotype.Component;

/** Founder-approved bounded formation policy for the P10 recovery probe Worker. */
@Component
public final class AutonomyRecoveryProbeStaffingPolicy implements AutonomousStaffingPolicy {
    @Override public String capabilityRef() { return AutonomyRecoveryProbeCapability.CAPABILITY; }

    @Override
    public FormationSpec formationSpec() {
        return new FormationSpec(
                true,
                "participant:autonomy-recovery-probe",
                WorkforceCoreService.ParticipantType.AI,
                "provenance:workforce-governed-autonomy-recovery-probe:v1",
                AutonomyRecoveryProbeCapability.WORKER_ID,
                "organization:metatron",
                "participation:autonomy-recovery-probe:metatron",
                "position:autonomy-recovery-probe",
                "role:bounded-autonomy-recovery-prober",
                1.0,
                "evidence:autonomy-recovery-probe-capability-acceptance:v1",
                "qualification:p10-read-only-recovery-probe:v1",
                "evidence:autonomy-recovery-probe-qualification:v1",
                AutonomyRecoveryProbeCapability.AUTHORITY_REFERENCE,
                1.0,
                "runtime-profile:autonomy-recovery-probe:v1",
                "cost-limit:autonomy-recovery-probe-bounded:v1",
                "lifecycle:autonomy-recovery-probe:v1");
    }
}
