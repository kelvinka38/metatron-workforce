package com.metatron.workforce.interaction.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InformationRequirementAcquisitionServiceTest {

    @Test
    void canonicalizesVietnameseFreshSoftwareVersionQueryForCrossLanguageEvidence() {
        String query = InformationRequirementAcquisitionService.canonicalExternalQuery(
                "Phiên bản stable mới nhất của Python hiện tại là gì? Kiểm tra nguồn hiện tại rồi trả lời.");

        assertTrue(query.contains("version"));
        assertTrue(query.contains("stable"));
        assertTrue(query.contains("latest"));
        assertTrue(query.contains("Python"));
        assertTrue(query.contains("current"));
        assertTrue(query.contains("check"));
        assertTrue(query.contains("answer"));
    }

    @Test
    void canonicalizesVietnameseCurrentOfficeHolderQueryForCrossLanguageEvidence() {
        String query = InformationRequirementAcquisitionService.canonicalExternalQuery(
                "Ai hiện đang là Tổng thống Indonesia? Kiểm tra nguồn hiện tại rồi trả lời.");

        assertTrue(query.contains("president Indonesia"));
        assertTrue(query.contains("current"));
        assertTrue(query.contains("check"));
        assertTrue(query.contains("answer"));
    }
}
