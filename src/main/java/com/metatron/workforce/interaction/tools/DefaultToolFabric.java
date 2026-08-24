package com.metatron.workforce.interaction.tools;

import java.util.List;
import java.util.Objects;

/** Adapter-backed implementation used by the runtime while preserving the existing ToolFabric contract. */
public final class DefaultToolFabric {
    private final List<ToolAdapter> adapters;

    public DefaultToolFabric(List<ToolAdapter> adapters) {
        Objects.requireNonNull(adapters, "adapters");
        this.adapters = List.copyOf(adapters);
    }

    public ToolResult execute(ToolRequest request) {
        Objects.requireNonNull(request, "request");
        ToolAdapter adapter = adapters.stream()
                .filter(candidate -> candidate.capability().equals(request.capability()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("tool capability is not configured: " + request.capability()));
        ToolResult result = Objects.requireNonNull(adapter.execute(request), "tool result");
        if (!request.requestId().equals(result.requestId()) || !request.capability().equals(result.capability())) {
            throw new IllegalStateException("tool result attribution mismatch");
        }
        return result;
    }
}
