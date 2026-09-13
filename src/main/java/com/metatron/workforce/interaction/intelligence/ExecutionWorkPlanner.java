package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.interaction.llm.LlmProviderRouter;
import com.metatron.workforce.interaction.llm.LlmRequest;
import com.metatron.workforce.interaction.llm.LlmResponse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Post-semantic, post-Case institutional execution work planner. */
public final class ExecutionWorkPlanner implements ExecutionPlanProposalService {
    private static final String REPOSITORY_PR_PROPOSE = "repository.pr.propose";
    private static final String REPOSITORY_AUDIT_READ = "repository.audit.read";
    private static final String CROSS_REPOSITORY_AUDIT_ANALYSIS = "cross-repository-audit-analysis";
    private static final String GENERAL_WORKSPACE = "execution.general.workspace";
    private static final String HOST_COMMANDER = "host.commander.execute";
    private static final String GATEWAY_DIRECTOR_APPOINTMENT = "workforce.staffing.gateway-director";
    private static final java.util.regex.Pattern EXACT_GIT_SHA =
            java.util.regex.Pattern.compile("(?<![0-9a-fA-F])[0-9a-fA-F]{40}(?![0-9a-fA-F])");
    private static final java.util.regex.Pattern WORKSPACE_FILE_PATH =
            java.util.regex.Pattern.compile("(?<![A-Za-z0-9_.-])([A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+\\.[A-Za-z0-9_.-]+)(?![A-Za-z0-9_.-])");
    static final int MAX_PLANNER_INPUT_CHARS = 24_000;
    private static final int MAX_CAPABILITY_ENTRIES = 128;
    private static final int MAX_CAPABILITY_ENTRY_CHARS = 256;

    private static final String SYSTEM = """
            You are Metatron's institutional execution work planner.
            You receive an already normalized Human request and an already-created Intelligence Case reference.
            Do NOT reinterpret raw Human language. Do NOT manufacture authority, authorization, assignment, execution evidence, Observation or completion.

            For an EXECUTION request, return ONLY one JSON object:
            {
              "execution_work_plan": [
                {
                  "step_id": "...",
                  "objective": "...",
                  "target": "...",
                  "required_capability": "...",
                  "depends_on": [],
                  "consequence": "READ_ONLY|MUTATING",
                  "acceptance_criteria": ["criterion stated as an observable condition"],
                  "evidence_requirements": ["evidence needed to independently verify that criterion"]
                }
              ]
            }

            Rules:
            - Decompose only the normalized objective actually requested.
            - Every material Work step MUST state at least one observable acceptance criterion and at least one evidence requirement.
            - Acceptance criteria describe the desired observable outcome; they are not execution claims.
            - Evidence requirements describe what an independent Observation must inspect or obtain; never fabricate evidence refs.
            - Use exact refs from AVAILABLE EXECUTION CAPABILITIES when a capability can perform the step.
            - Prefer one available bounded/composite capability over inventing lower-level effects that are not independently available.
            - `repository.pr.propose` is the bounded governed mutation capability for the approved Autonomy Closure repair: it performs the approved file repair on a branch and opens the pull request. It never merges. When that capability satisfies a repair-and-open-PR objective, do NOT invent a separate `repository.content.write` step.
            - `cross-repository-audit-analysis` is an evidence-bound join capability, not a repository reader. For a multi-repository audit, create one `repository.audit.read` step per repository and make the analysis step depend on every audit step. Never use `cross-repository-audit-analysis` for a single-repository audit.
            - If no capability can perform a step, use UNAVAILABLE:<short-semantic-capability-need>.
            - A READ_ONLY capability cannot satisfy MUTATING work.
            - Preserve explicit prohibitions and constraints.
            - Normalize an unambiguous GitHub repository target to owner/repo when needed.
            - Work planning is not authority, authorization, assignment, execution, Observation or evidence.
            - Dependencies may only refer to earlier steps.
            """;

    private final IntelligenceFabric fabric;
    private final int providerBudget;
    private final ObjectMapper mapper;
    private final LlmProviderRouter compatibilityRouter;
    private final Function<LlmProvider, String> compatibilityModelSelector;
    private final List<LlmProvider> compatibilityProviders;

    /** Compatibility constructor; provider transport remains contained inside Intelligence. */
    public ExecutionWorkPlanner(LlmProviderRouter router,
                                Function<LlmProvider, String> modelSelector,
                                List<LlmProvider> configuredProviders,
                                ObjectMapper mapper) {
        this.compatibilityRouter = Objects.requireNonNull(router, "router");
        this.compatibilityModelSelector = Objects.requireNonNull(modelSelector, "modelSelector");
        Objects.requireNonNull(configuredProviders, "configuredProviders");
        this.compatibilityProviders = configuredProviders.stream().distinct().toList();
        this.fabric = compatibilityFabric(router, modelSelector, configuredProviders);
        this.providerBudget = this.compatibilityProviders.size();
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public ExecutionWorkPlanner(IntelligenceFabric fabric, int providerBudget, ObjectMapper mapper) {
        this.fabric = Objects.requireNonNull(fabric, "fabric");
        this.providerBudget = Math.max(0, providerBudget);
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.compatibilityRouter = null;
        this.compatibilityModelSelector = null;
        this.compatibilityProviders = List.of();
    }

    private static IntelligenceFabric compatibilityFabric(
            LlmProviderRouter router,
            Function<LlmProvider, String> modelSelector,
            List<LlmProvider> configuredProviders) {
        Objects.requireNonNull(router, "router");
        Objects.requireNonNull(modelSelector, "modelSelector");
        Objects.requireNonNull(configuredProviders, "configuredProviders");
        List<LlmProvider> providers = configuredProviders.stream().distinct().toList();
        RouterBackedIntelligenceEngine engine = new RouterBackedIntelligenceEngine(router, modelSelector);
        return new IntelligenceFabric(
                new IntelligencePlanner(new AdaptiveProviderRoutingPolicy(providers, router.telemetry())),
                engine,
                new EvidencePreservingIntelligenceSynthesizer(),
                new EvidenceBackedGovernance());
    }

    @Override
    public List<ExecutionWorkSpec> propose(String caseId, NormalizedRequest normalized,
                                           List<String> availableExecutionCapabilities) {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(normalized, "normalized");
        Objects.requireNonNull(availableExecutionCapabilities, "availableExecutionCapabilities");
        if (normalized.mode() != IntelligenceMode.EXECUTION) return List.of();

        // Capability binding is deterministic when one governed bounded capability already exactly
        // covers the normalized work. Invoking frontier providers first adds latency/capacity failure
        // without adding decomposition value and can incorrectly BLOCK otherwise executable work.
        List<ExecutionWorkSpec> deterministicSingleRepositoryPlan = deterministicSingleRepositoryAudit(
                normalized, availableExecutionCapabilities);
        if (!deterministicSingleRepositoryPlan.isEmpty() && normalized.explicitlyRequestedProvider() == null) {
            validate(deterministicSingleRepositoryPlan);
            return deterministicSingleRepositoryPlan;
        }
        List<ExecutionWorkSpec> deterministicGatewayDirectorAppointment = deterministicGatewayDirectorAppointment(
                normalized, availableExecutionCapabilities);
        if (!deterministicGatewayDirectorAppointment.isEmpty() && normalized.explicitlyRequestedProvider() == null) {
            validate(deterministicGatewayDirectorAppointment);
            return deterministicGatewayDirectorAppointment;
        }

        List<ExecutionWorkSpec> deterministicHostCommanderPlan = deterministicHostCommanderWork(
                normalized, availableExecutionCapabilities);
        if (!deterministicHostCommanderPlan.isEmpty() && normalized.explicitlyRequestedProvider() == null) {
            validate(deterministicHostCommanderPlan);
            return deterministicHostCommanderPlan;
        }

        List<ExecutionWorkSpec> deterministicCrossRepositoryFallback = deterministicCrossRepositoryAudit(
                normalized, availableExecutionCapabilities);
        if (!deterministicCrossRepositoryFallback.isEmpty() && normalized.explicitlyRequestedProvider() == null) {
            validate(deterministicCrossRepositoryFallback);
            return deterministicCrossRepositoryFallback;
        }
        List<ExecutionWorkSpec> deterministicGeneralEngineeringFallback = deterministicGeneralEngineeringWork(
                normalized, availableExecutionCapabilities);
        if (!deterministicGeneralEngineeringFallback.isEmpty() && normalized.explicitlyRequestedProvider() == null) {
            validate(deterministicGeneralEngineeringFallback);
            return deterministicGeneralEngineeringFallback;
        }
        List<ExecutionWorkSpec> deterministicExternalResearchFallback = deterministicExternalResearchWork(
                normalized, availableExecutionCapabilities);
        if (!deterministicExternalResearchFallback.isEmpty() && normalized.explicitlyRequestedProvider() == null) {
            validate(deterministicExternalResearchFallback);
            return deterministicExternalResearchFallback;
        }
        if (providerBudget < 1) {
            if (!deterministicSingleRepositoryPlan.isEmpty()) {
                validate(deterministicSingleRepositoryPlan);
                return deterministicSingleRepositoryPlan;
            }
            if (!deterministicCrossRepositoryFallback.isEmpty()) {
                validate(deterministicCrossRepositoryFallback);
                return deterministicCrossRepositoryFallback;
            }
            if (!deterministicGeneralEngineeringFallback.isEmpty()) {
                validate(deterministicGeneralEngineeringFallback);
                return deterministicGeneralEngineeringFallback;
            }
            if (!deterministicExternalResearchFallback.isEmpty()) {
                validate(deterministicExternalResearchFallback);
                return deterministicExternalResearchFallback;
            }
            throw new IllegalStateException("execution_planning_provider_required");
        }

        String prefix = "INTELLIGENCE CASE REF:\n" + bounded(caseId, 512)
                + "\n\nNORMALIZED REQUEST (structured; already semantically interpreted):\n"
                + bounded(render(normalized), 8_000)
                + "\n\nAVAILABLE EXECUTION CAPABILITIES (inventory only; never authority):\n";
        if (prefix.length() >= MAX_PLANNER_INPUT_CHARS) {
            throw new IllegalStateException("execution_planning_input_exceeds_bound_before_capability_inventory");
        }
        String input = prefix + renderCapabilities(
                availableExecutionCapabilities, MAX_PLANNER_INPUT_CHARS - prefix.length());
        if (input.length() > MAX_PLANNER_INPUT_CHARS) {
            throw new IllegalStateException("execution_planning_input_exceeds_bound");
        }

        if (compatibilityRouter != null) {
            return proposeViaCompatibilityTransport(
                    normalized, availableExecutionCapabilities, input,
                    deterministicSingleRepositoryPlan, deterministicCrossRepositoryFallback,
                    deterministicGeneralEngineeringFallback, deterministicExternalResearchFallback);
        }

        RuntimeException intelligenceFailure = null;
        try {
            List<LlmProvider> requestedProviders = normalized.explicitlyRequestedProvider() == null
                    ? List.of() : List.of(normalized.explicitlyRequestedProvider());
            IntelligenceRequest request = new IntelligenceRequest(
                    "execution-planning-" + caseId,
                    "workforce-management",
                    IntelligenceMode.REASONING,
                    CollaborationMode.SINGLE,
                    input,
                    SYSTEM,
                    List.of("intelligence-case:" + caseId),
                    "execution.work.planning",
                    IntelligenceConsequencePolicy.forNonConsequentialMode(IntelligenceMode.REASONING),
                    "management-planning",
                    "bounded",
                    "",
                    "one strict execution_work_plan JSON object",
                    requestedProviders,
                    requestedProviders.isEmpty() ? providerBudget : 1,
                    false);
            IntelligenceResult result = fabric.execute(request);
            List<ExecutionWorkSpec> plan = parse(result.text(), "IntelligenceFabric");
            plan = reconcileCompositeCapabilities(normalized, availableExecutionCapabilities, plan);
            plan = reconcileCrossRepositoryAuditJoin(normalized, availableExecutionCapabilities, plan);
            plan = reconcileSingleRepositoryAudit(normalized, availableExecutionCapabilities, plan);
            validate(plan);
            if (plan.isEmpty()) throw new IllegalStateException("execution planner returned empty plan");
            return plan;
        } catch (RuntimeException failure) {
            intelligenceFailure = failure;
            // Deterministic fallbacks below remain available during Intelligence capacity failure.
        }
        if (!deterministicSingleRepositoryPlan.isEmpty()) {
            validate(deterministicSingleRepositoryPlan);
            return deterministicSingleRepositoryPlan;
        }
        if (!deterministicCrossRepositoryFallback.isEmpty()) {
            validate(deterministicCrossRepositoryFallback);
            return deterministicCrossRepositoryFallback;
        }
        if (!deterministicGeneralEngineeringFallback.isEmpty()) {
            validate(deterministicGeneralEngineeringFallback);
            return deterministicGeneralEngineeringFallback;
        }
        if (!deterministicExternalResearchFallback.isEmpty()) {
            validate(deterministicExternalResearchFallback);
            return deterministicExternalResearchFallback;
        }
        IllegalStateException all = new IllegalStateException("all execution planning providers failed through Intelligence");
        if (intelligenceFailure != null) {
            all.addSuppressed(new IllegalStateException(
                    "execution planning Intelligence failed: " + intelligenceFailure.getMessage(),
                    intelligenceFailure));
        }
        throw all;
    }

    private List<ExecutionWorkSpec> proposeViaCompatibilityTransport(
            NormalizedRequest normalized,
            List<String> availableExecutionCapabilities,
            String input,
            List<ExecutionWorkSpec> deterministicSingleRepositoryPlan,
            List<ExecutionWorkSpec> deterministicCrossRepositoryFallback,
            List<ExecutionWorkSpec> deterministicGeneralEngineeringFallback,
            List<ExecutionWorkSpec> deterministicExternalResearchFallback) {
        List<LlmProvider> orderedProviders = providersForCompatibility(normalized);
        List<RuntimeException> failures = new ArrayList<>();
        for (LlmProvider provider : orderedProviders) {
            try {
                LlmResponse response = compatibilityRouter.complete(new LlmRequest(
                        provider, compatibilityModelSelector.apply(provider), SYSTEM, input));
                List<ExecutionWorkSpec> plan = parse(response.text(), provider.name());
                plan = reconcileCompositeCapabilities(normalized, availableExecutionCapabilities, plan);
                plan = reconcileCrossRepositoryAuditJoin(normalized, availableExecutionCapabilities, plan);
                plan = reconcileSingleRepositoryAudit(normalized, availableExecutionCapabilities, plan);
                validate(plan);
                if (plan.isEmpty()) throw new IllegalStateException("execution planner returned empty plan");
                return plan;
            } catch (RuntimeException failure) {
                failures.add(new IllegalStateException(
                        "execution planning provider failed: " + provider + ": " + failure.getMessage(), failure));
            }
        }
        if (!deterministicSingleRepositoryPlan.isEmpty()) return validated(deterministicSingleRepositoryPlan);
        if (!deterministicCrossRepositoryFallback.isEmpty()) return validated(deterministicCrossRepositoryFallback);
        if (!deterministicGeneralEngineeringFallback.isEmpty()) return validated(deterministicGeneralEngineeringFallback);
        if (!deterministicExternalResearchFallback.isEmpty()) return validated(deterministicExternalResearchFallback);
        IllegalStateException all = new IllegalStateException(
                "all execution planning providers failed: " + orderedProviders);
        failures.forEach(all::addSuppressed);
        throw all;
    }

    private List<LlmProvider> providersForCompatibility(NormalizedRequest normalized) {
        LlmProvider explicit = normalized.explicitlyRequestedProvider();
        if (explicit != null) {
            return compatibilityProviders.contains(explicit) ? List.of(explicit) : List.of();
        }
        return AdaptiveProviderRoutingPolicy.rankConfiguredProviders(
                compatibilityProviders, compatibilityRouter.telemetry());
    }

    private static List<ExecutionWorkSpec> validated(List<ExecutionWorkSpec> plan) {
        validate(plan);
        return plan;
    }

    public List<ExecutionWorkSpec> plan(String caseId, NormalizedRequest normalized,
                                        List<String> availableExecutionCapabilities) {
        return propose(caseId, normalized, availableExecutionCapabilities);
    }

    private static String render(NormalizedRequest request) {
        return "objective=" + request.objective()
                + "\ntarget=" + request.target()
                + "\nconstraints=" + request.constraints()
                + "\nexplicit_prohibitions=" + request.explicitProhibitions()
                + "\nrequested_output=" + request.requestedOutput()
                + "\nmode=" + request.mode()
                + "\nanalytical_protocols=" + request.analyticalProtocols()
                + "\ntemporal_context=" + request.temporalContext();
    }

    private static String renderCapabilities(List<String> capabilities, int charBudget) {
        if (capabilities.isEmpty() || charBudget <= 0) return "NONE";
        StringBuilder out = new StringBuilder(Math.min(charBudget, 4096));
        int emitted = 0;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String raw : capabilities) {
            if (emitted >= MAX_CAPABILITY_ENTRIES || out.length() >= charBudget) break;
            String line = raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
            if (line.isBlank()) continue;
            line = bounded(line, MAX_CAPABILITY_ENTRY_CHARS);
            if (!seen.add(line)) continue;
            int remaining = charBudget - out.length();
            if (remaining <= 1) break;
            if (line.length() + 1 > remaining) line = line.substring(0, Math.max(0, remaining - 1));
            if (line.isBlank()) break;
            out.append(line).append('\n');
            emitted++;
        }
        if (out.isEmpty()) return "NONE";
        if (emitted < capabilities.size()) {
            String marker = "... capability inventory bounded; omitted=" + Math.max(0, capabilities.size() - emitted);
            int remaining = charBudget - out.length();
            if (remaining > 1) out.append(marker, 0, Math.min(marker.length(), remaining - 1)).append('\n');
        }
        return out.toString().trim();
    }

    private static String bounded(String value, int maxChars) {
        if (value == null || value.isBlank() || maxChars <= 0) return "";
        String trimmed = value.trim();
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars);
    }

    private List<ExecutionWorkSpec> parse(String responseText, String source) {
        try {
            JsonNode root = mapper.readTree(unwrapJson(responseText));
            JsonNode node = root.path("execution_work_plan");
            if (!node.isArray()) return List.of();
            List<ExecutionWorkSpec> values = new ArrayList<>();
            node.forEach(item -> {
                if (!item.isObject()) return;
                String stepId = optionalText(item, "step_id");
                String objective = optionalText(item, "objective");
                String target = optionalText(item, "target");
                String capability = optionalText(item, "required_capability");
                List<String> dependsOn = textArray(item, "depends_on");
                String consequence = optionalText(item, "consequence");
                List<String> criteria = textArray(item, "acceptance_criteria");
                List<String> evidenceRequirements = textArray(item, "evidence_requirements");
                if (stepId.isBlank() || objective.isBlank() || capability.isBlank() || consequence.isBlank()) return;
                values.add(new ExecutionWorkSpec(stepId, objective, target, capability, dependsOn,
                        enumValue(ExecutionWorkSpec.Consequence.class, consequence), criteria, evidenceRequirements));
            });
            return List.copyOf(values);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("invalid execution plan from " + source, failure);
        }
    }

    private static List<ExecutionWorkSpec> reconcileCompositeCapabilities(
            NormalizedRequest normalized,
            List<String> availableExecutionCapabilities,
            List<ExecutionWorkSpec> plan) {
        if (!hasCapability(availableExecutionCapabilities, REPOSITORY_PR_PROPOSE)
                || !requestsRepositoryPullRequest(normalized)
                || plan.isEmpty()) return plan;

        int unavailableWriteIndex = -1;
        int pullRequestIndex = -1;
        for (int i = 0; i < plan.size(); i++) {
            ExecutionWorkSpec step = plan.get(i);
            if (step.consequence() == ExecutionWorkSpec.Consequence.MUTATING
                    && isUnavailableRepositoryWrite(step.requiredCapability())) {
                if (unavailableWriteIndex >= 0) return plan;
                unavailableWriteIndex = i;
            }
            if (REPOSITORY_PR_PROPOSE.equals(step.requiredCapability())) {
                if (pullRequestIndex >= 0) return plan;
                pullRequestIndex = i;
            }
        }
        if (unavailableWriteIndex < 0 || pullRequestIndex <= unavailableWriteIndex) return plan;

        String expectedTarget = normalizeTarget(normalized.target());
        for (int i = unavailableWriteIndex; i <= pullRequestIndex; i++) {
            String target = normalizeTarget(plan.get(i).target());
            if (!expectedTarget.isBlank() && !target.isBlank() && !expectedTarget.equals(target)) return plan;
            if (i > unavailableWriteIndex) {
                String previousStep = plan.get(i - 1).stepId();
                if (!plan.get(i).dependsOn().contains(previousStep)) return plan;
            }
        }

        ExecutionWorkSpec first = plan.get(unavailableWriteIndex);
        LinkedHashSet<String> criteria = new LinkedHashSet<>();
        LinkedHashSet<String> evidence = new LinkedHashSet<>();
        Set<String> removedStepIds = new LinkedHashSet<>();
        for (int i = unavailableWriteIndex; i <= pullRequestIndex; i++) {
            ExecutionWorkSpec step = plan.get(i);
            removedStepIds.add(step.stepId());
            criteria.addAll(step.acceptanceCriteria());
            evidence.addAll(step.evidenceRequirements());
        }

        ExecutionWorkSpec composite = new ExecutionWorkSpec(
                first.stepId(), normalized.objective(),
                normalized.target().isBlank() ? first.target() : normalized.target(),
                REPOSITORY_PR_PROPOSE, first.dependsOn(), ExecutionWorkSpec.Consequence.MUTATING,
                List.copyOf(criteria), List.copyOf(evidence));

        List<ExecutionWorkSpec> reconciled = new ArrayList<>();
        reconciled.addAll(plan.subList(0, unavailableWriteIndex));
        reconciled.add(composite);
        for (int i = pullRequestIndex + 1; i < plan.size(); i++) {
            ExecutionWorkSpec step = plan.get(i);
            List<String> dependencies = step.dependsOn().stream()
                    .map(dependency -> removedStepIds.contains(dependency) ? composite.stepId() : dependency)
                    .distinct().toList();
            reconciled.add(new ExecutionWorkSpec(
                    step.stepId(), step.objective(), step.target(), step.requiredCapability(), dependencies,
                    step.consequence(), step.acceptanceCriteria(), step.evidenceRequirements()));
        }
        return List.copyOf(reconciled);
    }

    private static List<ExecutionWorkSpec> reconcileCrossRepositoryAuditJoin(
            NormalizedRequest normalized,
            List<String> availableExecutionCapabilities,
            List<ExecutionWorkSpec> plan) {
        if (!hasCapability(availableExecutionCapabilities, REPOSITORY_AUDIT_READ)
                || !hasCapability(availableExecutionCapabilities, CROSS_REPOSITORY_AUDIT_ANALYSIS)
                || plan.size() != 1) return plan;

        ExecutionWorkSpec analysis = plan.getFirst();
        if (!CROSS_REPOSITORY_AUDIT_ANALYSIS.equals(analysis.requiredCapability())
                || analysis.consequence() != ExecutionWorkSpec.Consequence.READ_ONLY
                || !analysis.dependsOn().isEmpty()) return plan;

        List<String> repositories = requestedRepositoryTargets(normalized.target());
        if (repositories.size() < 2) return plan;

        List<ExecutionWorkSpec> reconciled = new ArrayList<>();
        List<String> dependencies = new ArrayList<>();
        for (int i = 0; i < repositories.size(); i++) {
            String repository = repositories.get(i);
            String stepId = analysis.stepId() + "-audit-" + (i + 1);
            dependencies.add(stepId);
            reconciled.add(new ExecutionWorkSpec(
                    stepId, "Perform governed read-only repository audit for " + repository,
                    repository, REPOSITORY_AUDIT_READ, List.of(), ExecutionWorkSpec.Consequence.READ_ONLY,
                    List.of("governed read-only repository audit completes for " + repository),
                    List.of("durable repository.audit.read execution evidence for " + repository)));
        }
        reconciled.add(new ExecutionWorkSpec(
                analysis.stepId(), analysis.objective(), analysis.target(), CROSS_REPOSITORY_AUDIT_ANALYSIS,
                List.copyOf(dependencies), ExecutionWorkSpec.Consequence.READ_ONLY,
                analysis.acceptanceCriteria(), analysis.evidenceRequirements()));
        return List.copyOf(reconciled);
    }

    private static List<ExecutionWorkSpec> reconcileSingleRepositoryAudit(
            NormalizedRequest normalized,
            List<String> availableExecutionCapabilities,
            List<ExecutionWorkSpec> plan) {
        List<ExecutionWorkSpec> deterministic = deterministicSingleRepositoryAudit(normalized, availableExecutionCapabilities);
        if (deterministic.isEmpty() || plan.isEmpty()) return plan;
        boolean compatible = plan.stream().allMatch(step ->
                step.consequence() == ExecutionWorkSpec.Consequence.READ_ONLY
                        && (REPOSITORY_AUDIT_READ.equals(step.requiredCapability())
                        || CROSS_REPOSITORY_AUDIT_ANALYSIS.equals(step.requiredCapability())));
        if (!compatible) return plan;
        return deterministic;
    }

    private static List<ExecutionWorkSpec> deterministicSingleRepositoryAudit(
            NormalizedRequest normalized,
            List<String> availableExecutionCapabilities) {
        if (!hasCapability(availableExecutionCapabilities, REPOSITORY_AUDIT_READ)) return List.of();
        List<String> repositories = requestedRepositoryTargets(normalized.target());
        if (repositories.size() != 1) return List.of();
        String semantic = (normalized.objective() + " " + normalized.constraints() + " "
                + normalized.explicitProhibitions() + " " + normalized.requestedOutput()).toLowerCase(Locale.ROOT);
        boolean auditIntent = semantic.contains("audit");
        boolean readOnly = semantic.contains("read-only") || semantic.contains("read only")
                || semantic.contains("do not mutate") || semantic.contains("without mutation")
                || semantic.contains("without mutating");
        boolean mutationIntent = semantic.contains("pull request") || semantic.contains(" deploy")
                || semantic.contains(" delete") || semantic.contains(" merge") || semantic.contains(" commit")
                || semantic.contains(" push") || semantic.contains(" write") || semantic.contains(" modify")
                || semantic.contains(" update file") || semantic.contains(" change file") || semantic.contains(" fix code");
        if (!auditIntent || !readOnly || mutationIntent) return List.of();

        String repository = repositories.getFirst();
        return List.of(new ExecutionWorkSpec(
                "repository-audit-read", normalized.objective(), repository, REPOSITORY_AUDIT_READ, List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("governed read-only repository audit completes for " + repository
                        + " and produces evidence sufficient for Observation"),
                List.of("durable repository.audit.read execution evidence for " + repository
                        + " including externally attributable repository evidence")));
    }



    private static List<ExecutionWorkSpec> deterministicGatewayDirectorAppointment(
            NormalizedRequest normalized,
            List<String> availableExecutionCapabilities) {
        if (!hasCapability(availableExecutionCapabilities, GATEWAY_DIRECTOR_APPOINTMENT)) return List.of();
        String semantic = (normalized.objective() + " " + normalized.target() + " "
                + normalized.constraints() + " " + normalized.requestedOutput()).toLowerCase(Locale.ROOT);
        boolean gatewayDirector = semantic.contains("gateway director")
                || semantic.contains("gateway head")
                || semantic.contains("head of gateway")
                || semantic.contains("role-head-of-gateway");
        boolean appointment = semantic.contains("appoint") || semantic.contains("create")
                || semantic.contains("form") || semantic.contains("staff");
        if (!gatewayDirector || !appointment) return List.of();

        return List.of(new ExecutionWorkSpec(
                "appoint-gateway-director",
                "Form and appoint the canonical Gateway Director / Head of Gateway Worker through governed Workforce staffing",
                "ROLE-HEAD-OF-GATEWAY",
                GATEWAY_DIRECTOR_APPOINTMENT,
                List.of(),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of(
                        "Gateway Director Worker is ACTIVE with ROLE-HEAD-OF-GATEWAY participation",
                        "Gateway Director has the approved gateway.audit.read capability",
                        "Gateway Director has a durable usable runtime/tool profile binding"),
                List.of(
                        "observation-capability:workforce.staffing.gateway-director",
                        "staffing policy and Worker formation evidence",
                        "active Gateway Director role/position participation evidence",
                        "gateway.audit.read capability attestation",
                        "runtime-profile-bound evidence")));
    }

    private static List<ExecutionWorkSpec> deterministicCrossRepositoryAudit(
            NormalizedRequest normalized,
            List<String> availableExecutionCapabilities) {
        if (!hasCapability(availableExecutionCapabilities, REPOSITORY_AUDIT_READ)
                || !hasCapability(availableExecutionCapabilities, CROSS_REPOSITORY_AUDIT_ANALYSIS)) {
            return List.of();
        }
        List<String> repositories = requestedRepositoryTargets(normalized.target());
        if (repositories.size() < 2) return List.of();

        String semantic = (normalized.objective() + " " + normalized.constraints() + " "
                + normalized.explicitProhibitions() + " " + normalized.requestedOutput()).toLowerCase(Locale.ROOT);
        boolean auditIntent = semantic.contains("audit");
        boolean readOnly = semantic.contains("read-only") || semantic.contains("read only")
                || semantic.contains("do not mutate") || semantic.contains("without mutation")
                || semantic.contains("without mutating");
        boolean mutationIntent = semantic.contains("pull request") || semantic.contains(" deploy")
                || semantic.contains(" delete") || semantic.contains(" merge") || semantic.contains(" commit")
                || semantic.contains(" push") || semantic.contains(" write") || semantic.contains(" modify")
                || semantic.contains(" update file") || semantic.contains(" change file") || semantic.contains(" fix code");
        if (!auditIntent || !readOnly || mutationIntent) return List.of();

        List<ExecutionWorkSpec> plan = new ArrayList<>();
        List<String> dependencies = new ArrayList<>();
        for (int i = 0; i < repositories.size(); i++) {
            String repository = repositories.get(i);
            String stepId = "repository-audit-read-" + (i + 1);
            dependencies.add(stepId);
            plan.add(new ExecutionWorkSpec(
                    stepId,
                    "Perform governed read-only repository audit for " + repository,
                    repository,
                    REPOSITORY_AUDIT_READ,
                    List.of(),
                    ExecutionWorkSpec.Consequence.READ_ONLY,
                    List.of("governed read-only repository audit completes for " + repository),
                    List.of("durable repository.audit.read execution evidence for " + repository)));
        }
        plan.add(new ExecutionWorkSpec(
                "cross-repository-audit-analysis",
                normalized.objective(),
                normalized.target(),
                CROSS_REPOSITORY_AUDIT_ANALYSIS,
                List.copyOf(dependencies),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("all requested repository audits are joined, contradiction candidates are analyzed, and zero mutation is verified"),
                List.of("durable cross-repository analysis evidence referencing every prerequisite repository audit")));
        return List.copyOf(plan);
    }


    private static List<ExecutionWorkSpec> deterministicHostCommanderWork(
            NormalizedRequest normalized,
            List<String> availableExecutionCapabilities) {
        if (!hasCapability(availableExecutionCapabilities, HOST_COMMANDER)) return List.of();
        if (!requestedRepositoryTargets(normalized.target()).isEmpty()) return List.of();
        String semantic = (normalized.objective() + " " + normalized.target() + " "
                + normalized.constraints() + " " + normalized.explicitProhibitions() + " "
                + normalized.requestedOutput()).toLowerCase(Locale.ROOT);
        boolean commanderIntent = semantic.contains("host commander")
                || semantic.contains("real host")
                || semantic.contains("metatron host")
                || semantic.contains("commander session");
        boolean productionControlIntent = (semantic.contains("metatron production")
                || semantic.contains("production host") || semantic.contains("production server"))
                && (semantic.contains("prove") || semantic.contains("verify") || semantic.contains("demonstrate")
                || semantic.contains("chung minh"))
                && (semantic.contains("operate") || semantic.contains("operation") || semantic.contains("control")
                || semantic.contains("working") || semantic.contains("works") || semantic.contains("hoat dong"));
        boolean hostEffect = semantic.contains("uptime") || semantic.contains("docker")
                || semantic.contains("container") || semantic.contains("/tmp/metatron-commander/")
                || semantic.contains("runtime identity") || semantic.contains("generation")
                || semantic.contains("host file") || semantic.contains("host process")
                || semantic.contains("host storage") || semantic.contains("host network")
                || productionControlIntent;
        if (!(commanderIntent || productionControlIntent) || !hostEffect) return List.of();
        boolean mutating = productionControlIntent
                || semantic.contains("create") || semantic.contains("write")
                || semantic.contains("patch") || semantic.contains("remove")
                || semantic.contains("delete") || semantic.contains("restart")
                || semantic.contains("cleanup") || semantic.contains("terminate")
                || semantic.contains("maintenance session");
        return List.of(new ExecutionWorkSpec(
                "host-commander-execution", normalized.objective(), "host:metatron-production", HOST_COMMANDER, List.of(),
                mutating ? ExecutionWorkSpec.Consequence.MUTATING : ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("Requested Metatron host operation completes through the governed Host Commander and is independently verified"),
                List.of("host-commander broker execution evidence", "Host Commander security boundaries remain fail-closed")));
    }

    private static List<ExecutionWorkSpec> deterministicExternalResearchWork(
            NormalizedRequest normalized,
            List<String> availableExecutionCapabilities) {
        if (!hasCapability(availableExecutionCapabilities, GENERAL_WORKSPACE)) return List.of();
        if (!requestedRepositoryTargets(normalized.target()).isEmpty()) return List.of();

        String semantic = (normalized.objective() + " " + normalized.target() + " "
                + normalized.constraints() + " " + normalized.requestedOutput()).toLowerCase(Locale.ROOT);
        boolean hostOperation = (semantic.contains("host commander") || semantic.contains("real host") || semantic.contains("metatron host"))
                && (semantic.contains("uptime") || semantic.contains("docker") || semantic.contains("container")
                || semantic.contains("/tmp/metatron-commander/") || semantic.contains("runtime identity"));
        if (hostOperation) return List.of();
        boolean researchIntent = semantic.contains("research")
                || semantic.contains("paper") || semantic.contains("publication")
                || semantic.contains("report") || semantic.contains("standard")
                || semantic.contains("regulator") || semantic.contains("regulatory")
                || semantic.contains("evidence") || semantic.contains("source");
        boolean externalReality = normalized.freshExternalDataRequired()
                || semantic.contains("new ") || semantic.contains("recent")
                || semantic.contains("current") || semantic.contains("external")
                || semantic.contains("web") || semantic.contains("internet")
                || semantic.contains("source");
        if (!researchIntent || !externalReality) return List.of();

        List<String> acceptance = new ArrayList<>();
        acceptance.add("External research deliverable satisfies the normalized Objective: " + normalized.objective());
        if (!normalized.requestedOutput().isBlank()) {
            acceptance.add("Requested output is produced: " + normalized.requestedOutput());
        }
        List<String> evidence = List.of(
                "research-action:research.web.search",
                "externally attributable source URLs support each material finding",
                "general-work-output contains the completed research deliverable");
        return List.of(new ExecutionWorkSpec(
                "general-external-research",
                normalized.objective(),
                normalized.target(),
                GENERAL_WORKSPACE,
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                acceptance,
                evidence));
    }


    private static List<ExecutionWorkSpec> deterministicGeneralEngineeringWork(
            NormalizedRequest normalized,
            List<String> availableExecutionCapabilities) {
        if (normalized.explicitlyRequestedProvider() != null) return List.of();
        if (!hasCapability(availableExecutionCapabilities, GENERAL_WORKSPACE)) return List.of();

        List<String> repositories = requestedRepositoryTargets(normalized.target());
        if (repositories.size() != 1) return List.of();

        String original = normalized.objective() + " " + normalized.constraints() + " "
                + normalized.explicitProhibitions() + " " + normalized.requestedOutput();
        String semantic = original.toLowerCase(Locale.ROOT);
        java.util.regex.Matcher shaMatcher = EXACT_GIT_SHA.matcher(original);
        if (!shaMatcher.find()) return List.of();
        String sourceSha = shaMatcher.group().toLowerCase(Locale.ROOT);

        java.util.regex.Matcher pathMatcher = WORKSPACE_FILE_PATH.matcher(original);
        String workspacePath = "";
        while (pathMatcher.find()) {
            String candidate = pathMatcher.group(1);
            if (!candidate.toLowerCase(Locale.ROOT).startsWith("kelvinka38/")) {
                workspacePath = candidate;
                break;
            }
        }
        if (workspacePath.isBlank()) return List.of();
        String boundedWorkspacePath = workspacePath;

        List<String> constraints = normalized.constraints().stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).toList();
        List<String> prohibitions = normalized.explicitProhibitions().stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).toList();
        String requestedOutput = normalized.requestedOutput().toLowerCase(Locale.ROOT);

        boolean materialize = semantic.contains("materializ") || semantic.contains("snapshot") || semantic.contains("checkout");
        boolean targetFileBounded = constraints.stream().anyMatch(value ->
                value.contains("target file") && value.contains(boundedWorkspacePath.toLowerCase(Locale.ROOT)));
        boolean proofFileIntent = semantic.contains("proof") && semantic.contains(boundedWorkspacePath.toLowerCase(Locale.ROOT));
        boolean explicitFileBounded = (semantic.contains("create or replace only")
                || semantic.contains("write only") || semantic.contains("create only"))
                && semantic.contains(boundedWorkspacePath.toLowerCase(Locale.ROOT));
        boolean writeOneFile = (targetFileBounded && proofFileIntent) || explicitFileBounded;
        boolean exactShaProof = semantic.contains("proof") && semantic.contains(sourceSha)
                && constraints.stream().anyMatch(value -> value.contains("proof") && value.contains("exact source sha"));
        boolean test = semantic.contains("test suite") || semantic.contains("test action")
                || semantic.contains("tests pass") || semantic.contains("test suite must pass")
                || semantic.contains("run test");
        boolean stage = semantic.contains("stage only") || semantic.contains("only stage")
                || constraints.stream().anyMatch(value -> value.contains("stage") && value.contains("only"));
        boolean localCommit = semantic.contains("local git commit") || semantic.contains("local commit")
                || semantic.contains("create one local") || semantic.contains("exactly one local git commit")
                || semantic.contains("commit work product locally");
        boolean verify = semantic.contains("verify") || semantic.contains("verification")
                || semantic.contains("independent observation") || requestedOutput.contains("verification");
        String prohibitionSemantic = String.join(" ", prohibitions);
        boolean pushForbidden = prohibitionSemantic.contains("push");
        boolean pullRequestForbidden = prohibitionSemantic.contains("pull request")
                || prohibitionSemantic.contains("publish a pr")
                || prohibitionSemantic.contains("publish pr")
                || prohibitionSemantic.contains("open a pr");
        boolean remoteStateForbidden = prohibitionSemantic.contains("remote") && prohibitionSemantic.contains("state");
        boolean mergeAndDeployForbidden = prohibitionSemantic.contains("merge") && prohibitionSemantic.contains("deploy");
        boolean remoteMutationForbidden = pushForbidden && pullRequestForbidden
                && (remoteStateForbidden || mergeAndDeployForbidden);

        if (!materialize || !writeOneFile || !exactShaProof || !test || !stage
                || !localCommit || !verify || !remoteMutationForbidden) return List.of();

        String repository = repositories.getFirst();
        String proofContent = "source_sha=" + sourceSha + "\n";
        ExecutionWorkSpec snapshot = new ExecutionWorkSpec(
                "general-snapshot",
                "Materialize exact repository snapshot " + repository + " at source commit " + sourceSha,
                repository,
                GENERAL_WORKSPACE,
                List.of(),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("Objective workspace contains the exact repository source snapshot " + sourceSha),
                List.of("workspace.repository.materialize sourceCommitSha=" + sourceSha));

        ExecutionWorkSpec write = new ExecutionWorkSpec(
                "general-file-write",
                "Create or replace only " + workspacePath + " with a short proof containing exact source SHA "
                        + sourceSha + ". Exact UTF-8 content: " + proofContent.trim(),
                repository,
                GENERAL_WORKSPACE,
                List.of(snapshot.stepId()),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of(workspacePath + " exists in the Objective workspace and contains exact source SHA " + sourceSha),
                List.of("successful workspace.file.write for " + workspacePath));

        ExecutionWorkSpec tests = new ExecutionWorkSpec(
                "general-test",
                "Run the repository test suite through the governed test action and require it to pass",
                repository,
                GENERAL_WORKSPACE,
                List.of(write.stepId()),
                ExecutionWorkSpec.Consequence.READ_ONLY,
                List.of("repository test suite passes through workspace.test.run"),
                List.of("successful workspace.test.run evidence"));

        ExecutionWorkSpec commit = new ExecutionWorkSpec(
                "general-local-commit",
                "Stage only " + workspacePath + " and create one local Git commit as the immutable work product; "
                        + "verify the commit and do not push or modify remote repository state",
                repository,
                GENERAL_WORKSPACE,
                List.of(tests.stepId()),
                ExecutionWorkSpec.Consequence.MUTATING,
                List.of("one local Git commit exists containing exactly " + workspacePath),
                List.of("successful workspace.git.run add", "successful workspace.git.run commit",
                        "workspace.git.status verification"));
        return List.of(snapshot, write, tests, commit);
    }

    private static boolean hasCapability(List<String> capabilities, String required) {
        for (String raw : capabilities) {
            if (raw == null) continue;
            String value = raw.trim();
            if (value.equals(required)) return true;
            if (value.startsWith(required + " ") || value.startsWith(required + "|")
                    || value.startsWith(required + "\t") || value.startsWith(required + " - ")) return true;
        }
        return false;
    }

    private static List<String> requestedRepositoryTargets(String target) {
        if (target == null || target.isBlank()) return List.of();
        LinkedHashSet<String> repositories = new LinkedHashSet<>();
        for (String raw : target.split(",")) {
            String repository = normalizeTarget(raw);
            if (repository.matches("[a-z0-9_.-]+/[a-z0-9_.-]+")) repositories.add(repository);
        }
        return List.copyOf(repositories);
    }

    private static boolean requestsRepositoryPullRequest(NormalizedRequest normalized) {
        String objective = normalized.objective().toLowerCase(Locale.ROOT);
        if (!objective.contains("pull request")) return false;
        String target = normalizeTarget(normalized.target());
        return target.contains("/");
    }

    private static boolean isUnavailableRepositoryWrite(String capability) {
        String value = capability == null ? "" : capability.trim().toLowerCase(Locale.ROOT);
        if (!value.startsWith("unavailable:")) return false;
        String need = value.substring("unavailable:".length());
        return need.contains("repository") && (need.contains("write") || need.contains("content"));
    }

    private static String normalizeTarget(String target) {
        if (target == null) return "";
        String value = target.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("https://github.com/")) value = value.substring("https://github.com/".length());
        return value.replaceAll("\\.git$", "");
    }

    private static void validate(List<ExecutionWorkSpec> plan) {
        List<String> seen = new ArrayList<>();
        for (ExecutionWorkSpec step : plan) {
            if (seen.contains(step.stepId())) throw new IllegalStateException("duplicate execution step id: " + step.stepId());
            if (!step.verifiable()) throw new IllegalStateException("execution step lacks criterion-level verification requirements: " + step.stepId());
            for (String dependency : step.dependsOn()) {
                if (!seen.contains(dependency)) throw new IllegalStateException("execution step dependency must reference an earlier step: " + dependency);
            }
            seen.add(step.stepId());
        }
    }

    private static String unwrapJson(String text) {
        String value = text.trim();
        if (value.startsWith("```")) {
            int firstNewline = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) value = value.substring(firstNewline + 1, lastFence).trim();
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalStateException("execution planning response is not JSON");
        return value.substring(start, end + 1);
    }

    private static String optionalText(JsonNode root, String field) {
        JsonNode node = root.path(field);
        return node.isTextual() ? node.asText().trim() : "";
    }

    private static List<String> textArray(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(item -> { if (item.isTextual() && !item.asText().isBlank()) values.add(item.asText().trim()); });
        return List.copyOf(values);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    }
}
