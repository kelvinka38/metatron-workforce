package com.metatron.workforce.management;

import com.metatron.workforce.workplace.WorkplaceContinuityRecord;
import com.metatron.workforce.workplace.WorkplaceContinuityService;
import com.metatron.workforce.workplace.WorkplaceDashboardAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Founder control surface for an accepted Objective.
 *
 * Authentication and authorization remain distinct. A valid Workplace session authenticates the
 * request. Workforce then binds the asserted actor and authority reference to the durable acceptance
 * provenance already recorded for that Objective; a free-form header can never mint authority.
 */
@RestController
@RequestMapping("/workforce/management/objectives/{objectiveId}/control")
public final class AutonomyControlController {
    private final ManagementAutonomyService management;
    private final AutonomySafetyService safety;
    private final AutonomousManagementRunner runner;
    private final WorkplaceContinuityService workplace;
    private final WorkplaceDashboardAuthService authentication;

    public AutonomyControlController(ManagementAutonomyService management,
                                     AutonomySafetyService safety,
                                     AutonomousManagementRunner runner,
                                     WorkplaceContinuityService workplace,
                                     WorkplaceDashboardAuthService authentication) {
        this.management = management;
        this.safety = safety;
        this.runner = runner;
        this.workplace = workplace;
        this.authentication = authentication;
    }

    @GetMapping
    public ControlView view(@PathVariable String objectiveId,
                            @RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAuthenticated(authorization);
        return new ControlView(management.get(objectiveId), safety.ensureObjective(objectiveId));
    }

    @PostMapping("/pause")
    public ControlView pause(@PathVariable String objectiveId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader("X-Metatron-Actor") String actor,
            @RequestHeader("X-Metatron-Authority") String authorityReference) {
        requireControlAuthority(objectiveId, authorization, actor, authorityReference);
        Instant now = Instant.now();
        var state = safety.pause(objectiveId, authorityReference, now);
        ManagementObjective objective = management.get(objectiveId);
        if (!objective.terminal() && objective.status() != ManagementObjective.Status.PAUSED) {
            management.pauseObjective(objectiveId, objective.ownerWorkerId(),
                    "control-paused:actor=" + actor + ":authority=" + authorityReference, now);
        }
        return new ControlView(management.get(objectiveId), state);
    }

    @PostMapping("/resume")
    public ControlView resume(@PathVariable String objectiveId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader("X-Metatron-Actor") String actor,
            @RequestHeader("X-Metatron-Authority") String authorityReference) {
        requireControlAuthority(objectiveId, authorization, actor, authorityReference);
        Instant now = Instant.now();
        var state = safety.resume(objectiveId, authorityReference, now);
        ManagementObjective objective = management.get(objectiveId);
        if (objective.status() == ManagementObjective.Status.PAUSED
                || objective.status() == ManagementObjective.Status.BLOCKED
                || objective.status() == ManagementObjective.Status.ESCALATED) {
            management.resumeObjective(objectiveId, objective.ownerWorkerId(),
                    "control-resume:actor=" + actor + ":authority=" + authorityReference, now);
        }
        runner.wake();
        return new ControlView(management.get(objectiveId), state);
    }

    @PostMapping("/cancel")
    public ControlView cancel(@PathVariable String objectiveId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader("X-Metatron-Actor") String actor,
            @RequestHeader("X-Metatron-Authority") String authorityReference) {
        requireControlAuthority(objectiveId, authorization, actor, authorityReference);
        Instant now = Instant.now();
        var state = safety.cancel(objectiveId, authorityReference, now);
        ManagementObjective objective = management.get(objectiveId);
        if (!objective.terminal()) {
            management.cancelObjective(objectiveId, objective.ownerWorkerId(),
                    "control-cancelled:actor=" + actor + ":authority=" + authorityReference, now);
        }
        return new ControlView(management.get(objectiveId), state);
    }

    @PostMapping("/authority/revoke")
    public ControlView revoke(@PathVariable String objectiveId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader("X-Metatron-Actor") String actor,
            @RequestHeader("X-Metatron-Authority") String authorityReference) {
        requireControlAuthority(objectiveId, authorization, actor, authorityReference);
        Instant now = Instant.now();
        var state = safety.revokeAuthority(objectiveId, authorityReference, now);
        ManagementObjective objective = management.get(objectiveId);
        if (!objective.terminal() && objective.status() != ManagementObjective.Status.BLOCKED) {
            management.markBlocked(objectiveId, objective.ownerWorkerId(),
                    "authority-revoked:actor=" + actor + ":authority=" + authorityReference, now);
        }
        return new ControlView(management.get(objectiveId), state);
    }

    @PostMapping("/authority/restore")
    public ControlView restore(@PathVariable String objectiveId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader("X-Metatron-Actor") String actor,
            @RequestHeader("X-Metatron-Authority") String authorityReference) {
        requireControlAuthority(objectiveId, authorization, actor, authorityReference);
        Instant now = Instant.now();
        var state = safety.restoreAuthority(objectiveId, authorityReference, now);
        ManagementObjective objective = management.get(objectiveId);
        if (objective.status() == ManagementObjective.Status.BLOCKED
                || objective.status() == ManagementObjective.Status.ESCALATED) {
            management.resumeObjective(objectiveId, objective.ownerWorkerId(),
                    "authority-restored:actor=" + actor + ":authority=" + authorityReference, now);
        }
        runner.wake();
        return new ControlView(management.get(objectiveId), state);
    }

    @PostMapping("/amend")
    public ControlView amend(@PathVariable String objectiveId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader("X-Metatron-Actor") String actor,
            @RequestHeader("X-Metatron-Authority") String authorityReference,
            @RequestBody AmendmentCommand command) {
        requireControlAuthority(objectiveId, authorization, actor, authorityReference);
        Instant now = Instant.now();
        safety.amend(objectiveId, command.amendmentReference(),
                "actor=" + actor + ";" + command.detail(), authorityReference, now);
        ManagementObjective objective = management.get(objectiveId);
        if (!objective.terminal()) {
            management.requestReplan(objectiveId, objective.ownerWorkerId(),
                    "amendment=" + command.amendmentReference() + ";detail=" + command.detail(), now);
        }
        var state = safety.resume(objectiveId, authorityReference, now);
        runner.wake();
        return new ControlView(management.get(objectiveId), state);
    }

    @PostMapping("/resources")
    public ControlView configureResources(@PathVariable String objectiveId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader("X-Metatron-Actor") String actor,
            @RequestHeader("X-Metatron-Authority") String authorityReference,
            @RequestBody ResourceEnvelopeCommand command) {
        requireControlAuthority(objectiveId, authorization, actor, authorityReference);
        Instant now = Instant.now();
        var state = safety.configureEnvelope(objectiveId, command.maxCostUnits(),
                command.maxDispatchAttempts(), command.deadline(), command.maxRisk(),
                authorityReference, now);
        ManagementObjective objective = management.get(objectiveId);
        if (objective.status() == ManagementObjective.Status.BLOCKED) {
            AutonomousObjectiveWork work = management.findAutonomousWork(objectiveId).orElse(null);
            if (work != null && work.blocker().contains("autonomy-safety-gate:")) {
                management.resumeObjective(objectiveId, objective.ownerWorkerId(),
                        "resource-envelope-updated:actor=" + actor + ":authority=" + authorityReference, now);
                runner.wake();
            }
        }
        return new ControlView(management.get(objectiveId), state);
    }

    private WorkplaceContinuityRecord requireControlAuthority(String objectiveId, String authorization,
                                                               String actor, String authorityReference) {
        requireAuthenticated(authorization);
        if (actor == null || actor.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "control actor required");
        }
        if (authorityReference == null || authorityReference.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "external authority reference required");
        }
        WorkplaceContinuityRecord continuity;
        try {
            continuity = workplace.continuity(objectiveId);
        } catch (IllegalArgumentException missing) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "accepted Objective has no durable Workplace authority provenance");
        }
        String normalizedActor = actor.startsWith("human:") ? actor.substring("human:".length()) : actor;
        if (!continuity.humanId().equals(normalizedActor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "control actor mismatch");
        }
        if (!constantTimeEquals(continuity.requestAdmissionRef(), authorityReference.trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "control authority reference mismatch");
        }
        management.get(objectiveId);
        return continuity;
    }

    private void requireAuthenticated(String authorization) {
        if (!authentication.valid(bearer(authorization))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Workplace authentication required");
        }
    }

    private static String bearer(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    public record AmendmentCommand(String amendmentReference, String detail) {}
    public record ResourceEnvelopeCommand(double maxCostUnits, int maxDispatchAttempts,
                                          Instant deadline, AutonomySafetyState.RiskLevel maxRisk) {}
    public record ControlView(ManagementObjective objective, AutonomySafetyState safety) {}
}
