package com.loganomaly.app;

import com.loganomaly.config.ExperimentConfig;
import com.loganomaly.core.AnomalyClass;
import com.loganomaly.experiment.ScenarioResult;
import com.loganomaly.experiment.SyntheticLogDataset;
import com.loganomaly.loghub.BglLogHubDataset;
import com.loganomaly.opensearch.LogDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
