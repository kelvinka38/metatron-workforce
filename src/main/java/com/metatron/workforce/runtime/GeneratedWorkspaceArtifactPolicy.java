package com.metatron.workforce.runtime;

import java.util.Set;

/**
 * One deterministic definition of conventional dependency/build output that may exist in an
 * Objective workspace but is not new source work product.
 *
 * <p>This policy is deliberately applied only to untracked paths at the governed broad-staging
 * boundary. Git-tracked paths remain authoritative source regardless of their directory name.</p>
 */
public final class GeneratedWorkspaceArtifactPolicy {
    public static final String EXCLUDE_MARKER = "# METATRON-GENERATED-WORKSPACE-ARTIFACTS-V1";

    private static final Set<String> GENERATED_DIRECTORIES = Set.of(
            "node_modules", "dist", "build", "target", ".gradle",
            "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache",
            ".tox", ".nox", ".venv", "venv", "coverage");

    private GeneratedWorkspaceArtifactPolicy() {}

    public static boolean isGeneratedUntrackedPath(String path) {
        if (path == null || path.isBlank()) return false;
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        for (String segment : normalized.split("/")) {
            if (GENERATED_DIRECTORIES.contains(segment)) return true;
        }
        String filename = normalized.substring(normalized.lastIndexOf('/') + 1);
        return filename.equals(".coverage")
                || filename.endsWith(".pyc")
                || filename.endsWith(".pyo")
                || filename.endsWith(".class");
    }

    /** Patterns used both by generated projects and the local Git staging boundary. */
    public static String ignorePatterns() {
        return """
                **/node_modules/
                **/dist/
                **/build/
                **/target/
                **/.gradle/
                **/__pycache__/
                **/.pytest_cache/
                **/.mypy_cache/
                **/.ruff_cache/
                **/.tox/
                **/.nox/
                **/.venv/
                **/venv/
                **/coverage/
                **/.coverage
                **/*.py[cod]
                **/*.class
                """;
    }

    public static String scaffoldGitignore() {
        return "# Dependency, build, test and interpreter outputs\n" + ignorePatterns();
    }

    public static String localGitExcludeBlock() {
        return EXCLUDE_MARKER + "\n" + ignorePatterns();
    }
}
