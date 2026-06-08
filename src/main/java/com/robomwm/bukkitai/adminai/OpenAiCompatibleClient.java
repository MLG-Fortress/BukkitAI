package com.robomwm.bukkitai.adminai;

import com.google.gson.Gson;
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

class OpenAiCompatibleClient
{
    private final Gson gson = new Gson();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

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
            requestBody.add("options", gson.toJsonTree(java.util.Map.of("temperature", 0.1)));
        else
            requestBody.addProperty("temperature", 0.1);

        String endpoint = provider.endpoint();
        if (endpoint == null || endpoint.isBlank())
            throw new IOException("Provider " + provider.name() + " has no endpoint configured.");

        System.out.println("DEBUG: Sending request to " + endpoint + " for provider " + provider.name());

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(provider.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)));

        if (!provider.apiKey().isBlank())
            requestBuilder.header("Authorization", "Bearer " + provider.apiKey());

        HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException(provider.name() + " returned HTTP " + response.statusCode() + ": " + truncate(response.body(), 500));

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
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

    private String truncate(String input, int max)
    {
        if (input == null || input.length() <= max)
            return input;
        return input.substring(0, max);
    }
}
