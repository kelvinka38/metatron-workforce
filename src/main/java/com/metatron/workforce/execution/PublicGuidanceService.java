package com.metatron.workforce.execution;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Resolves required guidance only from the Workforce public publication.
 * No repository credential or cross-repository runtime access is permitted here.
 */
public final class PublicGuidanceService {
    private static final String HTTP_PREFIX = "/public/docs/";
    private static final String CLASSPATH_PREFIX = "static/public/docs/";

    public List<GuidanceReceipt> readRequired(Assignment assignment) {
        List<GuidanceReceipt> receipts = new ArrayList<>();
        for (GuidanceRequirement requirement : assignment.requiredGuidance()) {
            receipts.add(read(requirement));
        }
        return List.copyOf(receipts);
    }

    public GuidanceReceipt read(GuidanceRequirement requirement) {
        String publicPath = requirement.publicResourcePath();
        if (!publicPath.startsWith(HTTP_PREFIX)) {
            throw new IllegalStateException("guidance_not_public:" + publicPath);
        }
        String resourcePath = CLASSPATH_PREFIX + publicPath.substring(HTTP_PREFIX.length());
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            throw new IllegalStateException("required_guidance_unavailable:" + publicPath);
        }
        try (var input = resource.getInputStream()) {
            byte[] bytes = input.readAllBytes();
            if (new String(bytes, StandardCharsets.UTF_8).isBlank()) {
                throw new IllegalStateException("required_guidance_empty:" + publicPath);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new GuidanceReceipt(
                    requirement.guidanceId(),
                    publicPath,
                    requirement.sourceRef(),
                    HexFormat.of().formatHex(digest.digest(bytes)),
                    Instant.now());
        } catch (IOException failure) {
            throw new IllegalStateException("required_guidance_read_failed:" + publicPath, failure);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("sha256_unavailable", impossible);
        }
    }
}
