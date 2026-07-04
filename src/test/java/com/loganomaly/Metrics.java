package com.loganomaly;

import com.loganomaly.core.AnomalyClass;

import java.util.List;
import java.util.function.Function;

record Metrics(double precision, double recall, double accuracy, double falsePositiveRate) {
    static Metrics evaluate(List<TestScenarios.Case> cases, Function<TestScenarios.Case, AnomalyClass> classifier) {
        int truePositive = 0;
        int falsePositive = 0;
        int trueNegative = 0;
        int falseNegative = 0;

        for (TestScenarios.Case testCase : cases) {
            boolean predictedAnomaly = classifier.apply(testCase) != AnomalyClass.NORMAL_BEHAVIOR;
            if (predictedAnomaly && testCase.anomalous()) {
                truePositive++;
            } else if (predictedAnomaly) {
                falsePositive++;
            } else if (testCase.anomalous()) {
                falseNegative++;
            } else {
                trueNegative++;
            }
        }

        double precision = truePositive + falsePositive == 0 ? 0.0 : (double) truePositive / (truePositive + falsePositive);
        double recall = truePositive + falseNegative == 0 ? 0.0 : (double) truePositive / (truePositive + falseNegative);
        double accuracy = (double) (truePositive + trueNegative) / cases.size();
        double falsePositiveRate = falsePositive + trueNegative == 0 ? 0.0 : (double) falsePositive / (falsePositive + trueNegative);
        return new Metrics(precision, recall, accuracy, falsePositiveRate);
    }
}
