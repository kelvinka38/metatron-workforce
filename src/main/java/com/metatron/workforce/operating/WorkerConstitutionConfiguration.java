package com.metatron.workforce.operating;

import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.interaction.intelligence.PositionWorkRoute;
import com.metatron.workforce.phase5.WorkScheduleService;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

@Configuration
public class WorkerConstitutionConfiguration {
    @Bean
    WorkerConstitutionStateStore workerConstitutionStateStore(
            @Value("${METATRON_WORKER_CONSTITUTION_STATE_PATH:/var/lib/metatron-workforce/worker-constitution-state.json}") String configured) {
        return new FileWorkerConstitutionStateStore(Path.of(configured));
    }

    @Bean
    WorkerConstitutionService workerConstitutionService(WorkerConstitutionStateStore store,
                                                        List<PositionWorkRoute> positionWorkRoutes) {
        return new WorkerConstitutionService(store,
                PositionRouteCatalog.of(positionWorkRoutes.stream().map(PositionWorkRoute::capability).toList()));
    }

    @Bean
    PositionAddressResolver positionAddressResolver(WorkforceCoreService core, WorkerConstitutionService constitution) {
        return new PositionAddressResolver(core, constitution);
    }

    @Bean
    WorkerConstitutionRuntimeStateStore workerConstitutionRuntimeStateStore(
            @Value("${METATRON_WORKER_CONSTITUTION_RUNTIME_STATE_PATH:/var/lib/metatron-workforce/worker-constitution-runtime-state.json}") String configured) {
        return new FileWorkerConstitutionRuntimeStateStore(Path.of(configured));
    }

    @Bean
    WorkerConstitutionRuntimeMaterializer workerConstitutionRuntimeMaterializer(
            WorkforceCoreService core,
            WorkerConstitutionService constitution,
            WorkScheduleService schedules,
            WorkerRuntimeProfileBindingService runtimeProfiles,
            RuntimeRegistry runtimes,
            ExecutionAttemptService executionAttempts,
            WorkerConstitutionRuntimeStateStore store) {
        return new WorkerConstitutionRuntimeMaterializer(
                core, constitution, schedules, runtimeProfiles, runtimes, executionAttempts, store);
    }
}
