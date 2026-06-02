package com.loganomaly.config;

import java.util.Locale;

public enum BglCandidateMode {
    ALL,
    FILTERED;

    public static BglCandidateMode parse(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "all" -> ALL;
            case "filtered" -> FILTERED;
            default -> throw new IllegalArgumentException("Unsupported BGL_CANDIDATE_MODE: " + raw);
        };
    }
}
