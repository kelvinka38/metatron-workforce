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

    /**
     * Durable release-evidence persistence, same directory convention as workforceCoreStateStore.
     * File-backed by default so a process restart reproduces exactly the same evidence chain (see
     * FileReleaseEvidenceStateStore, which mirrors FileWorkforceCoreStateStore's atomic-write pattern).
     */
    @Bean
    ReleaseEvidenceStateStore releaseEvidenceStateStore(
            @Value("${METATRON_RELEASE_EVIDENCE_STATE_PATH:/var/lib/metatron-workforce/release-evidence-state.json}") String configured) {
        return new FileReleaseEvidenceStateStore(Path.of(configured));
    }

    /** One durable ReleaseEvidenceStore instance, shared by ReleaseControlService and the completion gate. */
    @Bean
    ReleaseEvidenceStore releaseEvidenceStore(ReleaseEvidenceStateStore stateStore) {
        return new ReleaseEvidenceStore(stateStore);
    }

    @Bean
    ReleaseEvidenceCompletionGate releaseEvidenceCompletionGate(ReleaseEvidenceStore releaseEvidenceStore) {
        return new ReleaseEvidenceCompletionGate(releaseEvidenceStore);
    }

    /**
     * Management-only. Merge/deploy executors are intentionally left at their fail-closed UNAVAILABLE
     * defaults here -- wiring a real privileged executor is future work gated on the broker-side
     * authorization boundary being closed first (see the PR description / audit).
     */
    @Bean
    ReleaseControlService releaseControlService(ReleaseEvidenceStore releaseEvidenceStore) {
        return new ReleaseControlService(releaseEvidenceStore);
    }

    /**
     * Production composition now wires the real completion gate -- transitionAssignment(..., COMPLETED)
     * is evidence-gated for any Assignment whose CompletionPolicy is PR_REQUIRED or PRODUCTION_REQUIRED,
     * everywhere in the running system, not just in isolated tests.
     */
    @Bean
    WorkforceCoreService workforceCoreService(WorkforceCoreStateStore store, CompletionEvidenceGate completionGate) {
        return new WorkforceCoreService(store, completionGate);
    }

}
