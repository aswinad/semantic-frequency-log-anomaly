package com.loganomaly.report;

import com.loganomaly.config.ExperimentConfig;
import com.loganomaly.core.AnomalyClass;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class PublicDatasetEvaluation {
    private PublicDatasetEvaluation() {
    }

    public static List<MethodPerformance> overallPerformance(List<PublicDatasetEvaluationResult> results) {
        return methodComparison(results);
    }

    public static List<MethodPerformance> methodComparison(List<PublicDatasetEvaluationResult> results) {
        return List.of(
                performance(EvaluationMethod.EXACT_PATTERN, results),
                performance(EvaluationMethod.TOP_K_RETRIEVAL, results),
                performance(EvaluationMethod.SEMANTIC_FREQUENCY, results),
                performance(EvaluationMethod.SEMANTIC_TEMPORAL, results),
                performance(EvaluationMethod.HYBRID_FRAMEWORK, results)
        );
    }

    public static List<MethodPerformance> ablationStudy(List<PublicDatasetEvaluationResult> results) {
        return List.of(
                performance(EvaluationMethod.EXACT_PATTERN, results),
                performance(EvaluationMethod.TOP_K_RETRIEVAL, results),
                performance(EvaluationMethod.SEMANTIC_FREQUENCY, results),
                performance(EvaluationMethod.SEMANTIC_TEMPORAL, results),
                performance(EvaluationMethod.HYBRID_FRAMEWORK, results)
        );
    }

    public static List<ClusterMetric> semanticClusterMetrics(List<PublicDatasetEvaluationResult> results) {
        return List.of(
                clusterMetric(EvaluationMethod.EXACT_PATTERN, results),
                clusterMetric(EvaluationMethod.TOP_K_RETRIEVAL, results),
                clusterMetric(EvaluationMethod.SEMANTIC_FREQUENCY, results),
                clusterMetric(EvaluationMethod.HYBRID_FRAMEWORK, results)
        );
    }

    public static List<SpikeMetric> spikeMetrics(List<PublicDatasetEvaluationResult> results) {
        return List.of(
                spikeMetric(EvaluationMethod.EXACT_PATTERN, results),
                spikeMetric(EvaluationMethod.TOP_K_RETRIEVAL, results),
                spikeMetric(EvaluationMethod.SEMANTIC_FREQUENCY, results),
                spikeMetric(EvaluationMethod.SEMANTIC_TEMPORAL, results),
                spikeMetric(EvaluationMethod.HYBRID_FRAMEWORK, results)
        );
    }

    public static List<MethodPerformance> pipelineValidation(List<PublicDatasetEvaluationResult> results) {
        return List.of(
                performance(EvaluationMethod.EXACT_PATTERN, results),
                performance(EvaluationMethod.SEMANTIC_TEMPORAL, results),
                performance(EvaluationMethod.HYBRID_FRAMEWORK, results)
        );
    }

    public static DetectionMetrics detectionMetrics(EvaluationMethod method, List<PublicDatasetEvaluationResult> results) {
        return performance(method, results).metrics();
    }

    private static MethodPerformance performance(EvaluationMethod method, List<PublicDatasetEvaluationResult> results) {
        long truePositive = 0;
        long falsePositive = 0;
        long trueNegative = 0;
        long falseNegative = 0;

        for (PublicDatasetEvaluationResult result : results) {
            boolean predictedAnomaly = isPositiveForResult(result, method);
            long weight = result.eventCount();
            if (predictedAnomaly && result.actualAnomaly()) {
                truePositive += weight;
            } else if (predictedAnomaly) {
                falsePositive += weight;
            } else if (result.actualAnomaly()) {
                falseNegative += weight;
            } else {
                trueNegative += weight;
            }
        }
        return new MethodPerformance(method, DetectionMetrics.fromCounts(truePositive, falsePositive, trueNegative, falseNegative));
    }

    private static ClusterMetric clusterMetric(EvaluationMethod method, List<PublicDatasetEvaluationResult> results) {
        long totalPositiveEvents = results.stream()
                .filter(PublicDatasetEvaluationResult::actualAnomaly)
                .mapToLong(PublicDatasetEvaluationResult::eventCount)
                .sum();
        if (totalPositiveEvents == 0) {
            return new ClusterMetric(method, 0.0, 0.0);
        }

        long capturedPositiveEvents = results.stream()
                .filter(PublicDatasetEvaluationResult::actualAnomaly)
                .filter(result -> isPositiveForResult(result, method))
                .mapToLong(PublicDatasetEvaluationResult::eventCount)
                .sum();
        Set<String> capturedPatterns = new LinkedHashSet<>();
        for (PublicDatasetEvaluationResult result : results) {
            if (result.actualAnomaly() && isPositiveForResult(result, method)) {
                capturedPatterns.add(result.scenarioResult().probe().pattern());
            }
        }

        return new ClusterMetric(
                method,
                (double) capturedPositiveEvents / totalPositiveEvents,
                capturedPatterns.isEmpty() ? 0.0 : capturedPatterns.size()
        );
    }

    private static SpikeMetric spikeMetric(EvaluationMethod method, List<PublicDatasetEvaluationResult> results) {
        List<PublicDatasetEvaluationResult> positives = results.stream()
                .filter(PublicDatasetEvaluationResult::actualAnomaly)
                .toList();
        if (positives.isEmpty()) {
            return new SpikeMetric(method, 0.0, 0.0, 0.0);
        }

        long totalPositiveEvents = positives.stream().mapToLong(PublicDatasetEvaluationResult::eventCount).sum();
        long detectedSpikeEvents = positives.stream()
                .filter(result -> isSpike(result.predictedClass(method)))
                .mapToLong(PublicDatasetEvaluationResult::eventCount)
                .sum();
        long detectedPositiveEvents = positives.stream()
                .filter(result -> isPositiveForResult(result, method))
                .mapToLong(PublicDatasetEvaluationResult::eventCount)
                .sum();

        return new SpikeMetric(
                method,
                totalPositiveEvents == 0 ? 0.0 : (double) detectedSpikeEvents / totalPositiveEvents,
                0.0,
                totalPositiveEvents == 0 ? 0.0 : (double) detectedPositiveEvents / totalPositiveEvents
        );
    }

    public static boolean isAnomaly(AnomalyClass anomalyClass) {
        return anomalyClass != AnomalyClass.NORMAL_BEHAVIOR;
    }

    public static boolean isPositiveForResult(PublicDatasetEvaluationResult result, EvaluationMethod method) {
        AnomalyClass predictedClass = result.predictedClass(method);
        if (isBglResult(result)) {
            return isSpike(predictedClass);
        }
        return isAnomaly(predictedClass);
    }

    public static boolean isSpike(AnomalyClass anomalyClass) {
        return anomalyClass == AnomalyClass.SURGE_ANOMALY || anomalyClass == AnomalyClass.CRITICAL_ANOMALY;
    }

    private static boolean isBglResult(PublicDatasetEvaluationResult result) {
        return result.scenarioResult().probe().incidentFamily().startsWith("bgl-");
    }
}
