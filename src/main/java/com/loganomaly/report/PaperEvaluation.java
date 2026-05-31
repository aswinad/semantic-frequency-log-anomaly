package com.loganomaly.report;

import com.loganomaly.config.ExperimentConfig;
import com.loganomaly.core.AnomalyClass;
import com.loganomaly.core.BaselineClassifier;
import com.loganomaly.experiment.ScenarioResult;
import com.loganomaly.opensearch.LogDocument;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class PaperEvaluation {
    private static final BaselineClassifier BASELINE = new BaselineClassifier();

    private PaperEvaluation() {
    }

    public static List<MethodPerformance> overallPerformance(
            List<ScenarioResult> results,
            ExperimentConfig config
    ) {
        return List.of(
                performance(EvaluationMethod.EXACT_PATTERN, results, result -> classify(result, EvaluationMethod.EXACT_PATTERN, config)),
                performance(EvaluationMethod.TOP_K_RETRIEVAL, results, result -> classify(result, EvaluationMethod.TOP_K_RETRIEVAL, config)),
                performance(EvaluationMethod.SEMANTIC_FREQUENCY, results, result -> classify(result, EvaluationMethod.SEMANTIC_FREQUENCY, config)),
                performance(EvaluationMethod.HYBRID_FRAMEWORK, results, result -> classify(result, EvaluationMethod.HYBRID_FRAMEWORK, config))
        );
    }

    public static List<MethodPerformance> ablationStudy(
            List<ScenarioResult> results,
            ExperimentConfig config
    ) {
        return List.of(
                performance(EvaluationMethod.EXACT_PATTERN, results, result -> classify(result, EvaluationMethod.EXACT_PATTERN, config)),
                performance(EvaluationMethod.TOP_K_RETRIEVAL, results, result -> classify(result, EvaluationMethod.TOP_K_RETRIEVAL, config)),
                performance(EvaluationMethod.SEMANTIC_FREQUENCY, results, result -> classify(result, EvaluationMethod.SEMANTIC_FREQUENCY, config)),
                performance(EvaluationMethod.SEMANTIC_TEMPORAL, results, result -> classify(result, EvaluationMethod.SEMANTIC_TEMPORAL, config)),
                performance(EvaluationMethod.HYBRID_FRAMEWORK, results, result -> classify(result, EvaluationMethod.HYBRID_FRAMEWORK, config))
        );
    }

    public static List<ClusterMetric> semanticClusterMetrics(
            List<LogDocument> logs,
            List<ScenarioResult> results
    ) {
        return List.of(
                clusterMetric(EvaluationMethod.EXACT_PATTERN, logs, results),
                clusterMetric(EvaluationMethod.TOP_K_RETRIEVAL, logs, results),
                clusterMetric(EvaluationMethod.SEMANTIC_FREQUENCY, logs, results),
                clusterMetric(EvaluationMethod.HYBRID_FRAMEWORK, logs, results)
        );
    }

    public static List<SpikeMetric> spikeMetrics(
            List<LogDocument> logs,
            List<ScenarioResult> results,
            ExperimentConfig config
    ) {
        return List.of(
                spikeMetric(EvaluationMethod.EXACT_PATTERN, logs, results, config),
                spikeMetric(EvaluationMethod.TOP_K_RETRIEVAL, logs, results, config),
                spikeMetric(EvaluationMethod.SEMANTIC_FREQUENCY, logs, results, config),
                spikeMetric(EvaluationMethod.HYBRID_FRAMEWORK, logs, results, config)
        );
    }

    public static AnomalyClass classify(
            ScenarioResult result,
            EvaluationMethod method,
            ExperimentConfig config
    ) {
        return switch (method) {
            case EXACT_PATTERN -> BASELINE.statisticalOnly(result.exactPatternBaseline());
            case TOP_K_RETRIEVAL -> result.semantic().semanticSimilarityScore() < config.similarityThreshold()
                    ? AnomalyClass.RARE_ANOMALY
                    : AnomalyClass.NORMAL_BEHAVIOR;
            case SEMANTIC_FREQUENCY -> BASELINE.semanticOnly(result.semantic());
            case SEMANTIC_TEMPORAL -> BASELINE.statisticalOnly(result.temporal());
            case HYBRID_FRAMEWORK -> result.hybrid().anomalyClass();
        };
    }

    public static boolean isAnomaly(AnomalyClass anomalyClass) {
        return anomalyClass != AnomalyClass.NORMAL_BEHAVIOR;
    }

    public static boolean isSpike(AnomalyClass anomalyClass) {
        return anomalyClass == AnomalyClass.SURGE_ANOMALY || anomalyClass == AnomalyClass.CRITICAL_ANOMALY;
    }

    private static MethodPerformance performance(
            EvaluationMethod method,
            List<ScenarioResult> results,
            Function<ScenarioResult, AnomalyClass> classifier
    ) {
        int truePositive = 0;
        int falsePositive = 0;
        int trueNegative = 0;
        int falseNegative = 0;

        for (ScenarioResult result : results) {
            boolean expectedAnomaly = isAnomaly(result.probe().expectedClass());
            boolean predictedAnomaly = isAnomaly(classifier.apply(result));
            if (predictedAnomaly && expectedAnomaly) {
                truePositive++;
            } else if (predictedAnomaly) {
                falsePositive++;
            } else if (expectedAnomaly) {
                falseNegative++;
            } else {
                trueNegative++;
            }
        }
        return new MethodPerformance(method, DetectionMetrics.fromCounts(truePositive, falsePositive, trueNegative, falseNegative));
    }

    private static ClusterMetric clusterMetric(
            EvaluationMethod method,
            List<LogDocument> logs,
            List<ScenarioResult> results
    ) {
        Set<String> families = results.stream()
                .map(result -> result.probe().incidentFamily())
                .collect(Collectors.toSet());
        Map<String, Long> totalByFamily = logs.stream()
                .collect(Collectors.groupingBy(LogDocument::incidentFamily, Collectors.counting()));
        Map<String, Set<String>> patternsByFamily = logs.stream()
                .collect(Collectors.groupingBy(
                        LogDocument::incidentFamily,
                        Collectors.mapping(LogDocument::pattern, Collectors.toSet())
                ));

        double coverageTotal = 0.0;
        double fragmentationTotal = 0.0;
        int familyCount = 0;
        for (String family : families) {
            long total = totalByFamily.getOrDefault(family, 0L);
            if (total == 0) {
                continue;
            }
            List<ScenarioResult> familyResults = results.stream()
                    .filter(result -> result.probe().incidentFamily().equals(family))
                    .toList();
            long captured = familyResults.stream()
                    .mapToLong(result -> capturedCount(result, method))
                    .max()
                    .orElse(0L);
            coverageTotal += Math.min(1.0, (double) captured / total);
            fragmentationTotal += fragmentation(method, familyResults, patternsByFamily.getOrDefault(family, Set.of()));
            familyCount++;
        }

        if (familyCount == 0) {
            return new ClusterMetric(method, 0.0, 0.0);
        }
        return new ClusterMetric(method, coverageTotal / familyCount, fragmentationTotal / familyCount);
    }

    private static SpikeMetric spikeMetric(
            EvaluationMethod method,
            List<LogDocument> logs,
            List<ScenarioResult> results,
            ExperimentConfig config
    ) {
        List<ScenarioResult> spikeResults = results.stream()
                .filter(result -> isSpike(result.probe().expectedClass()))
                .toList();
        if (spikeResults.isEmpty()) {
            return new SpikeMetric(method, 0.0, 0.0, 0.0);
        }

        Map<String, Long> totalByFamily = logs.stream()
                .collect(Collectors.groupingBy(LogDocument::incidentFamily, Collectors.counting()));
        long detectedSpikes = spikeResults.stream()
                .filter(result -> isSpike(classify(result, method, config)))
                .count();
        double coverage = spikeResults.stream()
                .mapToDouble(result -> {
                    long total = totalByFamily.getOrDefault(result.probe().incidentFamily(), 0L);
                    return total == 0 ? 0.0 : Math.min(1.0, (double) capturedCount(result, method) / total);
                })
                .average()
                .orElse(0.0);
        return new SpikeMetric(method, (double) detectedSpikes / spikeResults.size(), 0.0, coverage);
    }

    private static long capturedCount(ScenarioResult result, EvaluationMethod method) {
        return switch (method) {
            case EXACT_PATTERN -> result.exactPatternBaseline().shortCount() + result.exactPatternBaseline().longCount();
            case TOP_K_RETRIEVAL -> result.neighbors().stream()
                    .filter(neighbor -> neighbor.incidentFamily().equals(result.probe().incidentFamily()))
                    .count();
            case SEMANTIC_FREQUENCY, SEMANTIC_TEMPORAL, HYBRID_FRAMEWORK ->
                    result.temporal().shortCount() + result.temporal().longCount();
        };
    }

    private static double fragmentation(
            EvaluationMethod method,
            Collection<ScenarioResult> familyResults,
            Set<String> familyPatterns
    ) {
        return switch (method) {
            case EXACT_PATTERN -> Math.max(1, familyPatterns.size());
            case TOP_K_RETRIEVAL -> familyResults.stream()
                    .flatMap(result -> result.neighbors().stream())
                    .map(neighbor -> neighbor.pattern())
                    .collect(Collectors.toSet())
                    .size();
            case SEMANTIC_FREQUENCY, SEMANTIC_TEMPORAL, HYBRID_FRAMEWORK -> 1.0;
        };
    }
}
