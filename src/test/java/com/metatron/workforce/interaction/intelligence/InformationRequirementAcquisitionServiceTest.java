package com.metatron.workforce.interaction.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InformationRequirementAcquisitionServiceTest {

    @Test
    void canonicalizesVietnameseIncumbentOfficeHolderWithoutInstructionNoise() {
        String query = InformationRequirementAcquisitionService.canonicalExternalQuery(
                "Xác định Tổng thống đương nhiệm của Indonesia");

        assertEquals("president current of Indonesia", query);
        assertEquals(query, InformationRequirementAcquisitionService.searchOptimizedExternalQuery(query));
    }

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
        String productionParaphrase = InformationRequirementAcquisitionService.canonicalExternalQuery(
                "Kiểm tra phiên bản ổn định (stable) mới nhất hiện tại của Python bằng cách tra cứu nguồn hiện tại và trả lời kèm theo nguồn.");
        String productionRequirement = InformationRequirementAcquisitionService.canonicalExternalQuery(
                "Kiểm tra và cung cấp thông tin về phiên bản stable mới nhất của Python hiện tại từ nguồn chính thức.");

        assertEquals("Python latest stable version",
                InformationRequirementAcquisitionService.searchOptimizedExternalQuery(vietnamese));
        assertEquals("Visual Studio Code latest stable version",
                InformationRequirementAcquisitionService.searchOptimizedExternalQuery(english));
        assertEquals("Python latest stable version",
                InformationRequirementAcquisitionService.searchOptimizedExternalQuery(productionParaphrase));
        assertEquals("Python latest stable version",
                InformationRequirementAcquisitionService.searchOptimizedExternalQuery(productionRequirement));
        String failedProductionObjective = InformationRequirementAcquisitionService.canonicalExternalQuery(
                "Xác định phiên bản ổn định (stable) mới nhất của Python hiện tại dựa trên nguồn dữ liệu hiện tại");

        assertEquals("Python latest stable version",
                InformationRequirementAcquisitionService.searchOptimizedExternalQuery(failedProductionObjective));
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
