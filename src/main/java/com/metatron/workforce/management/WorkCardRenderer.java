package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Compact conversation projection of durable Objective/Work state. */
@Component
public final class WorkCardRenderer {
    private final ManagementAutonomyService management;

    public WorkCardRenderer(ManagementAutonomyService management) { this.management = management; }

    public Optional<String> latestObjectiveIdForHuman(String humanId) {
        String normalized = normalizeHuman(humanId);
        return management.allObjectives().stream()
                .filter(o -> management.findAutonomousWork(o.objectiveId()).map(w -> w.humanId().equals(normalized)).orElse(false))
                .max(Comparator.comparing(ManagementObjective::updatedAt)).map(ManagementObjective::objectiveId);
    }

    public String latestForHuman(String humanId) {
        return latestObjectiveIdForHuman(humanId).map(this::render)
                .orElse("📋 METATRON WORK ORDER\n\nNo autonomous Objective is currently visible for this Human.");
    }

    public String render(String objectiveId) { return render(management.get(objectiveId)); }

    public boolean terminal(String objectiveId) {
        return management.findAutonomousWork(objectiveId).map(AutonomousObjectiveWork::terminal)
                .orElseGet(() -> management.get(objectiveId).terminal());
    }

    private String render(ManagementObjective objective) {
        AutonomousObjectiveWork work = management.findAutonomousWork(objective.objectiveId()).orElse(null);
        if (work == null) return "📋 METATRON WORK ORDER\n\nOBJECTIVE\n" + compact(objective.description())
                + "\n\nSTATUS     " + objective.status() + "\nOWNER      " + objective.ownerWorkerId()
                + "\nWORKLOAD   Not planned yet\nETA        Not committed\nSTAFFING   Not materialized";

        List<ManagementAutonomyService.ManagementEvent> history = management.history(objective.objectiveId());
        Instant last = history.stream().map(ManagementAutonomyService.ManagementEvent::occurredAt)
                .max(Comparator.naturalOrder()).orElse(work.updatedAt());
        boolean fresh = Duration.between(last, Instant.now()).compareTo(Duration.ofSeconds(90)) <= 0;
        Set<String> completed = Set.copyOf(work.completedStepIds());
        Map<String, String> performers = performersByStep(work.evidenceReferences());
        int total = work.plannedWork().size();
        int done = completed.size();
        int percent = total == 0 ? (work.terminal() ? 100 : 0) : (int)Math.floor(done * 100.0 / total);
        String status = humanStatus(work, history, fresh);
        String reportsTo = "human:" + work.humanId();
        String staffing = performers.isEmpty()
                ? (objective.assignmentRefs().isEmpty() ? "UNASSIGNED / staffing not evidenced" : objective.assignmentRefs().size() + " assignment ref(s), performer pending execution evidence")
                : performers.values().stream().distinct().count() + " evidenced worker(s)";

        StringBuilder out = new StringBuilder();
        out.append("📋 METATRON · WORK ORDER\n\n");
        out.append("OBJECTIVE\n").append(compact(objective.description())).append("\n\n");
        out.append("STATUS     ").append(icon(status)).append(' ').append(status).append('\n');
        out.append("PROGRESS   ").append(progressBar(percent)).append(' ').append(percent).append("% ("+done+"/"+total+")\n");
        out.append("OWNER      ").append(objective.ownerWorkerId()).append('\n');
        out.append("REPORTS TO ").append(reportsTo).append('\n');
        out.append("WORKLOAD   ").append(total == 0 ? "Planning pending" : total + " work item(s)").append('\n');
        out.append("ETA        ").append(work.terminal() ? "Completed" : "Not committed by canonical plan").append('\n');
        out.append("STAFFING   ").append(staffing).append('\n');
        out.append("LAST EVENT ").append(last).append("\n\n");

        if (work.plannedWork().isEmpty()) {
            out.append("WORK BREAKDOWN\n  ⏳ Planning has not produced work items yet.\n");
        } else {
            out.append("WORK BREAKDOWN\n");
            int n = 1;
            for (ExecutionWorkSpec step : work.plannedWork()) {
                String state;
                if (completed.contains(step.stepId())) state = "✅";
                else if (!completed.containsAll(step.dependsOn())) state = "⏳";
                else if (work.status() == AutonomousObjectiveWork.Status.EXECUTING) state = fresh ? "🔄" : "⚠️";
                else if (work.status() == AutonomousObjectiveWork.Status.BLOCKED) state = "🛑";
                else state = "○";
                out.append(' ').append(n++).append(". ").append(state).append(' ').append(compactStep(step.objective())).append('\n');
                out.append("    Role: ").append(step.requiredCapability()).append('\n');
                out.append("    Performer: ").append(performers.getOrDefault(step.stepId(), "UNASSIGNED / not yet evidenced")).append('\n');
                out.append("    Depends: ").append(step.dependsOn().isEmpty() ? "none" : String.join(", ", step.dependsOn())).append('\n');
                out.append("    DoD: ").append(step.acceptanceCriteria().isEmpty() ? "NOT DEFINED" : compactList(step.acceptanceCriteria())).append('\n');
            }
        }
        if (!work.blocker().isBlank()) out.append("\nNOTE / BLOCKER\n  🛑 ").append(compact(work.blocker())).append('\n');
        else out.append("\nNOTE / RISK\n  No active blocker recorded.\n");
        out.append("\nEXECUTION PROOF\n  ").append(proof(work, history, fresh)).append('\n');
        if (!work.evidenceReferences().isEmpty()) {
            out.append("\nEVIDENCE  ").append(work.evidenceReferences().size()).append(" refs");
            if (work.terminal()) for (String ref : work.evidenceReferences().stream().limit(5).toList()) out.append("\n  • ").append(ref);
            out.append('\n');
        }
        out.append("\n📊 Monitor: /workforce/monitor");
        return out.toString();
    }

    /** Resolve performer only from durable execution attribution; never infer a worker from role/capability. */
    static Map<String, String> performersByStep(List<String> evidenceReferences) {
        Map<String, String> performers = new LinkedHashMap<>();
        for (String ref : evidenceReferences == null ? List.<String>of() : evidenceReferences) {
            if (ref == null || !ref.startsWith("autonomous-step:")) continue;
            int capabilityMarker = ref.indexOf(":capability=");
            int workerMarker = ref.indexOf(":worker=");
            if (capabilityMarker <= "autonomous-step:".length() || workerMarker < 0) continue;
            String stepId = ref.substring("autonomous-step:".length(), capabilityMarker).trim();
            int workerStart = workerMarker + ":worker=".length();
            int workerEnd = ref.indexOf(":dispatch=", workerStart);
            if (workerEnd < 0) workerEnd = ref.length();
            String workerId = ref.substring(workerStart, workerEnd).trim();
            if (!stepId.isBlank() && !workerId.isBlank()) performers.put(stepId, workerId);
        }
        return Map.copyOf(performers);
    }

    private static String humanStatus(AutonomousObjectiveWork work, List<ManagementAutonomyService.ManagementEvent> history, boolean fresh) {
        return switch (work.status()) {
            case PENDING_PLANNING -> "RECEIVED";
            case PLANNING -> "PLANNING";
            case READY -> "READY";
            case EXECUTING -> proof(work, history, fresh).startsWith("OBSERVED") ? "WORKING" : "WAITING FOR EXECUTION PROOF";
            case PENDING_VERIFICATION -> "PENDING VERIFICATION";
            case VERIFYING -> "VERIFYING";
            case BLOCKED -> "BLOCKED";
            case COMPLETED -> "COMPLETED";
            case CANCELLED -> "CANCELLED";
        };
    }

    private static String proof(AutonomousObjectiveWork work, List<ManagementAutonomyService.ManagementEvent> history, boolean fresh) {
        if (work.status() == AutonomousObjectiveWork.Status.COMPLETED)
            return work.evidenceReferences().isEmpty() ? "UNPROVEN terminal state" : "TERMINAL EVIDENCE PRESENT";
        if (work.status() != AutonomousObjectiveWork.Status.EXECUTING) return "NOT EXECUTING";
        boolean execution = history.stream().anyMatch(e -> e.type() == ManagementAutonomyService.ManagementEvent.Type.EXECUTION_STARTED);
        if (!execution) return "UNPROVEN execution claim";
        return fresh ? "OBSERVED durable execution activity" : "STALE — no recent durable activity";
    }

    private static String progressBar(int percent) { int cells=Math.max(0,Math.min(10,(int)Math.round(percent/10.0))); return "█".repeat(cells)+"░".repeat(10-cells); }
    private static String icon(String status) { if(status.equals("COMPLETED"))return "✅"; if(status.equals("BLOCKED")||status.contains("STALE"))return "🛑"; if(status.equals("WORKING")||status.equals("VERIFYING"))return "🟢"; return "🟡"; }
    private static String normalizeHuman(String value) { return value != null && value.startsWith("human:") ? value.substring(6) : value; }
    private static String compact(String value) { String s=value==null?"":value.replaceAll("\\s+"," ").trim(); return s.length()<=220?s:s.substring(0,217)+"..."; }
    private static String compactStep(String value) { String s=compact(value); return s.length()<=90?s:s.substring(0,87)+"..."; }
    private static String compactList(List<String> values) { String s=String.join("; ", values); return s.length()<=140?s:s.substring(0,137)+"..."; }
}