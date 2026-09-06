package com.metatron.workforce.runtime;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Durable completion identity for a fully established repository materialization baseline. */
public final class RepositoryWorkspaceMaterializationState {
    public static final String BASELINE_REF = "refs/metatron/materialized";
    public static final String BASELINE_REF_PATH = ".git/refs/metatron/materialized";

    private RepositoryWorkspaceMaterializationState() {}

    public static String completedBaselineSha(ObjectiveWorkspaceService workspaces,
                                              ObjectiveWorkspaceService.ObjectiveWorkspace workspace) {
        Objects.requireNonNull(workspaces, "workspaces");
        Objects.requireNonNull(workspace, "workspace");
        Path ref = workspaces.resolve(workspace, BASELINE_REF_PATH);
        if (!Files.exists(ref, LinkOption.NOFOLLOW_LINKS)) return "";
        if (!Files.isRegularFile(ref, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(ref)) {
            throw new IllegalStateException("materialization baseline ref is not a regular file");
        }
        String value = workspaces.read(workspace, BASELINE_REF_PATH).trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{40}")) {
            throw new IllegalStateException("materialization baseline ref is not an immutable Git SHA");
        }
        return value;
    }
}
