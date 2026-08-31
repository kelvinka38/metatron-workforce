package com.metatron.workforce.observation;

import java.util.LinkedHashMap;
import java.util.Map;

public interface ObservationStateStore {
    Snapshot load();
    void save(Snapshot snapshot);

    record Snapshot(Map<String, ObservationRequirement> requirements,
                    Map<String, ObservationReport> reportsByRequirement) {
        public Snapshot {
            requirements = copy(requirements);
            reportsByRequirement = copy(reportsByRequirement);
        }
        public static Snapshot empty() { return new Snapshot(Map.of(), Map.of()); }
        private static <K,V> Map<K,V> copy(Map<K,V> source) {
            return source == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(source));
        }
    }
}
