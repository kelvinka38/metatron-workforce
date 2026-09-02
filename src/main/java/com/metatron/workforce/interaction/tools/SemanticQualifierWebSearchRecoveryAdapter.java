package com.metatron.workforce.interaction.tools;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded recovery adapter for fresh/current web requirements whose answer-type qualifiers
 * (for example "latest stable version") are useful for discovery but are not part of the
 * subject identity that source-relevance verification should require literally.
 *
 * <p>The primary {@link WebSearchToolAdapter} remains authoritative and runs first. This adapter
 * is intentionally narrower: it searches with the complete semantic requirement, verifies the
 * fetched public source against qualifier-free subject identity, and additionally requires an
 * answer-shaped observation before returning evidence. It never treats search snippets alone as
 * evidence and never accesses loopback/private destinations in production.</p>
 */
public final class SemanticQualifierWebSearchRecoveryAdapter implements ToolAdapter {
    public static final String CAPABILITY = WebSearchToolAdapter.CAPABILITY;

    private static final String BING_ENDPOINT = "https://www.bing.com/search?format=rss&q=";
    private static final int SEARCH_RESULT_LIMIT = 5;
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_EXCERPT_CHARS = 6_000;

    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION_VALUE = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])v?([0-9]{1,4}\\.[0-9]+(?:\\.[0-9]+){0,3}(?:[-+][a-z0-9.-]+)?)(?:$|[^a-z0-9])");
    private static final Set<String> SUBJECT_STOP_WORDS = Set.of(
            "the", "and", "for", "with", "from", "this", "that", "what", "which", "how", "much", "about",
            "current", "currently", "latest", "today", "now", "new", "fresh", "official", "officially",
            "stable", "version", "versions", "release", "releases", "released", "build", "edition", "lts",
            "data", "source", "sources", "use", "using", "check", "answer", "information", "external", "reality",
            "please", "provide", "provides", "providing", "cite", "download", "downloads", "user", "users",
            "tra", "cuu", "kiem", "dung", "su", "lieu", "moi", "nhat", "neu", "nguon", "cho", "bao", "nhieu",
            "khoang", "hien", "tai", "bay", "gio", "ngay", "luc", "nay", "nao", "va", "cua", "dang", "roi", "gi", "ai", "la");

    private final HttpClient client;
    private final Duration timeout;
    private final String endpoint;
    private final boolean allowLoopbackForTests;

    public SemanticQualifierWebSearchRecoveryAdapter() {
        this(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(4))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                Duration.ofSeconds(8), BING_ENDPOINT, false);
    }

    SemanticQualifierWebSearchRecoveryAdapter(HttpClient client, Duration timeout, String endpoint,
                                              boolean allowLoopbackForTests) {
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.allowLoopbackForTests = allowLoopbackForTests;
        if (endpoint.isBlank()) throw new IllegalArgumentException("endpoint must not be blank");
    }

    @Override
    public String capability() {
        return CAPABILITY;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        Objects.requireNonNull(request, "request");
        if (!CAPABILITY.equals(request.capability())) return ToolResult.failure(request, "tool capability mismatch");
        String query = request.input() == null ? "" : request.input().trim();
        if (query.isBlank()) return ToolResult.failure(request, "semantic_recovery_query_empty");

        AnswerShape shape = answerShape(query);
        if (shape == AnswerShape.UNSUPPORTED) {
            return ToolResult.failure(request, "semantic_recovery_answer_shape_unsupported");
        }
        Set<String> subject = subjectTokens(query);
        if (subject.isEmpty()) return ToolResult.failure(request, "semantic_recovery_subject_missing");

        try {
            String searchUrl = endpoint + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpResponse<InputStream> search = get(searchUrl, true);
            if (!ok(search)) {
                closeQuietly(search.body());
                return ToolResult.failure(request, "semantic_recovery_search_http_status:" + search.statusCode());
            }
            String rss = readBounded(search.body());
            List<Result> results = parseResults(rss, SEARCH_RESULT_LIMIT);
            if (results.isEmpty()) return ToolResult.failure(request, "semantic_recovery_no_results");

            List<VerifiedResult> verified = new ArrayList<>();
            for (Result result : results) {
                if (verified.size() >= SEARCH_RESULT_LIMIT) break;
                String excerpt = fetchReadableExcerpt(result.url());
                if (excerpt.isBlank()) continue;
                String combined = result.title() + " " + result.description() + " " + excerpt;
                if (!subjectRelevant(subject, combined)) continue;
                if (!answersShape(shape, combined)) continue;
                verified.add(new VerifiedResult(result, excerpt));
            }
            if (verified.isEmpty()) return ToolResult.failure(request, "semantic_recovery_no_answer_shaped_relevant_results");

            StringBuilder output = new StringBuilder("SEMANTIC QUALIFIER WEB RECOVERY\n")
                    .append("query=").append(query)
                    .append("\nsubject_tokens=").append(subject)
                    .append("\nanswer_shape=").append(shape)
                    .append("\nretrieved_at=").append(Instant.now()).append('\n');
            List<String> evidence = new ArrayList<>();
            int index = 1;
            for (VerifiedResult item : verified) {
                Result result = item.result();
                output.append('[').append(index++).append("] ").append(result.title()).append('\n')
                        .append("url=").append(result.url()).append('\n')
                        .append("snippet=").append(result.description()).append('\n')
                        .append("source_excerpt=").append(item.excerpt()).append("\n\n");
                evidence.add(result.url());
            }
            return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(),
                    true, output.toString().trim(), List.copyOf(evidence));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ToolResult.failure(request, "semantic_recovery_interrupted");
        } catch (Exception failure) {
            return ToolResult.failure(request, "semantic_recovery_failed:" + failure.getClass().getSimpleName());
        }
    }

    static Set<String> subjectTokens(String value) {
        String folded = fold(value);
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : folded.split("[^a-z0-9]+")) {
            if (token.length() < 3 || SUBJECT_STOP_WORDS.contains(token)) continue;
            tokens.add(token);
        }
        return Set.copyOf(tokens);
    }

    static boolean subjectRelevant(Set<String> subject, String evidenceText) {
        if (subject == null || subject.isEmpty()) return false;
        Set<String> evidence = lexicalTokens(evidenceText);
        int overlap = 0;
        for (String token : subject) if (evidence.contains(token)) overlap++;
        int required = subject.size() == 1 ? 1 : Math.min(2, subject.size());
        return overlap >= required;
    }

    static boolean answersVersionRequirement(String evidenceText) {
        return VERSION_VALUE.matcher(fold(evidenceText)).find();
    }

    private static AnswerShape answerShape(String query) {
        String folded = fold(query);
        if (folded.matches(".*\\b(version|versions|stable|release|releases|lts)\\b.*")) {
            return AnswerShape.VERSION;
        }
        return AnswerShape.UNSUPPORTED;
    }

    private static boolean answersShape(AnswerShape shape, String evidenceText) {
        return switch (shape) {
            case VERSION -> answersVersionRequirement(evidenceText);
            case UNSUPPORTED -> false;
        };
    }

    private String fetchReadableExcerpt(String url) throws Exception {
        HttpResponse<InputStream> response = get(url, false);
        if (!ok(response)) {
            closeQuietly(response.body());
            return "";
        }
        String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
        if (!(contentType.contains("text/html") || contentType.contains("text/plain")
                || contentType.contains("application/xhtml"))) {
            closeQuietly(response.body());
            return "";
        }
        String readable = toReadableText(readBounded(response.body()));
        return readable.length() <= MAX_EXCERPT_CHARS ? readable : readable.substring(0, MAX_EXCERPT_CHARS);
    }

    private HttpResponse<InputStream> get(String url, boolean searchEndpoint) throws Exception {
        URI uri = URI.create(url);
        if (!searchEndpoint && !allowLoopbackForTests && !publicDestination(uri)) {
            throw new IllegalArgumentException("non_public_search_result_destination");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", "Metatron-Workforce/0.2")
                .header("Accept", "application/rss+xml, application/xml, text/xml, text/html, text/plain;q=0.9, */*;q=0.1")
                .GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private static boolean publicDestination(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return false;
        String host = uri.getHost();
        if (host == null || host.isBlank() || "localhost".equalsIgnoreCase(host)) return false;
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
            }
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    private static String readBounded(InputStream input) throws Exception {
        try (InputStream stream = input) {
            byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) throw new IllegalStateException("web_response_too_large");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) return;
        try { stream.close(); } catch (Exception ignored) { }
    }

    private static boolean ok(HttpResponse<?> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private static Set<String> lexicalTokens(String value) {
        String folded = fold(value);
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : folded.split("[^a-z0-9]+")) {
            if (token.length() >= 3) tokens.add(token);
        }
        return tokens;
    }

    private static String fold(String value) {
        if (value == null || value.isBlank()) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd')
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static List<Result> parseResults(String xml, int max) {
        List<Result> results = new ArrayList<>();
        Matcher items = ITEM.matcher(xml == null ? "" : xml);
        while (items.find() && results.size() < max) {
            String item = items.group(1);
            String title = extract(item, "title");
            String url = extract(item, "link");
            String description = extract(item, "description");
            if (!title.isBlank() && !url.isBlank()) results.add(new Result(decode(title), url.trim(), decode(description)));
        }
        return List.copyOf(results);
    }

    private static String extract(String source, String tag) {
        Pattern pattern = Pattern.compile("<" + Pattern.quote(tag) + ">(.*?)</" + Pattern.quote(tag) + ">",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(source == null ? "" : source);
        if (!matcher.find()) return "";
        String value = matcher.group(1);
        if (value.startsWith("<![CDATA[") && value.endsWith("]]>") && value.length() >= 12) {
            value = value.substring(9, value.length() - 3);
        }
        return value;
    }

    private static String toReadableText(String value) {
        if (value == null || value.isBlank()) return "";
        String text = value
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ")
                .replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?is)<[^>]+>", " ");
        return decode(text).replaceAll("\\s+", " ").trim();
    }

    private static String decode(String value) {
        if (value == null) return "";
        return value.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
                .replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private enum AnswerShape { VERSION, UNSUPPORTED }
    private record Result(String title, String url, String description) { }
    private record VerifiedResult(Result result, String excerpt) { }
}
