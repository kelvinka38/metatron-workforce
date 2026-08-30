package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.interaction.llm.LlmProvider;
import com.metatron.workforce.phase8.WorkforcePracticeCandidate;
import com.metatron.workforce.phase9.BoundaryProvenance;
import com.metatron.workforce.phase9.BoundaryResult;
import com.metatron.workforce.phase9.BoundaryStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class IntelligenceCaseLifecycleServiceTest {
    @TempDir
    Path tempDir;

    private final Instant now = Instant.parse("2026-08-30T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @Test
    void persistsInstitutionalLifecycleByStableCaseIdentityAcrossRestart() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PersistentIntelligenceCaseStore store = new PersistentIntelligenceCaseStore(tempDir, mapper);
        IntelligenceCase opened = store.openOrUpdate(
                "conversation:case-lifecycle", "human:primary", normalized());
        String caseId = opened.caseId();

        IntelligenceCaseLifecycleService lifecycle = new IntelligenceCaseLifecycleService(store, clock);
        BoundaryResult authorization = success(
                InstitutionalIntelligenceReferenceBridge.AUTHORIZATION_CONTRACT,
                "authorization:auth-1", "auth-evidence:1");
        BoundaryResult gateway = success(
                InstitutionalIntelligenceReferenceBridge.GATEWAY_CONTRACT,
                "authority:gateway-1", "gateway-evidence:1");

        IntelligenceCase authorized = lifecycle.recordAuthorization(
                caseId, authorization, "authorization:auth-1");
        assertTrue(authorized.externalInstitutionalReferences().contains("authorization:auth-1"));

        IntelligenceCase gatewayLinked = lifecycle.recordGateway(
                caseId, gateway, "gateway:request-1", List.of("gateway-evidence:1"));
        assertTrue(gatewayLinked.externalInstitutionalReferences().contains("gateway:request-1"));
        assertTrue(gatewayLinked.evidenceReferences().contains("gateway-evidence:1"));

        var admitted = lifecycle.admitExecution(
                caseId, "worker:director", "assignment-1", "work-package-1", "execution-1",
                "authorization:auth-1", "gateway:request-1", authorization, gateway);
        assertEquals("execution-1", admitted.handoff().executionId());
        assertTrue(admitted.intelligenceCase().externalInstitutionalReferences().contains("assignment:assignment-1"));

        BoundaryResult execution = success(
                InstitutionalIntelligenceReferenceBridge.EXECUTION_CONTRACT,
                "authority:execution-1", "execution-evidence:1");
        lifecycle.recordExecution(
                caseId, execution, "execution:execution-1", List.of("execution-evidence:1"));

        BoundaryResult observation = success(
                InstitutionalIntelligenceReferenceBridge.OBSERVATION_CONTRACT,
                "authority:observation-1", "observation-evidence:1");
        var feedback = lifecycle.recordObservedOutcome(
                caseId, observation, "execution-1", "observation-1", "outcome-1",
                "observation-evidence:1", "expected=10 actual=9", now);
        assertEquals("execution-1", feedback.learningEvidence().executionId());
        assertEquals("outcome-1", feedback.learningEvidence().outcomeId());
        assertEquals(IntelligenceCaseStatus.REASSESSMENT, feedback.intelligenceCase().status());
        assertTrue(feedback.intelligenceCase().externalInstitutionalReferences().contains("outcome:outcome-1"));

        WorkforcePracticeCandidate candidate = new WorkforcePracticeCandidate(
                "practice-1", List.of("worker-evidence:1", "worker-evidence:2"),
                "validated operating pattern", "validation-evidence:1", true);
        var admissionRequest = lifecycle.prepareKnowledgeAdmission(
                caseId, candidate, "worker:director", "authority:knowledge-submit-1");
        assertEquals(InstitutionalIntelligenceReferenceBridge.KNOWLEDGE_CONTRACT,
                admissionRequest.boundaryRequest().contractId());

        BoundaryResult knowledge = success(
                InstitutionalIntelligenceReferenceBridge.KNOWLEDGE_CONTRACT,
                "authority:knowledge-submit-1", "knowledge-admission-evidence:1");
        lifecycle.recordKnowledgeAdmission(caseId, knowledge, "knowledge:item-1");

        // New service/store instance simulates a process/container restart.
        IntelligenceCaseLifecycleService restarted = new IntelligenceCaseLifecycleService(
                new PersistentIntelligenceCaseStore(tempDir, mapper), clock);
        IntelligenceCase recovered = restarted.requireCase(caseId);

        assertEquals(caseId, recovered.caseId());
        assertTrue(recovered.externalInstitutionalReferences().contains("execution:execution-1"));
        assertTrue(recovered.externalInstitutionalReferences().contains("outcome:outcome-1"));
        assertTrue(recovered.externalInstitutionalReferences().contains("knowledge:item-1"));
        assertTrue(recovered.evidenceReferences().contains("learning-evidence:" + feedback.learningEvidence().evidenceId()));
    }

    @Test
    void deniedAuthorizationCannotCreateExecutionHandoff() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PersistentIntelligenceCaseStore store = new PersistentIntelligenceCaseStore(tempDir, mapper);
        IntelligenceCase opened = store.openOrUpdate("conversation:denied", "human:primary", normalized());
        IntelligenceCaseLifecycleService lifecycle = new IntelligenceCaseLifecycleService(store, clock);

        BoundaryResult denied = new BoundaryResult(
                "request-auth-denied", InstitutionalIntelligenceReferenceBridge.AUTHORIZATION_CONTRACT,
                BoundaryStatus.DENIED, null, "authorization:denied", provenance("auth-denied"));
        BoundaryResult gateway = success(
                InstitutionalIntelligenceReferenceBridge.GATEWAY_CONTRACT,
                "authority:gateway-1", "gateway-evidence:1");

        assertThrows(SecurityException.class, () -> lifecycle.admitExecution(
                opened.caseId(), "worker:director", "assignment-1", "work-package-1", "execution-1",
                "authorization:denied", "gateway:request-1", denied, gateway));
        assertTrue(store.findByCaseId(opened.caseId()).orElseThrow().externalInstitutionalReferences().isEmpty());
    }

    private BoundaryResult success(String contract, String authorityReference, String evidenceReference) {
        return new BoundaryResult(
                "request-" + contract, contract, BoundaryStatus.SUCCESS,
                "external-domain-result", authorityReference, provenance(evidenceReference));
    }

    private BoundaryProvenance provenance(String evidenceReference) {
        return new BoundaryProvenance("authoritative-domain-test", evidenceReference, now);
    }

    private static NormalizedRequest normalized() {
        return new NormalizedRequest(
                "improve institutional outcome", "operations", List.of(), IntelligenceDepth.DEEP,
                "governed recommendation", List.of(), List.of(), "current", "",
                IntelligenceMode.REASONING, CollaborationMode.SINGLE,
                List.of(AnalyticalProtocolType.IMPROVEMENT), DeterministicCapability.NONE, false,
                null, LlmProvider.GOOGLE, "");
    }
}
