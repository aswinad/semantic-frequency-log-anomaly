package com.loganomaly.loghub;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record TimestampRange(Instant start, Instant end) {
    public TimestampRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("Timestamp range end must not be before start");
        }
    }

    public Duration duration() {
        return Duration.between(start, end);
    }
}
