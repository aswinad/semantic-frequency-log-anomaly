package com.loganomaly.loghub;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class BglLogHubDataset {
    private final Path file;
    private final BglLogParser parser = new BglLogParser();

    public BglLogHubDataset(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public long countLines() throws IOException {
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.count();
        }
    }

    public Instant firstTimestamp() throws IOException {
        AtomicReference<Instant> first = new AtomicReference<>();
        forEachRecord(record -> {
            if (first.get() == null) {
                first.set(record.timestamp());
            }
        });
        return first.get();
    }

    public void forEachRecord(Consumer<BglLogRecord> consumer) throws IOException {
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            final long[] lineNumber = {0L};
            lines.forEach(line -> {
                lineNumber[0]++;
                if (line.isBlank()) {
                    return;
                }
                consumer.accept(parser.parse(line, lineNumber[0]));
            });
        }
    }
}
