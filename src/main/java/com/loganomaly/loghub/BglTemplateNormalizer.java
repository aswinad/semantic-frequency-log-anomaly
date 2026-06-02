package com.loganomaly.loghub;

public final class BglTemplateNormalizer {
    private BglTemplateNormalizer() {
    }

    public static String normalize(String message) {
        return message.toLowerCase()
                .replaceAll("\\b\\d+\\.\\d+\\.\\d+\\.\\d+\\b", "<ip>")
                .replaceAll("0x[0-9a-f]+", "<hex>")
                .replaceAll("core\\.\\d+", "core.<n>")
                .replaceAll("\\b\\d+\\b", "<n>")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
