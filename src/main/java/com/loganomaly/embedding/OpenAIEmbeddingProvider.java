package com.loganomaly.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loganomaly.config.OpenAiConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class OpenAIEmbeddingProvider implements EmbeddingProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final URI EMBEDDINGS_ENDPOINT = URI.create("https://api.openai.com/v1/embeddings");

    private final Optional<String> apiKey;
    private final String model;
    private final int dimensions;
    private final HttpClient httpClient;

    public OpenAIEmbeddingProvider(OpenAiConfig config) {
        this(
                config.apiKey(),
                config.embeddingModel(),
                config.embeddingDimensions(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
        );
    }

    OpenAIEmbeddingProvider(Optional<String> apiKey, String model, int dimensions, HttpClient httpClient) {
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
        this.httpClient = httpClient;
    }

    @Override
    public String name() {
        return model;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        String resolvedApiKey = apiKey.orElseThrow(() ->
                new IllegalStateException("OPENAI_API_KEY is required to create missing OpenAI embeddings"));
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "dimensions", dimensions,
                    "input", texts
            );
            HttpRequest request = HttpRequest.newBuilder(EMBEDDINGS_ENDPOINT)
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + resolvedApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("OpenAI embedding request failed with HTTP " + response.statusCode()
                        + ": " + response.body());
            }
            JsonNode data = MAPPER.readTree(response.body()).path("data");
            List<float[]> embeddings = new ArrayList<>();
            for (JsonNode item : data) {
                embeddings.add(parseEmbedding(item.path("embedding")));
            }
            return embeddings;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to call OpenAI embeddings API", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling OpenAI embeddings API", e);
        }
    }

    private static float[] parseEmbedding(JsonNode embeddingNode) {
        float[] embedding = new float[embeddingNode.size()];
        for (int i = 0; i < embeddingNode.size(); i++) {
            embedding[i] = (float) embeddingNode.get(i).asDouble();
        }
        return embedding;
    }
}
