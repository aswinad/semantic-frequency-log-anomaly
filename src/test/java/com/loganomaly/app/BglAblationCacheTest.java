package com.loganomaly.app;

import com.loganomaly.core.HybridAnomalyDetector;
import com.loganomaly.core.SemanticAnalysis;
import com.loganomaly.core.TemporalAnalysis;
import com.loganomaly.experiment.ScenarioProbe;
import com.loganomaly.core.AnomalyClass;
import com.loganomaly.experiment.ScenarioResult;
import com.loganomaly.opensearch.KnnNeighbor;
import com.loganomaly.report.BinaryGroundTruth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.List;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BglAblationCacheTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsAndReloadsCachedScenarioPayload() throws Exception {
        Path cachePath = tempDir.resolve("bgl-ablation-cache.jsonl");
        BglAblationCache cache = new BglAblationCache(cachePath);
        cache.load(false);

        var candidate = new BglEvaluationWorkflow.EvaluationCandidate(
                "candidate",
                "panic-pattern",
                "service",
                "bgl-anomaly",
                "APPREAD",
                "panic candidate",
                new float[]{1.0f, 0.0f},
                BinaryGroundTruth.ANOMALY,
                4,
                Instant.parse("2026-01-01T01:00:00Z")
        );
        String cacheKey = BglAblationCache.cacheKey(
                com.loganomaly.config.BglCandidateMode.ALL,
                new com.loganomaly.config.ExperimentConfig(
                        Duration.ofMinutes(15),
                        Duration.ofHours(24),
                        5,
                        3,
                        0.85,
                        2.0
                ),
                5,
                3,
                new BglEvaluationWorkflow.EvaluationRange(
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-01-02T00:00:00Z")
                ),
                Duration.ofMinutes(5),
                candidate
        );

        ScenarioResult scenarioResult = scenarioResult(3, 40, 5, 50, 0.91);
        cache.putIfAbsent(cacheKey, BglAblationCache.fromScenarioResult(scenarioResult));

        BglAblationCache reloaded = new BglAblationCache(cachePath);
        reloaded.load(false);
        var payload = reloaded.get(cacheKey).orElseThrow();

        assertEquals(50, payload.semanticCount());
        assertEquals(5, payload.temporalShortCount());
        assertEquals(3, payload.exactShortCount());
        assertEquals(1, payload.neighbors().size());
        assertTrue(payload.neighbors().get(0).cosineSimilarity() > 0.9);
    }

    @Test
    void reloadsExistingV1CacheEntriesWithoutMigration() throws Exception {
        Path cachePath = tempDir.resolve("bgl-ablation-cache-existing.jsonl");
        Files.writeString(cachePath, """
                {"schemaVersion":"bgl-ablation-cache-v1","cacheKey":"existing-key","payload":{"semanticCount":50,"semanticSimilarityScore":0.91,"temporalShortCount":5,"temporalLongCount":50,"exactShortCount":3,"exactLongCount":40,"neighbors":[{"id":"n1","timestamp":"2026-01-01T00:59:00Z","service":"service","pattern":"panic-pattern","incidentFamily":"bgl-anomaly","scenario":"scenario","message":"neighbor","openSearchScore":1.0,"cosineSimilarity":0.91}]}}
                """);

        BglAblationCache cache = new BglAblationCache(cachePath);
        cache.load(false);

        BglAblationCache.CachedScenarioPayload payload = cache.get("existing-key").orElseThrow();
        assertEquals(50, payload.semanticCount());
        assertEquals(5, payload.temporalShortCount());
        assertEquals(3, payload.exactShortCount());
        assertEquals(1, payload.neighbors().size());
        assertEquals("n1", payload.neighbors().get(0).id());
    }

    private static ScenarioResult scenarioResult(
            int exactShort,
            int exactBaseline,
            int semanticShort,
            int semanticBaseline,
            double maxSimilarity
    ) {
        ScenarioProbe probe = new ScenarioProbe(
                "candidate",
                "panic-pattern",
                "bgl-anomaly",
                Instant.parse("2026-01-01T01:00:00Z"),
                "service",
                "panic candidate",
                new float[]{1.0f, 0.0f},
                AnomalyClass.SURGE_ANOMALY
        );
        SemanticAnalysis semantic = new SemanticAnalysis(semanticBaseline, maxSimilarity, 3);
        TemporalAnalysis semanticTemporal = new TemporalAnalysis(
                semanticShort,
                semanticBaseline,
                Duration.ofMinutes(15),
                Duration.ofHours(24),
                2.0,
                5,
                true
        );
        return new ScenarioResult(
                probe,
                semantic,
                semanticTemporal,
                new HybridAnomalyDetector(0.5, 0.5).analyze(semantic, semanticTemporal),
                List.of(new KnnNeighbor(
                        "n1",
                        "2026-01-01T00:59:00Z",
                        "service",
                        "panic-pattern",
                        "bgl-anomaly",
                        "scenario",
                        "neighbor",
                        1.0,
                        maxSimilarity
                )),
                new TemporalAnalysis(
                        exactShort,
                        exactBaseline,
                        Duration.ofMinutes(15),
                        Duration.ofHours(24),
                        2.0,
                        5,
                        true
                )
        );
    }
}
