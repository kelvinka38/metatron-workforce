package com.metatron.workforce.workplace;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.management.ManagementObjective;
import com.metatron.workforce.runtime.RuntimeInstance;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Purpose-built Workplace Control Room projection.
 *
 * This service owns no Worker/Objective/Work/Execution truth. It composes authenticated presentation
 * views from the canonical Workforce dashboard projection, runtime registry and runtime bindings.
 */
@Service
public final class WorkplaceControlRoomService {
    private static final Pattern REPOSITORY = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");

    private final WorkplaceDashboardService dashboard;
    private final WorkforceCoreService core;
    private final WorkerRuntimeProfileBindingService runtimeProfiles;
    private final RuntimeRegistry runtimes;

    public WorkplaceControlRoomService(
            WorkplaceDashboardService dashboard,
            WorkforceCoreService core,
            WorkerRuntimeProfileBindingService runtimeProfiles,
            RuntimeRegistry runtimes) {
        this.dashboard = dashboard;
        this.core = core;
        this.runtimeProfiles = runtimeProfiles;
        this.runtimes = runtimes;
    }

    public ControlRoomSnapshot snapshot() {
        WorkplaceDashboardService.Dashboard source = dashboard.dashboard();
        List<TaskView> tasks = tasks(source);
        List<ProjectView> projects = projects(source, tasks);
        return new ControlRoomSnapshot(source.generatedAt(), source.revision(), source.environment(),
                source.summary(), workerSummaries(source), source.objectivePulse(), tasks, projects,
                source.alerts(), source.recentEvents());
    }

    public WorkerDetail worker(String workerId) {
        WorkplaceDashboardService.Dashboard source = dashboard.dashboard();
        WorkplaceDashboardService.WorkerView worker = source.workers().stream()
                .filter(candidate -> candidate.workerId().equals(workerId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown worker: " + workerId));

        WorkerRuntimeProfileBindingService.Binding binding = runtimeProfiles.find(workerId).orElse(null);
        RuntimeInstance runtime = runtimes.runningForWorker(workerId).orElse(null);

        Set<String> objectiveIds = new LinkedHashSet<>();
        worker.assignments().forEach(assignment -> objectiveIds.add(assignment.objectiveRef()));
        source.objectivePulse().stream()
                .filter(objective -> workerId.equals(objective.ownerWorkerId()))
                .forEach(objective -> objectiveIds.add(objective.objectiveId()));

        List<WorkplaceDashboardService.ObjectivePulse> objectives = source.objectivePulse().stream()
                .filter(objective -> objectiveIds.contains(objective.objectiveId())
                        || objective.actionRecords().stream().anyMatch(action -> workerId.equals(action.workerId())))
                .sorted(Comparator.comparing(WorkplaceDashboardService.ObjectivePulse::lastActivityAt).reversed())
                .toList();

        List<TaskView> workerTasks = tasks(source).stream()
                .filter(task -> workerId.equals(task.performer()) || objectiveIds.contains(task.objectiveId()))
                .toList();

        List<WorkplaceDashboardService.ActionPulse> actions = source.objectivePulse().stream()
                .flatMap(objective -> objective.actionRecords().stream())
                .filter(action -> workerId.equals(action.workerId()))
                .sorted(Comparator.comparing(WorkplaceDashboardService.ActionPulse::recordedAt).reversed())
                .limit(100)
                .toList();

        String primaryRole = worker.participations().stream()
                .filter(participation -> participation.status() == WorkforceCoreService.ParticipationStatus.ACTIVE)
                .map(WorkforceCoreService.Participation::roleRef)
                .findFirst().orElse("");

        return new WorkerDetail(
                worker.workerId(), worker.status(), primaryRole,
                worker.participations(), worker.capabilities(), worker.qualifications(),
                worker.availability(), assignmentViews(worker.assignments()),
                binding == null ? "" : binding.profile().profileRef(),
                binding == null ? List.of() : binding.profile().actionRefs().stream().sorted().toList(),
                runtime == null ? "" : runtime.runtimeId(),
                runtime == null ? "NOT_RUNNING" : runtime.state().name(),
                runtime == null ? null : runtime.createdAt(),
                objectives, workerTasks, actions);
    }

    public String primaryRole(String workerId) {
        return core.participations(workerId).stream()
                .filter(participation -> participation.status() == WorkforceCoreService.ParticipationStatus.ACTIVE)
                .map(WorkforceCoreService.Participation::roleRef)
                .findFirst().orElse("");
    }

    public List<TaskView> tasks() {
        return tasks(dashboard.dashboard());
    }

    public List<ProjectView> projects() {
        WorkplaceDashboardService.Dashboard source = dashboard.dashboard();
        return projects(source, tasks(source));
    }

    private List<WorkerSummary> workerSummaries(WorkplaceDashboardService.Dashboard source) {
        return source.workers().stream().map(worker -> {
            String role = worker.participations().stream()
                    .filter(participation -> participation.status() == WorkforceCoreService.ParticipationStatus.ACTIVE)
                    .map(WorkforceCoreService.Participation::roleRef)
                    .findFirst().orElse("");
            String profile = runtimeProfiles.find(worker.workerId())
                    .map(binding -> binding.profile().profileRef()).orElse("");
            RuntimeInstance runtime = runtimes.runningForWorker(worker.workerId()).orElse(null);
            long completed = source.objectivePulse().stream()
                    .filter(objective -> objective.actionRecords().stream()
                            .anyMatch(action -> worker.workerId().equals(action.workerId())))
                    .filter(objective -> "COMPLETED_WITH_EVIDENCE".equals(objective.executionState()))
                    .count();
            long active = source.objectivePulse().stream()
                    .filter(objective -> worker.assignments().stream()
                            .anyMatch(assignment -> assignment.objectiveRef().equals(objective.objectiveId()))
                            || worker.workerId().equals(objective.ownerWorkerId()))
                    .filter(objective -> !"COMPLETED_WITH_EVIDENCE".equals(objective.executionState()))
                    .count();
            return new WorkerSummary(
                    worker.workerId(), worker.status(), role,
                    worker.availability() != null && worker.availability().available(),
                    worker.availability() == null ? 0.0 : worker.availability().capacity(),
                    worker.assignments().size(), active, completed,
                    profile,
                    runtime == null ? "" : runtime.runtimeId(),
                    runtime == null ? "NOT_RUNNING" : runtime.state().name());
        }).sorted(Comparator.comparing(WorkerSummary::workerId)).toList();
    }

    private static List<AssignmentView> assignmentViews(List<WorkforceCoreService.Assignment> assignments) {
        return assignments.stream().map(assignment -> new AssignmentView(
                assignment.assignmentId(), assignment.objectiveRef(), assignment.description(),
                assignment.status().name(), assignment.createdAt())).toList();
    }

    private List<TaskView> tasks(WorkplaceDashboardService.Dashboard source) {
        Map<String, ManagementObjective> objectives = new LinkedHashMap<>();
        source.objectives().forEach(objective -> objectives.put(objective.objectiveId(), objective));
        List<TaskView> tasks = new ArrayList<>();
        for (WorkplaceDashboardService.ObjectivePulse objective : source.objectivePulse()) {
            ManagementObjective managementObjective = objectives.get(objective.objectiveId());
            String projectId = projectId(managementObjective, objective);
            for (WorkplaceDashboardService.WorkItemPulse task : objective.workItems()) {
                tasks.add(new TaskView(
                        projectId, objective.objectiveId(), objective.summary(), task.stepId(),
                        task.objective(), task.state(), task.performer(), task.requiredCapability(),
                        task.target(), task.effectState(), task.actionCount(), task.latestAction(),
                        task.dependsOn(), task.acceptanceCriteria(), task.evidenceRequirements(),
                        objective.lastActivityAt()));
            }
        }
        return tasks.stream()
                .sorted(Comparator.comparing(TaskView::lastActivityAt).reversed()
                        .thenComparing(TaskView::objectiveId)
                        .thenComparing(TaskView::stepId))
                .toList();
    }

    private List<ProjectView> projects(WorkplaceDashboardService.Dashboard source, List<TaskView> tasks) {
        Map<String, ManagementObjective> objectiveById = new LinkedHashMap<>();
        source.objectives().forEach(objective -> objectiveById.put(objective.objectiveId(), objective));
        Map<String, ProjectAccumulator> grouped = new LinkedHashMap<>();

        for (WorkplaceDashboardService.ObjectivePulse objective : source.objectivePulse()) {
            String id = projectId(objectiveById.get(objective.objectiveId()), objective);
            ProjectAccumulator project = grouped.computeIfAbsent(id, ProjectAccumulator::new);
            project.objectiveIds.add(objective.objectiveId());
            project.workers.add(objective.ownerWorkerId());
            project.totalObjectives++;
            if ("BLOCKED".equals(objective.executionState()) || objective.executionState().startsWith("STALE")
                    || objective.executionState().startsWith("UNPROVEN")) project.blockedObjectives++;
            if ("COMPLETED_WITH_EVIDENCE".equals(objective.executionState())) project.completedObjectives++;
            else project.activeObjectives++;
            if (project.lastActivityAt == null || objective.lastActivityAt().isAfter(project.lastActivityAt)) {
                project.lastActivityAt = objective.lastActivityAt();
            }
        }

        for (TaskView task : tasks) {
            ProjectAccumulator project = grouped.computeIfAbsent(task.projectId(), ProjectAccumulator::new);
            project.totalTasks++;
            if ("COMPLETED".equals(task.state())) project.completedTasks++;
            else if ("BLOCKED".equals(task.state()) || "STALE".equals(task.state())) project.blockedTasks++;
            else project.activeTasks++;
            if (task.performer() != null && !task.performer().isBlank() && !"UNASSIGNED".equals(task.performer())) {
                project.workers.add(task.performer());
            }
        }

        return grouped.values().stream()
                .map(ProjectAccumulator::view)
                .sorted(Comparator.comparing(ProjectView::lastActivityAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private static String projectId(ManagementObjective objective, WorkplaceDashboardService.ObjectivePulse pulse) {
        for (WorkplaceDashboardService.WorkItemPulse task : pulse.workItems()) {
            String target = normalizeTarget(task.target());
            if (REPOSITORY.matcher(target).matches()) return target;
        }
        if (objective != null && objective.organizationContextId() != null
                && !objective.organizationContextId().isBlank()) {
            return objective.organizationContextId();
        }
        return "unscoped";
    }

    private static String normalizeTarget(String target) {
        if (target == null) return "";
        String value = target.trim();
        if (value.startsWith("https://github.com/")) value = value.substring("https://github.com/".length());
        if (value.endsWith(".git")) value = value.substring(0, value.length() - 4);
        return value;
    }

    public record ControlRoomSnapshot(
            Instant generatedAt,
            String revision,
            String environment,
            WorkplaceDashboardService.Summary summary,
            List<WorkerSummary> workers,
            List<WorkplaceDashboardService.ObjectivePulse> objectives,
            List<TaskView> tasks,
            List<ProjectView> projects,
            List<WorkplaceDashboardService.Alert> alerts,
            List<com.metatron.workforce.management.ManagementAutonomyService.ManagementEvent> recentEvents) {}

    public record WorkerSummary(
            String workerId, String status, String role, boolean available, double capacity,
            int assignments, long activeObjectives, long completedObjectives,
            String runtimeProfile, String runtimeId, String runtimeState) {}

    public record AssignmentView(
            String assignmentId, String objectiveId, String description, String status, Instant createdAt) {}

    public record WorkerDetail(
            String workerId, String status, String primaryRole,
            List<WorkforceCoreService.Participation> participations,
            List<WorkforceCoreService.Capability> capabilities,
            List<WorkforceCoreService.Qualification> qualifications,
            WorkforceCoreService.Availability availability,
            List<AssignmentView> assignments,
            String runtimeProfile, List<String> runtimeActions,
            String runtimeId, String runtimeState, Instant runtimeCreatedAt,
            List<WorkplaceDashboardService.ObjectivePulse> objectives,
            List<TaskView> tasks,
            List<WorkplaceDashboardService.ActionPulse> actions) {}

    public record TaskView(
            String projectId, String objectiveId, String objectiveSummary, String stepId,
            String task, String state, String performer, String requiredCapability,
            String target, String effectState, int actionCount, String latestAction,
            List<String> dependsOn, List<String> acceptanceCriteria,
            List<String> evidenceRequirements, Instant lastActivityAt) {}

    public record ProjectView(
            String projectId, int objectives, int activeObjectives, int completedObjectives, int blockedObjectives,
            int tasks, int activeTasks, int completedTasks, int blockedTasks,
            List<String> workerIds, List<String> objectiveIds, int progressPercent, Instant lastActivityAt) {}

    private static final class ProjectAccumulator {
        private final String id;
        private int totalObjectives;
        private int activeObjectives;
        private int completedObjectives;
        private int blockedObjectives;
        private int totalTasks;
        private int activeTasks;
        private int completedTasks;
        private int blockedTasks;
        private final Set<String> workers = new LinkedHashSet<>();
        private final Set<String> objectiveIds = new LinkedHashSet<>();
        private Instant lastActivityAt;

        private ProjectAccumulator(String id) {
            this.id = id == null || id.isBlank() ? "unscoped" : id;
        }

        private ProjectView view() {
            int progress = totalTasks == 0
                    ? (totalObjectives > 0 && completedObjectives == totalObjectives ? 100 : 0)
                    : (int)Math.floor(completedTasks * 100.0 / totalTasks);
            return new ProjectView(id, totalObjectives, activeObjectives, completedObjectives, blockedObjectives,
                    totalTasks, activeTasks, completedTasks, blockedTasks,
                    List.copyOf(workers), List.copyOf(objectiveIds), progress, lastActivityAt);
        }
    }
}
