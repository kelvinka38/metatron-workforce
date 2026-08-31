package com.metatron.workforce.management;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Durable boundary for Work Graph scheduling, inbox dedupe and execution dispatch coordination. */
public interface AutonomyCoordinationStateStore {
    Snapshot load();
    void save(Snapshot snapshot);

    record InboxMessage(String messageId, String idempotencyKey, String correlationId,
                        String causationId, int schemaVersion, String payload, Instant receivedAt) {}
    record DeadLetter(String referenceId, String objectiveId, String stepId, String reason, Instant createdAt) {}

    record Snapshot(
            Map<String, Integer> activeGraphVersions,
            Map<String, DurableWorkGraph> graphs,
            Map<String, DurableDispatch> dispatches,
            Map<String, InboxMessage> inbox,
            List<DeadLetter> deadLetters) {
        public Snapshot {
            activeGraphVersions = copy(activeGraphVersions);
            graphs = copy(graphs);
            dispatches = copy(dispatches);
            inbox = copy(inbox);
            deadLetters = List.copyOf(deadLetters == null ? List.of() : deadLetters);
        }
        public static Snapshot empty() { return new Snapshot(Map.of(), Map.of(), Map.of(), Map.of(), List.of()); }
        private static <K,V> Map<K,V> copy(Map<K,V> source) {
            return source == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(source));
        }
    }
}
