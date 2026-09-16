package com.metatron.workforce.management;

import com.metatron.workforce.core.CompletionPolicy;
import com.metatron.workforce.interaction.intelligence.NormalizedRequest;

import java.util.Locale;

/**
 * Deterministic, conservative resolver deriving the authoritative Objective completion requirement from
 * already-normalized structured fields (requestedOutput, explicitProhibitions, constraints) on the
 * accepted NormalizedRequest -- never from arbitrary free-form text, never from Worker or planner output,
 * never from post-execution metadata. Called exactly once, at Objective acceptance
 * (HumanObjectiveIngressService), before any planning or execution occurs.
 *
 * Conservative by design: absent explicit signal, defaults to the safe existing contract
 * (EXECUTION_REQUIRED). An explicit do-not-merge/do-not-deploy signal is never silently escalated to
 * PRODUCTION_REQUIRED regardless of whether the underlying work is MUTATING. Genuinely ambiguous or
 * unrecognized phrasing resolves to the safe default rather than guessing.
 */
final class ObjectiveCompletionPolicyResolver {
    private ObjectiveCompletionPolicyResolver() {}

    static CompletionPolicy resolve(NormalizedRequest request) {
        String haystack = normalize(request.requestedOutput()) + " "
                + normalize(String.join(" ", request.explicitProhibitions())) + " "
                + normalize(String.join(" ", request.constraints()));

        boolean explicitNoMergeOrDeploy = containsAny(haystack,
                "do not merge", "don't merge", "no merge",
                "do not deploy", "don't deploy", "no deploy");
        boolean explicitProductionSignal = containsAny(haystack,
                "deploy to production", "verify production",
                "production outcome required", "production deployment required", "deploy production");
        boolean explicitPrSignal = containsAny(haystack,
                "open a pull request", "open pr", "pull request only",
                "pr only", "publish a pr", "open/publish a pr", "open and publish a pr");

        if (explicitProductionSignal && !explicitNoMergeOrDeploy) {
            return CompletionPolicy.PRODUCTION_REQUIRED;
        }
        if (explicitPrSignal || explicitNoMergeOrDeploy) {
            return CompletionPolicy.PR_REQUIRED;
        }
        return CompletionPolicy.EXECUTION_REQUIRED;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) if (haystack.contains(needle)) return true;
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
