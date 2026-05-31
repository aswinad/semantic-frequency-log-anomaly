package com.loganomaly.opensearch;

public record KnnNeighbor(
        String id,
        String timestamp,
        String service,
        String pattern,
        String incidentFamily,
        String scenario,
        String message,
        double openSearchScore,
        double cosineSimilarity
) {
}
