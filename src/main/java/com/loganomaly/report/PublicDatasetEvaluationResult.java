package com.loganomaly.report;

import com.loganomaly.core.AnomalyClass;
import com.loganomaly.experiment.ScenarioResult;

public record PublicDatasetEvaluationResult(
        String candidateName,
        BinaryGroundTruth groundTruth,
        String nativeLabel,
        long eventCount,
        ScenarioResult scenarioResult,
        AnomalyClass exactPatternClass,
        AnomalyClass topKClass,
        AnomalyClass semanticFrequencyClass,
        AnomalyClass semanticTemporalClass
) {
    public AnomalyClass predictedClass(EvaluationMethod method) {
        return switch (method) {
            case EXACT_PATTERN -> exactPatternClass;
            case TOP_K_RETRIEVAL -> topKClass;
            case SEMANTIC_FREQUENCY -> semanticFrequencyClass;
            case SEMANTIC_TEMPORAL -> semanticTemporalClass;
            case HYBRID_FRAMEWORK -> scenarioResult.hybrid().anomalyClass();
        };
    }

    public boolean actualAnomaly() {
        return groundTruth == BinaryGroundTruth.ANOMALY;
    }
}
