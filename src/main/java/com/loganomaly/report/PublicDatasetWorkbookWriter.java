package com.loganomaly.report;

import com.loganomaly.app.BglEvaluationWorkflow;
import com.loganomaly.config.AppConfig;
import com.loganomaly.core.AnomalyClass;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PublicDatasetWorkbookWriter {
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withLocale(Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    public Path writeOpenStack(
            AppConfig appConfig,
            List<PublicDatasetEvaluationResult> results,
            Instant runStartedAt
    ) throws IOException {
        return writeOpenStack(appConfig, results, runStartedAt, defaultOpenStackSummary(results));
    }

    public Path writeOpenStack(
            AppConfig appConfig,
            List<PublicDatasetEvaluationResult> results,
            Instant runStartedAt,
            OpenStackCaseStudySummary summary
    ) throws IOException {
        return writeWorkbook(
                appConfig,
                runStartedAt,
                "openstack-semantic-frequency-paper-test",
                workbook -> {
                    CellStyle headerStyle = headerStyle(workbook);
                    writeOpenStackRunSummary(workbook, headerStyle, appConfig, results, runStartedAt);
                    writePipelineValidation(workbook, headerStyle, results);
                    writeOpenStackOperationalMetrics(workbook, headerStyle, summary);
                    writeOpenStackRuntime(workbook, headerStyle, summary);
                }
        );
    }

    public Path writeBgl(
            AppConfig appConfig,
            List<PublicDatasetEvaluationResult> results,
            Instant runStartedAt,
            BglEvaluationWorkflow.EvaluationRange evaluationRange,
            List<BglEvaluationWorkflow.ThresholdSweepResult> thresholdSweep,
            List<BglEvaluationWorkflow.CandidateStrategyComparisonRow> candidateStrategyComparison
    ) throws IOException {
        return writeWorkbook(
                appConfig,
                runStartedAt,
                "bgl-semantic-frequency-paper-test",
                workbook -> {
                    CellStyle headerStyle = headerStyle(workbook);
                    writeBglRunSummary(workbook, headerStyle, appConfig, results, runStartedAt, evaluationRange);
                    if (!candidateStrategyComparison.isEmpty()) {
                        writeCandidateSelectionImpact(workbook, headerStyle, candidateStrategyComparison);
                    }
                    writeBglMethodComparison(workbook, headerStyle, results);
                    if (!thresholdSweep.isEmpty()) {
                        writeThresholdSensitivity(workbook, headerStyle, thresholdSweep);
                    }
                    writeFalsePositiveAnalysis(workbook, headerStyle, results);
                    writeFalseNegativeAnalysis(workbook, headerStyle, results);
                    if (!thresholdSweep.isEmpty() || !candidateStrategyComparison.isEmpty()) {
                        writeBglCharts(workbook, headerStyle, thresholdSweep, candidateStrategyComparison);
                    }
                }
        );
    }

    private Path writeWorkbook(
            AppConfig appConfig,
            Instant runStartedAt,
            String defaultPrefix,
            WorkbookWriter writer
    ) throws IOException {
        Files.createDirectories(Path.of(appConfig.report().outputDir()));
        String configuredPrefix = appConfig.report().filePrefix();
        String prefix = configuredPrefix.equals("semantic-frequency-paper-test")
                ? defaultPrefix
                : configuredPrefix;
        Path outputPath = Path.of(
                appConfig.report().outputDir(),
                "%s-%s.xlsx".formatted(prefix, FILE_TIMESTAMP.format(runStartedAt))
        );

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            writer.write(workbook);
            try (OutputStream outputStream = Files.newOutputStream(outputPath)) {
                workbook.write(outputStream);
            }
        }
        return outputPath;
    }

    private static void writeOpenStackRunSummary(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            AppConfig appConfig,
            List<PublicDatasetEvaluationResult> results,
            Instant runStartedAt
    ) {
        Sheet sheet = workbook.createSheet("Run Summary");
        int row = 0;
        row = keyValue(sheet, row, "Run Timestamp UTC", runStartedAt.toString(), headerStyle);
        row = keyValue(sheet, row, "Dataset", "OpenStack LogHub", headerStyle);
        row = keyValue(sheet, row, "Ground Truth Type", "Binary", headerStyle);
        row = keyValue(sheet, row, "OpenSearch Index", appConfig.openStack().indexName(), headerStyle);
        row = keyValue(sheet, row, "Embedding Provider", appConfig.embeddingProviderName(), headerStyle);
        row = keyValue(sheet, row, "Short Window", appConfig.openStack().shortWindow().toString(), headerStyle);
        row = keyValue(sheet, row, "Baseline Window", appConfig.openStack().baselineWindow().toString(), headerStyle);
        row = keyValue(sheet, row, "Positive Set", "Labeled anomaly VM lines", headerStyle);
        row = keyValue(sheet, row, "Negative Set", "Normal-file lines", headerStyle);
        row = keyValue(sheet, row, "Excluded", "Unlabeled abnormal-file lines", headerStyle);
        keyValue(sheet, row, "Evaluation Rows", results.size(), headerStyle);
        autosize(sheet, 2);
    }

    private static void writePipelineValidation(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            List<PublicDatasetEvaluationResult> results
    ) {
        Sheet sheet = workbook.createSheet("Pipeline Validation");
        writeHeader(sheet.createRow(0), headerStyle, "Method", "Precision", "Recall", "F1 Score");
        int rowIndex = 1;
        for (MethodPerformance performance : PublicDatasetEvaluation.pipelineValidation(results)) {
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, performance.method().displayName());
            write(row, 1, performance.metrics().precision());
            write(row, 2, performance.metrics().recall());
            write(row, 3, performance.metrics().f1Score());
        }
        autosize(sheet, 4);
    }

    private static void writeOpenStackOperationalMetrics(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            OpenStackCaseStudySummary summary
    ) {
        Sheet sheet = workbook.createSheet("Operational Metrics");
        int row = 0;
        row = keyValue(sheet, row, "Events Processed", summary.eventsProcessed(), headerStyle);
        row = keyValue(sheet, row, "Candidates Selected", summary.candidatesSelected(), headerStyle);
        row = keyValue(sheet, row, "Semantic Families", summary.semanticFamilies(), headerStyle);
        keyValue(sheet, row, "Detected Surges", summary.detectedSurges(), headerStyle);
        autosize(sheet, 2);
    }

    private static void writeOpenStackRuntime(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            OpenStackCaseStudySummary summary
    ) {
        Sheet sheet = workbook.createSheet("Runtime");
        writeHeader(sheet.createRow(0), headerStyle, "Operation", "Runtime");
        int row = 1;
        row = writeOperation(sheet, row, "Embedding", summary.embeddingRuntime());
        row = writeOperation(sheet, row, "Index Build", summary.indexBuildRuntime());
        row = writeOperation(sheet, row, "Candidate Selection", summary.candidateSelectionRuntime());
        writeOperation(sheet, row, "Semantic Analysis", summary.semanticAnalysisRuntime());
        autosize(sheet, 2);
    }

    private static void writeBglRunSummary(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            AppConfig appConfig,
            List<PublicDatasetEvaluationResult> results,
            Instant runStartedAt,
            BglEvaluationWorkflow.EvaluationRange evaluationRange
    ) {
        Sheet sheet = workbook.createSheet("Run Summary");
        int row = 0;
        row = keyValue(sheet, row, "Run Timestamp UTC", runStartedAt.toString(), headerStyle);
        row = keyValue(sheet, row, "Dataset", "BGL LogHub", headerStyle);
        row = keyValue(sheet, row, "Ground Truth Type", "Binary", headerStyle);
        row = keyValue(sheet, row, "OpenSearch Index", appConfig.bgl().indexName(), headerStyle);
        row = keyValue(sheet, row, "Embedding Provider", appConfig.embeddingProviderName(), headerStyle);
        row = keyValue(sheet, row, "Candidate Mode", appConfig.bgl().candidateMode(), headerStyle);
        row = keyValue(sheet, row, "Short Window", appConfig.bgl().shortWindow().toString(), headerStyle);
        row = keyValue(sheet, row, "Baseline Window", appConfig.bgl().baselineWindow().toString(), headerStyle);
        row = keyValue(sheet, row, "Minimum Historical Support", appConfig.bgl().minimumHistoricalSupport(), headerStyle);
        row = keyValue(sheet, row, "Minimum Alert Short Support", appConfig.bgl().minimumAlertShortSupport(), headerStyle);
        row = keyValue(sheet, row, "Evaluation Start", evaluationRange.start().toString(), headerStyle);
        row = keyValue(sheet, row, "Evaluation End", evaluationRange.end().toString(), headerStyle);
        row = keyValue(sheet, row, "Evaluation Rows", results.size(), headerStyle);
        keyValue(sheet, row, "Positive Events", results.stream().filter(PublicDatasetEvaluationResult::actualAnomaly).mapToLong(PublicDatasetEvaluationResult::eventCount).sum(), headerStyle);
        autosize(sheet, 2);
    }

    private static void writeCandidateSelectionImpact(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            List<BglEvaluationWorkflow.CandidateStrategyComparisonRow> rows
    ) {
        Sheet sheet = workbook.createSheet("Candidate Selection Impact");
        writeHeader(sheet.createRow(0), headerStyle, "Candidate Mode", "Candidates", "Precision", "Recall", "F1");
        int rowIndex = 1;
        for (BglEvaluationWorkflow.CandidateStrategyComparisonRow rowData : rows) {
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, candidateModeDisplay(rowData.candidateMode()));
            write(row, 1, rowData.evaluationRows());
            write(row, 2, rowData.metrics().precision());
            write(row, 3, rowData.metrics().recall());
            write(row, 4, rowData.metrics().f1Score());
        }
        autosize(sheet, 5);
    }

    private static void writeBglMethodComparison(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            List<PublicDatasetEvaluationResult> results
    ) {
        Sheet sheet = workbook.createSheet("Method Comparison");
        writeHeader(sheet.createRow(0), headerStyle, "Method", "Precision", "Recall", "F1", "FPR");
        int rowIndex = 1;
        for (MethodPerformance performance : PublicDatasetEvaluation.methodComparison(results)) {
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, performance.method().displayName());
            write(row, 1, performance.metrics().precision());
            write(row, 2, performance.metrics().recall());
            write(row, 3, performance.metrics().f1Score());
            write(row, 4, performance.metrics().falsePositiveRate());
        }
        autosize(sheet, 5);
    }

    private static void writeThresholdSensitivity(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            List<BglEvaluationWorkflow.ThresholdSweepResult> thresholdSweep
    ) {
        Sheet sheet = workbook.createSheet("Threshold Sensitivity");
        writeHeader(sheet.createRow(0), headerStyle, "Tsim", "Precision", "Recall", "F1");
        int rowIndex = 1;
        for (BglEvaluationWorkflow.ThresholdSweepResult result : thresholdSweep) {
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, result.similarityThreshold());
            write(row, 1, result.metrics().precision());
            write(row, 2, result.metrics().recall());
            write(row, 3, result.metrics().f1Score());
        }
        autosize(sheet, 4);
    }

    private static void writeFalsePositiveAnalysis(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            List<PublicDatasetEvaluationResult> results
    ) {
        Sheet sheet = workbook.createSheet("False Positive Analysis");
        writeHeader(sheet.createRow(0), headerStyle, "Template", "FP Count", "Reason");
        List<TemplateCountRow> rows = aggregateTemplateCounts(results, false, true);
        int rowIndex = 1;
        for (TemplateCountRow rowData : rows) {
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, rowData.template());
            write(row, 1, rowData.count());
            write(row, 2, rowData.reason());
        }
        autosize(sheet, 3);
    }

    private static void writeFalseNegativeAnalysis(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            List<PublicDatasetEvaluationResult> results
    ) {
        Sheet sheet = workbook.createSheet("False Negative Analysis");
        writeHeader(sheet.createRow(0), headerStyle, "Template", "FN Count", "Reason");
        List<TemplateCountRow> rows = aggregateTemplateCounts(results, true, false);
        int rowIndex = 1;
        for (TemplateCountRow rowData : rows) {
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, rowData.template());
            write(row, 1, rowData.count());
            write(row, 2, rowData.reason());
        }
        autosize(sheet, 3);
    }

    private static void writeBglCharts(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            List<BglEvaluationWorkflow.ThresholdSweepResult> thresholdSweep,
            List<BglEvaluationWorkflow.CandidateStrategyComparisonRow> candidateStrategyComparison
    ) {
        XSSFSheet sheet = workbook.createSheet("Charts");
        writeHeader(sheet.createRow(0), headerStyle, "Figure", "Source Sheet", "Metric");
        int metadataRow = 1;
        if (!candidateStrategyComparison.isEmpty()) {
            writeChartMetadata(sheet, metadataRow++, "Candidate Selection Impact", "Candidate Selection Impact", "F1");
            createBarChart(
                    workbook,
                    sheet,
                    "Candidate Selection Impact",
                    "Candidate Selection Impact",
                    1,
                    candidateStrategyComparison.size(),
                    4,
                    0,
                    5,
                    8,
                    18
            );
        }
        if (!thresholdSweep.isEmpty()) {
            writeChartMetadata(sheet, metadataRow, "Threshold Sensitivity", "Threshold Sensitivity", "F1");
            createLineChart(
                    workbook,
                    sheet,
                    "Threshold Sensitivity",
                    "Threshold Sensitivity",
                    1,
                    thresholdSweep.size(),
                    3,
                    candidateStrategyComparison.isEmpty() ? 0 : 9,
                    5,
                    candidateStrategyComparison.isEmpty() ? 8 : 17,
                    18
            );
        }
        autosize(sheet, 3);
    }

    private static List<TemplateCountRow> aggregateTemplateCounts(
            List<PublicDatasetEvaluationResult> results,
            boolean requireActualAnomaly,
            boolean requirePredictedPositive
    ) {
        Map<String, TemplateAggregate> grouped = new LinkedHashMap<>();
        for (PublicDatasetEvaluationResult result : results) {
            boolean actual = result.actualAnomaly();
            boolean predicted = PublicDatasetEvaluation.isPositiveForResult(result, EvaluationMethod.HYBRID_FRAMEWORK);
            if (actual != requireActualAnomaly || predicted != requirePredictedPositive) {
                continue;
            }
            String template = result.scenarioResult().probe().pattern();
            grouped.compute(template, (ignored, existing) -> {
                TemplateAggregate aggregate = existing == null ? new TemplateAggregate() : existing;
                aggregate.count += result.eventCount();
                aggregate.minBaseline = Math.min(aggregate.minBaseline, result.scenarioResult().temporal().longCount());
                aggregate.maxSimilarity = Math.max(aggregate.maxSimilarity, result.scenarioResult().semantic().semanticSimilarityScore());
                aggregate.maxShortCount = Math.max(aggregate.maxShortCount, result.scenarioResult().temporal().shortCount());
                return aggregate;
            });
        }
        return grouped.entrySet().stream()
                .map(entry -> new TemplateCountRow(
                        entry.getKey(),
                        entry.getValue().count,
                        reasonFor(entry.getValue(), requirePredictedPositive)
                ))
                .sorted(Comparator.comparingLong(TemplateCountRow::count).reversed())
                .limit(10)
                .toList();
    }

    private static String reasonFor(TemplateAggregate aggregate, boolean falsePositive) {
        if (aggregate.minBaseline < 5) {
            return falsePositive ? "small baseline triggered false alert" : "insufficient history suppressed alert";
        }
        if (!falsePositive && aggregate.maxSimilarity < 0.85) {
            return "novel wording below similarity threshold";
        }
        if (falsePositive && aggregate.maxSimilarity >= 0.9) {
            return "semantically similar but operationally benign";
        }
        if (aggregate.maxShortCount < 3) {
            return "short-window support below alert threshold";
        }
        return "manual review needed";
    }

    private static String candidateModeDisplay(String candidateMode) {
        return switch (candidateMode) {
            case "all_lines" -> "All Lines";
            case "label_blind_suspicious_templates" -> "Suspicious Templates";
            case "strict_suspicious_templates" -> "Strict Suspicious Templates";
            default -> candidateMode;
        };
    }

    private static OpenStackCaseStudySummary defaultOpenStackSummary(List<PublicDatasetEvaluationResult> results) {
        long eventsProcessed = results.stream().mapToLong(PublicDatasetEvaluationResult::eventCount).sum();
        long candidatesSelected = results.size();
        long semanticFamilies = results.stream()
                .map(result -> result.scenarioResult().probe().pattern())
                .distinct()
                .count();
        long detectedSurges = results.stream()
                .filter(result -> PublicDatasetEvaluation.isSpike(result.predictedClass(EvaluationMethod.HYBRID_FRAMEWORK)))
                .count();
        return new OpenStackCaseStudySummary(
                eventsProcessed,
                candidatesSelected,
                semanticFamilies,
                detectedSurges,
                "not measured in evaluate run",
                "existing index reused",
                "not measured in evaluate run",
                "not measured in evaluate run"
        );
    }

    private static int writeOperation(Sheet sheet, int rowIndex, String operation, String runtime) {
        Row row = sheet.createRow(rowIndex);
        write(row, 0, operation);
        write(row, 1, runtime);
        return rowIndex + 1;
    }

    private static void writeChartMetadata(
            Sheet sheet,
            int rowIndex,
            String figure,
            String sourceSheet,
            String metric
    ) {
        Row row = sheet.createRow(rowIndex);
        write(row, 0, figure);
        write(row, 1, sourceSheet);
        write(row, 2, metric);
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

    private static void createLineChart(
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

        XDDFChartData data = chart.createData(ChartTypes.LINE, bottomAxis, leftAxis);
        XDDFChartData.Series series = data.addSeries(categories, values);
        series.setTitle(title, null);
        chart.plot(data);
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

    public record OpenStackCaseStudySummary(
            long eventsProcessed,
            long candidatesSelected,
            long semanticFamilies,
            long detectedSurges,
            String embeddingRuntime,
            String indexBuildRuntime,
            String candidateSelectionRuntime,
            String semanticAnalysisRuntime
    ) {
    }

    private record TemplateCountRow(
            String template,
            long count,
            String reason
    ) {
    }

    private static final class TemplateAggregate {
        private long count;
        private int minBaseline = Integer.MAX_VALUE;
        private double maxSimilarity = 0.0;
        private int maxShortCount = 0;
    }

    @FunctionalInterface
    private interface WorkbookWriter {
        void write(XSSFWorkbook workbook) throws IOException;
    }
}
