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

}
