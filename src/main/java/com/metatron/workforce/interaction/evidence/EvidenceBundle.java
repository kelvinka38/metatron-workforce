package com.metatron.workforce.interaction.evidence;

import java.util.List;
import java.util.Objects;

public record EvidenceBundle(List<Evidence> items) {
    public EvidenceBundle {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }

    public boolean isEmpty() { return items.isEmpty(); }
}
