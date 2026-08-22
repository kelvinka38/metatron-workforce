package com.metatron.workforce.phase9;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Executes the semantic rules declared by the Phase 9 integration contracts.
 *
 * This service owns boundary enforcement only. It does not own authorization,
 * gateway, execution, observation, knowledge, or accounting infrastructure.
 */
public final class Phase9BoundaryService {

    private final Map<String, IntegrationContract> contracts;

    public Phase9BoundaryService() {
        contracts = Phase9IntegrationRegistry.canonicalContracts().stream()
                .collect(Collectors.toUnmodifiableMap(IntegrationContract::id, Function.identity()));
    }

    public BoundaryResult authorize(BoundaryRequest request, BoundaryDecision decision, Object output) {
        return execute(request, decision, output, BoundaryStatus.DENIED);
    }

    public BoundaryResult gateway(BoundaryRequest request, BoundaryDecision decision, Object output) {
        return execute(request, decision, output, BoundaryStatus.REJECTED);
    }

    public BoundaryResult execution(BoundaryRequest request, BoundaryDecision decision, Object output) {
        return execute(request, decision, output, BoundaryStatus.FAILURE);
    }

    public BoundaryResult observation(BoundaryRequest request, BoundaryDecision decision, Object output) {
        return execute(request, decision, output, BoundaryStatus.UNAVAILABLE);
    }

    public BoundaryResult knowledge(BoundaryRequest request, BoundaryDecision decision, Object output) {
        return execute(request, decision, output, BoundaryStatus.REJECTED);
    }

    public BoundaryResult economy(BoundaryRequest request, BoundaryDecision decision, Object output) {
        return execute(request, decision, output, BoundaryStatus.FAILURE);
    }

    public BoundaryResult execute(
            BoundaryRequest request,
            BoundaryDecision decision,
            Object output,
            BoundaryStatus failureStatus) {

        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(failureStatus, "failureStatus");

        IntegrationContract contract = contractFor(request.contractId());
        validateRequestAgainstContract(request, contract);
        validateDecision(decision);

        BoundaryStatus status = decision.status() == BoundaryStatus.SUCCESS
                ? BoundaryStatus.SUCCESS
                : failureStatus;

        if (status == BoundaryStatus.SUCCESS && output == null) {
            throw new IllegalArgumentException("successful boundary result requires output evidence");
        }

        return new BoundaryResult(
                request.requestId(),
                contract.id(),
                status,
                output,
                decision.authorityReference(),
                decision.provenance());
    }

    public IntegrationContract contractFor(String contractId) {
        Objects.requireNonNull(contractId, "contractId");
        IntegrationContract contract = contracts.get(contractId);
        if (contract == null) {
            throw new IllegalArgumentException("unknown Phase 9 integration contract: " + contractId);
        }
        return contract;
    }

    private static void validateRequestAgainstContract(
            BoundaryRequest request,
            IntegrationContract contract) {

        if (!contract.sourceDomain().equals("WORKFORCE")) {
            throw new IllegalArgumentException("Phase 9 boundary source must remain WORKFORCE");
        }

        if (request.provenance() == null) {
            throw new IllegalArgumentException("boundary provenance is mandatory");
        }

        if (request.authorityReference().isBlank()) {
            throw new IllegalArgumentException("boundary authority reference is mandatory");
        }
    }

    private static void validateDecision(BoundaryDecision decision) {
        if (decision.provenance() == null) {
            throw new IllegalArgumentException("decision provenance is mandatory");
        }
        if (decision.authorityReference().isBlank()) {
            throw new IllegalArgumentException("decision authority reference is mandatory");
        }
    }
}
