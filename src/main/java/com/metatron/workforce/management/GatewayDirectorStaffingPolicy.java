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
    public PositionContractSpec positionContractSpec() {
        FormationSpec spec = formationSpec();
        return new PositionContractSpec(
                "Continuously own Gateway as Metatron's controlled institutional boundary and make authorized ingress/egress, routing and operator access as reliable, secure, observable, recoverable and smooth as justified demand, risk and cost allow.",
                List.of(
                        "Continuously assess Gateway demand, service health, dependencies, risks, capacity and cost.",
                        "Plan and maintain the Gateway operating model across Front Office, Access Operations, Reliability, Engineering/Maintenance, Gateway Security and Observation/Management functions.",
                        "Define service objectives, operating coverage, recovery expectations and measurable management targets from evidence rather than invented numbers.",
                        "Assess staffing/capability demand and raise governed Workforce requests instead of manually fabricating personnel.",
                        "Own Gateway-scoped reliability, incident/recovery coordination, change planning, rollback readiness and continuous improvement.",
                        "Maintain Gateway-scoped security controls while preserving institution-wide Defense/Security authority.",
                        "Report material health, blockers, incidents, capacity/cost, risks, decisions and evidence upward without making Founder the routine operator."),
                List.of(
                        new ReportingLineSpec("REPORTS_TO", GatewayDirectorAppointmentCapability.FOUNDER_HUMAN_ID,
                                "Gateway executive accountability, material exception and protected-authority escalation"),
                        new ReportingLineSpec("COORDINATES_WITH", "domain:04_DEFENSE_AND_SECURITY",
                                "Institution-wide security policy, standards, oversight and material security escalation"),
                        new ReportingLineSpec("COORDINATES_WITH", "domain:05_WORKFORCE",
                                "Staffing, capacity allocation, assignments and Workforce operating reality")),
                List.of(
                        GatewayDirectorAppointmentCapability.CAPABILITY,
                        GatewayDirectorAppointmentCapability.GATEWAY_AUDIT_CAPABILITY,
                        "gateway.operational.management",
                        "gateway.reliability.management",
                        "gateway.capacity.cost.management",
                        "gateway.incident.recovery.coordination"),
                List.of(
                        spec.authorityEnvelopeRef(),
                        "gateway:operational-management-within-delegated-scope",
                        "gateway:staffing-and-resource-request",
                        "gateway:change-and-recovery-decision-within-authorization"),
                List.of(
                        new ResourceScopeSpec("capacity", "max:" + spec.capacity()),
                        new ResourceScopeSpec("runtime-profile", spec.runtimeProfileRef()),
                        new ResourceScopeSpec("cost", spec.costLimitRef()),
                        new ResourceScopeSpec("gateway-production-resources", "only-as-explicitly-authorized")),
                List.of(
                        new EscalationRouteSpec("SECURITY_POLICY_OR_INSTITUTIONAL_RISK", "domain:04_DEFENSE_AND_SECURITY",
                                "Required decision crosses Gateway-scoped security authority or material institutional security threshold"),
                        new EscalationRouteSpec("MAJOR_INVESTMENT_OR_PROTECTED_AUTHORITY", GatewayDirectorAppointmentCapability.FOUNDER_HUMAN_ID,
                                "Major budget extension, constitutional/protected authority, irreversible/high-risk exception or demonstrated infeasibility"),
                        new EscalationRouteSpec("STAFFING_OR_CAPACITY_GAP", "domain:05_WORKFORCE",
                                "Current legitimate capacity cannot satisfy approved demand/SLO/risk envelope")),
                List.of(
                        new SuccessMeasureSpec("gateway-boundary-correctness",
                                "Authorized boundary crossings preserve identity/authentication/authorization/admission/routing distinctions and evidence",
                                "No known semantic boundary bypass or ownership capture"),
                        new SuccessMeasureSpec("gateway-operational-health",
                                "Critical Gateway services have evidence-derived health, owned recovery and service-objective tracking",
                                "Health/SLO/recovery status continuously inspectable"),
                        new SuccessMeasureSpec("gateway-routine-autonomy",
                                "Routine operation, remediation, deployment/rollback and recovery do not require Founder as undocumented operator",
                                "0 routine Founder workstation dependencies"),
                        new SuccessMeasureSpec("gateway-change-integrity",
                                "Production changes are attributable, authorized, observable, recoverable and verified",
                                "100% material changes carry change/execution/observation evidence"),
                        new SuccessMeasureSpec("gateway-capacity-cost-visibility",
                                "Demand, capacity, justified reserve and cost are visible enough for executive decisions",
                                "Material capacity/cost variance explainable from evidence"),
                        new SuccessMeasureSpec("gateway-user-smoothness",
                                "Human/channel access avoids unnecessary relay, queueing and interaction friction while preserving controls",
                                "Material latency/interaction friction tracked and actively improved")),
                List.of(
                        "Set Gateway operating priorities and plans within canonical scope.",
                        "Define demand/capacity/staffing requirements and submit governed Workforce requests.",
                        "Coordinate incidents, recovery and change within delegated Gateway authority.",
                        "Approve or reject Gateway-scoped operational proposals where separately authorized.",
                        "Escalate protected authority, major investment, legal/security-policy conflict or infeasibility rather than self-grant authority.",
                        "Initiate evidence-backed improvement work when observed performance or risk justifies it."),
                "CONTINUOUS_ACCOUNTABILITY_WITH_DEMAND_DRIVEN_BOUNDED_EXECUTION",
                "UTC",
                1);
    }

    @Override
    public List<CapabilityGrant> additionalCapabilities() {
        return List.of(
                new CapabilityGrant(
                        GatewayDirectorAppointmentCapability.GATEWAY_AUDIT_CAPABILITY,
                        1.0,
                        "evidence:gateway-director-audit-capability:v1"),
                new CapabilityGrant(
                        "gateway.operational.management",
                        1.0,
                        "evidence:gateway-director-operational-management:v1"),
                new CapabilityGrant(
                        "gateway.reliability.management",
                        1.0,
                        "evidence:gateway-director-reliability-management:v1"),
                new CapabilityGrant(
                        "gateway.capacity.cost.management",
                        1.0,
                        "evidence:gateway-director-capacity-cost-management:v1"),
                new CapabilityGrant(
                        "gateway.incident.recovery.coordination",
                        1.0,
                        "evidence:gateway-director-incident-recovery-coordination:v1"));
    }
}
