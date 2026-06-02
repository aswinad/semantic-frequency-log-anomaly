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

        var lowSupport = BglOpenSearchHybridAnalyzer.minimumSupportTemporalAnalysis(2, 1, config, 3);
        var supported = BglOpenSearchHybridAnalyzer.minimumSupportTemporalAnalysis(3, 1, config, 3);

        assertEquals(TemporalSignal.STABLE, lowSupport.signal());
        assertEquals(TemporalSignal.SPIKE, supported.signal());
    }
}
