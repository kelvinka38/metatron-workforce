package com.metatron.workforce.runtime.execution;

import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import com.metatron.workforce.runtime.CanonicalRepositoryScope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Protected-ref integration coordinator. Coding remains parallel in isolated workspaces; only the
 * protected ref mutation is serialized through ExecutionResourceManager and expected-base CAS.
 * This controller coordinates eligibility/fencing/evidence and deliberately does not own GitHub
 * credentials or release authority.
 */
public final class RepositoryIntegrationController {
    private final ExecutionAttemptService attempts;
    private final ExecutionWorkspaceManager workspaces;
    private final ExecutionResourceManager resources;
    private final IntegrationQueueStore store;
    private final Duration leaseTtl;
    private final Map<String, IntegrationQueueEntry> entries = new LinkedHashMap<>();

    public RepositoryIntegrationController(ExecutionAttemptService attempts,
                                           ExecutionWorkspaceManager workspaces,
                                           ExecutionResourceManager resources,
                                           IntegrationQueueStore store,
                                           Duration leaseTtl) {
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.store = Objects.requireNonNull(store, "store");
        this.leaseTtl = Objects.requireNonNull(leaseTtl, "leaseTtl");
        if (leaseTtl.isZero() || leaseTtl.isNegative()) throw new IllegalArgumentException("leaseTtl must be positive");
        entries.putAll(store.load());
    }

    public synchronized IntegrationQueueEntry enqueue(String attemptId,
                                                      long attemptFence,
                                                      String componentId,
                                                      String candidateHeadSha,
                                                      List<String> changedPaths,
                                                      List<String> protectedResources,
                                                      List<String> ciEvidence,
                                                      Instant at) {
        ExecutionAttempt attempt = attempts.requireCurrent(attemptId, attemptFence, at);
        ExecutionWorkspaceBinding binding = workspaces.requireActive(attemptId, attemptFence, at);
        ExecutionRepositoryComponent component = binding.requireComponent(componentId);
        if (component.baseSha().isBlank()) throw new IllegalStateException("integration requires immutable repository base SHA");
        String candidate = requireSha(candidateHeadSha, "candidateHeadSha");
        if (!component.localHeadSha().isBlank() && !component.localHeadSha().equals(candidate)) {
            throw new IllegalStateException("integration candidate does not match recorded workspace HEAD");
        }
        String repository = CanonicalRepositoryScope.requireAllowed(component.repository());
        String entryId = "integration:" + digest(attemptId + "|" + componentId + "|" + component.baseSha() + "|" + candidate).substring(0, 32);
        IntegrationQueueEntry existing = entries.get(entryId);
        if (existing != null) {
            if (!existing.attemptId().equals(attemptId)
                    || existing.attemptFencingToken() != attemptFence
                    || !existing.repository().equals(repository)
                    || !existing.expectedBaseSha().equals(component.baseSha())
                    || !existing.candidateHeadSha().equals(candidate)) {
                throw new IllegalStateException("integration idempotency conflict");
            }
            return existing;
        }
        IntegrationQueueEntry entry = new IntegrationQueueEntry(entryId, attempt.attemptId(), attemptFence,
                componentId, repository, component.baseSha(), candidate, changedPaths,
                protectedResources == null ? List.of() : protectedResources, ciEvidence,
                IntegrationQueueEntry.Status.QUEUED, "queued", "", 0, "", 1, at, at);
        entries.put(entryId, entry);
        persist();
        return entry;
    }

    public synchronized List<ConflictEdge> conflicts() {
        return ConflictGraph.detect(entries.values());
    }

    /**
     * Begin one protected-ref integration candidate. If its base is stale it is terminally marked
     * STALE_BASE and the controller evaluates the next queued candidate instead of overwriting main.
     */
    public synchronized Optional<IntegrationQueueEntry> beginNext(String repository, String actualMainSha, Instant at) {
        String repo = CanonicalRepositoryScope.requireAllowed(repository);
        String actual = requireSha(actualMainSha, "actualMainSha");
        List<IntegrationQueueEntry> candidates = entries.values().stream()
                .filter(e -> e.status() == IntegrationQueueEntry.Status.QUEUED)
                .filter(e -> e.repository().equals(repo))
                .sorted(java.util.Comparator.comparing(IntegrationQueueEntry::createdAt)
                        .thenComparing(IntegrationQueueEntry::entryId))
                .toList();
        for (IntegrationQueueEntry entry : candidates) {
            try {
                attempts.requireCurrent(entry.attemptId(), entry.attemptFencingToken(), at);
            } catch (RuntimeException staleAttempt) {
                IntegrationQueueEntry failed = entry.transition(IntegrationQueueEntry.Status.FAILED,
                        "execution-attempt-not-current", "", 0, "", at);
                entries.put(entry.entryId(), failed);
                persist();
                continue;
            }
            if (!entry.expectedBaseSha().equals(actual)) {
                IntegrationQueueEntry stale = entry.transition(IntegrationQueueEntry.Status.STALE_BASE,
                        "expected-base=" + entry.expectedBaseSha() + ";actual-main=" + actual,
                        "", 0, "", at);
                entries.put(entry.entryId(), stale);
                persist();
                continue;
            }
            ResourceClaim claim = new ResourceClaim(
                    "claim:" + entry.entryId(), entry.attemptId(), integrationResource(repo),
                    ResourceClaim.ResourceClass.INTEGRATION, ResourceClaim.Mode.CAS_SERIALIZED,
                    1, "protected-ref", true, entry.expectedBaseSha(),
                    Map.of("repository", repo, "candidateHeadSha", entry.candidateHeadSha()));
            ResourceGrant grant;
            try {
                ExecutionAttempt owner = attempts.find(entry.attemptId()).orElseThrow();
                grant = resources.acquire(entry.attemptId(), entry.attemptFencingToken(), owner.workerId(),
                        List.of(claim), leaseTtl, at);
            } catch (IllegalStateException conflict) {
                return Optional.empty();
            }
            ResourceLease lease = grant.leases().getFirst();
            IntegrationQueueEntry integrating = entry.transition(IntegrationQueueEntry.Status.INTEGRATING,
                    "protected-ref-lease-acquired", lease.leaseId(), lease.fencingToken(), "", at);
            entries.put(entry.entryId(), integrating);
            persist();
            return Optional.of(integrating);
        }
        return Optional.empty();
    }

    /** Validate expected-main CAS plus both attempt and resource fencing immediately after the merge effect. */
    public synchronized IntegrationQueueEntry markMerged(String entryId,
                                                         String actualBaseBeforeMerge,
                                                         String mergedSha,
                                                         String leaseId,
                                                         long resourceFence,
                                                         Instant at) {
        IntegrationQueueEntry entry = requireEntry(entryId);
        if (entry.status() != IntegrationQueueEntry.Status.INTEGRATING) {
            throw new IllegalStateException("integration entry is not INTEGRATING");
        }
        String actualBase = requireSha(actualBaseBeforeMerge, "actualBaseBeforeMerge");
        if (!entry.expectedBaseSha().equals(actualBase)) {
            releaseIfOwned(entry, at);
            IntegrationQueueEntry stale = entry.transition(IntegrationQueueEntry.Status.STALE_BASE,
                    "expected-base=" + entry.expectedBaseSha() + ";actual-before-merge=" + actualBase,
                    "", 0, "", at);
            entries.put(entry.entryId(), stale);
            persist();
            return stale;
        }
        if (!entry.integrationLeaseId().equals(leaseId) || entry.integrationResourceFence() != resourceFence) {
            throw new SecurityException("integration lease/fence mismatch");
        }
        resources.requireCurrent(entry.attemptId(), entry.attemptFencingToken(), leaseId, resourceFence,
                integrationResource(entry.repository()), at);
        String merged = requireSha(mergedSha, "mergedSha");
        IntegrationQueueEntry completed = entry.transition(IntegrationQueueEntry.Status.MERGED,
                "expected-main-CAS-pass", "", 0, merged, at);
        entries.put(entry.entryId(), completed);
        resources.release(entry.attemptId(), List.of(leaseId), at);
        persist();
        return completed;
    }

    public synchronized IntegrationQueueEntry fail(String entryId, String reason, Instant at) {
        IntegrationQueueEntry entry = requireEntry(entryId);
        if (entry.terminal()) return entry;
        releaseIfOwned(entry, at);
        IntegrationQueueEntry failed = entry.transition(IntegrationQueueEntry.Status.FAILED,
                require(reason, "reason"), "", 0, "", at);
        entries.put(entryId, failed);
        persist();
        return failed;
    }

    public synchronized Optional<IntegrationQueueEntry> find(String entryId) { return Optional.ofNullable(entries.get(entryId)); }
    public synchronized List<IntegrationQueueEntry> all() { return List.copyOf(entries.values()); }

    public static String integrationResource(String repository) {
        return "github:main:" + CanonicalRepositoryScope.requireAllowed(repository);
    }

    private void releaseIfOwned(IntegrationQueueEntry entry, Instant at) {
        if (!entry.integrationLeaseId().isBlank()) resources.release(entry.attemptId(), List.of(entry.integrationLeaseId()), at);
    }
    private IntegrationQueueEntry requireEntry(String entryId) {
        IntegrationQueueEntry entry = entries.get(entryId);
        if (entry == null) throw new IllegalArgumentException("integration entry not found: " + entryId);
        return entry;
    }
    private static String requireSha(String value, String field) {
        String out = require(value, field).toLowerCase();
        if (!out.matches("[0-9a-f]{40}")) throw new IllegalArgumentException(field + " must be immutable 40-char SHA");
        return out;
    }
    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
    private void persist() { store.save(entries); }
}
