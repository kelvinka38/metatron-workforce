package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderClient;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExecutionWorkPlannerTest {
    @Test
    void plannerReceivesCaseAndNormalizedRequestNotRawHumanUtterance() {
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }

            @Override public LlmResponse complete(LlmRequest request) {
                assertTrue(request.userInput().contains("case-123"));
                assertTrue(request.userInput().contains("objective=coordinate governed repository evidence work"));
                assertTrue(request.userInput().contains("repository.audit.read"));
                assertFalse(request.userInput().contains("Đụ má audit cái repo này giùm tao"));
                return planResponse(LlmProvider.GOOGLE);
            }
        };
        ObjectMapper mapper = new ObjectMapper();
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(google)), provider -> "planner-test",
                List.of(LlmProvider.GOOGLE), mapper);
        NormalizedRequest normalized = normalizedProviderExecution(null);

        List<ExecutionWorkSpec> plan = planner.plan("case-123", normalized, List.of("repository.audit.read"));

        assertEquals(1, plan.size());
        assertEquals("repository.audit.read", plan.getFirst().requiredCapability());
        assertEquals(ExecutionWorkSpec.Consequence.READ_ONLY, plan.getFirst().consequence());
        assertTrue(plan.getFirst().verifiable());
    }

    @Test
    void plannerRejectsExecutionPlanWithoutCriterionLevelVerificationRequirementsWhenNoSafeFallbackApplies() {
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                return new LlmResponse(LlmProvider.GOOGLE, "planner-test", """
                        {"execution_work_plan":[{
                          "step_id":"audit-repo",
                          "objective":"audit repository",
                          "target":"kelvinka38/bios",
                          "required_capability":"repository.audit.read",
                          "depends_on":[],
                          "consequence":"READ_ONLY"
                        }]}
                        """, "planner-ref");
            }
        };
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(google)), provider -> "planner-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> planner.plan("case-123", normalizedExecution(null), List.of("unrelated.read")));

        assertTrue(failure.getMessage().contains("all execution planning providers failed"));
        assertTrue(List.of(failure.getSuppressed()).stream()
                .anyMatch(suppressed -> suppressed.getMessage().contains("criterion-level verification requirements")));
    }

    @Test
    void plannerUsesLiveTelemetryToAvoidKnownDegradedProviderInAutoMode() {
        AtomicInteger googleCalls = new AtomicInteger();
        AtomicInteger anthropicCalls = new AtomicInteger();
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                googleCalls.incrementAndGet();
                throw new IllegalStateException("should not call degraded provider first");
            }
        };
        LlmProviderClient anthropic = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.ANTHROPIC; }
            @Override public LlmResponse complete(LlmRequest request) {
                anthropicCalls.incrementAndGet();
                return planResponse(LlmProvider.ANTHROPIC);
            }
        };
        LlmProviderRouter router = new LlmProviderRouter(List.of(google, anthropic));
        long started = router.telemetry().begin(LlmProvider.GOOGLE);
        router.telemetry().failure(LlmProvider.GOOGLE, started,
                new IllegalStateException("google_request_failed:429:quota exceeded"));
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                router, provider -> "planner-test", List.of(LlmProvider.GOOGLE, LlmProvider.ANTHROPIC),
                new ObjectMapper());

        List<ExecutionWorkSpec> plan = planner.plan("case-123", normalizedProviderExecution(null),
                List.of("repository.audit.read"));

        assertEquals(1, plan.size());
        assertEquals(0, googleCalls.get());
        assertEquals(1, anthropicCalls.get());
    }

    @Test
    void explicitPlannerProviderIsPreservedDespiteTelemetryPrediction() {
        AtomicInteger googleCalls = new AtomicInteger();
        AtomicInteger anthropicCalls = new AtomicInteger();
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                googleCalls.incrementAndGet();
                return planResponse(LlmProvider.GOOGLE);
            }
        };
        LlmProviderClient anthropic = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.ANTHROPIC; }
            @Override public LlmResponse complete(LlmRequest request) {
                anthropicCalls.incrementAndGet();
                return planResponse(LlmProvider.ANTHROPIC);
            }
        };
        LlmProviderRouter router = new LlmProviderRouter(List.of(google, anthropic));
        long started = router.telemetry().begin(LlmProvider.GOOGLE);
        router.telemetry().failure(LlmProvider.GOOGLE, started,
                new IllegalStateException("google_request_failed:429:quota exceeded"));
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                router, provider -> "planner-test", List.of(LlmProvider.GOOGLE, LlmProvider.ANTHROPIC),
                new ObjectMapper());

        List<ExecutionWorkSpec> plan = planner.plan("case-123", normalizedExecution(LlmProvider.GOOGLE),
                List.of("repository.audit.read"));

        assertEquals(1, plan.size());
        assertEquals(1, googleCalls.get());
        assertEquals(0, anthropicCalls.get());
    }

    @Test
    void boundedSingleRepositoryAuditCanPlanWithoutExternalProvider() {
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of()), provider -> "unused", List.of(), new ObjectMapper());

        List<ExecutionWorkSpec> plan = planner.plan(
                "case-123", normalizedExecution(null), List.of("repository.audit.read"));

        assertEquals(1, plan.size());
        assertEquals("repository.audit.read", plan.getFirst().requiredCapability());
        assertEquals("kelvinka38/bios", plan.getFirst().target());
        assertEquals(ExecutionWorkSpec.Consequence.READ_ONLY, plan.getFirst().consequence());
        assertTrue(plan.getFirst().verifiable());
    }

    @Test
    void boundedSingleRepositoryAuditNeverDependsOnFrontierPlanningCapacity() {
        AtomicInteger calls = new AtomicInteger();
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                calls.incrementAndGet();
                throw new IllegalStateException("provider must not be called for exact bounded capability binding");
            }
        };
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(google)), provider -> "planner-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());

        List<ExecutionWorkSpec> plan = planner.plan("case-bounded", normalizedExecution(null),
                List.of("repository.audit.read | governed read-only repository evidence adapter"));

        assertEquals(0, calls.get());
        assertEquals(1, plan.size());
        assertEquals("repository.audit.read", plan.getFirst().requiredCapability());
        assertTrue(plan.getFirst().verifiable());
    }

    @Test
    void plannerHardBoundsOversizedCapabilityInventoryBeforeAnyProviderCall() {
        AtomicInteger inputChars = new AtomicInteger();
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                inputChars.set(request.userInput().length());
                assertTrue(request.userInput().contains("objective=coordinate governed repository evidence work"));
                assertTrue(request.userInput().contains("repository.audit.read"));
                return planResponse(LlmProvider.GOOGLE);
            }
        };
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(google)), provider -> "planner-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());
        String giant = "x".repeat(400_000);

        List<ExecutionWorkSpec> plan = planner.plan("case-bounded-input", normalizedProviderExecution(null),
                List.of("repository.audit.read " + giant, "other.capability " + giant, "third.capability " + giant));

        assertEquals(1, plan.size());
        assertTrue(inputChars.get() > 0);
        assertTrue(inputChars.get() <= ExecutionWorkPlanner.MAX_PLANNER_INPUT_CHARS,
                "planner input must remain bounded, actual=" + inputChars.get());
    }


    @Test
    void explicitGeneralEngineeringWorkBindsBeforePlanningProviders() {
        AtomicInteger calls = new AtomicInteger();
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                calls.incrementAndGet();
                throw new IllegalStateException("simulated provider outage");
            }
        };
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(google)), provider -> "planner-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());

        List<ExecutionWorkSpec> plan = planner.plan(
                "case-general-provider-outage",
                normalizedGeneralEngineeringExecution(),
                List.of("execution.general.workspace"));

        assertEquals(0, calls.get());
        assertEquals(0, calls.get());
        assertEquals(4, plan.size());
        assertEquals(List.of("general-snapshot", "general-file-write", "general-test", "general-local-commit"),
                plan.stream().map(ExecutionWorkSpec::stepId).toList());
        assertTrue(plan.stream().allMatch(step -> step.requiredCapability().equals("execution.general.workspace")));
        assertEquals(ExecutionWorkSpec.Consequence.READ_ONLY, plan.get(0).consequence());
        assertEquals(ExecutionWorkSpec.Consequence.MUTATING, plan.get(1).consequence());
        assertEquals(ExecutionWorkSpec.Consequence.READ_ONLY, plan.get(2).consequence());
        assertEquals(ExecutionWorkSpec.Consequence.MUTATING, plan.get(3).consequence());
        assertEquals(List.of("general-snapshot"), plan.get(1).dependsOn());
        assertEquals(List.of("general-file-write"), plan.get(2).dependsOn());
        assertEquals(List.of("general-test"), plan.get(3).dependsOn());
        assertEquals("kelvinka38/metatron-workforce", plan.get(1).target());
        assertEquals("kelvinka38/metatron-workforce", plan.get(3).target());
        assertTrue(plan.get(1).objective().contains("source_sha=3e86d4e2876a90c580383d5c1de48360b5049b3b"));
    }


    @Test
    void productionNormalizedGeneralEngineeringRequestBindsBeforeProviderPlanning() {
        AtomicInteger calls = new AtomicInteger();
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                calls.incrementAndGet();
                throw new IllegalStateException("bounded general engineering must not depend on provider planning");
            }
        };
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(google)), provider -> "planner-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());

        String sha = "0028477b750724568db5ec20cb1f70ead133ba6c";
        NormalizedRequest normalized = new NormalizedRequest(
                "Materialize engineering objective against specific commit, generate autonomy proof document, execute repository test suite, and commit work product locally.",
                "kelvinka38/metatron-workforce",
                List.of(
                        "Use commit " + sha,
                        "Target file: docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md",
                        "Proof content must include exact source SHA",
                        "Repository test suite must pass",
                        "Only stage the proof file",
                        "Create exactly one local Git commit",
                        "Do not push or open pull request",
                        "Do not modify remote repository state"),
                IntelligenceDepth.DEEP,
                "Final confirmation of local commit hash and verification report.",
                List.of("Repository is accessible", "Test action is available via governed interface",
                        "Local workspace supports Git operations"),
                List.of("No remote pushes", "No pull request creation", "No remote state modification"),
                "", "", IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.of(AnalyticalProtocolType.AUDIT, AnalyticalProtocolType.IMPROVEMENT),
                DeterministicCapability.NONE, List.of(), List.of(), true, null, LlmProvider.GOOGLE, "");

        List<ExecutionWorkSpec> plan = planner.plan(
                "case-production-normalized-gs2", normalized, List.of("execution.general.workspace"));

        assertEquals(4, plan.size());
        assertEquals(List.of("general-snapshot", "general-file-write", "general-test", "general-local-commit"),
                plan.stream().map(ExecutionWorkSpec::stepId).toList());
        assertEquals("kelvinka38/metatron-workforce", plan.get(1).target());
        assertEquals("kelvinka38/metatron-workforce", plan.get(3).target());
        assertTrue(plan.get(1).objective().contains("source_sha=" + sha));
    }



    @Test
    void latestProductionGs2NormalizedShapeAlsoPrebindsGeneralWorkspace() {
        AtomicInteger calls = new AtomicInteger();
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                calls.incrementAndGet();
                return new LlmResponse(LlmProvider.GOOGLE, "planner-test", """
                        {"execution_work_plan":[
                          {"step_id":"step-1","objective":"materialize","target":"kelvinka38/metatron-workforce","required_capability":"execution.general.workspace","depends_on":[],"consequence":"MUTATING","acceptance_criteria":["materialized"],"evidence_requirements":["general-action-runtime:execution.general.workspace"]},
                          {"step_id":"step-2","objective":"write","target":"docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md","required_capability":"UNAVAILABLE:local-file-write","depends_on":["step-1"],"consequence":"MUTATING","acceptance_criteria":["written"],"evidence_requirements":["file evidence"]}
                        ]}
                        """, "planner-request");
            }
        };
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(google)), provider -> "planner-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());

        String sha = "8d8f3cab78dbfa114db874798d9807ab442187b6";
        NormalizedRequest normalized = new NormalizedRequest(
                "Take ownership of a governed general engineering Objective on kelvinka38/metatron-workforce at commit "
                        + sha + ", materialize the snapshot, create docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md "
                        + "with the source SHA, run the test suite, stage and commit the file locally, and verify via Observation "
                        + "without pushing or modifying remote state.",
                "kelvinka38/metatron-workforce",
                List.of(
                        "Use exact source commit " + sha,
                        "Materialize exact repository snapshot into the Objective workspace",
                        "Create or replace only docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md",
                        "Include exact source SHA in the proof file",
                        "Run repository test suite through governed test action and require it to pass",
                        "Stage only the proof file",
                        "Create one local Git commit as the immutable work product",
                        "Verify result through independent Observation",
                        "Do not push",
                        "Do not open a pull request",
                        "Do not modify any remote repository state"),
                IntelligenceDepth.DEEP,
                "durable immutable local git commit and verification proof",
                List.of(),
                List.of(
                        "Do not push",
                        "Do not open a pull request",
                        "Do not modify any remote repository state",
                        "Do not modify any files other than docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md"),
                "", "", IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.of(AnalyticalProtocolType.AUDIT, AnalyticalProtocolType.RISK, AnalyticalProtocolType.PERFORMANCE),
                DeterministicCapability.NONE, List.of(), List.of(), false, null, LlmProvider.GOOGLE, "");

        List<ExecutionWorkSpec> plan = planner.plan(
                "case-latest-production-gs2", normalized, List.of("execution.general.workspace"));

        assertEquals(0, calls.get());
        assertEquals(List.of("general-snapshot", "general-file-write", "general-test", "general-local-commit"),
                plan.stream().map(ExecutionWorkSpec::stepId).toList());
        assertTrue(plan.stream().allMatch(step -> step.requiredCapability().equals("execution.general.workspace")));
        assertTrue(plan.stream().noneMatch(step -> step.requiredCapability().startsWith("UNAVAILABLE:")));
    }

    @Test
    void telegramGeneralEngineeringProhibitionsBindDeterministicallyWithoutHostCommander() {
        AtomicInteger calls = new AtomicInteger();
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                calls.incrementAndGet();
                throw new IllegalStateException("bounded repository workspace work must not reach frontier planning");
            }
        };
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(google)), provider -> "planner-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());
        String sha = "a1fdf79af04b35620615b1d60bc7d4635bdf1be9";
        String objective = "Take ownership of one Objective: using repository kelvinka38/metatron-workforce at exact source SHA "
                + sha + ", materialize that exact source, create or replace only docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md "
                + "with the exact UTF-8 content source_sha=" + sha + ", run the repository test suite, stage only that proof file, "
                + "create one local Git commit, then verify the resulting commit and Git status.";
        NormalizedRequest normalized = new NormalizedRequest(
                objective,
                "kelvinka38/metatron-workforce",
                List.of(objective),
                IntelligenceDepth.DEEP,
                "durable evidence for materialization, file mutation, test success, local commit, and final verification",
                List.of(),
                List.of("Do not push, publish a PR, merge, or deploy"),
                "", "", IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.of(AnalyticalProtocolType.AUDIT, AnalyticalProtocolType.IMPROVEMENT),
                DeterministicCapability.NONE, List.of(), List.of(), false, null, LlmProvider.GOOGLE, "");

        List<ExecutionWorkSpec> plan = planner.plan(
                "case-telegram-general-engineering", normalized,
                List.of("execution.general.workspace", "host.commander.execute"));

        assertEquals(0, calls.get());
        assertEquals(List.of("general-snapshot", "general-file-write", "general-test", "general-local-commit"),
                plan.stream().map(ExecutionWorkSpec::stepId).toList());
        assertTrue(plan.stream().allMatch(step -> step.requiredCapability().equals("execution.general.workspace")));
        assertTrue(plan.stream().noneMatch(step -> step.requiredCapability().equals("host.commander.execute")));
    }

    @Test
    void gatewayDirectorAppointmentBindsWithoutFrontierPlanning() {
        AtomicInteger calls = new AtomicInteger();
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                calls.incrementAndGet();
                throw new IllegalStateException("canonical appointment must not require frontier planning");
            }
        };
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(google)), provider -> "planner-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());
        NormalizedRequest normalized = new NormalizedRequest(
                "Create a Workforce Worker and appoint it as the Gateway Director / Head of Gateway",
                "ROLE-HEAD-OF-GATEWAY",
                List.of("persistent institutional Worker", "use governed Workforce staffing"),
                IntelligenceDepth.ANALYZE,
                "appointment confirmation with Worker, role and runtime evidence",
                List.of(), List.of(), "", "", IntelligenceMode.EXECUTION,
                CollaborationMode.SINGLE, List.of(), DeterministicCapability.NONE,
                List.of(), List.of(), false, null, LlmProvider.GOOGLE, "");

        List<ExecutionWorkSpec> plan = planner.plan(
                "case-gateway-director-appointment",
                normalized,
                List.of("workforce.staffing.gateway-director"));

        assertEquals(0, calls.get());
        assertEquals(1, plan.size());
        ExecutionWorkSpec step = plan.getFirst();
        assertEquals("workforce.staffing.gateway-director", step.requiredCapability());
        assertEquals("ROLE-HEAD-OF-GATEWAY", step.target());
        assertEquals(ExecutionWorkSpec.Consequence.MUTATING, step.consequence());
        assertTrue(step.verifiable());
    }

    @Test
    void providerlessPlannerStillFailsClosedOutsideBoundedAuditShape() {
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of()), provider -> "unused", List.of(), new ObjectMapper());
        NormalizedRequest mutating = new NormalizedRequest(
                "fix code and open pull request", "kelvinka38/bios", List.of("mutation required"),
                IntelligenceDepth.ANALYZE, "pull request", List.of(), List.of(),
                "current", "", IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.of(AnalyticalProtocolType.AUDIT), DeterministicCapability.NONE, List.of(), List.of(),
                false, null, LlmProvider.GOOGLE, "");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> planner.plan("case-mutating", mutating, List.of("repository.audit.read")));

        assertEquals("execution_planning_provider_required", failure.getMessage());
    }

    @Test
    void plannerNormalizesGatewayDirectorAppointmentTargetWhenFrontierGuessesWrongTarget() {
        // Reproduces a real production incident (2026-09-16): the deterministic composer above only
        // fires when the objective text explicitly says "gateway director"/"gateway head"/etc. Here the
        // objective never mentions Gateway Director at all -- it asks for worker cognition -- but the
        // frontier planner independently decided to insert a workforce.staffing.gateway-director
        // prerequisite step and guessed target="workforce" (a plausible-sounding but wrong value; the
        // capability's AuthorityManifestCatalog entry only matches ROLE-HEAD-OF-GATEWAY/
        // position:gateway-director). Before this fix, AuthorityManifestCatalog.resolve("workforce")
        // threw AUTHORITY_UNRESOLVED and permanently blocked the Objective in production.
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                return new LlmResponse(LlmProvider.GOOGLE, "planner-test", """
                        {"execution_work_plan":[
                          {"step_id":"step-1","objective":"staff the workforce","target":"workforce","required_capability":"workforce.staffing.gateway-director","depends_on":[],"consequence":"MUTATING","acceptance_criteria":["staffed"],"evidence_requirements":["observation-capability:workforce.staffing.gateway-director"]},
                          {"step_id":"step-2","objective":"perform worker cognition","target":"","required_capability":"worker.cognitive.work","depends_on":["step-1"],"consequence":"READ_ONLY","acceptance_criteria":["cognition completes"],"evidence_requirements":["worker cognition evidence"]}
                        ]}
                        """, "planner-ref");
            }
        };
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(google)), provider -> "planner-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());
        NormalizedRequest normalized = new NormalizedRequest(
                "Take ownership of one Objective: use Worker cognition to summarize in one sentence that the "
                        + "Workforce system is healthy.",
                "", List.of(), IntelligenceDepth.ANALYZE, "one sentence summary", List.of(), List.of(),
                "", "", IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.of(), DeterministicCapability.NONE, List.of(), List.of(),
                false, null, LlmProvider.GOOGLE, "");

        List<ExecutionWorkSpec> plan = planner.plan("case-production-incident", normalized,
                List.of("workforce.staffing.gateway-director", "worker.cognitive.work"));

        assertEquals(2, plan.size());
        assertEquals("workforce.staffing.gateway-director", plan.get(0).requiredCapability());
        assertEquals("ROLE-HEAD-OF-GATEWAY", plan.get(0).target(),
                "frontier-guessed target must be normalized to the exact authority manifest pattern");
        assertEquals("worker.cognitive.work", plan.get(1).requiredCapability());
    }

    @Test
    void plannerNormalizesCognitionAssuranceTargetWhenFrontierInventsSyntheticModel() {
        // Reproduces the second half of the same production incident: after the gateway-director target
        // fix, step-3 (worker.cognition.assure) truthfully failed with MODEL_MISMATCH
        // expected=workforce/assurance -- the frontier planner invented a plausible-looking but
        // nonexistent model identity because the objective never actually specified one. The capability
        // itself now treats a blank target as "no specific model required"; this proves the planner
        // normalizer blanks a fabricated, unrecognized target rather than letting it through to needlessly
        // fail a real, successful cognition call.
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                return new LlmResponse(LlmProvider.GOOGLE, "planner-test", """
                        {"execution_work_plan":[
                          {"step_id":"step-1","objective":"perform worker cognition","target":"","required_capability":"worker.cognitive.work","depends_on":[],"consequence":"READ_ONLY","acceptance_criteria":["cognition completes"],"evidence_requirements":["worker cognition evidence"]},
                          {"step_id":"step-2","objective":"assure cognition ran through metatron owned path","target":"workforce/assurance","required_capability":"worker.cognition.assure","depends_on":["step-1"],"consequence":"READ_ONLY","acceptance_criteria":["assured"],"evidence_requirements":["cognition assurance evidence"]}
                        ]}
                        """, "planner-ref");
            }
        };
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(google)), provider -> "planner-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());
        NormalizedRequest normalized = new NormalizedRequest(
                "Take ownership of one Objective: use Worker cognition to summarize in one sentence that the "
                        + "Workforce system is healthy. Report the Worker assignment and cognition evidence.",
                "", List.of(), IntelligenceDepth.ANALYZE, "one sentence summary", List.of(), List.of(),
                "", "", IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.of(), DeterministicCapability.NONE, List.of(), List.of(),
                false, null, LlmProvider.GOOGLE, "");

        List<ExecutionWorkSpec> plan = planner.plan("case-production-incident-2", normalized,
                List.of("worker.cognitive.work", "worker.cognition.assure"));

        assertEquals(2, plan.size());
        assertEquals("worker.cognition.assure", plan.get(1).requiredCapability());
        assertEquals("", plan.get(1).target(),
                "fabricated non-model target must be blanked, not passed through to needlessly fail assurance");
    }

    @Test
    void plannerPreservesARealCognitionAssuranceTarget() {
        // A genuine, recognizable model identity must survive normalization unchanged -- this is not a
        // blanket wipe of the target field, only a filter against fabricated non-model values.
        LlmProviderClient google = new LlmProviderClient() {
            @Override public LlmProvider provider() { return LlmProvider.GOOGLE; }
            @Override public LlmResponse complete(LlmRequest request) {
                return new LlmResponse(LlmProvider.GOOGLE, "planner-test", """
                        {"execution_work_plan":[
                          {"step_id":"step-1","objective":"assure qwen3 is serving cognition","target":"qwen3:8b","required_capability":"worker.cognition.assure","depends_on":[],"consequence":"READ_ONLY","acceptance_criteria":["assured"],"evidence_requirements":["cognition assurance evidence"]}
                        ]}
                        """, "planner-ref");
            }
        };
        ExecutionWorkPlanner planner = new ExecutionWorkPlanner(
                new LlmProviderRouter(List.of(google)), provider -> "planner-test",
                List.of(LlmProvider.GOOGLE), new ObjectMapper());
        NormalizedRequest normalized = new NormalizedRequest(
                "Verify Worker cognition uses qwen3:8b through the Metatron-owned path.",
                "", List.of(), IntelligenceDepth.ANALYZE, "assurance result", List.of(), List.of(),
                "", "", IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.of(), DeterministicCapability.NONE, List.of(), List.of(),
                false, null, LlmProvider.GOOGLE, "");

        List<ExecutionWorkSpec> plan = planner.plan("case-real-model-target", normalized,
                List.of("worker.cognition.assure"));

        assertEquals(1, plan.size());
        assertEquals("qwen3:8b", plan.get(0).target());
    }

    private static LlmResponse planResponse(LlmProvider provider) {


        return new LlmResponse(provider, "planner-test", """
                {"execution_work_plan":[{
                  "step_id":"audit-repo",
                  "objective":"audit repository and preserve evidence",
                  "target":"kelvinka38/bios",
                  "required_capability":"repository.audit.read",
                  "depends_on":[],
                  "consequence":"READ_ONLY",
                  "acceptance_criteria":["repository audit is complete and evidence-backed"],
                  "evidence_requirements":["repository contents and cited audit evidence"]
                }]}
                """, "planner-ref");
    }

    private static NormalizedRequest normalizedExecution(LlmProvider explicitlyRequestedProvider) {
        return new NormalizedRequest(
                "execute governed repository audit", "kelvinka38/bios", List.of("preserve evidence", "read-only"),
                IntelligenceDepth.ANALYZE, "terminal audit result", List.of(), List.of("do not mutate"),
                "current", "", IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.of(AnalyticalProtocolType.AUDIT), DeterministicCapability.NONE, List.of(), List.of(),
                false, explicitlyRequestedProvider, LlmProvider.GOOGLE, "");
    }


    private static NormalizedRequest normalizedGeneralEngineeringExecution() {
        String sha = "3e86d4e2876a90c580383d5c1de48360b5049b3b";
        return new NormalizedRequest(
                "Take ownership of one governed general engineering Objective against kelvinka38/metatron-workforce at exact source commit "
                        + sha + ". Materialize that exact repository snapshot into the Objective workspace. "
                        + "Create or replace only docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md with a short proof containing exact source SHA "
                        + sha + ". Run the repository test suite through the governed test action and require it to pass. "
                        + "Stage only that proof file and create one local Git commit as the immutable work product. "
                        + "Verify the result through independent Observation. Do not push, do not open a pull request, "
                        + "do not modify any remote repository state.",
                "kelvinka38/metatron-workforce",
                List.of(
                        "exact source commit " + sha,
                        "create or replace only docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md",
                        "proof must contain exact source SHA " + sha,
                        "run repository test suite through governed test action and require it to pass",
                        "stage only that proof file and create one local Git commit",
                        "verify result through independent observation",
                        "do not push",
                        "do not open a pull request",
                        "do not modify any remote repository state"),
                IntelligenceDepth.DEEP, "direct natural-language answer", List.of(),
                List.of("do not push", "do not open a pull request", "do not modify any remote repository state"),
                "", "", IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.of(AnalyticalProtocolType.IMPROVEMENT, AnalyticalProtocolType.RISK),
                DeterministicCapability.NONE, List.of(), List.of(), true, null, LlmProvider.GOOGLE, "");
    }

    private static NormalizedRequest normalizedProviderExecution(LlmProvider explicitlyRequestedProvider) {
        return new NormalizedRequest(
                "coordinate governed repository evidence work", "kelvinka38/bios", List.of("preserve evidence"),
                IntelligenceDepth.ANALYZE, "terminal governed result", List.of(), List.of(),
                "current", "", IntelligenceMode.EXECUTION, CollaborationMode.SINGLE,
                List.of(AnalyticalProtocolType.RISK), DeterministicCapability.NONE, List.of(), List.of(),
                false, explicitlyRequestedProvider, LlmProvider.GOOGLE, "");
    }
}
