package com.loganomaly.config;

import java.util.Locale;

public enum DatasetAction {
    INDEX,
    EVALUATE,
    EVALUATE_ABLATION;

    public static DatasetAction parse(String value) {
        return DatasetAction.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }
}
