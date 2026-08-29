package com.metatron.workforce.interaction.intelligence;

import java.util.Locale;

/**
 * Deterministic consistency guard for answers produced after successful external evidence retrieval.
 * A provider must not claim that Metatron lacks web/current-data access when Workforce has already
 * supplied external evidence for the same request.
 */
public final class ExternalEvidenceResponseGuard {

    public void validate(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("EXTERNAL_EVIDENCE_OUTPUT_EMPTY");
        }
        String value = text.toLowerCase(Locale.ROOT);
        if (containsAny(value,
                "không có dữ liệu thời gian thực",
                "không có dữ liệu thời gian",
                "chưa được kết nối với công cụ duyệt web",
                "chưa được kết nối với công cụ tìm kiếm",
                "không thể truy cập trực tiếp vào các trang web",
                "không thể truy cập internet",
                "không thể truy cập web",
                "không có quyền truy cập web",
                "i don't have real-time data",
                "i do not have real-time data",
                "i can't access the web",
                "i cannot access the web",
                "i don't have web access",
                "i do not have web access",
                "i can't browse the internet",
                "i cannot browse the internet")) {
            throw new IllegalStateException("EXTERNAL_EVIDENCE_CONTRADICTION");
        }
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }
}
