package com.metatron.workforce.interaction.intelligence;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded Metatron-owned cognition capacity coordinator.
 *
 * <p>Worker count is intentionally decoupled from inference concurrency. Saturation queues within a
 * bounded internal lane or fails truthfully; this class has no external-provider fallback path.</p>
 */
public final class CognitionCapacityCoordinator implements MetatronCognitionClient {
    private final MetatronCognitionClient delegate;
    private final CognitionCapacityEventStore events;
    private final Semaphore permits;
    private final AtomicInteger queued = new AtomicInteger();
    private final int maxConcurrent;
    private final int maxQueued;
    private final Duration queueWait;

    public CognitionCapacityCoordinator(
            MetatronCognitionClient delegate,
            CognitionCapacityEventStore events,
            int maxConcurrent,
            int maxQueued,
            Duration queueWait) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.events = Objects.requireNonNull(events, "events");
        if (maxConcurrent < 1) throw new IllegalArgumentException("maxConcurrent must be >= 1");
        if (maxQueued < 0) throw new IllegalArgumentException("maxQueued must be >= 0");
        this.queueWait = Objects.requireNonNull(queueWait, "queueWait");
        if (queueWait.isNegative()) throw new IllegalArgumentException("queueWait must not be negative");
        this.permits = new Semaphore(maxConcurrent, true);
        this.maxConcurrent = maxConcurrent;
        this.maxQueued = maxQueued;
        this.events.reconcileIncomplete();
    }

    @Override
    public Response reason(Request request) {
        Objects.requireNonNull(request, "request");
        events.latest(request.requestId()).ifPresent(latest -> {
            if (latest.state() == CognitionRequestState.SUCCEEDED) {
                throw new IllegalStateException("cognition_request_already_succeeded:" + request.requestId());
            }
        });

        events.append(CognitionCapacityEvent.transition(request, CognitionRequestState.ADMITTED,
                "metatron_owned_compute_admitted"));

        boolean acquired = permits.tryAcquire();
        if (!acquired) {
            int queuePosition = queued.incrementAndGet();
            if (queuePosition > maxQueued) {
                queued.decrementAndGet();
                events.append(CognitionCapacityEvent.transition(request, CognitionRequestState.FAILED_RETRYABLE,
                        "internal_capacity_queue_full"));
                throw new IllegalStateException("metatron_cognition_capacity_queue_full");
            }
            events.append(CognitionCapacityEvent.transition(request, CognitionRequestState.QUEUED,
                    "internal_capacity_wait_position=" + queuePosition));
            try {
                acquired = permits.tryAcquire(queueWait.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                events.append(CognitionCapacityEvent.transition(request, CognitionRequestState.FAILED_RETRYABLE,
                        "internal_capacity_wait_interrupted"));
                throw new IllegalStateException("metatron_cognition_capacity_wait_interrupted", interrupted);
            } finally {
                queued.decrementAndGet();
            }
            if (!acquired) {
                events.append(CognitionCapacityEvent.transition(request, CognitionRequestState.FAILED_RETRYABLE,
                        "internal_capacity_wait_timeout"));
                throw new IllegalStateException("metatron_cognition_capacity_wait_timeout");
            }
            events.append(CognitionCapacityEvent.capacityRecovered(request));
        }

        events.append(CognitionCapacityEvent.transition(request, CognitionRequestState.RUNNING,
                "internal_inference_started"));
        try {
            Response response = delegate.reason(request);
            events.append(CognitionCapacityEvent.transition(request, CognitionRequestState.SUCCEEDED,
                    "internal_inference_completed:endpoint=" + response.endpointId()));
            return response;
        } catch (RuntimeException failure) {
            CognitionRequestState state = retryable(failure)
                    ? CognitionRequestState.FAILED_RETRYABLE
                    : CognitionRequestState.FAILED_TERMINAL;
            events.append(CognitionCapacityEvent.transition(request, state,
                    "internal_inference_failure=" + failure.getClass().getSimpleName()));
            throw failure;
        } finally {
            permits.release();
        }
    }

    public int activeCount() { return maxConcurrent - permits.availablePermits(); }
    public int queuedCount() { return queued.get(); }

    static boolean retryable(Throwable failure) {
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            String message = String.valueOf(cursor.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (message.contains("429") || message.contains("capacity") || message.contains("resource_exhausted")
                    || message.contains("timeout") || message.contains("temporarily unavailable")
                    || message.contains("http_500") || message.contains("http_502")
                    || message.contains("http_503") || message.contains("http_504")
                    || cursor instanceof java.io.IOException) {
                return true;
            }
        }
        return false;
    }
}
