package com.loganomaly.app;

import com.loganomaly.config.AppConfig;
import com.loganomaly.config.BglConfig;
import com.loganomaly.embedding.EmbeddingCache;
import com.loganomaly.embedding.EmbeddingProvider;
import com.loganomaly.embedding.OpenAIEmbeddingProvider;
import com.loganomaly.loghub.BglLogHubDataset;
import com.loganomaly.loghub.BglLogRecord;
import com.loganomaly.opensearch.BglEventDocument;
import com.loganomaly.opensearch.BglOpenSearchRepository;
import com.loganomaly.opensearch.BglTemplateDocument;
import com.loganomaly.opensearch.BglTemplateId;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BglIndexingWorkflow {
    private final AppConfig appConfig;
    private final EmbeddingProvider embeddingProvider;

    public BglIndexingWorkflow(AppConfig appConfig, EmbeddingProvider embeddingProvider) {
        this.appConfig = appConfig;
        this.embeddingProvider = embeddingProvider;
    }

    public void run() throws IOException {
        BglConfig config = appConfig.bgl();
        BglLogHubDataset dataset = new BglLogHubDataset(config.loghubFile());
        long totalLines = dataset.countLines();
        System.out.printf("Loaded %,d BGL LogHub records from %s%n", totalLines, config.loghubFile());

        EmbeddingCache cache = new EmbeddingCache(
                config.embeddingCache(),
                embeddingProvider.name(),
                embeddingProvider.dimensions()
        );
        cache.load();
        embedMissingTemplates(dataset, cache, config.batchSize());

        try (BglOpenSearchRepository repository = BglOpenSearchRepository.fromConfig(appConfig, config.indexName())) {
            BglOpenSearchRepository.BglIndexPreparation indexPreparation =
                    repository.prepareIndexes(embeddingProvider.dimensions(), config.recreateIndex());
            if (!indexPreparation.valid()) {
                throw new IllegalStateException(
                        "BGL indexes derived from '%s' have invalid mapping: %s. Rerun with BGL_RECREATE_INDEX=true."
                                .formatted(config.indexName(), indexPreparation.message())
                );
            }
            System.out.printf(
                    "BGL indexes '%s' and '%s' %s%n",
                    repository.templateIndexName(),
                    repository.eventIndexName(),
                    indexPreparation.action()
            );
            bulkIndexTemplatesWithProgress(repository, dataset, cache, config.indexBatchSize());
            bulkIndexEventsWithProgress(repository, dataset, config.indexBatchSize());
            repository.refresh();
            long totalTemplates = repository.countAllTemplates();
            long totalEvents = repository.countAllEvents();
            long anomalyEvents = totalEvents - repository.countEventsByNativeLabel("-");
            System.out.printf("Indexed %,d BGL templates into '%s'%n", totalTemplates, repository.templateIndexName());
            System.out.printf("Indexed %,d BGL events into '%s'%n", totalEvents, repository.eventIndexName());
            System.out.printf("Event count: %,d documents, anomaly events: %,d%n", totalEvents, anomalyEvents);
        }
    }

    private void embedMissingTemplates(BglLogHubDataset dataset, EmbeddingCache cache, int batchSize) throws IOException {
        Set<String> missingTemplates = new LinkedHashSet<>();
        dataset.forEachRecord(record -> {
            if (cache.get(record.pattern()).isEmpty()) {
                missingTemplates.add(record.pattern());
            }
        });
        System.out.printf("Embedding cache missing %,d unique BGL templates%n", missingTemplates.size());
        if (missingTemplates.isEmpty()) {
            return;
        }

        List<String> templates = new ArrayList<>(missingTemplates);
        int effectiveBatchSize = Math.max(1, batchSize);
        for (int from = 0; from < templates.size(); from += effectiveBatchSize) {
            int to = Math.min(templates.size(), from + effectiveBatchSize);
            List<String> batch = templates.subList(from, to);
            List<float[]> embeddings = embedBatch(batch);
            Map<String, float[]> newEmbeddings = new LinkedHashMap<>();
            for (int i = 0; i < batch.size(); i++) {
                newEmbeddings.put(batch.get(i), embeddings.get(i));
            }
            cache.putAll(newEmbeddings);
            System.out.printf("Cached embeddings %,d/%,d%n", to, templates.size());
        }
    }

    private List<float[]> embedBatch(List<String> batch) {
        if (embeddingProvider instanceof OpenAIEmbeddingProvider openAIEmbeddingProvider) {
            return openAIEmbeddingProvider.embedBatch(batch);
        }
        return batch.stream().map(embeddingProvider::embed).toList();
    }

    private static void bulkIndexTemplatesWithProgress(
            BglOpenSearchRepository repository,
            BglLogHubDataset dataset,
            EmbeddingCache cache,
            int batchSize
    ) throws IOException {
        Map<String, BglTemplateDocument> templates = new LinkedHashMap<>();
        dataset.forEachRecord(record -> {
            String templateId = BglTemplateId.fromPattern(record.pattern());
            templates.computeIfAbsent(templateId, ignored -> new BglTemplateDocument(
                    templateId,
                    record.pattern(),
                    record.service(),
                    record.anomaly() ? "bgl-anomaly" : "bgl-normal",
                    record.rawLabel(),
                    record.rawMessage(),
                    cache.get(record.pattern()).orElseThrow(() ->
                            new IllegalStateException("Missing cached embedding for " + record.pattern()))
            ));
        });
        List<BglTemplateDocument> documents = new ArrayList<>(templates.values());
        System.out.printf("Indexing %,d unique BGL templates with OpenSearch bulk batch size %,d%n", documents.size(), Math.max(1, batchSize));
        repository.bulkIndexTemplates(documents, batchSize);
        System.out.printf("Indexed %,d BGL templates%n", documents.size());
    }

    private static void bulkIndexEventsWithProgress(
            BglOpenSearchRepository repository,
            BglLogHubDataset dataset,
            int batchSize
    ) throws IOException {
        int effectiveBatchSize = Math.max(1, batchSize);
        System.out.printf("Indexing BGL events with OpenSearch bulk batch size %,d%n", effectiveBatchSize);
        List<BglEventDocument> batch = new ArrayList<>(effectiveBatchSize);
        final long[] indexed = {0L};
        dataset.forEachRecord(record -> {
            batch.add(new BglEventDocument(
                    "bgl-" + record.lineNumber(),
                    record.timestamp(),
                    record.timestamp(),
                    BglTemplateId.fromPattern(record.pattern()),
                    record.pattern(),
                    record.service(),
                    record.anomaly() ? "bgl-anomaly" : "bgl-normal",
                    record.rawLabel(),
                    dataset.file().getFileName().toString(),
                    record.rawMessage()
            ));
            if (batch.size() >= effectiveBatchSize) {
                flushEventBatch(repository, batch, effectiveBatchSize);
                indexed[0] += batch.size();
                System.out.printf("Indexed %,d BGL events%n", indexed[0]);
                batch.clear();
            }
        });
        if (!batch.isEmpty()) {
            flushEventBatch(repository, batch, effectiveBatchSize);
            indexed[0] += batch.size();
            System.out.printf("Indexed %,d BGL events%n", indexed[0]);
        }
    }

    private static void flushEventBatch(
            BglOpenSearchRepository repository,
            List<BglEventDocument> documents,
            int batchSize
    ) {
        try {
            bulkIndexEventRange(repository, documents, 0, documents.size(), batchSize);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to bulk index BGL event batch", e);
        }
    }

    private static void bulkIndexEventRange(
            BglOpenSearchRepository repository,
            List<BglEventDocument> documents,
            int from,
            int to,
            int batchSize
    ) throws IOException {
        try {
            repository.bulkIndexEvents(documents.subList(from, to), batchSize);
        } catch (IOException e) {
            if (!isRetryableTimeout(e) || (to - from) <= 100) {
                throw e;
            }
            int midpoint = from + ((to - from) / 2);
            System.out.printf(
                    "Bulk request slowed down for BGL events %,d-%,d. Retrying in smaller chunks (%d -> %d + %d)%n",
                    from + 1,
                    to,
                    to - from,
                    midpoint - from,
                    to - midpoint
            );
            bulkIndexEventRange(repository, documents, from, midpoint, Math.max(1, batchSize / 2));
            bulkIndexEventRange(repository, documents, midpoint, to, Math.max(1, batchSize / 2));
        }
    }

    private static boolean isRetryableTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
