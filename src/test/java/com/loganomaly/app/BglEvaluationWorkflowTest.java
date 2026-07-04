package com.loganomaly.app;

import com.loganomaly.embedding.DeterministicEmbeddingProvider;
import com.loganomaly.embedding.EmbeddingCache;
import com.loganomaly.config.BglCandidateMode;
import com.loganomaly.core.AnomalyClass;
import com.loganomaly.core.HybridAnomalyDetector;
import com.loganomaly.core.SemanticAnalysis;
import com.loganomaly.core.TemporalAnalysis;
import com.loganomaly.experiment.ScenarioProbe;
import com.loganomaly.experiment.ScenarioResult;
import com.loganomaly.loghub.BglLogHubDataset;
import com.loganomaly.loghub.BglLogRecord;
import com.loganomaly.opensearch.KnnNeighbor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BglEvaluationWorkflowTest {
    @TempDir
    Path tempDir;

    @Test
    void groupsByTemplateLabelAndBucketAfterWarmupAndSuspiciousPrefilter() throws Exception {
        Path bglFile = tempDir.resolve("BGL.log");
        Files.writeString(bglFile, """
                - 1117838570 2005.06.03 R02-M1-N0-C:J12-U11 2005-06-03-00.00.00.000000 R02-M1-N0-C:J12-U11 RAS KERNEL INFO instruction cache parity error corrected
                APPREAD 1117838570 2005.06.03 R02-M1-N0-C:J12-U11 2005-06-03-00.21.00.000000 R02-M1-N0-C:J12-U11 RAS KERNEL INFO ciod: failed to read message prefix on control stream (ciostream socket to 10.0.0.1:1234)
                APPREAD 1117838570 2005.06.03 R02-M1-N0-C:J12-U11 2005-06-03-00.24.00.000000 R02-M1-N0-C:J12-U11 RAS KERNEL INFO ciod: failed to read message prefix on control stream (ciostream socket to 10.0.0.2:5678)
                - 1117838570 2005.06.03 R02-M1-N0-C:J12-U11 2005-06-03-00.26.00.000000 R02-M1-N0-C:J12-U11 RAS KERNEL INFO lustre mount failed point /p/gb1
                - 1117838570 2005.06.03 R02-M1-N0-C:J12-U11 2005-06-03-00.27.00.000000 R02-M1-N0-C:J12-U11 RAS KERNEL INFO instruction cache parity error corrected
                """);

        BglLogHubDataset dataset = new BglLogHubDataset(bglFile);
        EmbeddingCache cache = new EmbeddingCache(tempDir.resolve("bgl-cache.jsonl"), "deterministic-synthetic-v1", 12);
        cache.load();
        Instant warmupCutoff = Instant.parse("2005-06-03T00:20:00Z");

        BglEvaluationWorkflow.ensureCandidateEmbeddings(
                dataset,
                cache,
                new DeterministicEmbeddingProvider(),
                8,
                warmupCutoff,
                new BglEvaluationWorkflow.EvaluationRange(
                        Instant.parse("2005-06-03T00:20:00Z"),
                        Instant.parse("2005-06-03T00:35:00Z")
                ),
                BglCandidateMode.FILTERED
        );

        List<BglEvaluationWorkflow.EvaluationCandidate> candidates = BglEvaluationWorkflow.buildCandidates(
                dataset,
                cache,
                Duration.ofMinutes(5),
                warmupCutoff,
                new BglEvaluationWorkflow.EvaluationRange(
                        Instant.parse("2005-06-03T00:20:00Z"),
                        Instant.parse("2005-06-03T00:35:00Z")
                ),
                BglCandidateMode.FILTERED
        );

        assertEquals(2, candidates.size());
        BglEvaluationWorkflow.EvaluationCandidate anomaly = candidates.stream()
                .filter(candidate -> candidate.groundTruth() == com.loganomaly.report.BinaryGroundTruth.ANOMALY)
                .findFirst()
                .orElseThrow();
        BglEvaluationWorkflow.EvaluationCandidate normal = candidates.stream()
                .filter(candidate -> candidate.groundTruth() == com.loganomaly.report.BinaryGroundTruth.NORMAL)
                .findFirst()
                .orElseThrow();

        assertEquals("APPREAD", anomaly.nativeLabel());
        assertEquals(2, anomaly.eventCount());
        assertEquals(Instant.parse("2005-06-03T00:25:00Z"), anomaly.observedAt());
        assertEquals("-", normal.nativeLabel());
        assertEquals(1, normal.eventCount());
        assertEquals(Instant.parse("2005-06-03T00:30:00Z"), normal.observedAt());
    }

    @Test
    void suspiciousPrefilterIsLabelBlind() {
        BglLogRecord suspiciousNormal = new BglLogRecord(
                1,
                "-",
                Instant.parse("2005-06-03T00:26:00Z"),
                "node",
                "RAS|KERNEL|INFO",
                "lustre mount failed point /p/gb1",
                "lustre mount failed point /p/gb<n>"
        );
        BglLogRecord suspiciousAnomaly = new BglLogRecord(
                2,
                "APPUNAV",
                Instant.parse("2005-06-03T00:26:00Z"),
                "node",
                "RAS|APP|INFO",
                "resource temporarily unavailable",
                "resource temporarily unavailable"
        );
        BglLogRecord routineNormal = new BglLogRecord(
                3,
                "-",
                Instant.parse("2005-06-03T00:26:00Z"),
                "node",
                "RAS|KERNEL|INFO",
                "instruction cache parity error corrected",
                "instruction cache parity error corrected"
        );

        assertEquals(true, BglEvaluationWorkflow.isSuspiciousCandidate(suspiciousNormal));
        assertEquals(true, BglEvaluationWorkflow.isSuspiciousCandidate(suspiciousAnomaly));
        assertEquals(false, BglEvaluationWorkflow.isSuspiciousCandidate(routineNormal));
    }

    @Test
    void restrictsEvaluationToConfiguredContiguousRange() throws Exception {
        Path bglFile = tempDir.resolve("BGL.log");
        Files.writeString(bglFile, """
                APPREAD 1117838570 2005.06.03 node 2005-06-03-00.26.00.000000 node RAS KERNEL INFO ciod: failed to read message prefix on control stream (ciostream socket to 10.0.0.1:1234)
                APPREAD 1117838570 2005.06.03 node 2005-06-03-00.31.00.000000 node RAS KERNEL INFO ciod: failed to read message prefix on control stream (ciostream socket to 10.0.0.2:1234)
                APPREAD 1117838570 2005.06.03 node 2005-06-03-00.36.00.000000 node RAS KERNEL INFO ciod: failed to read message prefix on control stream (ciostream socket to 10.0.0.3:1234)
                """);

        BglLogHubDataset dataset = new BglLogHubDataset(bglFile);
        EmbeddingCache cache = new EmbeddingCache(tempDir.resolve("bgl-cache-range.jsonl"), "deterministic-synthetic-v1", 12);
        cache.load();
        Instant warmupCutoff = Instant.parse("2005-06-03T00:20:00Z");
        BglEvaluationWorkflow.EvaluationRange evaluationRange = new BglEvaluationWorkflow.EvaluationRange(
                Instant.parse("2005-06-03T00:30:00Z"),
                Instant.parse("2005-06-03T00:36:00Z")
        );

        BglEvaluationWorkflow.ensureCandidateEmbeddings(
                dataset,
                cache,
                new DeterministicEmbeddingProvider(),
                8,
                warmupCutoff,
                evaluationRange,
                BglCandidateMode.FILTERED
        );

        List<BglEvaluationWorkflow.EvaluationCandidate> candidates = BglEvaluationWorkflow.buildCandidates(
                dataset,
                cache,
                Duration.ofMinutes(5),
                warmupCutoff,
                evaluationRange,
                BglCandidateMode.FILTERED
        );

        assertEquals(1, candidates.size());
        assertEquals(Instant.parse("2005-06-03T00:35:00Z"), candidates.get(0).observedAt());
        assertEquals(1, candidates.get(0).eventCount());
    }

    @Test
    void allCandidateModeIncludesRoutineRowsInsideSlice() throws Exception {
        Path bglFile = tempDir.resolve("BGL-all.log");
        Files.writeString(bglFile, """
                - 1117838570 2005.06.03 node 2005-06-03-00.26.00.000000 node RAS KERNEL INFO instruction cache parity error corrected
                APPREAD 1117838570 2005.06.03 node 2005-06-03-00.27.00.000000 node RAS KERNEL INFO ciod: failed to read message prefix on control stream (ciostream socket to 10.0.0.1:1234)
                """);

        BglLogHubDataset dataset = new BglLogHubDataset(bglFile);
        EmbeddingCache cache = new EmbeddingCache(tempDir.resolve("bgl-cache-all.jsonl"), "deterministic-synthetic-v1", 12);
        cache.load();
        Instant warmupCutoff = Instant.parse("2005-06-03T00:20:00Z");
        BglEvaluationWorkflow.EvaluationRange evaluationRange = new BglEvaluationWorkflow.EvaluationRange(
                Instant.parse("2005-06-03T00:20:00Z"),
                Instant.parse("2005-06-03T00:35:00Z")
        );

        BglEvaluationWorkflow.ensureCandidateEmbeddings(
                dataset,
                cache,
                new DeterministicEmbeddingProvider(),
                8,
                warmupCutoff,
                evaluationRange,
                BglCandidateMode.ALL
        );

        List<BglEvaluationWorkflow.EvaluationCandidate> candidates = BglEvaluationWorkflow.buildCandidates(
                dataset,
                cache,
                Duration.ofMinutes(5),
                warmupCutoff,
                evaluationRange,
                BglCandidateMode.ALL
        );

        assertEquals(2, candidates.size());
    }

    @Test
    void strictCandidateModeIsNarrowerThanFiltered() {
        BglLogRecord timedRecord = new BglLogRecord(
                1,
                "APPTO",
                Instant.parse("2005-06-03T00:26:00Z"),
                "node",
                "RAS|APP|INFO",
                "connection timed out while waiting for reply",
                "connection timed out while waiting for reply"
        );
        BglLogRecord panicRecord = new BglLogRecord(
                2,
                "KERNRTSP",
                Instant.parse("2005-06-03T00:26:00Z"),
                "node",
                "RAS|KERNEL|INFO",
                "rts panic! - stopping execution",
                "rts panic! - stopping execution"
        );

        assertTrue(BglEvaluationWorkflow.isSuspiciousCandidate(timedRecord));
        assertFalse(BglEvaluationWorkflow.isStrictSuspiciousCandidate(timedRecord));
        assertTrue(BglEvaluationWorkflow.isStrictSuspiciousCandidate(panicRecord));
    }

    @Test
    void looseSemanticCanAlertWithoutStrictAgreement() {
        var result = scenarioResult(1, 100, 12, 20, 0.95);

        assertTrue(BglEvaluationWorkflow.looseSemanticPositive(result, 3));
        assertFalse(BglEvaluationWorkflow.strictAgreementPositive(result, 3));
    }

    @Test
    void phaseLoggingHelpersProduceExpectedMessages() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(output);

        BglEvaluationWorkflow.logPhaseStart(printStream, 3, 5, "Main evaluation");
        BglEvaluationWorkflow.logPhaseComplete(printStream, "Main evaluation", Duration.ofSeconds(12));
        printStream.println(BglEvaluationWorkflow.progressMessage("Threshold sweep 2/4", 700, 913));

        String text = output.toString();
        assertTrue(text.contains("Phase 3/5: Main evaluation"));
        assertTrue(text.contains("Completed Main evaluation in 12s"));
        assertTrue(text.contains("Threshold sweep 2/4 progress: 700/913 rows (76.7%)"));
    }

    @Test
    void labeledCandidateEmbeddingLoggingShowsPhaseLabel() throws Exception {
        Path bglFile = tempDir.resolve("BGL-labeled.log");
        Files.writeString(bglFile, """
                APPREAD 1117838570 2005.06.03 node 2005-06-03-00.26.00.000000 node RAS KERNEL INFO ciod: failed to read message prefix on control stream (ciostream socket to 10.0.0.1:1234)
                """);
        BglLogHubDataset dataset = new BglLogHubDataset(bglFile);
        EmbeddingCache cache = new EmbeddingCache(tempDir.resolve("bgl-cache-labeled.jsonl"), "deterministic-synthetic-v1", 12);
        cache.load();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(output));
        try {
            BglEvaluationWorkflow.ensureCandidateEmbeddings(
                    dataset,
                    cache,
                    new DeterministicEmbeddingProvider(),
                    8,
                    Instant.parse("2005-06-03T00:20:00Z"),
                    new BglEvaluationWorkflow.EvaluationRange(
                            Instant.parse("2005-06-03T00:20:00Z"),
                            Instant.parse("2005-06-03T00:35:00Z")
                    ),
                    BglCandidateMode.FILTERED,
                    "strategy:all_lines"
            );
        } finally {
            System.setOut(original);
        }

        String text = output.toString();
        assertTrue(text.contains("[strategy:all_lines] candidate templates missing from cache: 1"));
        assertTrue(text.contains("[strategy:all_lines] cached evaluation embeddings 1/1"));
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
