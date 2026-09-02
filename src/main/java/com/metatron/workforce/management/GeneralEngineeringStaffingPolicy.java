package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.springframework.stereotype.Component;

/** Founder-approved formation policy for the general engineering Cognitive Worker. */
@Component
public final class GeneralEngineeringStaffingPolicy implements AutonomousStaffingPolicy {
    @Override public String capabilityRef() { return GeneralWorkspaceAutonomousCapability.CAPABILITY; }

    @Override
    public FormationSpec formationSpec() {
        return new FormationSpec(
                true,
                "participant:general-engineering-worker",
                WorkforceCoreService.ParticipantType.AI,
                "provenance:workforce-general-cognitive-engineering:v1",
                GeneralWorkspaceAutonomousCapability.WORKER_ID,
                "organization:metatron",
                "participation:general-engineering-worker:metatron",
                "position:general-engineering-executor",
                "role:general-code-and-runtime-worker",
                1.0,
                "evidence:general-engineering-capability-acceptance:v1",
                "qualification:general-governed-workspace-execution:v1",
                "evidence:general-engineering-qualification:v1",
                GeneralWorkspaceAutonomousCapability.AUTHORITY_REFERENCE,
                2.0,
                WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                "cost-limit:general-engineering-bounded:v1",
                "lifecycle:general-engineering-worker:v1");
    }
}
