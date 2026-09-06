package com.metatron.workforce.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubWorkspaceProposalPublisherTest {
    private static final String HEAD = "0123456789abcdef0123456789abcdef01234567";

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
