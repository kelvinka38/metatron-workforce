package com.metatron.workforce.interaction;

import com.metatron.workforce.interaction.intelligence.CanonicalObjectiveControlInterpreter;
import com.metatron.workforce.workplace.WorkplaceDashboardService;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic read-only projection for Human questions about Metatron's own institutional state.
 * It prevents internal Metatron questions from being misrouted to public-world Intelligence retrieval.
 */
@Service
public final class MetatronInstitutionalStateChatService {
    private final WorkplaceDashboardService dashboard;

    public MetatronInstitutionalStateChatService(WorkplaceDashboardService dashboard) {
        this.dashboard = Objects.requireNonNull(dashboard, "dashboard");
    }

    public Optional<String> answer(String text) {
        if (CanonicalObjectiveControlInterpreter.isExplicitObjectiveControl(text)) {
            return Optional.empty();
        }
        String q = normalize(text);
        if (!q.contains("metatron")) return Optional.empty();
        boolean institutional = q.contains("workforce") || q.contains("worker")
                || q.contains("objective") || q.contains("task")
                || q.contains("control room") || q.contains("doing")
                || q.contains("working") || q.contains("status");
        if (!institutional) return Optional.empty();

        WorkplaceDashboardService.Dashboard d = dashboard.dashboard();
        WorkplaceDashboardService.Summary s = d.summary();

        if (q.contains("workforce") || q.contains("worker")) {
            StringBuilder out = new StringBuilder("METATRON · INSTITUTIONAL STATE\n")
                    .append("Workers: ").append(s.activeWorkers()).append(" active / ")
                    .append(s.workers()).append(" total admitted\n");
            if (!d.workers().isEmpty()) {
                out.append("Worker IDs:");
                d.workers().stream().limit(20).forEach(w -> out.append("\n• ").append(w.workerId())
                        .append(" · ").append(w.status()));
            }
            out.append("\nsource=canonical-workplace-state");
            return Optional.of(out.toString());
        }

        return Optional.of("METATRON · INSTITUTIONAL STATE\n"
                + "Objectives: " + s.activeObjectives() + " active / " + s.objectives() + " total\n"
                + "Tasks: " + s.runningTasks() + " running · " + s.completedTasks() + " completed · "
                + s.waitingTasks() + " waiting · " + s.blockedTasks() + " blocked\n"
                + "Actions: " + s.mutatingActions() + " mutating · " + s.readOnlyActions()
                + " read-only · " + s.failedActions() + " failed\n"
                + "source=canonical-workplace-state");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
