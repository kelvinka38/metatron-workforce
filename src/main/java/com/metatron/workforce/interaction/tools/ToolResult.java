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

    public static ToolResult success(ToolRequest request, String output) {
        Objects.requireNonNull(request, "request");
        return new ToolResult(
                request.requestId(),
                request.capability(),
                request.target(),
                request.operation(),
                true,
                output == null ? "" : output,
                List.of(request.target()));
    }

    public static ToolResult failure(ToolRequest request, String reason) {
        Objects.requireNonNull(request, "request");
        return new ToolResult(
                request.requestId(),
                request.capability(),
                request.target(),
                request.operation(),
                false,
                reason == null ? "" : reason,
                List.of());
    }

    /** Backward-compatible result factory for legacy adapter tests. */
    public static ToolResult success(String output) {
        return new ToolResult("legacy", "legacy", "legacy", "legacy", true,
                output == null ? "" : output, List.of());
    }

    /** Backward-compatible result factory for legacy policy tests. */
    public static ToolResult failure(String reason) {
        return new ToolResult("legacy", "legacy", "legacy", "legacy", false,
                reason == null ? "" : reason, List.of());
    }

    /** Compatibility alias for the former result-message contract. */
    public String message() {
        return output;
    }
}
