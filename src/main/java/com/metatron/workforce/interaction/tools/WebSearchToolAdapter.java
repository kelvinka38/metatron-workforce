package com.metatron.workforce.interaction.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.MathContext;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only Internet capability used by interactive Workforce paths.
 *
 * <p>When a current-information requirement can be bound unambiguously to a structured public
 * source, the adapter acquires that source before spending frontier/search capacity. Otherwise it
 * falls back to search-grounded frontier retrieval, bounded RSS search, and finally a
 * credential-free public-knowledge search. A technically successful fetch is not useful evidence
 * unless it answers the actual requirement and attributes the answer to external sources. External
 * content is evidence only and never creates authority.</p>
 */
public final class WebSearchToolAdapter implements ToolAdapter {
    public static final String CAPABILITY = "web.search";

    private static final String BING_ENDPOINT = "https://www.bing.com/search?format=rss&q=";
    private static final String WIKIPEDIA_ENDPOINT = "https://en.wikipedia.org/w/api.php?action=query&list=search&srnamespace=0&srlimit=5&srprop=snippet%7Ctimestamp&format=json&utf8=1&srsearch=";
    private static final String WIKIPEDIA_ARTICLE = "https://en.wikipedia.org/?curid=";
    private static final String GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
    private static final List<String> GROUNDED_MODELS = List.of(
            "gemini-3.7-flash",
            "gemini-3.6-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite",
            "gemini-2.5-flash");
    private static final String COINGECKO_BTC = "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd,vnd&include_last_updated_at=true";
    private static final String COINBASE_BTC = "https://api.coinbase.com/v2/prices/BTC-USD/spot";
    private static final String KRAKEN_BTC = "https://api.kraken.com/0/public/Ticker?pair=XBTUSD";
    private static final String FRANKFURTER_RATE = "https://api.frankfurter.dev/v2/rate/%s/%s";
    private static final String OPEN_EXCHANGE_LATEST = "https://open.er-api.com/v6/latest/%s";
    private static final String OPEN_METEO_GEOCODE = "https://geocoding-api.open-meteo.com/v1/search?name=%s&count=1&language=en&format=json";
    private static final String OPEN_METEO_CURRENT = "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m&timezone=auto";
    private static final int SEARCH_RESULT_LIMIT = 5;
    private static final int FETCH_RESULT_LIMIT = 5;
    private static final int MAX_EXCERPT_CHARS = 5000;
    private static final Set<String> KNOWN_CURRENCIES = Set.of(
            "USD", "VND", "EUR", "GBP", "JPY", "CNY", "KRW", "SGD", "THB", "AUD", "CAD",
            "CHF", "HKD", "NZD", "INR", "IDR", "MYR", "PHP", "TWD", "AED", "SAR");
    private static final Set<String> QUERY_STOP_WORDS = Set.of(
            "the", "and", "for", "with", "from", "this", "that", "what", "how", "much", "about",
            "current", "currently", "latest", "today", "now", "data", "source", "sources", "use", "using",
            "check", "answer", "information", "external", "reality", "please", "new", "fresh",
            "tra", "cuu", "kiem", "dung", "su", "lieu", "moi", "nhat", "neu", "nguon", "cho", "bao", "nhieu",
            "khoang", "hien", "tai", "bay", "gio", "ngay", "luc", "nay", "nao", "va", "cua", "dang", "roi", "gi", "ai", "loi");

    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<%s>(?:<!\\[CDATA\\[(.*?)\\]\\]|(.*?))</%s>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern COINGECKO_USD = Pattern.compile("\\\"usd\\\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern COINGECKO_VND = Pattern.compile("\\\"vnd\\\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern COINGECKO_UPDATED = Pattern.compile("\\\"last_updated_at\\\"\\s*:\\s*([0-9]+)");
    private static final Pattern COINBASE_AMOUNT = Pattern.compile("\\\"amount\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern EXPLICIT_CURRENCY_PAIR = Pattern.compile(
            "(?i)\\b([a-z]{3})\\b\\s*(?:/|->|to|vs\\.?|versus)\\s*\\b([a-z]{3})\\b");
    private static final Pattern WEATHER_LOCATION = Pattern.compile(
            "(?iu)(?:weather|thời\\s*tiết).*?(?:\\bin\\b|\\bfor\\b|ở|tại)\\s+([^?.,;]+)");

    private final HttpClient client;
    private final Duration timeout;
    private final String endpoint;
    private final String publicKnowledgeEndpoint;
    private final String publicKnowledgeArticleEndpoint;
    private final ObjectMapper mapper = new ObjectMapper();

    public WebSearchToolAdapter() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build(),
                Duration.ofSeconds(8), BING_ENDPOINT, WIKIPEDIA_ENDPOINT, WIKIPEDIA_ARTICLE);
    }

    public WebSearchToolAdapter(HttpClient client, Duration timeout) {
        this(client, timeout, BING_ENDPOINT, WIKIPEDIA_ENDPOINT, WIKIPEDIA_ARTICLE);
    }

    public WebSearchToolAdapter(HttpClient client, Duration timeout, String endpoint) {
        this(client, timeout, endpoint, "", WIKIPEDIA_ARTICLE);
    }

    WebSearchToolAdapter(HttpClient client, Duration timeout, String endpoint, String publicKnowledgeEndpoint) {
        this(client, timeout, endpoint, publicKnowledgeEndpoint, WIKIPEDIA_ARTICLE);
    }

    WebSearchToolAdapter(HttpClient client, Duration timeout, String endpoint, String publicKnowledgeEndpoint,
                         String publicKnowledgeArticleEndpoint) {
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.publicKnowledgeEndpoint = publicKnowledgeEndpoint == null ? "" : publicKnowledgeEndpoint.trim();
        this.publicKnowledgeArticleEndpoint = publicKnowledgeArticleEndpoint == null ? "" : publicKnowledgeArticleEndpoint.trim();
        if (endpoint.isBlank()) throw new IllegalArgumentException("endpoint must not be blank");
    }

    @Override public String capability() { return CAPABILITY; }

    @Override
    public ToolResult execute(ToolRequest request) {
        Objects.requireNonNull(request, "request");
        if (!CAPABILITY.equals(request.capability())) return ToolResult.failure(request, "tool capability mismatch");
        String query = request.input() == null ? "" : request.input().trim();
        if (query.isBlank()) return ToolResult.failure(request, "web_search_query_empty");

        if (bitcoinStructuredSourceEligible(query)) {
            ToolResult market = fetchBitcoinPrice(request, query);
            if (market.success()) return market;
        }

        CurrencyPair pair = currencyPair(query);
        if (pair != null && isExchangeRateQuery(query)) {
            ToolResult rate = fetchExchangeRate(request, query, pair);
            if (rate.success()) return rate;
        }

        String weatherLocation = weatherLocation(query);
        if (!weatherLocation.isBlank()) {
            ToolResult weather = fetchCurrentWeather(request, query, weatherLocation);
            if (weather.success()) return weather;
        }

        ToolResult grounded = searchGrounded(request, query);
        if (grounded.success()) return grounded;

        ToolResult web = searchWeb(request, query);
        if (web.success()) return web;

        ToolResult publicKnowledge = searchPublicKnowledge(request, query);
        if (publicKnowledge.success()) return publicKnowledge;

        String compactQuery = compactSearchQuery(query);
        if (!compactQuery.isBlank() && !compactQuery.equalsIgnoreCase(query)) {
            ToolResult compactGrounded = searchGrounded(request, compactQuery);
            if (compactGrounded.success()) return compactGrounded;
            ToolResult compactWeb = searchWeb(request, compactQuery);
            if (compactWeb.success()) return compactWeb;
            ToolResult compactPublicKnowledge = searchPublicKnowledge(request, compactQuery);
            if (compactPublicKnowledge.success()) return compactPublicKnowledge;
            return ToolResult.failure(request, "fresh_search_exhausted:grounded=" + grounded.output()
                    + ";web=" + web.output()
                    + ";public_knowledge=" + publicKnowledge.output()
                    + ";compact_grounded=" + compactGrounded.output()
                    + ";compact_web=" + compactWeb.output()
                    + ";compact_public_knowledge=" + compactPublicKnowledge.output());
        }
        return ToolResult.failure(request, "fresh_search_exhausted:grounded=" + grounded.output()
                + ";web=" + web.output() + ";public_knowledge=" + publicKnowledge.output());
    }

    private ToolResult searchGrounded(ToolRequest request, String query) {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) return ToolResult.failure(request, "grounded_search_credential_unavailable");
        String configured = System.getenv("GEMINI_GROUNDED_SEARCH_MODEL");
        Set<String> models = new LinkedHashSet<>();
        if (configured != null && !configured.isBlank()) models.add(configured.trim());
        models.addAll(GROUNDED_MODELS);

        List<String> failures = new ArrayList<>();
        try {
            String prompt = "Answer this information requirement using current external reality. Search the web. "
                    + "Return a direct factual answer useful to the Human. Explicitly name the subject and answer type from the requirement so the result remains self-contained. "
                    + "When the requirement asks for a current measurement, rate, condition, status, person, version, or other observable value, include the concrete current value or condition and its units/context. "
                    + "Use only facts supported by the search grounding. If the search results are off-topic, stale, or insufficient to answer the requirement directly, output exactly INSUFFICIENT_EVIDENCE. "
                    + "Requirement: " + query;
            String body = mapper.writeValueAsString(Map.of(
                    "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))),
                    "tools", List.of(Map.of("google_search", Map.of()))
            ));
            for (String model : models) {
                HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(GEMINI_ENDPOINT.formatted(
                                URLEncoder.encode(model, StandardCharsets.UTF_8))))
                        .timeout(Duration.ofSeconds(Math.max(20, timeout.toSeconds())))
                        .header("Content-Type", "application/json")
                        .header("x-goog-api-key", apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (!ok(response)) {
                    failures.add(model + ":http_" + response.statusCode());
                    continue;
                }
                JsonNode root = mapper.readTree(response.body());
                JsonNode candidate = root.path("candidates").path(0);
                JsonNode groundingMetadata = candidate.path("groundingMetadata");
                String answer = candidateText(candidate);
                List<String> refs = groundingUrls(groundingMetadata);
                if (answer.isBlank()) {
                    failures.add(model + ":empty_answer");
                    continue;
                }
                if (refs.isEmpty()) {
                    failures.add(model + ":no_grounding_urls");
                    continue;
                }
                if (looksLikeInsufficientAnswer(answer)) {
                    failures.add(model + ":insufficient_answer");
                    continue;
                }
                List<VerifiedSource> verified = verifiedSources(query, refs);
                if (verified.isEmpty()) {
                    failures.add(model + ":source_body_relevance_rejected");
                    continue;
                }
                List<String> verifiedRefs = verified.stream().map(VerifiedSource::url).toList();
                StringBuilder output = new StringBuilder("GROUNDED WEB ANSWER\nquery=").append(query)
                        .append("\nanswer=").append(answer)
                        .append("\nsearch_queries=").append(groundingQueryText(groundingMetadata))
                        .append("\nsource_urls=").append(verifiedRefs)
                        .append("\nretrieved_at=").append(Instant.now())
                        .append("\nprovider=google-search-grounding\nmodel=").append(model);
                for (VerifiedSource source : verified) {
                    output.append("\nsource_excerpt=").append(source.excerpt());
                }
                return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(),
                        true, output.toString(), verifiedRefs);
            }
            return ToolResult.failure(request, "grounded_search_no_sufficient_grounded_answer:" + failures);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure(request, "grounded_search_interrupted");
        } catch (Exception e) {
            return ToolResult.failure(request, "grounded_search_failed:" + e.getClass().getSimpleName());
        }
    }

    private static String candidateText(JsonNode candidate) {
        JsonNode parts = candidate.path("content").path("parts");
        if (!parts.isArray()) return "";
        StringBuilder text = new StringBuilder();
        for (JsonNode part : parts) {
            String value = part.path("text").asText("").trim();
            if (value.isBlank()) continue;
            if (!text.isEmpty()) text.append('\n');
            text.append(value);
        }
        return text.toString().trim();
    }

    private static List<String> groundingUrls(JsonNode groundingMetadata) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        JsonNode chunks = groundingMetadata.path("groundingChunks");
        if (chunks.isArray()) {
            for (JsonNode chunk : chunks) {
                String uri = chunk.path("web").path("uri").asText("").trim();
                if (uri.startsWith("https://") || uri.startsWith("http://")) refs.add(uri);
            }
        }
        return List.copyOf(refs);
    }

    static String groundingQueryText(JsonNode groundingMetadata) {
        JsonNode queries = groundingMetadata.path("webSearchQueries");
        if (!queries.isArray()) return "";
        StringBuilder out = new StringBuilder();
        for (JsonNode query : queries) {
            String value = query.asText("").trim();
            if (value.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(value);
        }
        return out.toString().trim();
    }

    static boolean looksLikeInsufficientAnswer(String answer) {
        String v = answer == null ? "" : answer.toLowerCase(Locale.ROOT);
        return v.isBlank()
                || v.contains("insufficient_evidence") || v.contains("insufficient evidence")
                || v.contains("i cannot provide") || v.contains("i can't provide")
                || v.contains("i am unable") || v.contains("i'm unable")
                || v.contains("insufficient information") || v.contains("not enough information")
                || v.contains("does not provide") || v.contains("cannot determine")
                || v.contains("không thể cung cấp") || v.contains("không đủ thông tin")
                || v.contains("chưa đủ thông tin") || v.contains("không thể xác định")
                || v.contains("không có đủ bằng chứng");
    }

    static boolean materiallyRelevant(String query, String evidenceText) {
        Set<String> subject = subjectTokens(query);
        if (subject.isEmpty()) return true;
        Set<String> evidence = subjectTokens(evidenceText);
        int overlap = 0;
        for (String token : subject) if (evidence.contains(token)) overlap++;
        int required = Math.min(2, subject.size());
        return overlap >= required;
    }

    static String compactSearchQuery(String query) {
        Set<String> tokens = subjectTokens(query);
        if (tokens.isEmpty()) return query == null ? "" : query.trim();
        return String.join(" ", tokens.stream().limit(8).toList());
    }

    private static Set<String> subjectTokens(String value) {
        String folded = fold(value);
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : folded.split("[^a-z0-9]+")) {
            if (token.length() < 3 || QUERY_STOP_WORDS.contains(token)) continue;
            tokens.add(token);
        }
        return tokens;
    }

    private static String fold(String value) {
        if (value == null || value.isBlank()) return "";
        String folded = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd')
                .toLowerCase(Locale.ROOT)
                .trim();
        return folded
                .replaceAll("\\btong\\s+thong\\b", "president")
                .replaceAll("\\bphien\\s+ban\\b", "version");
    }

    private ToolResult fetchBitcoinPrice(ToolRequest request, String query) {
        ToolResult primary = fetchBitcoinFromCoinGecko(request, query);
        if (primary.success()) return primary;
        ToolResult secondary = fetchBitcoinFromCoinbase(request, query);
        if (secondary.success()) return secondary;
        return fetchBitcoinFromKraken(request, query);
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
            if (requiresCurrency(query, "VND") && vnd.isBlank()) {
                OpenRateObservation fx = fetchOpenRate("USD", "VND");
                if (fx == null) return ToolResult.failure(request, "coingecko_vnd_missing_and_fx_unavailable");
                vnd = convert(usd, fx.rate());
                return bitcoinResult(request, query, usd, vnd, "CoinGecko + ExchangeRate-API Open Access",
                        sourceTime, List.of(COINGECKO_BTC, fx.sourceUrl()), "fx_source_updated_at=" + fx.updatedAt());
            }
            return bitcoinResult(request, query, usd, vnd, "CoinGecko", sourceTime, List.of(COINGECKO_BTC), "");
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
            return bitcoinUsdFallbackResult(request, query, usd, "Coinbase", COINBASE_BTC);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure(request, "coinbase_interrupted");
        } catch (Exception e) {
            return ToolResult.failure(request, "coinbase_failed:" + e.getClass().getSimpleName());
        }
    }

    private ToolResult fetchBitcoinFromKraken(ToolRequest request, String query) {
        try {
            HttpResponse<String> response = get(KRAKEN_BTC);
            if (!ok(response)) return ToolResult.failure(request, "kraken_http_status:" + response.statusCode());
            JsonNode root = mapper.readTree(response.body());
            JsonNode result = root.path("result");
            if (!result.isObject() || result.isEmpty()) return ToolResult.failure(request, "kraken_price_missing");
            JsonNode ticker = result.fields().next().getValue();
            String usd = ticker.path("c").path(0).asText("").trim();
            if (usd.isBlank()) return ToolResult.failure(request, "kraken_price_missing");
            return bitcoinUsdFallbackResult(request, query, usd, "Kraken", KRAKEN_BTC);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure(request, "kraken_interrupted");
        } catch (Exception e) {
            return ToolResult.failure(request, "kraken_failed:" + e.getClass().getSimpleName());
        }
    }

    private ToolResult bitcoinUsdFallbackResult(ToolRequest request, String query, String usd,
                                                String sourceName, String sourceUrl) throws Exception {
        if (!requiresCurrency(query, "VND")) {
            return bitcoinResult(request, query, usd, "", sourceName, Instant.now().toString(),
                    List.of(sourceUrl), "");
        }
        OpenRateObservation fx = fetchOpenRate("USD", "VND");
        if (fx == null) return ToolResult.failure(request, sourceName.toLowerCase(Locale.ROOT) + "_fx_unavailable");
        String vnd = convert(usd, fx.rate());
        return bitcoinResult(request, query, usd, vnd, sourceName + " + ExchangeRate-API Open Access",
                Instant.now().toString(), List.of(sourceUrl, fx.sourceUrl()),
                "fx_source_updated_at=" + fx.updatedAt());
    }

    private ToolResult bitcoinResult(ToolRequest request, String query, String usd, String vnd,
                                     String sourceName, String sourceTime, List<String> refs, String extra) {
        StringBuilder output = new StringBuilder("CURRENT EXTERNAL DATA\nquery=").append(query)
                .append("\nasset=Bitcoin (BTC)")
                .append("\nprice_usd=").append(usd).append(" USD");
        if (vnd != null && !vnd.isBlank()) output.append("\nprice_vnd=").append(vnd).append(" VND");
        output.append("\nsource=").append(sourceName)
                .append("\nsource_urls=").append(refs)
                .append("\nsource_updated_at=").append(sourceTime);
        if (extra != null && !extra.isBlank()) output.append('\n').append(extra);
        output.append("\nretrieved_at=").append(Instant.now());
        return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(),
                true, output.toString(), List.copyOf(refs));
    }

    private ToolResult fetchExchangeRate(ToolRequest request, String query, CurrencyPair pair) {
        ToolResult primary = fetchExchangeRateFromFrankfurter(request, query, pair);
        if (primary.success()) return primary;
        return fetchExchangeRateFromOpenApi(request, query, pair);
    }

    private ToolResult fetchExchangeRateFromFrankfurter(ToolRequest request, String query, CurrencyPair pair) {
        String sourceUrl = FRANKFURTER_RATE.formatted(pair.base(), pair.quote());
        try {
            HttpResponse<String> response = get(sourceUrl);
            if (!ok(response)) return ToolResult.failure(request, "frankfurter_http_status:" + response.statusCode());
            JsonNode root = mapper.readTree(response.body());
            JsonNode rateNode = root.path("rate");
            if (!rateNode.isNumber()) return ToolResult.failure(request, "frankfurter_rate_missing");
            String rate = rateNode.asText();
            String date = root.path("date").asText("");
            String output = "CURRENT EXTERNAL EXCHANGE RATE\nquery=" + query
                    + "\nbase=" + pair.base()
                    + "\nquote=" + pair.quote()
                    + "\nexchange_rate=" + rate + " " + pair.quote() + " per " + pair.base()
                    + (date.isBlank() ? "" : "\nsource_date=" + date)
                    + "\nsource=Frankfurter"
                    + "\nsource_url=" + sourceUrl
                    + "\nretrieved_at=" + Instant.now();
            return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(),
                    true, output, List.of(sourceUrl));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure(request, "frankfurter_interrupted");
        } catch (Exception e) {
            return ToolResult.failure(request, "frankfurter_failed:" + e.getClass().getSimpleName());
        }
    }

    private ToolResult fetchExchangeRateFromOpenApi(ToolRequest request, String query, CurrencyPair pair) {
        try {
            OpenRateObservation observation = fetchOpenRate(pair.base(), pair.quote());
            if (observation == null) return ToolResult.failure(request, "open_exchange_rate_missing");
            String output = "CURRENT EXTERNAL EXCHANGE RATE\nquery=" + query
                    + "\nbase=" + pair.base()
                    + "\nquote=" + pair.quote()
                    + "\nexchange_rate=" + observation.rate().stripTrailingZeros().toPlainString()
                    + " " + pair.quote() + " per " + pair.base()
                    + "\nsource_updated_at=" + observation.updatedAt()
                    + "\nsource=ExchangeRate-API Open Access"
                    + "\nsource_url=" + observation.sourceUrl()
                    + "\nretrieved_at=" + Instant.now();
            return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(),
                    true, output, List.of(observation.sourceUrl()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure(request, "open_exchange_interrupted");
        } catch (Exception e) {
            return ToolResult.failure(request, "open_exchange_failed:" + e.getClass().getSimpleName());
        }
    }

    private OpenRateObservation fetchOpenRate(String base, String quote) throws Exception {
        String sourceUrl = OPEN_EXCHANGE_LATEST.formatted(URLEncoder.encode(base, StandardCharsets.UTF_8));
        HttpResponse<String> response = get(sourceUrl);
        if (!ok(response)) return null;
        JsonNode root = mapper.readTree(response.body());
        if (!"success".equalsIgnoreCase(root.path("result").asText(""))) return null;
        JsonNode rateNode = root.path("rates").path(quote);
        if (!rateNode.isNumber()) return null;
        BigDecimal rate = rateNode.decimalValue();
        if (rate.signum() <= 0) return null;
        String updated = root.path("time_last_update_utc").asText("");
        if (updated.isBlank()) updated = root.path("time_last_update_unix").asText("");
        return new OpenRateObservation(rate, updated, sourceUrl);
    }

    private ToolResult fetchCurrentWeather(ToolRequest request, String query, String location) {
        String geocodeUrl = OPEN_METEO_GEOCODE.formatted(URLEncoder.encode(location, StandardCharsets.UTF_8));
        try {
            HttpResponse<String> geocodeResponse = get(geocodeUrl);
            if (!ok(geocodeResponse)) return ToolResult.failure(request, "weather_geocode_http_status:" + geocodeResponse.statusCode());
            JsonNode place = mapper.readTree(geocodeResponse.body()).path("results").path(0);
            if (!place.path("latitude").isNumber() || !place.path("longitude").isNumber()) {
                return ToolResult.failure(request, "weather_location_not_found");
            }
            double latitude = place.path("latitude").asDouble();
            double longitude = place.path("longitude").asDouble();
            String forecastUrl = OPEN_METEO_CURRENT.formatted(
                    Double.toString(latitude), Double.toString(longitude));
            HttpResponse<String> forecastResponse = get(forecastUrl);
            if (!ok(forecastResponse)) return ToolResult.failure(request, "weather_http_status:" + forecastResponse.statusCode());
            JsonNode root = mapper.readTree(forecastResponse.body());
            JsonNode current = root.path("current");
            if (!current.path("temperature_2m").isNumber()) return ToolResult.failure(request, "weather_current_missing");
            JsonNode units = root.path("current_units");
            String resolved = place.path("name").asText(location);
            String admin1 = place.path("admin1").asText("");
            String country = place.path("country").asText("");
            if (!admin1.isBlank() && !resolved.equalsIgnoreCase(admin1)) resolved += ", " + admin1;
            if (!country.isBlank()) resolved += ", " + country;
            int code = current.path("weather_code").asInt(-1);
            String output = "CURRENT EXTERNAL WEATHER\nquery=" + query
                    + "\nlocation=" + resolved
                    + "\nlatitude=" + latitude
                    + "\nlongitude=" + longitude
                    + "\ntemperature_2m=" + current.path("temperature_2m").asText() + " " + units.path("temperature_2m").asText("°C")
                    + "\napparent_temperature=" + current.path("apparent_temperature").asText() + " " + units.path("apparent_temperature").asText("°C")
                    + "\nrelative_humidity_2m=" + current.path("relative_humidity_2m").asText() + " " + units.path("relative_humidity_2m").asText("%")
                    + "\nweather_code=" + code
                    + "\nweather_condition=" + weatherCondition(code)
                    + "\nwind_speed_10m=" + current.path("wind_speed_10m").asText() + " " + units.path("wind_speed_10m").asText("")
                    + "\nobserved_at=" + current.path("time").asText("")
                    + "\nsource=Open-Meteo"
                    + "\nsource_url=" + forecastUrl
                    + "\ngeocoding_source_url=" + geocodeUrl
                    + "\nretrieved_at=" + Instant.now();
            return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(),
                    true, output, List.of(forecastUrl, geocodeUrl));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure(request, "weather_interrupted");
        } catch (Exception e) {
            return ToolResult.failure(request, "weather_failed:" + e.getClass().getSimpleName());
        }
    }

    private ToolResult searchPublicKnowledge(ToolRequest request, String query) {
        if (publicKnowledgeEndpoint.isBlank()) return ToolResult.failure(request, "public_knowledge_search_disabled");
        try {
            String searchUrl = publicKnowledgeEndpoint + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpResponse<String> response = get(searchUrl);
            if (!ok(response)) return ToolResult.failure(request, "public_knowledge_http_status:" + response.statusCode());
            JsonNode results = mapper.readTree(response.body()).path("query").path("search");
            if (!results.isArray() || results.isEmpty()) return ToolResult.failure(request, "public_knowledge_no_results");

            StringBuilder output = new StringBuilder("PUBLIC KNOWLEDGE SEARCH RESULTS\nquery=")
                    .append(query).append("\nprovider=Wikipedia/MediaWiki")
                    .append("\nsearch_url=").append(searchUrl)
                    .append("\nretrieved_at=").append(Instant.now()).append('\n');
            List<String> evidence = new ArrayList<>();
            int index = 1;
            for (JsonNode item : results) {
                String title = item.path("title").asText("").trim();
                String snippet = toReadableText(item.path("snippet").asText(""));
                long pageId = item.path("pageid").asLong(0L);
                String modified = item.path("timestamp").asText("").trim();
                if (title.isBlank() || pageId <= 0L) continue;
                if (publicKnowledgeArticleEndpoint.isBlank()) continue;
                String url = publicKnowledgeArticleEndpoint + pageId;
                String excerpt = fetchReadableExcerpt(url);
                if (excerpt.isBlank() || !materiallyRelevant(query, excerpt)) continue;
                output.append('[').append(index).append("] ").append(title).append('\n')
                        .append("url=").append(url).append('\n');
                if (!modified.isBlank()) output.append("source_modified_at=").append(modified).append('\n');
                if (!snippet.isBlank()) output.append("snippet=").append(snippet).append('\n');
                output.append("source_excerpt=").append(excerpt).append('\n');
                output.append('\n');
                evidence.add(url);
                index++;
                if (evidence.size() >= SEARCH_RESULT_LIMIT) break;
            }
            if (evidence.isEmpty()) return ToolResult.failure(request, "public_knowledge_no_relevant_results");
            return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(),
                    true, output.toString().trim(), List.copyOf(evidence));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure(request, "public_knowledge_interrupted");
        } catch (Exception e) {
            return ToolResult.failure(request, "public_knowledge_failed:" + e.getClass().getSimpleName());
        }
    }

    private List<VerifiedSource> verifiedSources(String query, List<String> refs) {
        List<VerifiedSource> verified = new ArrayList<>();
        int fetched = 0;
        for (String ref : refs) {
            if (fetched >= FETCH_RESULT_LIMIT) break;
            String excerpt = fetchReadableExcerpt(ref);
            fetched++;
            if (!excerpt.isBlank() && materiallyRelevant(query, excerpt)) {
                verified.add(new VerifiedSource(ref, excerpt));
            }
        }
        return List.copyOf(verified);
    }

    private ToolResult searchWeb(ToolRequest request, String query) {
        try {
            URI uri = URI.create(endpoint + URLEncoder.encode(query, StandardCharsets.UTF_8));
            HttpResponse<String> response = get(uri.toString());
            if (!ok(response)) return ToolResult.failure(request, "web_search_http_status:" + response.statusCode());
            List<Result> results = parseResults(response.body(), SEARCH_RESULT_LIMIT);
            if (results.isEmpty()) return ToolResult.failure(request, "web_search_no_results");

            List<SearchEvidence> relevant = new ArrayList<>();
            int fetched = 0;
            for (Result result : results) {
                String excerpt = "";
                if (fetched < FETCH_RESULT_LIMIT) {
                    excerpt = fetchReadableExcerpt(result.url());
                    fetched++;
                }
                if (!excerpt.isBlank() && materiallyRelevant(query, excerpt)) {
                    relevant.add(new SearchEvidence(result, excerpt));
                }
            }
            if (relevant.isEmpty()) return ToolResult.failure(request, "web_search_no_relevant_results");

            StringBuilder output = new StringBuilder("WEB SEARCH RESULTS\nquery=").append(query).append("\nretrieved_at=").append(Instant.now()).append('\n');
            List<String> evidence = new ArrayList<>();
            int index = 1;
            for (SearchEvidence item : relevant) {
                Result result = item.result();
                output.append('[').append(index).append("] ").append(result.title()).append('\n')
                        .append("url=").append(result.url()).append('\n').append("snippet=").append(result.description()).append('\n');
                output.append("source_excerpt=").append(item.excerpt()).append('\n');
                output.append('\n');
                evidence.add(result.url());
                index++;
            }
            return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(), true, output.toString().trim(), List.copyOf(evidence));
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("User-Agent", "Metatron-Workforce/0.2")
                .header("Accept", "application/json, application/rss+xml, application/xml, text/xml, text/html, text/plain;q=0.9, */*;q=0.1")
                .GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String toReadableText(String value) {
        if (value == null || value.isBlank()) return "";
        String text = value.replaceAll("(?is)<script[^>]*>.*?</script>", " ").replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ").replaceAll("(?is)<!--.*?-->", " ").replaceAll("(?is)<[^>]+>", " ");
        return decode(text).replaceAll("\\s+", " ").trim();
    }

    private static boolean ok(HttpResponse<?> response) { return response.statusCode() >= 200 && response.statusCode() < 300; }

    static boolean bitcoinStructuredSourceEligible(String query) {
        String value = fold(query);
        boolean bitcoin = value.contains("bitcoin") || value.matches(".*\\bbtc\\b.*");
        boolean valueIntent = value.contains("gia") || value.contains("price") || value.contains("value")
                || value.contains("valuation") || value.contains("quote") || value.contains("worth")
                || value.contains("bao nhieu") || value.contains("market");
        boolean freshness = value.contains("hom nay") || value.contains("hien tai") || value.contains("ngay luc")
                || value.contains("today") || value.contains("latest") || value.contains("now") || value.contains("current");
        boolean currencyContext = requiresCurrency(query, "USD") || requiresCurrency(query, "VND");
        return bitcoin && (valueIntent || (freshness && currencyContext));
    }

    private static boolean isExchangeRateQuery(String query) {
        String value = fold(query);
        return value.contains("ty gia") || value.contains("exchange rate") || value.contains("forex")
                || value.contains("rate") || EXPLICIT_CURRENCY_PAIR.matcher(query).find();
    }

    private static CurrencyPair currencyPair(String query) {
        Matcher explicit = EXPLICIT_CURRENCY_PAIR.matcher(query);
        if (explicit.find()) {
            String base = explicit.group(1).toUpperCase(Locale.ROOT);
            String quote = explicit.group(2).toUpperCase(Locale.ROOT);
            if (KNOWN_CURRENCIES.contains(base) && KNOWN_CURRENCIES.contains(quote) && !base.equals(quote)) {
                return new CurrencyPair(base, quote);
            }
        }
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (String token : query.toUpperCase(Locale.ROOT).split("[^A-Z]+")) {
            if (KNOWN_CURRENCIES.contains(token)) codes.add(token);
        }
        if (codes.size() < 2) return null;
        var iterator = codes.iterator();
        return new CurrencyPair(iterator.next(), iterator.next());
    }

    private static boolean requiresCurrency(String query, String currency) {
        if (query == null || query.isBlank()) return false;
        return Pattern.compile("(?i)(?:^|[^A-Z])" + Pattern.quote(currency) + "(?:[^A-Z]|$)")
                .matcher(query).find();
    }

    static String weatherLocation(String query) {
        String folded = fold(query);
        if (!(folded.contains("weather") || folded.contains("thoi tiet"))) return "";
        if (folded.contains("ho chi minh")) return "Ho Chi Minh City";
        Matcher matcher = WEATHER_LOCATION.matcher(query);
        if (matcher.find()) {
            String location = matcher.group(1).trim()
                    .replaceAll("(?iu)\\s+(?:right\\s+now|today|currently|now|thế\\s+nào|hôm\\s+nay|hiện\\s+tại|là\\s+gì).*$", "")
                    .replaceAll("(?iu)\\s+(?:using|with)\\s+(?:fresh|current|latest).*?$", "")
                    .replaceAll("(?iu)\\s+(?:and|và)\\s+(?:cite|provide|include|nêu|cho).*?$", "")
                    .replaceAll("(?iu)^(?:city\\s+of|thành\\s+phố|tp\\.?)\\s+", "")
                    .trim();
            if (!location.isBlank()) return location;
        }
        return "";
    }

    private static String convert(String amount, BigDecimal rate) {
        return new BigDecimal(amount).multiply(rate, MathContext.DECIMAL64).stripTrailingZeros().toPlainString();
    }

    private static String weatherCondition(int code) {
        return switch (code) {
            case 0 -> "clear sky";
            case 1 -> "mainly clear";
            case 2 -> "partly cloudy";
            case 3 -> "overcast";
            case 45, 48 -> "fog";
            case 51, 53, 55, 56, 57 -> "drizzle";
            case 61, 63, 65, 66, 67 -> "rain";
            case 71, 73, 75, 77 -> "snow";
            case 80, 81, 82 -> "rain showers";
            case 85, 86 -> "snow showers";
            case 95, 96, 99 -> "thunderstorm";
            default -> "unknown weather code";
        };
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
        Pattern pattern = Pattern.compile(String.format(TAG.pattern(), Pattern.quote(tag), Pattern.quote(tag)), Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) return "";
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }

    private static String decode(String value) {
        if (value == null) return "";
        return value.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&nbsp;", " ").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private record OpenRateObservation(BigDecimal rate, String updatedAt, String sourceUrl) {}
    private record CurrencyPair(String base, String quote) {}
    private record Result(String title, String url, String description) {}
    private record SearchEvidence(Result result, String excerpt) {}
    private record VerifiedSource(String url, String excerpt) {}
}
