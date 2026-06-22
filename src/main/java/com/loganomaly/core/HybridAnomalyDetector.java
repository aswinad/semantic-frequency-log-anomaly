package com.loganomaly.core;

public final class HybridAnomalyDetector {
    private final double semanticWeight;
    private final double temporalWeight;

    public HybridAnomalyDetector(double semanticWeight, double temporalWeight) {
        if (semanticWeight < 0.0 || temporalWeight < 0.0) {
            throw new IllegalArgumentException("weights must be >= 0");
        }
        if (semanticWeight + temporalWeight == 0.0) {
            throw new IllegalArgumentException("at least one weight must be positive");
        }
        double total = semanticWeight + temporalWeight;
        this.semanticWeight = semanticWeight / total;
        this.temporalWeight = temporalWeight / total;
    }

    public HybridAnalysisResult analyze(SemanticAnalysis semantic, TemporalAnalysis temporal) {
        SemanticSignal semanticSignal = semantic.signal();
        TemporalSignal temporalSignal = temporal.signal();
        AnomalyClass anomalyClass = classify(semanticSignal, temporalSignal);
        double score = semanticWeight * semantic.noveltyScore() + temporalWeight * temporal.temporalSpikeScore();

        String summary = "semantic=%s, temporal=%s, class=%s, score=%.3f"
                .formatted(semanticSignal, temporalSignal, anomalyClass, score);
        return new HybridAnalysisResult(semanticSignal, temporalSignal, anomalyClass, score, summary);
    }

    public static AnomalyClass classify(SemanticSignal semanticSignal, TemporalSignal temporalSignal) {
        if (semanticSignal == SemanticSignal.NOVEL) {
            if (temporalSignal == TemporalSignal.SPIKE) {
                return AnomalyClass.CRITICAL_ANOMALY;
            }
            return AnomalyClass.RARE_ANOMALY;
        }
        if (temporalSignal == TemporalSignal.SPIKE) {
            return AnomalyClass.SURGE_ANOMALY;
        }
        return AnomalyClass.NORMAL_BEHAVIOR;
    }
}
