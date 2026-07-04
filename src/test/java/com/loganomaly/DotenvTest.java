package com.loganomaly;

import com.loganomaly.config.AppConfig;
import com.loganomaly.config.BglCandidateMode;
import com.loganomaly.config.BglEvalRangeMode;
import com.loganomaly.config.Dotenv;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DotenvTest {
    @Test
    void loadsExperimentAndOpenSearchValuesFromDotenvFile() throws Exception {
        Path file = Files.createTempFile("log-anomaly", ".env");
        Files.writeString(file, """
                OPENSEARCH_URL=http://example.test:9200
                OPENSEARCH_INDEX=paper-experiment-index
                OPENSEARCH_INTEGRATION_ENABLED=true
                DATASET_MODE=openstack
                DATASET_ACTION=index
                OPENAI_API_KEY=test-key
                OPENAI_EMBEDDING_MODEL=text-embedding-3-small
                OPENAI_EMBEDDING_DIMENSIONS=1536
                OPENSTACK_INDEX=log-anomaly-openstack-test
                OPENSTACK_INDEX_BATCH_SIZE=2000
                OPENSTACK_SHORT_WINDOW_MINUTES=15
                OPENSTACK_BASELINE_WINDOW_HOURS=24
                BGL_CANDIDATE_MODE=all
                BGL_VERBOSE_ROW_LOGGING=true
                BGL_MIN_HISTORICAL_SUPPORT=5
                BGL_MIN_ALERT_SHORT_SUPPORT=3
                BGL_SIMILARITY_SWEEP=0.70,0.80,0.90
                BGL_EVAL_RANGE_MODE=contiguous
                BGL_EVAL_START=2005-06-10T00:00:00Z
                BGL_EVAL_DURATION_DAYS=7
                BGL_ABLATION_CACHE=target/bgl-ablation-cache.jsonl
                BGL_CLEAR_ABLATION_CACHE=true
                BGL_ABLATION_PARALLEL=true
                BGL_ABLATION_MAX_WORKERS=3
                EXPERIMENT_TOP_K=20
                EXPERIMENT_SIMILARITY_THRESHOLD=0.91
                EXPERIMENT_SHORT_WINDOW_MINUTES=15
                EXPERIMENT_BASELINE_WINDOW_MINUTES=1440
                """);

        AppConfig config = AppConfig.load(Dotenv.load(file));

        assertEquals("http://example.test:9200", config.openSearchUrl());
        assertEquals("paper-experiment-index", config.openSearchIndex());
        assertTrue(config.openSearchIntegrationEnabled());
        assertEquals(com.loganomaly.config.DatasetMode.OPENSTACK, config.datasetMode());
        assertEquals(com.loganomaly.config.DatasetAction.INDEX, config.datasetAction());
        assertEquals("test-key", config.openAi().apiKey().orElseThrow());
        assertEquals("text-embedding-3-small", config.openAi().embeddingModel());
        assertEquals(1536, config.openAi().embeddingDimensions());
        assertEquals("log-anomaly-openstack-test", config.openStack().indexName());
        assertEquals(2000, config.openStack().indexBatchSize());
        assertEquals(15, config.openStack().shortWindow().toMinutes());
        assertEquals(24, config.openStack().baselineWindow().toHours());
        assertEquals(BglCandidateMode.ALL, config.bgl().candidateMode());
        assertTrue(config.bgl().verboseRowLogging());
        assertEquals(5, config.bgl().minimumHistoricalSupport());
        assertEquals(3, config.bgl().minimumAlertShortSupport());
        assertEquals(java.util.List.of(0.70, 0.80, 0.90), config.bgl().similaritySweep());
        assertEquals(BglEvalRangeMode.CONTIGUOUS, config.bgl().evalRangeMode());
        assertEquals(Instant.parse("2005-06-10T00:00:00Z"), config.bgl().evalStart().orElseThrow());
        assertEquals(7, config.bgl().evalDuration().toDays());
        assertEquals(Path.of("target/bgl-ablation-cache.jsonl"), config.bgl().ablationCache());
        assertTrue(config.bgl().clearAblationCache());
        assertTrue(config.bgl().ablationParallel());
        assertEquals(3, config.bgl().ablationMaxWorkers());
        assertEquals(20, config.experiment().topK());
        assertEquals(0.91, config.experiment().similarityThreshold());
        assertEquals(15, config.experiment().shortWindow().toMinutes());
        assertEquals(1440, config.experiment().baselineWindow().toMinutes());
    }

    @Test
    void defaultsKeepOpenSearchIntegrationTestsDisabledForPaperReproducibility() {
        AppConfig config = AppConfig.load(Dotenv.load(Path.of("/tmp/nonexistent-log-anomaly.env")));

        assertFalse(config.openSearchIntegrationEnabled());
        assertEquals(com.loganomaly.config.DatasetMode.SYNTHETIC, config.datasetMode());
        assertEquals(com.loganomaly.config.DatasetAction.EVALUATE, config.datasetAction());
        assertEquals("deterministic-synthetic-v1", config.embeddingProviderName());
    }
}
