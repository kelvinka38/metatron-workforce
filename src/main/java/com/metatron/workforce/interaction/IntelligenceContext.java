package com.metatron.workforce.interaction;

import com.metatron.workforce.interaction.knowledge.KnowledgeFabric;
import com.metatron.workforce.interaction.tools.ToolFabric;

import java.util.List;
import java.util.Objects;

/**
 * Resolved context supplied to intelligence. External information is explicit and evidence-backed.
 */
public record IntelligenceContext(
        String objective,
        List<KnowledgeFabric.KnowledgeItem> knowledge,
        List<ToolFabric.ToolResult> toolResults,
        List<String> workerReferences) {
    public IntelligenceContext {
        Objects.requireNonNull(objective, "objective");
        Objects.requireNonNull(knowledge, "knowledge");
        Objects.requireNonNull(toolResults, "toolResults");
        Objects.requireNonNull(workerReferences, "workerReferences");
        knowledge = List.copyOf(knowledge);
        toolResults = List.copyOf(toolResults);
        workerReferences = List.copyOf(workerReferences);
        if (objective.isBlank()) throw new IllegalArgumentException("objective must not be blank");
    }
}
