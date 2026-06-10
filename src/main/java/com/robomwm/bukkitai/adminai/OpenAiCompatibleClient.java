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
        requestBody.addProperty("stream", false);
        if ("simple-chat-api".equalsIgnoreCase(provider.protocol()))
            requestBody.addProperty("message", messages.get(messages.size() - 1).content());
        else if ("ollama-native".equalsIgnoreCase(provider.protocol()) || "ollama".equalsIgnoreCase(provider.protocol()))
        {
            java.util.Map<String, Object> options = new java.util.HashMap<>();
            options.put("temperature", 0.1);
            options.putAll(provider.sampling());
            requestBody.add("options", gson.toJsonTree(options));
        }
        else
        {
            if (provider.endpoint().contains("arliai.com"))
            {
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
        logger.info("DEBUG: Sending request to " + endpoint + " for provider " + provider.name());

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(provider.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonRequest));

        if (!provider.apiKey().isBlank())
            requestBuilder.header("Authorization", "Bearer " + provider.apiKey());

        try
        {
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body();

            if (response.statusCode() < 200 || response.statusCode() >= 300)
            {
                String errorMessage = provider.name() + " returned HTTP " + response.statusCode();
                logger.warning("DEBUG: " + errorMessage);
                throw new IOException(errorMessage);
            }

            if (responseBody == null || responseBody.isBlank())
                throw new IOException(provider.name() + " returned empty response.");
            
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            if (json.has("choices"))
            {
                JsonArray choices = json.getAsJsonArray("choices");
                if (!choices.isEmpty())
                    return choices.get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
            }
            if (json.has("message") && json.get("message").isJsonObject())
                return json.getAsJsonObject("message").get("content").getAsString();
            if (json.has("message") && json.get("message").isJsonPrimitive())
                return json.get("message").getAsString();
            if (json.has("content"))
                return json.get("content").getAsString();
            if (json.has("response"))
                return json.get("response").getAsString();
            if (json.has("text"))
                return json.get("text").getAsString();
            throw new IOException(provider.name() + " response had no chat content.");
        }
        catch (Exception e)
        {
            String message = e.getMessage();
            if (message == null) message = e.getClass().getName();
            logger.warning("DEBUG: Exception during HTTP request: " + message);
            throw e;
        }
    }

    private String truncate(String input, int max)
    {
        if (input == null || input.length() <= max)
            return input;
        return input.substring(0, max);
    }
}
