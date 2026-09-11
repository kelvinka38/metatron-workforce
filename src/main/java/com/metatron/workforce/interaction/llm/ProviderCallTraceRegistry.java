package com.metatron.workforce.interaction.llm;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bounded in-process observability for individual frontier calls.
 *
 * This registry records what happened; it does not grant authority, own institutional
 * memory, or decide provider routing. Unknown provider usage remains UNKNOWN rather than
 * being estimated or fabricated.
 */
public final class ProviderCallTraceRegistry {
    private static final int MAX_RECORDS = 10_000;
    private static final int MAX_FAILURE_CHARS = 500;

    private final CopyOnWriteArrayList<ProviderCallTrace> traces = new CopyOnWriteArrayList<>();

    public void success(LlmRequest request, long startedNanos, LlmResponse response) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        LlmUsage usage = response.usage() == null ? LlmUsage.UNKNOWN_USAGE : response.usage();
        append(new ProviderCallTrace(
                request.logicalRequestRef(),
                request.caseRef(),
                request.purpose(),
                request.reasonCode(),
                response.provider(),
                response.model(),
                true,
                elapsedMillis(startedNanos),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.totalTokens(),
                request.systemContext().length(),
                request.userInput().length(),
                "",
                Instant.now()));
    }

    public void failure(LlmRequest request, long startedNanos, RuntimeException failure) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(failure, "failure");
        append(new ProviderCallTrace(
                request.logicalRequestRef(),
                request.caseRef(),
                request.purpose(),
                request.reasonCode(),
                request.provider(),
                request.model(),
                false,
                elapsedMillis(startedNanos),
                LlmUsage.UNKNOWN,
                LlmUsage.UNKNOWN,
                LlmUsage.UNKNOWN,
                request.systemContext().length(),
                request.userInput().length(),
                compactFailure(failure),
                Instant.now()));
    }

    public List<ProviderCallTrace> records() {
        return List.copyOf(traces);
    }

    public List<ProviderCallTrace> forLogicalRequest(String logicalRequestRef) {
        String ref = logicalRequestRef == null ? "" : logicalRequestRef.trim();
        if (ref.isBlank()) return List.of();
        return traces.stream().filter(item -> ref.equals(item.logicalRequestRef())).toList();
    }

    public long totalCallsForLogicalRequest(String logicalRequestRef) {
        return forLogicalRequest(logicalRequestRef).size();
    }

    public void clear() {
        traces.clear();
    }

    private void append(ProviderCallTrace trace) {
        traces.add(trace);
        int overflow = traces.size() - MAX_RECORDS;
        if (overflow > 0) {
            List<ProviderCallTrace> snapshot = new ArrayList<>(traces);
            traces.clear();
            traces.addAll(snapshot.subList(Math.min(overflow, snapshot.size()), snapshot.size()));
        }
    }

    private static long elapsedMillis(long startedNanos) {
        if (startedNanos <= 0L) return 0L;
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static String compactFailure(RuntimeException failure) {
        String value = String.valueOf(failure.getMessage());
        if (value == null || value.equals("null")) value = failure.getClass().getSimpleName();
        value = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (value.length() > MAX_FAILURE_CHARS) value = value.substring(0, MAX_FAILURE_CHARS);
        return value;
    }

    public record ProviderCallTrace(
            String logicalRequestRef,
            String caseRef,
            String purpose,
            String reasonCode,
            LlmProvider provider,
            String model,
            boolean success,
            long latencyMillis,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            int systemContextChars,
            int userInputChars,
            String failure,
            Instant completedAt) {
        public ProviderCallTrace {
            logicalRequestRef = logicalRequestRef == null ? "" : logicalRequestRef;
            caseRef = caseRef == null ? "" : caseRef;
            purpose = purpose == null || purpose.isBlank() ? "unspecified" : purpose;
            reasonCode = reasonCode == null ? "" : reasonCode;
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(model, "model");
            failure = failure == null ? "" : failure;
            Objects.requireNonNull(completedAt, "completedAt");
            if (latencyMillis < 0L || systemContextChars < 0 || userInputChars < 0) {
                throw new IllegalArgumentException("trace sizes/latency must be non-negative");
            }
        }
    }
}
