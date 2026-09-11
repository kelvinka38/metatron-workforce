package com.metatron.workforce.runtime.execution;

import com.metatron.workforce.execution.ExecutionAttempt;
import com.metatron.workforce.execution.ExecutionAttemptService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Adapter from Highway's existing release-resource vocabulary into the canonical resource manager.
 * Highway remains the release plane; this bridge makes its protected resources share the same
 * ExecutionAttempt + ResourceLease/fencing authority as Workforce execution.
 */
@Component
public final class HighwayResourceBridgeService {
    public static final String ACTOR = "SYSTEM-HIGHWAY-RELEASE";
    public static final String AUTHORIZATION = "authorization:highway-release:v1";
    private static final Duration ATTEMPT_LEASE = Duration.ofSeconds(90);
    private static final Duration RESOURCE_LEASE = Duration.ofSeconds(60);

    private final ExecutionAttemptService attempts;
    private final ExecutionResourceManager resources;

    public HighwayResourceBridgeService(ExecutionAttemptService attempts, ExecutionResourceManager resources) {
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    public synchronized Grant acquire(AcquireRequest request, Instant at) {
        Objects.requireNonNull(request, "request");
        ExecutionAttempt attempt = findOrBegin(request, at);
        attempts.heartbeat(attempt.attemptId(), attempt.fencingToken(), ATTEMPT_LEASE, at);
        List<ResourceClaim> claims = new ArrayList<>();
        request.resources().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String resource = require(entry.getKey(), "resource");
            String mode = require(entry.getValue(), "mode").toUpperCase(Locale.ROOT);
            ResourceClaim.Mode claimMode = switch (mode) {
                case "READ" -> ResourceClaim.Mode.READ_SHARED;
                case "WRITE" -> resource.startsWith("github:main:")
                        ? ResourceClaim.Mode.CAS_SERIALIZED : ResourceClaim.Mode.LEASE_EXCLUSIVE;
                default -> throw new IllegalArgumentException("Highway resource mode must be READ or WRITE");
            };
            claims.add(new ResourceClaim("highway:" + request.taskId() + ":" + stable(resource),
                    attempt.attemptId(), resource, resourceClass(resource), claimMode, 1, "lease", true,
                    request.sourceSha(), Map.of("highwayTaskId", request.taskId(), "highwayKind", request.kind())));
        });
        ResourceGrant grant = resources.acquire(attempt.attemptId(), attempt.fencingToken(), ACTOR,
                claims, RESOURCE_LEASE, at);
        List<LeaseView> leases = grant.leases().stream().map(HighwayResourceBridgeService::view).toList();
        return new Grant(attempt.attemptId(), attempt.fencingToken(), request.taskId(), request.attemptNumber(), leases, at);
    }

    public synchronized Grant heartbeat(LeaseRequest request, Instant at) {
        ExecutionAttempt attempt = attempts.requireCurrent(request.attemptId(), request.attemptFencingToken(), at);
        attempts.heartbeat(attempt.attemptId(), attempt.fencingToken(), ATTEMPT_LEASE, at);
        List<ResourceLease> renewed = resources.renew(attempt.attemptId(), attempt.fencingToken(),
                request.leases().stream().map(LeaseToken::leaseId).toList(), RESOURCE_LEASE, at);
        return new Grant(attempt.attemptId(), attempt.fencingToken(), request.taskId(), attempt.attemptNumber(),
                renewed.stream().map(HighwayResourceBridgeService::view).toList(), at);
    }

    /** Must be called immediately before Highway launches a command that can mutate protected resources. */
    public synchronized Grant validate(LeaseRequest request, Instant at) {
        ExecutionAttempt attempt = attempts.requireCurrent(request.attemptId(), request.attemptFencingToken(), at);
        List<LeaseView> views = new ArrayList<>();
        for (LeaseToken token : request.leases()) {
            ResourceLease lease = resources.findLease(token.leaseId())
                    .orElseThrow(() -> new SecurityException("RESOURCE_LEASE_REQUIRED: " + token.leaseId()));
            if (!lease.attemptId().equals(attempt.attemptId()) || !lease.resourceId().equals(token.resourceId())) {
                throw new SecurityException("RESOURCE_LEASE_REQUIRED: Highway ownership mismatch");
            }
            if (!lease.activeAt(at)) throw new SecurityException("RESOURCE_LEASE_EXPIRED: " + lease.leaseId());
            if (lease.mode() != ResourceClaim.Mode.READ_SHARED && lease.mode() != ResourceClaim.Mode.CAPACITY) {
                resources.requireCurrent(attempt.attemptId(), attempt.fencingToken(), lease.leaseId(),
                        token.resourceFencingToken(), lease.resourceId(), at);
            }
            views.add(view(lease));
        }
        return new Grant(attempt.attemptId(), attempt.fencingToken(), request.taskId(), attempt.attemptNumber(), List.copyOf(views), at);
    }

    public synchronized void finish(FinishRequest request, Instant at) {
        ExecutionAttempt attempt = attempts.find(request.attemptId())
                .orElseThrow(() -> new IllegalArgumentException("Highway execution attempt not found"));
        if (attempt.fencingToken() != request.attemptFencingToken()) throw new SecurityException("Highway attempt fence mismatch");
        resources.release(attempt.attemptId(), request.leaseIds(), at);
        if (attempt.terminal()) return;
        if (request.success()) attempts.succeed(attempt.attemptId(), attempt.fencingToken(), at);
        else attempts.fail(attempt.attemptId(), attempt.fencingToken(), require(request.failure(), "failure"), at);
    }

    private ExecutionAttempt findOrBegin(AcquireRequest request, Instant at) {
        List<ExecutionAttempt> matching = attempts.all().stream()
                .filter(a -> a.dispatchId().equals(request.taskId()))
                .filter(a -> a.attemptNumber() == request.attemptNumber())
                .sorted(Comparator.comparingLong(ExecutionAttempt::fencingToken).reversed())
                .toList();
        for (ExecutionAttempt existing : matching) {
            if (!existing.terminal()) {
                attempts.requireCurrent(existing.attemptId(), existing.fencingToken(), at);
                return existing;
            }
        }
        return attempts.begin(request.taskId(), "highway:" + request.correlationId(), "highway-task:" + request.taskId(),
                ACTOR, "assignment:highway:" + request.taskId(), AUTHORIZATION,
                "runtime:highway:" + request.executorId(), request.attemptNumber(), ATTEMPT_LEASE, at);
    }

    private static ResourceClaim.ResourceClass resourceClass(String resource) {
        if (resource.startsWith("prod:")) return ResourceClaim.ResourceClass.ENVIRONMENT;
        if (resource.startsWith("artifact:") || resource.startsWith("build:")) return ResourceClaim.ResourceClass.BUILD;
        if (resource.startsWith("source-release:") || resource.startsWith("repo:")) return ResourceClaim.ResourceClass.REPOSITORY;
        if (resource.startsWith("github:")) return ResourceClaim.ResourceClass.INTEGRATION;
        return ResourceClaim.ResourceClass.EXTERNAL;
    }
    private static LeaseView view(ResourceLease lease) {
        return new LeaseView(lease.leaseId(), lease.resourceId(), lease.mode().name(), lease.fencingToken(), lease.expiresAt());
    }
    private static String stable(String value) {
        return Integer.toUnsignedString(value.hashCode(), 16);
    }
    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }

    public record AcquireRequest(String taskId, String kind, String sourceSha, String correlationId,
                                 String executorId, int attemptNumber, Map<String,String> resources) {
        public AcquireRequest {
            taskId=require(taskId,"taskId"); kind=require(kind,"kind"); sourceSha=require(sourceSha,"sourceSha").toLowerCase();
            if(!sourceSha.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("sourceSha must be immutable SHA");
            correlationId=require(correlationId,"correlationId"); executorId=require(executorId,"executorId");
            if(attemptNumber<1) throw new IllegalArgumentException("attemptNumber must be positive");
            resources=Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(resources,"resources")));
            if(resources.isEmpty()) throw new IllegalArgumentException("Highway resources required");
        }
    }
    public record LeaseToken(String leaseId, String resourceId, long resourceFencingToken) {
        public LeaseToken { leaseId=require(leaseId,"leaseId"); resourceId=require(resourceId,"resourceId"); if(resourceFencingToken<1)throw new IllegalArgumentException("resourceFencingToken must be positive"); }
    }
    public record LeaseRequest(String taskId, String attemptId, long attemptFencingToken, List<LeaseToken> leases) {
        public LeaseRequest { taskId=require(taskId,"taskId");attemptId=require(attemptId,"attemptId");if(attemptFencingToken<1)throw new IllegalArgumentException("attemptFencingToken must be positive");leases=List.copyOf(Objects.requireNonNull(leases,"leases"));if(leases.isEmpty())throw new IllegalArgumentException("leases required"); }
        int attemptNumber(){return attemptsNumber(attemptId);}
        private static int attemptsNumber(String ignored){return 0;}
    }
    public record FinishRequest(String taskId, String attemptId, long attemptFencingToken, List<String> leaseIds, boolean success, String failure) {
        public FinishRequest { taskId=require(taskId,"taskId");attemptId=require(attemptId,"attemptId");if(attemptFencingToken<1)throw new IllegalArgumentException("attemptFencingToken must be positive");leaseIds=List.copyOf(leaseIds==null?List.of():leaseIds);failure=failure==null?"":failure.trim();if(!success&&failure.isBlank())throw new IllegalArgumentException("failure required when unsuccessful"); }
    }
    public record LeaseView(String leaseId, String resourceId, String mode, long resourceFencingToken, Instant expiresAt) { }
    public record Grant(String attemptId, long attemptFencingToken, String taskId, int attemptNumber, List<LeaseView> leases, Instant observedAt) {
        public Grant { leases=List.copyOf(leases); }
    }
}
