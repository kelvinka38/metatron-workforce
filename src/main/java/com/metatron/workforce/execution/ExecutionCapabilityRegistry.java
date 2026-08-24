package com.metatron.workforce.execution;

import java.util.Map;
import java.util.Objects;

/** Immutable registry for explicitly approved execution capabilities. */
public final class ExecutionCapabilityRegistry {
    private final Map<String, ExecutionCapability> capabilities;

    public ExecutionCapabilityRegistry(Map<String, ExecutionCapability> capabilities) {
        this.capabilities = Map.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
    }

    public ExecutionCapability require(String capability) {
        ExecutionCapability result = capabilities.get(capability);
        if (result == null) {
            throw new IllegalArgumentException("execution_capability_not_registered:" + capability);
        }
        return result;
    }
}
