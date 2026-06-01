package com.loganomaly;

import com.loganomaly.config.ExperimentConfig;
import com.loganomaly.core.AnomalyClass;
import com.loganomaly.core.HybridAnomalyDetector;
import com.loganomaly.core.SemanticAnalysis;
import com.loganomaly.core.TemporalAnalysis;
import com.loganomaly.experiment.ScenarioProbe;
import com.loganomaly.experiment.ScenarioResult;
import com.loganomaly.experiment.SyntheticLogDataset;
import com.loganomaly.opensearch.LogDocument;
import com.loganomaly.report.EvaluationMethod;
import com.loganomaly.report.PaperEvaluation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpandedSyntheticDatasetTest {
    private static final ExperimentConfig CONFIG = ExperimentConfig.defaults();
    private static final List<LogDocument> LOGS = SyntheticLogDataset.historicalLogs();

    @Test
    void expandedDatasetHasMultipleIncidentFamiliesAndNormalCases() {
        Set<String> families = LOGS.stream()
                .map(LogDocument::incidentFamily)
                .collect(Collectors.toSet());
        long normalProbes = SyntheticLogDataset.probes().stream()
                .filter(probe -> probe.expectedClass() == AnomalyClass.NORMAL_BEHAVIOR)
                .count();

        assertTrue(families.containsAll(Set.of(
                "db-connectivity",
                "cache-corruption",
                "auth-token",
                "payment-api",
                "disk-storage",
                "memory-pressure",
                "network-latency",
                "routine-operations"
        )));
        assertTrue(normalProbes >= 3);
    }

    @Test
    void hardParaphraseSpikeShowsExactPatternMissAndSemanticTemporalCatch() {
        ScenarioResult result = analyze(probe("G. Hard DB Paraphrase Spike"));

        assertEquals(AnomalyClass.NORMAL_BEHAVIOR,
                PaperEvaluation.classify(result, EvaluationMethod.EXACT_PATTERN, CONFIG));
        assertEquals(AnomalyClass.SURGE_ANOMALY,
                PaperEvaluation.classify(result, EvaluationMethod.SEMANTIC_TEMPORAL, CONFIG));
        assertEquals(AnomalyClass.SURGE_ANOMALY, result.hybrid().anomalyClass());
    }

    @Test
    void novelRareEventShowsHybridCatchAndSemanticTemporalMiss() {
        ScenarioResult result = analyze(probe("K. Auth Token Novel Rare Event"));

        assertEquals(AnomalyClass.NORMAL_BEHAVIOR,
                PaperEvaluation.classify(result, EvaluationMethod.SEMANTIC_TEMPORAL, CONFIG));
        assertEquals(AnomalyClass.RARE_ANOMALY, result.hybrid().anomalyClass());
    }

    @Test
    void criticalCompoundScenariosClassifyAsCritical() {
        assertEquals(AnomalyClass.CRITICAL_ANOMALY,
                analyze(probe("E. Critical Compound Anomaly")).hybrid().anomalyClass());
        assertEquals(AnomalyClass.CRITICAL_ANOMALY,
                analyze(probe("P. Novel + Emerging Payment Failure")).hybrid().anomalyClass());
    }

    @Test
    void stableNoiseAndNearMissCasesRemainNormal() {
        assertEquals(AnomalyClass.NORMAL_BEHAVIOR,
                analyze(probe("F. Stable Operational Noise")).hybrid().anomalyClass());
        assertEquals(AnomalyClass.NORMAL_BEHAVIOR,
                analyze(probe("M. Near-Miss Similar Wording Normal Case")).hybrid().anomalyClass());
        assertEquals(AnomalyClass.NORMAL_BEHAVIOR,
                analyze(probe("N. High-Volume Routine Noise")).hybrid().anomalyClass());
        assertEquals(AnomalyClass.NORMAL_BEHAVIOR,
                analyze(probe("O. Low-Volume Known Stable Error")).hybrid().anomalyClass());
    }

    @Test
    void allExpandedScenarioProbesMatchExpectedHybridClass() {
        for (ScenarioProbe probe : SyntheticLogDataset.probes()) {
            assertEquals(probe.expectedClass(), analyze(probe).hybrid().anomalyClass(), probe.name());
        }
    }

    private static ScenarioProbe probe(String name) {
        return SyntheticLogDataset.probes().stream()
                .filter(probe -> probe.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static ScenarioResult analyze(ScenarioProbe probe) {
        List<LogDocument> neighbors = LOGS.stream()
                .sorted(Comparator.comparingDouble((LogDocument log) -> cosine(probe.embedding(), log.embedding())).reversed())
                .limit(CONFIG.topK())
                .toList();
        double maxSimilarity = neighbors.stream()
                .mapToDouble(log -> cosine(probe.embedding(), log.embedding()))
                .max()
                .orElse(0.0);

        int shortSemanticCount = countSemantic(
                probe,
                probe.observedAt().minus(CONFIG.shortWindow()),
                probe.observedAt()
        );
        int baselineSemanticCount = countSemantic(
                probe,
                probe.observedAt().minus(CONFIG.shortWindow()).minus(CONFIG.baselineWindow()),
                probe.observedAt().minus(CONFIG.shortWindow())
        );
        SemanticAnalysis semantic = new SemanticAnalysis(
                baselineSemanticCount,
                maxSimilarity,
                CONFIG.noveltyThreshold()
        );
        TemporalAnalysis temporal = new TemporalAnalysis(
                shortSemanticCount,
                baselineSemanticCount,
                CONFIG.shortWindow(),
                CONFIG.baselineWindow(),
                CONFIG.spikeThreshold()
        );
        TemporalAnalysis exact = new TemporalAnalysis(
                countPattern(probe, probe.observedAt().minus(CONFIG.shortWindow()), probe.observedAt()),
                countPattern(probe, probe.observedAt().minus(CONFIG.shortWindow()).minus(CONFIG.baselineWindow()),
                        probe.observedAt().minus(CONFIG.shortWindow())),
                CONFIG.shortWindow(),
                CONFIG.baselineWindow(),
                CONFIG.spikeThreshold()
        );

        return new ScenarioResult(
                probe,
                semantic,
                temporal,
                new HybridAnomalyDetector(0.5, 0.5).analyze(semantic, temporal),
                List.of(),
                exact
        );
    }

    private static int countSemantic(ScenarioProbe probe, Instant fromInclusive, Instant toExclusive) {
        return (int) LOGS.stream()
                .filter(log -> !log.timestamp().isBefore(fromInclusive) && log.timestamp().isBefore(toExclusive))
                .filter(log -> cosine(probe.embedding(), log.embedding()) >= CONFIG.similarityThreshold())
                .count();
    }

    private static int countPattern(ScenarioProbe probe, Instant fromInclusive, Instant toExclusive) {
        return (int) LOGS.stream()
                .filter(log -> !log.timestamp().isBefore(fromInclusive) && log.timestamp().isBefore(toExclusive))
                .filter(log -> log.pattern().equals(probe.pattern()))
                .count();
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0.0;
        double leftMagnitude = 0.0;
        double rightMagnitude = 0.0;
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            dot += left[i] * right[i];
            leftMagnitude += left[i] * left[i];
            rightMagnitude += right[i] * right[i];
        }
        if (leftMagnitude == 0.0 || rightMagnitude == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftMagnitude) * Math.sqrt(rightMagnitude));
    }
}
