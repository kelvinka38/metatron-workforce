package com.metatron.workforce.interaction.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GoogleLlmProviderClientRetryPolicyTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void retriesRateLimitAndTransientServerFailures() {
        assertTrue(GoogleLlmProviderClient.isRetryableStatus(429));
        assertTrue(GoogleLlmProviderClient.isRetryableStatus(500));
        assertTrue(GoogleLlmProviderClient.isRetryableStatus(502));
        assertTrue(GoogleLlmProviderClient.isRetryableStatus(503));
        assertTrue(GoogleLlmProviderClient.isRetryableStatus(504));
    }

    @Test
    void doesNotRetryPermanentClientFailures() {
        assertFalse(GoogleLlmProviderClient.isRetryableStatus(400));
        assertFalse(GoogleLlmProviderClient.isRetryableStatus(401));
        assertFalse(GoogleLlmProviderClient.isRetryableStatus(403));
        assertFalse(GoogleLlmProviderClient.isRetryableStatus(404));
    }

    @Test
    void honorsStructuredGoogleRetryInfo() throws Exception {
        JsonNode root = mapper.readTree("""
                {"error":{"details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"56.571776792s"}]}}
                """);

        assertEquals(56_822L, GoogleLlmProviderClient.retryDelayMillis(1, root, Optional.empty()));
    }

    @Test
    void honorsHumanReadableGoogleRetryHintWhenStructuredDetailIsAbsent() throws Exception {
        JsonNode root = mapper.readTree("""
                {"error":{"message":"Quota exceeded. Please retry in 212.581651ms."}}
                """);

        assertEquals(463L, GoogleLlmProviderClient.retryDelayMillis(1, root, Optional.empty()));
    }

    @Test
    void retryAfterHeaderTakesPrecedenceAndIsBounded() throws Exception {
        JsonNode root = mapper.readTree("{\"error\":{\"message\":\"Please retry in 1s\"}}");

        assertEquals(60_250L, GoogleLlmProviderClient.retryDelayMillis(1, root, Optional.of("60")));
        assertEquals(65_000L, GoogleLlmProviderClient.retryDelayMillis(1, root, Optional.of("120")));
    }

    @Test
    void fallsBackToShortBackoffWhenProviderSuppliesNoWindow() throws Exception {
        JsonNode root = mapper.readTree("{\"error\":{\"message\":\"temporarily unavailable\"}}");

        assertEquals(550L, GoogleLlmProviderClient.retryDelayMillis(1, root, Optional.empty()));
        assertEquals(1_000L, GoogleLlmProviderClient.retryDelayMillis(2, root, Optional.empty()));
    }
}
