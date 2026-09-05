package com.metatron.workforce.action;

import com.metatron.workforce.interaction.tools.DefaultToolFabric;
import com.metatron.workforce.interaction.tools.ToolAdapter;
import com.metatron.workforce.interaction.tools.ToolRequest;
import com.metatron.workforce.interaction.tools.ToolResult;
import com.metatron.workforce.interaction.tools.WebSearchToolAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Governed read-only external research action for a Cognitive Worker.
 *
 * <p>The Worker chooses only the search query. External retrieval remains inside the existing
 * ToolFabric/WebSearchToolAdapter boundary, which preserves source attribution and rejects
 * semantically irrelevant evidence. This Action never grants mutation authority.</p>
 */
public final class GeneralWebResearchAction implements ActionFabric.Action {
    public static final String ACTION_REF = "research.web.search";

    private final String workerId;
    private final String authorizationReference;
    private final DefaultToolFabric tools;

    public GeneralWebResearchAction(String workerId, String authorizationReference) {
        this(workerId, authorizationReference, new DefaultToolFabric(List.of(new WebSearchToolAdapter())));
    }

    GeneralWebResearchAction(String workerId,
                             String authorizationReference,
                             DefaultToolFabric tools) {
        this.workerId = require(workerId, "workerId");
        this.authorizationReference = require(authorizationReference, "authorizationReference");
        this.tools = Objects.requireNonNull(tools, "tools");
    }

    GeneralWebResearchAction(String workerId,
                             String authorizationReference,
                             ToolAdapter adapter) {
        this(workerId, authorizationReference, new DefaultToolFabric(List.of(Objects.requireNonNull(adapter, "adapter"))));
    }

    @Override public String actionRef() { return ACTION_REF; }
    @Override public ActionFabric.Consequence consequence() { return ActionFabric.Consequence.READ_ONLY; }
    @Override public Set<String> allowedWorkers() { return Set.of(workerId); }
    @Override public Set<String> acceptedAuthorizations() { return Set.of(authorizationReference); }

    @Override
    public ActionFabric.ActionObservation invoke(ActionFabric.ActionRequest request) {
        Objects.requireNonNull(request, "request");
        String query = require(request.inputs().get("query"), "query");
        ToolRequest toolRequest = new ToolRequest(
                request.idempotencyKey() + ":research:" + Integer.toUnsignedString(query.hashCode()),
                request.workerId(),
                WebSearchToolAdapter.CAPABILITY,
                "public-internet",
                "search",
                query,
                List.of(request.authorizationReference(), request.assignmentReference(), request.objectiveId()));
        ToolResult result = tools.execute(toolRequest);

        List<String> evidence = new ArrayList<>(result.evidenceReferences());
        evidence.add("research-web-query:objective=" + request.objectiveId()
                + ":step=" + request.workStepId()
                + ":sources=" + result.evidenceReferences().size());

        if (!result.success()) {
            return ActionFabric.ActionObservation.failure(
                    ACTION_REF,
                    "governed external research failed: " + abbreviate(result.output()),
                    evidence);
        }
        return ActionFabric.ActionObservation.success(
                ACTION_REF,
                "governed external research returned attributable evidence",
                Map.of(
                        "query", query,
                        "researchResult", result.output(),
                        "sourceCount", Integer.toString(result.evidenceReferences().size())),
                evidence);
    }

    private static String abbreviate(String value) {
        String clean = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= 500 ? clean : clean.substring(0, 500);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }
}
