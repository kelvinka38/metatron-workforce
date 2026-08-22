package com.metatron.workforce.phase5;

import java.time.Instant;
import java.util.Objects;

public record WorkingTimeWindow(
        Instant start,
        Instant end,
        int availableMinutes) {

    public WorkingTimeWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
        if (availableMinutes < 0) {
            throw new IllegalArgumentException("availableMinutes must be non-negative");
        }
    }

    public boolean contains(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(start) && instant.isBefore(end);
    }
}
