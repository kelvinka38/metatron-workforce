package com.metatron.workforce.interaction.intelligence;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Durable evidence store for the cognition capacity sub-lifecycle. */
public interface CognitionCapacityEventStore {
    void append(CognitionCapacityEvent event);
    List<CognitionCapacityEvent> events();

    default Optional<CognitionCapacityEvent> latest(String requestId) {
        return events().stream()
                .filter(event -> event.requestId().equals(requestId))
                .max(Comparator.comparing(CognitionCapacityEvent::observedAt));
    }

    /** Mark non-terminal requests from a prior process as requiring truthful reconciliation. */
    default int reconcileIncomplete() { return 0; }
}
