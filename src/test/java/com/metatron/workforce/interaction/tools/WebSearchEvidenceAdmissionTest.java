package com.metatron.workforce.interaction.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchEvidenceAdmissionTest {

    @Test
    void rejectsWrongCountryOfficeholderSourceBody() {
        String requirement = "Ai hiện tại đang là president Indonesia? Kiểm tra nguồn hiện tại rồi trả lời.";
        String wrongSourceBody = "List of presidents of the United States. The current president of the United States serves as head of state and government.";
        String matchingSourceBody = "The President of Indonesia is the head of state and head of government of the Republic of Indonesia.";

        assertFalse(WebSearchToolAdapter.materiallyRelevant(requirement, wrongSourceBody));
        assertTrue(WebSearchToolAdapter.materiallyRelevant(requirement, matchingSourceBody));
    }

    @Test
    void rejectsPythonEntityTextWithoutVersionFacet() {
        String requirement = "Phiên bản stable mới nhất của Python hiện tại là gì? Kiểm tra nguồn hiện tại rồi trả lời.";
        String entityOnlyBody = "Python is a high-level general-purpose programming language.";
        String factBearingBody = "The latest stable Python version is Python 3.14.7.";

        assertFalse(WebSearchToolAdapter.materiallyRelevant(requirement, entityOnlyBody));
        assertTrue(WebSearchToolAdapter.materiallyRelevant(requirement, factBearingBody));
    }
}
