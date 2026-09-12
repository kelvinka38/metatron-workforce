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
            List<String> evidenceReferences,
            String workerId,
            String objectiveId,
            String assignmentId,
            String stepId,
            String executionAttemptId) {
        public Request(String requester,
                       String capability,
                       String instructions,
                       String context,
                       List<String> evidenceReferences) {
            this(requester, capability, instructions, context, evidenceReferences,
                    requester, "", "", "", "");
        }

        public Request {
            Objects.requireNonNull(requester, "requester");
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(instructions, "instructions");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(evidenceReferences, "evidenceReferences");
            workerId = clean(workerId);
            objectiveId = clean(objectiveId);
            assignmentId = clean(assignmentId);
            stepId = clean(stepId);
            executionAttemptId = clean(executionAttemptId);
            evidenceReferences = List.copyOf(evidenceReferences);
            if (requester.isBlank() || capability.isBlank() || instructions.isBlank() || workerId.isBlank()) {
                throw new IllegalArgumentException("worker intelligence request fields must not be blank");
            }
        }

        private static String clean(String value) { return value == null ? "" : value.trim(); }
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
        return backedBy(fabric, configuredProviderCount, Thread::sleep);
    }

    static WorkerIntelligenceService backedBy(
            IntelligenceFabric fabric,
            int configuredProviderCount,
            RetrySleeper retrySleeper) {
        Objects.requireNonNull(fabric, "fabric");
        Objects.requireNonNull(retrySleeper, "retrySleeper");
        int providerBudget = Math.max(1, configuredProviderCount);
        return request -> {
            String requestId = "worker-cognition-" + UUID.randomUUID();
            LinkedHashSet<String> governedEvidence = new LinkedHashSet<>(request.evidenceReferences());
            // Cycle 1 legitimately has no prior action observation yet. The governed Intelligence
            // request itself is durable reasoning-input provenance and satisfies BIOS without
            // fabricating external evidence or weakening the governance gate.
            governedEvidence.add("worker-cognition-input:" + requestId);

            IntelligenceResult result = null;
            RuntimeException lastFailure = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
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
                        false,
                        IntelligenceOriginContext.worker(
                                request.workerId(), request.objectiveId(), request.assignmentId(), request.stepId(),
                                request.executionAttemptId(), request.capability(), requestId));
                try {
                    result = fabric.execute(intelligenceRequest);
                    break;
                } catch (RuntimeException failure) {
                    lastFailure = failure;
                    long delayMillis = "worker.cognition".equals(request.capability())
                            ? transientProviderRecoveryDelayMillis(failure)
                            : 0L;
                    if (attempt >= 2 || delayMillis <= 0L) throw failure;
                    governedEvidence.add("worker-intelligence-capacity-retry:" + requestId
                            + ":attempt=2:delay_ms=" + delayMillis);
                    try {
                        retrySleeper.sleep(delayMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("worker intelligence capacity retry interrupted", interrupted);
                    }
                }
            }
            if (result == null) throw Objects.requireNonNull(lastFailure, "worker intelligence result/failure");

            List<String> evidence = new ArrayList<>(result.evidenceReferences());
            evidence.add("worker-intelligence-request:" + requestId);
            result.providerResults().forEach(provider ->
                    evidence.add("worker-intelligence-provider:" + provider.provider()
                            + ":model=" + provider.response().model()
                            + ":request=" + safe(provider.response().providerRequestReference())));
            return new Response(requestId, result.text(), List.copyOf(evidence));
        };
    }

    static long transientProviderRecoveryDelayMillis(Throwable failure) {
        if (failure == null) return 0L;
        java.util.ArrayDeque<Throwable> pending = new java.util.ArrayDeque<>();
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        pending.add(failure);
        boolean transientNetworkOrServer = false;
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (current == null || !seen.add(current)) continue;
            String message = String.valueOf(current.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (message.contains("429") || message.contains("rate limit") || message.contains("quota")
                    || message.contains("capacity_exhausted") || message.contains("resource_exhausted")) {
                return 60_000L;
            }
            if (message.contains("500") || message.contains("502") || message.contains("503")
                    || message.contains("504") || message.contains("timeout")
                    || message.contains("temporarily unavailable") || current instanceof java.io.IOException) {
                transientNetworkOrServer = true;
            }
            if (current.getCause() != null) pending.addLast(current.getCause());
            for (Throwable suppressed : current.getSuppressed()) pending.addLast(suppressed);
        }
        return transientNetworkOrServer ? 5_000L : 0L;
    }

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
