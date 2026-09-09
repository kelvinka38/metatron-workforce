package com.metatron.workforce.operating;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class WorkerConstitutionConfiguration {
    @Bean
    WorkerConstitutionStateStore workerConstitutionStateStore(
            @Value("${METATRON_WORKER_CONSTITUTION_STATE_PATH:/var/lib/metatron-workforce/worker-constitution-state.json}") String configured) {
        return new FileWorkerConstitutionStateStore(Path.of(configured));
    }

    @Bean
    WorkerConstitutionService workerConstitutionService(WorkerConstitutionStateStore store) {
        return new WorkerConstitutionService(store);
    }
}
