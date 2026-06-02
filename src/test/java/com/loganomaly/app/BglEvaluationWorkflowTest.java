package com.loganomaly.app;

import com.loganomaly.embedding.DeterministicEmbeddingProvider;
import com.loganomaly.embedding.EmbeddingCache;
import com.loganomaly.config.BglCandidateMode;
import com.loganomaly.loghub.BglLogHubDataset;
import com.loganomaly.loghub.BglLogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
