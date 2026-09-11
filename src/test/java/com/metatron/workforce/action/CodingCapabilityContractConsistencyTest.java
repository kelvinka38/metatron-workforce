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
    private static final String CONTRACT_SHA256 = "f34e82996eeeaad4f39f75e81597c1173b501a585c6dc7ae83c6b735c357449f";

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
        assertTrue(root.path("invariants").path("directRequiresWorkforceProcess").asBoolean(false));
        assertFalse(root.path("invariants").path("rawRootShellCanonical").asBoolean(true));
        assertTrue(root.path("invariants").path("sourcePublicationGoverned").asBoolean(false));
        assertTrue(root.path("invariants").path("repositoryCodingUsesObjectiveWorkspace").asBoolean(false));
        assertTrue(root.path("invariants").path("hostWorkspaceToolsAreOperationalCompatibilityOnly").asBoolean(false));
        assertTrue(root.path("invariants").path("repositoryCredentialsRemainInWorkforce").asBoolean(false));

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
                "repository_open", "repository_list", "repository_search", "repository_read",
                "repository_git_status", "repository_git_diff",
                "repository_patch", "repository_write", "repository_process", "repository_shell",
                "repository_dependencies_install", "repository_build", "repository_test",
                "repository_git_run", "repository_pr_publish",
                "workforce_deploy_local_sha", "workforce_verify_production", "production_identity"), directTools,
                "Direct MCP mapping changed without a coding contract version/hash change");
    }
}
