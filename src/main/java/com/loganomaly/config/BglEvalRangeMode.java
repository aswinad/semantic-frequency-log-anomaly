package com.loganomaly.config;

import java.util.Locale;

public enum BglEvalRangeMode {
    CONTIGUOUS;

    public static BglEvalRangeMode parse(String value) {
        return BglEvalRangeMode.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }
}
