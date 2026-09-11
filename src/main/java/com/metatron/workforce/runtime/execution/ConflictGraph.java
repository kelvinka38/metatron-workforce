package com.metatron.workforce.runtime.execution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Correctness-first integration conflict graph. V1 is deterministic and does not depend on AI:
 * exact/ancestor changed-path overlap plus hierarchical protected-resource overlap.
 */
public final class ConflictGraph {
    private ConflictGraph() {}

    public static List<ConflictEdge> detect(Collection<IntegrationQueueEntry> entries) {
        List<IntegrationQueueEntry> list = entries == null ? List.of() : entries.stream()
                .filter(IntegrationQueueEntry::activeCandidate)
                .sorted(java.util.Comparator.comparing(IntegrationQueueEntry::createdAt)
                        .thenComparing(IntegrationQueueEntry::entryId))
                .toList();
        List<ConflictEdge> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                IntegrationQueueEntry left = list.get(i), right = list.get(j);
                if (!left.repository().equals(right.repository())) continue;
                for (String lp : left.changedPaths()) {
                    for (String rp : right.changedPaths()) {
                        if (pathConflict(lp, rp)) {
                            String subject = shorter(lp, rp);
                            out.add(new ConflictEdge(left.entryId(), right.entryId(), ConflictEdge.Kind.PATH,
                                    subject, "changed-path-overlap:" + lp + "<->" + rp));
                        }
                    }
                }
                for (String lr : left.protectedResources()) {
                    for (String rr : right.protectedResources()) {
                        if (resourceConflict(lr, rr)) {
                            String subject = shorter(lr, rr);
                            out.add(new ConflictEdge(left.entryId(), right.entryId(), ConflictEdge.Kind.RESOURCE,
                                    subject, "protected-resource-overlap:" + lr + "<->" + rr));
                        }
                    }
                }
            }
        }
        // Stable de-duplication matters because path hierarchy can report equivalent overlap evidence.
        Set<String> seen = new LinkedHashSet<>();
        List<ConflictEdge> deduped = new ArrayList<>();
        for (ConflictEdge edge : out) {
            String key = edge.leftEntryId() + "|" + edge.rightEntryId() + "|" + edge.kind() + "|" + edge.subject();
            if (seen.add(key)) deduped.add(edge);
        }
        return List.copyOf(deduped);
    }

    static boolean pathConflict(String a, String b) {
        String x = cleanPath(a), y = cleanPath(b);
        return x.equals(y) || x.startsWith(y + "/") || y.startsWith(x + "/");
    }

    static boolean resourceConflict(String a, String b) {
        String x = require(a), y = require(b);
        return x.equals(y) || x.startsWith(y + ":") || y.startsWith(x + ":");
    }

    private static String cleanPath(String value) {
        String v = require(value).replace('\\', '/');
        while (v.startsWith("./")) v = v.substring(2);
        if (v.startsWith("/") || v.contains("..") || v.contains("\n") || v.contains("\r")) {
            throw new IllegalArgumentException("unsafe changed path");
        }
        return v;
    }

    private static String shorter(String a, String b) {
        String x = require(a), y = require(b);
        return x.length() <= y.length() ? x : y;
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("conflict subject required");
        return value.trim();
    }
}
