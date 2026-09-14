package com.metatron.workforce.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.action.GeneralCognitiveWorkerBrainFactory;
import com.metatron.workforce.action.GeneralWorkspaceActionCatalog;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import com.metatron.workforce.runtime.execution.ExecutionIsolationReconciler;
import com.metatron.workforce.runtime.execution.ExecutionQueueStore;
import com.metatron.workforce.runtime.execution.ExecutionResourceManager;
import com.metatron.workforce.runtime.execution.ExecutionResourceScheduler;
import com.metatron.workforce.runtime.execution.ExecutionWorkspaceBindingStore;
import com.metatron.workforce.runtime.execution.ExecutionWorkspaceManager;
import com.metatron.workforce.runtime.execution.ExecutionWorkspaceReconciler;
import com.metatron.workforce.runtime.execution.FileExecutionQueueStore;
import com.metatron.workforce.runtime.execution.FileExecutionWorkspaceBindingStore;
import com.metatron.workforce.runtime.execution.FileIntegrationQueueStore;
import com.metatron.workforce.runtime.execution.FileResourceStateStore;
import com.metatron.workforce.runtime.execution.IntegrationQueueReconciler;
import com.metatron.workforce.runtime.execution.IntegrationQueueStore;
import com.metatron.workforce.runtime.execution.RepositoryIntegrationController;
import com.metatron.workforce.runtime.execution.ResourceLeaseReconciler;
import com.metatron.workforce.runtime.execution.ResourceStateStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Production composition for general Worker runtime profiles, execution workspaces, resources, sandbox and cognition. */
@Configuration
public class GeneralExecutionRuntimeConfiguration {

    @Bean
    WorkerRuntimeProfileBindingService workerRuntimeProfileBindingService(
            @Value("${METATRON_RUNTIME_PROFILE_BINDINGS_PATH:/var/lib/metatron-workforce/runtime-profile-bindings.tsv}") String path) {
        return new WorkerRuntimeProfileBindingService(Path.of(path));
    }

    @Bean
    ExecutionWorkspaceBindingStore executionWorkspaceBindingStore(
            @Value("${METATRON_EXECUTION_WORKSPACE_BINDINGS_PATH:/var/lib/metatron-workforce/execution-workspace-bindings.json}") String path) {
        return new FileExecutionWorkspaceBindingStore(Path.of(path));
    }

    @Bean
    ExecutionWorkspaceManager executionWorkspaceManager(
            @Value("${METATRON_EXECUTION_WORKSPACE_ROOT:/var/lib/metatron/executions}") String root,
            ExecutionAttemptService attempts,
            ExecutionWorkspaceBindingStore store) {
        return new ExecutionWorkspaceManager(Path.of(root), attempts, store);
    }

    @Bean
    ObjectiveWorkspaceService objectiveWorkspaceService(
            @Value("${METATRON_OBJECTIVE_WORKSPACE_ROOT:/var/lib/metatron-workforce/objective-workspaces}") String legacyRoot,
            ExecutionWorkspaceManager executionWorkspaces) {
        return new ObjectiveWorkspaceService(Path.of(legacyRoot), executionWorkspaces);
    }

    @Bean
    ResourceStateStore executionResourceStateStore(
            @Value("${METATRON_EXECUTION_RESOURCE_STATE_PATH:/var/lib/metatron-workforce/execution-resource-state.json}") String path) {
        return new FileResourceStateStore(Path.of(path));
    }

    @Bean
    ExecutionResourceManager executionResourceManager(
            ExecutionAttemptService attempts,
            ResourceStateStore store,
            @Value("${METATRON_EXECUTION_SLOTS:4}") double executionSlots,
            @Value("${METATRON_JVM_BUILD_SLOTS:2}") double jvmBuildSlots,
            @Value("${METATRON_GITHUB_MUTATION_SLOTS:2}") double githubMutationSlots,
            @Value("${METATRON_DOCKER_BUILD_SLOTS:1}") double dockerBuildSlots) {
        Map<String, Double> capacities = new LinkedHashMap<>();
        capacities.put("compute:execution", positive(executionSlots, "METATRON_EXECUTION_SLOTS"));
        capacities.put("build:jvm", positive(jvmBuildSlots, "METATRON_JVM_BUILD_SLOTS"));
        capacities.put("github:api:mutation", positive(githubMutationSlots, "METATRON_GITHUB_MUTATION_SLOTS"));
        capacities.put("build:docker", positive(dockerBuildSlots, "METATRON_DOCKER_BUILD_SLOTS"));
        return new ExecutionResourceManager(attempts, store, capacities);
    }

    @Bean
    ExecutionQueueStore executionQueueStore(
            @Value("${METATRON_EXECUTION_RESOURCE_QUEUE_PATH:/var/lib/metatron-workforce/execution-resource-queue.json}") String path) {
        return new FileExecutionQueueStore(Path.of(path));
    }

    @Bean
    ExecutionResourceScheduler executionResourceScheduler(
            ExecutionAttemptService attempts,
            ExecutionResourceManager resources,
            ExecutionWorkspaceManager workspaces,
            ExecutionQueueStore store,
            @Value("${METATRON_MAX_ACTIVE_EXECUTIONS_PER_OBJECTIVE:4}") int maxPerObjective,
            @Value("${METATRON_RESOURCE_LEASE_SECONDS:60}") long leaseSeconds) {
        if (leaseSeconds < 5) throw new IllegalArgumentException("METATRON_RESOURCE_LEASE_SECONDS must be >= 5");
        return new ExecutionResourceScheduler(attempts, resources, workspaces, store,
                maxPerObjective, Duration.ofSeconds(leaseSeconds));
    }

    @Bean
    IntegrationQueueStore integrationQueueStore(
            @Value("${METATRON_INTEGRATION_QUEUE_PATH:/var/lib/metatron-workforce/integration-queue.json}") String path) {
        return new FileIntegrationQueueStore(Path.of(path));
    }

    @Bean
    RepositoryIntegrationController repositoryIntegrationController(
            ExecutionAttemptService attempts,
            ExecutionWorkspaceManager workspaces,
            ExecutionResourceManager resources,
            IntegrationQueueStore store,
            @Value("${METATRON_INTEGRATION_LEASE_SECONDS:90}") long leaseSeconds) {
        if (leaseSeconds < 10) throw new IllegalArgumentException("METATRON_INTEGRATION_LEASE_SECONDS must be >= 10");
        return new RepositoryIntegrationController(attempts, workspaces, resources, store, Duration.ofSeconds(leaseSeconds));
    }

    @Bean
    ResourceLeaseReconciler resourceLeaseReconciler(ExecutionResourceManager resources) {
        return new ResourceLeaseReconciler(resources);
    }

    @Bean
    IntegrationQueueReconciler integrationQueueReconciler(RepositoryIntegrationController integration,
                                                            ExecutionAttemptService attempts,
                                                            ExecutionResourceManager resources) {
        return new IntegrationQueueReconciler(integration, attempts, resources);
    }

    @Bean
    ExecutionWorkspaceReconciler executionWorkspaceReconciler(
            ExecutionAttemptService attempts,
            ExecutionWorkspaceManager workspaces,
            ExecutionWorkspaceBindingStore bindings,
            ExecutionResourceManager resources,
            RepositoryIntegrationController integration,
            @Value("${METATRON_EXECUTION_WORKSPACE_RETENTION_SECONDS:86400}") long retentionSeconds) {
        if (retentionSeconds < 60) throw new IllegalArgumentException("METATRON_EXECUTION_WORKSPACE_RETENTION_SECONDS must be >= 60");
        return new ExecutionWorkspaceReconciler(attempts, workspaces, bindings, resources, integration,
                Duration.ofSeconds(retentionSeconds));
    }

    @Bean(destroyMethod = "close")
    ExecutionIsolationReconciler executionIsolationReconciler(
            ResourceLeaseReconciler leases,
            IntegrationQueueReconciler integration,
            ExecutionWorkspaceReconciler workspaces,
            ExecutionAttemptService attempts,
            @Value("${METATRON_EXECUTION_RECONCILE_SECONDS:15}") long intervalSeconds) {
        if (intervalSeconds < 1) throw new IllegalArgumentException("METATRON_EXECUTION_RECONCILE_SECONDS must be positive");
        ExecutionIsolationReconciler reconciler = new ExecutionIsolationReconciler(
                leases, integration, workspaces, attempts, Clock.systemUTC(), Duration.ofSeconds(intervalSeconds));
        reconciler.start();
        return reconciler;
    }

    @Bean
    WorkerExecutionSandboxService workerExecutionSandboxService(
            @Value("${METATRON_SANDBOX_URL:http://workforce-sandbox:8090}") String endpoint,
            @Value("${METATRON_SANDBOX_TOKEN:}") String token,
            WorkerRuntimeProfileBindingService profiles,
            ObjectiveWorkspaceService workspaces,
            ObjectMapper json) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        return new WorkerExecutionSandboxService(http, URI.create(endpoint), token, profiles, workspaces, json);
    }

    @Bean
    RepositoryWorkspaceMaterializationService repositoryWorkspaceMaterializationService(
            RepositoryCredentialAuthority repositoryCredentials,
            ObjectiveWorkspaceService workspaces,
            ExecutionWorkspaceManager executionWorkspaces,
            ObjectMapper json) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return new RepositoryWorkspaceMaterializationService(http, repositoryCredentials.tokenOrEmpty(), workspaces, executionWorkspaces, json);
    }

    @Bean
    GitHubWorkspaceProposalPublisher gitHubWorkspaceProposalPublisher(
            RepositoryCredentialAuthority repositoryCredentials,
            ObjectiveWorkspaceService workspaces,
            WorkerExecutionSandboxService sandbox,
            ObjectMapper json) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return new GitHubWorkspaceProposalPublisher(http, repositoryCredentials.tokenOrEmpty(), workspaces, sandbox, json);
    }

    @Bean
    GeneralWorkspaceActionCatalog generalWorkspaceActionCatalog(
            ObjectiveWorkspaceService workspaces,
            WorkerExecutionSandboxService sandbox,
            WorkerRuntimeProfileBindingService profiles,
            RepositoryWorkspaceMaterializationService repositories,
            GitHubWorkspaceProposalPublisher proposals,
            ObjectMapper json) {
        return new GeneralWorkspaceActionCatalog(workspaces, sandbox, profiles, repositories, proposals, json);
    }

    @Bean
    GeneralCognitiveWorkerBrainFactory generalCognitiveWorkerBrainFactory(WorkerIntelligenceService intelligence,
                                                                          ObjectMapper json) {
        return new GeneralCognitiveWorkerBrainFactory(intelligence, json);
    }

    private static double positive(double value, String field) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }
}
