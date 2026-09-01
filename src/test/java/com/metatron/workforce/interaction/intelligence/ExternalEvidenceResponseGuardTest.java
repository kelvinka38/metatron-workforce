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
    void rejectsProviderThatRefusesCurrentValueAfterEvidenceWasRetrieved() {
        assertThrows(IllegalStateException.class,
                () -> guard.validate("Hệ thống hiện tại không duy trì nguồn cấp dữ liệu tự động nên tôi không thể cung cấp trực tiếp con số chính xác."));
        assertThrows(IllegalStateException.class,
                () -> guard.validate("I cannot provide the current value even though sources were supplied."));
    }

    @Test
    void acceptsGroundedAnswer() {
        assertDoesNotThrow(() -> guard.validate(
                "Giá vàng SJC hiện tại theo nguồn đã truy xuất là 129.000.000 đồng/lượng."));
    }
}
