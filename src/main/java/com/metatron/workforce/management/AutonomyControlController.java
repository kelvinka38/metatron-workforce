package com.metatron.workforce.management;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

/**
 * Internal control surface. Authentication/authorization are resolved upstream; Workforce preserves
 * actor/authority references and enforces resulting operational controls without minting authority.
 */
@RestController
@RequestMapping("/workforce/management/objectives/{objectiveId}/control")
public final class AutonomyControlController {
    private final ManagementAutonomyService management;
    private final AutonomySafetyService safety;
    private final AutonomousManagementRunner runner;

    public AutonomyControlController(ManagementAutonomyService management,
                                     AutonomySafetyService safety,
                                     AutonomousManagementRunner runner) {
        this.management = management;
        this.safety = safety;
        this.runner = runner;
    }

    @GetMapping
    public ControlView view(@PathVariable String objectiveId) {
        return new ControlView(management.get(objectiveId), safety.ensureObjective(objectiveId));
    }

    @PostMapping("/pause")
    public ControlView pause(@PathVariable String objectiveId,
            @RequestHeader("X-Metatron-Actor") String actor,
            @RequestHeader("X-Metatron-Authority") String authorityReference) {
        requireControlActor(objectiveId, actor); requireAuthority(authorityReference);
        var state = safety.pause(objectiveId, authorityReference, Instant.now());
        blockIfActive(objectiveId, "control-paused:actor=" + actor + ":authority=" + authorityReference);
        return new ControlView(management.get(objectiveId), state);
    }

    @PostMapping("/resume")
    public ControlView resume(@PathVariable String objectiveId,
            @RequestHeader("X-Metatron-Actor") String actor,
            @RequestHeader("X-Metatron-Authority") String authorityReference) {
        requireControlActor(objectiveId, actor); requireAuthority(authorityReference);
        var state = safety.resume(objectiveId, authorityReference, Instant.now());
        recoverIfBlocked(objectiveId, "control-resume:actor=" + actor + ":authority=" + authorityReference);
        runner.wake();
        return new ControlView(management.get(objectiveId), state);
    }

    @PostMapping("/cancel")
    public ControlView cancel(@PathVariable String objectiveId,
            @RequestHeader("X-Metatron-Actor") String actor,
            @RequestHeader("X-Metatron-Authority") String authorityReference) {
        requireControlActor(objectiveId, actor); requireAuthority(authorityReference);
        var state = safety.cancel(objectiveId, authorityReference, Instant.now());
        blockIfActive(objectiveId, "control-cancelled:actor=" + actor + ":authority=" + authorityReference);
        return new ControlView(management.get(objectiveId), state);
    }

    @PostMapping("/authority/revoke")
    public ControlView revoke(@PathVariable String objectiveId,
            @RequestHeader("X-Metatron-Actor") String actor,
            @RequestHeader("X-Metatron-Authority") String authorityReference) {
        requireControlActor(objectiveId, actor); requireAuthority(authorityReference);
        var state = safety.revokeAuthority(objectiveId, authorityReference, Instant.now());
        blockIfActive(objectiveId, "authority-revoked:actor=" + actor + ":authority=" + authorityReference);
        return new ControlView(management.get(objectiveId), state);
    }

    @PostMapping("/authority/restore")
    public ControlView restore(@PathVariable String objectiveId,
            @RequestHeader("X-Metatron-Actor") String actor,
            @RequestHeader("X-Metatron-Authority") String authorityReference) {
        requireControlActor(objectiveId, actor); requireAuthority(authorityReference);
        var state = safety.restoreAuthority(objectiveId, authorityReference, Instant.now());
        recoverIfBlocked(objectiveId, "authority-restored:actor=" + actor + ":authority=" + authorityReference);
        runner.wake();
        return new ControlView(management.get(objectiveId), state);
    }

    @PostMapping("/amend")
    public ControlView amend(@PathVariable String objectiveId,
            @RequestHeader("X-Metatron-Actor") String actor,
            @RequestHeader("X-Metatron-Authority") String authorityReference,
            @RequestBody AmendmentCommand command) {
        requireControlActor(objectiveId, actor); requireAuthority(authorityReference);
        var state = safety.amend(objectiveId, command.amendmentReference(), command.detail(),
                authorityReference, Instant.now());
        blockIfActive(objectiveId, "amendment-replan-required:" + command.amendmentReference());
        return new ControlView(management.get(objectiveId), state);
    }

    @PostMapping("/resources")
    public ControlView configureResources(@PathVariable String objectiveId,
            @RequestHeader("X-Metatron-Actor") String actor,
            @RequestHeader("X-Metatron-Authority") String authorityReference,
            @RequestBody ResourceEnvelopeCommand command) {
        requireControlActor(objectiveId, actor); requireAuthority(authorityReference);
        var state = safety.configureEnvelope(objectiveId, command.maxCostUnits(),
                command.maxDispatchAttempts(), command.deadline(), command.maxRisk(),
                authorityReference, Instant.now());
        return new ControlView(management.get(objectiveId), state);
    }

    private void blockIfActive(String objectiveId, String reason) {
        ManagementObjective objective = management.get(objectiveId);
        if (!objective.terminal() && objective.status() != ManagementObjective.Status.BLOCKED
                && objective.status() != ManagementObjective.Status.ESCALATED) {
            management.markBlocked(objectiveId, objective.ownerWorkerId(), reason, Instant.now());
        }
    }

    private void recoverIfBlocked(String objectiveId, String reason) {
        ManagementObjective objective = management.get(objectiveId);
        if (objective.status() == ManagementObjective.Status.BLOCKED
                || objective.status() == ManagementObjective.Status.ESCALATED) {
            management.recoverLocally(objectiveId, objective.ownerWorkerId(), reason, Instant.now());
        }
    }

    private void requireControlActor(String objectiveId, String actor) {
        if (actor == null || actor.isBlank()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "control actor required");
        ManagementObjective objective = management.get(objectiveId);
        boolean owner = objective.ownerWorkerId().equals(actor);
        boolean human = management.findAutonomousWork(objectiveId)
                .map(work -> work.humanId().equals(actor) || ("human:" + work.humanId()).equals(actor))
                .orElse(false);
        if (!owner && !human) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "control actor mismatch");
    }

    private static void requireAuthority(String authorityReference) {
        if (authorityReference == null || authorityReference.isBlank())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "external authority reference required");
    }

    public record AmendmentCommand(String amendmentReference, String detail) {}
    public record ResourceEnvelopeCommand(double maxCostUnits, int maxDispatchAttempts,
                                          Instant deadline, AutonomySafetyState.RiskLevel maxRisk) {}
    public record ControlView(ManagementObjective objective, AutonomySafetyState safety) {}
}
