package com.metatron.workforce.interaction.tools;

import java.util.Map;
import java.util.Objects;

/** Tool fabric that enforces explicit capability policy before dispatch. */
public final class PolicyToolFabric implements ToolAdapter {
    private final Map<String, ToolAdapter> adapters;
    private final CapabilityPolicy policy;

    public PolicyToolFabric(Map<String, ToolAdapter> adapters, CapabilityPolicy policy) {
        this.adapters = Map.copyOf(Objects.requireNonNull(adapters, "adapters"));
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public String capability() { return "tool.fabric"; }

    public ToolResult execute(ToolRequest request) {
        Objects.requireNonNull(request, "request");
        if (!policy.allows(request.capability())) return ToolResult.failure(request, "capability_denied");
        ToolAdapter adapter = adapters.get(request.capability());
        if (adapter == null) return ToolResult.failure(request, "capability_unavailable");
        return adapter.execute(request);
    }
}
