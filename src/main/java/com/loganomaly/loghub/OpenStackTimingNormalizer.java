package com.loganomaly.loghub;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class OpenStackTimingNormalizer {
    public static final Instant DEFAULT_EXPERIMENT_ANCHOR = Instant.parse("2026-01-01T00:00:00Z");
    public static final Duration DEFAULT_BASELINE_DURATION = Duration.ofHours(23).plusMinutes(45);
    public static final Duration DEFAULT_TEST_DURATION = Duration.ofMinutes(15);

    private static final TimestampRange LOGHUB_NORMAL_ORIGINAL_RANGE = new TimestampRange(
            Instant.parse("2017-05-16T00:00:00.008Z"),
            Instant.parse("2017-05-17T12:02:35.320Z")
    );
    private static final TimestampRange LOGHUB_ABNORMAL_ORIGINAL_RANGE = new TimestampRange(
            Instant.parse("2017-05-14T19:39:01.445Z"),
            Instant.parse("2017-05-14T21:56:25.531Z")
    );

    private final TimestampRange normalOriginalRange;
    private final TimestampRange abnormalOriginalRange;
    private final TimestampRange baselineExperimentRange;
    private final TimestampRange testExperimentRange;

    public OpenStackTimingNormalizer(
            TimestampRange normalOriginalRange,
            TimestampRange abnormalOriginalRange,
            Instant experimentAnchor,
            Duration baselineDuration,
            Duration testDuration
    ) {
        this.normalOriginalRange = Objects.requireNonNull(normalOriginalRange, "normalOriginalRange");
        this.abnormalOriginalRange = Objects.requireNonNull(abnormalOriginalRange, "abnormalOriginalRange");
        Objects.requireNonNull(experimentAnchor, "experimentAnchor");
        Objects.requireNonNull(baselineDuration, "baselineDuration");
        Objects.requireNonNull(testDuration, "testDuration");
        if (baselineDuration.isNegative() || baselineDuration.isZero()) {
            throw new IllegalArgumentException("Baseline duration must be positive");
        }
        if (testDuration.isNegative() || testDuration.isZero()) {
            throw new IllegalArgumentException("Test duration must be positive");
        }
        this.baselineExperimentRange = new TimestampRange(experimentAnchor, experimentAnchor.plus(baselineDuration));
        this.testExperimentRange = new TimestampRange(
                baselineExperimentRange.end(),
                baselineExperimentRange.end().plus(testDuration)
        );
    }

    public static OpenStackTimingNormalizer forLogHubDefaults() {
        return new OpenStackTimingNormalizer(
                LOGHUB_NORMAL_ORIGINAL_RANGE,
                LOGHUB_ABNORMAL_ORIGINAL_RANGE,
                DEFAULT_EXPERIMENT_ANCHOR,
                DEFAULT_BASELINE_DURATION,
                DEFAULT_TEST_DURATION
        );
    }

    public static OpenStackTimingNormalizer forLogHub(Instant experimentAnchor, Duration baselineDuration, Duration testDuration) {
        return new OpenStackTimingNormalizer(
                LOGHUB_NORMAL_ORIGINAL_RANGE,
                LOGHUB_ABNORMAL_ORIGINAL_RANGE,
                experimentAnchor,
                baselineDuration,
                testDuration
        );
    }

    public Instant normalize(OpenStackSourceRole role, Instant originalTimestamp) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(originalTimestamp, "originalTimestamp");
        return switch (role) {
            case NORMAL_BASELINE -> scale(originalTimestamp, normalOriginalRange, baselineExperimentRange);
            case ABNORMAL_TEST -> scale(originalTimestamp, abnormalOriginalRange, testExperimentRange);
        };
    }

    public TimestampRange baselineExperimentRange() {
        return baselineExperimentRange;
    }

    public TimestampRange testExperimentRange() {
        return testExperimentRange;
    }

    private static Instant scale(Instant sourceTimestamp, TimestampRange sourceRange, TimestampRange targetRange) {
        if (!sourceTimestamp.isAfter(sourceRange.start())) {
            return targetRange.start();
        }
        if (!sourceTimestamp.isBefore(sourceRange.end())) {
            return targetRange.end();
        }

        long sourceMillis = Math.max(1L, sourceRange.duration().toMillis());
        long elapsedMillis = Duration.between(sourceRange.start(), sourceTimestamp).toMillis();
        long targetMillis = targetRange.duration().toMillis();
        long normalizedOffsetMillis = Math.round((elapsedMillis / (double) sourceMillis) * targetMillis);
        return targetRange.start().plusMillis(normalizedOffsetMillis);
    }
}
