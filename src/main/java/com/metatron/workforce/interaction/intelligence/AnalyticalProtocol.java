package com.metatron.workforce.interaction.intelligence;

import java.util.List;
import java.util.Objects;

/** Reusable analytical procedure describing evidence needs and operations for one problem class. */
public record AnalyticalProtocol(
        AnalyticalProtocolType type,
        List<String> minimumInformationRequirements,
        List<String> optionalInformationRequirements,
        List<String> deterministicOperations,
        List<String> reasoningOperations,
        List<String> falsificationChecks,
        String outputContract) {

    public AnalyticalProtocol {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(minimumInformationRequirements, "minimumInformationRequirements");
        Objects.requireNonNull(optionalInformationRequirements, "optionalInformationRequirements");
        Objects.requireNonNull(deterministicOperations, "deterministicOperations");
        Objects.requireNonNull(reasoningOperations, "reasoningOperations");
        Objects.requireNonNull(falsificationChecks, "falsificationChecks");
        Objects.requireNonNull(outputContract, "outputContract");
        minimumInformationRequirements = List.copyOf(minimumInformationRequirements);
        optionalInformationRequirements = List.copyOf(optionalInformationRequirements);
        deterministicOperations = List.copyOf(deterministicOperations);
        reasoningOperations = List.copyOf(reasoningOperations);
        falsificationChecks = List.copyOf(falsificationChecks);
    }
}
