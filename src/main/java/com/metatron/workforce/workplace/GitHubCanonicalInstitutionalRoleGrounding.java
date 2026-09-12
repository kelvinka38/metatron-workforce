package com.metatron.workforce.workplace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.metatron.workforce.runtime.RepositoryCredentialAuthority;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Read-only canonical institutional grounding from metatron-institution.
 *
 * No Workforce-local copy is treated as authority. The fetched GitHub blob SHA is attached as
 * evidence so a Worker can state exactly which canonical revision grounded the conversation.
 */
@Service
public final class GitHubCanonicalInstitutionalRoleGrounding implements InstitutionalRoleGrounding {
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(6);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int MAX_GROUNDING_CHARS = 18_000;
    private static final Pattern SECTION = Pattern.compile("(?m)(?=^##+\\s+)");
    private static final Map<String, String> DOMAIN_BINDINGS = Map.ofEntries(
            Map.entry("gateway", "06_GATEWAY"),
            Map.entry("general code and runtime worker", "05_WORKFORCE"),
            Map.entry("general engineering", "05_WORKFORCE"),
            Map.entry("workforce", "05_WORKFORCE"),
            Map.entry("knowledge", "07_KNOWLEDGE"),
            Map.entry("intelligence", "10_INTELLIGENCE"),
            Map.entry("execution", "14_EXECUTION"),
            Map.entry("observation", "15_OBSERVATION"),
            Map.entry("security", "04_DEFENSE_AND_SECURITY"),
            Map.entry("defense", "04_DEFENSE_AND_SECURITY"),
            Map.entry("treasury", "11_ECONOMY_AND_TREASURY"),
            Map.entry("finance", "11_ECONOMY_AND_TREASURY"),
            Map.entry("economy", "11_ECONOMY_AND_TREASURY"),
            Map.entry("network", "12_NETWORK_AND_NODES"),
            Map.entry("node", "12_NETWORK_AND_NODES"),
            Map.entry("product", "13_PRODUCTS_AND_SERVICES"),
            Map.entry("service", "13_PRODUCTS_AND_SERVICES"),
            Map.entry("data", "09_DATA"),
            Map.entry("library", "08_LIBRARY"),
            Map.entry("governance", "02_GOVERNANCE"),
            Map.entry("judiciary", "03_JUDICIARY")
    );

    private final ObjectMapper json;
    private final HttpClient http;
    private final String token;
    private final String repository;
    private final String ref;
    private final Map<String, CachedDocument> cache = new ConcurrentHashMap<>();

    @Autowired
    public GitHubCanonicalInstitutionalRoleGrounding(
            ObjectMapper json,
            RepositoryCredentialAuthority repositoryCredentials,
            @Value("${METATRON_INSTITUTION_REPOSITORY:kelvinka38/metatron-institution}") String repository,
            @Value("${METATRON_INSTITUTION_REF:main}") String ref) {
        this(json, HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build(), repositoryCredentials.tokenOrEmpty(), repository, ref);
    }

    GitHubCanonicalInstitutionalRoleGrounding(
            ObjectMapper json, HttpClient http, String token, String repository, String ref) {
        this.json = json;
        this.http = http;
        this.token = token == null ? "" : token.trim();
        this.repository = require(repository, "repository");
        this.ref = require(ref, "ref");
    }

    @Override
    public Grounding resolve(String roleRef, String positionRef, String requestedRole, String userMessage) {
        String domain = resolveDomain(roleRef, positionRef, requestedRole);
        if (domain == null) {
            return Grounding.unavailable("no-canonical-domain-binding");
        }

        String sotPath = canonicalSotPath(domain);
        Document sot = fetch(sotPath);
        if (sot == null) {
            return Grounding.unavailable("canonical-sot-unavailable:" + sotPath);
        }

        List<Document> documents = new ArrayList<>();
        documents.add(sot);

        String message = normalize(userMessage);
        if ("06_GATEWAY".equals(domain)) {
            if (containsAny(message, "contract", "auth", "identity", "ingress", "egress", "channel", "route", "meeting", "operator")) {
                Document contracts = fetch(domain + "/CONTRACTS.md");
                if (contracts != null) documents.add(contracts);
            }
            if (containsAny(message, "production", "deploy", "runtime", "architecture", "envoy", "cloudflare", "recovery", "audit", "build", "fix")) {
                Document architecture = fetch(domain + "/GATEWAY_PRODUCTION_ARCHITECTURE.md");
                if (architecture != null) documents.add(architecture);
            }
        }

        String query = String.join(" ", safe(roleRef), safe(positionRef), safe(requestedRole), safe(userMessage));
        StringBuilder context = new StringBuilder();
        List<String> evidence = new ArrayList<>();
        int remaining = MAX_GROUNDING_CHARS;
        for (Document document : documents) {
            if (remaining <= 0) break;
            String excerpt = relevantSections(document.content(), query, sotPath.equals(document.path()), remaining);
            if (excerpt.isBlank()) continue;
            context.append("CANONICAL SOURCE\n")
                    .append("repository=").append(repository).append('\n')
                    .append("ref=").append(ref).append('\n')
                    .append("path=").append(document.path()).append('\n')
                    .append("blob_sha=").append(document.sha()).append('\n')
                    .append("content=\n").append(excerpt).append("\n\n");
            evidence.add("institutional-source:" + repository + "@" + ref + ":" + document.path()
                    + ":blob=" + document.sha());
            remaining = MAX_GROUNDING_CHARS - context.length();
        }
        if (context.isEmpty()) {
            return Grounding.unavailable("canonical-grounding-empty:" + sotPath);
        }
        return Grounding.available(domain, context.toString().trim(), List.copyOf(evidence));
    }

    static String canonicalSotPath(String domain) {
        if ("05_WORKFORCE".equals(domain)) return "05_WORKFORCE/WORKFORCE_SOT.md";
        return domain + "/SOT.md";
    }

    private Document fetch(String path) {
        CachedDocument cached = cache.get(path);
        Instant now = Instant.now();
        if (cached != null && now.isBefore(cached.expiresAt())) return cached.document();

        try {
            String endpoint = "https://api.github.com/repos/" + repository + "/contents/" + path
                    + "?ref=" + URLEncoder.encode(ref, StandardCharsets.UTF_8);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(HTTP_TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "metatron-workforce-institutional-grounding")
                    .GET();
            if (!token.isBlank()) request.header("Authorization", "Bearer " + token);

            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return null;
            JsonNode body = json.readTree(response.body());
            String sha = body.path("sha").asText("");
            String encoding = body.path("encoding").asText("");
            String encoded = body.path("content").asText("");
            if (sha.isBlank() || encoded.isBlank() || !"base64".equalsIgnoreCase(encoding)) return null;
            String content = new String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8);
            if (content.isBlank()) return null;
            Document document = new Document(path, sha, content);
            cache.put(path, new CachedDocument(document, now.plus(CACHE_TTL)));
            return document;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception failure) {
            return null;
        }
    }

    private static String relevantSections(String content, String query, boolean canonicalSot, int maxChars) {
        if (content == null || content.isBlank() || maxChars <= 0) return "";
        Set<String> queryTokens = tokens(query);
        List<ScoredSection> sections = new ArrayList<>();
        String[] chunks = SECTION.split(content);
        for (int index = 0; index < chunks.length; index++) {
            String chunk = chunks[index].trim();
            if (chunk.isBlank()) continue;
            String heading = chunk.lines().findFirst().orElse("");
            int score = overlap(queryTokens, tokens(chunk));
            if (index == 0) score += 30;
            if (canonicalSot && mandatorySotHeading(heading)) score += 100;
            sections.add(new ScoredSection(index, score, chunk));
        }
        sections.sort(Comparator.comparingInt(ScoredSection::score).reversed()
                .thenComparingInt(ScoredSection::index));

        List<ScoredSection> selected = new ArrayList<>();
        int used = 0;
        for (ScoredSection section : sections) {
            if (section.score() <= 0 && !selected.isEmpty()) continue;
            int cost = section.content().length() + 2;
            if (used + cost > maxChars && !selected.isEmpty()) continue;
            selected.add(section);
            used += cost;
            if (used >= maxChars) break;
        }
        selected.sort(Comparator.comparingInt(ScoredSection::index));
        String joined = selected.stream().map(ScoredSection::content)
                .reduce((left, right) -> left + "\n\n" + right).orElse("");
        return joined.length() <= maxChars ? joined : joined.substring(0, maxChars);
    }

    private static boolean mandatorySotHeading(String heading) {
        String h = normalize(heading);
        return h.contains("purpose")
                || h.contains("ownership boundary")
                || h.contains("continuous ownership model")
                || h.contains("functional organization")
                || h.contains("workforce staffing model")
                || h.contains("non-negotiable invariants");
    }

    private static String resolveDomain(String roleRef, String positionRef, String requestedRole) {
        String combined = normalize(String.join(" ", safe(roleRef), safe(positionRef), safe(requestedRole)));
        for (Map.Entry<String, String> entry : DOMAIN_BINDINGS.entrySet()) {
            if (combined.contains(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private static Set<String> tokens(String value) {
        Set<String> result = new HashSet<>();
        for (String token : normalize(value).split("[^\\p{L}\\p{N}_-]+")) {
            if (token.length() >= 3) result.add(token);
        }
        return result;
    }

    private static int overlap(Set<String> left, Set<String> right) {
        int result = 0;
        for (String token : left) if (right.contains(token)) result++;
        return result;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static String normalize(String value) {
        return safe(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
        return value.trim();
    }

    private record Document(String path, String sha, String content) {}
    private record CachedDocument(Document document, Instant expiresAt) {}
    private record ScoredSection(int index, int score, String content) {}
}
