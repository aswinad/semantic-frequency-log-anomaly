package com.loganomaly.core;

public record HybridAnalysisResult(
        SemanticSignal semanticSignal,
        TemporalSignal temporalSignal,
        AnomalyClass anomalyClass,
        double hybridAnomalyScore,
        String reasoningSummary
) {
}
