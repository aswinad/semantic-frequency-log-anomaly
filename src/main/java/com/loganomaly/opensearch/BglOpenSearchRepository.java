package com.loganomaly.opensearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loganomaly.config.AppConfig;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.nio.entity.NStringEntity;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.ResponseException;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class BglOpenSearchRepository implements Closeable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int SOCKET_TIMEOUT_MILLIS = 180_000;
    private static final int MAX_SEMANTIC_TEMPLATE_MATCHES = 10_000;

    private final RestClient client;
    private final String templateIndexName;
    private final String eventIndexName;

    public BglOpenSearchRepository(RestClient client, String baseIndexName) {
        this.client = client;
        this.templateIndexName = baseIndexName + "-templates";
        this.eventIndexName = baseIndexName + "-events";
    }

    public static BglOpenSearchRepository fromConfig(AppConfig config, String baseIndexName) {
        return new BglOpenSearchRepository(
                buildClient(
                        config.openSearchUrl(),
                        config.openSearchUsername().orElse(null),
                        config.openSearchPassword().orElse(null)
                ),
                baseIndexName
        );
    }

    private static RestClient buildClient(String url, String username, String password) {
        URI uri = URI.create(url);
        RestClientBuilder builder = RestClient.builder(new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme()));
        builder.setRequestConfigCallback(requestConfigBuilder -> requestConfigBuilder
                .setConnectTimeout(CONNECT_TIMEOUT_MILLIS)
                .setSocketTimeout(SOCKET_TIMEOUT_MILLIS));
        if (username != null && password != null) {
            CredentialsProvider credentials = new BasicCredentialsProvider();
            credentials.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));
            builder.setHttpClientConfigCallback((HttpAsyncClientBuilder httpClientBuilder) ->
                    httpClientBuilder.setDefaultCredentialsProvider(credentials));
        }
        return builder.build();
    }

    public String templateIndexName() {
        return templateIndexName;
    }

    public String eventIndexName() {
        return eventIndexName;
    }

    public BglIndexPreparation prepareIndexes(int dimensions, boolean recreate) throws IOException {
        if (recreate) {
            deleteIndexIfExists(templateIndexName);
            deleteIndexIfExists(eventIndexName);
            createTemplateIndex(dimensions);
            createEventIndex();
            return new BglIndexPreparation("recreated", validateTemplateIndex(dimensions), validateEventIndex());
        }

        boolean created = false;
        if (!indexExists(templateIndexName)) {
            createTemplateIndex(dimensions);
            created = true;
        }
        if (!indexExists(eventIndexName)) {
            createEventIndex();
            created = true;
        }
        return new BglIndexPreparation(created ? "created" : "reused", validateTemplateIndex(dimensions), validateEventIndex());
    }

    public boolean indexesExist() throws IOException {
        return indexExists(templateIndexName) && indexExists(eventIndexName);
    }

    public BglIndexValidation validateTemplateIndex(int expectedDimensions) throws IOException {
        try {
            Response response = client.performRequest(new Request("GET", "/" + templateIndexName + "/_mapping"));
            JsonNode root = MAPPER.readTree(response.getEntity().getContent());
            JsonNode properties = mappingProperties(root, templateIndexName);
            String embeddingType = properties.path("embedding").path("type").asText("");
            int embeddingDimension = properties.path("embedding").path("dimension").asInt(-1);
            String templateIdType = properties.path("templateId").path("type").asText("");
            if (!"knn_vector".equals(embeddingType)) {
                return new BglIndexValidation(false, "Expected template embedding.type=knn_vector but found '%s'".formatted(emptyToMissing(embeddingType)));
            }
            if (embeddingDimension != expectedDimensions) {
                return new BglIndexValidation(false, "Expected template embedding.dimension=%d but found %d".formatted(expectedDimensions, embeddingDimension));
            }
            if (!"keyword".equals(templateIdType)) {
                return new BglIndexValidation(false, "Expected templateId.type=keyword but found '%s'".formatted(emptyToMissing(templateIdType)));
            }
            return new BglIndexValidation(true, "OK");
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() == 404) {
                return new BglIndexValidation(false, "Template index not found: " + templateIndexName);
            }
            throw e;
        }
    }

    public BglIndexValidation validateEventIndex() throws IOException {
        try {
            Response response = client.performRequest(new Request("GET", "/" + eventIndexName + "/_mapping"));
            JsonNode root = MAPPER.readTree(response.getEntity().getContent());
            JsonNode properties = mappingProperties(root, eventIndexName);
            String templateIdType = properties.path("templateId").path("type").asText("");
            String timestampType = properties.path("timestamp").path("type").asText("");
            String embeddingType = properties.path("embedding").path("type").asText("");
            if (!"keyword".equals(templateIdType)) {
                return new BglIndexValidation(false, "Expected event templateId.type=keyword but found '%s'".formatted(emptyToMissing(templateIdType)));
            }
            if (!"date".equals(timestampType)) {
                return new BglIndexValidation(false, "Expected event timestamp.type=date but found '%s'".formatted(emptyToMissing(timestampType)));
            }
            if (!embeddingType.isBlank()) {
                return new BglIndexValidation(false, "Expected event index to omit embedding but found embedding.type=%s".formatted(embeddingType));
            }
            return new BglIndexValidation(true, "OK");
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() == 404) {
                return new BglIndexValidation(false, "Event index not found: " + eventIndexName);
            }
            throw e;
        }
    }

    private void createTemplateIndex(int dimensions) throws IOException {
        Map<String, Object> body = Map.of(
                "settings", Map.of("index", Map.of("knn", true)),
                "mappings", Map.of("properties", Map.of(
                        "templateId", Map.of("type", "keyword"),
                        "pattern", Map.of("type", "keyword"),
                        "service", Map.of("type", "keyword"),
                        "incidentFamily", Map.of("type", "keyword"),
                        "nativeLabel", Map.of("type", "keyword"),
                        "message", Map.of("type", "text"),
                        "embedding", Map.of("type", "knn_vector", "dimension", dimensions)
                ))
        );
        request("PUT", "/" + templateIndexName, body);
    }

    private void createEventIndex() throws IOException {
        Map<String, Object> body = Map.of(
                "mappings", Map.of("properties", Map.of(
                        "timestamp", Map.of("type", "date"),
                        "originalTimestamp", Map.of("type", "date"),
                        "templateId", Map.of("type", "keyword"),
                        "pattern", Map.of("type", "keyword"),
                        "service", Map.of("type", "keyword"),
                        "incidentFamily", Map.of("type", "keyword"),
                        "nativeLabel", Map.of("type", "keyword"),
                        "scenario", Map.of("type", "keyword"),
                        "message", Map.of("type", "text")
                ))
        );
        request("PUT", "/" + eventIndexName, body);
    }

    public void bulkIndexTemplates(List<BglTemplateDocument> documents, int batchSize) throws IOException {
        int effectiveBatchSize = Math.max(1, batchSize);
        for (int from = 0; from < documents.size(); from += effectiveBatchSize) {
            int to = Math.min(documents.size(), from + effectiveBatchSize);
            StringBuilder body = new StringBuilder();
            for (BglTemplateDocument document : documents.subList(from, to)) {
                body.append(MAPPER.writeValueAsString(Map.of("index", Map.of("_id", document.templateId())))).append('\n');
                body.append(MAPPER.writeValueAsString(document.source())).append('\n');
            }
            bulk(templateIndexName, body);
        }
    }

    public void bulkIndexEvents(List<BglEventDocument> documents, int batchSize) throws IOException {
        int effectiveBatchSize = Math.max(1, batchSize);
        for (int from = 0; from < documents.size(); from += effectiveBatchSize) {
            int to = Math.min(documents.size(), from + effectiveBatchSize);
            StringBuilder body = new StringBuilder();
            for (BglEventDocument document : documents.subList(from, to)) {
                body.append(MAPPER.writeValueAsString(Map.of("index", Map.of("_id", document.id())))).append('\n');
                body.append(MAPPER.writeValueAsString(document.source())).append('\n');
            }
            bulk(eventIndexName, body);
        }
    }

    public List<KnnNeighbor> knnTemplates(float[] queryVector, int k) throws IOException {
        Map<String, Object> body = topKNeighborSearchBody(queryVector, k);
        return searchTemplateNeighbors(body);
    }

    public long countEventsForTemplateBetween(String templateId, Instant fromInclusive, Instant toExclusive) throws IOException {
        return countEvents(Map.of("bool", Map.of("filter", List.of(
                Map.of("term", Map.of("templateId", templateId)),
                timeRange(fromInclusive, toExclusive)
        ))));
    }

    public long countEventsForTemplatesBetween(List<String> templateIds, Instant fromInclusive, Instant toExclusive) throws IOException {
        if (templateIds.isEmpty()) {
            return 0L;
        }
        return countEvents(Map.of("bool", Map.of("filter", List.of(
                Map.of("terms", Map.of("templateId", templateIds)),
                timeRange(fromInclusive, toExclusive)
        ))));
    }

    public long countSemanticEventsBetween(
            float[] queryVector,
            Instant fromInclusive,
            Instant toExclusive,
            double similarityThreshold
    ) throws IOException {
        return countSemanticEventsBetween(queryVector, fromInclusive, toExclusive, similarityThreshold, "bgl-semantic");
    }

    public long countSemanticEventsBetween(
            float[] queryVector,
            Instant fromInclusive,
            Instant toExclusive,
            double similarityThreshold,
            String lookupContextLabel
    ) throws IOException {
        List<String> templateIds = semanticTemplateIds(queryVector, similarityThreshold, lookupContextLabel);
        return countEventsForTemplatesBetween(templateIds, fromInclusive, toExclusive);
    }

    public long countAllEvents() throws IOException {
        return countEvents(Map.of("match_all", Map.of()));
    }

    public long countEventsByNativeLabel(String nativeLabel) throws IOException {
        return countEvents(Map.of("term", Map.of("nativeLabel", nativeLabel)));
    }

    public long countAllTemplates() throws IOException {
        Response response = request("GET", "/" + templateIndexName + "/_count", Map.of("query", Map.of("match_all", Map.of())));
        return parseTotalCount(response);
    }

    public void refresh() throws IOException {
        client.performRequest(new Request("POST", "/" + templateIndexName + "/_refresh"));
        client.performRequest(new Request("POST", "/" + eventIndexName + "/_refresh"));
    }

    public void deleteIndexesIfExist() throws IOException {
        deleteIndexIfExists(templateIndexName);
        deleteIndexIfExists(eventIndexName);
    }

    List<String> semanticTemplateIds(float[] queryVector, double similarityThreshold, String lookupContextLabel) throws IOException {
        Map<String, Object> body = semanticTemplateIdSearchBody(queryVector, similarityThreshold);
        return searchTemplateNeighborIds(body, lookupContextLabel);
    }

    private List<KnnNeighbor> searchTemplateNeighbors(Map<String, Object> body) throws IOException {
        Response response = request("GET", "/" + templateIndexName + "/_search", body);
        JsonNode hits = MAPPER.readTree(response.getEntity().getContent()).path("hits").path("hits");
        float[] queryVector = queryVectorFromBody(body);
        List<KnnNeighbor> neighbors = new ArrayList<>();
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            float[] embedding = parseEmbedding(source.path("embedding"));
            neighbors.add(new KnnNeighbor(
                    hit.path("_id").asText(),
                    "",
                    source.path("service").asText(),
                    source.path("pattern").asText(),
                    source.path("incidentFamily").asText("unknown"),
                    "bgl-template",
                    source.path("message").asText(),
                    hit.path("_score").asDouble(),
                    cosineSimilarity(queryVector, embedding)
            ));
        }
        return neighbors;
    }

    private List<String> searchTemplateNeighborIds(Map<String, Object> body, String lookupContextLabel) throws IOException {
        Response response = request("GET", "/" + templateIndexName + "/_search", body);
        JsonNode hits = MAPPER.readTree(response.getEntity().getContent()).path("hits").path("hits");
        List<String> templateIds = new ArrayList<>();
        for (JsonNode hit : hits) {
            templateIds.add(hit.path("_id").asText());
        }
        logSemanticLookupGuardrail(templateIds.size(), lookupContextLabel);
        return templateIds;
    }

    static Map<String, Object> topKNeighborSearchBody(float[] queryVector, int k) {
        return Map.of(
                "size", k,
                "query", semanticSimilarityQuery(queryVector)
        );
    }

    static Map<String, Object> semanticTemplateIdSearchBody(float[] queryVector, double similarityThreshold) {
        return Map.of(
                "size", MAX_SEMANTIC_TEMPLATE_MATCHES,
                "_source", false,
                "min_score", similarityThreshold + 1.0,
                "query", semanticSimilarityQuery(queryVector)
        );
    }

    private static Map<String, Object> semanticSimilarityQuery(float[] queryVector) {
        return Map.of("script_score", Map.of(
                "query", Map.of("match_all", Map.of()),
                "script", Map.of(
                        "source", "cosineSimilarity(params.queryVector, doc['embedding']) + 1.0",
                        "params", Map.of("queryVector", queryVector)
                )
        ));
    }

    private static void logSemanticLookupGuardrail(int templateCount, String lookupContextLabel) {
        if (templateCount >= MAX_SEMANTIC_TEMPLATE_MATCHES) {
            System.out.printf("[%s] semantic template lookup hit the configured cap of %,d template ids; semantic counts may be truncated%n",
                    lookupContextLabel,
                    MAX_SEMANTIC_TEMPLATE_MATCHES);
            return;
        }
        int warningThreshold = Math.max(1, (int) Math.floor(MAX_SEMANTIC_TEMPLATE_MATCHES * 0.9));
        if (templateCount >= warningThreshold) {
            System.out.printf("[%s] semantic template lookup is near the configured cap: %,d/%,d template ids%n",
                    lookupContextLabel,
                    templateCount,
                    MAX_SEMANTIC_TEMPLATE_MATCHES);
        }
    }

    @SuppressWarnings("unchecked")
    private static float[] queryVectorFromBody(Map<String, Object> body) {
        Map<String, Object> query = (Map<String, Object>) body.get("query");
        Map<String, Object> scriptScore = (Map<String, Object>) query.get("script_score");
        Map<String, Object> script = (Map<String, Object>) scriptScore.get("script");
        Map<String, Object> params = (Map<String, Object>) script.get("params");
        Object vector = params.get("queryVector");
        if (vector instanceof float[] floats) {
            return floats;
        }
        return new float[0];
    }

    private long countEvents(Map<String, Object> query) throws IOException {
        Response response = request("GET", "/" + eventIndexName + "/_count", Map.of("query", query));
        return parseTotalCount(response);
    }

    private long parseTotalCount(Response response) throws IOException {
        return MAPPER.readTree(response.getEntity().getContent()).path("count").asLong();
    }

    private static Map<String, Object> timeRange(Instant fromInclusive, Instant toExclusive) {
        return Map.of("range", Map.of("timestamp", Map.of(
                "gte", fromInclusive.toString(),
                "lt", toExclusive.toString()
        )));
    }

    private boolean indexExists(String indexName) throws IOException {
        try {
            client.performRequest(new Request("HEAD", "/" + indexName));
            return true;
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    private void deleteIndexIfExists(String indexName) throws IOException {
        Request request = new Request("DELETE", "/" + indexName);
        request.addParameter("ignore_unavailable", "true");
        client.performRequest(request);
    }

    private Response request(String method, String endpoint, Map<String, Object> body) throws IOException {
        Request request = new Request(method, endpoint);
        request.setEntity(new NStringEntity(MAPPER.writeValueAsString(body), ContentType.APPLICATION_JSON));
        return client.performRequest(request);
    }

    private void bulk(String indexName, StringBuilder body) throws IOException {
        if (body.isEmpty()) {
            return;
        }
        Request request = new Request("POST", "/" + indexName + "/_bulk");
        request.setEntity(new NStringEntity(body.toString(), ContentType.create("application/x-ndjson", StandardCharsets.UTF_8)));
        Response response = client.performRequest(request);
        JsonNode root = MAPPER.readTree(response.getEntity().getContent());
        if (root.path("errors").asBoolean(false)) {
            throw new IOException("OpenSearch bulk index request completed with errors for " + indexName);
        }
    }

    private static JsonNode mappingProperties(JsonNode root, String indexName) {
        JsonNode indexNode = root.path(indexName);
        if (indexNode.isMissingNode() && root.fields().hasNext()) {
            indexNode = root.fields().next().getValue();
        }
        return indexNode.path("mappings").path("properties");
    }

    private static float[] parseEmbedding(JsonNode embeddingNode) {
        float[] embedding = new float[embeddingNode.size()];
        for (int i = 0; i < embeddingNode.size(); i++) {
            embedding[i] = (float) embeddingNode.get(i).asDouble();
        }
        return embedding;
    }

    private static double cosineSimilarity(float[] left, float[] right) {
        double dot = 0.0;
        double leftMagnitude = 0.0;
        double rightMagnitude = 0.0;
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            dot += left[i] * right[i];
            leftMagnitude += left[i] * left[i];
            rightMagnitude += right[i] * right[i];
        }
        if (leftMagnitude == 0.0 || rightMagnitude == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftMagnitude) * Math.sqrt(rightMagnitude));
    }

    private static String emptyToMissing(String value) {
        return value == null || value.isBlank() ? "<missing>" : value;
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

    public record BglIndexValidation(boolean valid, String message) {
    }

    public record BglIndexPreparation(String action, BglIndexValidation templateValidation, BglIndexValidation eventValidation) {
        public boolean valid() {
            return templateValidation.valid() && eventValidation.valid();
        }

        public String message() {
            if (!templateValidation.valid()) {
                return templateValidation.message();
            }
            if (!eventValidation.valid()) {
                return eventValidation.message();
            }
            return "OK";
        }
    }
}
