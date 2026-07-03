package com.loganomaly.report;

import com.loganomaly.app.BglEvaluationWorkflow;
import com.loganomaly.app.BglMetadataAnalysisWorkflow;
import com.loganomaly.app.SyntheticMetadataWorkflow;
import com.loganomaly.config.AppConfig;
import com.loganomaly.core.AnomalyClass;
import com.loganomaly.experiment.ScenarioResult;
import com.loganomaly.opensearch.LogDocument;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PaperMetadataWorkbookWriter {
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withLocale(Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    public Path writeSyntheticMetadata(
            AppConfig appConfig,
            List<ScenarioResult> results,
            List<LogDocument> logs,
            Instant runStartedAt
    ) throws IOException {
        return writeWorkbook(appConfig, runStartedAt, "synthetic-paper-metadata", workbook -> {
            CellStyle headerStyle = headerStyle(workbook);
            writeSyntheticRunSummary(workbook, headerStyle, appConfig, results, logs, runStartedAt);
            writeTaxonomyDistribution(workbook.createSheet("Taxonomy Distribution"), headerStyle, taxonomyRows(results, null, false));
            writeSignalCombination(workbook.createSheet("Signal Combination"), headerStyle, syntheticSignalCombinationRows(results));
            writeFamilySizeDistribution(workbook.createSheet("Semantic Family Size Distribution"), headerStyle, familySizeRows(results, null));
            writeSimilarityDistribution(workbook.createSheet("Similarity Distribution"), headerStyle, similarityRows(results));
            writeSyntheticScenarioCoverage(workbook.createSheet("Scenario Coverage Addendum"), headerStyle, results, logs);
            writeMethodNotes(workbook.createSheet("Method Notes"), headerStyle, List.of(
                    "Semantic family size is measured as event prevalence in the configured time window.",
                    "Synthetic metadata is reconstructed offline from SyntheticLogDataset without OpenSearch.",
                    "Similarity distribution uses the max semantic similarity for each scenario.",
                    "Matched-template cardinality is not reported from current artifacts."
            ));
        });
    }

    public Path writeBglMetadata(
            AppConfig appConfig,
            List<PublicDatasetEvaluationResult> results,
            Instant runStartedAt,
            BglEvaluationWorkflow.EvaluationRange evaluationRange,
            BglMetadataAnalysisWorkflow.CandidateFilterStats filterStats
    ) throws IOException {
        return writeWorkbook(appConfig, runStartedAt, "bgl-paper-metadata", workbook -> {
            CellStyle headerStyle = headerStyle(workbook);
            writeBglRunSummary(workbook, headerStyle, appConfig, results, runStartedAt, evaluationRange, filterStats);
            writeTaxonomyDistribution(workbook.createSheet("Taxonomy Distribution"), headerStyle, taxonomyRows(null, results, true));
            writeSignalCombination(workbook.createSheet("Signal Combination"), headerStyle, bglSignalCombinationRows(results, appConfig.bgl().minimumAlertShortSupport()));
            writeFamilySizeDistribution(workbook.createSheet("Semantic Family Size Distribution"), headerStyle, familySizeRows(null, results));
            writeSimilarityDistribution(workbook.createSheet("Similarity Distribution"), headerStyle, similarityRows(results));
            writeTemplateAggregateSheet(
                    workbook.createSheet("False Positives by Template"),
                    headerStyle,
                    falsePositiveRows(results)
            );
            writeTemplateAggregateSheet(
                    workbook.createSheet("False Negatives by Template"),
                    headerStyle,
                    falseNegativeRows(results)
            );
            writeCandidateFilterStats(workbook.createSheet("Candidate Filter Statistics"), headerStyle, filterStats, results.size());
            writeTopSemanticFamilies(workbook.createSheet("Top Semantic Families"), headerStyle, topSemanticFamilies(results));
            writeMethodNotes(workbook.createSheet("Method Notes"), headerStyle, List.of(
                    "Semantic family size is measured as event prevalence in the configured time window.",
                    "BGL metadata is reconstructed from the persisted ablation cache without OpenSearch.",
                    "Similarity distribution uses the cached max semantic similarity for each evaluated candidate.",
                    "Matched-template cardinality and intra-family template diversity require extra instrumentation."
            ));
        });
    }

    private Path writeWorkbook(
            AppConfig appConfig,
            Instant runStartedAt,
            String defaultPrefix,
            WorkbookWriter writer
    ) throws IOException {
        Files.createDirectories(Path.of(appConfig.report().outputDir()));
        String configuredPrefix = appConfig.report().filePrefix();
        String prefix = configuredPrefix.equals("semantic-frequency-paper-test") ? defaultPrefix : configuredPrefix;
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

    private static void writeSyntheticRunSummary(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            AppConfig appConfig,
            List<ScenarioResult> results,
            List<LogDocument> logs,
            Instant runStartedAt
    ) {
        Sheet sheet = workbook.createSheet("Run Summary");
        int row = 0;
        row = keyValue(sheet, row, "Run Timestamp UTC", runStartedAt.toString(), headerStyle);
        row = keyValue(sheet, row, "Dataset", "Synthetic", headerStyle);
        row = keyValue(sheet, row, "Embedding Provider", appConfig.embeddingProviderName(), headerStyle);
        row = keyValue(sheet, row, "Similarity Threshold", appConfig.experiment().similarityThreshold(), headerStyle);
        row = keyValue(sheet, row, "Short Window", appConfig.experiment().shortWindow().toString(), headerStyle);
        row = keyValue(sheet, row, "Baseline Window", appConfig.experiment().baselineWindow().toString(), headerStyle);
        row = keyValue(sheet, row, "Historical Logs", logs.size(), headerStyle);
        keyValue(sheet, row, "Scenario Count", results.size(), headerStyle);
        autosize(sheet, 2);
    }

    private static void writeBglRunSummary(
            XSSFWorkbook workbook,
            CellStyle headerStyle,
            AppConfig appConfig,
            List<PublicDatasetEvaluationResult> results,
            Instant runStartedAt,
            BglEvaluationWorkflow.EvaluationRange evaluationRange,
            BglMetadataAnalysisWorkflow.CandidateFilterStats filterStats
    ) {
        Sheet sheet = workbook.createSheet("Run Summary");
        int row = 0;
        row = keyValue(sheet, row, "Run Timestamp UTC", runStartedAt.toString(), headerStyle);
        row = keyValue(sheet, row, "Dataset", "BGL", headerStyle);
        row = keyValue(sheet, row, "Candidate Mode", appConfig.bgl().candidateMode(), headerStyle);
        row = keyValue(sheet, row, "Similarity Threshold", appConfig.experiment().similarityThreshold(), headerStyle);
        row = keyValue(sheet, row, "Evaluation Start", evaluationRange.start().toString(), headerStyle);
        row = keyValue(sheet, row, "Evaluation End", evaluationRange.end().toString(), headerStyle);
        row = keyValue(sheet, row, "Evaluation Rows", results.size(), headerStyle);
        row = keyValue(sheet, row, "Weighted Events", results.stream().mapToLong(PublicDatasetEvaluationResult::eventCount).sum(), headerStyle);
        row = keyValue(sheet, row, "Candidate Logs", filterStats.candidateLogs(), headerStyle);
        keyValue(sheet, row, "Strict Candidate Logs", filterStats.strictCandidateLogs(), headerStyle);
        autosize(sheet, 2);
    }

    private static List<TaxonomyRow> taxonomyRows(
            List<ScenarioResult> syntheticResults,
            List<PublicDatasetEvaluationResult> bglResults,
            boolean includeWeights
    ) {
        Map<AnomalyClass, Long> rowCounts = new EnumMap<>(AnomalyClass.class);
        Map<AnomalyClass, Long> weightedCounts = new EnumMap<>(AnomalyClass.class);
        for (AnomalyClass anomalyClass : List.of(
                AnomalyClass.NORMAL_BEHAVIOR,
                AnomalyClass.RARE_ANOMALY,
                AnomalyClass.SURGE_ANOMALY,
                AnomalyClass.CRITICAL_ANOMALY
        )) {
            rowCounts.put(anomalyClass, 0L);
            weightedCounts.put(anomalyClass, 0L);
        }
        if (syntheticResults != null) {
            for (ScenarioResult result : syntheticResults) {
                rowCounts.compute(result.hybrid().anomalyClass(), (ignored, count) -> count + 1);
            }
        }
        if (bglResults != null) {
            for (PublicDatasetEvaluationResult result : bglResults) {
                rowCounts.compute(result.hybridClass(), (ignored, count) -> count + 1);
                weightedCounts.compute(result.hybridClass(), (ignored, count) -> count + result.eventCount());
            }
        }
        List<TaxonomyRow> rows = new ArrayList<>();
        for (AnomalyClass anomalyClass : rowCounts.keySet()) {
            rows.add(new TaxonomyRow(anomalyClass.name(), rowCounts.get(anomalyClass), includeWeights ? weightedCounts.get(anomalyClass) : null));
        }
        return rows;
    }

    private static List<SignalCombinationRow> syntheticSignalCombinationRows(List<ScenarioResult> results) {
        Map<String, Long> counts = emptyCombinationCounts();
        for (ScenarioResult result : results) {
            String key = combinationKey(SyntheticMetadataWorkflow.exactSpike(result), SyntheticMetadataWorkflow.semanticSpike(result));
            counts.compute(key, (ignored, count) -> count + 1);
        }
        return counts.entrySet().stream()
                .map(entry -> new SignalCombinationRow(entry.getKey(), entry.getValue(), null))
                .toList();
    }

    private static List<SignalCombinationRow> bglSignalCombinationRows(
            List<PublicDatasetEvaluationResult> results,
            int minimumAlertShortSupport
    ) {
        Map<String, Long> rowCounts = emptyCombinationCounts();
        Map<String, Long> weightedCounts = emptyCombinationCounts();
        for (PublicDatasetEvaluationResult result : results) {
            boolean exact = BglMetadataAnalysisWorkflow.exactSpike(result.scenarioResult());
            boolean semantic = BglMetadataAnalysisWorkflow.semanticSpike(result.scenarioResult(), minimumAlertShortSupport);
            String key = combinationKey(exact, semantic);
            rowCounts.compute(key, (ignored, count) -> count + 1);
            weightedCounts.compute(key, (ignored, count) -> count + result.eventCount());
        }
        return rowCounts.entrySet().stream()
                .map(entry -> new SignalCombinationRow(entry.getKey(), entry.getValue(), weightedCounts.get(entry.getKey())))
                .toList();
    }

    private static Map<String, Long> emptyCombinationCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("Exact spike only", 0L);
        counts.put("Semantic spike only", 0L);
        counts.put("Both", 0L);
        counts.put("Neither", 0L);
        return counts;
    }

    private static String combinationKey(boolean exact, boolean semantic) {
        if (exact && semantic) {
            return "Both";
        }
        if (exact) {
            return "Exact spike only";
        }
        if (semantic) {
            return "Semantic spike only";
        }
        return "Neither";
    }

    private static List<DistributionRow> familySizeRows(
            List<ScenarioResult> syntheticResults,
            List<PublicDatasetEvaluationResult> bglResults
    ) {
        Map<String, Long> rowCounts = emptyBinCounts();
        Map<String, Long> weightedCounts = emptyBinCounts();
        if (syntheticResults != null) {
            for (ScenarioResult result : syntheticResults) {
                rowCounts.compute(binForFamilySize(result.temporal().shortCount()), (ignored, count) -> count + 1);
            }
        }
        if (bglResults != null) {
            for (PublicDatasetEvaluationResult result : bglResults) {
                String bin = binForFamilySize(result.scenarioResult().temporal().shortCount());
                rowCounts.compute(bin, (ignored, count) -> count + 1);
                weightedCounts.compute(bin, (ignored, count) -> count + result.eventCount());
            }
        }
        return rowCounts.entrySet().stream()
                .map(entry -> new DistributionRow(entry.getKey(), entry.getValue(), bglResults == null ? null : weightedCounts.get(entry.getKey())))
                .toList();
    }

    private static List<DistributionRow> similarityRows(List<?> results) {
        Map<String, Long> counts = emptySimilarityBins();
        for (Object result : results) {
            double similarity = result instanceof ScenarioResult scenarioResult
                    ? scenarioResult.semantic().semanticSimilarityScore()
                    : ((PublicDatasetEvaluationResult) result).scenarioResult().semantic().semanticSimilarityScore();
            counts.compute(binForSimilarity(similarity), (ignored, count) -> count + 1);
        }
        return counts.entrySet().stream()
                .map(entry -> new DistributionRow(entry.getKey(), entry.getValue(), null))
                .toList();
    }

    private static Map<String, Long> emptyBinCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("1", 0L);
        counts.put("2-5", 0L);
        counts.put("6-10", 0L);
        counts.put("11-20", 0L);
        counts.put(">20", 0L);
        return counts;
    }

    private static String binForFamilySize(int count) {
        if (count <= 1) {
            return "1";
        }
        if (count <= 5) {
            return "2-5";
        }
        if (count <= 10) {
            return "6-10";
        }
        if (count <= 20) {
            return "11-20";
        }
        return ">20";
    }

    private static Map<String, Long> emptySimilarityBins() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("0.70-0.75", 0L);
        counts.put("0.75-0.80", 0L);
        counts.put("0.80-0.85", 0L);
        counts.put("0.85-0.90", 0L);
        counts.put("0.90+", 0L);
        return counts;
    }

    private static String binForSimilarity(double similarity) {
        if (similarity < 0.75) {
            return "0.70-0.75";
        }
        if (similarity < 0.80) {
            return "0.75-0.80";
        }
        if (similarity < 0.85) {
            return "0.80-0.85";
        }
        if (similarity < 0.90) {
            return "0.85-0.90";
        }
        return "0.90+";
    }

    private static List<TemplateAggregateRow> falsePositiveRows(List<PublicDatasetEvaluationResult> results) {
        return aggregateTemplates(
                results,
                result -> !result.actualAnomaly() && PublicDatasetEvaluation.isPositiveForResult(result, EvaluationMethod.HYBRID_FRAMEWORK),
                result -> result.hybridClass().name()
        );
    }

    private static List<TemplateAggregateRow> falseNegativeRows(List<PublicDatasetEvaluationResult> results) {
        return aggregateTemplates(
                results,
                result -> result.actualAnomaly() && !PublicDatasetEvaluation.isPositiveForResult(result, EvaluationMethod.HYBRID_FRAMEWORK),
                PublicDatasetEvaluationResult::nativeLabel
        );
    }

    private static List<TemplateAggregateRow> aggregateTemplates(
            List<PublicDatasetEvaluationResult> results,
            java.util.function.Predicate<PublicDatasetEvaluationResult> filter,
            java.util.function.Function<PublicDatasetEvaluationResult, String> detail
    ) {
        Map<String, TemplateAggregate> grouped = new LinkedHashMap<>();
        for (PublicDatasetEvaluationResult result : results) {
            if (!filter.test(result)) {
                continue;
            }
            grouped.compute(result.scenarioResult().probe().pattern(), (ignored, aggregate) -> {
                TemplateAggregate updated = aggregate == null ? new TemplateAggregate(detail.apply(result), 0L) : aggregate;
                updated.count += result.eventCount();
                return updated;
            });
        }
        return grouped.entrySet().stream()
                .map(entry -> new TemplateAggregateRow(entry.getKey(), entry.getValue().count, entry.getValue().detail))
                .sorted(Comparator.comparingLong(TemplateAggregateRow::count).reversed())
                .limit(10)
                .toList();
    }

    private static List<TopSemanticFamilyRow> topSemanticFamilies(List<PublicDatasetEvaluationResult> results) {
        Map<String, Integer> maxFamilySize = new LinkedHashMap<>();
        for (PublicDatasetEvaluationResult result : results) {
            String pattern = result.scenarioResult().probe().pattern();
            maxFamilySize.compute(pattern, (ignored, existing) ->
                    Math.max(existing == null ? 0 : existing, result.scenarioResult().temporal().shortCount()));
        }
        return maxFamilySize.entrySet().stream()
                .map(entry -> new TopSemanticFamilyRow(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(TopSemanticFamilyRow::count).reversed())
                .limit(20)
                .toList();
    }

    private static void writeTaxonomyDistribution(Sheet sheet, CellStyle headerStyle, List<TaxonomyRow> rows) {
        boolean includeWeights = rows.stream().anyMatch(row -> row.weightedEvents() != null);
        if (includeWeights) {
            writeHeader(sheet.createRow(0), headerStyle, "Class", "Count", "Weighted Events");
        } else {
            writeHeader(sheet.createRow(0), headerStyle, "Class", "Count");
        }
        int rowIndex = 1;
        for (TaxonomyRow rowData : rows) {
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, rowData.label());
            write(row, 1, rowData.count());
            if (includeWeights) {
                write(row, 2, rowData.weightedEvents());
            }
        }
        autosize(sheet, includeWeights ? 3 : 2);
    }

    private static void writeSignalCombination(Sheet sheet, CellStyle headerStyle, List<SignalCombinationRow> rows) {
        boolean includeWeights = rows.stream().anyMatch(row -> row.weightedEvents() != null);
        if (includeWeights) {
            writeHeader(sheet.createRow(0), headerStyle, "Signal Combination", "Count", "Weighted Events");
        } else {
            writeHeader(sheet.createRow(0), headerStyle, "Signal Combination", "Count");
        }
        int rowIndex = 1;
        for (SignalCombinationRow rowData : rows) {
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, rowData.label());
            write(row, 1, rowData.count());
            if (includeWeights) {
                write(row, 2, rowData.weightedEvents());
            }
        }
        autosize(sheet, includeWeights ? 3 : 2);
    }

    private static void writeFamilySizeDistribution(Sheet sheet, CellStyle headerStyle, List<DistributionRow> rows) {
        boolean includeWeights = rows.stream().anyMatch(row -> row.weightedEvents() != null);
        if (includeWeights) {
            writeHeader(sheet.createRow(0), headerStyle, "Cluster Size Range", "Count", "Weighted Events");
        } else {
            writeHeader(sheet.createRow(0), headerStyle, "Cluster Size Range", "Count");
        }
        int rowIndex = 1;
        for (DistributionRow rowData : rows) {
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, rowData.label());
            write(row, 1, rowData.count());
            if (includeWeights) {
                write(row, 2, rowData.weightedEvents());
            }
        }
        autosize(sheet, includeWeights ? 3 : 2);
    }

    private static void writeSimilarityDistribution(Sheet sheet, CellStyle headerStyle, List<DistributionRow> rows) {
        writeHeader(sheet.createRow(0), headerStyle, "Similarity Range", "Count");
        int rowIndex = 1;
        for (DistributionRow rowData : rows) {
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, rowData.label());
            write(row, 1, rowData.count());
        }
        autosize(sheet, 2);
    }

    private static void writeSyntheticScenarioCoverage(
            Sheet sheet,
            CellStyle headerStyle,
            List<ScenarioResult> results,
            List<LogDocument> logs
    ) {
        writeHeader(sheet.createRow(0), headerStyle, "Scenario", "Coverage", "Semantic Family Size", "Hybrid Class");
        Map<String, Long> familyTotals = logs.stream()
                .collect(java.util.stream.Collectors.groupingBy(LogDocument::incidentFamily, java.util.stream.Collectors.counting()));
        int rowIndex = 1;
        for (ScenarioResult result : results) {
            long familyTotal = familyTotals.getOrDefault(result.probe().incidentFamily(), 0L);
            double coverage = familyTotal == 0 ? 0.0
                    : Math.min(1.0, (result.temporal().shortCount() + result.temporal().longCount()) / (double) familyTotal);
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, result.probe().name());
            write(row, 1, coverage);
            write(row, 2, result.temporal().shortCount());
            write(row, 3, result.hybrid().anomalyClass().name());
        }
        autosize(sheet, 4);
    }

    private static void writeTemplateAggregateSheet(Sheet sheet, CellStyle headerStyle, List<TemplateAggregateRow> rows) {
        String detailHeader = sheet.getSheetName().startsWith("False Positive") ? "Predicted Class" : "Native Label";
        writeHeader(sheet.createRow(0), headerStyle, "Template", "Count", detailHeader);
        int rowIndex = 1;
        for (TemplateAggregateRow rowData : rows) {
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, rowData.template());
            write(row, 1, rowData.count());
            write(row, 2, rowData.detail());
        }
        autosize(sheet, 3);
    }

    private static void writeCandidateFilterStats(
            Sheet sheet,
            CellStyle headerStyle,
            BglMetadataAnalysisWorkflow.CandidateFilterStats filterStats,
            int evaluatedRows
    ) {
        writeHeader(sheet.createRow(0), headerStyle, "Stage", "Events");
        int row = 1;
        row = writeStage(sheet, row, "Total logs", filterStats.totalLogs());
        row = writeStage(sheet, row, "Candidate logs", filterStats.candidateLogs());
        row = writeStage(sheet, row, "Strict candidates", filterStats.strictCandidateLogs());
        writeStage(sheet, row, "Evaluated rows", evaluatedRows);
        autosize(sheet, 2);
    }

    private static int writeStage(Sheet sheet, int rowIndex, String label, long value) {
        Row row = sheet.createRow(rowIndex);
        write(row, 0, label);
        write(row, 1, value);
        return rowIndex + 1;
    }

    private static void writeTopSemanticFamilies(Sheet sheet, CellStyle headerStyle, List<TopSemanticFamilyRow> rows) {
        writeHeader(sheet.createRow(0), headerStyle, "Family", "Count");
        int rowIndex = 1;
        for (TopSemanticFamilyRow rowData : rows) {
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, rowData.family());
            write(row, 1, rowData.count());
        }
        autosize(sheet, 2);
    }

    private static void writeMethodNotes(Sheet sheet, CellStyle headerStyle, List<String> notes) {
        writeHeader(sheet.createRow(0), headerStyle, "Notes");
        for (int i = 0; i < notes.size(); i++) {
            write(sheet.createRow(i + 1), 0, notes.get(i));
        }
        autosize(sheet, 1);
    }

    private static int keyValue(Sheet sheet, int rowIndex, String key, Object value, CellStyle headerStyle) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(key);
        row.getCell(0).setCellStyle(headerStyle);
        write(row, 1, value);
        return rowIndex + 1;
    }

    private static void writeHeader(Row row, CellStyle headerStyle, String... values) {
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
            row.getCell(i).setCellStyle(headerStyle);
        }
    }

    private static void write(Row row, int cellIndex, Object value) {
        if (value == null) {
            row.createCell(cellIndex).setBlank();
            return;
        }
        if (value instanceof Number number) {
            row.createCell(cellIndex).setCellValue(number.doubleValue());
            return;
        }
        row.createCell(cellIndex).setCellValue(String.valueOf(value));
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
            sheet.autoSizeColumn(column);
        }
    }

    private interface WorkbookWriter {
        void write(XSSFWorkbook workbook) throws IOException;
    }

    private record TaxonomyRow(String label, long count, Long weightedEvents) {
    }

    private record SignalCombinationRow(String label, long count, Long weightedEvents) {
    }

    private record DistributionRow(String label, long count, Long weightedEvents) {
    }

    private record TemplateAggregateRow(String template, long count, String detail) {
    }

    private record TopSemanticFamilyRow(String family, int count) {
    }

    private static final class TemplateAggregate {
        private final String detail;
        private long count;

        private TemplateAggregate(String detail, long count) {
            this.detail = detail;
            this.count = count;
        }
    }
}
