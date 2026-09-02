package com.metatron.workforce.runtime;

import com.metatron.workforce.management.AutonomousExecutionCapability;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Actual-effect consumer for governed runtime commands.
 *
 * <p>This service is deliberately downstream of institutional admission. It does not allocate a Worker,
 * mint an Assignment, create Authorization or invent a Dispatch. It requires those bindings to already
 * be present on the command, re-checks them against the selected capability, then invokes the real
 * capability adapter and verifies returned attribution before reporting the effect.</p>
 */
@Service
public final class RuntimeCapabilityExecutionService {
    private final Map<String, AutonomousExecutionCapability> capabilities;

    public RuntimeCapabilityExecutionService(List<AutonomousExecutionCapability> capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        Map<String, AutonomousExecutionCapability> indexed = new LinkedHashMap<>();
        for (AutonomousExecutionCapability capability : capabilities) {
            Objects.requireNonNull(capability, "capability");
            String ref = require(capability.capabilityRef(), "capabilityRef");
            if (indexed.putIfAbsent(ref, capability) != null) {
                throw new IllegalStateException("duplicate runtime execution capability: " + ref);
            }
        }
        this.capabilities = Map.copyOf(indexed);
    }

    public RuntimeExecutionResult execute(RuntimeExecutionCommand command) {
        Objects.requireNonNull(command, "command");
        if (!command.governedBound()) {
            throw new SecurityException("runtime-command-missing-governed-binding");
        }

        String capabilityRef = require(command.workSpec().requiredCapability(), "requiredCapability");
        AutonomousExecutionCapability capability = capabilities.get(capabilityRef);
        if (capability == null) {
            throw new IllegalArgumentException("runtime-capability-unavailable:" + capabilityRef);
        }
        if (!capability.supportsWorker(command.workerId())) {
            throw new SecurityException("runtime-worker-not-admitted-for-capability:" + command.workerId());
        }

        String expectedAuthorization = require(capability.authorizationReference(), "capability.authorizationReference");
        if (!expectedAuthorization.equals(command.authorizationReference())) {
            throw new SecurityException("runtime-authorization-mismatch:capability=" + capabilityRef);
        }
        String authorityReference = require(capability.authorityReference(), "capability.authorityReference");

        AutonomousExecutionCapability.CapabilityRequest request = new AutonomousExecutionCapability.CapabilityRequest(
                command.humanId(),
                command.organizationContextId(),
                command.objectiveId(),
                command.workSpec(),
                command.workerId(),
                command.assignmentReference(),
                command.authorizationReference(),
                command.dispatchReference(),
                command.dispatchAttempt());

        AutonomousExecutionCapability.CapabilityResult result = Objects.requireNonNull(
                capability.execute(request), "runtime capability result");
        if (!command.workerId().equals(result.workerId())) {
            throw new IllegalStateException("runtime-result-worker-attribution-mismatch");
        }
        if (!command.assignmentReference().equals(result.assignmentReference())) {
            throw new IllegalStateException("runtime-result-assignment-attribution-mismatch");
        }

        List<String> evidence = new ArrayList<>(result.evidenceReferences());
        evidence.add("runtime-actual-effect:execution=" + command.executionId()
                + ":runtime=" + command.runtimeId()
                + ":objective=" + command.objectiveId()
                + ":worker=" + command.workerId()
                + ":assignment=" + command.assignmentReference()
                + ":authorization=" + command.authorizationReference()
                + ":authority=" + authorityReference
                + ":dispatch=" + command.dispatchReference()
                + ":attempt=" + command.dispatchAttempt()
                + ":capability=" + capabilityRef
                + ":success=" + result.success());

        return new RuntimeExecutionResult(
                command.executionId(), command.runtimeId(), command.objectiveId(), command.workerId(),
                command.assignmentReference(), command.authorizationReference(), authorityReference,
                capabilityRef, result.success(), result.workReference(), evidence, result.summary());
    }

    public List<String> capabilityCatalog() {
        return capabilities.keySet().stream().sorted().toList();
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }
}
