package com.metatron.workforce.deliberation;

import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Universal deliberation decorator around the existing provider-neutral Worker intelligence boundary.
 * It does not own providers, routing, authority or execution. It only governs interaction/work progression.
 */
public final class DeliberatingWorkerIntelligenceService implements WorkerIntelligenceService {
    private final WorkerIntelligenceService delegate;
    private final WorkerDeliberationRuntime deliberation;

    public DeliberatingWorkerIntelligenceService(WorkerIntelligenceService delegate, WorkerDeliberationRuntime deliberation) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.deliberation = Objects.requireNonNull(deliberation, "deliberation");
    }

    @Override
    public Response reason(Request request) {
        Objects.requireNonNull(request, "request");
        if (!"worker.live.conversation".equals(request.capability())) {
            return delegate.reason(request);
        }

        WorkerDeliberationRuntime.Directive directive = deliberation.prepare(
                request.requester(), extractHumanMessage(request.context()), request.context());
        List<String> evidence = new ArrayList<>(request.evidenceReferences());
        evidence.add("worker-deliberation:stage=" + directive.state().stage());
        evidence.add("worker-deliberation:next=" + directive.state().nextMove());
        evidence.add("worker-deliberation:sufficiency=" + directive.state().contextSufficiency());

        Request governed = new Request(
                request.requester(),
                request.capability(),
                request.instructions() + "\n\n" + directive.instructions(),
                request.context(),
                List.copyOf(evidence));
        Response response = delegate.reason(governed);
        deliberation.completeTurn(request.requester(), directive.state().nextMove());

        List<String> responseEvidence = new ArrayList<>(response.evidenceReferences());
        responseEvidence.add("worker-deliberation:stage=" + directive.state().stage());
        responseEvidence.add("worker-deliberation:next=" + directive.state().nextMove());
        return new Response(response.requestReference(), response.text(), List.copyOf(responseEvidence));
    }

    private static String extractHumanMessage(String context) {
        if (context == null || context.isBlank()) return "";
        int marker = context.indexOf("HUMAN MESSAGE");
        if (marker < 0) return "";
        String tail = context.substring(marker + "HUMAN MESSAGE".length());
        int next = tail.indexOf("CONVERSATION CONTEXT");
        return (next >= 0 ? tail.substring(0, next) : tail).trim();
    }
}
