package com.loganomaly;

import com.loganomaly.config.AppConfig;
import com.loganomaly.config.DatasetAction;
import com.loganomaly.config.DatasetMode;
import com.loganomaly.config.ExperimentConfig;
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
    void writesWorkbookWithPaperMetricSheetsAndLlmPlaceholder() throws Exception {
        List<LogDocument> logs = List.of(
                PaperEvaluationTest.log("1", "db-connectivity", "database-connection-timeout"),
                PaperEvaluationTest.log("2", "db-connectivity", "jdbc-connection-acquire")
        );
        List<ScenarioResult> results = List.of(
                PaperEvaluationTest.result("B. Paraphrased Failure Family", "db-connectivity", "jdbc-connection-acquire",
                        AnomalyClass.SURGE_ANOMALY, 1, 0, 2, 0, 0.97)
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
                ExperimentConfig.defaults(),
                new ReportConfig(true, tempDir.toString(), "semantic-frequency-paper-test", "placeholder")
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
            assertSheetExists(workbook, "Overall Performance");
            assertSheetExists(workbook, "Semantic Cluster Detection");
            assertSheetExists(workbook, "Operational Spike Detection");
            assertSheetExists(workbook, "Ablation Study");
            Sheet chartsSheet = assertSheetExists(workbook, "Charts");
            Sheet scenarioSheet = assertSheetExists(workbook, "Scenario Results");
            assertSheetExists(workbook, "Top-K Examples");
            Sheet llmSheet = assertSheetExists(workbook, "LLM Evaluation Placeholder");
            assertSheetExists(workbook, "Method Notes");

            assertEquals("Paper Figures", chartsSheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("F1 Score by Method", chartsSheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("Cluster Fragmentation", chartsSheet.getRow(4).getCell(0).getStringCellValue());
            assertEquals("Top-K Count", scenarioSheet.getRow(0).getCell(14).getStringCellValue());
            assertEquals("Semantic Short", scenarioSheet.getRow(0).getCell(10).getStringCellValue());
            assertEquals("B. Paraphrased Failure Family", scenarioSheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("confused-top-k-prompt", llmSheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("signal-separated-prompt", llmSheet.getRow(2).getCell(1).getStringCellValue());
        }
    }

    private static Sheet assertSheetExists(XSSFWorkbook workbook, String name) {
        Sheet sheet = workbook.getSheet(name);
        assertNotNull(sheet, name);
        return sheet;
    }
}
