package com.metatron.workforce.interaction.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalEvidenceResponseGuardTest {

    private final ExternalEvidenceResponseGuard guard = new ExternalEvidenceResponseGuard();

    @Test
    void rejectsProviderThatDeniesWebAfterEvidenceWasRetrieved() {
        assertThrows(IllegalStateException.class,
                () -> guard.validate("Hiện tại tôi không có dữ liệu thời gian thực về giá vàng hôm nay."));
        assertThrows(IllegalStateException.class,
                () -> guard.validate("I cannot access the web right now."));
    }

    @Test
    void acceptsGroundedAnswer() {
        assertDoesNotThrow(() -> guard.validate(
                "Workforce đã truy xuất các nguồn hiện tại; dữ liệu nguồn cho thấy mức giá được công bố như sau."));
    }
}
