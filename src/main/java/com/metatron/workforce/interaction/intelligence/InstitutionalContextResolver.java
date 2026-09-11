package com.metatron.workforce.interaction.intelligence;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic boundary that assembles relevant institutional context before cognition. */
public interface InstitutionalContextResolver {
    InstitutionalContextPackage resolve(ContextResolutionRequest request);

    record ContextResolutionRequest(
            CanonicalRequestEnvelope envelope,
            List<String> authorityChain,
            List<String> canonicalRefs,
            List<String> planRefs,
            List<String> runtimeRefs,
            List<String> evidenceRefs,
            List<String> conflicts,
            Map<String, String> freshness) {
        public ContextResolutionRequest {
            Objects.requireNonNull(envelope, "envelope");
            authorityChain = authorityChain == null ? List.of() : List.copyOf(authorityChain);
            canonicalRefs = canonicalRefs == null ? List.of() : List.copyOf(canonicalRefs);
            planRefs = planRefs == null ? List.of() : List.copyOf(planRefs);
            runtimeRefs = runtimeRefs == null ? List.of() : List.copyOf(runtimeRefs);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
            freshness = freshness == null ? Map.of() : Map.copyOf(freshness);
        }
    }

    /**
     * Compatibility-safe resolver: the caller supplies already-authoritative references; this
     * component orders/packages them and never invents missing authority.
     */
    final class Default implements InstitutionalContextResolver {
        @Override
        public InstitutionalContextPackage resolve(ContextResolutionRequest request) {
            Objects.requireNonNull(request, "request");
            CanonicalRequestEnvelope envelope = request.envelope();
            List<String> caseRefs = envelope.caseRef().isBlank()
                    ? List.of()
                    : List.of(envelope.caseRef());
            return InstitutionalContextPackage.resolve(
                    "context:" + envelope.requestId(),
                    request.authorityChain(),
                    request.canonicalRefs(),
                    request.planRefs(),
                    request.runtimeRefs(),
                    caseRefs,
                    request.evidenceRefs(),
                    request.conflicts(),
                    request.freshness());
        }
    }
}
