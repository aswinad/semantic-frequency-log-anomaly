package com.loganomaly;

import com.loganomaly.core.AnomalyClass;
import com.loganomaly.core.SemanticAnalysis;
import com.loganomaly.core.TemporalAnalysis;
import com.loganomaly.reasoning.ConflatingReasoningLayer;
import com.loganomaly.reasoning.ReasoningLayer;
import com.loganomaly.reasoning.SignalSeparatedReasoningLayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SignalSeparationReasoningTest {
    @Test
    void separatedReasoningTreatsKnnCountsAsSemanticNotFrequencySignals() {
        SemanticAnalysis boundedKnnResult = new SemanticAnalysis(5, 0.95, 3);
        TemporalAnalysis trueFrequencyStats = TestScenarios.temporal(30, 120);

        ReasoningLayer separated = new SignalSeparatedReasoningLayer();

        assertEquals(
                AnomalyClass.SURGE_ANOMALY,
                separated.classify(boundedKnnResult, trueFrequencyStats)
        );
    }

    @Test
    void conflatingKnnNeighborCountWithHistoricalFrequencySuppressesSpikeClassification() {
        SemanticAnalysis boundedKnnResult = new SemanticAnalysis(5, 0.95, 3);
        TemporalAnalysis trueFrequencyStats = TestScenarios.temporal(30, 120);

        ReasoningLayer conflating = new ConflatingReasoningLayer();

        assertEquals(
                AnomalyClass.NORMAL_BEHAVIOR,
                conflating.classify(boundedKnnResult, trueFrequencyStats)
        );
    }
}
