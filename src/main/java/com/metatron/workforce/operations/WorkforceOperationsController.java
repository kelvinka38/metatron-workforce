package com.metatron.workforce.operations;

import com.metatron.workforce.phase5.*;
import com.metatron.workforce.phase7.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/workforce/operations")
public class WorkforceOperationsController {
    private final WorkScheduleService schedules;
    private final StaffingService staffing;
    private final ReviewService reviews;

    public WorkforceOperationsController(WorkScheduleService schedules, StaffingService staffing, ReviewService reviews) {
        this.schedules = schedules; this.staffing = staffing; this.reviews = reviews;
    }

    @PostMapping("/schedules") @ResponseStatus(HttpStatus.CREATED)
    public WorkSchedule schedule(@RequestBody ScheduleCommand c) {
        return schedules.schedule(new WorkSchedule(c.scheduleId(), c.assignmentRef(), c.workerId(), c.start(), c.end(),
                c.committedCapacity(), WorkSchedule.Status.PLANNED, c.evidenceRef()), c.workerCapacity());
    }

    @PostMapping("/schedules/{id}/status/{status}")
    public WorkSchedule scheduleStatus(@PathVariable String id, @PathVariable WorkSchedule.Status status) {
        return schedules.transition(id, status);
    }

    @GetMapping("/schedules/{id}")
    public WorkSchedule schedule(@PathVariable String id) { return schedules.get(id); }

    @PostMapping("/staffing") @ResponseStatus(HttpStatus.CREATED)
    public StaffingRequest staffing(@RequestBody StaffingCommand c) {
        return staffing.detect(c.staffingRequestId(), c.objectiveRef(), c.organizationContextId(), c.requestedByWorkerId(),
                c.capabilityRef(), c.requiredCapacity(), c.availableCapacity(), Instant.now());
    }

    @PostMapping("/staffing/{id}/proposal")
    public StaffingRequest staffingProposal(@PathVariable String id, @RequestBody RefCommand c) {
        return staffing.propose(id, c.ref(), Instant.now());
    }

    @PostMapping("/staffing/{id}/resolve")
    public StaffingRequest staffingResolve(@PathVariable String id, @RequestBody RefCommand c) {
        return staffing.resolve(id, c.ref(), Instant.now());
    }

    @GetMapping("/staffing/{id}")
    public StaffingRequest staffing(@PathVariable String id) { return staffing.get(id); }

    @PostMapping("/reviews") @ResponseStatus(HttpStatus.CREATED)
    public InstitutionalReview review(@RequestBody ReviewCommand c) {
        return reviews.record(c.reviewId(), c.subjectRef(), c.reviewerWorkerId(), c.reviewerRoleRef(), c.authorityRef(),
                c.decision(), c.rationale(), c.evidenceRefs(), Instant.now());
    }

    @GetMapping("/reviews/{id}")
    public InstitutionalReview review(@PathVariable String id) { return reviews.get(id); }

    public record ScheduleCommand(String scheduleId, String assignmentRef, String workerId, Instant start, Instant end,
                                  double committedCapacity, double workerCapacity, String evidenceRef) {}
    public record StaffingCommand(String staffingRequestId, String objectiveRef, String organizationContextId,
                                  String requestedByWorkerId, String capabilityRef, double requiredCapacity, double availableCapacity) {}
    public record RefCommand(String ref) {}
    public record ReviewCommand(String reviewId, String subjectRef, String reviewerWorkerId, String reviewerRoleRef,
                                String authorityRef, InstitutionalReview.Decision decision, String rationale, List<String> evidenceRefs) {}
}
