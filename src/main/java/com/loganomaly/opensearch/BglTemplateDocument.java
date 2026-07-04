package com.loganomaly.opensearch;

import java.util.Map;
import java.util.Objects;

public record BglTemplateDocument(
        String templateId,
        String pattern,
        String service,
        String incidentFamily,
        String nativeLabel,
        String message,
        float[] embedding
) {
    public BglTemplateDocument {
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(incidentFamily, "incidentFamily");
        Objects.requireNonNull(nativeLabel, "nativeLabel");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(embedding, "embedding");
    }

    public Map<String, Object> source() {
        return Map.of(
                "templateId", templateId,
                "pattern", pattern,
                "service", service,
                "incidentFamily", incidentFamily,
                "nativeLabel", nativeLabel,
                "message", message,
                "embedding", embedding
        );
    }
}
