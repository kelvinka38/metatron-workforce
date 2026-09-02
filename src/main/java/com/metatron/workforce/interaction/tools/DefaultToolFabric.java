package com.metatron.workforce.interaction.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Adapter-backed implementation used by the runtime while preserving the existing ToolFabric contract. */
public final class DefaultToolFabric {
    private final List<ToolAdapter> adapters;

    public DefaultToolFabric(List<ToolAdapter> adapters) {
        Objects.requireNonNull(adapters, "adapters");
        List<ToolAdapter> configured = new ArrayList<>(adapters);
        boolean hasPrimaryWebSearch = configured.stream().anyMatch(WebSearchToolAdapter.class::isInstance);
        boolean hasSemanticRecovery = configured.stream().anyMatch(SemanticQualifierWebSearchRecoveryAdapter.class::isInstance);
        if (hasPrimaryWebSearch && !hasSemanticRecovery) {
            configured.add(new SemanticQualifierWebSearchRecoveryAdapter());
        }
        this.adapters = List.copyOf(configured);
    }

    public ToolResult execute(ToolRequest request) {
        Objects.requireNonNull(request, "request");
        List<ToolAdapter> candidates = adapters.stream()
                .filter(candidate -> candidate.capability().equals(request.capability()))
                .toList();
        if (candidates.isEmpty()) {
            throw new IllegalStateException("tool capability is not configured: " + request.capability());
        }

        List<String> failures = new ArrayList<>();
        for (ToolAdapter adapter : candidates) {
            ToolResult result = Objects.requireNonNull(adapter.execute(request), "tool result");
            validateAttribution(request, result);
            if (result.success()) return result;
            failures.add(adapter.getClass().getSimpleName() + "=" + result.output());
        }

        if (candidates.size() == 1) {
            return ToolResult.failure(request, failures.getFirst().substring(failures.getFirst().indexOf('=') + 1));
        }
        return ToolResult.failure(request, "all_tool_adapters_failed:" + failures);
    }

    private static void validateAttribution(ToolRequest request, ToolResult result) {
        if (!request.requestId().equals(result.requestId()) || !request.capability().equals(result.capability())) {
            throw new IllegalStateException("tool result attribution mismatch");
        }
    }
}
