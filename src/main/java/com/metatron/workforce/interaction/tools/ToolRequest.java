package com.metatron.workforce.interaction.tools;

import java.util.List;
import java.util.Objects;

public record ToolRequest(
        String requestId,
        String requester,
        String capability,
        String target,
        String operation,
        String input,
        List<String> authorityContext) {
    public ToolRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(authorityContext, "authorityContext");
        authorityContext = List.copyOf(authorityContext);
        if (requestId.isBlank() || requester.isBlank() || capability.isBlank() || target.isBlank() || operation.isBlank()) {
            throw new IllegalArgumentException("tool request identity fields must not be blank");
        }
    }
}
