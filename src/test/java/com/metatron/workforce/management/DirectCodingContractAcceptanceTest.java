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
    void directRepositoryCodingUsesSharedObjectiveWorkspaceSurfaceNotHostWorkspaceTools() throws Exception {
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

        List<String> coding = new ArrayList<>();
        for (JsonNode stage : contract.path("stages")) {
            if ("release".equals(stage.path("id").asText())) continue;
            for (JsonNode tool : stage.path("directTools")) coding.add(tool.asText());
        }
        assertFalse(coding.isEmpty());
        assertTrue(coding.stream().allMatch(name -> name.startsWith("repository_")));
        assertFalse(coding.stream().anyMatch(name -> name.startsWith("workspace_")));
        assertFalse(coding.contains("workforce_gradle"));
        assertTrue(coding.contains("repository_open"));
        assertTrue(coding.contains("repository_pr_publish"));
    }
}
