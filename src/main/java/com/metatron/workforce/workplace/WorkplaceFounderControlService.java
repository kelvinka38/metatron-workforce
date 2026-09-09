package com.metatron.workforce.workplace;

import com.metatron.workforce.management.AutonomySafetyService;
import com.metatron.workforce.management.AutonomySafetyState;
import com.metatron.workforce.management.AutonomousManagementRunner;
import com.metatron.workforce.management.ManagementAutonomyService;
import com.metatron.workforce.management.ManagementObjective;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Authenticated Workplace application boundary for existing founder Objective controls.
 *
 * The browser never receives or supplies the durable authority reference. This service resolves the
 * accepted Objective's canonical Workplace continuity and applies the same safety/management
 * transitions used by the lower-level control API.
 */
@Service
public final class WorkplaceFounderControlService {
    private final ManagementAutonomyService management;
    private final AutonomySafetyService safety;
    private final AutonomousManagementRunner runner;
    private final WorkplaceContinuityService continuity;

    public WorkplaceFounderControlService(
            ManagementAutonomyService management,
            AutonomySafetyService safety,
            AutonomousManagementRunner runner,
            WorkplaceContinuityService continuity) {
        this.management = management;
        this.safety = safety;
        this.runner = runner;
        this.continuity = continuity;
    }

    public ControlResult pause(String objectiveId) {
        ControlIdentity identity = identity(objectiveId);
        Instant now = Instant.now();
        AutonomySafetyState state = safety.pause(objectiveId, identity.authorityReference(), now);
        ManagementObjective objective = management.get(objectiveId);
        if (!objective.terminal() && objective.status() != ManagementObjective.Status.PAUSED) {
            management.pauseObjective(objectiveId, objective.ownerWorkerId(),
                    "workplace-control:pause:human=" + identity.humanId(), now);
        }
        return result(objectiveId, state);
    }

    public ControlResult resume(String objectiveId) {
        ControlIdentity identity = identity(objectiveId);
        Instant now = Instant.now();
        AutonomySafetyState state = safety.resume(objectiveId, identity.authorityReference(), now);
        ManagementObjective objective = management.get(objectiveId);
        if (objective.status() == ManagementObjective.Status.PAUSED
                || objective.status() == ManagementObjective.Status.BLOCKED
                || objective.status() == ManagementObjective.Status.ESCALATED) {
            management.resumeObjective(objectiveId, objective.ownerWorkerId(),
                    "workplace-control:resume:human=" + identity.humanId(), now);
        }
        runner.wake();
        return result(objectiveId, state);
    }

    public ControlResult cancel(String objectiveId) {
        ControlIdentity identity = identity(objectiveId);
        Instant now = Instant.now();
        AutonomySafetyState state = safety.cancel(objectiveId, identity.authorityReference(), now);
        ManagementObjective objective = management.get(objectiveId);
        if (!objective.terminal()) {
            management.cancelObjective(objectiveId, objective.ownerWorkerId(),
                    "workplace-control:cancel:human=" + identity.humanId(), now);
        }
        return result(objectiveId, state);
    }

    public ControlResult replan(String objectiveId, String reason) {
        ControlIdentity identity = identity(objectiveId);
        Instant now = Instant.now();
        AutonomySafetyState state = safety.resume(objectiveId, identity.authorityReference(), now);
        ManagementObjective objective = management.get(objectiveId);
        if (objective.terminal()) throw new IllegalStateException("terminal Objective cannot be replanned");
        management.requestReplan(objectiveId, objective.ownerWorkerId(),
                "workplace-control:replan:human=" + identity.humanId()
                        + ";reason=" + compact(reason), now);
        runner.wake();
        return result(objectiveId, state);
    }

    private ControlIdentity identity(String objectiveId) {
        WorkplaceContinuityRecord record = continuity.continuity(objectiveId);
        return new ControlIdentity(record.humanId(), record.requestAdmissionRef());
    }

    private ControlResult result(String objectiveId, AutonomySafetyState state) {
        return new ControlResult(management.get(objectiveId), state);
    }

    private static String compact(String reason) {
        String value = reason == null ? "" : reason.replaceAll("\\s+", " ").trim();
        if (value.isBlank()) value = "Founder requested replan from Workplace Control Room";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private record ControlIdentity(String humanId, String authorityReference) {}
    public record ControlResult(ManagementObjective objective, AutonomySafetyState safety) {}
}
