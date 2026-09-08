package com.metatron.workforce.interaction.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetatronIntelligenceResponderDepthBannerTest {

    @Test
    void stripsInternalDepthBannerBeforeCasePersistence() {
        assertEquals(
                "Giá hiện tại là 1 USD = 26,034 VND.",
                MetatronIntelligenceResponder.stripInternalDepthBanner(
                        "🔬 **DEEP · METATRON**\n\nGiá hiện tại là 1 USD = 26,034 VND."));
    }

    @Test
    void keepsNormalAnswerUntouched() {
        assertEquals(
                "Phân tích sâu hơn khi cần.",
                MetatronIntelligenceResponder.stripInternalDepthBanner("Phân tích sâu hơn khi cần."));
    }
}
