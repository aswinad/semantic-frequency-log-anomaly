package com.loganomaly.config;

public record ReportConfig(
        boolean excelEnabled,
        String outputDir,
        String filePrefix
) {
}
