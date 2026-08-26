package com.metatron.workforce.interaction.tools;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only Internet search capability backed by Bing's RSS search endpoint. */
public final class WebSearchToolAdapter implements ToolAdapter {
    public static final String CAPABILITY = "web.search";
    private static final String ENDPOINT = "https://www.bing.com/search?format=rss&q=";
    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<%s>(?:<!\\[CDATA\\[(.*?)\\]\\]|(.*?))</%s>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private final HttpClient client;
    private final Duration timeout;
    private final String endpoint;

    public WebSearchToolAdapter() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), Duration.ofSeconds(8), ENDPOINT);
    }

    public WebSearchToolAdapter(HttpClient client, Duration timeout) {
        this(client, timeout, ENDPOINT);
    }

    public WebSearchToolAdapter(HttpClient client, Duration timeout, String endpoint) {
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        if (endpoint.isBlank()) throw new IllegalArgumentException("endpoint must not be blank");
    }

    @Override
    public String capability() {
        return CAPABILITY;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        Objects.requireNonNull(request, "request");
        if (!CAPABILITY.equals(request.capability())) {
            return ToolResult.failure(request, "tool capability mismatch");
        }
        String query = request.input() == null ? "" : request.input().trim();
        if (query.isBlank()) return ToolResult.failure(request, "web_search_query_empty");

        try {
            URI uri = URI.create(endpoint + URLEncoder.encode(query, StandardCharsets.UTF_8));
            HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("User-Agent", "Metatron-Workforce/0.1")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return ToolResult.failure(request, "web_search_http_status:" + response.statusCode());
            }

            List<Result> results = parseResults(response.body(), 5);
            if (results.isEmpty()) return ToolResult.failure(request, "web_search_no_results");

            StringBuilder output = new StringBuilder("WEB SEARCH RESULTS\\nquery=").append(query).append('\\n');
            List<String> evidence = new ArrayList<>();
            int index = 1;
            for (Result result : results) {
                output.append('[').append(index++).append("] ")
                        .append(result.title()).append('\\n')
                        .append("url=").append(result.url()).append('\\n')
                        .append("snippet=").append(result.description()).append("\\n\\n");
                evidence.add(result.url());
            }
            return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(),
                    true, output.toString().trim(), List.copyOf(evidence));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure(request, "web_search_interrupted");
        } catch (Exception e) {
            return ToolResult.failure(request, "web_search_failed:" + e.getClass().getSimpleName());
        }
    }

    static List<String> parseEvidenceUrls(String xml) {
        return parseResults(xml, 5).stream().map(Result::url).toList();
    }

    private static List<Result> parseResults(String xml, int max) {
        List<Result> results = new ArrayList<>();
        Matcher items = ITEM.matcher(xml == null ? "" : xml);
        while (items.find() && results.size() < max) {
            String item = items.group(1);
            String title = extract(item, "title");
            String url = extract(item, "link");
            String description = extract(item, "description");
            if (!title.isBlank() && !url.isBlank()) {
                results.add(new Result(decode(title), url.trim(), decode(description)));
            }
        }
        return List.copyOf(results);
    }

    private static String extract(String source, String tag) {
        Pattern pattern = Pattern.compile(String.format(TAG.pattern(), Pattern.quote(tag), Pattern.quote(tag)),
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) return "";
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }

    private static String decode(String value) {
        if (value == null) return "";
        return value.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private record Result(String title, String url, String description) {}
}
