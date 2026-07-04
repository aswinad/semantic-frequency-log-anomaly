package com.loganomaly;

import com.loganomaly.core.TemporalAnalysis;
import com.loganomaly.core.TemporalSignal;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TemporalSpikeDetectorTest {
    @Test
    void calculatesExpectedShortTermCountUsingProportionalWindowScaling() {
        TemporalAnalysis temporal = new TemporalAnalysis(
                25,
                120,
                Duration.ofMinutes(5),
                Duration.ofMinutes(60),
                2.0
        );

        assertEquals(10.0, temporal.expectedShortTermCount(), 0.0001);
        assertEquals(2.5, temporal.spikeRatio(), 0.0001);
        assertEquals(TemporalSignal.SPIKE, temporal.signal());
    }

    @Test
    void zeroHistoricalBaselineWithNewShortTermOccurrencesIsASpike() {
        TemporalAnalysis temporal = TestScenarios.temporal(1, 0);

        assertEquals(Double.POSITIVE_INFINITY, temporal.spikeRatio());
        assertEquals(TemporalSignal.SPIKE, temporal.signal());
    }

    @Test
    void stableKnownFrequencyDoesNotTriggerSpike() {
        TemporalAnalysis temporal = TestScenarios.temporal(8, 120);

        assertEquals(TemporalSignal.STABLE, temporal.signal());
    }

    @Test
    void smoothedHistoryAwareTemporalAnalysisAvoidsInfiniteSpikes() {
        TemporalAnalysis temporal = new TemporalAnalysis(
                1,
                0,
                Duration.ofMinutes(5),
                Duration.ofMinutes(60),
                2.0,
                5,
                true
        );

        assertEquals(2.0, temporal.spikeRatio(), 0.0001);
        assertEquals(TemporalSignal.INSUFFICIENT_HISTORY, temporal.signal());
    }
}
