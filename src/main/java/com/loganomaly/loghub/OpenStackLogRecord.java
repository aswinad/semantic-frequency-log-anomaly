package com.loganomaly.loghub;

import java.time.Instant;
import java.util.Optional;

public record OpenStackLogRecord(
        String sourceFile,
        long lineNumber,
        OpenStackSourceRole role,
        Instant originalTimestamp,
        String service,
        String level,
        Optional<String> instanceId,
        String rawMessage,
        String pattern,
        String incidentFamily
) {
}
