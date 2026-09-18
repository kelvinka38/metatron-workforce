package com.metatron.workforce.interaction.intelligence;

import com.metatron.workforce.management.GeneralWorkspaceAutonomousCapability;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic Work-graph decomposition for lifecycle-heavy general workspace execution.
 *
 * <p>The Cognitive Worker should reason within a bounded phase, not invent a whole engineering
 * workflow from one giant action catalog. Phase markers are durable Work-contract facts consumed by
 * the execution capability and cognition completion guards.</p>
 */
public final class GeneralWorkspacePhasePlanner {
    public static final String PHASE_PRODUCE = "workspace-phase:produce";
    public static final String PHASE_PREPARE = "workspace-phase:prepare";
    public static final String PHASE_VERIFY = "workspace-phase:verify";
    public static final String PHASE_DELIVER = "workspace-phase:deliver";

    public static final String REQUIRE_MANIFEST = "workspace-requirement:project-manifest";
    public static final String REQUIRE_BUILD = "workspace-requirement:build";
    public static final String REQUIRE_TEST = "workspace-requirement:test";
    public static final String REQUIRE_RUNTIME = "workspace-requirement:runtime";
    public static final String REQUIRE_GIT_COMMIT = "workspace-requirement:git-commit";
    public static final String REQUIRE_GIT_VERIFY = "workspace-requirement:git-verify";
    public static final String REQUIRE_GITHUB_PR = "workspace-requirement:github-pr";

    private GeneralWorkspacePhasePlanner() {}

    static List<ExecutionWorkSpec> phase(ExecutionWorkSpec base) {
        if (base == null
                || !GeneralWorkspaceAutonomousCapability.CAPABILITY.equals(base.requiredCapability())
                || base.consequence() != ExecutionWorkSpec.Consequence.MUTATING) {
            return base == null ? List.of() : List.of(base);
        }

        String semantic = (base.objective() + " "
                + String.join(" ", base.acceptanceCriteria()) + " "
                + String.join(" ", base.evidenceRequirements())).toLowerCase(Locale.ROOT);

        boolean sourceWork = containsAny(semantic,
                "create", "implement", "fix", "repair", "patch", "write", "modify", "change",
                "build and deliver", "build a ", "build the ");
        boolean build = semantic.contains("build") || semantic.contains("compile");
        boolean test = semantic.contains("test");
        boolean runtime = semantic.contains("runtime verification")
                || semantic.contains("verify the runtime")
                || semantic.contains("runtime observation")
                || semantic.contains("runnable");
        boolean commit = semantic.contains("git evidence")
                || semantic.contains("local git commit")
                || semantic.contains("local commit")
                || semantic.contains(" commit")
                || semantic.contains("git status")
                || semantic.contains("git show");
        boolean gitVerify = semantic.contains("git evidence")
                || semantic.contains("git status")
                || semantic.contains("git show")
                || semantic.contains("verify git")
                || semantic.contains("verify the commit")
                || semantic.contains("verify commit");
        boolean publish = semantic.contains("pull request")
                || semantic.contains("open pr")
                || semantic.contains("proposal branch")
                || semantic.contains("publish") && semantic.contains("github");

        boolean lifecycleHeavy = sourceWork && (build || test || runtime || commit || publish);
        if (!lifecycleHeavy) return List.of(base);

        boolean fresh = base.evidenceRequirements().stream()
                .anyMatch(value -> "workspace-source:fresh-new-application".equalsIgnoreCase(value));

        List<String> prepareRequirements = fresh && (build || test || runtime)
                ? List.of(REQUIRE_MANIFEST) : List.of();
        List<String> verifyRequirements = new ArrayList<>();
        if (build) verifyRequirements.add(REQUIRE_BUILD);
        if (test) verifyRequirements.add(REQUIRE_TEST);
        if (runtime) verifyRequirements.add(REQUIRE_RUNTIME);
        List<String> deliverRequirements = new ArrayList<>();
        if (commit || publish) deliverRequirements.add(REQUIRE_GIT_COMMIT);
        if (gitVerify || publish) deliverRequirements.add(REQUIRE_GIT_VERIFY);
        if (publish) deliverRequirements.add(REQUIRE_GITHUB_PR);

        List<ExecutionWorkSpec> phased = new ArrayList<>();
        String produceId = base.stepId() + "-produce";
        phased.add(new ExecutionWorkSpec(
                produceId,
                "PRODUCE PHASE. Create the requested source/work product for the Objective. "
                        + "Do not run build/test/runtime verification or Git delivery in this phase. "
                        + "Original Objective: " + base.objective(),
                base.target(),
                base.requiredCapability(),
                base.dependsOn(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("requested source/work product exists in the Objective workspace"),
                evidence(base.evidenceRequirements(), PHASE_PRODUCE, List.of())));

        String predecessor = produceId;
        if (fresh && (build || test || runtime)) {
            String prepareId = base.stepId() + "-prepare";
            phased.add(new ExecutionWorkSpec(
                    prepareId,
                    "PREPARE PHASE. Inspect the carried source/work product and create the minimal project/dependency "
                            + "manifest and support configuration required for governed build, tests, and runtime verification. "
                            + "Do not replace the requested work product and do not perform Git delivery. "
                            + "Original Objective: " + base.objective(),
                    base.target(),
                    base.requiredCapability(),
                    List.of(produceId),
                    ExecutionWorkSpec.Consequence.MUTATING,
                    List.of("a project/dependency manifest exists for the carried work product"),
                    evidence(base.evidenceRequirements(), PHASE_PREPARE, prepareRequirements)));
            predecessor = prepareId;
        }
        if (build || test || runtime) {
            String verifyId = base.stepId() + "-verify";
            List<String> acceptance = new ArrayList<>();
            if (build) acceptance.add("governed build succeeds against the produced workspace");
            if (test) acceptance.add("governed tests succeed against the produced workspace");
            if (runtime) acceptance.add("governed runtime verification succeeds against the produced workspace");
            phased.add(new ExecutionWorkSpec(
                    verifyId,
                    "VERIFY PHASE. Verify the carried workspace from the completed production phase. "
                            + "Do not change source or perform Git delivery. Original Objective: " + base.objective(),
                    base.target(),
                    base.requiredCapability(),
                    List.of(predecessor),
                    runtime ? ExecutionWorkSpec.Consequence.MUTATING : ExecutionWorkSpec.Consequence.READ_ONLY,
                    acceptance,
                    evidence(base.evidenceRequirements(), PHASE_VERIFY, verifyRequirements)));
            predecessor = verifyId;
        }

        if (commit || publish) {
            List<String> acceptance = new ArrayList<>();
            acceptance.add("produced workspace changes are committed in local Git");
            if (gitVerify || publish) acceptance.add("local Git HEAD/status is verified after commit");
            if (publish) acceptance.add("reviewable unmerged GitHub pull request exists for the committed work product");
            phased.add(new ExecutionWorkSpec(
                    base.stepId() + "-deliver",
                    "DELIVER PHASE. Deliver the already produced and verified workspace through governed Git. "
                            + "Initialize local Git when needed, stage the carried work product, create a local commit, "
                            + "verify immutable Git state, and publish a reviewable unmerged pull request only when required. "
                            + "Do not modify source in this phase. Original Objective: " + base.objective(),
                    base.target(),
                    base.requiredCapability(),
                    List.of(predecessor),
                    ExecutionWorkSpec.Consequence.MUTATING,
                    acceptance,
                    evidence(base.evidenceRequirements(), PHASE_DELIVER, deliverRequirements)));
        }

        return List.copyOf(phased);
    }

    private static List<String> evidence(List<String> inherited, String phase, List<String> requirements) {
        List<String> values = new ArrayList<>();
        if (inherited != null) {
            for (String value : inherited) {
                if (value == null || value.isBlank()) continue;
                if (value.startsWith("workspace-phase:")) continue;
                if (value.startsWith("workspace-requirement:")) continue;
                if (!PHASE_PRODUCE.equals(phase)
                        && "workspace-source:fresh-new-application".equalsIgnoreCase(value)) continue;
                values.add(value);
            }
        }
        if (!values.contains(phase)) values.add(phase);
        for (String requirement : requirements) if (!values.contains(requirement)) values.add(requirement);
        return List.copyOf(values);
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }
}
