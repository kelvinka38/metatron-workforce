package com.metatron.workforce.workplace;

import com.metatron.workforce.core.WorkforceCoreService;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Resolves a Meeting role to one real ACTIVE canonical Workforce Worker. Never fabricates a participant. */
@Service
public final class MeetingWorkerDirectory {
    private final WorkforceCoreService core;

    public MeetingWorkerDirectory(WorkforceCoreService core) {
        this.core = Objects.requireNonNull(core, "core");
    }

    public ResolvedWorker resolveActive(String role) {
        String wanted = normalize(role);
        List<ResolvedWorker> matches = core.allWorkers().stream()
                .filter(w -> w.status() == WorkforceCoreService.WorkerStatus.ACTIVE)
                .flatMap(w -> core.participations(w.workerId()).stream()
                        .filter(p -> p.status() == WorkforceCoreService.ParticipationStatus.ACTIVE)
                        .filter(p -> matches(wanted, p.positionRef()) || matches(wanted, p.roleRef()))
                        .map(p -> new ResolvedWorker(w.workerId(), p.participationId(), role, p.positionRef(), p.roleRef())))
                .sorted(Comparator.comparing(ResolvedWorker::workerId))
                .distinct()
                .toList();

        if (matches.isEmpty()) {
            throw new IllegalStateException("meeting_worker_not_found: no ACTIVE Worker is bound to role " + role);
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("meeting_worker_ambiguous: role " + role
                    + " resolves to " + matches.stream().map(ResolvedWorker::workerId).toList());
        }
        return matches.getFirst();
    }

    private static boolean matches(String wanted, String ref) {
        if (ref == null || ref.isBlank()) return false;
        String candidate = normalize(ref);
        if (candidate.equals(wanted)) return true;
        String wantedSlug = wanted.replace(' ', '-');
        return candidate.endsWith(":" + wantedSlug)
                || candidate.endsWith("/" + wantedSlug)
                || candidate.endsWith("-" + wantedSlug)
                || candidate.contains(wantedSlug);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String n = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd')
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
        return n;
    }

    public record ResolvedWorker(String workerId, String participationId, String role,
                                 String positionRef, String roleRef) {}
}
