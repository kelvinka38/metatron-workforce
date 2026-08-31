package com.metatron.workforce.core;

import java.util.LinkedHashMap;
import java.util.Map;

public interface WorkforceCoreStateStore {
    Snapshot load();
    void save(Snapshot snapshot);

    record Snapshot(
            Map<String, WorkforceCoreService.Participant> participants,
            Map<String, WorkforceCoreService.Worker> workers,
            Map<String, WorkforceCoreService.Participation> participations,
            Map<String, Map<String, WorkforceCoreService.Capability>> capabilities,
            Map<String, Map<String, WorkforceCoreService.Qualification>> qualifications,
            Map<String, WorkforceCoreService.Availability> availability,
            Map<String, WorkforceCoreService.Assignment> assignments,
            Map<String, WorkforceCoreService.CapacityReservation> capacityReservations) {
        public Snapshot {
            participants = copy(participants);
            workers = copy(workers);
            participations = copy(participations);
            capabilities = nestedCopy(capabilities);
            qualifications = nestedCopy(qualifications);
            availability = copy(availability);
            assignments = copy(assignments);
            capacityReservations = copy(capacityReservations);
        }

        /** Backward-compatible constructor for snapshots created before capacity reservation. */
        public Snapshot(
                Map<String, WorkforceCoreService.Participant> participants,
                Map<String, WorkforceCoreService.Worker> workers,
                Map<String, WorkforceCoreService.Participation> participations,
                Map<String, Map<String, WorkforceCoreService.Capability>> capabilities,
                Map<String, Map<String, WorkforceCoreService.Qualification>> qualifications,
                Map<String, WorkforceCoreService.Availability> availability,
                Map<String, WorkforceCoreService.Assignment> assignments) {
            this(participants, workers, participations, capabilities, qualifications,
                    availability, assignments, Map.of());
        }

        public static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
        private static <K,V> Map<K,V> copy(Map<K,V> source) {
            return source == null ? Map.of() : Map.copyOf(source);
        }
        private static <K1,K2,V> Map<K1,Map<K2,V>> nestedCopy(Map<K1,Map<K2,V>> source) {
            if (source == null) return Map.of();
            Map<K1,Map<K2,V>> copy = new LinkedHashMap<>();
            source.forEach((k,v) -> copy.put(k, Map.copyOf(v)));
            return Map.copyOf(copy);
        }
    }
}
