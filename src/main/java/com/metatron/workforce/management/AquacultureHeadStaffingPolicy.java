package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Founder-approved formation contract for the Head of Aquaculture (BIOS Aquaculture domain head). */
@Component
public final class AquacultureHeadStaffingPolicy implements AutonomousStaffingPolicy {
    public static final Set<String> REPOSITORIES = Set.of("kelvinka38/bios");
    public static final List<String> WRITE_PATH_PREFIXES = List.of(
            "DOMAINS/AQUACULTURE/ACTION_PLANS/",
            "DOMAINS/AQUACULTURE/REPORTS/");
    private static final String FOUNDER = AquacultureHeadAppointmentCapability.FOUNDER_HUMAN_ID;

    @Override public String capabilityRef() { return AquacultureHeadAppointmentCapability.CAPABILITY; }

    @Override
    public FormationSpec formationSpec() {
        return new FormationSpec(
                true,
                "participant:head-of-aquaculture-ai",
                WorkforceCoreService.ParticipantType.AI,
                "provenance:head-of-aquaculture-ai:v1",
                AquacultureHeadAppointmentCapability.WORKER_ID,
                AquacultureHeadAppointmentCapability.ORGANIZATION_REF,
                "participation:head-of-aquaculture:bios",
                AquacultureHeadAppointmentCapability.POSITION_REF,
                AquacultureHeadAppointmentCapability.ROLE_REF,
                1.0,
                "evidence:founder-aquaculture-head-appointment:v1",
                "qualification:head-of-aquaculture:v1",
                "evidence:head-of-aquaculture-qualification:v1",
                AquacultureHeadAppointmentCapability.AUTHORITY_REFERENCE,
                1.0,
                WorkerRuntimeProfileBindingService.DOMAIN_HEAD_PROFILE,
                "cost-limit:head-of-aquaculture-free-tier-cognition-only:v1",
                "lifecycle:head-of-aquaculture-persistent:v1");
    }

    @Override
    public Optional<WorkerResourceScopeSpec> workerResourceScope() {
        return Optional.of(new WorkerResourceScopeSpec(REPOSITORIES, WRITE_PATH_PREFIXES));
    }

    @Override
    public PositionContractSpec positionContractSpec() {
        FormationSpec spec = formationSpec();
        return new PositionContractSpec(
                "Plan, assign, integrate and report BIOS Aquaculture work according to "
                        + "DOMAINS/AQUACULTURE/EXECUTION_PLAN.md in kelvinka38/bios, within the HOA trust envelope, "
                        + "proposing every output as an unmerged pull request for review.",
                List.of(
                        "Read the governing program documents (EXECUTION_PLAN, DECISIONS, DOMAIN_PACK, gaps, coverage) before planning.",
                        "Write ACTION_PLAN_vN per EXECUTION_PLAN §7 for the current phase and submit it for HOI review.",
                        "Own the backlog and gap list; split work into tasks with checkable outputs and evidence.",
                        "Record delegation to worker capability classes; no subordinate Workers exist yet, so delegation is recorded only.",
                        "Report at least weekly: completed work, evidence, new gaps, risks.",
                        "Never state that work was executed without attributable evidence."),
                List.of(new ReportingLineSpec("REPORTS_TO", FOUNDER,
                        "BIOS Aquaculture program accountability, envelope exceptions and escalations")),
                List.of(
                        AquacultureHeadAppointmentCapability.CAPABILITY,
                        AquacultureHeadAppointmentCapability.PLANNING_CAPABILITY,
                        AquacultureHeadAppointmentCapability.REPORTING_CAPABILITY,
                        AquacultureHeadAppointmentCapability.DELEGATION_CAPABILITY,
                        AquacultureHeadAppointmentCapability.WEB_READ_CAPABILITY,
                        AquacultureHeadAppointmentCapability.BIOS_PROPOSAL_CAPABILITY),
                List.of(
                        spec.authorityEnvelopeRef(),
                        "aquaculture:hoa-trust-envelope:EXECUTION_PLAN-§3.1",
                        "aquaculture:proposal-only-no-merge"),
                List.of(
                        new ResourceScopeSpec("capacity", "max:" + spec.capacity()),
                        new ResourceScopeSpec("runtime-profile", spec.runtimeProfileRef()),
                        new ResourceScopeSpec("cost", spec.costLimitRef()),
                        new ResourceScopeSpec("repositories", String.join(",", REPOSITORIES.stream().sorted().toList())),
                        new ResourceScopeSpec("write-path-prefixes", String.join(",", WRITE_PATH_PREFIXES)),
                        new ResourceScopeSpec("tool-effects", "profile-actions-only;no-merge;no-deploy")),
                List.of(
                        new EscalationRouteSpec("OUT_OF_ENVELOPE", FOUNDER,
                                "Required decision or action is outside the HOA trust envelope (EXECUTION_PLAN §3.2)"),
                        new EscalationRouteSpec("TWO_FAILED_ATTEMPTS", FOUNDER,
                                "A task or ACTION_PLAN failed review/validation twice (EXECUTION_PLAN §4, §7)"),
                        new EscalationRouteSpec("CAPABILITY_REQUIRED", FOUNDER,
                                "Work needs a capability, Worker or tool the HOA does not have")),
                List.of(
                        new SuccessMeasureSpec("action-plan-review-criteria",
                                "ACTION_PLAN meets the review criteria of EXECUTION_PLAN §7",
                                "Accepted by HOI without rewrite"),
                        new SuccessMeasureSpec("no-unevidenced-execution-claims",
                                "No claim that work was executed without attributable evidence",
                                "0 unevidenced execution claims")),
                List.of(
                        "Order the backlog, split tasks and (re)assign within the current phase (EXECUTION_PLAN §3.1).",
                        "Accept data output that passed the validator and spot checks; open gap records.",
                        "Propose changes only as unmerged pull requests; never merge, never deploy, never release."),
                "ASSIGNMENT_DRIVEN_BOUNDED_WITH_WEEKLY_REPORTING",
                "Asia/Ho_Chi_Minh",
                1);
    }

    @Override
    public List<CapabilityGrant> additionalCapabilities() {
        return List.of(
                new CapabilityGrant(AquacultureHeadAppointmentCapability.PLANNING_CAPABILITY, 1.0,
                        "evidence:head-of-aquaculture-domain-planning:v1"),
                new CapabilityGrant(AquacultureHeadAppointmentCapability.REPORTING_CAPABILITY, 1.0,
                        "evidence:head-of-aquaculture-reporting:v1"),
                new CapabilityGrant(AquacultureHeadAppointmentCapability.DELEGATION_CAPABILITY, 1.0,
                        "evidence:head-of-aquaculture-delegation-record-only:v1"),
                new CapabilityGrant(AquacultureHeadAppointmentCapability.WEB_READ_CAPABILITY, 1.0,
                        "evidence:head-of-aquaculture-web-read:v1"),
                new CapabilityGrant(AquacultureHeadAppointmentCapability.BIOS_PROPOSAL_CAPABILITY, 1.0,
                        "evidence:head-of-aquaculture-bios-proposal:v1"));
    }
}
