package com.loganomaly.opensearch;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record BglEventDocument(
        String id,
        Instant timestamp,
        Instant originalTimestamp,
        String templateId,
        String pattern,
        String service,
        String incidentFamily,
        String nativeLabel,
        String scenario,
        String message
) {
    public BglEventDocument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(originalTimestamp, "originalTimestamp");
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(incidentFamily, "incidentFamily");
        Objects.requireNonNull(nativeLabel, "nativeLabel");
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(message, "message");
    }

    public Map<String, Object> source() {
        return Map.of(
                "timestamp", timestamp.toString(),
                "originalTimestamp", originalTimestamp.toString(),
                "templateId", templateId,
                "pattern", pattern,
                "service", service,
                "incidentFamily", incidentFamily,
                "nativeLabel", nativeLabel,
                "scenario", scenario,
                "message", message
        );
    }
}
