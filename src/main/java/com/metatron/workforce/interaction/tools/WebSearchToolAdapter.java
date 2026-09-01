package com.metatron.workforce.interaction.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * falls back to search-grounded frontier retrieval and then bounded RSS search. A technically
 * successful fetch is not useful evidence unless it answers the actual requirement and attributes
 * the answer to external sources. External content is evidence only and never creates authority.</p>
 */
public final class WebSearchToolAdapter implements ToolAdapter {
    public static final String CAPABILITY = "web.search";

    private static final String BING_ENDPOINT = "https://www.bing.com/search?format=rss&q=";
    private static final String GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
    private static final List<String> GROUNDED_MODELS = List.of("gemini-3.1-flash-lite", "gemini-2.5-flash");
    private static final String COINGECKO_BTC = "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd,vnd&include_last_updated_at=true";
    private static final String COINBASE_BTC = "https://api.coinbase.com/v2/prices/BTC-USD/spot";
    private static final String FRANKFURTER_RATE = "https://api.frankfurter.dev/v2/rate/%s/%s";
    private static final String OPEN_METEO_GEOCODE = "https://geocoding-api.open-meteo.com/v1/search?name=%s&count=1&language=en&format=json";
    private static final String OPEN_METEO_CURRENT = "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m&timezone=auto";
    private static final int SEARCH_RESULT_LIMIT = 5;
    private static final int FETCH_RESULT_LIMIT = 3;
    private static final int MAX_EXCERPT_CHARS = 5000;
    private static final Set<String> KNOWN_CURRENCIES = Set.of(
            "USD", "VND", "EUR", "GBP", "JPY", "CNY", "KRW", "SGD", "THB", "AUD", "CAD",
            "CHF", "HKD", "NZD", "INR", "IDR", "MYR", "PHP", "TWD", "AED", "SAR");
    private static final Set<String> QUERY_STOP_WORDS = Set.of(
            "the", "and", "for", "with", "from", "this", "that", "what", "how", "much", "about",
            "current", "currently", "latest", "today", "now", "data", "source", "sources", "use", "using",
            "check", "answer", "information", "external", "reality", "please", "new", "fresh",
            "tra", "cuu", "kiem", "dung", "su", "lieu", "moi", "neu", "nguon", "cho", "bao", "nhieu",
            "khoang", "hien", "tai", "bay", "gio", "ngay", "luc", "nay", "nao", "va", "cua");

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
    private final ObjectMapper mapper = new ObjectMapper();

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

    @Override public String capability() { return CAPABILITY; }

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
        return searchWeb(request, query);
    }

    private ToolResult searchGrounded(ToolRequest request, String query) {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) return ToolResult.failure(request, "grounded_search_credential_unavailable");
        String configured = System.getenv("GEMINI_GROUNDED_SEARCH_MODEL");
        Set<String> models = new LinkedHashSet<>();
        if (configured != null && !configured.isBlank()) models.add(configured.trim());
        models.addAll(GROUNDED_MODELS);

        try {
            String prompt = "Answer this information requirement using current external reality. Search the web. "
                    + "Return a direct factual answer useful to the Human. When the requirement asks for a current measurement, rate, condition, status, or other observable value, include the concrete current value or condition and its units/context. "
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
                if (!ok(response)) continue;
                JsonNode root = mapper.readTree(response.body());
                JsonNode candidate = root.path("candidates").path(0);
                String answer = candidateText(candidate);
                List<String> refs = groundingUrls(candidate.path("groundingMetadata"));
                if (answer.isBlank() || refs.isEmpty() || looksLikeInsufficientAnswer(answer)
                        || !materiallyRelevant(query, answer)) continue;
                String output = "GROUNDED WEB ANSWER\nquery=" + query
                        + "\nanswer=" + answer
                        + "\nsource_urls=" + refs
                        + "\nretrieved_at=" + Instant.now()
                        + "\nprovider=google-search-grounding\nmodel=" + model;
                return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(),
                        true, output, refs);
            }
            return ToolResult.failure(request, "grounded_search_no_sufficient_grounded_answer");
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
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd')
                .toLowerCase(Locale.ROOT)
                .trim();
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
            String output = "CURRENT EXTERNAL DATA\nquery=" + query + "\nasset=Bitcoin (BTC)\nprice_usd=" + usd + "\n"
                    + (vnd.isBlank() ? "" : "price_vnd=" + vnd + "\n") + "source=CoinGecko\nsource_url=" + COINGECKO_BTC
                    + "\nsource_updated_at=" + sourceTime + "\nretrieved_at=" + Instant.now();
            return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(), true, output, List.of(COINGECKO_BTC));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return ToolResult.failure(request, "coingecko_interrupted");
        } catch (Exception e) { return ToolResult.failure(request, "coingecko_failed:" + e.getClass().getSimpleName()); }
    }

    private ToolResult fetchBitcoinFromCoinbase(ToolRequest request, String query) {
        try {
            HttpResponse<String> response = get(COINBASE_BTC);
            if (!ok(response)) return ToolResult.failure(request, "coinbase_http_status:" + response.statusCode());
            String usd = first(COINBASE_AMOUNT, response.body());
            if (usd.isBlank()) return ToolResult.failure(request, "coinbase_price_missing");
            String output = "CURRENT EXTERNAL DATA\nquery=" + query + "\nasset=Bitcoin (BTC)\nprice_usd=" + usd
                    + "\nsource=Coinbase\nsource_url=" + COINBASE_BTC + "\nretrieved_at=" + Instant.now();
            return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(), true, output, List.of(COINBASE_BTC));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return ToolResult.failure(request, "coinbase_interrupted");
        } catch (Exception e) { return ToolResult.failure(request, "coinbase_failed:" + e.getClass().getSimpleName()); }
    }

    private ToolResult fetchExchangeRate(ToolRequest request, String query, CurrencyPair pair) {
        String sourceUrl = FRANKFURTER_RATE.formatted(pair.base(), pair.quote());
        try {
            HttpResponse<String> response = get(sourceUrl);
            if (!ok(response)) return ToolResult.failure(request, "exchange_rate_http_status:" + response.statusCode());
            JsonNode root = mapper.readTree(response.body());
            JsonNode rateNode = root.path("rate");
            if (!rateNode.isNumber()) return ToolResult.failure(request, "exchange_rate_missing");
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
            return ToolResult.failure(request, "exchange_rate_interrupted");
        } catch (Exception e) {
            return ToolResult.failure(request, "exchange_rate_failed:" + e.getClass().getSimpleName());
        }
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

    private ToolResult searchWeb(ToolRequest request, String query) {
        try {
            URI uri = URI.create(endpoint + URLEncoder.encode(query, StandardCharsets.UTF_8));
            HttpResponse<String> response = get(uri.toString());
            if (!ok(response)) return ToolResult.failure(request, "web_search_http_status:" + response.statusCode());
            List<Result> results = parseResults(response.body(), SEARCH_RESULT_LIMIT);
            if (results.isEmpty()) return ToolResult.failure(request, "web_search_no_results");
            List<Result> relevant = results.stream()
                    .filter(result -> materiallyRelevant(query, result.title() + " " + result.description()))
                    .toList();
            if (relevant.isEmpty()) return ToolResult.failure(request, "web_search_no_relevant_results");
            StringBuilder output = new StringBuilder("WEB SEARCH RESULTS\nquery=").append(query).append("\nretrieved_at=").append(Instant.now()).append('\n');
            List<String> evidence = new ArrayList<>();
            int index = 1;
            for (Result result : relevant) {
                output.append('[').append(index).append("] ").append(result.title()).append('\n')
                        .append("url=").append(result.url()).append('\n').append("snippet=").append(result.description()).append('\n');
                if (index <= FETCH_RESULT_LIMIT) {
                    String excerpt = fetchReadableExcerpt(result.url());
                    if (!excerpt.isBlank()) output.append("source_excerpt=").append(excerpt).append('\n');
                }
                output.append('\n'); evidence.add(result.url()); index++;
            }
            return new ToolResult(request.requestId(), request.capability(), request.target(), request.operation(), true, output.toString().trim(), List.copyOf(evidence));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return ToolResult.failure(request, "web_search_interrupted");
        } catch (Exception e) { return ToolResult.failure(request, "web_search_failed:" + e.getClass().getSimpleName()); }
    }

    private String fetchReadableExcerpt(String url) {
        try {
            URI uri = URI.create(url); String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return "";
            HttpResponse<String> response = get(url); if (!ok(response)) return "";
            String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
            if (!(contentType.contains("text/html") || contentType.contains("text/plain") || contentType.contains("application/xhtml"))) return "";
            String readable = toReadableText(response.body());
            return readable.length() <= MAX_EXCERPT_CHARS ? readable : readable.substring(0, MAX_EXCERPT_CHARS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return ""; }
        catch (Exception ignored) { return ""; }
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

    private static boolean isBitcoinPriceQuery(String query) {
        String value = fold(query);
        boolean bitcoin = value.contains("bitcoin") || value.matches(".*\\bbtc\\b.*");
        boolean priceIntent = value.contains("gia") || value.contains("price") || value.contains("bao nhieu")
                || value.contains("hom nay") || value.contains("hien tai") || value.contains("ngay luc")
                || value.contains("now") || value.contains("current");
        return bitcoin && priceIntent;
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

    private static String first(Pattern pattern, String value) { Matcher matcher = pattern.matcher(value == null ? "" : value); return matcher.find() ? matcher.group(1) : ""; }
    static List<String> parseEvidenceUrls(String xml) { return parseResults(xml, SEARCH_RESULT_LIMIT).stream().map(Result::url).toList(); }
    private static List<Result> parseResults(String xml, int max) {
        List<Result> results = new ArrayList<>(); Matcher items = ITEM.matcher(xml == null ? "" : xml);
        while (items.find() && results.size() < max) {
            String item = items.group(1), title = extract(item, "title"), url = extract(item, "link"), description = extract(item, "description");
            if (!title.isBlank() && !url.isBlank()) results.add(new Result(decode(title), url.trim(), decode(description)));
        }
        return List.copyOf(results);
    }
    private static String extract(String source, String tag) {
        Pattern pattern = Pattern.compile(String.format(TAG.pattern(), Pattern.quote(tag), Pattern.quote(tag)), Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(source); if (!matcher.find()) return ""; return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }
    private static String decode(String value) {
        if (value == null) return "";
        return value.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&nbsp;", " ").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
    private record CurrencyPair(String base, String quote) {}
    private record Result(String title, String url, String description) {}
}
