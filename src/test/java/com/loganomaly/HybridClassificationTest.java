package com.loganomaly;

import com.loganomaly.core.AnomalyClass;
import com.loganomaly.core.HybridAnalysisResult;
import com.loganomaly.core.HybridAnomalyDetector;
import com.loganomaly.core.SemanticAnalysis;
import com.loganomaly.core.TemporalAnalysis;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HybridClassificationTest {
    private final HybridAnomalyDetector detector = new HybridAnomalyDetector(0.5, 0.5);

    @ParameterizedTest
    @CsvSource({
            "1, 0.20, 20, 100, CRITICAL_ANOMALY",
            "1, 0.20, 2, 100, RARE_ANOMALY",
            "5, 0.92, 20, 100, SURGE_ANOMALY",
            "5, 0.92, 2, 100, NORMAL_BEHAVIOR"
    })
    void classifiesTheFourSemanticTemporalCombinations(
            int semanticCount,
            double similarity,
            int shortCount,
            int longCount,
            AnomalyClass expectedClass
    ) {
        SemanticAnalysis semantic = new SemanticAnalysis(semanticCount, similarity, 3);
        TemporalAnalysis temporal = TestScenarios.temporal(shortCount, longCount);

        HybridAnalysisResult result = detector.analyze(semantic, temporal);

        assertEquals(expectedClass, result.anomalyClass());
    }
}
