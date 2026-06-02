package com.loganomaly.report;

public record DetectionMetrics(
        double precision,
        double recall,
        double f1Score,
        double falsePositiveRate,
        double falseNegativeRate
) {
    public static DetectionMetrics fromCounts(int truePositive, int falsePositive, int trueNegative, int falseNegative) {
        return fromCounts((long) truePositive, falsePositive, trueNegative, falseNegative);
    }

    public static DetectionMetrics fromCounts(long truePositive, long falsePositive, long trueNegative, long falseNegative) {
        double precision = safeDivide(truePositive, truePositive + falsePositive);
        double recall = safeDivide(truePositive, truePositive + falseNegative);
        double f1 = precision + recall == 0.0 ? 0.0 : 2.0 * precision * recall / (precision + recall);
        double falsePositiveRate = safeDivide(falsePositive, falsePositive + trueNegative);
        double falseNegativeRate = safeDivide(falseNegative, falseNegative + truePositive);
        return new DetectionMetrics(precision, recall, f1, falsePositiveRate, falseNegativeRate);
    }

    private static double safeDivide(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }
}
