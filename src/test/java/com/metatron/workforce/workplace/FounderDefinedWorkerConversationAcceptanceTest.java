package com.metatron.workforce.workplace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.interaction.FounderDefinedWorkerFormationService;
import com.metatron.workforce.interaction.intelligence.WorkerIntelligenceService;
import com.metatron.workforce.operating.WorkerConstitutionService;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** W4 acceptance: a dynamically formed Worker is not only a record; it can be used through real Worker cognition. */
class FounderDefinedWorkerConversationAcceptanceTest {
    @Test
    void composerArtistConversationRunsAsCanonicalWorkerWithConstitutionGrounding() {
        WorkforceCoreService core = new WorkforceCoreService();
        WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
        WorkerConstitutionService constitution = WorkerConstitutionService.inMemory();
        RuntimeCapacityCoordinator runtimes = new RuntimeCapacityCoordinator(new RuntimeRegistry());
        FounderDefinedWorkerFormationService formation = new FounderDefinedWorkerFormationService(
                core, profiles, constitution, runtimes);

        formation.form("composer/artist",
                "Create for me a worker, role composer/artist. Người có tư duy âm nhạc của Michael Jackson.",
                Instant.parse("2026-09-11T06:30:00Z"));

        GitHubCanonicalInstitutionalRoleGrounding upstream = new GitHubCanonicalInstitutionalRoleGrounding(
                new ObjectMapper(), HttpClient.newHttpClient(), "", "owner/repository", "main");
        FounderAwareInstitutionalRoleGrounding grounding =
                new FounderAwareInstitutionalRoleGrounding(upstream, constitution);

        WorkerIntelligenceService intelligence = request -> {
            assertEquals("WORKER-COMPOSER-ARTIST", request.requester());
            assertEquals("worker.live.conversation", request.capability());
            assertTrue(request.context().contains("MATERIALIZED WORKER / POSITION OPERATING CONTRACT"));
            assertTrue(request.context().contains("FOUNDER_DEFINED_POSITION"));
            assertTrue(request.context().contains("composer/artist"));
            return new WorkerIntelligenceService.Response(
                    "worker-cognition-composer-test",
                    "Original work product: a syncopated pop concept built around call-and-response rhythm.",
                    List.of("worker-cognition:test", "cognitive-work-product:test"));
        };

        CanonicalWorkerConversationService conversation = new CanonicalWorkerConversationService(
                core, profiles, runtimes, intelligence, grounding, constitution);

        WorkerConversationGateway.Reply reply = conversation.converse(
                "WORKER-COMPOSER-ARTIST",
                "composer artist",
                "Create an original pop song concept",
                "Founder assigned a creative cognitive task");

        assertTrue(reply.text().startsWith("Original work product:"));
        assertFalse(reply.runtimeId().isBlank());
        assertTrue(reply.evidenceReferences().stream()
                .anyMatch(value -> value.equals("worker-cognition:test")));
        assertTrue(reply.evidenceReferences().stream()
                .anyMatch(value -> value.startsWith("position-contract:")));
    }
}
