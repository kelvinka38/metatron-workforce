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
    public static final String WORKER_ID = "WORKER-GATEWAY-DIRECTOR";
    public static final String ROLE_REF = "ROLE-HEAD-OF-GATEWAY";
    public static final String POSITION_REF = "position:gateway-director";
    public static final String GATEWAY_AUDIT_CAPABILITY = "gateway.audit.read";
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

        boolean appointmentCapability = core.capabilities(WORKER_ID).stream()
                .anyMatch(item -> CAPABILITY.equals(item.capabilityRef()) && item.level() >= 1.0);
        boolean gatewayAuditCapability = core.capabilities(WORKER_ID).stream()
                .anyMatch(item -> GATEWAY_AUDIT_CAPABILITY.equals(item.capabilityRef()) && item.level() >= 1.0);
        if (!appointmentCapability || !gatewayAuditCapability) {
            throw new IllegalStateException("Gateway Director approved capability bundle incomplete");
        }

        WorkerRuntimeProfileBindingService.Binding binding = runtimeProfiles.requireBinding(WORKER_ID);
        if (!WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE.equals(binding.profile().profileRef())) {
            throw new IllegalStateException("Gateway Director usable runtime profile missing");
        }

        List<String> evidence = new ArrayList<>();
        evidence.add("worker:" + worker.workerId() + ":status=" + worker.status());
        evidence.add("participation:" + participation.participationId() + ":role=" + participation.roleRef()
                + ":position=" + participation.positionRef());
        evidence.add("capability:" + CAPABILITY);
        evidence.add("capability:" + GATEWAY_AUDIT_CAPABILITY);
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
