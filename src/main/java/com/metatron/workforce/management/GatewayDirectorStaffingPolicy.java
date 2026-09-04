package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.springframework.stereotype.Component;

import java.util.List;

/** Founder-approved canonical formation contract for the Gateway Director / Head of Gateway. */
@Component
public final class GatewayDirectorStaffingPolicy implements AutonomousStaffingPolicy {
    @Override public String capabilityRef() { return GatewayDirectorAppointmentCapability.CAPABILITY; }

    @Override
    public FormationSpec formationSpec() {
        return new FormationSpec(
                true,
                "participant:gateway-director-ai",
                WorkforceCoreService.ParticipantType.AI,
                "provenance:gateway-director-ai:v1",
                GatewayDirectorAppointmentCapability.WORKER_ID,
                "organization:metatron",
                "participation:gateway-director:metatron",
                GatewayDirectorAppointmentCapability.POSITION_REF,
                GatewayDirectorAppointmentCapability.ROLE_REF,
                1.0,
                "evidence:founder-gateway-director-appointment:v1",
                "qualification:gateway-director:v1",
                "evidence:gateway-director-qualification:v1",
                GatewayDirectorAppointmentCapability.AUTHORITY_REFERENCE,
                1.0,
                WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                "cost-limit:gateway-director-bounded:v1",
                "lifecycle:gateway-director-persistent:v1");
    }

    @Override
    public List<CapabilityGrant> additionalCapabilities() {
        return List.of(new CapabilityGrant(
                GatewayDirectorAppointmentCapability.GATEWAY_AUDIT_CAPABILITY,
                1.0,
                "evidence:gateway-director-audit-capability:v1"));
    }
}
