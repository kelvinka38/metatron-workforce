package com.metatron.workforce.interaction.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GoogleLlmProviderClientRetryPolicyTest {
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
    void backoffCoversObservedSubSecondRetryWindowWithoutUnboundedDelay() {
        assertEquals(300L, GoogleLlmProviderClient.retryDelayMillis(1));
        assertEquals(750L, GoogleLlmProviderClient.retryDelayMillis(2));
        assertEquals(1500L, GoogleLlmProviderClient.retryDelayMillis(3));
    }
}
