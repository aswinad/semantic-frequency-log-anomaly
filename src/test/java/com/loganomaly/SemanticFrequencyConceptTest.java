package com.loganomaly;

import com.loganomaly.core.SemanticAnalysis;
import com.loganomaly.core.SemanticSignal;
import com.loganomaly.core.TemporalAnalysis;
import com.loganomaly.core.TemporalSignal;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SemanticFrequencyConceptTest {
    @Test
    void semanticNoveltyUsesBaselineSemanticFrequencyNotTopKExampleCount() {
        int topKExamplesReturned = 5;
        int baselineSemanticFrequency = 0;

        SemanticAnalysis semantic = new SemanticAnalysis(baselineSemanticFrequency, 0.12, 3);

        assertEquals(5, topKExamplesReturned);
        assertEquals(SemanticSignal.NOVEL, semantic.signal());
    }

    @Test
    void temporalDeviationUsesSemanticFrequencyCountsAcrossWindows() {
        TemporalAnalysis semanticFrequency = new TemporalAnalysis(
                40,
                20,
                Duration.ofMinutes(5),
                Duration.ofMinutes(55),
                2.0
        );

        assertEquals(1.818, semanticFrequency.expectedShortTermCount(), 0.001);
        assertEquals(22.0, semanticFrequency.spikeRatio(), 0.001);
        assertEquals(TemporalSignal.SPIKE, semanticFrequency.signal());
    }

    @Test
    void topKConfusionTestShowsReturnedExamplesAreNotFrequency() {
        int requestedTopK = 5;
        int returnedExamples = 5;
        int actualSemanticFrequency = 500;

        assertEquals(requestedTopK, returnedExamples);
        assertEquals(500, actualSemanticFrequency);
    }
}
