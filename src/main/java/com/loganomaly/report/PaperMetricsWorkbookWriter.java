package com.loganomaly.report;

import com.loganomaly.config.AppConfig;
import com.loganomaly.config.ExperimentConfig;
import com.loganomaly.config.ReportConfig;
import com.loganomaly.core.TemporalAnalysis;
import com.loganomaly.experiment.ScenarioResult;
import com.loganomaly.opensearch.KnnNeighbor;
import com.loganomaly.opensearch.LogDocument;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.BarDirection;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.XDDFBarChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFCategoryAxis;
import org.apache.poi.xddf.usermodel.chart.XDDFChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFValueAxis;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PaperMetricsWorkbookWriter {
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withLocale(Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    public Path write(
            AppConfig appConfig,
            List<LogDocument> logs,
            List<ScenarioResult> results,
            Instant runStartedAt
    ) throws IOException {
        ReportConfig reportConfig = appConfig.report();
        Files.createDirectories(Path.of(reportConfig.outputDir()));
        Path outputPath = Path.of(
                reportConfig.outputDir(),
                "%s-%s.xlsx".formatted(reportConfig.filePrefix(), FILE_TIMESTAMP.format(runStartedAt))
        );

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = headerStyle(workbook);
            writeRunSummary(workbook, headerStyle, appConfig, logs, results, runStartedAt);
            writeOverallPerformance(workbook, headerStyle, results, appConfig.experiment());
            writeSemanticClusterDetection(workbook, headerStyle, logs, results);
            writeOperationalSpikeDetection(workbook, headerStyle, logs, results, appConfig.experiment());
            writeAblationStudy(workbook, headerStyle, results, appConfig.experiment());
            writeCharts(workbook, headerStyle);
            writeScenarioResults(workbook, headerStyle, results, appConfig.experiment());
            writeTopKExamples(workbook, headerStyle, results);
            if ("placeholder".equalsIgnoreCase(reportConfig.llmEvaluationMode())) {
                writeLlmEvaluationPlaceholder(workbook, headerStyle, results);
            }
            writeMethodNotes(workbook, headerStyle);

            try (OutputStream outputStream = Files.newOutputStream(outputPath)) {
                workbook.write(outputStream);
            }
        }
        return outputPath;
    }

    private static void writeRunSummary(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            AppConfig appConfig,
            List<LogDocument> logs,
            List<ScenarioResult> results,
            Instant runStartedAt
    ) {
        Sheet sheet = workbook.createSheet("Run Summary");
        int passCount = (int) results.stream()
                .filter(result -> result.probe().expectedClass() == result.hybrid().anomalyClass())
                .count();
        int row = 0;
        row = keyValue(sheet, row, "Run Timestamp UTC", runStartedAt.toString(), headerStyle);
        row = keyValue(sheet, row, "OpenSearch Index", appConfig.openSearchIndex(), headerStyle);
        row = keyValue(sheet, row, "Embedding Provider", appConfig.embeddingProviderName(), headerStyle);
        row = keyValue(sheet, row, "Top-K", appConfig.experiment().topK(), headerStyle);
        row = keyValue(sheet, row, "Similarity Threshold", appConfig.experiment().similarityThreshold(), headerStyle);
        row = keyValue(sheet, row, "Novelty Threshold", appConfig.experiment().noveltyThreshold(), headerStyle);
        row = keyValue(sheet, row, "Spike Threshold", appConfig.experiment().spikeThreshold(), headerStyle);
        row = keyValue(sheet, row, "Short Window", appConfig.experiment().shortWindow().toString(), headerStyle);
        row = keyValue(sheet, row, "Baseline Window", appConfig.experiment().baselineWindow().toString(), headerStyle);
        row = keyValue(sheet, row, "Hero Scenario", heroScenario(results), headerStyle);
        row = keyValue(sheet, row, "Hero Metric", "Semantic Cluster Coverage", headerStyle);
        row = keyValue(sheet, row, "Seeded Historical Logs", logs.size(), headerStyle);
        row = keyValue(sheet, row, "Scenario Count", results.size(), headerStyle);
        row = keyValue(sheet, row, "Pass Count", passCount, headerStyle);
        keyValue(sheet, row, "Fail Count", results.size() - passCount, headerStyle);
        autosize(sheet, 2);
    }

    private static void writeOverallPerformance(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            List<ScenarioResult> results,
            ExperimentConfig config
    ) {
        Sheet sheet = workbook.createSheet("Overall Performance");
        writeMetricsHeader(sheet, headerStyle);
        int rowIndex = 1;
        for (MethodPerformance performance : PaperEvaluation.overallPerformance(results, config)) {
            writePerformanceRow(sheet.createRow(rowIndex++), performance);
        }
        autosize(sheet, 6);
    }

    private static void writeSemanticClusterDetection(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            List<LogDocument> logs,
            List<ScenarioResult> results
    ) {
        Sheet sheet = workbook.createSheet("Semantic Cluster Detection");
        writeHeader(sheet.createRow(0), headerStyle, "Method", "Cluster Coverage", "Avg Clusters per Incident");
        int rowIndex = 1;
        for (ClusterMetric metric : PaperEvaluation.semanticClusterMetrics(logs, results)) {
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, metric.method().displayName());
            write(row, 1, metric.clusterCoverage());
            write(row, 2, metric.averageClustersPerIncident());
        }
        autosize(sheet, 3);
    }

    private static void writeOperationalSpikeDetection(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            List<LogDocument> logs,
            List<ScenarioResult> results,
            ExperimentConfig config
    ) {
        Sheet sheet = workbook.createSheet("Operational Spike Detection");
        writeHeader(sheet.createRow(0), headerStyle, "Method", "Spike Recall", "Avg Detection Delay", "Incident Coverage");
        int rowIndex = 1;
        for (SpikeMetric metric : PaperEvaluation.spikeMetrics(logs, results, config)) {
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, metric.method().displayName());
            write(row, 1, metric.spikeRecall());
            write(row, 2, metric.averageDetectionDelayMinutes());
            write(row, 3, metric.incidentCoverage());
        }
        autosize(sheet, 4);
    }

    private static void writeAblationStudy(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            List<ScenarioResult> results,
            ExperimentConfig config
    ) {
        Sheet sheet = workbook.createSheet("Ablation Study");
        writeMetricsHeader(sheet, headerStyle);
        int rowIndex = 1;
        for (MethodPerformance performance : PaperEvaluation.ablationStudy(results, config)) {
            writePerformanceRow(sheet.createRow(rowIndex++), performance);
        }
        autosize(sheet, 6);
    }

    private static void writeScenarioResults(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            List<ScenarioResult> results,
            ExperimentConfig config
    ) {
        Sheet sheet = workbook.createSheet("Scenario Results");
        writeHeader(sheet.createRow(0), headerStyle,
                "Scenario", "Incident Family", "Message", "Expected Class", "Actual Class", "Pass",
                "Exact Short", "Exact Baseline", "Exact Expected", "Exact Ratio",
                "Semantic Short", "Semantic Baseline", "Semantic Expected", "Semantic Ratio",
                "Top-K Count", "Max Similarity", "Semantic Signal", "Temporal Signal", "Hybrid Score",
                "Exact Pattern Class", "Top-K Class", "Semantic Frequency Class", "Semantic + Temporal Class");

        int rowIndex = 1;
        for (ScenarioResult result : results) {
            TemporalAnalysis exact = result.exactPatternBaseline();
            TemporalAnalysis semantic = result.temporal();
            Row row = sheet.createRow(rowIndex++);
            int column = 0;
            write(row, column++, result.probe().name());
            write(row, column++, result.probe().incidentFamily());
            write(row, column++, result.probe().message());
            write(row, column++, result.probe().expectedClass().name());
            write(row, column++, result.hybrid().anomalyClass().name());
            write(row, column++, result.probe().expectedClass() == result.hybrid().anomalyClass() ? "PASS" : "FAIL");
            column = writeTemporal(row, column, exact);
            column = writeTemporal(row, column, semantic);
            write(row, column++, result.neighbors().size());
            write(row, column++, result.semantic().semanticSimilarityScore());
            write(row, column++, result.hybrid().semanticSignal().name());
            write(row, column++, result.hybrid().temporalSignal().name());
            write(row, column++, result.hybrid().hybridAnomalyScore());
            write(row, column++, PaperEvaluation.classify(result, EvaluationMethod.EXACT_PATTERN, config).name());
            write(row, column++, PaperEvaluation.classify(result, EvaluationMethod.TOP_K_RETRIEVAL, config).name());
            write(row, column++, PaperEvaluation.classify(result, EvaluationMethod.SEMANTIC_FREQUENCY, config).name());
            write(row, column, PaperEvaluation.classify(result, EvaluationMethod.SEMANTIC_TEMPORAL, config).name());
        }
        autosize(sheet, 23);
    }

    private static void writeCharts(XSSFWorkbook workbook, CellStyle headerStyle) {
        XSSFSheet sheet = workbook.createSheet("Charts");
        writeHeader(sheet.createRow(0), headerStyle, "Paper Figures", "Source Sheet", "Metric", "Notes");
        writeChartMetadata(sheet, 1, "F1 Score by Method", "Ablation Study", "F1 Score", "Higher is better.");
        writeChartMetadata(sheet, 2, "Recall by Method", "Ablation Study", "Recall", "Higher is better.");
        writeChartMetadata(sheet, 3, "Semantic Cluster Coverage", "Semantic Cluster Detection", "Cluster Coverage", "Higher is better.");
        writeChartMetadata(sheet, 4, "Cluster Fragmentation", "Semantic Cluster Detection", "Avg Clusters per Incident", "Lower is better.");
        writeChartMetadata(sheet, 5, "Spike Recall", "Operational Spike Detection", "Spike Recall", "Higher is better.");

        createBarChart(workbook, sheet, "F1 Score by Method", "Ablation Study", 1, 5, 3, 0, 7, 8, 20);
        createBarChart(workbook, sheet, "Recall by Method", "Ablation Study", 1, 5, 2, 9, 16, 17, 29);
        createBarChart(workbook, sheet, "Semantic Cluster Coverage", "Semantic Cluster Detection", 1, 4, 1, 0, 31, 8, 43);
        createBarChart(workbook, sheet, "Cluster Fragmentation", "Semantic Cluster Detection", 1, 4, 2, 9, 31, 17, 43);
        createBarChart(workbook, sheet, "Spike Recall", "Operational Spike Detection", 1, 4, 1, 0, 45, 8, 57);
        autosize(sheet, 4);
    }

    private static void writeChartMetadata(
            Sheet sheet,
            int rowIndex,
            String figure,
            String sourceSheet,
            String metric,
            String notes
    ) {
        Row row = sheet.createRow(rowIndex);
        write(row, 0, figure);
        write(row, 1, sourceSheet);
        write(row, 2, metric);
        write(row, 3, notes);
    }

    private static void createBarChart(
            XSSFWorkbook workbook,
            XSSFSheet chartSheet,
            String title,
            String sourceSheetName,
            int firstDataRow,
            int lastDataRow,
            int valueColumn,
            int leftColumn,
            int topRow,
            int rightColumn,
            int bottomRow
    ) {
        XSSFSheet sourceSheet = workbook.getSheet(sourceSheetName);
        XSSFDrawing drawing = chartSheet.createDrawingPatriarch();
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, leftColumn, topRow, rightColumn, bottomRow);
        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText(title);
        chart.setTitleOverlay(false);

        XDDFCategoryAxis bottomAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
        XDDFValueAxis leftAxis = chart.createValueAxis(AxisPosition.LEFT);
        XDDFDataSource<String> categories = XDDFDataSourcesFactory.fromStringCellRange(
                sourceSheet,
                new CellRangeAddress(firstDataRow, lastDataRow, 0, 0)
        );
        XDDFNumericalDataSource<Double> values = XDDFDataSourcesFactory.fromNumericCellRange(
                sourceSheet,
                new CellRangeAddress(firstDataRow, lastDataRow, valueColumn, valueColumn)
        );

        XDDFChartData data = chart.createData(ChartTypes.BAR, bottomAxis, leftAxis);
        ((XDDFBarChartData) data).setBarDirection(BarDirection.COL);
        data.setVaryColors(true);
        XDDFChartData.Series series = data.addSeries(categories, values);
        series.setTitle(title, null);
        chart.plot(data);
    }

    private static void writeTopKExamples(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            List<ScenarioResult> results
    ) {
        Sheet sheet = workbook.createSheet("Top-K Examples");
        writeHeader(sheet.createRow(0), headerStyle,
                "Scenario", "Rank", "Neighbor Message", "Pattern", "Incident Family", "OpenSearch Score", "Cosine Similarity");
        int rowIndex = 1;
        for (ScenarioResult result : results) {
            int rank = 1;
            for (KnnNeighbor neighbor : result.neighbors()) {
                Row row = sheet.createRow(rowIndex++);
                write(row, 0, result.probe().name());
                write(row, 1, rank++);
                write(row, 2, neighbor.message());
                write(row, 3, neighbor.pattern());
                write(row, 4, neighbor.incidentFamily());
                write(row, 5, neighbor.openSearchScore());
                write(row, 6, neighbor.cosineSimilarity());
            }
        }
        autosize(sheet, 7);
    }

    private static void writeLlmEvaluationPlaceholder(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            List<ScenarioResult> results
    ) {
        Sheet sheet = workbook.createSheet("LLM Evaluation Placeholder");
        writeHeader(sheet.createRow(0), headerStyle,
                "Scenario", "Prompt Mode", "Model Name", "Expected Class", "LLM Class",
                "Explanation Correct", "Signal Confusion", "Notes");
        int rowIndex = 1;
        for (ScenarioResult result : results) {
            rowIndex = writeLlmPlaceholderRow(sheet, rowIndex, result, "confused-top-k-prompt",
                    "TBD", "TBD", "TBD", "TBD",
                    "Future LLM-mode run should test whether the explanation treats top-K count as frequency.");
            rowIndex = writeLlmPlaceholderRow(sheet, rowIndex, result, "signal-separated-prompt",
                    "TBD", "TBD", "TBD", "TBD",
                    "Future LLM-mode run should provide top-K examples separately from semantic-frequency counts.");
        }
        autosize(sheet, 8);
    }

    private static void writeMethodNotes(XSSFWorkbook workbook, CellStyle headerStyle) {
        Sheet sheet = workbook.createSheet("Method Notes");
        writeHeader(sheet.createRow(0), headerStyle, "Term", "Definition");
        Map<String, String> notes = Map.ofEntries(
                Map.entry("Hero Scenario", "B. Paraphrased Failure Family is the main synthetic proof that semantic frequency captures operational prevalence better than exact string counting."),
                Map.entry("Hero Metric", "Semantic Cluster Coverage is the main paper-facing metric for whether paraphrased incident families are captured as one operational phenomenon."),
                Map.entry("Exact Pattern", "Counts exact normalized pattern matches. This is narrow frequency."),
                Map.entry("Top-K Retrieval", "Retrieves representative nearest examples. Returned count is bounded by K and is not frequency."),
                Map.entry("Semantic Frequency", "Counts all logs above a similarity threshold in a time window."),
                Map.entry("Hybrid Framework", "Combines semantic familiarity and semantic-frequency temporal deviation."),
                Map.entry("Cluster Coverage", "Captured related events divided by total related events."),
                Map.entry("Cluster Fragmentation", "Number of groups a method splits one incident family into."),
                Map.entry("Spike Recall", "Detected spike scenarios divided by true spike scenarios."),
                Map.entry("Detection Delay", "Minutes from incident start to detection; fixed-window synthetic v1 reports 0."),
                Map.entry("Signal Confusion Rate", "Explanations that treat top-K count as total frequency divided by all explanations.")
        );
        int rowIndex = 1;
        for (Map.Entry<String, String> note : notes.entrySet()) {
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, note.getKey());
            write(row, 1, note.getValue());
        }
        autosize(sheet, 2);
    }

    private static String heroScenario(List<ScenarioResult> results) {
        return results.stream()
                .map(result -> result.probe().name())
                .filter(name -> name.startsWith("B. Paraphrased"))
                .findFirst()
                .orElse("B. Paraphrased Failure Family");
    }

    private static void writeMetricsHeader(Sheet sheet, CellStyle headerStyle) {
        writeHeader(sheet.createRow(0), headerStyle,
                "Method", "Precision", "Recall", "F1 Score", "False Positive Rate", "False Negative Rate");
    }

    private static void writePerformanceRow(Row row, MethodPerformance performance) {
        write(row, 0, performance.method().displayName());
        write(row, 1, performance.metrics().precision());
        write(row, 2, performance.metrics().recall());
        write(row, 3, performance.metrics().f1Score());
        write(row, 4, performance.metrics().falsePositiveRate());
        write(row, 5, performance.metrics().falseNegativeRate());
    }

    private static int writeTemporal(Row row, int column, TemporalAnalysis analysis) {
        write(row, column++, analysis.shortCount());
        write(row, column++, analysis.longCount());
        write(row, column++, analysis.expectedShortTermCount());
        write(row, column++, analysis.spikeRatio());
        return column;
    }

    private static int writeLlmPlaceholderRow(
            Sheet sheet,
            int rowIndex,
            ScenarioResult result,
            String promptMode,
            String modelName,
            String llmClass,
            String explanationCorrect,
            String signalConfusion,
            String notes
    ) {
        Row row = sheet.createRow(rowIndex);
        write(row, 0, result.probe().name());
        write(row, 1, promptMode);
        write(row, 2, modelName);
        write(row, 3, result.probe().expectedClass().name());
        write(row, 4, llmClass);
        write(row, 5, explanationCorrect);
        write(row, 6, signalConfusion);
        write(row, 7, notes);
        return rowIndex + 1;
    }

    private static int keyValue(Sheet sheet, int rowIndex, String key, Object value, CellStyle headerStyle) {
        Row row = sheet.createRow(rowIndex);
        Cell keyCell = row.createCell(0);
        keyCell.setCellValue(key);
        keyCell.setCellStyle(headerStyle);
        write(row, 1, value);
        return rowIndex + 1;
    }

    private static void writeHeader(Row row, CellStyle headerStyle, String... headers) {
        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
    }

    private static void write(Row row, int column, Object value) {
        Cell cell = row.createCell(column);
        if (value instanceof Number number) {
            double numericValue = number.doubleValue();
            if (Double.isFinite(numericValue)) {
                cell.setCellValue(numericValue);
            } else {
                cell.setCellValue(number.toString());
            }
        } else {
            cell.setCellValue(value == null ? "" : value.toString());
        }
    }

    private static CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private static void autosize(Sheet sheet, int columns) {
        for (int column = 0; column < columns; column++) {
            sheet.setColumnWidth(column, 24 * 256);
        }
    }
}
