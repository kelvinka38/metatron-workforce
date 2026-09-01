package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Compact conversation projection of durable Objective/Work state. */
@Component
public final class WorkCardRenderer {
    private final ManagementAutonomyService management;

    public WorkCardRenderer(ManagementAutonomyService management) {
        this.management = management;
    }

    public Optional<String> latestObjectiveIdForHuman(String humanId) {
        String normalized = normalizeHuman(humanId);
        return management.allObjectives().stream()
                .filter(o -> management.findAutonomousWork(o.objectiveId())
                        .map(w -> w.humanId().equals(normalized)).orElse(false))
                .max(Comparator.comparing(ManagementObjective::updatedAt))
                .map(ManagementObjective::objectiveId);
    }

    public String latestForHuman(String humanId) {
        return latestObjectiveIdForHuman(humanId).map(this::render)
                .orElse("📊 METATRON WORK\n\nNo autonomous Objective is currently visible for this Human.");
    }

    public String render(String objectiveId) {
        return render(management.get(objectiveId));
    }

    public boolean terminal(String objectiveId) {
        return management.findAutonomousWork(objectiveId).map(AutonomousObjectiveWork::terminal)
                .orElseGet(() -> management.get(objectiveId).terminal());
    }

    private String render(ManagementObjective objective) {
        AutonomousObjectiveWork work = management.findAutonomousWork(objective.objectiveId()).orElse(null);
        if (work == null) return "📊 METATRON WORK\n\n" + compact(objective.description()) + "\n\nSTATUS  " + objective.status();
        List<ManagementAutonomyService.ManagementEvent> history = management.history(objective.objectiveId());
        Instant last = history.stream().map(ManagementAutonomyService.ManagementEvent::occurredAt)
                .max(Comparator.naturalOrder()).orElse(work.updatedAt());
        boolean fresh = Duration.between(last, Instant.now()).compareTo(Duration.ofSeconds(90)) <= 0;
        Set<String> completed = Set.copyOf(work.completedStepIds());
        int total = work.plannedWork().size();
        int done = completed.size();
        int percent = total == 0 ? (work.terminal() ? 100 : 0) : (int)Math.floor(done * 100.0 / total);
        String status = humanStatus(work, history, fresh);

        StringBuilder out = new StringBuilder();
        out.append("📊 METATRON · AUTONOMOUS WORK\n\n");
        out.append(compact(objective.description())).append("\n\n");
        out.append("STATUS     ").append(icon(status)).append(' ').append(status).append('\n');
        out.append("PROGRESS   ").append(progressBar(percent)).append(' ').append(percent).append("%\n");
        out.append("OWNER      ").append(objective.ownerWorkerId()).append('\n');
        out.append("LAST EVENT ").append(last).append("\n\n");
        if (work.plannedWork().isEmpty()) {
            out.append("WORK GRAPH\n  ⏳ Planning has not produced work items yet.\n");
        } else {
            out.append("WORK GRAPH\n");
            int n = 1;
            for (ExecutionWorkSpec step : work.plannedWork()) {
                String state;
                if (completed.contains(step.stepId())) state = "✅";
                else if (!completed.containsAll(step.dependsOn())) state = "⏳";
                else if (work.status() == AutonomousObjectiveWork.Status.EXECUTING) state = fresh ? "🔄" : "⚠️";
                else if (work.status() == AutonomousObjectiveWork.Status.BLOCKED) state = "🛑";
                else state = "○";
                out.append(' ').append(n++).append(". ").append(state).append(' ')
                        .append(compactStep(step.objective())).append('\n')
                        .append("    ↳ ").append(step.requiredCapability()).append('\n');
            }
        }
        if (!work.blocker().isBlank()) out.append("\nBLOCKER\n  🛑 ").append(compact(work.blocker())).append('\n');
        out.append("\nEXECUTION PROOF\n  ").append(proof(work, history, fresh)).append('\n');
        if (!work.evidenceReferences().isEmpty()) {
            out.append("\nEVIDENCE  ").append(work.evidenceReferences().size()).append(" refs");
            if (work.terminal()) for (String ref : work.evidenceReferences().stream().limit(5).toList()) out.append("\n  • ").append(ref);
            out.append('\n');
        }
        out.append("\nDetails: /workforce/monitor");
        return out.toString();
    }

    private static String humanStatus(AutonomousObjectiveWork work,
            List<ManagementAutonomyService.ManagementEvent> history, boolean fresh) {
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

    private static String proof(AutonomousObjectiveWork work,
            List<ManagementAutonomyService.ManagementEvent> history, boolean fresh) {
        if (work.status() == AutonomousObjectiveWork.Status.COMPLETED)
            return work.evidenceReferences().isEmpty() ? "UNPROVEN terminal state" : "TERMINAL EVIDENCE PRESENT";
        if (work.status() != AutonomousObjectiveWork.Status.EXECUTING) return "NOT EXECUTING";
        boolean execution = history.stream().anyMatch(e -> e.type() == ManagementAutonomyService.ManagementEvent.Type.EXECUTION_STARTED);
        if (!execution) return "UNPROVEN execution claim";
        return fresh ? "OBSERVED durable execution activity" : "STALE — no recent durable activity";
    }

    private static String progressBar(int percent) {
        int cells = Math.max(0, Math.min(10, (int)Math.round(percent / 10.0)));
        return "█".repeat(cells) + "░".repeat(10 - cells);
    }
    private static String icon(String status) {
        if (status.equals("COMPLETED")) return "✅";
        if (status.equals("BLOCKED") || status.contains("STALE")) return "🛑";
        if (status.equals("WORKING") || status.equals("VERIFYING")) return "🟢";
        return "🟡";
    }
    private static String normalizeHuman(String value) { return value != null && value.startsWith("human:") ? value.substring(6) : value; }
    private static String compact(String value) { String s=value==null?"":value.replaceAll("\\s+"," ").trim(); return s.length()<=220?s:s.substring(0,217)+"..."; }
    private static String compactStep(String value) { String s=compact(value); return s.length()<=90?s:s.substring(0,87)+"..."; }
}
