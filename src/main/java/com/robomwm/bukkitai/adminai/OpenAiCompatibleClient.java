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
            options.put("temperature", 0.1);
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
            } catch (Exception e) {
                // Ignore parsing errors for incomplete or non-JSON chunks
            }
        });

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

    private String truncate(String input, int max)
    {
        if (input == null || input.length() <= max)
            return input;
        return input.substring(0, max);
    }
}
