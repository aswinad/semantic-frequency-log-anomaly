package com.loganomaly;

import com.loganomaly.config.AppConfig;
import com.loganomaly.config.Dotenv;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

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
                EXPERIMENT_TOP_K=20
                EXPERIMENT_SIMILARITY_THRESHOLD=0.91
                EXPERIMENT_SHORT_WINDOW_MINUTES=15
                EXPERIMENT_BASELINE_WINDOW_MINUTES=1440
                """);

        AppConfig config = AppConfig.load(Dotenv.load(file));

        assertEquals("http://example.test:9200", config.openSearchUrl());
        assertEquals("paper-experiment-index", config.openSearchIndex());
        assertTrue(config.openSearchIntegrationEnabled());
        assertEquals(20, config.experiment().topK());
        assertEquals(0.91, config.experiment().similarityThreshold());
        assertEquals(15, config.experiment().shortWindow().toMinutes());
        assertEquals(1440, config.experiment().baselineWindow().toMinutes());
    }

    @Test
    void defaultsKeepOpenSearchIntegrationTestsDisabledForPaperReproducibility() {
        AppConfig config = AppConfig.load(Dotenv.load(Path.of("/tmp/nonexistent-log-anomaly.env")));

        assertFalse(config.openSearchIntegrationEnabled());
        assertEquals("deterministic-synthetic-v1", config.embeddingProviderName());
    }
}
