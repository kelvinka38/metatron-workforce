package com.metatron.workforce.observation;

import java.util.LinkedHashMap;
import java.util.Map;

public interface ObservationStateStore {
    Snapshot load();
    void save(Snapshot snapshot);

    record Snapshot(Map<String, ObservationRequirement> requirements,
                    Map<String, ObservationReport> reportsByRequirement,
                    Map<String, Integer> attemptsByRequirement) {
        public Snapshot {
            requirements = copy(requirements);
            reportsByRequirement = copy(reportsByRequirement);
            attemptsByRequirement = copy(attemptsByRequirement);
        }

        /** Compatibility constructor for pre-recovery callers and persisted schema evolution. */
        public Snapshot(Map<String, ObservationRequirement> requirements,
                        Map<String, ObservationReport> reportsByRequirement) {
            this(requirements, reportsByRequirement, Map.of());
        }

        public static Snapshot empty() { return new Snapshot(Map.of(), Map.of(), Map.of()); }

        private static <K,V> Map<K,V> copy(Map<K,V> source) {
            return source == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(source));
        }
    }
}
