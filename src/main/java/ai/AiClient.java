package ai;

import com.google.gson.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Minimal multi-provider chat client for the AI organizer.
 *
 * Supports Google Gemini (native REST) and any OpenAI-compatible endpoint
 * (Groq, OpenRouter). Calls go out via the JDK HttpClient — NOT through Burp's
 * HTTP stack — so they never appear in Proxy history or loop back through the
 * Live handler.
 *
 * Returns the model's raw text completion; JSON extraction/parsing is the
 * caller's responsibility (see {@link AiOrganizer}).
 */
public class AiClient {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final AiConfig config;

    public AiClient(AiConfig config) {
        this.config = config;
    }

    /** Thrown for transport / HTTP / provider errors so the caller can log + skip. */
    public static class AiException extends Exception {
        public AiException(String msg) { super(msg); }
    }

    /**
     * Sends a system + user prompt and returns the assistant's text reply.
     * Blocks for up to ~30s; always call off the EDT.
     */
    public String complete(String systemPrompt, String userPrompt) throws AiException {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new AiException("No API key configured");
        }
        try {
            return switch (config.provider()) {
                case "GROQ"       -> openAiCompatible("https://api.groq.com/openai/v1/chat/completions",
                                                      systemPrompt, userPrompt, false);
                case "OPENROUTER" -> openAiCompatible("https://openrouter.ai/api/v1/chat/completions",
                                                      systemPrompt, userPrompt, true);
                default           -> gemini(systemPrompt, userPrompt);   // GEMINI
            };
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ---- Gemini ----------------------------------------------------------

    private String gemini(String systemPrompt, String userPrompt) throws Exception {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + config.effectiveModel() + ":generateContent?key="
                + java.net.URLEncoder.encode(config.apiKey(), StandardCharsets.UTF_8);

        JsonObject body = new JsonObject();
        JsonArray contents = new JsonArray();
        JsonObject userTurn = new JsonObject();
        userTurn.addProperty("role", "user");
        JsonArray parts = new JsonArray();
        JsonObject part = new JsonObject();
        // Gemini has no dedicated system role here; prepend the instruction.
        part.addProperty("text", systemPrompt + "\n\n" + userPrompt);
        parts.add(part);
        userTurn.add("parts", parts);
        contents.add(userTurn);
        body.add("contents", contents);

        JsonObject genCfg = new JsonObject();
        genCfg.addProperty("temperature", 0.2);
        genCfg.addProperty("maxOutputTokens", 256);
        genCfg.addProperty("responseMimeType", "application/json");
        body.add("generationConfig", genCfg);

        String resp = post(url, body.toString(), null, null);
        JsonObject root = JsonParser.parseString(resp).getAsJsonObject();
        JsonArray candidates = root.getAsJsonArray("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new AiException("Gemini: empty response");
        }
        JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
        JsonArray rParts = content.getAsJsonArray("parts");
        StringBuilder sb = new StringBuilder();
        for (JsonElement el : rParts) {
            JsonElement t = el.getAsJsonObject().get("text");
            if (t != null) sb.append(t.getAsString());
        }
        return sb.toString();
    }

    // ---- OpenAI-compatible (Groq / OpenRouter) ---------------------------

    private String openAiCompatible(String url, String systemPrompt, String userPrompt,
                                    boolean openRouter) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", config.effectiveModel());
        body.addProperty("temperature", 0.2);
        body.addProperty("max_tokens", 256);
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        body.add("messages", messages);

        String extraHeaderKey = null, extraHeaderVal = null;
        if (openRouter) {
            // OpenRouter recommends these attribution headers; harmless elsewhere.
            extraHeaderKey = "X-Title";
            extraHeaderVal = "BAC Time-Machine";
        }

        String resp = post(url, body.toString(),
                "Authorization", "Bearer " + config.apiKey(),
                extraHeaderKey, extraHeaderVal);
        JsonObject root = JsonParser.parseString(resp).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new AiException("Provider returned no choices");
        }
        JsonObject msg = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        JsonElement content = msg.get("content");
        return content != null ? content.getAsString() : "";
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }

    // ---- HTTP ------------------------------------------------------------

    private String post(String url, String jsonBody,
                        String headerKey, String headerVal) throws Exception {
        return post(url, jsonBody, headerKey, headerVal, null, null);
    }

    private String post(String url, String jsonBody,
                        String headerKey, String headerVal,
                        String headerKey2, String headerVal2) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        if (headerKey != null && headerVal != null) b.header(headerKey, headerVal);
        if (headerKey2 != null && headerVal2 != null) b.header(headerKey2, headerVal2);

        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        int code = r.statusCode();
        if (code < 200 || code >= 300) {
            String snippet = r.body() != null && r.body().length() > 300
                    ? r.body().substring(0, 300) : r.body();
            throw new AiException("HTTP " + code + " from provider: " + snippet);
        }
        return r.body();
    }
}
