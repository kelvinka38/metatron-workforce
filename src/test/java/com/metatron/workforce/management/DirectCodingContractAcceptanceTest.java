package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DirectCodingContractAcceptanceTest {
    @Test
    void directRepositoryCodingUsesSharedObjectiveWorkspaceSurfaceNotHostWorkspaceCompatibilityTools() throws Exception {
        JsonNode contract;
        try (var input = new ClassPathResource("contracts/coding-capability-v1.json").getInputStream()) {
            contract = new ObjectMapper().readTree(input);
        }
        assertEquals("metatron.coding.v1", contract.path("version").asText());
        assertEquals("execution", contract.path("owner").asText());
        assertEquals("highway", contract.path("releaseOwner").asText());
        assertTrue(contract.path("invariants").path("repositoryCodingUsesObjectiveWorkspace").asBoolean());
        assertTrue(contract.path("invariants").path("hostWorkspaceToolsAreOperationalCompatibilityOnly").asBoolean());
        assertTrue(contract.path("invariants").path("repositoryCredentialsRemainInWorkforce").asBoolean());

        List<String> repositoryTools = new ArrayList<>();
        for (JsonNode stage : contract.path("stages")) {
            if ("release".equals(stage.path("id").asText())) continue;
            for (JsonNode tool : stage.path("directTools")) repositoryTools.add(tool.asText());
        }
        assertFalse(repositoryTools.isEmpty());
        assertTrue(repositoryTools.stream().allMatch(name -> name.startsWith("repository_")),
                "direct coding stages must use repository_* adapters backed by Workforce");
        assertFalse(repositoryTools.stream().anyMatch(name -> name.startsWith("workspace_")));
        assertFalse(repositoryTools.contains("workforce_gradle"));
        assertTrue(repositoryTools.contains("repository_open"));
        assertTrue(repositoryTools.contains("repository_pr_publish"));

        List<String> release = new ArrayList<>();
        for (JsonNode stage : contract.path("stages")) {
            if (!"release".equals(stage.path("id").asText())) continue;
            for (JsonNode tool : stage.path("directTools")) release.add(tool.asText());
        }
        assertTrue(release.contains("workforce_deploy_local_sha"));
        assertTrue(release.contains("workforce_verify_production"));
        assertFalse(release.stream().anyMatch(name -> name.startsWith("repository_")));
    }
}
