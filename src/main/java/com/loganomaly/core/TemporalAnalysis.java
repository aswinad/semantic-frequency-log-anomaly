package com.loganomaly.core;

import java.time.Duration;

public record TemporalAnalysis(
        int shortCount,
        int longCount,
        Duration shortWindow,
        Duration longWindow,
        double spikeThreshold,
        int historicalSupportThreshold,
        boolean smoothingEnabled
) {
    public TemporalAnalysis(
            int shortCount,
            int longCount,
            Duration shortWindow,
            Duration longWindow,
            double spikeThreshold
    ) {
        this(shortCount, longCount, shortWindow, longWindow, spikeThreshold, 0, false);
    }

    public TemporalAnalysis {
        if (shortCount < 0 || longCount < 0) {
            throw new IllegalArgumentException("counts must be >= 0");
        }
        if (shortWindow == null || longWindow == null || shortWindow.isZero() || longWindow.isZero()) {
            throw new IllegalArgumentException("windows must be non-zero");
        }
        if (shortWindow.isNegative() || longWindow.isNegative()) {
            throw new IllegalArgumentException("windows must be positive");
        }
        if (spikeThreshold <= 0.0) {
            throw new IllegalArgumentException("spikeThreshold must be > 0");
        }
        if (historicalSupportThreshold < 0) {
            throw new IllegalArgumentException("historicalSupportThreshold must be >= 0");
        }
    }

    public double expectedShortTermCount() {
        return longCount * ((double) shortWindow.toMillis() / (double) longWindow.toMillis());
    }

    public boolean hasSufficientHistory() {
        return longCount >= historicalSupportThreshold;
    }

    public double spikeRatio() {
        double expected = expectedShortTermCount();
        if (smoothingEnabled) {
            return (shortCount + 1.0) / (expected + 1.0);
        }
        if (expected == 0.0) {
            return shortCount > 0 ? Double.POSITIVE_INFINITY : 0.0;
        }
        return shortCount / expected;
    }

    public TemporalSignal signal() {
        if (!hasSufficientHistory()) {
            return TemporalSignal.INSUFFICIENT_HISTORY;
        }
        return spikeRatio() > spikeThreshold ? TemporalSignal.SPIKE : TemporalSignal.STABLE;
    }

    public double temporalSpikeScore() {
        if (signal() == TemporalSignal.INSUFFICIENT_HISTORY) {
            return 0.0;
        }
        return Math.min(spikeRatio() / spikeThreshold, 1.0);
    }
}
