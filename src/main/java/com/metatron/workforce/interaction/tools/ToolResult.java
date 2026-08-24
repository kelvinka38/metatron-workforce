package com.metatron.workforce.interaction.tools;

import java.util.List;
import java.util.Objects;

public record ToolResult(
        String requestId,
        String capability,
        String target,
        String operation,
        boolean success,
        String output,
        List<String> evidenceReferences) {
    public ToolResult {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(evidenceReferences, "evidenceReferences");
        evidenceReferences = List.copyOf(evidenceReferences);
    }
}
