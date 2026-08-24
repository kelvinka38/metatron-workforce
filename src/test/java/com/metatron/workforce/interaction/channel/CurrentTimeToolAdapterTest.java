package com.metatron.workforce.interaction.channel;

import com.metatron.workforce.interaction.tools.CurrentTimeToolAdapter;
import com.metatron.workforce.interaction.tools.ToolRequest;
import com.metatron.workforce.interaction.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentTimeToolAdapterTest {
    @Test
    void readsAuthoritativeVietnamDateAndDay() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-25T00:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));
        CurrentTimeToolAdapter adapter = new CurrentTimeToolAdapter(clock);
        ToolRequest request = new ToolRequest(
                "test-time-1", "test", CurrentTimeToolAdapter.CAPABILITY,
                "runtime:workforce", "read", "current date and time", List.of("test"));

        ToolResult result = adapter.execute(request);

        assertTrue(result.success());
        assertTrue(result.output().contains("current_date=25/08/2026"));
        assertTrue(result.output().contains("day_of_week=TUESDAY"));
        assertTrue(result.output().contains("timezone=Asia/Ho_Chi_Minh"));
    }
}
