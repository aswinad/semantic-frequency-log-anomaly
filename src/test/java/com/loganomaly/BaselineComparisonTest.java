package com.loganomaly;

import com.loganomaly.core.BaselineClassifier;
import com.loganomaly.core.HybridAnomalyDetector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineComparisonTest {
    @Test
    void hybridClassifierCoversCasesMissedBySingleSignalBaselines() {
        List<TestScenarios.Case> cases = List.of(
                TestScenarios.caseOf("stable known error", 5, 0.95, 8, 120, false),
                TestScenarios.caseOf("novel authentication failure", 0, 0.15, 1, 120, true),
                TestScenarios.caseOf("database timeout spike", 5, 0.94, 30, 120, true),
                TestScenarios.caseOf("cache corruption compound", 0, 0.10, 30, 120, true)
        );

        HybridAnomalyDetector hybrid = new HybridAnomalyDetector(0.5, 0.5);
        BaselineClassifier baseline = new BaselineClassifier();

        Metrics semanticOnly = Metrics.evaluate(cases, c -> baseline.semanticOnly(c.semantic()));
        Metrics statisticalOnly = Metrics.evaluate(cases, c -> baseline.statisticalOnly(c.temporal()));
        Metrics hybridMetrics = Metrics.evaluate(cases, c -> hybrid.analyze(c.semantic(), c.temporal()).anomalyClass());

        assertTrue(hybridMetrics.recall() > semanticOnly.recall());
        assertTrue(hybridMetrics.recall() > statisticalOnly.recall());
        assertTrue(hybridMetrics.accuracy() > semanticOnly.accuracy());
        assertTrue(hybridMetrics.accuracy() > statisticalOnly.accuracy());
    }
}
