package com.metatron.workforce.workplace;

import java.util.LinkedHashMap;
import java.util.Map;

public interface WorkplaceContinuityStateStore {
    Snapshot load();
    void save(Snapshot snapshot);

    record Snapshot(Map<String, WorkplaceContinuityRecord> continuity,
                    Map<String, WorkplaceDelivery> deliveries) {
        public Snapshot {
            continuity = copy(continuity);
            deliveries = copy(deliveries);
        }
        public static Snapshot empty() { return new Snapshot(Map.of(), Map.of()); }
        private static <K,V> Map<K,V> copy(Map<K,V> source) {
            return source == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(source));
        }
    }
}
