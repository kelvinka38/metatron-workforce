package com.metatron.workforce.core;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/workforce/core")
public class WorkforceCoreController {
    private final WorkforceCoreService core;
    public WorkforceCoreController(WorkforceCoreService core) { this.core = core; }

    @PostMapping("/participants") @ResponseStatus(HttpStatus.CREATED)
    public WorkforceCoreService.Participant participant(@RequestBody ParticipantCommand c) {
        return core.recognizeParticipant(c.participantId(), c.type(), c.provenanceRef());
    }
    @PostMapping("/workers") @ResponseStatus(HttpStatus.CREATED)
    public WorkforceCoreService.Worker worker(@RequestBody WorkerCommand c) { return core.admitWorker(c.workerId(), c.participantId()); }
    @PostMapping("/workers/{id}/status/{status}")
    public WorkforceCoreService.Worker workerStatus(@PathVariable String id, @PathVariable WorkforceCoreService.WorkerStatus status) {
        return core.setWorkerStatus(id, status);
    }
    @PostMapping("/participations") @ResponseStatus(HttpStatus.CREATED)
    public WorkforceCoreService.Participation participation(@RequestBody ParticipationCommand c) {
        return core.participate(c.participationId(), c.workerId(), c.organizationRef(), c.positionRef(), c.roleRef());
    }
    @PostMapping("/participations/{id}/status/{status}")
    public WorkforceCoreService.Participation participationStatus(@PathVariable String id, @PathVariable WorkforceCoreService.ParticipationStatus status) {
        return core.setParticipationStatus(id, status);
    }
    @PostMapping("/capabilities")
    public WorkforceCoreService.Capability capability(@RequestBody CapabilityCommand c) { return core.attestCapability(c.workerId(), c.capabilityRef(), c.level(), c.evidenceRef()); }
    @PostMapping("/qualifications")
    public WorkforceCoreService.Qualification qualification(@RequestBody QualificationCommand c) { return core.attestQualification(c.workerId(), c.qualificationRef(), c.evidenceRef(), c.validUntil()); }
    @PostMapping("/availability")
    public WorkforceCoreService.Availability availability(@RequestBody AvailabilityCommand c) { return core.setAvailability(c.workerId(), c.available(), c.capacity()); }
    @PostMapping("/assignments") @ResponseStatus(HttpStatus.CREATED)
    public WorkforceCoreService.Assignment assignment(@RequestBody AssignmentCommand c) {
        return core.assign(c.assignmentId(), c.objectiveRef(), c.workerId(), c.participationId(), c.authorityRef(), c.authorizationRef(), c.description());
    }
    @PostMapping("/assignments/{id}/status/{status}")
    public WorkforceCoreService.Assignment transition(@PathVariable String id, @PathVariable WorkforceCoreService.AssignmentStatus status) { return core.transitionAssignment(id, status); }
    @GetMapping("/workers") public List<WorkerView> workers() {
        return core.allWorkers().stream().map(this::view).toList();
    }
    @GetMapping("/workers/active") public List<WorkerView> activeWorkers() {
        return core.allWorkers().stream()
                .filter(w -> w.status() == WorkforceCoreService.WorkerStatus.ACTIVE)
                .map(this::view).toList();
    }
    @GetMapping("/workers/{id}") public WorkerView view(@PathVariable String id) {
        return view(core.worker(id));
    }
    private WorkerView view(WorkforceCoreService.Worker worker) {
        String id = worker.workerId();
        return new WorkerView(worker, core.participations(id), core.assignments(id), core.capabilities(id),
                core.qualifications(id), core.availability(id).orElse(null));
    }

    public record ParticipantCommand(String participantId, WorkforceCoreService.ParticipantType type, String provenanceRef) {}
    public record WorkerCommand(String workerId, String participantId) {}
    public record ParticipationCommand(String participationId, String workerId, String organizationRef, String positionRef, String roleRef) {}
    public record CapabilityCommand(String workerId, String capabilityRef, double level, String evidenceRef) {}
    public record QualificationCommand(String workerId, String qualificationRef, String evidenceRef, Instant validUntil) {}
    public record AvailabilityCommand(String workerId, boolean available, double capacity) {}
    public record AssignmentCommand(String assignmentId, String objectiveRef, String workerId, String participationId, String authorityRef, String authorizationRef, String description) {}
    public record WorkerView(WorkforceCoreService.Worker worker, List<WorkforceCoreService.Participation> participations,
                             List<WorkforceCoreService.Assignment> assignments, List<WorkforceCoreService.Capability> capabilities,
                             List<WorkforceCoreService.Qualification> qualifications, WorkforceCoreService.Availability availability) {}
}
