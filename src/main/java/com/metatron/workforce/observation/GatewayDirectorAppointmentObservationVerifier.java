package com.metatron.workforce.observation;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.management.GatewayDirectorAppointmentCapability;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Independent institutional-state Observation for the canonical Gateway Director appointment. */
@Component
public final class GatewayDirectorAppointmentObservationVerifier implements ObservationVerifier {
    public static final String MARKER = "observation-capability:workforce.staffing.gateway-director";

    private final WorkforceCoreService core;
    private final WorkerRuntimeProfileBindingService runtimeProfiles;

    public GatewayDirectorAppointmentObservationVerifier(
            WorkforceCoreService core,
            WorkerRuntimeProfileBindingService runtimeProfiles) {
        this.core = java.util.Objects.requireNonNull(core, "core");
        this.runtimeProfiles = java.util.Objects.requireNonNull(runtimeProfiles, "runtimeProfiles");
    }

    @Override
    public boolean supports(ObservationRequirement requirement) {
        return requirement.evidenceRequirements().contains(MARKER);
    }

    @Override
    public Optional<ObservationReport> observe(
            ObservationRequirement requirement,
            List<String> executionEvidenceReferences,
            Instant at) {
        List<String> evidence = new ArrayList<>();
        boolean workerActive = core.allWorkers().stream().anyMatch(worker ->
                GatewayDirectorAppointmentCapability.WORKER_ID.equals(worker.workerId())
                        && worker.status() == WorkforceCoreService.WorkerStatus.ACTIVE);
        boolean roleActive = core.participations(GatewayDirectorAppointmentCapability.WORKER_ID).stream()
                .anyMatch(participation ->
                        participation.status() == WorkforceCoreService.ParticipationStatus.ACTIVE
                                && GatewayDirectorAppointmentCapability.ROLE_REF.equals(participation.roleRef())
                                && GatewayDirectorAppointmentCapability.POSITION_REF.equals(participation.positionRef()));
        boolean appointmentCapability = core.capabilities(GatewayDirectorAppointmentCapability.WORKER_ID).stream()
                .anyMatch(capability ->
                        GatewayDirectorAppointmentCapability.CAPABILITY.equals(capability.capabilityRef())
                                && capability.level() >= 1.0);
        boolean gatewayAuditCapability = core.capabilities(GatewayDirectorAppointmentCapability.WORKER_ID).stream()
                .anyMatch(capability ->
                        GatewayDirectorAppointmentCapability.GATEWAY_AUDIT_CAPABILITY.equals(capability.capabilityRef())
                                && capability.level() >= 1.0);
        var binding = runtimeProfiles.find(GatewayDirectorAppointmentCapability.WORKER_ID);
        boolean runtimeBound = binding.isPresent()
                && WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE
                        .equals(binding.orElseThrow().profile().profileRef())
                && binding.orElseThrow().profile().writableWorkspace()
                && !binding.orElseThrow().profile().actionRefs().isEmpty();

        evidence.add("observation-gateway-director-worker-active:" + workerActive);
        evidence.add("observation-gateway-director-role-active:" + roleActive);
        evidence.add("observation-gateway-director-appointment-capability:" + appointmentCapability);
        evidence.add("observation-gateway-director-audit-capability:" + gatewayAuditCapability);
        evidence.add("observation-gateway-director-runtime-bound:" + runtimeBound);

        boolean pass = workerActive && roleActive && appointmentCapability && gatewayAuditCapability && runtimeBound;
        return Optional.of(new ObservationReport(
                "observation:gateway-director:" + requirement.requirementId() + ":" + at.toEpochMilli(),
                requirement.requirementId(),
                requirement.objectiveId(),
                requirement.target(),
                pass
                        ? "independent Workforce Core/runtime state proves the canonical Gateway Director appointment"
                        : "Gateway Director appointment state is incomplete",
                "independent-workforce-core-and-runtime-profile-read",
                at,
                at,
                List.copyOf(evidence),
                0.99,
                ObservationReport.Quality.HIGH,
                pass ? "" : "workerActive=" + workerActive
                        + ",roleActive=" + roleActive
                        + ",appointmentCapability=" + appointmentCapability
                        + ",gatewayAuditCapability=" + gatewayAuditCapability
                        + ",runtimeBound=" + runtimeBound,
                pass ? ObservationReport.CriterionResult.PASS : ObservationReport.CriterionResult.FAIL));
    }
}
