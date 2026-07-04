package com.loganomaly.report;

import com.loganomaly.config.AppConfig;
import com.loganomaly.config.ExperimentConfig;
import com.loganomaly.config.ReportConfig;
import com.loganomaly.core.AnomalyClass;
import com.loganomaly.experiment.ScenarioResult;
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
            writeClassificationMetrics(workbook, headerStyle, results, appConfig.experiment());
            writeSemanticMetrics(workbook, headerStyle, logs, results, appConfig.experiment());
            writeScenarioResults(workbook, headerStyle, results, appConfig.experiment());
            writeAblationStudy(workbook, headerStyle, results, appConfig.experiment());
            writeCharts(workbook, headerStyle);

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

    private static void writeClassificationMetrics(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            List<ScenarioResult> results,
            ExperimentConfig config
    ) {
        Sheet sheet = workbook.createSheet("Classification Metrics");
        writeMetricsHeader(sheet, headerStyle);
        int rowIndex = 1;
        for (MethodPerformance performance : PaperEvaluation.overallPerformance(results, config)) {
            writePerformanceRow(sheet.createRow(rowIndex++), performance);
        }
        autosize(sheet, 7);
    }

    private static void writeSemanticMetrics(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            List<LogDocument> logs,
            List<ScenarioResult> results,
            ExperimentConfig config
    ) {
        Sheet sheet = workbook.createSheet("Semantic Metrics");
        writeHeader(sheet.createRow(0), headerStyle, "Method", "Cluster Coverage", "Incident Coverage", "Fragmentation");
        Map<EvaluationMethod, ClusterMetric> clustersByMethod = PaperEvaluation.semanticClusterMetrics(logs, results).stream()
                .collect(java.util.stream.Collectors.toMap(ClusterMetric::method, metric -> metric));
        Map<EvaluationMethod, SpikeMetric> spikeByMethod = PaperEvaluation.spikeMetrics(logs, results, config).stream()
                .collect(java.util.stream.Collectors.toMap(SpikeMetric::method, metric -> metric));
        int rowIndex = 1;
        for (EvaluationMethod method : List.of(
                EvaluationMethod.EXACT_PATTERN,
                EvaluationMethod.TOP_K_RETRIEVAL,
                EvaluationMethod.SEMANTIC_FREQUENCY,
                EvaluationMethod.SEMANTIC_TEMPORAL,
                EvaluationMethod.HYBRID_FRAMEWORK
        )) {
            ClusterMetric clusterMetric = clustersByMethod.get(method);
            SpikeMetric spikeMetric = spikeByMethod.get(method);
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, method.displayName());
            write(row, 1, clusterMetric == null ? 0.0 : clusterMetric.clusterCoverage());
            write(row, 2, spikeMetric == null ? 0.0 : spikeMetric.incidentCoverage());
            write(row, 3, clusterMetric == null ? 0.0 : clusterMetric.averageClustersPerIncident());
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
        writeHeader(sheet.createRow(0), headerStyle, "Method", "Precision", "Recall", "F1 Score");
        int rowIndex = 1;
        for (MethodPerformance performance : PaperEvaluation.ablationStudy(results, config)) {
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, performance.method().displayName());
            write(row, 1, performance.metrics().precision());
            write(row, 2, performance.metrics().recall());
            write(row, 3, performance.metrics().f1Score());
        }
        autosize(sheet, 4);
    }

    private static void writeScenarioResults(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            List<ScenarioResult> results,
            ExperimentConfig config
    ) {
        Sheet sheet = workbook.createSheet("Scenario Results");
        writeHeader(sheet.createRow(0), headerStyle,
                "Scenario", "Exact Pattern", "Top-K", "Semantic Frequency", "Semantic + Temporal", "Hybrid");
        List<String> scenarioOrder = List.of(
                "A. Exact Repeated Error",
                "Q. Paraphrased Semantic Surge",
                "C. Novel Semantic Event",
                "D. Known Semantic Spike",
                "N. High-Volume Routine Noise"
        );
        int rowIndex = 1;
        for (String scenarioName : scenarioOrder) {
            ScenarioResult result = results.stream()
                    .filter(candidate -> candidate.probe().name().equals(scenarioName))
                    .findFirst()
                    .orElse(null);
            if (result == null) {
                continue;
            }
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, scenarioDisplayName(scenarioName));
            write(row, 1, scenarioOutcome(result, EvaluationMethod.EXACT_PATTERN, config));
            write(row, 2, scenarioOutcome(result, EvaluationMethod.TOP_K_RETRIEVAL, config));
            write(row, 3, scenarioOutcome(result, EvaluationMethod.SEMANTIC_FREQUENCY, config));
            write(row, 4, scenarioOutcome(result, EvaluationMethod.SEMANTIC_TEMPORAL, config));
            write(row, 5, scenarioOutcome(result, EvaluationMethod.HYBRID_FRAMEWORK, config));
        }
        autosize(sheet, 6);
    }

    private static void writeCharts(XSSFWorkbook workbook, CellStyle headerStyle) {
        XSSFSheet sheet = workbook.createSheet("Charts");
        writeHeader(sheet.createRow(0), headerStyle, "Paper Figures", "Source Sheet", "Metric", "Notes");
        writeChartMetadata(sheet, 1, "Semantic Cluster Coverage", "Semantic Metrics", "Cluster Coverage", "Higher is better.");
        writeChartMetadata(sheet, 2, "Incident Fragmentation", "Semantic Metrics", "Fragmentation", "Lower is better.");

        createBarChart(workbook, sheet, "Semantic Cluster Coverage", "Semantic Metrics", 1, 5, 1, 0, 5, 8, 18);
        createBarChart(workbook, sheet, "Incident Fragmentation", "Semantic Metrics", 1, 5, 3, 9, 5, 17, 18);
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

    private static String heroScenario(List<ScenarioResult> results) {
        return results.stream()
                .map(result -> result.probe().name())
                .filter(name -> name.equals("Q. Paraphrased Semantic Surge"))
                .findFirst()
                .orElseGet(() -> results.stream()
                        .map(result -> result.probe().name())
                        .filter(name -> name.startsWith("B. Paraphrased"))
                        .findFirst()
                        .orElse("B. Paraphrased Failure Family"));
    }

    private static void writeMetricsHeader(Sheet sheet, CellStyle headerStyle) {
        writeHeader(sheet.createRow(0), headerStyle,
                "Method", "Precision", "Recall", "F1 Score", "False Positive Rate", "False Negative Rate", "Notes");
    }

    private static void writePerformanceRow(Row row, MethodPerformance performance) {
        write(row, 0, performance.method().displayName());
        write(row, 1, performance.metrics().precision());
        write(row, 2, performance.metrics().recall());
        write(row, 3, performance.metrics().f1Score());
        write(row, 4, performance.metrics().falsePositiveRate());
        write(row, 5, performance.metrics().falseNegativeRate());
        write(row, 6, methodNote(performance.method()));
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

    private static String scenarioDisplayName(String scenarioName) {
        return switch (scenarioName) {
            case "A. Exact Repeated Error" -> "Exact Repeats";
            case "B. Paraphrased Failure Family" -> "Paraphrased Family";
            case "Q. Paraphrased Semantic Surge" -> "Paraphrased Semantic Surge";
            case "C. Novel Semantic Event" -> "Novel Event";
            case "D. Known Semantic Spike" -> "Operational Surge";
            case "N. High-Volume Routine Noise" -> "High Volume Normal";
            default -> scenarioName;
        };
    }

    private static String methodNote(EvaluationMethod method) {
        return switch (method) {
            case EXACT_PATTERN -> "Template-level";
            case TOP_K_RETRIEVAL -> "Retrieval only";
            case SEMANTIC_FREQUENCY -> "Family count";
            case SEMANTIC_TEMPORAL -> "Surge detection";
            case HYBRID_FRAMEWORK -> "Taxonomy + temporal";
        };
    }

    private static String scenarioOutcome(
            ScenarioResult result,
            EvaluationMethod method,
            ExperimentConfig config
    ) {
        return switch (method) {
            case EXACT_PATTERN -> detectedOrMissed(PaperEvaluation.classify(result, method, config));
            case TOP_K_RETRIEVAL -> {
                AnomalyClass outcome = PaperEvaluation.classify(result, method, config);
                yield outcome == AnomalyClass.RARE_ANOMALY ? "Weak neighbors" : "Context only";
            }
            case SEMANTIC_FREQUENCY -> {
                AnomalyClass outcome = PaperEvaluation.classify(result, method, config);
                if (outcome == AnomalyClass.RARE_ANOMALY) {
                    yield "Low history";
                }
                yield result.temporal().shortCount() > 0 ? "Captured family" : "Not captured";
            }
            case SEMANTIC_TEMPORAL -> detectedOrMissed(PaperEvaluation.classify(result, method, config));
            case HYBRID_FRAMEWORK -> result.hybrid().anomalyClass().name();
        };
    }

    private static String detectedOrMissed(AnomalyClass anomalyClass) {
        return anomalyClass == AnomalyClass.NORMAL_BEHAVIOR ? "Missed" : "Detected";
    }
}
