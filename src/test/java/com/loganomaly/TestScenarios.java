package com.loganomaly;

import com.loganomaly.core.SemanticAnalysis;
import com.loganomaly.core.TemporalAnalysis;

import java.time.Duration;

final class TestScenarios {
    private TestScenarios() {
    }

    static TemporalAnalysis temporal(int shortCount, int longCount) {
        return new TemporalAnalysis(
                shortCount,
                longCount,
                Duration.ofMinutes(5),
                Duration.ofMinutes(60),
                2.0
        );
    }

    static Case caseOf(
            String name,
            int semanticCount,
            double similarity,
            int shortCount,
            int longCount,
            boolean anomalous
    ) {
        return new Case(
                name,
                new SemanticAnalysis(semanticCount, similarity, 3),
                temporal(shortCount, longCount),
                anomalous
        );
    }

    record Case(String name, SemanticAnalysis semantic, TemporalAnalysis temporal, boolean anomalous) {
    }
}
