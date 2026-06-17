package com.robomwm.bukkitai.adminai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

class OpenAiCompatibleClient
{
    /** Hard cap on a single response. The model's output is just a small JSON action, so this is generous. */
    private static final int MAX_RESPONSE_CHARS = 262_144;
    /** Run the repetition check once per this many appended chars (cheap amortization). */
    private static final int LOOP_CHECK_INTERVAL = 512;

    /** Thrown internally to break out of the streaming forEach when a runaway is detected. */
    private static final class StreamAbort extends RuntimeException
    {
        StreamAbort(String message) { super(message); }
    }

    private final Logger logger;
    private final Gson gson = new Gson();
    private final Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    OpenAiCompatibleClient(Logger logger)
    {
        this.logger = logger;
    }

    String complete(AiProvider provider, List<AiMessage> messages) throws IOException, InterruptedException
    {
        JsonObject requestBody = new JsonObject();
        String model = provider.model();
        if (model == null || model.isBlank())
            throw new IOException("Provider " + provider.name() + " has no model configured.");
        requestBody.addProperty("model", model);
        requestBody.add("messages", gson.toJsonTree(messages));
        requestBody.addProperty("stream", true);
        if ("simple-chat-api".equalsIgnoreCase(provider.protocol()))
            requestBody.addProperty("message", messages.get(messages.size() - 1).content());
        else if ("ollama-native".equalsIgnoreCase(provider.protocol()) || "ollama".equalsIgnoreCase(provider.protocol()))
        {
            java.util.Map<String, Object> options = new java.util.HashMap<>();
            // Anti-runaway defaults. Ollama's stock repeat_penalty (1.1) over a
            // 64-token window is far too weak to break a small model out of a
            // degenerate loop, and there's no output cap. These are overridable
            // via the provider's `sampling` config.
            options.put("temperature", 0.1);
            options.put("repeat_penalty", 1.3);
            options.put("repeat_last_n", 256);
            options.put("num_predict", 2048);
            options.putAll(provider.sampling());
            if (model.toLowerCase().contains("qwen"))
                options.put("include_reasoning", false);
            requestBody.add("options", gson.toJsonTree(options));
        }
        else
        {
            if (provider.endpoint().contains("arliai.com") || model.toLowerCase().contains("qwen"))
            {
                if (provider.endpoint().contains("arliai.com"))
                    requestBody.addProperty("output_kind", "delta");
                requestBody.add("chat_template_kwargs", gson.toJsonTree(java.util.Map.of("enable_thinking", false)));
            }

            // Apply custom sampling overrides
            for (java.util.Map.Entry<String, Object> entry : provider.sampling().entrySet())
                requestBody.add(entry.getKey(), gson.toJsonTree(entry.getValue()));
        }

        String endpoint = provider.endpoint();
        if (endpoint == null || endpoint.isBlank())
            throw new IOException("Provider " + provider.name() + " has no endpoint configured.");

        String jsonRequest = gson.toJson(requestBody);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(provider.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonRequest));

        if (!provider.apiKey().isBlank())
            requestBuilder.header("Authorization", "Bearer " + provider.apiKey());

        HttpResponse<java.util.stream.Stream<String>> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofLines());

        if (response.statusCode() < 200 || response.statusCode() >= 300)
        {
            String errorMessage = provider.name() + " returned HTTP " + response.statusCode();
            throw new IOException(errorMessage);
        }

        StringBuilder fullResponse = new StringBuilder();
        StringBuilder lineBuffer = new StringBuilder();
        int[] lastLoopCheck = {0};

        try {
            response.body().forEach(line -> {
                if (line.isEmpty() || line.startsWith(":")) return;
                String jsonStr = line;
                if (line.startsWith("data: ")) {
                    jsonStr = line.substring(6).trim();
                    if (jsonStr.equals("[DONE]")) return;
                }

                try {
                    JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();
                    String content = extractContent(json);
                    if (content != null && !content.isEmpty()) {
                        fullResponse.append(content);

                        // Abort runaway generation. Protects every protocol,
                        // including ones (e.g. OpenAI-compatible) that have no
                        // server-side output cap.
                        if (fullResponse.length() > MAX_RESPONSE_CHARS)
                            throw new StreamAbort("exceeded " + MAX_RESPONSE_CHARS + " chars");
                        if (fullResponse.length() - lastLoopCheck[0] >= LOOP_CHECK_INTERVAL) {
                            lastLoopCheck[0] = fullResponse.length();
                            if (isRepetitiveTail(fullResponse))
                                throw new StreamAbort("detected repetition loop");
                        }

                        lineBuffer.append(content);
                        int newlineIdx;
                        while ((newlineIdx = lineBuffer.indexOf("\n")) >= 0) {
                            String completeLine = lineBuffer.substring(0, newlineIdx);
                            logger.info("[AI Stream] " + completeLine);
                            lineBuffer.delete(0, newlineIdx + 1);
                        }
                        if (lineBuffer.length() > 80) {
                            logger.info("[AI Stream] " + lineBuffer.toString());
                            lineBuffer.setLength(0);
                        }
                    }
                } catch (StreamAbort abort) {
                    throw abort;
                } catch (Exception e) {
                    logger.warning("Failed to parse AI stream chunk: " + line + ". Error: " + e.getMessage());
                }
            });
        } catch (StreamAbort abort) {
            logger.warning("[AI Stream] Aborted runaway response from " + provider.name() + ": " + abort.getMessage()
                    + " (kept " + fullResponse.length() + " chars).");
        }

        if (lineBuffer.length() > 0) {
            logger.info("[AI Stream] " + lineBuffer.toString());
        }

        if (fullResponse.length() == 0)
            throw new IOException(provider.name() + " returned empty response.");

        return fullResponse.toString();
    }

    private String extractContent(JsonObject json)
    {
        if (json.has("choices"))
        {
            JsonArray choices = json.getAsJsonArray("choices");
            if (!choices.isEmpty())
            {
                JsonObject choice = choices.get(0).getAsJsonObject();
                if (choice.has("delta")) {
                    JsonObject delta = choice.getAsJsonObject("delta");
                    if (delta.has("content")) return delta.get("content").getAsString();
                    if (delta.has("reasoning_content")) return "[Thinking] " + delta.get("reasoning_content").getAsString();
                    if (delta.has("thinking")) return "[Thinking] " + delta.get("thinking").getAsString();
                }
                if (choice.has("message")) {
                    JsonObject message = choice.getAsJsonObject("message");
                    if (message.has("content")) return message.get("content").getAsString();
                    if (message.has("reasoning_content")) return "[Thinking] " + message.get("reasoning_content").getAsString();
                }
            }
        }
        if (json.has("message") && json.get("message").isJsonObject())
        {
            JsonObject msg = json.getAsJsonObject("message");
            if (msg.has("content")) return msg.get("content").getAsString();
            if (msg.has("thinking")) return "[Thinking] " + msg.get("thinking").getAsString();
        }
        if (json.has("content"))
            return json.get("content").getAsString();
        if (json.has("reasoning_content"))
            return "[Thinking] " + json.get("reasoning_content").getAsString();
        if (json.has("thinking"))
            return "[Thinking] " + json.get("thinking").getAsString();
        if (json.has("response"))
            return json.get("response").getAsString();
        if (json.has("text"))
            return json.get("text").getAsString();
        return null;
    }

    /**
     * Detects a short-period repetition at the tail of the response — the classic
     * small-model loop (e.g. "Error|Exception|Error|Exception..." or a repeated
     * line). Catches the failure mode that Ollama's weak repeat_penalty does not,
     * and works regardless of provider since it inspects emitted text directly.
     */
    private boolean isRepetitiveTail(StringBuilder sb)
    {
        int len = sb.length();
        int window = Math.min(len, 2048);
        if (window < 128) return false;

        // Try each period; require enough consecutive repeats to avoid false positives.
        for (int period = 1; period <= 64; period++)
        {
            int repeats = 8;
            int checkLen = period * repeats;
            if (window < checkLen) continue;

            boolean periodic = true;
            for (int i = 0; i < checkLen; i++)
            {
                if (sb.charAt(len - 1 - i) != sb.charAt(len - 1 - i - period))
                {
                    periodic = false;
                    break;
                }
            }
            if (periodic) return true;
        }
        return false;
    }
}
