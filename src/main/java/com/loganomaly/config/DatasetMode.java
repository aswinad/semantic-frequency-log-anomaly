package com.loganomaly.config;

import java.util.Locale;

public enum DatasetMode {
    SYNTHETIC,
    OPENSTACK,
    BGL;

    public static DatasetMode parse(String value) {
        return DatasetMode.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }
}
