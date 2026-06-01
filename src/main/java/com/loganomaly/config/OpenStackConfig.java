package com.loganomaly.config;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

public record OpenStackConfig(
        Path loghubDir,
        String indexName,
        boolean recreateIndex,
        Path embeddingCache,
        int batchSize,
        int indexBatchSize,
        Instant experimentAnchor,
        Duration shortWindow,
        Duration baselineWindow
) {
}
