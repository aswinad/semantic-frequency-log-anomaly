package com.loganomaly.config;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public record BglConfig(
        Path loghubFile,
        String indexName,
        boolean recreateIndex,
        Path embeddingCache,
        int batchSize,
        int indexBatchSize,
        Duration shortWindow,
        Duration baselineWindow,
        Duration evalBucket,
        BglCandidateMode candidateMode,
        boolean verboseRowLogging,
        int minimumHistoricalSupport,
        int minimumAlertShortSupport,
        List<Double> similaritySweep,
        BglEvalRangeMode evalRangeMode,
        Optional<Instant> evalStart,
        Duration evalDuration,
        Path ablationCache,
        boolean clearAblationCache,
        boolean ablationParallel,
        int ablationMaxWorkers
) {
}
