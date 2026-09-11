package com.metatron.workforce.observation;

import com.metatron.workforce.management.FounderWorkerWorkProductStore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Independently re-reads the durable cognitive work product before Workforce may claim completion. */
@Component
public final class FounderWorkerWorkProductObservationVerifier implements ObservationVerifier {
    private static final String EVIDENCE_PREFIX = "founder-worker-work-product:";
    private final FounderWorkerWorkProductStore products;

    public FounderWorkerWorkProductObservationVerifier(FounderWorkerWorkProductStore products) {
        this.products = Objects.requireNonNull(products, "products");
    }

    @Override
    public boolean supports(ObservationRequirement requirement) {
        Objects.requireNonNull(requirement, "requirement");
        return requirement.evidenceRequirements().stream()
                .map(value -> value == null ? "" : value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains("founder-worker-work-product")
                        || value.contains("durable cognitive work product"));
    }

    @Override
    public Optional<ObservationReport> observe(
            ObservationRequirement requirement,
            List<String> executionEvidenceReferences,
            Instant at) {
        Objects.requireNonNull(requirement, "requirement");
        Objects.requireNonNull(executionEvidenceReferences, "executionEvidenceReferences");
        Objects.requireNonNull(at, "at");

        String productId = executionEvidenceReferences.stream()
                .filter(Objects::nonNull)
                .filter(value -> value.startsWith(EVIDENCE_PREFIX))
                .map(value -> value.substring(EVIDENCE_PREFIX.length()).trim())
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse("");
        if (productId.isBlank()) return Optional.empty();

        return products.find(productId).map(product -> {
            boolean objectiveMatches = requirement.objectiveId().equals(product.objectiveId());
            boolean targetMatches = !requirement.target().toUpperCase(Locale.ROOT).startsWith("WORKER-")
                    || requirement.target().equalsIgnoreCase(product.workerId());
            boolean usable = !product.product().isBlank();
            boolean pass = objectiveMatches && targetMatches && usable;

            List<String> evidence = new ArrayList<>();
            evidence.add(EVIDENCE_PREFIX + product.productId());
            evidence.add("observed-worker:" + product.workerId());
            evidence.add("observed-assignment:" + product.assignmentReference());
            evidence.addAll(product.evidenceReferences().stream().limit(100).toList());

            return new ObservationReport(
                    "observation:founder-worker-product:"
                            + Integer.toUnsignedString(Objects.hash(requirement.requirementId(), product.productId()), 16),
                    requirement.requirementId(),
                    requirement.objectiveId(),
                    requirement.target(),
                    pass
                            ? "durable cognitive work product exists for the assigned canonical Worker"
                            : "durable work product did not match required Objective/Worker target",
                    "durable-founder-worker-work-product-read",
                    at,
                    product.createdAt(),
                    List.copyOf(evidence),
                    pass ? 1.0 : 0.95,
                    ObservationReport.Quality.HIGH,
                    pass ? "" : "objective_or_worker_target_mismatch",
                    pass ? ObservationReport.CriterionResult.PASS : ObservationReport.CriterionResult.FAIL);
        });
    }
}
