package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import org.springframework.stereotype.Component;

/**
 * Formation policy for the standing, least-privilege WORKER-HOUSEKEEPING. This is the governance
 * input AutonomousStaffingService requires before it may create the Participant/Worker/Participation
 * the first time host.storage.housekeeping is dispatched -- a staffing gap alone is never sufficient
 * authority (see AutonomousStaffingPolicy). Formation is lazy: this Worker comes into existence on
 * first governed Housekeeping dispatch via the same awaitEligibleWorker() -> staffing.ensureStaffed()
 * path every other capability already uses, not a separate boot-time ceremony.
 */
@Component
public final class WorkerHousekeepingStaffingPolicy implements AutonomousStaffingPolicy {
    public static final String PARTICIPANT_ID = "participant:housekeeping-worker";
    public static final String ORGANIZATION_REF = "org-metatron";
    public static final String PARTICIPATION_ID = "participation:housekeeping-worker";
    public static final String POSITION_REF = "position:housekeeping";
    public static final String ROLE_REF = "role:housekeeping";
    public static final String QUALIFICATION_REF = "qualification:housekeeping-least-privilege-cleanup";

    @Override public String capabilityRef() { return WorkerHousekeepingCapability.CAPABILITY; }

    @Override
    public FormationSpec formationSpec() {
        return new FormationSpec(
                true,
                PARTICIPANT_ID,
                WorkforceCoreService.ParticipantType.AI,
                "founder-approved-standing-housekeeping-formation:v1",
                WorkerHousekeepingCapability.WORKER_ID,
                ORGANIZATION_REF,
                PARTICIPATION_ID,
                POSITION_REF,
                ROLE_REF,
                1.0,
                "founder-approved-standing-housekeeping-capability:v1",
                QUALIFICATION_REF,
                "founder-approved-standing-housekeeping-qualification:v1",
                WorkerHousekeepingCapability.AUTHORITY_REFERENCE,
                1.0,
                "runtime-profile:host-commander-bounded",
                "cost-limit:housekeeping-standard",
                "lifecycle:standing-least-privilege-cleanup");
    }

    @Override
    public PositionContractSpec positionContractSpec() {
        return new PositionContractSpec(
                "Keep the production host's Docker/build-cache/journal footprint within safe bounds without ever "
                        + "touching production images, rollback images, active Assignments/ExecutionAttempts/workspaces, "
                        + "named Metatron volumes, durable Workforce state, evidence, repositories, or credentials.",
                java.util.List.of(
                        "Audit disk usage before any mutation.",
                        "Reclaim only artifacts host_safe_cleanup already classifies as safe: apt cache, bounded "
                                + "journal vacuum, dangling images, and build cache older than the configured bound.",
                        "Never use blind prune, wildcard deletion, or the force-abandon-all ExecutionAttempt endpoint "
                                + "as a substitute for bounded housekeeping.",
                        "Report exact before/after disk usage and the broker's own reclaimed-bytes accounting as evidence.",
                        "Leave the host unchanged (idempotent) when nothing is safely reclaimable, and say so truthfully "
                                + "rather than reporting a cleanup that did not happen."),
                java.util.List.of(new ReportingLineSpec("REPORTS_TO", "objective-owner",
                        "Housekeeping Assignment accountability and exception reporting")),
                java.util.List.of(WorkerHousekeepingCapability.CAPABILITY),
                java.util.List.of(WorkerHousekeepingCapability.AUTHORITY_REFERENCE),
                java.util.List.of(
                        new ResourceScopeSpec("capacity", "max:1.0"),
                        new ResourceScopeSpec("runtime-profile", "runtime-profile:host-commander-bounded"),
                        new ResourceScopeSpec("cost", "cost-limit:housekeeping-standard")),
                java.util.List.of(
                        new EscalationRouteSpec("INSUFFICIENT_AUTHORITY", "workforce-management",
                                "Required cleanup would exceed the bounded host.storage.housekeeping capability"),
                        new EscalationRouteSpec("NON_RECLAIMABLE_PRESSURE", "objective-owner",
                                "Disk remains under pressure after safe cleanup; report exact non-reclaimable consumers "
                                        + "rather than deleting protected state")),
                java.util.List.of(
                        new SuccessMeasureSpec("safe-reclaim-only",
                                "Every reclaimed byte is accounted for by host_safe_cleanup's own bounded categories",
                                "0 deletions outside the documented safe categories"),
                        new SuccessMeasureSpec("idempotent-reporting",
                                "Repeated runs with nothing new to reclaim report 0B reclaimed, not a false healthy cleanup",
                                "100% of reports match actual before/after disk usage")),
                java.util.List.of("Execute bounded housekeeping cleanup.",
                        "Report before/after disk usage and reclaimed artifacts truthfully.",
                        "Escalate rather than widen scope when safe cleanup is insufficient."),
                "ASSIGNMENT_DRIVEN_BOUNDED",
                "UTC",
                1);
    }
}
