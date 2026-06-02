package com.loganomaly;

import com.loganomaly.core.AnomalyClass;
import com.loganomaly.experiment.ScenarioResult;
import com.loganomaly.report.BinaryGroundTruth;
import com.loganomaly.report.EvaluationMethod;
import com.loganomaly.report.MethodPerformance;
import com.loganomaly.report.PublicDatasetEvaluation;
import com.loganomaly.report.PublicDatasetEvaluationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublicDatasetEvaluationTest {
    @Test
    void weightedBinaryMetricsUseEventCounts() {
        ScenarioResult positiveDetected = PaperEvaluationTest.result(
                "positive-detected", "openstack-anomaly-vm", "claim-success",
                AnomalyClass.SURGE_ANOMALY, 10, 100, 10, 100, 0.95
        );
        ScenarioResult positiveMissed = PaperEvaluationTest.result(
                "positive-missed", "openstack-anomaly-vm", "novel-positive",
                AnomalyClass.RARE_ANOMALY, 0, 100, 0, 100, 0.20
        );
        ScenarioResult negativeFalsePositive = PaperEvaluationTest.result(
                "negative-fp", "openstack-normal", "normal-pattern",
                AnomalyClass.NORMAL_BEHAVIOR, 10, 100, 10, 100, 0.95
        );
        ScenarioResult negativeTrueNegative = PaperEvaluationTest.result(
                "negative-tn", "openstack-normal", "routine-normal",
                AnomalyClass.NORMAL_BEHAVIOR, 0, 100, 0, 100, 0.95
        );

        List<PublicDatasetEvaluationResult> results = List.of(
                new PublicDatasetEvaluationResult("p1", BinaryGroundTruth.ANOMALY, "openstack-anomaly-vm", 5, positiveDetected,
                        AnomalyClass.SURGE_ANOMALY, AnomalyClass.NORMAL_BEHAVIOR, AnomalyClass.NORMAL_BEHAVIOR, AnomalyClass.SURGE_ANOMALY),
                new PublicDatasetEvaluationResult("p2", BinaryGroundTruth.ANOMALY, "openstack-anomaly-vm", 3, positiveMissed,
                        AnomalyClass.NORMAL_BEHAVIOR, AnomalyClass.NORMAL_BEHAVIOR, AnomalyClass.RARE_ANOMALY, AnomalyClass.NORMAL_BEHAVIOR),
                new PublicDatasetEvaluationResult("n1", BinaryGroundTruth.NORMAL, "openstack-normal", 2, negativeFalsePositive,
                        AnomalyClass.SURGE_ANOMALY, AnomalyClass.NORMAL_BEHAVIOR, AnomalyClass.NORMAL_BEHAVIOR, AnomalyClass.SURGE_ANOMALY),
                new PublicDatasetEvaluationResult("n2", BinaryGroundTruth.NORMAL, "openstack-normal", 10, negativeTrueNegative,
                        AnomalyClass.NORMAL_BEHAVIOR, AnomalyClass.NORMAL_BEHAVIOR, AnomalyClass.NORMAL_BEHAVIOR, AnomalyClass.NORMAL_BEHAVIOR)
        );

        MethodPerformance exactPattern = PublicDatasetEvaluation.overallPerformance(results).stream()
                .filter(performance -> performance.method() == EvaluationMethod.EXACT_PATTERN)
                .findFirst()
                .orElseThrow();

        assertEquals(5.0 / 7.0, exactPattern.metrics().precision(), 1e-9);
        assertEquals(5.0 / 8.0, exactPattern.metrics().recall(), 1e-9);
    }
}
