package com.metatron.workforce.operating;

import java.util.List;

/** Durable Workforce-owned state for Position operating contracts and Worker operating reality. */
public interface WorkerConstitutionStateStore {
    Snapshot load();
    void save(Snapshot snapshot);

    record Snapshot(
            List<WorkerConstitutionService.PositionOperatingContract> positionContracts,
            List<WorkerConstitutionService.WorkerPositionBinding> bindings,
            List<WorkerConstitutionService.PerformanceEvaluation> performanceEvaluations,
            List<WorkerConstitutionService.ExperienceRecord> experiences,
            List<WorkerConstitutionService.LearningRecord> learningRecords) {
        public Snapshot {
            positionContracts = List.copyOf(positionContracts == null ? List.of() : positionContracts);
            bindings = List.copyOf(bindings == null ? List.of() : bindings);
            performanceEvaluations = List.copyOf(performanceEvaluations == null ? List.of() : performanceEvaluations);
            experiences = List.copyOf(experiences == null ? List.of() : experiences);
            learningRecords = List.copyOf(learningRecords == null ? List.of() : learningRecords);
        }

        public static Snapshot empty() {
            return new Snapshot(List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }
}
