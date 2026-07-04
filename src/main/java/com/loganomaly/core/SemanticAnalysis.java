package com.loganomaly.core;

public record SemanticAnalysis(
        int semanticCount,
        double semanticSimilarityScore,
        int noveltyThreshold
) {
    public SemanticAnalysis {
        if (semanticCount < 0) {
            throw new IllegalArgumentException("semanticCount must be >= 0");
        }
        if (semanticSimilarityScore < 0.0 || semanticSimilarityScore > 1.0) {
            throw new IllegalArgumentException("semanticSimilarityScore must be in [0, 1]");
        }
        if (noveltyThreshold < 0) {
            throw new IllegalArgumentException("noveltyThreshold must be >= 0");
        }
    }

    public SemanticSignal signal() {
        return semanticCount < noveltyThreshold ? SemanticSignal.NOVEL : SemanticSignal.KNOWN;
    }

    public double noveltyScore() {
        return 1.0 - semanticSimilarityScore;
    }
}
