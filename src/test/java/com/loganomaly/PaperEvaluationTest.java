package com.loganomaly;

import com.loganomaly.config.ExperimentConfig;
import com.loganomaly.core.AnomalyClass;
import com.loganomaly.core.HybridAnomalyDetector;
import com.loganomaly.core.SemanticAnalysis;
import com.loganomaly.core.TemporalAnalysis;
import com.loganomaly.experiment.ScenarioProbe;
import com.loganomaly.experiment.ScenarioResult;
import com.loganomaly.opensearch.KnnNeighbor;
import com.loganomaly.opensearch.LogDocument;
import com.loganomaly.report.ClusterMetric;
import com.loganomaly.report.DetectionMetrics;
import com.loganomaly.report.EvaluationMethod;
import com.loganomaly.report.MethodPerformance;
import com.loganomaly.report.PaperEvaluation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperEvaluationTest {
    private static final ExperimentConfig CONFIG = ExperimentConfig.defaults();

    @Test
    void overallPerformanceCalculatesPrecisionRecallF1AndErrorRates() {
        List<ScenarioResult> results = List.of(
                result("known spike", "db-connectivity", "database-connection-timeout",
                        AnomalyClass.SURGE_ANOMALY, 10, 10, 35, 15, 0.94),
                result("novel event", "auth-delegation", "auth-delegation",
                        AnomalyClass.RARE_ANOMALY, 0, 0, 0, 0, 0.10),
                result("normal noise", "routine-operations", "routine-heartbeat",
                        AnomalyClass.NORMAL_BEHAVIOR, 3, 30, 3, 30, 0.96)
        );

        MethodPerformance hybrid = PaperEvaluation.overallPerformance(results, CONFIG).stream()
                .filter(row -> row.method() == EvaluationMethod.HYBRID_FRAMEWORK)
                .findFirst()
                .orElseThrow();

        assertEquals(1.0, hybrid.metrics().precision());
        assertEquals(1.0, hybrid.metrics().recall());
        assertEquals(1.0, hybrid.metrics().f1Score());
        assertEquals(0.0, hybrid.metrics().falsePositiveRate());
        assertEquals(0.0, hybrid.metrics().falseNegativeRate());
    }

    @Test
    void metricsHandleZeroDenominators() {
        DetectionMetrics metrics = DetectionMetrics.fromCounts(0, 0, 0, 0);

        assertEquals(0.0, metrics.precision());
        assertEquals(0.0, metrics.recall());
        assertEquals(0.0, metrics.f1Score());
        assertEquals(0.0, metrics.falsePositiveRate());
        assertEquals(0.0, metrics.falseNegativeRate());
    }

    @Test
    void semanticClusterCoverageCapturesParaphrasedFamilyBetterThanExactPattern() {
        List<LogDocument> logs = List.of(
                log("1", "db-connectivity", "database-connection-timeout"),
                log("2", "db-connectivity", "jdbc-connection-acquire"),
                log("3", "db-connectivity", "sql-connection-refused"),
                log("4", "db-connectivity", "connection-pool-exhausted")
        );
        List<ScenarioResult> results = List.of(
                result("paraphrased family", "db-connectivity", "jdbc-connection-acquire",
                        AnomalyClass.SURGE_ANOMALY, 1, 0, 4, 0, 0.97)
        );

        List<ClusterMetric> metrics = PaperEvaluation.semanticClusterMetrics(logs, results);
        ClusterMetric exact = findCluster(metrics, EvaluationMethod.EXACT_PATTERN);
        ClusterMetric semantic = findCluster(metrics, EvaluationMethod.SEMANTIC_FREQUENCY);

        assertTrue(semantic.clusterCoverage() > exact.clusterCoverage());
        assertTrue(exact.averageClustersPerIncident() > semantic.averageClustersPerIncident());
    }

    private static ClusterMetric findCluster(List<ClusterMetric> metrics, EvaluationMethod method) {
        return metrics.stream()
                .filter(metric -> metric.method() == method)
                .findFirst()
                .orElseThrow();
    }

    static ScenarioResult result(
            String name,
            String incidentFamily,
            String pattern,
            AnomalyClass expectedClass,
            int exactShort,
            int exactBaseline,
            int semanticShort,
            int semanticBaseline,
            double maxSimilarity
    ) {
        ScenarioProbe probe = new ScenarioProbe(
                name,
                pattern,
                incidentFamily,
                Instant.parse("2026-01-01T01:00:00Z"),
                "test-service",
                name + " message",
                new float[]{1.0f, 0.0f},
                expectedClass
        );
        SemanticAnalysis semantic = new SemanticAnalysis(semanticBaseline, maxSimilarity, 3);
        TemporalAnalysis semanticTemporal = temporal(semanticShort, semanticBaseline);
        return new ScenarioResult(
                probe,
                semantic,
                semanticTemporal,
                new HybridAnomalyDetector(0.5, 0.5).analyze(semantic, semanticTemporal),
                List.of(
                        new KnnNeighbor("n1", "2026-01-01T00:59:00Z", "test-service",
                                pattern, incidentFamily, "test-scenario", "neighbor message", 1.2, maxSimilarity)
                ),
                temporal(exactShort, exactBaseline)
        );
    }

    static LogDocument log(String id, String incidentFamily, String pattern) {
        return new LogDocument(
                id,
                Instant.parse("2026-01-01T00:30:00Z"),
                "test-service",
                pattern,
                incidentFamily,
                "test-scenario",
                "message",
                new float[]{1.0f, 0.0f}
        );
    }

    private static TemporalAnalysis temporal(int shortCount, int baselineCount) {
        return new TemporalAnalysis(
                shortCount,
                baselineCount,
                Duration.ofMinutes(5),
                Duration.ofMinutes(55),
                2.0
        );
    }
}
