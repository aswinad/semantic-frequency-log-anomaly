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
    void writesFullPublicDatasetWorkbook() throws Exception {
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
                        BglEvalRangeMode.CONTIGUOUS,
                        Optional.empty(),
                        java.time.Duration.ofDays(14)
                ),
                ExperimentConfig.defaults(),
                new ReportConfig(true, tempDir.toString(), "openstack-semantic-frequency-paper-test", "placeholder")
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
            assertNotNull(workbook.getSheet("Overall Performance"));
            assertNotNull(workbook.getSheet("Semantic Cluster Detection"));
            assertNotNull(workbook.getSheet("Operational Spike Detection"));
            assertNotNull(workbook.getSheet("Ablation Study"));
            assertNotNull(workbook.getSheet("Charts"));
            assertNotNull(workbook.getSheet("Event Results"));
            assertNotNull(workbook.getSheet("Top-K Examples"));
            assertNotNull(workbook.getSheet("Method Notes"));
            assertEquals("Binary", workbook.getSheet("Run Summary").getRow(2).getCell(1).getStringCellValue());
        }
    }

    @Test
    void writesBglWorkbookWithLabelBreakdown() throws Exception {
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
                        BglEvalRangeMode.CONTIGUOUS,
                        Optional.empty(),
                        java.time.Duration.ofDays(14)
                ),
                ExperimentConfig.defaults(),
                new ReportConfig(true, tempDir.toString(), "bgl-semantic-frequency-paper-test", "placeholder")
        );

        Path workbookPath = new PublicDatasetWorkbookWriter().writeBgl(
                appConfig,
                List.of(result),
                Instant.parse("2026-06-01T19:00:00Z"),
                new com.loganomaly.app.BglEvaluationWorkflow.EvaluationRange(
                        Instant.parse("2005-06-10T00:00:00Z"),
                        Instant.parse("2005-06-24T00:00:00Z")
                )
        );

        assertTrue(Files.exists(workbookPath));
        try (InputStream inputStream = Files.newInputStream(workbookPath);
             XSSFWorkbook workbook = new XSSFWorkbook(inputStream)) {
            assertNotNull(workbook.getSheet("BGL Label Breakdown"));
            assertEquals("BGL LogHub", workbook.getSheet("Run Summary").getRow(1).getCell(1).getStringCellValue());
            assertEquals("2005-06-10T00:00:00Z", workbook.getSheet("Run Summary").getRow(9).getCell(1).getStringCellValue());
            assertEquals("2005-06-24T00:00:00Z", workbook.getSheet("Run Summary").getRow(10).getCell(1).getStringCellValue());
            assertEquals(14.0, workbook.getSheet("Run Summary").getRow(11).getCell(1).getNumericCellValue());
            assertEquals("APPREAD", workbook.getSheet("BGL Label Breakdown").getRow(1).getCell(0).getStringCellValue());
        }
    }
}
