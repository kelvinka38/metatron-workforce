package com.metatron.workforce.actor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.nio.file.Path;
import java.time.Duration;

/** Production composition for the elastic Worker actor runtime. */
@Configuration
public class WorkerActorRuntimeConfiguration {
    @Bean
    WorkerActorStateStore workerActorStateStore(
            ObjectMapper objectMapper,
            @Value("${METATRON_WORKER_ACTOR_STATE_PATH:/var/lib/metatron-workforce/worker-actors}") String configured) {
        return new FileWorkerActorStateStore(Path.of(configured), objectMapper);
    }

    @Bean(destroyMethod = "close")
    WorkerActorRuntime workerActorRuntime(
            WorkerActorStateStore store,
            @Value("${METATRON_WORKER_ACTOR_MAX_CONCURRENCY:32}") int maxConcurrentTurns) {
        if (maxConcurrentTurns < 1) throw new IllegalStateException("METATRON_WORKER_ACTOR_MAX_CONCURRENCY must be positive");
        return new WorkerActorRuntime(store, maxConcurrentTurns);
    }

    /**
     * Production-primary runtime capacity coordinator. Existing one-argument construction remains for
     * tests/compatibility; production capacity realization also materializes the canonical Worker actor.
     */
    @Bean
    @Primary
    RuntimeCapacityCoordinator actorAwareRuntimeCapacityCoordinator(
            RuntimeRegistry registry,
            WorkerActorRuntime actors) {
        return new RuntimeCapacityCoordinator(registry, actors);
    }

    @Bean(destroyMethod = "close")
    WorkerActorAssignmentConsumer workerActorAssignmentConsumer(WorkerActorRuntime actors) {
        return new WorkerActorAssignmentConsumer(actors);
    }

    @Bean(destroyMethod = "close")
    WorkerActorAssignmentReconciler workerActorAssignmentReconciler(
            WorkforceCoreService core,
            WorkerActorRuntime actors,
            @Value("${METATRON_WORKER_ACTOR_RECONCILE_MILLIS:1000}") long intervalMillis) {
        if (intervalMillis < 100) throw new IllegalStateException("METATRON_WORKER_ACTOR_RECONCILE_MILLIS must be >= 100");
        return new WorkerActorAssignmentReconciler(core, actors, Duration.ofMillis(intervalMillis));
    }

    @Bean(destroyMethod = "close")
    WorkerActorAssignmentSupervisor workerActorAssignmentSupervisor(
            WorkforceCoreService core,
            com.metatron.workforce.management.ManagementAutonomyService management,
            com.metatron.workforce.management.AutonomyCoordinationService coordination,
            WorkerActorRuntime actors,
            java.util.List<com.metatron.workforce.management.AutonomousExecutionCapability> capabilities) {
        return new WorkerActorAssignmentSupervisor(core, management, coordination, actors, capabilities);
    }

    @Bean
    ApplicationRunner workerActorRuntimeBootstrap(
            WorkerActorAssignmentReconciler reconciler,
            WorkerActorAssignmentSupervisor supervisor) {
        return args -> {
            reconciler.start();
            supervisor.start();
        };
    }
}
