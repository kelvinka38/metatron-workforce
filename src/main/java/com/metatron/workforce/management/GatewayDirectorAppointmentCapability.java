package com.metatron.workforce.management;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Governed institutional appointment proof for the canonical Gateway Director / Head of Gateway.
 *
 * The actual Participant/Worker/Participation formation happens through AutonomousStaffingService
 * before this capability is dispatched. This adapter proves the resulting institutional state and
 * runtime binding instead of fabricating a chat-only "created" response.
 */
@Component
public final class GatewayDirectorAppointmentCapability implements AutonomousExecutionCapability {
    public static final String CAPABILITY = "workforce.staffing.gateway-director";
    public static final String FOUNDER_HUMAN_ID = "human-primary";
    public static final String WORKER_ID = "WORKER-GATEWAY-DIRECTOR";
    public static final String ROLE_REF = "ROLE-HEAD-OF-GATEWAY";
    public static final String POSITION_REF = "position:gateway-director";
    public static final String GATEWAY_AUDIT_CAPABILITY = "gateway.audit.read";
    public static final String OPERATIONAL_MANAGEMENT_CAPABILITY = "gateway.operational.management";
    public static final String RELIABILITY_MANAGEMENT_CAPABILITY = "gateway.reliability.management";
    public static final String CAPACITY_COST_MANAGEMENT_CAPABILITY = "gateway.capacity.cost.management";
    public static final String INCIDENT_RECOVERY_CAPABILITY = "gateway.incident.recovery.coordination";
    public static final String AUTHORITY_REFERENCE = "policy:founder-gateway-director-appointment:v1";
    public static final String AUTHORIZATION_REFERENCE = "authorization:founder-gateway-director-appointment:v1";

    private final WorkforceCoreService core;
    private final WorkerRuntimeProfileBindingService runtimeProfiles;

    public GatewayDirectorAppointmentCapability(WorkforceCoreService core,
                                                WorkerRuntimeProfileBindingService runtimeProfiles) {
        this.core = Objects.requireNonNull(core, "core");
        this.runtimeProfiles = Objects.requireNonNull(runtimeProfiles, "runtimeProfiles");
    }

    @Override public String capabilityRef() { return CAPABILITY; }
    @Override public String capabilityDescription() {
        return CAPABILITY + " — form/appoint and prove the canonical Gateway Director Worker";
    }
    @Override public String authorityReference() { return AUTHORITY_REFERENCE; }
    @Override public String authorizationReference() { return AUTHORIZATION_REFERENCE; }
    @Override public double requiredCapacity() { return 1.0; }
    @Override public boolean supportsWorker(String workerId) { return WORKER_ID.equals(workerId); }

    @Override
    public CapabilityResult execute(CapabilityRequest request) {
        Objects.requireNonNull(request, "request");
        if (!FOUNDER_HUMAN_ID.equals(request.humanId())) {
            throw new SecurityException("Founder identity required for Gateway Director appointment");
        }
        if (!request.allocated()) throw new SecurityException("governed Gateway Director allocation required");
        if (!WORKER_ID.equals(request.allocatedWorkerId())) {
            throw new SecurityException("Gateway Director worker mismatch");
        }
        if (!AUTHORIZATION_REFERENCE.equals(request.authorizationReference())) {
            throw new SecurityException("Gateway Director appointment authorization mismatch");
        }

        WorkforceCoreService.Worker worker = core.worker(WORKER_ID);
        if (worker.status() != WorkforceCoreService.WorkerStatus.ACTIVE) {
            throw new IllegalStateException("Gateway Director worker is not ACTIVE");
        }

        WorkforceCoreService.Participation participation = core.participations(WORKER_ID).stream()
                .filter(item -> item.status() == WorkforceCoreService.ParticipationStatus.ACTIVE)
                .filter(item -> ROLE_REF.equals(item.roleRef()))
                .filter(item -> POSITION_REF.equals(item.positionRef()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Gateway Director active role/position participation missing"));

        java.util.Set<String> approvedCapabilities = core.capabilities(WORKER_ID).stream()
                .filter(item -> item.level() >= 1.0)
                .map(WorkforceCoreService.Capability::capabilityRef)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Set<String> requiredCapabilities = java.util.Set.of(
                CAPABILITY,
                GATEWAY_AUDIT_CAPABILITY,
                OPERATIONAL_MANAGEMENT_CAPABILITY,
                RELIABILITY_MANAGEMENT_CAPABILITY,
                CAPACITY_COST_MANAGEMENT_CAPABILITY,
                INCIDENT_RECOVERY_CAPABILITY);
        if (!approvedCapabilities.containsAll(requiredCapabilities)) {
            java.util.Set<String> missing = new java.util.LinkedHashSet<>(requiredCapabilities);
            missing.removeAll(approvedCapabilities);
            throw new IllegalStateException("Gateway Director approved capability bundle incomplete: missing=" + missing);
        }

        WorkerRuntimeProfileBindingService.Binding binding = runtimeProfiles.requireBinding(WORKER_ID);
        if (!WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE.equals(binding.profile().profileRef())) {
            throw new IllegalStateException("Gateway Director usable runtime profile missing");
        }

        List<String> evidence = new ArrayList<>();
        evidence.add("worker:" + worker.workerId() + ":status=" + worker.status());
        evidence.add("participation:" + participation.participationId() + ":role=" + participation.roleRef()
                + ":position=" + participation.positionRef());
        requiredCapabilities.stream().sorted().forEach(capability -> evidence.add("capability:" + capability));
        evidence.add("runtime-profile-bound:" + binding.profile().profileRef());
        evidence.add("runtime-actions=" + binding.profile().actionRefs().stream().sorted().toList());
        if (request.dispatchBound()) {
            evidence.add("dispatch:" + request.dispatchReference() + ":attempt=" + request.dispatchAttempt());
        }

        return new CapabilityResult(
                true,
                request.allocatedWorkerId(),
                request.assignmentReference(),
                "workforce-worker:" + WORKER_ID,
                List.copyOf(evidence),
                "Gateway Director Worker formed, appointed, capability-attested and runtime-bound");
    }
}
