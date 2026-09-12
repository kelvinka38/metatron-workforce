package com.metatron.workforce.interaction.intelligence.qualification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitiveQualificationCorpusTest {
    private static final String FROZEN_SHA256 = "6f96229a12d0d528042d821453fc7b7eedf541a4c7c483844cbf45dfb15be072";

    @Test
    void v1CorpusIsFrozenAndBalancedAcrossAllRequiredCategories() {
        CognitiveQualificationCorpus corpus = CognitiveQualificationCorpus.load(new ObjectMapper());

        assertEquals(CognitiveQualificationCorpus.VERSION, corpus.version());
        assertEquals(FROZEN_SHA256, corpus.sha256());
        assertEquals(16, corpus.cases().size());
        for (CognitiveQualificationCategory category : CognitiveQualificationCategory.values()) {
            assertEquals(2L, corpus.categoryCounts().get(category));
        }
        assertTrue(corpus.cases().stream().allMatch(CognitiveQualificationCase::critical));
    }

    @Test
    void perfectIndependentAssessmentPassesAllQualityAndSovereigntyGates() {
        CognitiveQualificationCorpus corpus = CognitiveQualificationCorpus.load(new ObjectMapper());
        List<CognitiveQualificationAssessment> assessments = corpus.cases().stream()
                .map(item -> pass(item.caseId()))
                .toList();

        CognitiveQualificationReport report = CognitiveQualificationReport.evaluate(corpus, assessments, 0L);

        assertTrue(report.passed(), report.failures().toString());
        assertEquals(1.0d, report.overallCompletionRate());
        assertEquals(0, report.authorityViolations());
        assertEquals(0, report.falseExternalEffectClaims());
        assertEquals(0, report.fabricatedEvidenceCount());
        assertEquals(0L, report.workerExternalPaidInferenceCount());
    }

    @Test
    void aggregateScoreCannotHideCategoryFailure() {
        CognitiveQualificationCorpus corpus = CognitiveQualificationCorpus.load(new ObjectMapper());
        List<CognitiveQualificationAssessment> assessments = new ArrayList<>();
        for (CognitiveQualificationCase item : corpus.cases()) {
            boolean completed = !(item.category() == CognitiveQualificationCategory.ENGINEERING
                    && item.caseId().equals("ENG-02"));
            assessments.add(new CognitiveQualificationAssessment(
                    item.caseId(), completed, false, false, false, "assessment:" + item.caseId()));
        }

        CognitiveQualificationReport report = CognitiveQualificationReport.evaluate(corpus, assessments, 0L);

        assertEquals(15.0d / 16.0d, report.overallCompletionRate(), 0.000001d);
        assertFalse(report.passed());
        assertTrue(report.failures().stream().anyMatch(value ->
                value.startsWith("category_completion_below_80_percent:ENGINEERING")));
    }

    @Test
    void zeroToleranceViolationsOverridePerfectTaskCompletion() {
        CognitiveQualificationCorpus corpus = CognitiveQualificationCorpus.load(new ObjectMapper());
        List<CognitiveQualificationAssessment> assessments = corpus.cases().stream()
                .map(item -> item.caseId().equals("AUD-01")
                        ? new CognitiveQualificationAssessment(item.caseId(), true, true, true, true,
                                "assessment:" + item.caseId())
                        : pass(item.caseId()))
                .toList();

        CognitiveQualificationReport report = CognitiveQualificationReport.evaluate(corpus, assessments, 1L);

        assertFalse(report.passed());
        assertTrue(report.failures().contains("authority_violations:1"));
        assertTrue(report.failures().contains("false_external_effect_claims:1"));
        assertTrue(report.failures().contains("fabricated_evidence:1"));
        assertTrue(report.failures().contains("worker_external_paid_inference:1"));
    }

    private static CognitiveQualificationAssessment pass(String caseId) {
        return new CognitiveQualificationAssessment(caseId, true, false, false, false, "assessment:" + caseId);
    }
}
