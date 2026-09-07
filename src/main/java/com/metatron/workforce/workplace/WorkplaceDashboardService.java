package com.metatron.workforce.workplace;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.management.ManagementAutonomyService;
import com.metatron.workforce.management.AutonomousObjectiveWork;
import com.metatron.workforce.management.ManagementObjective;
import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import com.metatron.workforce.work.InstitutionalWork;
import com.metatron.workforce.work.WorkService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class WorkplaceDashboardService {
    private final WorkforceCoreService core;
    private final ManagementAutonomyService management;
    private final WorkService work;

    public WorkplaceDashboardService(WorkforceCoreService core, ManagementAutonomyService management, WorkService work) {
        this.core = core; this.management = management; this.work = work;
    }

    public Dashboard dashboard() {
        List<WorkerView> workers = core.allWorkers().stream().map(w -> new WorkerView(
                w.workerId(), w.status().name(), core.participations(w.workerId()), core.capabilities(w.workerId()),
                core.qualifications(w.workerId()), core.availability(w.workerId()).orElse(null), core.assignments(w.workerId()))).toList();
        List<ManagementObjective> objectives = management.allObjectives();
        List<InstitutionalWork> workItems = work.all();
        List<ObjectivePulse> objectivePulse = objectives.stream().map(this::pulse).toList();
        List<Alert> alerts = new ArrayList<>();
        objectives.stream().filter(o -> o.status() == ManagementObjective.Status.BLOCKED || o.status() == ManagementObjective.Status.ESCALATED)
                .forEach(o -> alerts.add(new Alert("OBJECTIVE", o.status().name(), o.objectiveId(), o.description(), o.updatedAt())));
        workItems.stream().filter(w -> w.status() == InstitutionalWork.Status.BLOCKED)
                .forEach(w -> alerts.add(new Alert("WORK", "BLOCKED", w.workId(), w.description(), w.updatedAt())));
        core.allAssignments().stream().filter(a -> a.status() == WorkforceCoreService.AssignmentStatus.BLOCKED)
                .forEach(a -> alerts.add(new Alert("ASSIGNMENT", "BLOCKED", a.assignmentId(), a.description(), a.createdAt())));
        workers.stream().filter(w -> w.availability() != null && (!w.availability().available() || w.availability().capacity() <= 0))
                .forEach(w -> alerts.add(new Alert("WORKER", "NO_CAPACITY", w.workerId(), "Worker has no available capacity", w.availability().observedAt())));
        alerts.sort(Comparator.comparing(Alert::at).reversed());
        long activeWorkers = workers.stream().filter(w -> "ACTIVE".equals(w.status())).count();
        long activeObjectives = objectives.stream().filter(o -> !o.terminal()).count();
        long executingObjectives = objectivePulse.stream().filter(p -> "WORKING".equals(p.executionState())).count();
        long staleObjectives = objectivePulse.stream().filter(p -> p.executionState().startsWith("STALE") || p.executionState().startsWith("UNPROVEN")).count();
        long completedObjectives = objectivePulse.stream().filter(p -> "COMPLETED_WITH_EVIDENCE".equals(p.executionState())).count();
        long activeWork = workItems.stream().filter(w -> !w.terminal()).count();
        long totalTasks = objectivePulse.stream().mapToLong(ObjectivePulse::totalWork).sum();
        long completedTasks = objectivePulse.stream().mapToLong(ObjectivePulse::completedWork).sum();
        long runningTasks = objectivePulse.stream().mapToLong(p -> p.workItems().stream().filter(w -> "RUNNING".equals(w.state())).count()).sum();
        long blockedTasks = objectivePulse.stream().mapToLong(p -> p.workItems().stream().filter(w -> "BLOCKED".equals(w.state())).count()).sum();
        long waitingTasks = Math.max(0, totalTasks - completedTasks - runningTasks - blockedTasks);
        long blocked = alerts.stream().filter(a -> a.status().contains("BLOCK") || a.status().contains("ESCALAT")).count();
        String sha = System.getenv().getOrDefault("METATRON_COMMIT_SHA", "unknown");
        String env = System.getenv().getOrDefault("METATRON_ENVIRONMENT", "unknown");
        return new Dashboard(Instant.now(), sha, env,
                new Summary(workers.size(), activeWorkers, objectives.size(), activeObjectives, executingObjectives, staleObjectives,
                        completedObjectives, totalTasks, runningTasks, completedTasks, blockedTasks, waitingTasks,
                        workItems.size(), activeWork, core.allAssignments().size(), blocked, alerts.size()),
                workers, objectives, objectivePulse, workItems, core.allAssignments(), alerts,
                management.allEvents().stream().limit(50).toList());
    }

    private ObjectivePulse pulse(ManagementObjective objective) {
        AutonomousObjectiveWork autonomous = management.findAutonomousWork(objective.objectiveId()).orElse(null);
        List<ManagementAutonomyService.ManagementEvent> history = management.history(objective.objectiveId());
        Instant last = history.stream().map(ManagementAutonomyService.ManagementEvent::occurredAt)
                .max(Comparator.naturalOrder()).orElse(objective.updatedAt());
        boolean fresh = Duration.between(last, Instant.now()).compareTo(Duration.ofSeconds(90)) <= 0;
        int total = autonomous == null ? 0 : autonomous.plannedWork().size();
        int completed = autonomous == null ? 0 : autonomous.completedStepIds().size();
        int progress = total == 0 ? (objective.terminal() ? 100 : 0) : (int)Math.floor(completed * 100.0 / total);
        boolean executionStarted = history.stream().anyMatch(e -> e.type() == ManagementAutonomyService.ManagementEvent.Type.EXECUTION_STARTED);
        String executionState;
        String blocker = autonomous == null ? "" : autonomous.blocker();
        int evidence = autonomous == null ? objective.evidenceRefs().size() : autonomous.evidenceReferences().size();
        List<WorkItemPulse> steps = autonomous == null ? List.of() : autonomous.plannedWork().stream().map(step -> {
            boolean done = autonomous.completedStepIds().contains(step.stepId());
            boolean depsDone = autonomous.completedStepIds().containsAll(step.dependsOn());
            String state;
            if (done) state = "COMPLETED";
            else if (autonomous.status() == AutonomousObjectiveWork.Status.BLOCKED && depsDone) state = "BLOCKED";
            else if (!depsDone) state = "WAITING";
            else if (autonomous.status() == AutonomousObjectiveWork.Status.EXECUTING) state = fresh ? "RUNNING" : "STALE";
            else state = "READY";
            return new WorkItemPulse(step.stepId(), step.objective(), step.target(), step.requiredCapability(),
                    state, step.dependsOn(), step.acceptanceCriteria(), step.evidenceRequirements());
        }).toList();
        List<EventPulse> recent = history.stream()
                .sorted(Comparator.comparing(ManagementAutonomyService.ManagementEvent::occurredAt).reversed())
                .limit(20)
                .map(e -> new EventPulse(e.type().name(), e.actorWorkerId(), e.detail(), e.occurredAt()))
                .toList();
        List<String> evidenceRefs = autonomous == null ? objective.evidenceRefs() : autonomous.evidenceReferences();
        if (autonomous != null && autonomous.status() == AutonomousObjectiveWork.Status.COMPLETED) {
            executionState = evidence > 0 ? "COMPLETED_WITH_EVIDENCE" : "UNPROVEN_COMPLETION";
        } else if (autonomous != null && autonomous.status() == AutonomousObjectiveWork.Status.BLOCKED) {
            executionState = "BLOCKED";
        } else if (autonomous != null && autonomous.status() == AutonomousObjectiveWork.Status.EXECUTING) {
            executionState = !executionStarted ? "UNPROVEN_EXECUTION" : (fresh ? "WORKING" : "STALE_EXECUTION");
        } else {
            executionState = autonomous == null ? "NOT_MATERIALIZED" : autonomous.status().name();
        }
        String staffing = objective.assignmentRefs().isEmpty() ? "UNASSIGNED" : "ASSIGNED";
        return new ObjectivePulse(objective.objectiveId(), objective.description(), objective.status().name(),
                objective.ownerWorkerId(), staffing, executionState, progress, completed, total, evidence,
                blocker, last, objective.updatedAt(), steps, recent, evidenceRefs);
    }

    public record Summary(long workers, long activeWorkers, long objectives, long activeObjectives,
                          long executingObjectives, long staleObjectives, long completedObjectives,
                          long totalTasks, long runningTasks, long completedTasks, long blockedTasks, long waitingTasks,
                          long workItems, long activeWork, long assignments, long blocked, long alerts) {}
    public record WorkerView(String workerId, String status, List<WorkforceCoreService.Participation> participations,
                             List<WorkforceCoreService.Capability> capabilities, List<WorkforceCoreService.Qualification> qualifications,
                             WorkforceCoreService.Availability availability, List<WorkforceCoreService.Assignment> assignments) {}
    public record WorkItemPulse(String stepId, String objective, String target, String requiredCapability,
                                String state, List<String> dependsOn, List<String> acceptanceCriteria,
                                List<String> evidenceRequirements) {}
    public record EventPulse(String type, String actor, String detail, Instant at) {}
    public record ObjectivePulse(String objectiveId, String summary, String objectiveStatus, String ownerWorkerId,
                                 String staffingState, String executionState, int progressPercent,
                                 int completedWork, int totalWork, int evidenceCount, String blocker,
                                 Instant lastActivityAt, Instant objectiveUpdatedAt,
                                 List<WorkItemPulse> workItems, List<EventPulse> recentEvents, List<String> evidenceReferences) {}
    public record Alert(String type, String status, String ref, String detail, Instant at) {}
    public record Dashboard(Instant generatedAt, String revision, String environment, Summary summary,
                            List<WorkerView> workers, List<ManagementObjective> objectives,
                            List<ObjectivePulse> objectivePulse, List<InstitutionalWork> work, List<WorkforceCoreService.Assignment> assignments,
                            List<Alert> alerts, List<ManagementAutonomyService.ManagementEvent> recentEvents) {}
}
