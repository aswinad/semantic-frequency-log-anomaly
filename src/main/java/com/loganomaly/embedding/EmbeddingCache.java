package com.loganomaly.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class EmbeddingCache {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path path;
    private final String model;
    private final int dimensions;
    private final Map<String, float[]> embeddingsByTemplate = new HashMap<>();

    public EmbeddingCache(Path path, String model, int dimensions) {
        this.path = path;
        this.model = model;
        this.dimensions = dimensions;
    }

    public void load() throws IOException {
        embeddingsByTemplate.clear();
        if (!Files.exists(path)) {
            return;
        }
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = MAPPER.readTree(line);
            if (!model.equals(node.path("model").asText()) || dimensions != node.path("dimensions").asInt()) {
                continue;
            }
            embeddingsByTemplate.put(node.path("template").asText(), parseEmbedding(node.path("embedding")));
        }
    }

    public Optional<float[]> get(String template) {
        return Optional.ofNullable(embeddingsByTemplate.get(template));
    }

    public void putAll(Map<String, float[]> newEmbeddings) throws IOException {
        if (newEmbeddings.isEmpty()) {
            return;
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND,
                java.nio.file.StandardOpenOption.WRITE
        )) {
            for (Map.Entry<String, float[]> entry : newEmbeddings.entrySet()) {
                embeddingsByTemplate.put(entry.getKey(), entry.getValue());
                writer.write(MAPPER.writeValueAsString(Map.of(
                        "model", model,
                        "dimensions", dimensions,
                        "templateHash", sha256(entry.getKey()),
                        "template", entry.getKey(),
                        "embedding", entry.getValue()
                )));
                writer.newLine();
            }
        }
    }

    private static float[] parseEmbedding(JsonNode embeddingNode) {
        float[] embedding = new float[embeddingNode.size()];
        for (int i = 0; i < embeddingNode.size(); i++) {
            embedding[i] = (float) embeddingNode.get(i).asDouble();
        }
        return embedding;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
