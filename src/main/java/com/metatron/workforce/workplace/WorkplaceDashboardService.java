package com.metatron.workforce.workplace;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.management.ManagementAutonomyService;
import com.metatron.workforce.management.ManagementObjective;
import com.metatron.workforce.work.InstitutionalWork;
import com.metatron.workforce.work.WorkService;
import org.springframework.stereotype.Service;

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
        long activeWork = workItems.stream().filter(w -> !w.terminal()).count();
        long blocked = alerts.stream().filter(a -> a.status().contains("BLOCK") || a.status().contains("ESCALAT")).count();
        String sha = System.getenv().getOrDefault("METATRON_COMMIT_SHA", "unknown");
        String env = System.getenv().getOrDefault("METATRON_ENVIRONMENT", "unknown");
        return new Dashboard(Instant.now(), sha, env,
                new Summary(workers.size(), activeWorkers, objectives.size(), activeObjectives, workItems.size(), activeWork, core.allAssignments().size(), blocked, alerts.size()),
                workers, objectives, workItems, core.allAssignments(), alerts,
                management.allEvents().stream().limit(50).toList());
    }

    public record Summary(long workers, long activeWorkers, long objectives, long activeObjectives, long workItems,
                          long activeWork, long assignments, long blocked, long alerts) {}
    public record WorkerView(String workerId, String status, List<WorkforceCoreService.Participation> participations,
                             List<WorkforceCoreService.Capability> capabilities, List<WorkforceCoreService.Qualification> qualifications,
                             WorkforceCoreService.Availability availability, List<WorkforceCoreService.Assignment> assignments) {}
    public record Alert(String type, String status, String ref, String detail, Instant at) {}
    public record Dashboard(Instant generatedAt, String revision, String environment, Summary summary,
                            List<WorkerView> workers, List<ManagementObjective> objectives,
                            List<InstitutionalWork> work, List<WorkforceCoreService.Assignment> assignments,
                            List<Alert> alerts, List<ManagementAutonomyService.ManagementEvent> recentEvents) {}
}
