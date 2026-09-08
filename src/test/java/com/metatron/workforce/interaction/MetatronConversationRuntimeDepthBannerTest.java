package com.metatron.workforce.interaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetatronConversationRuntimeDepthBannerTest {

    @Test
    void stripsInternalDeepBannerFromChatAnswer() {
        assertEquals(
                "Tỷ giá hiện tại là 1 USD = 26,034 VND.",
                MetatronConversationRuntime.stripInternalDepthBanner(
                        "🔬 **DEEP · METATRON**\n\nTỷ giá hiện tại là 1 USD = 26,034 VND."));
    }

    @Test
    void preservesOrdinaryAnswerContent() {
        assertEquals(
                "Phân tích sâu hơn về rủi ro này.",
                MetatronConversationRuntime.stripInternalDepthBanner(
                        "Phân tích sâu hơn về rủi ro này."));
    }
}
