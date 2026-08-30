package com.metatron.workforce.interaction.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable filesystem-backed Intelligence Case store.
 *
 * Case identity is primary and historical Cases are retained. A conversation index points to the
 * currently active/latest Case for continuity. Legacy conversation-keyed files are migrated lazily.
 */
public final class PersistentIntelligenceCaseStore implements IntelligenceCaseStore {
    private static final String CASES_DIR = "cases";
    private static final String CONVERSATION_INDEX_DIR = "by-conversation";
    private static final int MAX_MIGRATION_SCAN_FILES = 10_000;

    private final Path root;
    private final ObjectMapper objectMapper;
    private final InformationRequirementPlanner requirementPlanner;

    public PersistentIntelligenceCaseStore(Path root, ObjectMapper objectMapper) {
        this(root, objectMapper, new InformationRequirementPlanner(new AnalyticalProtocolRegistry()));
    }

    public PersistentIntelligenceCaseStore(Path root, ObjectMapper objectMapper,
                                           InformationRequirementPlanner requirementPlanner) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
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
            Files.createDirectories(casesRoot());
            Files.createDirectories(conversationIndexRoot());
            writeAtomically(caseFile(intelligenceCase.caseId()), objectMapper.writeValueAsString(intelligenceCase));
            writeAtomically(conversationIndexFile(intelligenceCase.conversationId()), intelligenceCase.caseId());
        } catch (IOException failure) {
            throw new IllegalStateException("intelligence_case_write_failed", failure);
        }
    }

    @Override
    public synchronized Optional<IntelligenceCase> findActive(String conversationId) {
        requireNonBlank(conversationId, "conversationId");
        try {
            Path index = conversationIndexFile(conversationId);
            if (Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(index)) {
                String caseId = Files.readString(index, StandardCharsets.UTF_8).trim();
                if (!caseId.isBlank()) {
                    Optional<IntelligenceCase> found = findByCaseId(caseId);
                    if (found.isPresent()) {
                        if (!conversationId.equals(found.get().conversationId())) {
                            throw new IllegalStateException("intelligence_case_conversation_mismatch");
                        }
                        return found;
                    }
                }
            }

            Optional<IntelligenceCase> legacy = readLegacyConversationFile(conversationId);
            if (legacy.isPresent()) {
                save(legacy.get());
                return legacy;
            }
            return Optional.empty();
        } catch (IOException failure) {
            throw new IllegalStateException("intelligence_case_read_failed", failure);
        }
    }

    @Override
    public synchronized Optional<IntelligenceCase> findByCaseId(String caseId) {
        requireNonBlank(caseId, "caseId");
        try {
            Path target = caseFile(caseId);
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
                IntelligenceCase intelligenceCase = readCase(target);
                if (!caseId.equals(intelligenceCase.caseId())) {
                    throw new IllegalStateException("intelligence_case_id_mismatch");
                }
                return Optional.of(intelligenceCase);
            }

            // Backward-compatible bounded migration path for legacy conversation-keyed case files.
            Optional<IntelligenceCase> migrated = findLegacyByCaseId(caseId);
            if (migrated.isPresent()) save(migrated.get());
            return migrated;
        } catch (IOException failure) {
            throw new IllegalStateException("intelligence_case_read_failed", failure);
        }
    }

    private Optional<IntelligenceCase> readLegacyConversationFile(String conversationId) throws IOException {
        Path legacy = legacyConversationFile(conversationId);
        if (!Files.isRegularFile(legacy, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(legacy)) {
            return Optional.empty();
        }
        IntelligenceCase intelligenceCase = readCase(legacy);
        if (!conversationId.equals(intelligenceCase.conversationId())) {
            throw new IllegalStateException("intelligence_case_conversation_mismatch");
        }
        return Optional.of(intelligenceCase);
    }

    private Optional<IntelligenceCase> findLegacyByCaseId(String caseId) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try (var paths = Files.list(root)) {
            return paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .limit(MAX_MIGRATION_SCAN_FILES)
                    .map(this::readCaseUnchecked)
                    .filter(Objects::nonNull)
                    .filter(item -> caseId.equals(item.caseId()))
                    .findFirst();
        }
    }

    private IntelligenceCase readCase(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new IllegalStateException("intelligence_case_path_invalid");
        if (Files.isSymbolicLink(normalized)) throw new IllegalStateException("intelligence_case_symlink_rejected");
        return objectMapper.readValue(Files.readString(normalized, StandardCharsets.UTF_8), IntelligenceCase.class);
    }

    private IntelligenceCase readCaseUnchecked(Path path) {
        try {
            return readCase(path);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeAtomically(Path target, String content) throws IOException {
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".intelligence-case-", ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path casesRoot() {
        return root.resolve(CASES_DIR).normalize();
    }

    private Path conversationIndexRoot() {
        return root.resolve(CONVERSATION_INDEX_DIR).normalize();
    }

    private Path caseFile(String caseId) {
        requireNonBlank(caseId, "caseId");
        return casesRoot().resolve(hash(caseId) + ".json").normalize();
    }

    private Path conversationIndexFile(String conversationId) {
        requireNonBlank(conversationId, "conversationId");
        return conversationIndexRoot().resolve(hash(conversationId) + ".ref").normalize();
    }

    private Path legacyConversationFile(String conversationId) {
        requireNonBlank(conversationId, "conversationId");
        return root.resolve(hash(conversationId) + ".json").normalize();
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception impossible) {
            throw new IllegalStateException("intelligence_case_key_failed", impossible);
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
