package com.loganomaly;

import com.loganomaly.config.AppConfig;
import com.loganomaly.config.ExperimentConfig;
import com.loganomaly.core.HybridAnomalyDetector;
import com.loganomaly.embedding.DeterministicEmbeddingProvider;
import com.loganomaly.embedding.EmbeddingProvider;
import com.loganomaly.experiment.OpenSearchHybridAnalyzer;
import com.loganomaly.experiment.ScenarioProbe;
import com.loganomaly.experiment.ScenarioResult;
import com.loganomaly.experiment.SyntheticLogDataset;
import com.loganomaly.opensearch.OpenSearchLogVectorRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OpenSearchLogVectorRepositoryIT {
    @Test
    void indexesLogsAndReturnsSemanticNeighborsFromDockerOpenSearch() throws IOException {
        assumeOpenSearchIntegrationEnabled();
        String indexName = "log-anomaly-test-" + System.currentTimeMillis();
        try (OpenSearchLogVectorRepository repository = OpenSearchLogVectorRepository.fromEnvironment(indexName)) {
            EmbeddingProvider embeddings = new DeterministicEmbeddingProvider();
            repository.recreateIndex(embeddings.dimensions());
            repository.index("1", "payments", "Database connection timeout during transaction processing",
                    embeddings.embed("Database connection timeout during transaction processing"));
            repository.index("2", "payments", "SQL connection timed out while committing transaction",
                    embeddings.embed("SQL connection timed out while committing transaction"));
            repository.index("3", "auth", "JWT delegation signature mismatch during federated token validation",
                    embeddings.embed("JWT delegation signature mismatch during federated token validation"));
            repository.refresh();

            List<String> messages = repository.knnMessages(
                    embeddings.embed("Unable to acquire JDBC connection from pool"),
                    2
            );

            assertEquals(2, messages.size());
            assertTrue(messages.stream().allMatch(message ->
                    message.toLowerCase().contains("connection") || message.toLowerCase().contains("sql")));

            repository.deleteIndexIfExists();
        }
    }

    @Test
    void semanticFrequencyCountsParaphrasedFailuresThatExactPatternCountingSplits() throws IOException {
        assumeOpenSearchIntegrationEnabled();
        String indexName = "log-anomaly-frequency-test-" + System.currentTimeMillis();
        try (OpenSearchLogVectorRepository repository = OpenSearchLogVectorRepository.fromEnvironment(indexName)) {
            ExperimentConfig config = ExperimentConfig.defaults();
            repository.recreateIndex(SyntheticLogDataset.dimensions());
            repository.indexAll(SyntheticLogDataset.historicalLogs());
            repository.refresh();

            ScenarioProbe paraphrasedProbe = findProbe("G. Hard DB Paraphrase Spike");

            long exactPatternShortCount = repository.countPatternBetween(
                    paraphrasedProbe.pattern(),
                    paraphrasedProbe.observedAt().minus(config.shortWindow()),
                    paraphrasedProbe.observedAt()
            );
            long semanticShortCount = repository.countSemanticNeighborsBetween(
                    paraphrasedProbe.embedding(),
                    paraphrasedProbe.observedAt().minus(config.shortWindow()),
                    paraphrasedProbe.observedAt(),
                    config.similarityThreshold()
            );

            assertEquals(0, exactPatternShortCount);
            assertTrue(semanticShortCount > exactPatternShortCount);

            repository.deleteIndexIfExists();
        }
    }

    @Test
    void endToEndSemanticFrequencyScenariosClassifyAsExpected() throws IOException {
        assumeOpenSearchIntegrationEnabled();
        String indexName = "log-anomaly-e2e-test-" + System.currentTimeMillis();
        try (OpenSearchLogVectorRepository repository = OpenSearchLogVectorRepository.fromEnvironment(indexName)) {
            ExperimentConfig config = ExperimentConfig.defaults();
            repository.recreateIndex(SyntheticLogDataset.dimensions());
            repository.indexAll(SyntheticLogDataset.historicalLogs());
            repository.refresh();

            OpenSearchHybridAnalyzer analyzer = new OpenSearchHybridAnalyzer(
                    repository,
                    new HybridAnomalyDetector(0.5, 0.5),
                    config
            );

            for (ScenarioProbe probe : SyntheticLogDataset.probes()) {
                ScenarioResult result = analyzer.analyze(probe);
                assertEquals(probe.expectedClass(), result.hybrid().anomalyClass(), probe.name());
            }

            repository.deleteIndexIfExists();
        }
    }

    private static void assumeOpenSearchIntegrationEnabled() {
        assumeTrue(
                AppConfig.load().openSearchIntegrationEnabled(),
                "Set OPENSEARCH_INTEGRATION_ENABLED=true in .env to run Docker OpenSearch integration tests"
        );
    }

    private static ScenarioProbe findProbe(String name) {
        return SyntheticLogDataset.probes().stream()
                .filter(probe -> probe.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
