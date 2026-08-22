package com.metatron.workforce.phase9;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class Phase9D2ExecutableBoundaryAcceptanceTest {

    private static final Instant NOW = Instant.parse("2026-08-22T00:00:00Z");
    private final Phase9BoundaryService service = new Phase9BoundaryService();
    private final BoundaryProvenance provenance =
            new BoundaryProvenance("worker-1", "evidence-1", NOW);

    private BoundaryRequest request(String contractId) {
        return new BoundaryRequest(
                "request-1", contractId, "worker-1", "authority-1", "input", provenance);
    }

    private BoundaryDecision success() {
        return new BoundaryDecision(
                BoundaryStatus.SUCCESS, "authority-1", "approved", provenance);
    }

    private BoundaryDecision failure() {
        return new BoundaryDecision(
                BoundaryStatus.FAILURE, "authority-1", "external boundary failed", provenance);
    }

    @Test
    void allSixCanonicalBoundariesAreExecutable() {
        assertEquals(6, Phase9IntegrationRegistry.canonicalContracts().size());

        assertEquals(BoundaryStatus.SUCCESS,
                AuthorizationBoundary.apply(service, request("INT-WORKFORCE-AUTHORIZATION"), success(), "decision")
                        .status());
        assertEquals(BoundaryStatus.SUCCESS,
                GatewayBoundary.apply(service, request("INT-WORKFORCE-GATEWAY"), success(), "gateway-result")
                        .status());
        assertEquals(BoundaryStatus.SUCCESS,
                ExecutionBoundary.apply(service, request("INT-WORKFORCE-EXECUTION"), success(), "execution-result")
                        .status());
        assertEquals(BoundaryStatus.SUCCESS,
                ObservationBoundary.apply(service, request("INT-WORKFORCE-OBSERVATION"), success(), "observation")
                        .status());
        assertEquals(BoundaryStatus.SUCCESS,
                KnowledgeBoundary.apply(service, request("INT-WORKFORCE-KNOWLEDGE"), success(), "knowledge-ref")
                        .status());
        assertEquals(BoundaryStatus.SUCCESS,
                EconomyBoundary.apply(service, request("INT-WORKFORCE-ECONOMY"), success(), "economic-evidence")
                        .status());
    }

    @Test
    void authorizationFailureDeniesExecution() {
        BoundaryResult result = AuthorizationBoundary.apply(
                service,
                request("INT-WORKFORCE-AUTHORIZATION"),
                failure(),
                null);

        assertEquals(BoundaryStatus.DENIED, result.status());
    }

    @Test
    void gatewayFailureFailsClosed() {
        BoundaryResult result = GatewayBoundary.apply(
                service,
                request("INT-WORKFORCE-GATEWAY"),
                failure(),
                null);

        assertEquals(BoundaryStatus.REJECTED, result.status());
    }

    @Test
    void executionFailureCannotBecomeSuccess() {
        BoundaryResult result = ExecutionBoundary.apply(
                service,
                request("INT-WORKFORCE-EXECUTION"),
                failure(),
                null);

        assertEquals(BoundaryStatus.FAILURE, result.status());
        assertFalse(result.succeeded());
    }

    @Test
    void observationFailureRemainsUnavailable() {
        BoundaryResult result = ObservationBoundary.apply(
                service,
                request("INT-WORKFORCE-OBSERVATION"),
                failure(),
                null);

        assertEquals(BoundaryStatus.UNAVAILABLE, result.status());
    }

    @Test
    void unvalidatedKnowledgeCannotBeAdmitted() {
        BoundaryResult result = KnowledgeBoundary.apply(
                service,
                request("INT-WORKFORCE-KNOWLEDGE"),
                failure(),
                null);

        assertEquals(BoundaryStatus.REJECTED, result.status());
    }

    @Test
    void economyEmitsEvidenceWithoutBecomingAccountingAuthority() {
        BoundaryResult result = EconomyBoundary.apply(
                service,
                request("INT-WORKFORCE-ECONOMY"),
                success(),
                "worker/time/resource/cost evidence");

        assertEquals(BoundaryStatus.SUCCESS, result.status());
        assertEquals("worker/time/resource/cost evidence", result.output());
        assertEquals("authority-1", result.authorityReference());
        assertTrue(service.contractFor("INT-WORKFORCE-ECONOMY")
                .authorityBoundary()
                .toLowerCase()
                .contains("external"));
    }

    @Test
    void provenanceIsPreservedAcrossRequestDecisionAndResult() {
        BoundaryRequest request = request("INT-WORKFORCE-EXECUTION");
        BoundaryDecision decision = success();
        BoundaryResult result = ExecutionBoundary.apply(service, request, decision, "done");

        assertSame(provenance, request.provenance());
        assertSame(provenance, decision.provenance());
        assertSame(provenance, result.provenance());
    }

    @Test
    void unknownContractIsRejected() {
        BoundaryRequest request = request("INT-WORKFORCE-UNKNOWN");
        assertThrows(IllegalArgumentException.class,
                () -> service.execute(request, success(), "output", BoundaryStatus.FAILURE));
    }

    @Test
    void successfulBoundaryRequiresOutputEvidence() {
        assertThrows(IllegalArgumentException.class,
                () -> service.execute(
                        request("INT-WORKFORCE-GATEWAY"), success(), null, BoundaryStatus.REJECTED));
    }

    @Test
    void provenanceIsMandatoryAtModelBoundary() {
        assertThrows(NullPointerException.class,
                () -> new BoundaryRequest(
                        "request-1",
                        "INT-WORKFORCE-GATEWAY",
                        "worker-1",
                        "authority-1",
                        "input",
                        null));

        assertThrows(NullPointerException.class,
                () -> new BoundaryDecision(
                        BoundaryStatus.SUCCESS,
                        "authority-1",
                        "approved",
                        null));
    }

    @Test
    void externalAuthorityReferenceIsPreserved() {
        BoundaryResult result = AuthorizationBoundary.apply(
                service,
                request("INT-WORKFORCE-AUTHORIZATION"),
                success(),
                "authorization-result");

        assertEquals("authority-1", result.authorityReference());
        assertNotEquals("WORKFORCE", result.authorityReference());
    }
}
