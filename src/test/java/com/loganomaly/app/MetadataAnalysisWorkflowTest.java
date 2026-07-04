package com.loganomaly.app;

import com.loganomaly.config.ExperimentConfig;
import com.loganomaly.core.AnomalyClass;
import com.loganomaly.experiment.ScenarioResult;
import com.loganomaly.experiment.SyntheticLogDataset;
import com.loganomaly.loghub.BglLogHubDataset;
import com.loganomaly.opensearch.LogDocument;
import com.loganomaly.report.EvaluationMethod;
import com.loganomaly.report.PaperEvaluation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataAnalysisWorkflowTest {
    @TempDir
    Path tempDir;

    @Test
    void syntheticOfflineAnalysisReconstructsParaphrasedSpike() {
        List<LogDocument> logs = SyntheticLogDataset.historicalLogs();
        ScenarioResult result = SyntheticMetadataWorkflow.analyze(
                SyntheticLogDataset.probes().stream()
                        .filter(probe -> probe.name().equals("B. Paraphrased Failure Family"))
                        .findFirst()
                        .orElseThrow(),
                logs,
                ExperimentConfig.defaults()
        );

        assertTrue(SyntheticMetadataWorkflow.semanticSpike(result));
        assertEquals(AnomalyClass.SURGE_ANOMALY, result.hybrid().anomalyClass());
    }

    @Test
    void syntheticOfflineAnalysisReconstructsParaphrasedSemanticSurge() {
        List<LogDocument> logs = SyntheticLogDataset.historicalLogs();
        ScenarioResult result = SyntheticMetadataWorkflow.analyze(
                SyntheticLogDataset.probes().stream()
                        .filter(probe -> probe.name().equals("Q. Paraphrased Semantic Surge"))
                        .findFirst()
                        .orElseThrow(),
                logs,
                ExperimentConfig.defaults()
        );

        assertEquals(3, result.exactPatternBaseline().shortCount());
        assertEquals(18, result.exactPatternBaseline().longCount());
        assertEquals(26, result.temporal().shortCount());
        assertEquals(132, result.temporal().longCount());
        assertEquals(AnomalyClass.NORMAL_BEHAVIOR,
                PaperEvaluation.classify(result, EvaluationMethod.EXACT_PATTERN, ExperimentConfig.defaults()));
        assertEquals(AnomalyClass.NORMAL_BEHAVIOR,
                PaperEvaluation.classify(result, EvaluationMethod.TOP_K_RETRIEVAL, ExperimentConfig.defaults()));
        assertTrue(result.temporal().spikeRatio() > ExperimentConfig.defaults().spikeThreshold());
        assertEquals(AnomalyClass.SURGE_ANOMALY,
                PaperEvaluation.classify(result, EvaluationMethod.SEMANTIC_TEMPORAL, ExperimentConfig.defaults()));
        assertEquals(AnomalyClass.SURGE_ANOMALY, result.hybrid().anomalyClass());
    }

    @Test
    void distributedSemanticSurgeFamilyIsIsolatedFromExistingDbConnectivityFamily() {
        List<LogDocument> logs = SyntheticLogDataset.historicalLogs();
        Set<String> distributedPatterns = logs.stream()
                .filter(log -> log.incidentFamily().equals("db-connectivity-distributed-surge"))
                .map(LogDocument::pattern)
                .collect(Collectors.toSet());
        Set<String> existingDbPatterns = logs.stream()
                .filter(log -> log.incidentFamily().equals("db-connectivity"))
                .map(LogDocument::pattern)
                .collect(Collectors.toSet());

        assertEquals(158, logs.stream()
                .filter(log -> log.incidentFamily().equals("db-connectivity-distributed-surge"))
                .count());
        assertFalse(distributedPatterns.isEmpty());
        assertTrue(distributedPatterns.stream().noneMatch(existingDbPatterns::contains));
    }

    @Test
    void candidateFilterStatsUseSliceAndWarmupBoundaries() throws Exception {
        Path bglFile = tempDir.resolve("BGL.log");
        Files.writeString(bglFile, """
                - 1117838570 2005.06.03 node 2005-06-03-00.00.00.000000 node RAS KERNEL INFO instruction cache parity error corrected
                APPREAD 1117838570 2005.06.03 node 2005-06-03-00.21.00.000000 node RAS KERNEL INFO ciod: failed to read message prefix on control stream
                APPREAD 1117838570 2005.06.03 node 2005-06-03-00.24.00.000000 node RAS KERNEL INFO ciod: failed to read message prefix on control stream
                - 1117838570 2005.06.03 node 2005-06-03-00.26.00.000000 node RAS KERNEL INFO lustre mount failed point /p/gb1
                - 1117838570 2005.06.03 node 2005-06-03-00.31.00.000000 node RAS KERNEL INFO instruction cache parity error corrected
                """);

        BglMetadataAnalysisWorkflow.CandidateFilterStats stats = BglMetadataAnalysisWorkflow.candidateFilterStats(
                new BglLogHubDataset(bglFile),
                Instant.parse("2005-06-03T00:20:00Z"),
                new BglEvaluationWorkflow.EvaluationRange(
                        Instant.parse("2005-06-03T00:20:00Z"),
                        Instant.parse("2005-06-03T00:30:00Z")
                )
        );

        assertEquals(3, stats.totalLogs());
        assertEquals(3, stats.candidateLogs());
        assertEquals(3, stats.strictCandidateLogs());
    }
}
