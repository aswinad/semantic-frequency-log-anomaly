package com.loganomaly.config;

import java.util.Locale;

public enum BglCandidateMode {
    ALL,
    FILTERED,
    STRICT;

    public static BglCandidateMode parse(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "all", "all_lines" -> ALL;
            case "filtered", "label_blind_suspicious_templates" -> FILTERED;
            case "strict", "strict_suspicious_templates" -> STRICT;
            default -> throw new IllegalArgumentException("Unsupported BGL_CANDIDATE_MODE: " + raw);
        };
    }

    @Override
    public String toString() {
        return switch (this) {
            case ALL -> "all_lines";
            case FILTERED -> "label_blind_suspicious_templates";
            case STRICT -> "strict_suspicious_templates";
        };
    }
}
