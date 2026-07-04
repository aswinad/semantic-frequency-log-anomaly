package com.loganomaly.report;

public record MethodPerformance(
        EvaluationMethod method,
        DetectionMetrics metrics
) {
}
