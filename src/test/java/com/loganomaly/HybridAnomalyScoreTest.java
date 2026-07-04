package com.loganomaly;

import com.loganomaly.core.AnomalyClass;
import com.loganomaly.core.HybridAnalysisResult;
import com.loganomaly.core.HybridAnomalyDetector;
import com.loganomaly.core.SemanticAnalysis;
import com.loganomaly.core.TemporalAnalysis;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridAnomalyScoreTest {
    @Test
    void combinesSemanticNoveltyAndTemporalSpikeScores() {
        HybridAnomalyDetector detector = new HybridAnomalyDetector(0.6, 0.4);
        SemanticAnalysis semantic = new SemanticAnalysis(1, 0.25, 3);
        TemporalAnalysis temporal = TestScenarios.temporal(30, 120);

        HybridAnalysisResult result = detector.analyze(semantic, temporal);

        assertEquals(0.85, result.hybridAnomalyScore(), 0.0001);
    }

    @Test
    void criticalCompoundAnomalyRanksAboveRareOnlyAnomaly() {
        HybridAnomalyDetector detector = new HybridAnomalyDetector(0.5, 0.5);

        HybridAnalysisResult rareOnly = detector.analyze(
                new SemanticAnalysis(0, 0.10, 3),
                TestScenarios.temporal(1, 120)
        );
        HybridAnalysisResult compound = detector.analyze(
                new SemanticAnalysis(0, 0.10, 3),
                TestScenarios.temporal(30, 120)
        );

        assertTrue(compound.hybridAnomalyScore() > rareOnly.hybridAnomalyScore());
        assertEquals(AnomalyClass.CRITICAL_ANOMALY, compound.anomalyClass());
    }
}
