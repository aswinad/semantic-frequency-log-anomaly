package com.loganomaly.loghub;

import java.time.Instant;

public record BglLogRecord(
        long lineNumber,
        String rawLabel,
        Instant timestamp,
        String node,
        String service,
        String rawMessage,
        String pattern
) {
    public boolean anomaly() {
        return !"-".equals(rawLabel);
    }
}
