package com.loganomaly.opensearch;

import java.time.Instant;
import java.util.Objects;

public record LogDocument(
        String id,
        Instant timestamp,
        Instant originalTimestamp,
        String service,
        String pattern,
        String incidentFamily,
        String scenario,
        String message,
        float[] embedding
) {
    public LogDocument(
            String id,
            Instant timestamp,
            String service,
            String pattern,
            String incidentFamily,
            String scenario,
            String message,
            float[] embedding
    ) {
        this(id, timestamp, timestamp, service, pattern, incidentFamily, scenario, message, embedding);
    }

    public LogDocument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(originalTimestamp, "originalTimestamp");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(incidentFamily, "incidentFamily");
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(embedding, "embedding");
    }
}
