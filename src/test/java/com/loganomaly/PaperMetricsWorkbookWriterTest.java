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
import com.loganomaly.experiment.ScenarioResult;
import com.loganomaly.opensearch.LogDocument;
import com.loganomaly.report.PaperMetricsWorkbookWriter;
import org.apache.poi.ss.usermodel.Sheet;
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

class PaperMetricsWorkbookWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void writesWorkbookWithPaperFocusedSyntheticSheets() throws Exception {
        List<LogDocument> logs = List.of(
                PaperEvaluationTest.log("1", "db-connectivity", "database-connection-timeout"),
                PaperEvaluationTest.log("2", "db-connectivity-distributed-surge", "jdbc-connection-acquire")
        );
        List<ScenarioResult> results = List.of(
                PaperEvaluationTest.result("Q. Paraphrased Semantic Surge", "db-connectivity-distributed-surge", "database-connection-timeout",
                        AnomalyClass.SURGE_ANOMALY, 3, 18, 26, 132, 0.97)
        );
        AppConfig appConfig = new AppConfig(
                DatasetMode.SYNTHETIC,
                DatasetAction.EVALUATE,
                "http://localhost:9200",
                Optional.empty(),
                Optional.empty(),
                "log-anomaly-synthetic",
                false,
                "deterministic-synthetic-v1",
                new OpenAiConfig(Optional.empty(), "text-embedding-3-small", 1536),
                new OpenStackConfig(
                        tempDir,
                        "log-anomaly-openstack",
                        false,
                        tempDir.resolve("openstack-embedding-cache.jsonl"),
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
                new ReportConfig(true, tempDir.toString(), "semantic-frequency-paper-test")
        );

        Path workbookPath = new PaperMetricsWorkbookWriter().write(
                appConfig,
                logs,
                results,
                Instant.parse("2026-01-01T01:00:00Z")
        );

        assertTrue(Files.exists(workbookPath));
        try (InputStream inputStream = Files.newInputStream(workbookPath);
             XSSFWorkbook workbook = new XSSFWorkbook(inputStream)) {
            assertSheetExists(workbook, "Run Summary");
            assertSheetExists(workbook, "Classification Metrics");
            assertSheetExists(workbook, "Semantic Metrics");
            assertSheetExists(workbook, "Ablation Study");
            Sheet chartsSheet = assertSheetExists(workbook, "Charts");
            Sheet runSummarySheet = assertSheetExists(workbook, "Run Summary");
            Sheet scenarioSheet = assertSheetExists(workbook, "Scenario Results");
            Sheet classificationSheet = assertSheetExists(workbook, "Classification Metrics");
            Sheet semanticSheet = assertSheetExists(workbook, "Semantic Metrics");
            Sheet ablationSheet = assertSheetExists(workbook, "Ablation Study");

            assertEquals("Paper Figures", chartsSheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Semantic Cluster Coverage", chartsSheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("Incident Fragmentation", chartsSheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals("Q. Paraphrased Semantic Surge", runSummarySheet.getRow(9).getCell(1).getStringCellValue());
            assertEquals("Notes", classificationSheet.getRow(0).getCell(6).getStringCellValue());
            assertEquals("Template-level", classificationSheet.getRow(1).getCell(6).getStringCellValue());
            assertEquals("Method", semanticSheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Method", ablationSheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Semantic + Temporal", scenarioSheet.getRow(0).getCell(4).getStringCellValue());
            assertEquals("Paraphrased Semantic Surge", scenarioSheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("Missed", scenarioSheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("Context only", scenarioSheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("Captured family", scenarioSheet.getRow(1).getCell(3).getStringCellValue());
            assertEquals("Detected", scenarioSheet.getRow(1).getCell(4).getStringCellValue());
            assertEquals("SURGE_ANOMALY", scenarioSheet.getRow(1).getCell(5).getStringCellValue());
        }
    }

    private static Sheet assertSheetExists(XSSFWorkbook workbook, String name) {
        Sheet sheet = workbook.getSheet(name);
        assertNotNull(sheet, name);
        return sheet;
    }
}
