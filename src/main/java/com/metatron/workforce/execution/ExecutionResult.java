package com.metatron.workforce.execution;

import java.time.Instant;

public record ExecutionResult(
        String executionId,
        ExecutionState state,
        String message,
        Instant completedAt
) {
    public boolean success() {
        return state == ExecutionState.COMPLETED;
    }

    public String capability() {
        int separator = message == null ? -1 : message.indexOf(";capability=");
        if (separator < 0) return "unknown";
        int start = separator + ";capability=".length();
        int end = message.indexOf(';', start);
        return end < 0 ? message.substring(start) : message.substring(start, end);
    }

    public String summary() {
        if (message == null) return "";
        int start = message.indexOf("summary=");
        if (start < 0) return message;
        start += "summary=".length();
        int end = message.indexOf(";evidence=", start);
        return end < 0 ? message.substring(start) : message.substring(start, end);
    }

    public String evidence() {
        if (message == null) return "";
        int start = message.indexOf("evidence=");
        return start < 0 ? message : message.substring(start + "evidence=".length());
    }
}
