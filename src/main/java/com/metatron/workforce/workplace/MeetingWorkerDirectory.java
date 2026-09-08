package com.metatron.workforce.workplace;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.management.GatewayDirectorAppointmentCapability;
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

    public ResolvedWorker resolveActiveById(String workerId) {
        WorkforceCoreService.Worker worker;
        try { worker = core.worker(workerId); }
        catch (java.util.NoSuchElementException missing) {
            throw new IllegalStateException("meeting_worker_not_found: " + workerId);
        }
        if (worker.status() != WorkforceCoreService.WorkerStatus.ACTIVE) {
            throw new IllegalStateException("meeting_worker_not_active: " + workerId + " status=" + worker.status());
        }
        List<WorkforceCoreService.Participation> active = core.participations(workerId).stream()
                .filter(p -> p.status() == WorkforceCoreService.ParticipationStatus.ACTIVE)
                .toList();
        if (active.isEmpty()) {
            throw new IllegalStateException("meeting_worker_has_no_active_participation: " + workerId);
        }
        WorkforceCoreService.Participation participation = active.getFirst();
        String role = humanizeRole(!blank(participation.roleRef()) ? participation.roleRef() : participation.positionRef());
        return new ResolvedWorker(workerId, participation.participationId(), role,
                participation.positionRef(), participation.roleRef());
    }

    public List<ResolvedWorker> listActive() {
        return core.allWorkers().stream()
                .filter(w -> w.status() == WorkforceCoreService.WorkerStatus.ACTIVE)
                .flatMap(w -> core.participations(w.workerId()).stream()
                        .filter(p -> p.status() == WorkforceCoreService.ParticipationStatus.ACTIVE)
                        .map(p -> new ResolvedWorker(
                                w.workerId(),
                                p.participationId(),
                                humanizeRole(!blank(p.roleRef()) ? p.roleRef() : p.positionRef()),
                                p.positionRef(),
                                p.roleRef())))
                .sorted(Comparator.comparing(ResolvedWorker::workerId)
                        .thenComparing(ResolvedWorker::participationId))
                .toList();
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
            List<ResolvedWorker> canonical = matches.stream()
                    .filter(match -> GatewayDirectorAppointmentCapability.WORKER_ID.equals(match.workerId()))
                    .toList();
            if (canonical.size() == 1 && normalize(role).equals(normalize("Head of Gateway"))) {
                return canonical.getFirst();
            }
            throw new IllegalStateException("meeting_worker_ambiguous: role " + role
                    + " resolves to " + matches.stream().map(ResolvedWorker::workerId).toList());
        }
        return matches.getFirst();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static String humanizeRole(String ref) {
        if (ref == null || ref.isBlank()) return "Institutional Worker";
        String v = ref;
        int colon = Math.max(v.lastIndexOf(':'), v.lastIndexOf('/'));
        if (colon >= 0 && colon + 1 < v.length()) v = v.substring(colon + 1);
        return java.util.Arrays.stream(v.replace('_','-').split("-"))
                .filter(token -> !token.isBlank())
                .map(token -> Character.toUpperCase(token.charAt(0)) + token.substring(1))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private static boolean matches(String wanted, String ref) {
        if (ref == null || ref.isBlank()) return false;
        String candidate = normalize(ref);
        if (candidate.equals(wanted)) return true;
        return candidate.equals(wanted)
                || candidate.endsWith(" " + wanted)
                || candidate.contains(" " + wanted + " ");
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
