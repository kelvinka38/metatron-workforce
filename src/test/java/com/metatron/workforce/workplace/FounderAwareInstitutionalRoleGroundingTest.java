package com.metatron.workforce.workplace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.core.WorkforceCoreService;
import com.metatron.workforce.interaction.FounderDefinedWorkerFormationService;
import com.metatron.workforce.operating.WorkerConstitutionService;
import com.metatron.workforce.runtime.RuntimeCapacityCoordinator;
import com.metatron.workforce.runtime.RuntimeRegistry;
import com.metatron.workforce.runtime.WorkerRuntimeProfileBindingService;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class FounderAwareInstitutionalRoleGroundingTest {
    @Test
    void founderDefinedComposerUsesDurablePositionContractWhenNoInstitutionDomainBindingExists() {
        WorkforceCoreService core = new WorkforceCoreService();
        WorkerRuntimeProfileBindingService profiles = WorkerRuntimeProfileBindingService.inMemory();
        WorkerConstitutionService constitution = WorkerConstitutionService.inMemory();
        FounderDefinedWorkerFormationService formation = new FounderDefinedWorkerFormationService(
                core, profiles, constitution,
                new RuntimeCapacityCoordinator(new RuntimeRegistry()));

        formation.form("composer/artist",
                "Create for me a worker, role composer/artist. Người có tư duy âm nhạc của Michael Jackson.",
                Instant.parse("2026-09-11T06:30:00Z"));

        GitHubCanonicalInstitutionalRoleGrounding upstream = new GitHubCanonicalInstitutionalRoleGrounding(
                new ObjectMapper(), HttpClient.newHttpClient(), "", "owner/repository", "main");
        FounderAwareInstitutionalRoleGrounding grounding =
                new FounderAwareInstitutionalRoleGrounding(upstream, constitution);

        InstitutionalRoleGrounding.Grounding result = grounding.resolve(
                "role:founder-defined:composer-artist",
                "position:founder-defined:composer-artist",
                "composer artist",
                "Create an original pop song concept");

        assertTrue(result.available());
        assertEquals("FOUNDER_DEFINED_POSITION", result.domain());
        assertTrue(result.context().contains("CANONICAL FOUNDER-DEFINED POSITION CONTRACT"));
        assertTrue(result.context().contains("composer/artist"));
        assertTrue(result.evidenceReferences().stream().anyMatch(value -> value.startsWith("position-contract:")));
    }
}
