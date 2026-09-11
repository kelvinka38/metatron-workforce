package com.metatron.workforce.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks Worker and Direct MCP coding ingress to one versioned Execution-owned capability contract. */
class CodingCapabilityContractConsistencyTest {
    private static final String CONTRACT = "/contracts/coding-capability-v1.json";
    private static final String CONTRACT_SHA256 = "3c2a8fe63aebf1e2e9b3393dd4c14a95c5b7b0ea48962e002d6485acff4eff88";

    @Test
    void workerAndDirectIngressShareOneVersionedExecutionContract() throws Exception {
        byte[] bytes;
        try (InputStream in = getClass().getResourceAsStream(CONTRACT)) {
            assertNotNull(in, "canonical coding capability contract missing");
            bytes = in.readAllBytes();
        }
        assertEquals(CONTRACT_SHA256,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));

        JsonNode root = new ObjectMapper().readTree(bytes);
        assertEquals("metatron.coding.v1", root.path("version").asText());
        assertEquals("execution", root.path("owner").asText());
        assertEquals("highway", root.path("releaseOwner").asText());
        assertFalse(root.path("invariants").path("workerReleaseSelfGrant").asBoolean(true));
        assertFalse(root.path("invariants").path("directRequiresWorkforceProcess").asBoolean(true));
        assertFalse(root.path("invariants").path("rawRootShellCanonical").asBoolean(true));
        assertTrue(root.path("invariants").path("sourcePublicationGoverned").asBoolean(false));

        Set<String> workerActions = new LinkedHashSet<>();
        Set<String> directTools = new LinkedHashSet<>();
        for (JsonNode stage : root.path("stages")) {
            stage.path("workerActions").forEach(node -> workerActions.add(node.asText()));
            stage.path("directTools").forEach(node -> directTools.add(node.asText()));
        }

        WorkerRuntimeProfileBindingService.ToolProfile profile = WorkerRuntimeProfileBindingService.inMemory().profile(
                WorkerRuntimeProfileBindingService.GENERAL_ENGINEERING_PROFILE,
                "execution.general.workspace");
        assertTrue(profile.actionRefs().containsAll(workerActions),
                "general engineering Worker must implement every contract Worker action");
        assertFalse(profile.actionRefs().contains(InstitutionalActionAdapters.GITHUB_DEPLOY_DISPATCH),
                "release authority remains outside the coding Worker profile");

        assertEquals(Set.of(
                "workspace_list", "workspace_search", "workspace_read_file", "workspace_git_diff",
                "workspace_git_fetch", "workspace_git_compare", "workspace_git_show",
                "workspace_write_file", "workforce_gradle", "workspace_git_branch",
                "workspace_git_commit_local", "workforce_deploy_local_sha",
                "workforce_verify_production", "production_identity"), directTools,
                "Direct MCP mapping changed without a coding contract version change");
    }
}
