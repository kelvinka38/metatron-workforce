package com.metatron.workforce.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubWorkspaceProposalPublisherTest {
    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void acceptsMaterializedSourceWhenItIsStillAnAncestorOfCurrentMain() throws Exception {
        String source = "1111111111111111111111111111111111111111";
        String current = "2222222222222222222222222222222222222222";
        com.fasterxml.jackson.databind.JsonNode comparison = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {
                  "status": "ahead",
                  "ahead_by": 8,
                  "behind_by": 0,
                  "merge_base_commit": {"sha": "1111111111111111111111111111111111111111"}
                }
                """);

        assertTrue(GitHubWorkspaceProposalPublisher.sourceLineageAcceptable(comparison, source, current));
    }

    @Test
    void rejectsDivergedOrUnrelatedMaterializedSource() throws Exception {
        String source = "1111111111111111111111111111111111111111";
        String current = "2222222222222222222222222222222222222222";
        com.fasterxml.jackson.databind.JsonNode comparison = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {
                  "status": "diverged",
                  "ahead_by": 2,
                  "behind_by": 3,
                  "merge_base_commit": {"sha": "3333333333333333333333333333333333333333"}
                }
                """);

        assertFalse(GitHubWorkspaceProposalPublisher.sourceLineageAcceptable(comparison, source, current));
    }

    @Test
    void parsesOnlyBoundedSourcePathChanges() {
        List<GitHubWorkspaceProposalPublisher.Change> changes =
                GitHubWorkspaceProposalPublisher.parseChanges(
                        "M\tsrc/main/java/com/metatron/Fix.java\nA\tsrc/test/java/com/metatron/FixTest.java\n");

        assertEquals(2, changes.size());
        assertEquals("M", changes.get(0).status());
        assertEquals("src/main/java/com/metatron/Fix.java", changes.get(0).path());
        assertEquals("A", changes.get(1).status());
    }

    @Test
    void rejectsRepositoryMetadataAndUnsupportedRenameRecords() {
        assertThrows(SecurityException.class, () ->
                GitHubWorkspaceProposalPublisher.parseChanges("M\t.metatron-repository\n"));
        assertThrows(IllegalArgumentException.class, () ->
                GitHubWorkspaceProposalPublisher.parseChanges("R\told.java\tnew.java\n"));
    }

    @Test
    void objectiveBranchIsDeterministicAndNeverTargetsCanonicalBranch() {
        String first = GitHubWorkspaceProposalPublisher.branchName("objective:general:42", HEAD);
        String second = GitHubWorkspaceProposalPublisher.branchName("objective:general:42", HEAD);

        assertEquals(first, second);
        assertTrue(first.startsWith("metatron/objective-"));
        assertTrue(first.endsWith("-01234567"));
    }
}
