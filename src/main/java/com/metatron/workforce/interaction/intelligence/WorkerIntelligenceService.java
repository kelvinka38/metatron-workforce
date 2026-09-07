package com.metatron.workforce.interaction.intelligence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Provider-neutral Intelligence boundary used by institutional Cognitive Workers. */
@FunctionalInterface
public interface WorkerIntelligenceService {
    Response reason(Request request);

    record Request(
            String requester,
            String capability,
            String instructions,
            String context,
            List<String> evidenceReferences) {
        public Request {
            Objects.requireNonNull(requester, "requester");
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(instructions, "instructions");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(evidenceReferences, "evidenceReferences");
            evidenceReferences = List.copyOf(evidenceReferences);
            if (requester.isBlank() || capability.isBlank() || instructions.isBlank()) {
                throw new IllegalArgumentException("worker intelligence request fields must not be blank");
            }
        }
    }

    record Response(String requestReference, String text, List<String> evidenceReferences) {
        public Response {
            Objects.requireNonNull(requestReference, "requestReference");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(evidenceReferences, "evidenceReferences");
            evidenceReferences = List.copyOf(evidenceReferences);
            if (requestReference.isBlank() || text.isBlank()) {
                throw new IllegalArgumentException("worker intelligence response fields must not be blank");
            }
        }
    }

    static WorkerIntelligenceService backedBy(IntelligenceFabric fabric, int configuredProviderCount) {
        Objects.requireNonNull(fabric, "fabric");
        int providerBudget = Math.max(1, configuredProviderCount);
        return request -> {
            String requestId = "worker-cognition-" + UUID.randomUUID();
            LinkedHashSet<String> governedEvidence = new LinkedHashSet<>(request.evidenceReferences());
            // Cycle 1 legitimately has no prior action observation yet. The governed Intelligence
            // request itself is durable reasoning-input provenance and satisfies BIOS without
            // fabricating external evidence or weakening the governance gate.
            governedEvidence.add("worker-cognition-input:" + requestId);
            IntelligenceRequest intelligenceRequest = new IntelligenceRequest(
                    requestId,
                    request.requester(),
                    IntelligenceMode.REASONING,
                    CollaborationMode.SINGLE,
                    request.context(),
                    "COGNITIVE INSTRUCTIONS\n" + request.instructions(),
                    List.copyOf(governedEvidence),
                    request.capability(),
                    IntelligenceConsequencePolicy.forNonConsequentialMode(IntelligenceMode.REASONING),
                    "work-runtime",
                    "bounded",
                    "",
                    "strict structured cognitive result",
                    List.of(),
                    providerBudget,
                    false);
            IntelligenceResult result = fabric.execute(intelligenceRequest);
            List<String> evidence = new ArrayList<>(result.evidenceReferences());
            evidence.add("worker-intelligence-request:" + requestId);
            result.providerResults().forEach(provider ->
                    evidence.add("worker-intelligence-provider:" + provider.provider()
                            + ":model=" + provider.response().model()
                            + ":request=" + safe(provider.response().providerRequestReference())));
            return new Response(requestId, result.text(), List.copyOf(evidence));
        };
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
