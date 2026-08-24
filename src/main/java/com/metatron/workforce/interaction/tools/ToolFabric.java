package com.metatron.workforce.interaction.tools;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Capability boundary for deterministic and external operations available to Workers.
 * Tool use is distinct from LLM reasoning and is governed by capability policy.
 */
public interface ToolFabric {
    ToolResult execute(ToolRequest request);

    record ToolRequest(
            String requester,
            String capability,
            String objective,
            Map<String, String> arguments,
            List<String> authorityContext) {
        public ToolRequest {
            Objects.requireNonNull(requester, "requester");
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(objective, "objective");
            Objects.requireNonNull(arguments, "arguments");
            Objects.requireNonNull(authorityContext, "authorityContext");
            arguments = Map.copyOf(arguments);
            authorityContext = List.copyOf(authorityContext);
            if (requester.isBlank() || capability.isBlank() || objective.isBlank()) {
                throw new IllegalArgumentException("requester, capability and objective must not be blank");
            }
        }
    }

    record ToolResult(
            String tool,
            String status,
            String output,
            String evidenceReference) {
        public ToolResult {
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(evidenceReference, "evidenceReference");
            if (tool.isBlank() || status.isBlank()) {
                throw new IllegalArgumentException("tool and status must not be blank");
            }
        }
    }
}
