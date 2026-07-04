package com.loganomaly.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class Dotenv {
    private final Map<String, String> values;
    private final boolean systemOverridesEnabled;

    private Dotenv(Map<String, String> values, boolean systemOverridesEnabled) {
        this.values = Map.copyOf(values);
        this.systemOverridesEnabled = systemOverridesEnabled;
    }

    public static Dotenv load() {
        String path = System.getenv().getOrDefault("DOTENV_PATH", ".env");
        return load(Path.of(path), true);
    }

    public static Dotenv load(Path path) {
        return load(path, false);
    }

    private static Dotenv load(Path path, boolean systemOverridesEnabled) {
        Map<String, String> values = new HashMap<>();
        if (!Files.exists(path)) {
            return new Dotenv(values, systemOverridesEnabled);
        }
        try {
            for (String line : Files.readAllLines(path)) {
                parseLine(line).ifPresent(entry -> values.put(entry.key(), entry.value()));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read " + path.toAbsolutePath(), e);
        }
        return new Dotenv(values, systemOverridesEnabled);
    }

    public String get(String key, String defaultValue) {
        if (systemOverridesEnabled) {
            String envValue = System.getenv(key);
            if (envValue != null && !envValue.isBlank()) {
                return envValue;
            }
        }
        return values.getOrDefault(key, defaultValue);
    }

    public Optional<String> getOptional(String key) {
        if (systemOverridesEnabled) {
            String envValue = System.getenv(key);
            if (envValue != null && !envValue.isBlank()) {
                return Optional.of(envValue);
            }
        }
        return Optional.ofNullable(values.get(key)).filter(value -> !value.isBlank());
    }

    public int getInt(String key, int defaultValue) {
        return Integer.parseInt(get(key, Integer.toString(defaultValue)));
    }

    public double getDouble(String key, double defaultValue) {
        return Double.parseDouble(get(key, Double.toString(defaultValue)));
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(key, Boolean.toString(defaultValue)));
    }

    private static Optional<Entry> parseLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return Optional.empty();
        }
        int equals = trimmed.indexOf('=');
        if (equals <= 0) {
            return Optional.empty();
        }
        String key = trimmed.substring(0, equals).trim();
        String value = stripQuotes(trimmed.substring(equals + 1).trim());
        return Optional.of(new Entry(key, value));
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            boolean doubleQuoted = value.startsWith("\"") && value.endsWith("\"");
            boolean singleQuoted = value.startsWith("'") && value.endsWith("'");
            if (doubleQuoted || singleQuoted) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private record Entry(String key, String value) {
    }
}
