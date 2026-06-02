package com.loganomaly.report;

import com.loganomaly.app.BglEvaluationWorkflow;
import com.loganomaly.config.AppConfig;
import com.loganomaly.opensearch.KnnNeighbor;
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
import org.apache.poi.xddf.usermodel.chart.XDDFChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFCategoryAxis;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
        return writeDatasetWorkbook(appConfig, results, runStartedAt, new DatasetSpec(
                "OpenStack LogHub",
                "openstack-semantic-frequency-paper-test",
                appConfig.openStack().indexName(),
                appConfig.openStack().shortWindow(),
                appConfig.openStack().baselineWindow(),
                "Labeled anomaly VM lines",
                "Normal-file lines",
                "Unlabeled abnormal-file lines",
                "Binary public-dataset validation.",
                "NORMAL/RARE/SURGE/CRITICAL are model outputs, not dataset-provided labels for OpenStack.",
                false,
                null,
                null,
                0L,
                false
        ));
    }

    public Path writeBgl(
            AppConfig appConfig,
            List<PublicDatasetEvaluationResult> results,
            Instant runStartedAt,
            BglEvaluationWorkflow.EvaluationRange evaluationRange
    ) throws IOException {
        return writeDatasetWorkbook(appConfig, results, runStartedAt, new DatasetSpec(
                "BGL LogHub",
                "bgl-semantic-frequency-paper-test",
                appConfig.bgl().indexName(),
                appConfig.bgl().shortWindow(),
                appConfig.bgl().baselineWindow(),
                "Suspicious-template rows whose native label is not '-'",
                "Suspicious-template rows whose native label is '-'",
                "Rows outside the suspicious-template filter or without full 24h + 15m warm-up history",
                "Binary public-dataset validation from native line labels after a label-blind suspicious-template prefilter.",
                "NORMAL/RARE/SURGE/CRITICAL are model outputs; native BGL labels remain the source of truth.",
                true,
                evaluationRange.start().toString(),
                evaluationRange.end().toString(),
                appConfig.bgl().evalDuration().toDays(),
                true
        ));
    }

    private Path writeDatasetWorkbook(
            AppConfig appConfig,
            List<PublicDatasetEvaluationResult> results,
            Instant runStartedAt,
            DatasetSpec spec
    ) throws IOException {
        Files.createDirectories(Path.of(appConfig.report().outputDir()));
        String configuredPrefix = appConfig.report().filePrefix();
        String prefix = configuredPrefix.equals("semantic-frequency-paper-test")
                ? spec.defaultPrefix()
                : configuredPrefix;
        Path outputPath = Path.of(
                appConfig.report().outputDir(),
                "%s-%s.xlsx".formatted(prefix, FILE_TIMESTAMP.format(runStartedAt))
        );

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = headerStyle(workbook);
            writeRunSummary(workbook, headerStyle, appConfig, results, runStartedAt, spec);
            writeOverallPerformance(workbook, headerStyle, results);
            writeSemanticClusterDetection(workbook, headerStyle, results);
            writeOperationalSpikeDetection(workbook, headerStyle, results);
            writeAblationStudy(workbook, headerStyle, results);
            writeCharts(workbook, headerStyle);
            writeEventResults(workbook, headerStyle, results);
            writeTopKExamples(workbook, headerStyle, results);
            if (spec.includeLabelBreakdown()) {
                writeLabelBreakdown(workbook, headerStyle, results);
            }
            writeMethodNotes(workbook, headerStyle, spec);

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
            List<PublicDatasetEvaluationResult> results,
            Instant runStartedAt,
            DatasetSpec spec
    ) {
        Sheet sheet = workbook.createSheet("Run Summary");
        long positiveEvents = results.stream().filter(PublicDatasetEvaluationResult::actualAnomaly).mapToLong(PublicDatasetEvaluationResult::eventCount).sum();
        long negativeEvents = results.stream().filter(result -> !result.actualAnomaly()).mapToLong(PublicDatasetEvaluationResult::eventCount).sum();
        int row = 0;
        row = keyValue(sheet, row, "Run Timestamp UTC", runStartedAt.toString(), headerStyle);
        row = keyValue(sheet, row, "Dataset", spec.datasetName(), headerStyle);
        row = keyValue(sheet, row, "Ground Truth Type", "Binary", headerStyle);
        row = keyValue(sheet, row, "OpenSearch Index", spec.indexName(), headerStyle);
        row = keyValue(sheet, row, "Embedding Provider", appConfig.embeddingProviderName(), headerStyle);
        row = keyValue(sheet, row, "Top-K", appConfig.experiment().topK(), headerStyle);
        row = keyValue(sheet, row, "Similarity Threshold", appConfig.experiment().similarityThreshold(), headerStyle);
        row = keyValue(sheet, row, "Short Window", spec.shortWindow().toString(), headerStyle);
        row = keyValue(sheet, row, "Baseline Window", spec.baselineWindow().toString(), headerStyle);
        if (spec.includeEvaluationRange()) {
            row = keyValue(sheet, row, "Evaluation Start", spec.evaluationStart(), headerStyle);
            row = keyValue(sheet, row, "Evaluation End", spec.evaluationEnd(), headerStyle);
            row = keyValue(sheet, row, "Evaluation Duration Days", spec.evaluationDurationDays(), headerStyle);
        }
        row = keyValue(sheet, row, "Positive Set", spec.positiveSet(), headerStyle);
        row = keyValue(sheet, row, "Negative Set", spec.negativeSet(), headerStyle);
        row = keyValue(sheet, row, "Excluded", spec.excludedSet(), headerStyle);
        row = keyValue(sheet, row, "Evaluation Rows", results.size(), headerStyle);
        row = keyValue(sheet, row, "Positive Events", positiveEvents, headerStyle);
        keyValue(sheet, row, "Negative Events", negativeEvents, headerStyle);
        autosize(sheet, 2);
    }

    private static void writeOverallPerformance(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            List<PublicDatasetEvaluationResult> results
    ) {
        Sheet sheet = workbook.createSheet("Overall Performance");
        writeMetricsHeader(sheet, headerStyle);
        int rowIndex = 1;
        for (MethodPerformance performance : PublicDatasetEvaluation.overallPerformance(results)) {
            writePerformanceRow(sheet.createRow(rowIndex++), performance);
        }
        autosize(sheet, 6);
    }

    private static void writeSemanticClusterDetection(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            List<PublicDatasetEvaluationResult> results
    ) {
        Sheet sheet = workbook.createSheet("Semantic Cluster Detection");
        writeHeader(sheet.createRow(0), headerStyle, "Method", "Cluster Coverage", "Avg Clusters per Incident");
        int rowIndex = 1;
        for (ClusterMetric metric : PublicDatasetEvaluation.semanticClusterMetrics(results)) {
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
            List<PublicDatasetEvaluationResult> results
    ) {
        Sheet sheet = workbook.createSheet("Operational Spike Detection");
        writeHeader(sheet.createRow(0), headerStyle, "Method", "Spike Recall", "Avg Detection Delay", "Incident Coverage");
        int rowIndex = 1;
        for (SpikeMetric metric : PublicDatasetEvaluation.spikeMetrics(results)) {
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
            List<PublicDatasetEvaluationResult> results
    ) {
        Sheet sheet = workbook.createSheet("Ablation Study");
        writeMetricsHeader(sheet, headerStyle);
        int rowIndex = 1;
        for (MethodPerformance performance : PublicDatasetEvaluation.ablationStudy(results)) {
            writePerformanceRow(sheet.createRow(rowIndex++), performance);
        }
        autosize(sheet, 6);
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

    private static void writeEventResults(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            List<PublicDatasetEvaluationResult> results
    ) {
        Sheet sheet = workbook.createSheet("Event Results");
        writeHeader(sheet.createRow(0), headerStyle,
                "Candidate", "Ground Truth", "Native Label", "Event Count", "Incident Family", "Pattern", "Message",
                "Exact Short", "Exact Baseline", "Exact Expected", "Exact Ratio",
                "Semantic Short", "Semantic Baseline", "Semantic Expected", "Semantic Ratio",
                "Top-K Count", "Max Similarity", "Hybrid Score",
                "Exact Pattern Class", "Top-K Class", "Semantic Frequency Class", "Semantic + Temporal Class", "Hybrid Class");

        int rowIndex = 1;
        for (PublicDatasetEvaluationResult result : results) {
            var exact = result.scenarioResult().exactPatternBaseline();
            var semantic = result.scenarioResult().temporal();
            Row row = sheet.createRow(rowIndex++);
            int column = 0;
            write(row, column++, result.candidateName());
            write(row, column++, result.groundTruth().name());
            write(row, column++, result.nativeLabel());
            write(row, column++, result.eventCount());
            write(row, column++, result.scenarioResult().probe().incidentFamily());
            write(row, column++, result.scenarioResult().probe().pattern());
            write(row, column++, result.scenarioResult().probe().message());
            column = writeTemporal(row, column, exact);
            column = writeTemporal(row, column, semantic);
            write(row, column++, result.scenarioResult().neighbors().size());
            write(row, column++, result.scenarioResult().semantic().semanticSimilarityScore());
            write(row, column++, result.scenarioResult().hybrid().hybridAnomalyScore());
            write(row, column++, result.exactPatternClass().name());
            write(row, column++, result.topKClass().name());
            write(row, column++, result.semanticFrequencyClass().name());
            write(row, column++, result.semanticTemporalClass().name());
            write(row, column, result.scenarioResult().hybrid().anomalyClass().name());
        }
        autosize(sheet, 23);
    }

    private static void writeTopKExamples(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            List<PublicDatasetEvaluationResult> results
    ) {
        Sheet sheet = workbook.createSheet("Top-K Examples");
        writeHeader(sheet.createRow(0), headerStyle,
                "Candidate", "Rank", "Neighbor Message", "Pattern", "Incident Family", "OpenSearch Score", "Cosine Similarity");
        int rowIndex = 1;
        for (PublicDatasetEvaluationResult result : results) {
            int rank = 1;
            for (KnnNeighbor neighbor : result.scenarioResult().neighbors()) {
                Row row = sheet.createRow(rowIndex++);
                write(row, 0, result.candidateName());
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

    private static void writeLabelBreakdown(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            List<PublicDatasetEvaluationResult> results
    ) {
        Sheet sheet = workbook.createSheet("BGL Label Breakdown");
        writeHeader(sheet.createRow(0), headerStyle, "Raw Label", "Ground Truth", "Event Count", "Candidate Rows");
        Map<String, LabelBreakdown> counts = new LinkedHashMap<>();
        for (PublicDatasetEvaluationResult result : results) {
            counts.compute(result.nativeLabel(), (label, existing) -> {
                if (existing == null) {
                    return new LabelBreakdown(result.groundTruth(), result.eventCount(), 1);
                }
                return new LabelBreakdown(existing.groundTruth(), existing.eventCount() + result.eventCount(), existing.candidateRows() + 1);
            });
        }
        int rowIndex = 1;
        for (Map.Entry<String, LabelBreakdown> entry : counts.entrySet()) {
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, entry.getKey());
            write(row, 1, entry.getValue().groundTruth().name());
            write(row, 2, entry.getValue().eventCount());
            write(row, 3, entry.getValue().candidateRows());
        }
        autosize(sheet, 4);
    }

    private static void writeMethodNotes(XSSFWorkbook workbook, CellStyle headerStyle, DatasetSpec spec) {
        Sheet sheet = workbook.createSheet("Method Notes");
        writeHeader(sheet.createRow(0), headerStyle, "Term", "Definition");
        Map<String, String> notes = Map.ofEntries(
                Map.entry("Ground Truth Type", spec.groundTruthNote()),
                Map.entry("Positive Set", spec.positiveSet()),
                Map.entry("Negative Set", spec.negativeSet()),
                Map.entry("Excluded", spec.excludedSet()),
                Map.entry("Exact Pattern", "Counts exact normalized pattern matches. This is narrow frequency."),
                Map.entry("Top-K Retrieval", "Retrieves representative nearest examples. Returned count is bounded by K and is not frequency."),
                Map.entry("Semantic Frequency", "Counts all logs above a similarity threshold in a time window."),
                Map.entry("Hybrid Framework", "Combines semantic familiarity and semantic-frequency temporal deviation."),
                Map.entry("Predicted Classes", spec.predictedClassesNote())
        );
        int rowIndex = 1;
        for (Map.Entry<String, String> note : notes.entrySet()) {
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, note.getKey());
            write(row, 1, note.getValue());
        }
        autosize(sheet, 2);
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

    private static int writeTemporal(Row row, int column, com.loganomaly.core.TemporalAnalysis analysis) {
        write(row, column++, analysis.shortCount());
        write(row, column++, analysis.longCount());
        write(row, column++, analysis.expectedShortTermCount());
        write(row, column++, analysis.spikeRatio());
        return column;
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

    private record DatasetSpec(
            String datasetName,
            String defaultPrefix,
            String indexName,
            Duration shortWindow,
            Duration baselineWindow,
            String positiveSet,
            String negativeSet,
            String excludedSet,
            String groundTruthNote,
            String predictedClassesNote,
            boolean includeLabelBreakdown,
            String evaluationStart,
            String evaluationEnd,
            long evaluationDurationDays,
            boolean includeEvaluationRange
    ) {
    }

    private record LabelBreakdown(
            BinaryGroundTruth groundTruth,
            long eventCount,
            int candidateRows
    ) {
    }
}
