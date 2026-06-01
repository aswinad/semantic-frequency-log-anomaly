package com.loganomaly.loghub;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OpenStackLogParser {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Pattern INSTANCE_PATTERN = Pattern.compile("\\[instance: ([0-9a-fA-F-]{36})]");

    public OpenStackLogRecord parse(String line, long lineNumber, OpenStackSourceRole role, Set<String> anomalousVmIds) {
        String[] parts = line.split("\\s+", 7);
        if (parts.length < 6) {
            throw new IllegalArgumentException("Unable to parse OpenStack log line " + lineNumber + ": " + line);
        }

        String sourceFile = parts[0];
        Instant originalTimestamp = LocalDateTime.parse(parts[1] + " " + parts[2], TIMESTAMP_FORMAT).toInstant(ZoneOffset.UTC);
        String level = parts[4];
        String service = parts[5];
        String metadataAndMessage = parts.length >= 7 ? parts[6] : "";
        Optional<String> instanceId = extractInstanceId(metadataAndMessage);
        String rawMessage = stripLeadingMetadata(metadataAndMessage);
        if (rawMessage.isBlank()) {
            rawMessage = "<empty-message>";
        }
        String pattern = OpenStackTemplateNormalizer.normalize(rawMessage);
        boolean anomalousVmLine = anomalousVmIds.stream().anyMatch(line::contains);
        String incidentFamily = anomalousVmLine ? "openstack-anomaly-vm" : "openstack-normal";

        return new OpenStackLogRecord(
                sourceFile,
                lineNumber,
                role,
                originalTimestamp,
                service,
                level,
                instanceId,
                rawMessage,
                pattern,
                incidentFamily
        );
    }

    private static Optional<String> extractInstanceId(String text) {
        Matcher matcher = INSTANCE_PATTERN.matcher(text);
        if (matcher.find()) {
            return Optional.of(matcher.group(1).toLowerCase());
        }
        return Optional.empty();
    }

    private static String stripLeadingMetadata(String text) {
        String remaining = text.trim();
        while (remaining.startsWith("[")) {
            int close = remaining.indexOf(']');
            if (close < 0) {
                break;
            }
            remaining = remaining.substring(close + 1).trim();
        }
        return remaining;
    }
}
