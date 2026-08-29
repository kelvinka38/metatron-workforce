package com.metatron.workforce.interaction.intelligence;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic registry of reusable analytical protocols selected by frontier semantics. */
public final class AnalyticalProtocolRegistry {
    private final Map<AnalyticalProtocolType, AnalyticalProtocol> protocols;

    public AnalyticalProtocolRegistry() {
        EnumMap<AnalyticalProtocolType, AnalyticalProtocol> map = new EnumMap<>(AnalyticalProtocolType.class);
        put(map, AnalyticalProtocolType.AUDIT,
                List.of("expected standard/control", "observed state", "supporting evidence with provenance"),
                List.of("scope boundaries", "material exceptions"),
                List.of("validation", "reconciliation"),
                List.of("gap analysis"),
                List.of("unsupported compliance claim", "missing evidence for pass/fail"),
                "control-by-control finding with evidence and unresolved gaps");
        put(map, AnalyticalProtocolType.COMPARE,
                List.of("entities/options being compared", "comparison criteria", "comparable evidence for each criterion"),
                List.of("weights/preferences"),
                List.of("normalization", "side-by-side calculation"),
                List.of("trade-off analysis"),
                List.of("non-comparable inputs", "selection bias"),
                "comparison showing criteria, evidence, trade-offs and unresolved uncertainty");
        put(map, AnalyticalProtocolType.ROOT_CAUSE,
                List.of("observed deviation/problem", "baseline or expected state", "candidate drivers with evidence"),
                List.of("segment breakdown", "change timeline", "counterfactual evidence"),
                List.of("variance decomposition", "temporal/segment comparison"),
                List.of("causal hypothesis evaluation"),
                List.of("correlation presented as causation", "surviving alternative explanations"),
                "ranked causal hypotheses with evidence, falsification and confidence limits");
        put(map, AnalyticalProtocolType.PERFORMANCE,
                List.of("current-period performance metrics", "comparison baseline", "metric definitions"),
                List.of("segment/channel/product breakdown", "targets", "operational drivers"),
                List.of("variance", "growth rate", "mix decomposition", "aggregation validation"),
                List.of("performance interpretation"),
                List.of("metric definition mismatch", "seasonality or mix confounder"),
                "performance assessment with quantified variance, drivers and evidence");
        put(map, AnalyticalProtocolType.FORECAST,
                List.of("forecast horizon", "historical observations", "material drivers/assumptions"),
                List.of("scenario bounds", "external leading indicators"),
                List.of("trend calculation", "scenario arithmetic"),
                List.of("forecast synthesis"),
                List.of("unstated assumptions", "structural break", "false precision"),
                "forecast with assumptions, scenarios, uncertainty and invalidation conditions");
        put(map, AnalyticalProtocolType.INVESTMENT,
                List.of("capital/resource requirement", "expected economics/cash flows", "market or demand evidence", "material risks"),
                List.of("competitive alternatives", "exit/option value", "scenario sensitivities"),
                List.of("unit economics", "cash-flow model", "sensitivity calculation"),
                List.of("strategic and risk assessment"),
                List.of("optimism bias", "missing downside scenario", "unsupported demand assumption"),
                "investment case with economics, scenarios, risks and decision conditions");
        put(map, AnalyticalProtocolType.INCIDENT,
                List.of("incident symptoms", "timeline", "affected scope", "observations/log evidence"),
                List.of("recent changes", "dependency status"),
                List.of("timeline correlation", "error-rate/health comparison"),
                List.of("incident hypothesis analysis"),
                List.of("post-hoc causation", "unobserved dependency failure"),
                "incident assessment with timeline, hypotheses, evidence and recovery constraints");
        put(map, AnalyticalProtocolType.RISK,
                List.of("asset/objective at risk", "threat/failure mode", "impact evidence", "existing controls"),
                List.of("likelihood evidence", "risk appetite/tolerance"),
                List.of("exposure calculation where possible"),
                List.of("risk synthesis"),
                List.of("unknown likelihood treated as zero", "control assumed effective without evidence"),
                "risk assessment with exposure, controls, uncertainty and mitigation options");
        put(map, AnalyticalProtocolType.IMPROVEMENT,
                List.of("current baseline", "desired outcome", "binding constraint/problem"),
                List.of("candidate interventions", "implementation cost", "success metric"),
                List.of("baseline/target delta", "expected impact calculation where possible"),
                List.of("intervention design"),
                List.of("solution without diagnosed constraint", "benefit without measurable success condition"),
                "improvement options tied to diagnosed constraints, expected impact and validation plan");
        put(map, AnalyticalProtocolType.DECISION,
                List.of("decision objective", "available options", "decision criteria", "constraints"),
                List.of("stakeholder preferences", "reversibility", "authority context"),
                List.of("option scoring where appropriate"),
                List.of("trade-off and consequence analysis"),
                List.of("missing option", "criterion conflict", "recommendation presented as authorization"),
                "decision support recommendation; never manufactured institutional authorization");
        this.protocols = Map.copyOf(map);
    }

    public AnalyticalProtocol require(AnalyticalProtocolType type) {
        return Objects.requireNonNull(protocols.get(type), "unknown protocol: " + type);
    }

    public List<AnalyticalProtocol> resolve(List<AnalyticalProtocolType> types) {
        Objects.requireNonNull(types, "types");
        return types.stream().distinct().map(this::require).toList();
    }

    private static void put(EnumMap<AnalyticalProtocolType, AnalyticalProtocol> map,
                            AnalyticalProtocolType type,
                            List<String> minimum,
                            List<String> optional,
                            List<String> deterministic,
                            List<String> reasoning,
                            List<String> falsification,
                            String output) {
        map.put(type, new AnalyticalProtocol(type, minimum, optional, deterministic, reasoning, falsification, output));
    }
}
