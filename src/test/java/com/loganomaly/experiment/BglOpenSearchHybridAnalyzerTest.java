package com.loganomaly.experiment;

import com.loganomaly.config.ExperimentConfig;
import com.loganomaly.core.TemporalSignal;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BglOpenSearchHybridAnalyzerTest {
    @Test
    void minimumSupportSuppressesLowCountSpikeSignal() {
        ExperimentConfig config = new ExperimentConfig(
                Duration.ofMinutes(15),
                Duration.ofHours(24),
                5,
                3,
                0.85,
                2.0
        );

        var lowSupport = BglOpenSearchHybridAnalyzer.historyAwareTemporalAnalysis(20, 1, config, 3);
        var supported = BglOpenSearchHybridAnalyzer.historyAwareTemporalAnalysis(20, 3, config, 3);

        assertEquals(TemporalSignal.INSUFFICIENT_HISTORY, lowSupport.signal());
        assertEquals(TemporalSignal.SPIKE, supported.signal());
    }
}
