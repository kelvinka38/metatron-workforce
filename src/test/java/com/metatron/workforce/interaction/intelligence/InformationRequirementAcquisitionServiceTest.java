package com.metatron.workforce.interaction.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void optimizesCurrentVersionSearchAroundTheDynamicSubject() {
        String vietnamese = InformationRequirementAcquisitionService.canonicalExternalQuery(
                "Kiểm tra phiên bản stable mới nhất của Python từ nguồn hiện tại và trả lời người dùng");
        String english = InformationRequirementAcquisitionService.canonicalExternalQuery(
                "What is the latest stable version of Visual Studio Code? Check current sources and answer the user.");

        assertEquals("Python latest stable version",
                InformationRequirementAcquisitionService.searchOptimizedExternalQuery(vietnamese));
        assertEquals("Visual Studio Code latest stable version",
                InformationRequirementAcquisitionService.searchOptimizedExternalQuery(english));
    }

    @Test
    void leavesNonVersionCurrentQueriesSemanticallyIntact() {
        String query = InformationRequirementAcquisitionService.canonicalExternalQuery(
                "Ai hiện đang là Tổng thống Indonesia? Kiểm tra nguồn hiện tại rồi trả lời.");

        assertEquals(query, InformationRequirementAcquisitionService.searchOptimizedExternalQuery(query));
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
