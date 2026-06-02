package com.loganomaly.loghub;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class BglLogParser {
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS")
            .withLocale(Locale.ROOT);

    public BglLogRecord parse(String line, long lineNumber) {
        String[] parts = line.split("\\s+", 10);
        if (parts.length < 9) {
            throw new IllegalArgumentException("Unable to parse BGL log line " + lineNumber + ": " + line);
        }
        String rawLabel = parts[0];
        Instant timestamp = LocalDateTime.parse(parts[4], TIMESTAMP_FORMATTER).toInstant(ZoneOffset.UTC);
        String node = parts[5];
        String service = "%s|%s|%s|%s".formatted(parts[5], parts[6], parts[7], parts[8]);
        String rawMessage = parts.length >= 10 ? parts[9].trim() : parts[8].trim();
        return new BglLogRecord(
                lineNumber,
                rawLabel,
                timestamp,
                node,
                service,
                rawMessage,
                BglTemplateNormalizer.normalize(rawMessage)
        );
    }
}
