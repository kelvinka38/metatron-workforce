package com.metatron.workforce.execution;

import java.time.Instant;
import java.util.Objects;

/** Evidence that required public guidance was actually resolved and read. */
public record GuidanceReceipt(
        String guidanceId,
        String publicResourcePath,
        String sourceRef,
        String contentSha256,
        Instant readAt
) {
    public GuidanceReceipt {
        Objects.requireNonNull(guidanceId, "guidanceId");
        Objects.requireNonNull(publicResourcePath, "publicResourcePath");
        Objects.requireNonNull(sourceRef, "sourceRef");
        Objects.requireNonNull(contentSha256, "contentSha256");
        Objects.requireNonNull(readAt, "readAt");
        if (contentSha256.isBlank()) throw new IllegalArgumentException("contentSha256 must not be blank");
    }
}
