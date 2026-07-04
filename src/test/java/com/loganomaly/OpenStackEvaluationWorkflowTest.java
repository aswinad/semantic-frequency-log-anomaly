package com.loganomaly;

import com.loganomaly.core.SemanticAnalysis;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class OpenStackEvaluationWorkflowTest {
    @Test
    void semanticAnalysisAcceptsClampedRealEmbeddingSimilarity() {
        double rawSimilarity = 1.0000002d;
        double boundedSimilarity = Math.max(0.0, Math.min(1.0, rawSimilarity));

        assertDoesNotThrow(() -> new SemanticAnalysis(10, boundedSimilarity, 3));
    }
}
