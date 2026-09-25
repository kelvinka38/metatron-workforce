package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.runtime.WorkerResourceScopeService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Governed appointment proof for the Head of Aquaculture (HOA). Formation happens through
 * AutonomousStaffingService with {@link AquacultureHeadStaffingPolicy}; this capability proves the resulting
 * institutional state (Worker, participation, capabilities, runtime profile, resource scope) instead of
 * returning a chat-only "created" claim. Only the Founder (human-primary) may appoint.
 */
@Component
public final class AquacultureHeadAppointmentCapability implements AutonomousExecutionCapability {
    public static final String CAPABILITY = "workforce.staffing.head-of-aquaculture";
    public static final String FOUNDER_HUMAN_ID = "human-primary";
    public static final String WORKER_ID = "WORKER-HEAD-OF-AQUACULTURE";
    public static final String ROLE_REF = "ROLE-HEAD-OF-AQUACULTURE";
    public static final String POSITION_REF = "position:head-of-aquaculture";
    public static final String ORGANIZATION_REF = "bios";
    public static final String PLANNING_CAPABILITY = AquacultureDomainPlanningCapability.CAPABILITY;
    public static final String REPORTING_CAPABILITY = "aquaculture.reporting";
    public static final String DELEGATION_CAPABILITY = "aquaculture.delegation";
    public static final String WEB_READ_CAPABILITY = "research.web.read";
    public static final String BIOS_PROPOSAL_CAPABILITY = "repository.bios.proposal";
    public static final String AUTHORITY_REFERENCE = "policy:founder-aquaculture-head-appointment:v1";
    public static final String AUTHORIZATION_REFERENCE = "authorization:founder-aquaculture-head-appointment:v1";

    static final Set<String> REQUIRED_CAPABILITIES = Set.of(
            CAPABILITY, PLANNING_CAPABILITY, REPORTING_CAPABILITY, DELEGATION_CAPABILITY,
            WEB_READ_CAPABILITY, BIOS_PROPOSAL_CAPABILITY);

    private final WorkforceCoreService core;
    private final WorkerRuntimeProfileBindingService runtimeProfiles;
    private final WorkerResourceScopeService resourceScopes;

    public AquacultureHeadAppointmentCapability(WorkforceCoreService core,
                                                WorkerRuntimeProfileBindingService runtimeProfiles,
                                                WorkerResourceScopeService resourceScopes) {
        this.core = Objects.requireNonNull(core, "core");
        this.runtimeProfiles = Objects.requireNonNull(runtimeProfiles, "runtimeProfiles");
        this.resourceScopes = Objects.requireNonNull(resourceScopes, "resourceScopes");
    }

    @Override public String capabilityRef() { return CAPABILITY; }
    @Override public String capabilityDescription() {
        return CAPABILITY + " — form/appoint and prove the Head of Aquaculture Worker (BIOS)";
    }
    @Override public String authorityReference() { return AUTHORITY_REFERENCE; }
    @Override public String authorizationReference() { return AUTHORIZATION_REFERENCE; }
    @Override public double requiredCapacity() { return 1.0; }
    @Override public boolean supportsWorker(String workerId) { return WORKER_ID.equals(workerId); }

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        Objects.requireNonNull(request, "request");
        if (!FOUNDER_HUMAN_ID.equals(request.humanId())) {
            throw new SecurityException("Founder identity required for Head of Aquaculture appointment");
        }
        if (!request.allocated()) throw new SecurityException("governed Head of Aquaculture allocation required");
        if (!WORKER_ID.equals(request.allocatedWorkerId())) {
            throw new SecurityException("Head of Aquaculture worker mismatch");
        }
        if (!AUTHORIZATION_REFERENCE.equals(request.authorizationReference())) {
            throw new SecurityException("Head of Aquaculture appointment authorization mismatch");
        }

        WorkforceCoreService.Worker worker = core.worker(WORKER_ID);
        if (worker.status() != WorkforceCoreService.WorkerStatus.ACTIVE) {
            throw new IllegalStateException("Head of Aquaculture worker is not ACTIVE");
        }
        WorkforceCoreService.Participation participation = core.participations(WORKER_ID).stream()
                .filter(item -> item.status() == WorkforceCoreService.ParticipationStatus.ACTIVE)
                .filter(item -> ROLE_REF.equals(item.roleRef()))
                .filter(item -> POSITION_REF.equals(item.positionRef()))
                .filter(item -> ORGANIZATION_REF.equals(item.organizationRef()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Head of Aquaculture active role/position participation missing"));

        Set<String> approved = core.capabilities(WORKER_ID).stream()
                .filter(item -> item.level() >= 1.0)
                .map(WorkforceCoreService.Capability::capabilityRef)
                .collect(Collectors.toSet());
        if (!approved.containsAll(REQUIRED_CAPABILITIES)) {
            Set<String> missing = new LinkedHashSet<>(REQUIRED_CAPABILITIES);
            missing.removeAll(approved);
            throw new IllegalStateException("Head of Aquaculture capability bundle incomplete: missing=" + missing);
        }

        WorkerRuntimeProfileBindingService.Binding binding = runtimeProfiles.requireBinding(WORKER_ID);
        if (!WorkerRuntimeProfileBindingService.DOMAIN_HEAD_PROFILE.equals(binding.profile().profileRef())) {
            throw new IllegalStateException("Head of Aquaculture domain-head runtime profile missing");
        }
        WorkerResourceScopeService.Scope scope = resourceScopes.find(WORKER_ID)
                .orElseThrow(() -> new IllegalStateException("Head of Aquaculture resource scope missing"));
        if (!scope.repositories().equals(AquacultureHeadStaffingPolicy.REPOSITORIES)
                || !scope.writePathPrefixes().equals(AquacultureHeadStaffingPolicy.WRITE_PATH_PREFIXES)) {
            throw new IllegalStateException("Head of Aquaculture resource scope differs from the approved envelope");
        }

        List<String> evidence = new ArrayList<>();
        evidence.add("worker:" + worker.workerId() + ":status=" + worker.status());
        evidence.add("participation:" + participation.participationId() + ":role=" + participation.roleRef()
                + ":position=" + participation.positionRef());
        evidence.add("organization:" + participation.organizationRef());
        REQUIRED_CAPABILITIES.stream().sorted().forEach(capability -> evidence.add("capability:" + capability));
        evidence.add("runtime-profile-bound:" + binding.profile().profileRef());
        evidence.add("runtime-actions=" + binding.profile().actionRefs().stream().sorted().toList());
        evidence.add("runtime-executables=" + binding.profile().allowedExecutables().stream().sorted().toList());
        evidence.add("resource-scope:repositories=" + scope.repositories().stream().sorted().toList());
        evidence.add("resource-scope:write-prefixes=" + scope.writePathPrefixes());
        evidence.add("authority:" + AUTHORITY_REFERENCE);
        if (request.dispatchBound()) {
            evidence.add("dispatch:" + request.dispatchReference() + ":attempt=" + request.dispatchAttempt());
        }
        return new CapabilityResult(
                true,
                request.allocatedWorkerId(),
                request.assignmentReference(),
                "workforce-worker:" + WORKER_ID,
                List.copyOf(evidence),
                "Head of Aquaculture Worker formed, appointed, capability-attested, runtime-bound and resource-scoped");
    }
}
