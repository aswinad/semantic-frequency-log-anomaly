package com.loganomaly.loghub;

import java.util.Locale;

public final class OpenStackTemplateNormalizer {
    private OpenStackTemplateNormalizer() {
    }

    public static String normalize(String message) {
        String normalized = message;
        normalized = normalized.replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "<uuid>");
        normalized = normalized.replaceAll("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b", "<ip>");
        normalized = normalized.replaceAll("/v\\d+/[0-9a-fA-F]{32}", "/v<n>/<tenant>");
        normalized = normalized.replaceAll("\\b\\d+\\.\\d+\\b", "<decimal>");
        normalized = normalized.replaceAll("\\b\\d+\\b", "<number>");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized.toLowerCase(Locale.ROOT);
    }
}
