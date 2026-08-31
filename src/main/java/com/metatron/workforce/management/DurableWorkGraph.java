package com.metatron.workforce.management;

import com.metatron.workforce.interaction.intelligence.ExecutionWorkSpec;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Durable, versioned Workforce-managed DAG. Prior versions remain immutable history after supersession. */
public record DurableWorkGraph(
        String objectiveId,
        int graphVersion,
        Status status,
        Map<String, Node> nodes,
        Instant createdAt,
        Instant updatedAt) {

    public DurableWorkGraph {
        requireText(objectiveId, "objectiveId");
        if (graphVersion < 1) throw new IllegalArgumentException("graphVersion must be positive");
        Objects.requireNonNull(status, "status");
        nodes = Map.copyOf(nodes == null ? Map.of() : new LinkedHashMap<>(nodes));
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public enum Status { ACTIVE, SUPERSEDED, COMPLETED, CANCELLED }
    public enum NodeStatus { PENDING, DISPATCHED, SUCCEEDED, FAILED, CANCELLED }

    public record Node(
            ExecutionWorkSpec spec,
            NodeStatus status,
            int attempt,
            String dispatchId,
            List<String> evidenceReferences,
            String failure,
            Instant updatedAt) {
        public Node {
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(status, "status");
            if (attempt < 0) throw new IllegalArgumentException("attempt must be non-negative");
            dispatchId = dispatchId == null ? "" : dispatchId;
            evidenceReferences = List.copyOf(evidenceReferences == null ? List.of() : evidenceReferences);
            failure = failure == null ? "" : failure;
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
