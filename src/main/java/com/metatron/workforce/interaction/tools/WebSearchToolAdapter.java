package com.metatron.workforce.interaction.tools;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only Internet capability used by interactive Workforce paths.
 * Search results are enriched with bounded readable source excerpts when fetchable.
 * External content is evidence only and never creates institutional authority.
 */
public final class WebSearchToolAdapter implements ToolAdapter {
    public static final String CAPABILITY = "web.search";

    private static final String BING_ENDPOINT = "https://www.bing.com/search?format=rss&q=";
    private static final String COINGECKO_BTC = "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd,vnd&include_last_updated_at=true";
    private static final String COINBASE_BTC = "https://api.coinbase.com/v2/prices/BTC-USD/spot";
    private static final int SEARCH_RESULT_LIMIT = 5;
    private static final int FETCH_RESULT_LIMIT = 3;
    private static final int MAX_EXCERPT_CHARS = 5000;

    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<%s>(?:<!\\[CDATA\\[(.*?)\\]\\]|(.*?))</%s>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern COINGECKO_USD = Pattern.compile("\\\"usd\\\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern COINGECKO_VND = Pattern.compile("\\\"vnd\\\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern COINGECKO_UPDATED = Pattern.compile("\\\"last_updated_at\\\"\\s*:\\s*([0-9]+)");
    private static final Pattern COINBASE_AMOUNT = Pattern.compile("\\\"amount\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    private final HttpClient client;
    private final Duration timeout;
    private final String endpoint;

    public WebSearchToolAdapter() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build(),
                Duration.ofSeconds(8), BING_ENDPOINT);
    }

    public WebSearchToolAdapter(HttpClient client, Duration timeout) {
        this(client, timeout, BING_ENDPOINT);
    }

    public WebSearchToolAdapter(HttpClient client, Duration timeout, String endpoint) {
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        if (endpoint.isBlank()) throw new IllegalArgumentException("endpoint must not be blank");
    }

    @Override
    public String capability() { return CAPABILITY; }

    @Override
    public ToolResult execute(ToolRequest request) {
        Objects.requireNonNull(request, "request");
        if (!CAPABILITY.equals(request.capability())) return ToolResult.failure(request, "tool capability mismatch");
        String query = request.input() == null ? "" : request.input().trim();
        if (query.isBlank()) return ToolResult.failure(request, "web_search_query_empty");

        if (isBitcoinPriceQuery(query)) {
            ToolResult market = fetchBitcoinPrice(request, query);
            if (market.success()) return market;
        }
        return searchWeb(request, query);
    }

    private ToolResult fetchBitcoinPrice(ToolRequest request, String query) {
        ToolResult primary = fetchBitcoinFromCoinGecko(request, query);
        return primary.success() ? primary : fetchBitcoinFromCoinbase(request, query);
    }

    private ToolResult fetchBitcoinFromCoinGecko(ToolRequest request, String query) {
        try {
            HttpResponse<String> response = get(COINGECKO_BTC);
            if (!ok(response)) return ToolResult.failure(request, "coingecko_http_status:" + response.statusCode());
            String usd = first(COINGECKO_USD, response.body());
            String vnd = first(COINGECKO_VND, response.body());
            String updated = first(COINGECKO_UPDATED, response.body());
            if (usd.isBlank()) return ToolResult.failure(request, "coingecko_price_missing");
            String sourceTime = updated.isBlank() ? Instant.now().toString() : Instant.ofEpochSecond(Long.parseLong(updated)).toString();
            String output = "CURRENT EXTERNAL DATA\n"
                    + "query=" + query + "\n"
                    + "asset=Bitcoin (BTC)\n"
                    + "price_usd=" + usd + "\n"
                    + (vnd.isBlank() ? "" : "price_vnd=" + vnd + "\n")
                    + "source=CoinGecko\n"
                    + "source_url=" + COINGECKO_BTC + "\n"
                    + "source_updated_at=" + sourceTime + "\n"
                    + "retrieved_at=" + Instant.now();
            return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(),
                    true, output, List.of(COINGECKO_BTC));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure(request, "coingecko_interrupted");
        } catch (Exception e) {
            return ToolResult.failure(request, "coingecko_failed:" + e.getClass().getSimpleName());
        }
    }

    private ToolResult fetchBitcoinFromCoinbase(ToolRequest request, String query) {
        try {
            HttpResponse<String> response = get(COINBASE_BTC);
            if (!ok(response)) return ToolResult.failure(request, "coinbase_http_status:" + response.statusCode());
            String usd = first(COINBASE_AMOUNT, response.body());
            if (usd.isBlank()) return ToolResult.failure(request, "coinbase_price_missing");
            String output = "CURRENT EXTERNAL DATA\n"
                    + "query=" + query + "\n"
                    + "asset=Bitcoin (BTC)\n"
                    + "price_usd=" + usd + "\n"
                    + "source=Coinbase\n"
                    + "source_url=" + COINBASE_BTC + "\n"
                    + "retrieved_at=" + Instant.now();
            return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(),
                    true, output, List.of(COINBASE_BTC));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure(request, "coinbase_interrupted");
        } catch (Exception e) {
            return ToolResult.failure(request, "coinbase_failed:" + e.getClass().getSimpleName());
        }
    }

    private ToolResult searchWeb(ToolRequest request, String query) {
        try {
            URI uri = URI.create(endpoint + URLEncoder.encode(query, StandardCharsets.UTF_8));
            HttpResponse<String> response = get(uri.toString());
            if (!ok(response)) return ToolResult.failure(request, "web_search_http_status:" + response.statusCode());

            List<Result> results = parseResults(response.body(), SEARCH_RESULT_LIMIT);
            if (results.isEmpty()) return ToolResult.failure(request, "web_search_no_results");

            StringBuilder output = new StringBuilder("WEB SEARCH RESULTS\nquery=").append(query)
                    .append("\nretrieved_at=").append(Instant.now()).append('\n');
            List<String> evidence = new ArrayList<>();
            int index = 1;
            for (Result result : results) {
                output.append('[').append(index).append("] ").append(result.title()).append('\n')
                        .append("url=").append(result.url()).append('\n')
                        .append("snippet=").append(result.description()).append('\n');
                if (index <= FETCH_RESULT_LIMIT) {
                    String excerpt = fetchReadableExcerpt(result.url());
                    if (!excerpt.isBlank()) output.append("source_excerpt=").append(excerpt).append('\n');
                }
                output.append('\n');
                evidence.add(result.url());
                index++;
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

    private String fetchReadableExcerpt(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return "";
            HttpResponse<String> response = get(url);
            if (!ok(response)) return "";
            String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
            if (!(contentType.contains("text/html") || contentType.contains("text/plain") || contentType.contains("application/xhtml"))) return "";
            String readable = toReadableText(response.body());
            return readable.length() <= MAX_EXCERPT_CHARS ? readable : readable.substring(0, MAX_EXCERPT_CHARS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private HttpResponse<String> get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", "Metatron-Workforce/0.2")
                .header("Accept", "application/json, application/rss+xml, application/xml, text/xml, text/html, text/plain;q=0.9, */*;q=0.1")
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

    private static boolean ok(HttpResponse<?> response) { return response.statusCode() >= 200 && response.statusCode() < 300; }

    private static boolean isBitcoinPriceQuery(String query) {
        String value = query.toLowerCase(Locale.ROOT);
        boolean bitcoin = value.contains("bitcoin") || value.matches(".*\\bbtc\\b.*");
        boolean priceIntent = value.contains("giá") || value.contains("gia ") || value.contains("price")
                || value.contains("bao nhiêu") || value.contains("bao nhieu") || value.contains("hôm nay")
                || value.contains("hom nay") || value.contains("hiện tại") || value.contains("hien tai")
                || value.contains("ngay lúc") || value.contains("ngay luc") || value.contains("now")
                || value.contains("current");
        return bitcoin && priceIntent;
    }

    private static String first(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1) : "";
    }

    static List<String> parseEvidenceUrls(String xml) {
        return parseResults(xml, SEARCH_RESULT_LIMIT).stream().map(Result::url).toList();
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
                .replace("&nbsp;", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private record Result(String title, String url, String description) {}
}
