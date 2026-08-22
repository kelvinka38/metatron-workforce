package com.metatron.workforce.phase8;

import java.util.Objects;

public final class InstitutionalLearningBoundary {

    private InstitutionalLearningBoundary() {
    }

    public static void requireNotPolicy(Object learning) {
        Objects.requireNonNull(learning);

        if (learning instanceof PolicyLike) {
            throw new IllegalArgumentException(
                    "Learning cannot silently become institutional policy");
        }
    }

    public static void requireNotKnowledge(Object learning) {
        Objects.requireNonNull(learning);

        if (learning instanceof KnowledgeLike) {
            throw new IllegalArgumentException(
                    "Learning cannot silently become institutional knowledge");
        }
    }

    public interface KnowledgeLike {
    }

    public interface PolicyLike {
    }
}
