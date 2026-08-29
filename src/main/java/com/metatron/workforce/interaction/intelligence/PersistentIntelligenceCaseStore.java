package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable filesystem-backed Intelligence Case store keyed by canonical conversation id. */
public final class PersistentIntelligenceCaseStore implements IntelligenceCaseStore {
    private final Path root;
    private final ObjectMapper objectMapper;
    private final InformationRequirementPlanner requirementPlanner;

    public PersistentIntelligenceCaseStore(Path root, ObjectMapper objectMapper) {
        this(root, objectMapper, new InformationRequirementPlanner(new AnalyticalProtocolRegistry()));
    }

    public PersistentIntelligenceCaseStore(Path root, ObjectMapper objectMapper,
                                           InformationRequirementPlanner requirementPlanner) {
        this.root = Objects.requireNonNull(root, "root");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.requirementPlanner = Objects.requireNonNull(requirementPlanner, "requirementPlanner");
    }

    @Override
    public synchronized IntelligenceCase openOrUpdate(String conversationId, String requester, NormalizedRequest normalized) {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(normalized, "normalized");
        Instant now = Instant.now();
        List<InformationRequirement> requirements = requirementPlanner.plan(normalized);
        IntelligenceCase existing = findActive(conversationId).orElse(null);
        IntelligenceCase next;
        if (existing == null || existing.status() == IntelligenceCaseStatus.RESOLVED) {
            next = new IntelligenceCase(
                    "case-" + UUID.randomUUID(), conversationId, requester, normalized.objective(),
                    normalized.requestedDepth(), IntelligenceCaseStatus.INFORMATION_ASSESSMENT,
                    requirements, List.of(), normalized.explicitAssumptions(), List.of(),
                    normalized.materiallyAmbiguous() ? List.of(normalized.unresolvedSemanticAmbiguity()) : List.of(),
                    List.of(), List.of(), "", "", List.of(), now, now);
        } else {
            next = new IntelligenceCase(
                    existing.caseId(), existing.conversationId(), existing.requester(), normalized.objective(),
                    normalized.requestedDepth(), IntelligenceCaseStatus.REASSESSMENT,
                    requirements.isEmpty() ? existing.informationRequirements() : requirements,
                    existing.evidenceReferences(),
                    normalized.explicitAssumptions().isEmpty() ? existing.assumptions() : normalized.explicitAssumptions(),
                    existing.hypotheses(),
                    normalized.materiallyAmbiguous() ? List.of(normalized.unresolvedSemanticAmbiguity()) : existing.unknowns(),
                    existing.contradictions(), existing.reasoningArtifactReferences(), existing.latestConclusion(),
                    existing.latestRecommendation(), existing.externalInstitutionalReferences(), existing.createdAt(), now);
        }
        save(next);
        return next;
    }

    @Override
    public synchronized void save(IntelligenceCase intelligenceCase) {
        Objects.requireNonNull(intelligenceCase, "intelligenceCase");
        try {
            Files.createDirectories(root);
            Path target = file(intelligenceCase.conversationId());
            Path temp = Files.createTempFile(root, "intelligence-case-", ".tmp");
            Files.writeString(temp, objectMapper.writeValueAsString(intelligenceCase), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("intelligence_case_write_failed", failure);
        }
    }

    @Override
    public synchronized Optional<IntelligenceCase> findActive(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        try {
            Path target = file(conversationId);
            if (!Files.exists(target)) return Optional.empty();
            IntelligenceCase intelligenceCase = objectMapper.readValue(
                    Files.readString(target, StandardCharsets.UTF_8), IntelligenceCase.class);
            if (!conversationId.equals(intelligenceCase.conversationId())) {
                throw new IllegalStateException("intelligence_case_conversation_mismatch");
            }
            return Optional.of(intelligenceCase);
        } catch (IOException failure) {
            throw new IllegalStateException("intelligence_case_read_failed", failure);
        }
    }

    private Path file(String conversationId) {
        if (conversationId.isBlank()) throw new IllegalArgumentException("conversationId must not be blank");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(conversationId.getBytes(StandardCharsets.UTF_8));
            return root.resolve(HexFormat.of().formatHex(digest) + ".json");
        } catch (Exception impossible) {
            throw new IllegalStateException("intelligence_case_key_failed", impossible);
        }
    }
}
