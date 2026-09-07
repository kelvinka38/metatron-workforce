package com.metatron.workforce.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.action.GeneralCognitiveWorkerBrainFactory;
import com.metatron.workforce.action.GeneralWorkspaceActionCatalog;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;

/** Production composition for general Worker runtime profiles, Objective workspaces, sandbox and cognition. */
@Configuration
public class GeneralExecutionRuntimeConfiguration {

    @Bean
    WorkerRuntimeProfileBindingService workerRuntimeProfileBindingService(
            @Value("${METATRON_RUNTIME_PROFILE_BINDINGS_PATH:/var/lib/metatron-workforce/runtime-profile-bindings.tsv}") String path) {
        return new WorkerRuntimeProfileBindingService(Path.of(path));
    }

    @Bean
    ObjectiveWorkspaceService objectiveWorkspaceService(
            @Value("${METATRON_OBJECTIVE_WORKSPACE_ROOT:/var/lib/metatron-workforce/objective-workspaces}") String root) {
        return new ObjectiveWorkspaceService(Path.of(root));
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
            @Value("${GITHUB_TOKEN:}") String githubToken,
            ObjectiveWorkspaceService workspaces,
            ObjectMapper json) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new RepositoryWorkspaceMaterializationService(http, githubToken, workspaces, json);
    }

    @Bean
    GitHubWorkspaceProposalPublisher gitHubWorkspaceProposalPublisher(
            @Value("${GITHUB_TOKEN:}") String githubToken,
            ObjectiveWorkspaceService workspaces,
            WorkerExecutionSandboxService sandbox,
            ObjectMapper json) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return new GitHubWorkspaceProposalPublisher(http, githubToken, workspaces, sandbox, json);
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
    GeneralCognitiveWorkerBrainFactory generalCognitiveWorkerBrainFactory(
            WorkerIntelligenceService intelligence,
            ObjectMapper json) {
        return new GeneralCognitiveWorkerBrainFactory(intelligence, json);
    }

}
