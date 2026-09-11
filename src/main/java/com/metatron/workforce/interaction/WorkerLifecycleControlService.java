package com.metatron.workforce.interaction;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.management.AutonomousStaffingService;
import com.metatron.workforce.management.GatewayDirectorAppointmentCapability;
import com.metatron.workforce.operating.WorkerConstitutionService;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeInstance;
import com.metatron.workforce.runtime.RuntimeState;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * First-class Human control plane for Worker lifecycle operations.
 * Worker formation is institutional identity provisioning, not an Objective and not an LLM persona.
 */
@Service
public final class WorkerLifecycleControlService {
    private static final String FOUNDER = "human-primary";
    private static final Pattern ROLE_PATTERN = Pattern.compile(
            "(?iu)(?:\\brole\\b|vai\\s+tr[oòóỏõọôốồổỗộơớờởỡợ]+)\\s*[:=]?\\s*([^\\n.!?]{2,80})");
    private static final Pattern WORKER_ID_PATTERN = Pattern.compile("(?i)\\bWORKER-[A-Z0-9._:-]+\\b");
    private static final Pattern ENGLISH_CREATE_ROLE = Pattern.compile(
            "(?iu)(?:create(?:\\s+for\\s+me)?\\s+(?:a\\s+)?worker|provision\\s+(?:a\\s+)?worker)\\s*[,;:-]?\\s*(?:as\\s+)?([^\\n.!?]{2,80})");

    private final AutonomousStaffingService staffing;
    private final GatewayDirectorAppointmentCapability gatewayDirector;
    private final WorkforceCoreService core;
    private final WorkerRuntimeProfileBindingService runtimeProfiles;
    private final RuntimeCapacityCoordinator runtimes;
    private final FounderDefinedWorkerFormationService founderFormation;
    private final Clock clock;
    private final Map<String, String> lastWorkerByHuman = new ConcurrentHashMap<>();

    @Autowired
    public WorkerLifecycleControlService(
            AutonomousStaffingService staffing,
            GatewayDirectorAppointmentCapability gatewayDirector,
            WorkforceCoreService core,
            WorkerRuntimeProfileBindingService runtimeProfiles,
            RuntimeCapacityCoordinator runtimes,
            FounderDefinedWorkerFormationService founderFormation) {
        this(staffing, gatewayDirector, core, runtimeProfiles, runtimes, founderFormation, Clock.systemUTC());
    }

    WorkerLifecycleControlService(
            AutonomousStaffingService staffing,
            GatewayDirectorAppointmentCapability gatewayDirector,
            WorkforceCoreService core,
            WorkerRuntimeProfileBindingService runtimeProfiles,
            RuntimeCapacityCoordinator runtimes) {
        this(staffing, gatewayDirector, core, runtimeProfiles, runtimes,
                new FounderDefinedWorkerFormationService(
                        core, runtimeProfiles, WorkerConstitutionService.inMemory(), runtimes),
                Clock.systemUTC());
    }

    WorkerLifecycleControlService(
            AutonomousStaffingService staffing,
            GatewayDirectorAppointmentCapability gatewayDirector,
            WorkforceCoreService core,
            WorkerRuntimeProfileBindingService runtimeProfiles,
            RuntimeCapacityCoordinator runtimes,
            Clock clock) {
        this(staffing, gatewayDirector, core, runtimeProfiles, runtimes,
                new FounderDefinedWorkerFormationService(
                        core, runtimeProfiles, WorkerConstitutionService.inMemory(), runtimes),
                clock);
    }

    WorkerLifecycleControlService(
            AutonomousStaffingService staffing,
            GatewayDirectorAppointmentCapability gatewayDirector,
            WorkforceCoreService core,
            WorkerRuntimeProfileBindingService runtimeProfiles,
            RuntimeCapacityCoordinator runtimes,
            FounderDefinedWorkerFormationService founderFormation,
            Clock clock) {
        this.staffing = Objects.requireNonNull(staffing, "staffing");
        this.gatewayDirector = Objects.requireNonNull(gatewayDirector, "gatewayDirector");
        this.core = Objects.requireNonNull(core, "core");
        this.runtimeProfiles = Objects.requireNonNull(runtimeProfiles, "runtimeProfiles");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.founderFormation = Objects.requireNonNull(founderFormation, "founderFormation");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean supports(String text) {
        return supports(text, "");
    }

    /** Context-aware form allows pronoun follow-ups such as "activate it" after a Worker was created. */
    public boolean supports(String text, String conversationContext) {
        String q = folded(text);
        if (q.isBlank()) return false;
        return isCreate(q) || isInspect(q) || isActivate(q);
    }

    public Optional<String> handle(String humanId, String text) {
        return handle(humanId, text, "");
    }

    public Optional<String> handle(String humanId, String text, String conversationContext) {
        if (!supports(text, conversationContext)) return Optional.empty();
        if (!FOUNDER.equals(humanId)) {
            throw new SecurityException("Founder identity required for institutional Worker lifecycle control");
        }

        String q = folded(text);
        if (isCreate(q)) {
            if (isGatewayRole(q)) {
                String answer = createCanonicalGatewayDirector();
                lastWorkerByHuman.put(humanId, GatewayDirectorAppointmentCapability.WORKER_ID);
                return Optional.of(answer);
            }
            String role = extractRole(text).orElse("").trim();
            if (role.isBlank()) {
                return Optional.of("🧰 **METATRON · WORKER NOT CREATED**\n\n"
                        + "A role is required. Use for example: `Create for me a worker, role composer/artist.`\n"
                        + "No Worker, Participation, runtime or institutional state was created.");
            }
            FounderDefinedWorkerFormationService.FormationResult result =
                    founderFormation.form(role, text, clock.instant());
            lastWorkerByHuman.put(humanId, result.worker().workerId());
            return Optional.of(renderFounderWorker(result));
        }

        String workerId = explicitWorkerReference(text)
                .or(() -> contextWorkerReference(conversationContext))
                .or(() -> Optional.ofNullable(lastWorkerByHuman.get(humanId)))
                .orElseGet(() -> resolveRoleReference(text).orElse(""));
        if (workerId.isBlank()) {
            return Optional.of("🧰 **METATRON · WORKER CONTROL BLOCKED**\n\n"
                    + "No canonical Worker reference could be resolved. No lifecycle state was changed.");
        }
        if (!workerId.toUpperCase(Locale.ROOT).startsWith("WORKER-")) {
            workerId = founderFormation.resolveWorkerId(workerId);
        }

        if (isActivate(q)) {
            FounderDefinedWorkerFormationService.FormationResult result = founderFormation.activate(workerId, clock.instant());
            lastWorkerByHuman.put(humanId, result.worker().workerId());
            return Optional.of(renderFounderWorker(result));
        }
        if (isInspect(q)) {
            FounderDefinedWorkerFormationService.FormationResult result = founderFormation.inspect(workerId);
            lastWorkerByHuman.put(humanId, result.worker().workerId());
            return Optional.of(renderFounderWorker(result));
        }
        return Optional.empty();
    }

    private String createCanonicalGatewayDirector() {
        String workerId = GatewayDirectorAppointmentCapability.WORKER_ID;
        boolean existed = core.allWorkers().stream().anyMatch(w -> workerId.equals(w.workerId()));

        AutonomousStaffingService.StaffingOutcome staffed = staffing.ensureStaffed(gatewayDirector, clock.instant());
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
        return render(worker, participation, profile, runtime,
                "Head of Gateway", existed ? "REUSED" : "CREATED", "canonical-policy");
    }

    private static String renderFounderWorker(FounderDefinedWorkerFormationService.FormationResult result) {
        return render(result.worker(), result.participation(), result.runtimeProfile(), result.runtime(),
                result.humanRole(), result.created() ? "CREATED" : "REUSED",
                result.constitutionBinding().contractId());
    }

    private static String render(
            WorkforceCoreService.Worker worker,
            WorkforceCoreService.Participation participation,
            WorkerRuntimeProfileBindingService.Binding profile,
            RuntimeInstance runtime,
            String humanRole,
            String formation,
            String constitutionRef) {
        return "🧰 **METATRON · WORKER READY**\n\n"
                + "worker_id=`" + worker.workerId() + "`\n"
                + "participant_id=`" + worker.participantId() + "`\n"
                + "participation_id=`" + participation.participationId() + "`\n"
                + "role=" + humanRole + "\n"
                + "role_ref=" + participation.roleRef() + "\n"
                + "position_ref=" + participation.positionRef() + "\n"
                + "runtime_profile=" + profile.profile().profileRef() + "\n"
                + "runtime_id=`" + runtime.runtimeId() + "`\n"
                + "runtime_state=" + runtime.state().name() + "\n"
                + "worker_status=" + worker.status().name() + "\n"
                + "constitution=" + constitutionRef + "\n"
                + "runtime_actions=" + profile.profile().actionRefs().stream().sorted().toList() + "\n"
                + "formation=" + formation + "\n\n"
                + "Canonical identity, Position constitution and runtime are materialized. "
                + "Use `Workers`, `/worker " + worker.workerId() + "`, or `talk to " + humanRole
                + "` to use this Worker directly.";
    }

    private static boolean isCreate(String q) {
        return containsAny(q,
                "create worker", "create a worker", "create for me a worker", "create workforce worker",
                "provision worker", "provision a worker", "appoint worker", "staff worker",
                "tao worker", "tao mot worker", "tao workforce worker", "khoi tao worker");
    }

    private static boolean isActivate(String q) {
        return containsAny(q,
                "activate worker", "activate it", "make worker active", "start worker",
                "kich hoat worker", "dua worker vao hoat dong", "di vao hoat dong",
                "dua no vao hoat dong", "cho no hoat dong", "cho worker hoat dong");
    }

    private static boolean isInspect(String q) {
        return containsAny(q,
                "inspect worker", "show worker", "show canonical state", "worker state",
                "trang thai worker", "thong tin worker", "kiem tra worker");
    }

    private static boolean isGatewayRole(String q) {
        return containsAny(q,
                "head of gateway", "gateway head", "gateway director",
                "director of gateway", "role gateway head", "role head of gateway");
    }

    private static Optional<String> extractRole(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        Matcher role = ROLE_PATTERN.matcher(text);
        if (role.find()) return Optional.of(cleanCapturedRole(role.group(1)));
        Matcher english = ENGLISH_CREATE_ROLE.matcher(text);
        if (english.find()) {
            String candidate = cleanCapturedRole(english.group(1));
            if (!candidate.isBlank() && !folded(candidate).startsWith("head of gateway")) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static String cleanCapturedRole(String value) {
        if (value == null) return "";
        return value.trim()
                .replaceFirst("(?iu)^role\\s*[:=]?\\s*", "")
                .replaceFirst("(?iu)^as\\s+", "")
                .replaceAll("[,;:]+$", "")
                .trim();
    }

    private static Optional<String> explicitWorkerReference(String text) {
        if (text == null) return Optional.empty();
        Matcher matcher = WORKER_ID_PATTERN.matcher(text);
        if (matcher.find()) return Optional.of(matcher.group().toUpperCase(Locale.ROOT));
        return Optional.empty();
    }

    private static Optional<String> contextWorkerReference(String context) {
        if (context == null || context.isBlank()) return Optional.empty();
        Matcher matcher = WORKER_ID_PATTERN.matcher(context);
        String last = null;
        while (matcher.find()) last = matcher.group().toUpperCase(Locale.ROOT);
        return Optional.ofNullable(last);
    }

    private static Optional<String> resolveRoleReference(String text) {
        Optional<String> role = extractRole(text);
        if (role.isPresent()) return role;
        String q = folded(text);
        for (String prefix : new String[]{
                "activate ", "activate worker ", "show worker ", "inspect worker ",
                "kich hoat ", "kich hoat worker ", "trang thai worker ", "kiem tra worker "}) {
            if (q.startsWith(prefix) && q.length() > prefix.length()) {
                String candidate = q.substring(prefix.length()).trim();
                if (!candidate.equals("it") && !candidate.equals("no")) return Optional.of(candidate);
            }
        }
        return Optional.empty();
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
