package com.metatron.workforce.core;

import com.metatron.workforce.release.FileReleaseEvidenceStateStore;
import com.metatron.workforce.release.ReleaseControlService;
import com.metatron.workforce.release.ReleaseEvidenceCompletionGate;
import com.metatron.workforce.release.ReleaseEvidenceStateStore;
import com.metatron.workforce.release.ReleaseEvidenceStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class WorkforceCoreConfiguration {
    @Bean
    WorkforceCoreStateStore workforceCoreStateStore(
            @Value("${METATRON_WORKFORCE_CORE_STATE_PATH:/var/lib/metatron-workforce/workforce-core-state.json}") String configured) {
        return new FileWorkforceCoreStateStore(Path.of(configured));
    }

    @Bean
    ReleaseEvidenceStateStore releaseEvidenceStateStore(
            @Value("${METATRON_RELEASE_EVIDENCE_STATE_PATH:/var/lib/metatron-workforce/release-evidence-state.json}") String configured) {
        return new FileReleaseEvidenceStateStore(Path.of(configured));
    }

    @Bean
    ReleaseEvidenceStore releaseEvidenceStore(ReleaseEvidenceStateStore stateStore) {
        return new ReleaseEvidenceStore(stateStore);
    }

    @Bean
    ReleaseEvidenceCompletionGate releaseEvidenceCompletionGate(ReleaseEvidenceStore releaseEvidenceStore) {
        return new ReleaseEvidenceCompletionGate(releaseEvidenceStore);
    }

    @Bean
    ReleaseControlService releaseControlService(ReleaseEvidenceStore releaseEvidenceStore) {
        return new ReleaseControlService(releaseEvidenceStore);
    }

    @Bean
    WorkforceCoreService workforceCoreService(WorkforceCoreStateStore store, CompletionEvidenceGate completionGate) {
        return new WorkforceCoreService(store, completionGate);
    }

    /**
     * Real Objective-level gate: an assignment belonging to this objective satisfies the policy if its
     * own release evidence does. Deliberately checks "any assignment", not "every assignment" -- only the
     * deterministic release-owner step (see NormalizedRequest's ceiling) is expected to carry release
     * evidence for a multi-step plan; ordinary steps complete on their own execution evidence.
     */
    @Bean
    com.metatron.workforce.management.ObjectiveCompletionGate objectiveCompletionGate(
            ReleaseEvidenceStore releaseEvidenceStore, WorkforceCoreService workforceCoreService) {
        return (objectiveId, policy, work) -> {
            if (policy == CompletionPolicy.EXECUTION_REQUIRED) return true;
            return workforceCoreService.allAssignments().stream()
                    .filter(assignment -> objectiveId.equals(assignment.objectiveRef()))
                    .map(assignment -> releaseEvidenceStore.get(assignment.assignmentId()))
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(evidence -> policy == CompletionPolicy.PR_REQUIRED
                            ? evidence.satisfiesPrRequired() : evidence.satisfiesProductionRequired());
        };
    }

}
