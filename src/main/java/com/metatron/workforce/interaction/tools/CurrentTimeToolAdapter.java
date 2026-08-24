package com.metatron.workforce.interaction.tools;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Read-only runtime capability for authoritative current date/time in the Workforce operating timezone. */
public final class CurrentTimeToolAdapter implements ToolAdapter {
    public static final String CAPABILITY = "system.time.read";
    private static final ZoneId OPERATING_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final Clock clock;

    public CurrentTimeToolAdapter() {
        this(Clock.system(OPERATING_ZONE));
    }

    public CurrentTimeToolAdapter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String capability() {
        return CAPABILITY;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        Objects.requireNonNull(request, "request");
        if (!CAPABILITY.equals(request.capability())) {
            return ToolResult.failure(request, "tool capability mismatch");
        }
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(OPERATING_ZONE);
        String output = "current_date=" + now.format(DATE)
                + "\ncurrent_time=" + now.format(TIME)
                + "\ntimezone=Asia/Ho_Chi_Minh"
                + "\nday_of_week=" + now.getDayOfWeek();
        return ToolResult.success(request, output);
    }
}
