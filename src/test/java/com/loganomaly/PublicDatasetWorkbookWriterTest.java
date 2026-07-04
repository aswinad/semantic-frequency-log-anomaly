package com.loganomaly;

import com.loganomaly.config.AppConfig;
import com.loganomaly.config.BglCandidateMode;
import com.loganomaly.config.BglEvalRangeMode;
import com.loganomaly.config.DatasetAction;
import com.loganomaly.config.DatasetMode;
import com.loganomaly.config.ExperimentConfig;
import com.loganomaly.config.BglConfig;
import com.loganomaly.config.OpenAiConfig;
import com.loganomaly.config.OpenStackConfig;
import com.loganomaly.config.ReportConfig;
import com.loganomaly.core.AnomalyClass;
import com.loganomaly.report.BinaryGroundTruth;
import com.loganomaly.report.PublicDatasetEvaluationResult;
import com.loganomaly.report.PublicDatasetWorkbookWriter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.poi.ss.usermodel.Sheet;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicDatasetWorkbookWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void writesCompactOpenStackCaseStudyWorkbook() throws Exception {
        PublicDatasetEvaluationResult result = new PublicDatasetEvaluationResult(
                "ANOMALY | nova.compute.claims | attempting claim",
                BinaryGroundTruth.ANOMALY,
                "openstack-anomaly-vm",
                28,
                PaperEvaluationTest.result("candidate", "openstack-anomaly-vm", "attempting-claim",
                        AnomalyClass.SURGE_ANOMALY, 10, 100, 10, 100, 0.99),
                AnomalyClass.SURGE_ANOMALY,
                AnomalyClass.NORMAL_BEHAVIOR,
                AnomalyClass.NORMAL_BEHAVIOR,
                AnomalyClass.SURGE_ANOMALY,
                AnomalyClass.SURGE_ANOMALY
        );

        AppConfig appConfig = new AppConfig(
                DatasetMode.OPENSTACK,
                DatasetAction.EVALUATE,
                "http://localhost:9200",
                Optional.empty(),
                Optional.empty(),
                "log-anomaly-synthetic",
                false,
                "openai",
                new OpenAiConfig(Optional.empty(), "text-embedding-3-small", 1536),
                new OpenStackConfig(
                        tempDir,
                        "log-anomaly-openstack",
                        false,
                        tempDir.resolve("cache.jsonl"),
                        64,
                        1000,
                        Instant.parse("2026-01-01T00:00:00Z"),
                        java.time.Duration.ofMinutes(15),
                        java.time.Duration.ofHours(24)
                ),
                new BglConfig(
                        tempDir.resolve("BGL.log"),
                        "log-anomaly-bgl",
                        false,
                        tempDir.resolve("bgl-cache.jsonl"),
                        64,
                        1000,
                        java.time.Duration.ofMinutes(15),
                        java.time.Duration.ofHours(24),
                        java.time.Duration.ofMinutes(5),
                        BglCandidateMode.FILTERED,
                        false,
                        3,
                        3,
                        List.of(0.70, 0.75, 0.80, 0.85, 0.90),
                        BglEvalRangeMode.CONTIGUOUS,
                        Optional.empty(),
                        java.time.Duration.ofDays(14),
                        tempDir.resolve("bgl-ablation-cache.jsonl"),
                        false,
                        false,
                        2
                ),
                ExperimentConfig.defaults(),
                new ReportConfig(true, tempDir.toString(), "openstack-semantic-frequency-paper-test")
        );

        Path workbookPath = new PublicDatasetWorkbookWriter().writeOpenStack(
                appConfig,
                List.of(result),
                Instant.parse("2026-06-01T18:25:50Z")
        );

        assertTrue(Files.exists(workbookPath));
        try (InputStream inputStream = Files.newInputStream(workbookPath);
             XSSFWorkbook workbook = new XSSFWorkbook(inputStream)) {
            assertNotNull(workbook.getSheet("Run Summary"));
            assertNotNull(workbook.getSheet("Pipeline Validation"));
            assertNotNull(workbook.getSheet("Operational Metrics"));
            assertNotNull(workbook.getSheet("Runtime"));
            assertEquals("Binary", workbook.getSheet("Run Summary").getRow(2).getCell(1).getStringCellValue());
            assertEquals("Method", workbook.getSheet("Pipeline Validation").getRow(0).getCell(0).getStringCellValue());
            assertEquals("Events Processed", workbook.getSheet("Operational Metrics").getRow(0).getCell(0).getStringCellValue());
        }
    }

    @Test
    void writesBglWorkbookWithPaperFocusedSheets() throws Exception {
        PublicDatasetEvaluationResult result = new PublicDatasetEvaluationResult(
                "ANOMALY | APPREAD | service | read message prefix",
                BinaryGroundTruth.ANOMALY,
                "APPREAD",
                12,
                PaperEvaluationTest.result("candidate", "bgl-anomaly", "read-message-prefix",
                        AnomalyClass.SURGE_ANOMALY, 3, 30, 4, 40, 0.91),
                AnomalyClass.SURGE_ANOMALY,
                AnomalyClass.NORMAL_BEHAVIOR,
                AnomalyClass.NORMAL_BEHAVIOR,
                AnomalyClass.SURGE_ANOMALY,
                AnomalyClass.SURGE_ANOMALY
        );

        AppConfig appConfig = new AppConfig(
                DatasetMode.BGL,
                DatasetAction.EVALUATE,
                "http://localhost:9200",
                Optional.empty(),
                Optional.empty(),
                "log-anomaly-synthetic",
                false,
                "openai",
                new OpenAiConfig(Optional.empty(), "text-embedding-3-small", 1536),
                new OpenStackConfig(
                        tempDir,
                        "log-anomaly-openstack",
                        false,
                        tempDir.resolve("cache.jsonl"),
                        64,
                        1000,
                        Instant.parse("2026-01-01T00:00:00Z"),
                        java.time.Duration.ofMinutes(15),
                        java.time.Duration.ofHours(24)
                ),
                new BglConfig(
                        tempDir.resolve("BGL.log"),
                        "log-anomaly-bgl",
                        false,
                        tempDir.resolve("bgl-cache.jsonl"),
                        64,
                        1000,
                        java.time.Duration.ofMinutes(15),
                        java.time.Duration.ofHours(24),
                        java.time.Duration.ofMinutes(5),
                        BglCandidateMode.FILTERED,
                        false,
                        3,
                        3,
                        List.of(0.70, 0.75, 0.80, 0.85, 0.90),
                        BglEvalRangeMode.CONTIGUOUS,
                        Optional.empty(),
                        java.time.Duration.ofDays(14),
                        tempDir.resolve("bgl-ablation-cache.jsonl"),
                        false,
                        false,
                        2
                ),
                ExperimentConfig.defaults(),
                new ReportConfig(true, tempDir.toString(), "bgl-semantic-frequency-paper-test")
        );

        Path workbookPath = new PublicDatasetWorkbookWriter().writeBgl(
                appConfig,
                List.of(result),
                Instant.parse("2026-06-01T19:00:00Z"),
                new com.loganomaly.app.BglEvaluationWorkflow.EvaluationRange(
                        Instant.parse("2005-06-10T00:00:00Z"),
                        Instant.parse("2005-06-24T00:00:00Z")
                ),
                List.of(new com.loganomaly.app.BglEvaluationWorkflow.ThresholdSweepResult(
                        0.85,
                        new com.loganomaly.report.DetectionMetrics(0.8, 0.7, 0.7466666667, 0.1, 0.3)
                )),
                List.of(new com.loganomaly.app.BglEvaluationWorkflow.CandidateStrategyComparisonRow(
                        "label_blind_suspicious_templates",
                        12,
                        48,
                        new com.loganomaly.report.DetectionMetrics(0.8, 0.7, 0.7466666667, 0.1, 0.3)
                ))
        );

        assertTrue(Files.exists(workbookPath));
        try (InputStream inputStream = Files.newInputStream(workbookPath);
             XSSFWorkbook workbook = new XSSFWorkbook(inputStream)) {
            assertNotNull(workbook.getSheet("Candidate Selection Impact"));
            assertNotNull(workbook.getSheet("Method Comparison"));
            assertNotNull(workbook.getSheet("Threshold Sensitivity"));
            assertNotNull(workbook.getSheet("False Positive Analysis"));
            assertNotNull(workbook.getSheet("False Negative Analysis"));
            assertNotNull(workbook.getSheet("Charts"));
            assertEquals("BGL LogHub", workbook.getSheet("Run Summary").getRow(1).getCell(1).getStringCellValue());
            assertEquals("2005-06-10T00:00:00Z", valueForKey(workbook.getSheet("Run Summary"), "Evaluation Start"));
            assertEquals("2005-06-24T00:00:00Z", valueForKey(workbook.getSheet("Run Summary"), "Evaluation End"));
            assertEquals("3.0", valueForKey(workbook.getSheet("Run Summary"), "Minimum Historical Support"));
            assertEquals("3.0", valueForKey(workbook.getSheet("Run Summary"), "Minimum Alert Short Support"));
            assertEquals("Suspicious Templates", workbook.getSheet("Candidate Selection Impact").getRow(1).getCell(0).getStringCellValue());
            assertEquals(0.85, workbook.getSheet("Threshold Sensitivity").getRow(1).getCell(0).getNumericCellValue());
            assertEquals("Method", workbook.getSheet("Method Comparison").getRow(0).getCell(0).getStringCellValue());
            assertEquals("Template", workbook.getSheet("False Positive Analysis").getRow(0).getCell(0).getStringCellValue());
            assertEquals("Template", workbook.getSheet("False Negative Analysis").getRow(0).getCell(0).getStringCellValue());
        }
    }

    private static String valueForKey(Sheet sheet, String key) {
        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            if (sheet.getRow(rowIndex) == null || sheet.getRow(rowIndex).getCell(0) == null) {
                continue;
            }
            if (key.equals(sheet.getRow(rowIndex).getCell(0).getStringCellValue())) {
                return sheet.getRow(rowIndex).getCell(1).toString();
            }
        }
        throw new IllegalArgumentException("Missing key: " + key);
    }
}
