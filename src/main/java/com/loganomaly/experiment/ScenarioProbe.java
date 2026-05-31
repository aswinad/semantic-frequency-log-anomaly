package com.loganomaly.experiment;

import com.loganomaly.core.AnomalyClass;

import java.time.Instant;

public record ScenarioProbe(
        String name,
        String pattern,
        String incidentFamily,
        Instant observedAt,
        String service,
        String message,
        float[] embedding,
        AnomalyClass expectedClass
) {
}
