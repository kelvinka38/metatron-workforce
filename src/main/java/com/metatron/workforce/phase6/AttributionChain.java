package com.metatron.workforce.phase6;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record AttributionChain(List<Entry> entries) {
    public AttributionChain {
        Objects.requireNonNull(entries, "entries");
        entries = List.copyOf(entries);
        if (entries.isEmpty()) throw new IllegalArgumentException("entries must not be empty");
        Instant previous = null;
        for (Entry entry : entries) {
            Objects.requireNonNull(entry, "entry");
            if (previous != null && entry.at().isBefore(previous)) throw new IllegalArgumentException("attribution entries must be chronological");
            previous = entry.at();
        }
    }

    public record Entry(String actorId, Kind kind, String reference, Instant at) {
        public enum Kind { HUMAN_INSTRUCTION, DECISION, AUTHORIZATION, ASSIGNMENT, WORKER_EXECUTION, RUNTIME, OUTCOME }
        public Entry {
            if (actorId == null || actorId.isBlank()) throw new IllegalArgumentException("actorId must not be blank");
            if (reference == null || reference.isBlank()) throw new IllegalArgumentException("reference must not be blank");
            Objects.requireNonNull(kind, "kind"); Objects.requireNonNull(at, "at");
        }
    }
}
