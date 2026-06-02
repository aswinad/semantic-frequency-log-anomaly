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
        int minimumSupport,
        List<Double> similaritySweep,
        BglEvalRangeMode evalRangeMode,
        Optional<Instant> evalStart,
        Duration evalDuration
) {
}
