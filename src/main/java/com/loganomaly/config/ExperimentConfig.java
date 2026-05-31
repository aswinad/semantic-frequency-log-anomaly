package com.loganomaly.config;

import java.time.Duration;

public record ExperimentConfig(
        Duration shortWindow,
        Duration baselineWindow,
        int topK,
        int noveltyThreshold,
        double similarityThreshold,
        double spikeThreshold
) {
    public static ExperimentConfig defaults() {
        return new ExperimentConfig(
                Duration.ofMinutes(5),
                Duration.ofMinutes(55),
                5,
                3,
                0.85,
                2.0
        );
    }
}
