package com.metatron.workforce.phase5;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record WorkingTimePolicy(
        String workerId,
        ZoneId zone,
        Instant effectiveAt,
        Instant expiresAt,
        Map<DayOfWeek, List<Shift>> shifts,
        Map<DayOfWeek, List<BreakWindow>> breaks,
        List<Unavailability> unavailability) {

    public WorkingTimePolicy {
        requireText("workerId", workerId);
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(effectiveAt, "effectiveAt");
        if (expiresAt != null && !expiresAt.isAfter(effectiveAt)) {
            throw new IllegalArgumentException("expiresAt must be after effectiveAt");
        }
        shifts = copy(shifts);
        breaks = copy(breaks);
        unavailability = List.copyOf(Objects.requireNonNull(unavailability, "unavailability"));
    }

    public AvailabilityStatus resolve(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        if (instant.isBefore(effectiveAt) || (expiresAt != null && !instant.isBefore(expiresAt))) {
            return AvailabilityStatus.UNKNOWN;
        }
        for (Unavailability exception : unavailability) {
            if (exception.contains(instant)) {
                return AvailabilityStatus.UNAVAILABLE;
            }
        }
        ZonedDateTime local = instant.atZone(zone);
        if (!isInsideShift(local)) {
            return AvailabilityStatus.UNAVAILABLE;
        }
        if (isInsideBreak(local)) {
            return AvailabilityStatus.UNAVAILABLE;
        }
        return AvailabilityStatus.AVAILABLE;
    }

    private boolean isInsideShift(ZonedDateTime value) {
        DayOfWeek day = value.getDayOfWeek();
        LocalTime time = value.toLocalTime();
        if (contains(shifts.getOrDefault(day, List.of()), time, false)) return true;
        DayOfWeek previous = day.minus(1);
        return contains(shifts.getOrDefault(previous, List.of()), time, true);
    }

    private boolean isInsideBreak(ZonedDateTime value) {
        return contains(breaks.getOrDefault(value.getDayOfWeek(), List.of()), value.toLocalTime(), false)
                || contains(breaks.getOrDefault(value.getDayOfWeek().minus(1), List.of()), value.toLocalTime(), true);
    }

    private static boolean contains(List<? extends Window> windows, LocalTime time, boolean overnightOnly) {
        for (Window window : windows) {
            boolean overnight = window.end().isBefore(window.start()) || window.end().equals(window.start());
            if (overnightOnly && !overnight) continue;
            if (!overnightOnly && overnight) {
                if (!time.isBefore(window.start()) || time.isBefore(window.end())) return true;
                continue;
            }
            if (!time.isBefore(window.start()) && time.isBefore(window.end())) return true;
        }
        return false;
    }

    private static <K, V> Map<K, List<V>> copy(Map<K, List<V>> input) {
        Objects.requireNonNull(input, "map");
        return input.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }

    private static void requireText(String name, String value) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    public record Shift(LocalTime start, LocalTime end) implements Window {
        public Shift {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (start.equals(end)) throw new IllegalArgumentException("shift cannot be zero-length");
        }
    }

    public record BreakWindow(LocalTime start, LocalTime end) implements Window {
        public BreakWindow {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (start.equals(end)) throw new IllegalArgumentException("break cannot be zero-length");
        }
    }

    public record Unavailability(Instant start, Instant end, String reason) {
        public Unavailability {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            requireText("reason", reason);
            if (!end.isAfter(start)) throw new IllegalArgumentException("end must be after start");
        }

        boolean contains(Instant instant) {
            return !instant.isBefore(start) && instant.isBefore(end);
        }
    }

    private interface Window {
        LocalTime start();
        LocalTime end();
    }
}
