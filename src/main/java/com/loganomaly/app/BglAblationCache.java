package com.loganomaly.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loganomaly.config.BglCandidateMode;
import com.loganomaly.config.ExperimentConfig;
import com.loganomaly.experiment.ScenarioResult;
import com.loganomaly.opensearch.KnnNeighbor;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class BglAblationCache {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCHEMA_VERSION = "bgl-ablation-cache-v1";

    private final Path path;
    private final ConcurrentMap<String, CachedScenarioPayload> payloadsByKey = new ConcurrentHashMap<>();

    BglAblationCache(Path path) {
        this.path = path;
    }

    void load(boolean clear) throws IOException {
        payloadsByKey.clear();
        if (clear) {
            Files.deleteIfExists(path);
            return;
        }
        if (!Files.exists(path)) {
            return;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = MAPPER.readTree(line);
            if (!SCHEMA_VERSION.equals(node.path("schemaVersion").asText())) {
                continue;
            }
            payloadsByKey.put(node.path("cacheKey").asText(), parsePayload(node.path("payload")));
        }
    }

    Optional<CachedScenarioPayload> get(String cacheKey) {
        return Optional.ofNullable(payloadsByKey.get(cacheKey));
    }

    synchronized void putIfAbsent(String cacheKey, CachedScenarioPayload payload) throws IOException {
        if (payloadsByKey.putIfAbsent(cacheKey, payload) != null) {
            return;
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(
                path,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE
        )) {
            writer.write(MAPPER.writeValueAsString(Map.of(
                    "schemaVersion", SCHEMA_VERSION,
                    "cacheKey", cacheKey,
                    "payload", payload
            )));
            writer.newLine();
        }
    }

    static String cacheKey(
            BglCandidateMode candidateMode,
            ExperimentConfig experiment,
            int minimumHistoricalSupport,
            int minimumAlertShortSupport,
            BglEvaluationWorkflow.EvaluationRange evaluationRange,
            Duration evalBucket,
            BglEvaluationWorkflow.EvaluationCandidate candidate
    ) {
        return sha256(String.join("\u001F",
                SCHEMA_VERSION,
                candidateMode.toString(),
                evaluationRange.start().toString(),
                evaluationRange.end().toString(),
                Long.toString(evalBucket.toMillis()),
                Long.toString(experiment.shortWindow().toMillis()),
                Long.toString(experiment.baselineWindow().toMillis()),
                Integer.toString(experiment.topK()),
                Integer.toString(experiment.noveltyThreshold()),
                Double.toString(experiment.similarityThreshold()),
                Double.toString(experiment.spikeThreshold()),
                Integer.toString(minimumHistoricalSupport),
                Integer.toString(minimumAlertShortSupport),
                candidate.groundTruth().name(),
                candidate.nativeLabel(),
                candidate.service(),
                candidate.pattern(),
                candidate.observedAt().toString(),
                Long.toString(candidate.eventCount())
        ));
    }

    static CachedScenarioPayload fromScenarioResult(ScenarioResult scenarioResult) {
        return new CachedScenarioPayload(
                scenarioResult.semantic().semanticCount(),
                scenarioResult.semantic().semanticSimilarityScore(),
                scenarioResult.temporal().shortCount(),
                scenarioResult.temporal().longCount(),
                scenarioResult.exactPatternBaseline().shortCount(),
                scenarioResult.exactPatternBaseline().longCount(),
                scenarioResult.neighbors().stream()
                        .map(neighbor -> new CachedNeighbor(
                                neighbor.id(),
                                neighbor.timestamp(),
                                neighbor.service(),
                                neighbor.pattern(),
                                neighbor.incidentFamily(),
                                neighbor.scenario(),
                                neighbor.message(),
                                neighbor.openSearchScore(),
                                neighbor.cosineSimilarity()
                        ))
                        .toList()
        );
    }

    private static CachedScenarioPayload parsePayload(JsonNode node) {
        List<CachedNeighbor> neighbors = new ArrayList<>();
        for (JsonNode neighbor : node.path("neighbors")) {
            neighbors.add(new CachedNeighbor(
                    neighbor.path("id").asText(),
                    neighbor.path("timestamp").asText(),
                    neighbor.path("service").asText(),
                    neighbor.path("pattern").asText(),
                    neighbor.path("incidentFamily").asText(),
                    neighbor.path("scenario").asText(),
                    neighbor.path("message").asText(),
                    neighbor.path("openSearchScore").asDouble(),
                    neighbor.path("cosineSimilarity").asDouble()
            ));
        }
        return new CachedScenarioPayload(
                node.path("semanticCount").asInt(),
                node.path("semanticSimilarityScore").asDouble(),
                node.path("temporalShortCount").asInt(),
                node.path("temporalLongCount").asInt(),
                node.path("exactShortCount").asInt(),
                node.path("exactLongCount").asInt(),
                neighbors
        );
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    record CachedScenarioPayload(
            int semanticCount,
            double semanticSimilarityScore,
            int temporalShortCount,
            int temporalLongCount,
            int exactShortCount,
            int exactLongCount,
            List<CachedNeighbor> neighbors
    ) {
    }

    record CachedNeighbor(
            String id,
            String timestamp,
            String service,
            String pattern,
            String incidentFamily,
            String scenario,
            String message,
            double openSearchScore,
            double cosineSimilarity
    ) {
        KnnNeighbor toKnnNeighbor() {
            return new KnnNeighbor(
                    id,
                    timestamp,
                    service,
                    pattern,
                    incidentFamily,
                    scenario,
                    message,
                    openSearchScore,
                    cosineSimilarity
            );
        }
    }
}
