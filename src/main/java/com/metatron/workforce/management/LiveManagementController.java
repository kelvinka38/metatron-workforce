package com.metatron.workforce.management;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * Internal activation surface for already-recognized institutional actors and manager Workers.
 * The caller is expected to have been authenticated/admitted/authorized upstream. This surface
 * preserves attribution and authority references but does not mint Participants, Workers, Roles,
 * Authority or Authorization.
 */
@RestController
@RequestMapping("/workforce/management")
public class LiveManagementController {
    private final ManagementAutonomyService management;

    public LiveManagementController(ManagementAutonomyService management) {
        this.management = management;
    }

    @PostMapping("/objectives")
    @ResponseStatus(HttpStatus.CREATED)
    public ManagementObjective appointObjective(@RequestHeader("X-Metatron-Actor") String actor,
            @RequestBody ObjectiveCommand command) {
        requireActor(actor, command.initiatingActorId());
        requireText(command.authorityReference(), "authorityReference");
        requireText(command.authorizationReference(), "authorizationReference");
        return management.acceptObjective(command.objectiveId(), command.ownerWorkerId(),
                command.organizationContextId(), command.description(), command.initiatingActorId(),
                command.authorityReference(), command.authorizationReference(), Instant.now());
    }

    @PostMapping("/objectives/{objectiveId}/capacity")
    public StaffingAssessment assessCapacity(@PathVariable String objectiveId,
            @RequestHeader("X-Metatron-Actor") String actor, @RequestBody CapacityCommand command) {
        return new StaffingAssessment(management.assessCapacity(objectiveId, actor, command.requiredCapability(),
                command.requiredCapacity(), command.availableCapacity(), Instant.now()).orElse(null));
    }

    @PostMapping("/objectives/{objectiveId}/assignments")
    public ManagementObjective referenceAssignment(@PathVariable String objectiveId,
            @RequestHeader("X-Metatron-Actor") String actor, @RequestBody AssignmentCommand command) {
        return management.addAssignmentReference(objectiveId, actor, command.assignmentReference(), Instant.now());
    }

    @PostMapping("/objectives/{objectiveId}/blocked")
    public ManagementObjective block(@PathVariable String objectiveId,
            @RequestHeader("X-Metatron-Actor") String actor, @RequestBody ReasonCommand command) {
        return management.markBlocked(objectiveId, actor, command.reason(), Instant.now());
    }

    @PostMapping("/objectives/{objectiveId}/recover")
    public ManagementObjective recover(@PathVariable String objectiveId,
            @RequestHeader("X-Metatron-Actor") String actor, @RequestBody RecoveryCommand command) {
        return management.recoverLocally(objectiveId, actor, command.recoveryPlan(), Instant.now());
    }

    @PostMapping("/objectives/{objectiveId}/escalate")
    public ManagementObjective escalate(@PathVariable String objectiveId,
            @RequestHeader("X-Metatron-Actor") String actor, @RequestBody ReasonCommand command) {
        return management.escalate(objectiveId, actor, command.reason(), Instant.now());
    }

    @PostMapping("/objectives/{objectiveId}/deliver")
    public ManagementObjective deliver(@PathVariable String objectiveId,
            @RequestHeader("X-Metatron-Actor") String actor, @RequestBody DeliveryCommand command) {
        return management.deliver(objectiveId, actor, command.evidenceReferences(), Instant.now());
    }

    @GetMapping("/objectives/{objectiveId}")
    public ObjectiveView objective(@PathVariable String objectiveId) {
        return view(management.get(objectiveId));
    }

    @GetMapping("/objectives")
    public List<ObjectiveView> objectives() {
        return management.allObjectives().stream().map(this::view).toList();
    }

    private ObjectiveView view(ManagementObjective objective) {
        return new ObjectiveView(objective,
                management.findAutonomousWork(objective.objectiveId()).orElse(null),
                management.history(objective.objectiveId()));
    }

    private static void requireActor(String actor, String expected) {
        if (actor == null || actor.isBlank() || expected == null || !actor.equals(expected)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "caller attribution mismatch");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must not be blank");
        }
    }

    public record ObjectiveCommand(String objectiveId, String ownerWorkerId, String organizationContextId,
                                   String description, String initiatingActorId, String authorityReference,
                                   String authorizationReference) {}
    public record CapacityCommand(String requiredCapability, double requiredCapacity, double availableCapacity) {}
    public record AssignmentCommand(String assignmentReference) {}
    public record ReasonCommand(String reason) {}
    public record RecoveryCommand(String recoveryPlan) {}
    public record DeliveryCommand(List<String> evidenceReferences) {}
    public record StaffingAssessment(StaffingNeed staffingNeed) {}
    public record ObjectiveView(ManagementObjective objective, AutonomousObjectiveWork autonomousWork,
                                List<ManagementAutonomyService.ManagementEvent> history) {}
}
