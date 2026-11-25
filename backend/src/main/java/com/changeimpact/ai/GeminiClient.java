package com.changeimpact.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/**
 * Minimal Google Gemini REST client (generateContent with a JSON response schema, batch embeddings).
 *
 * <p>Deliberately thin and explicit: every call records the model that answered, token usage and latency; transient
 * provider errors (429, 5xx, timeouts) are retried once and then the fallback model is tried, so one overloaded model
 * does not block reviews. Requests go through the JVM's proxy settings when present. The API key comes from
 * {@code GEMINI_API_KEY}; when it is blank, AI features are off ({@code app.ai.enabled=false}).
 */
@Component
@Slf4j
public class GeminiClient {

    public record Generation(JsonNode json, String text, String model, int promptTokens, int outputTokens, long latencyMs) {}

    public static class AiUnavailableException extends RuntimeException {
        public AiUnavailableException(String message) { super(message); }
    }

    /** A daily quota is used up: retrying within minutes cannot succeed, so callers fail fast or degrade. */
    public static class AiQuotaExceededException extends AiUnavailableException {
        public AiQuotaExceededException(String message) { super(message); }
    }

    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final java.util.regex.Pattern RETRY_DELAY = java.util.regex.Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"");
    private static final java.util.regex.Pattern RETRY_IN = java.util.regex.Pattern.compile("[Pp]lease retry in ((?:\\d+h)?(?:\\d+m)?)");
    /** Recent query embeddings: the same question or search is not embedded (and charged) twice. */
    private final Map<String, float[]> queryCache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, float[]> e) { return size() > 512; }
    });

    /** Texts embedded per minute (Gemini's free tier counts every text of a batch: 100/min). */
    private final int embedPerMinute;
    private final java.util.ArrayDeque<long[]> embedWindow = new java.util.ArrayDeque<>(); // {timeMillis, count}

    private final boolean enabled;
    private final String apiKey, chatModel, fallbackModel, embeddingModel;
    private final int dimensions;
    private final RestClient http;

    public GeminiClient(@Value("${app.ai.enabled:false}") boolean enabled,
                        @Value("${spring.ai.google.genai.api-key:}") String apiKey,
                        @Value("${app.ai.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl,
                        @Value("${app.ai.chat-model:gemini-3.5-flash}") String chatModel,
                        @Value("${app.ai.fallback-model:gemini-3.1-flash-lite}") String fallbackModel,
                        @Value("${app.ai.embedding-model:gemini-embedding-001}") String embeddingModel,
                        @Value("${app.ai.embedding-dimensions:768}") int dimensions,
                        @Value("${app.ai.timeout-seconds:90}") int timeoutSeconds,
                        @Value("${app.ai.embed-per-minute:100}") int embedPerMinute) {
        this.embedPerMinute = Math.max(1, embedPerMinute);
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.chatModel = chatModel;
        this.fallbackModel = fallbackModel;
        this.embeddingModel = embeddingModel;
        this.dimensions = dimensions;
        HttpClient client = HttpClient.newBuilder().proxy(ProxySelector.getDefault())
                .connectTimeout(Duration.ofSeconds(15)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("x-goog-api-key", apiKey == null ? "" : apiKey).build();
    }

    public boolean enabled() { return enabled; }
    public String chatModel() { return chatModel; }
    public String embeddingModel() { return embeddingModel; }

    /** JSON answer constrained by {@code schema} (Gemini's OpenAPI-subset schema). */
    public Generation generateJson(String system, String prompt, Map<String, Object> schema, double temperature) {
        requireEnabled();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))));
        body.put("generationConfig", Map.of("responseMimeType", "application/json", "responseSchema", schema,
                "temperature", temperature, "maxOutputTokens", 8192));
        List<String> attempts = new ArrayList<>(List.of(chatModel, chatModel));
        if (fallbackModel != null && !fallbackModel.isBlank() && !fallbackModel.equals(chatModel)) attempts.add(fallbackModel);
        RuntimeException last = null;
        for (int i = 0; i < attempts.size(); i++) {
            String model = attempts.get(i);
            long start = System.nanoTime();
            try {
                JsonNode res = JSON.readTree(http.post().uri("/models/{m}:generateContent", model)
                        .contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsString(body))
                        .retrieve().body(String.class));
                long ms = (System.nanoTime() - start) / 1_000_000;
                JsonNode cand = res.path("candidates").path(0);
                String finish = cand.path("finishReason").asString("");
                StringBuilder text = new StringBuilder();
                for (JsonNode part : cand.path("content").path("parts")) text.append(part.path("text").asString(""));
                if (text.isEmpty()) throw new AiUnavailableException("Empty answer from " + model + " (finishReason " + finish + ")");
                JsonNode usage = res.path("usageMetadata");
                JsonNode parsed;
                try {
                    parsed = JSON.readTree(text.toString());
                } catch (Exception e) {
                    throw new AiUnavailableException("Model " + model + " returned invalid JSON (finishReason " + finish + ")");
                }
                return new Generation(parsed, text.toString(), res.path("modelVersion").asString(model),
                        usage.path("promptTokenCount").asInt(0), usage.path("candidatesTokenCount").asInt(0), ms);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                last = new AiUnavailableException("Gemini " + model + " answered " + status + ": " + abbreviate(e.getResponseBodyAsString()));
                if (status != 429 && status < 500) throw last;          // 400/401/403/404: not transient
                if (status == 429 && dailyQuotaExhausted(e.getResponseBodyAsString())) {
                    last = new AiQuotaExceededException(quotaMessage(model, e.getResponseBodyAsString()));
                    while (i + 1 < attempts.size() && attempts.get(i + 1).equals(model)) i++; // the same model cannot answer today
                }
                log.warn("Gemini {} answered {} (attempt {}), retrying", model, status, i + 1);
            } catch (AiUnavailableException e) {
                last = e;
                log.warn("Gemini {}: {} (attempt {})", model, e.getMessage(), i + 1);
            } catch (Exception e) { // timeouts, connection errors
                last = new AiUnavailableException("Gemini " + model + " unreachable: " + e.getMessage());
                log.warn("Gemini {} failed (attempt {}): {}", model, i + 1, e.getMessage());
            }
            sleep(i == 0 ? 1500 : 500);
        }
        throw last;
    }

    /** Embeddings (unit-normalised) for documents ({@code RETRIEVAL_DOCUMENT}) or a query ({@code RETRIEVAL_QUERY}). */
    public List<float[]> embed(List<String> texts, String taskType) {
        requireEnabled();
        if ("RETRIEVAL_QUERY".equals(taskType) && texts.size() == 1) {
            String key = embeddingModel + "\u0000" + texts.get(0);
            float[] cached = queryCache.get(key);
            if (cached != null) return List.of(cached);
            List<float[]> v = embedUncached(texts, taskType);
            queryCache.put(key, v.get(0));
            return v;
        }
        return embedUncached(texts, taskType);
    }

    private List<float[]> embedUncached(List<String> texts, String taskType) {
        List<float[]> out = new ArrayList<>();
        // Bulk document embedding (indexing) leaves a tenth of the per-minute budget to interactive queries, so a search
        // never waits for an index build to finish.
        int budget = "RETRIEVAL_QUERY".equals(taskType) ? embedPerMinute : Math.max(1, embedPerMinute - Math.max(1, embedPerMinute / 10));
        int batchSize = Math.min(100, budget);
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> batch = texts.subList(from, Math.min(texts.size(), from + batchSize));
            throttle(batch.size(), budget);
            List<Map<String, Object>> requests = new ArrayList<>();
            for (String t : batch) {
                requests.add(Map.of("model", "models/" + embeddingModel, "taskType", taskType,
                        "outputDimensionality", dimensions, "content", Map.of("parts", List.of(Map.of("text", t)))));
            }
            JsonNode res = null;
            String lastError = "";
            for (int attempt = 0; attempt < 6 && res == null; attempt++) {
                try {
                    res = JSON.readTree(http.post().uri("/models/{m}:batchEmbedContents", embeddingModel)
                            .contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsString(Map.of("requests", requests)))
                            .retrieve().body(String.class));
                } catch (RestClientResponseException e) {
                    int status = e.getStatusCode().value();
                    if (status != 429 && status < 500) {
                        throw new AiUnavailableException("Gemini embeddings answered " + status + ": " + abbreviate(e.getResponseBodyAsString()));
                    }
                    lastError = status + " " + abbreviate(e.getResponseBodyAsString());
                    if (status == 429 && dailyQuotaExhausted(e.getResponseBodyAsString())) {
                        throw new AiQuotaExceededException(quotaMessage("embedding", e.getResponseBodyAsString()));
                    }
                    long wait = retryDelayMillis(e.getResponseBodyAsString(), 2000L * (attempt + 1));
                    log.warn("Gemini embeddings answered {} (attempt {}), waiting {} ms", status, attempt + 1, wait);
                    sleep(wait);
                } catch (Exception e) {
                    lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                    log.warn("Gemini embeddings failed (attempt {}): {}", attempt + 1, lastError);
                    sleep(2000L * (attempt + 1));
                }
            }
            if (res == null) throw new AiUnavailableException("Gemini embeddings unavailable after retries: " + lastError);
            for (JsonNode e : res.path("embeddings")) {
                JsonNode values = e.path("values");
                float[] v = new float[values.size()];
                double norm = 0;
                for (int i = 0; i < v.length; i++) { v[i] = (float) values.get(i).asDouble(); norm += v[i] * v[i]; }
                norm = Math.sqrt(norm);
                if (norm > 0) for (int i = 0; i < v.length; i++) v[i] /= (float) norm;
                out.add(v);
            }
        }
        if (out.size() != texts.size()) throw new AiUnavailableException("Gemini returned " + out.size() + " embeddings for " + texts.size() + " texts");
        return out;
    }

    /**
     * Waits until sending {@code n} more texts keeps the texts sent in the last minute within {@code limit}
     * (≤ {@link #embedPerMinute}). Waiting releases the lock, so a query with budget left is not held up by an index
     * build that is waiting for its next batch.
     */
    synchronized void throttle(int n, int limit) {
        while (true) {
            long now = System.currentTimeMillis();
            while (!embedWindow.isEmpty() && embedWindow.peekFirst()[0] < now - 60_000) embedWindow.pollFirst();
            long used = embedWindow.stream().mapToLong(e -> e[1]).sum();
            if (used + n <= limit || embedWindow.isEmpty()) {
                embedWindow.addLast(new long[]{now, n});
                return;
            }
            try {
                wait(Math.max(200, embedWindow.peekFirst()[0] + 60_000 - now + 100));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiUnavailableException("Interrupted while waiting for the embedding rate limit");
            }
        }
    }

    /** Gemini names the exhausted quota; daily ones ({@code ...PerDay...}) reset at midnight Pacific time. */
    static boolean dailyQuotaExhausted(String body) {
        return body != null && body.contains("PerDay");
    }

    static String quotaMessage(String what, String body) {
        java.util.regex.Matcher m = RETRY_IN.matcher(body == null ? "" : body);
        String when = m.find() && !m.group(1).isEmpty() ? " (Gemini: retry in " + m.group(1) + ")" : "";
        return "The daily Gemini quota for " + what + " requests is used up" + when
                + "; raise it with a paid plan or wait until it resets.";
    }

    /** Gemini's 429 body says how long to wait ("retryDelay": "34s"); capped at 90 s. */
    static long retryDelayMillis(String body, long fallback) {
        if (body == null) return fallback;
        java.util.regex.Matcher m = RETRY_DELAY.matcher(body);
        return m.find() ? Math.min(90_000, Long.parseLong(m.group(1)) * 1000 + 500) : fallback;
    }

    private void requireEnabled() {
        if (!enabled) throw new AiUnavailableException("AI features are not configured (GEMINI_API_KEY is empty)");
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ");
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** pgvector literal. */
    public static String vectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(','); sb.append(v[i]); }
        return sb.append(']').toString();
    }
}
