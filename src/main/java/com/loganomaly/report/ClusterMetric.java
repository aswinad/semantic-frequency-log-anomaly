package com.loganomaly.report;

public record ClusterMetric(
        EvaluationMethod method,
        double clusterCoverage,
        double averageClustersPerIncident
) {
}
