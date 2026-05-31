package com.loganomaly.report;

public record SpikeMetric(
        EvaluationMethod method,
        double spikeRecall,
        double averageDetectionDelayMinutes,
        double incidentCoverage
) {
}
