package com.metatron.workforce.interaction;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.management.AutonomousStaffingService;
import com.metatron.workforce.management.GatewayDirectorAppointmentCapability;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeInstance;
import com.metatron.workforce.runtime.RuntimeState;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * First-class Human control plane for Worker lifecycle operations.
 * Worker formation is institutional identity provisioning, not an Objective.
 */
@Service
public final class WorkerLifecycleControlService {
    private static final String FOUNDER = "human-primary";

    private final AutonomousStaffingService staffing;
    private final GatewayDirectorAppointmentCapability gatewayDirector;
    private final WorkforceCoreService core;
    private final WorkerRuntimeProfileBindingService runtimeProfiles;
    private final RuntimeCapacityCoordinator runtimes;
    private final Clock clock;

    public WorkerLifecycleControlService(
            AutonomousStaffingService staffing,
            GatewayDirectorAppointmentCapability gatewayDirector,
            WorkforceCoreService core,
            WorkerRuntimeProfileBindingService runtimeProfiles,
            RuntimeCapacityCoordinator runtimes) {
        this(staffing, gatewayDirector, core, runtimeProfiles, runtimes, Clock.systemUTC());
    }

    WorkerLifecycleControlService(
            AutonomousStaffingService staffing,
            GatewayDirectorAppointmentCapability gatewayDirector,
            WorkforceCoreService core,
            WorkerRuntimeProfileBindingService runtimeProfiles,
            RuntimeCapacityCoordinator runtimes,
            Clock clock) {
        this.staffing = Objects.requireNonNull(staffing, "staffing");
        this.gatewayDirector = Objects.requireNonNull(gatewayDirector, "gatewayDirector");
        this.core = Objects.requireNonNull(core, "core");
        this.runtimeProfiles = Objects.requireNonNull(runtimeProfiles, "runtimeProfiles");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean supports(String text) {
        String q = folded(text);
        if (q.isBlank()) return false;
        boolean lifecycleAction = containsAny(q,
                "create worker", "create a worker", "create workforce worker",
                "provision worker", "appoint worker", "staff worker",
                "tao worker", "tao mot worker", "tao workforce worker",
                "khoi tao worker", "kich hoat worker");
        boolean gatewayRole = containsAny(q,
                "head of gateway", "gateway head", "gateway director",
                "director of gateway", "role gateway head", "role head of gateway");
        return lifecycleAction && gatewayRole;
    }

    public Optional<String> handle(String humanId, String text) {
        if (!supports(text)) return Optional.empty();
        if (!FOUNDER.equals(humanId)) {
            throw new SecurityException("Founder identity required for institutional Worker formation");
        }

        String workerId = GatewayDirectorAppointmentCapability.WORKER_ID;
        boolean existed = core.allWorkers().stream().anyMatch(w -> workerId.equals(w.workerId()));

        AutonomousStaffingService.StaffingOutcome staffed =
                staffing.ensureStaffed(gatewayDirector, clock.instant());
        if (!staffed.staffed() || !workerId.equals(staffed.workerId())) {
            throw new IllegalStateException("gateway_director_worker_formation_failed");
        }

        WorkforceCoreService.Worker worker = core.worker(workerId);
        WorkforceCoreService.Participation participation = core.participations(workerId).stream()
                .filter(p -> p.status() == WorkforceCoreService.ParticipationStatus.ACTIVE)
                .filter(p -> GatewayDirectorAppointmentCapability.ROLE_REF.equals(p.roleRef()))
                .filter(p -> GatewayDirectorAppointmentCapability.POSITION_REF.equals(p.positionRef()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("gateway_director_active_participation_missing"));

        WorkerRuntimeProfileBindingService.Binding profile = runtimeProfiles.requireBinding(workerId);
        RuntimeInstance runtime = runtimes.ensureRunning(workerId);

        if (worker.status() != WorkforceCoreService.WorkerStatus.ACTIVE) {
            throw new IllegalStateException("gateway_director_worker_not_active");
        }
        if (runtime.state() != RuntimeState.RUNNING) {
            throw new IllegalStateException("gateway_director_runtime_not_running");
        }

        String formation = existed ? "REUSED" : "CREATED";
        return Optional.of(
                "🧰 **METATRON · WORKER READY**\n\n"
                        + "worker_id=`" + worker.workerId() + "`\n"
                        + "participant_id=`" + worker.participantId() + "`\n"
                        + "participation_id=`" + participation.participationId() + "`\n"
                        + "role=Head of Gateway\n"
                        + "role_ref=" + participation.roleRef() + "\n"
                        + "position_ref=" + participation.positionRef() + "\n"
                        + "runtime_profile=" + profile.profile().profileRef() + "\n"
                        + "runtime_id=`" + runtime.runtimeId() + "`\n"
                        + "runtime_state=" + runtime.state().name() + "\n"
                        + "worker_status=" + worker.status().name() + "\n"
                        + "formation=" + formation + "\n\n"
                        + "No Case, Objective, queue item, or planner was created. "
                        + "This is the canonical institutional Worker identity and its live runtime.");
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    static String folded(String value) {
        String source = value == null ? "" : value;
        String decomposed = Normalizer.normalize(source, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT)
                .replace('đ', 'd')
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
