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

public final class OpenSearchLogVectorRepository implements Closeable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int SOCKET_TIMEOUT_MILLIS = 180_000;

    private final RestClient client;
    private final String indexName;

    public OpenSearchLogVectorRepository(RestClient client, String indexName) {
        this.client = client;
        this.indexName = indexName;
    }

    public static OpenSearchLogVectorRepository fromEnvironment(String indexName) {
        AppConfig config = AppConfig.load();
        return new OpenSearchLogVectorRepository(
                buildClient(
                        config.openSearchUrl(),
                        config.openSearchUsername().orElse(null),
                        config.openSearchPassword().orElse(null)
                ),
                indexName
        );
    }

    public static OpenSearchLogVectorRepository fromConfig(AppConfig config) {
        return fromConfig(config, config.openSearchIndex());
    }

    public static OpenSearchLogVectorRepository fromConfig(AppConfig config, String indexName) {
        return new OpenSearchLogVectorRepository(
                buildClient(
                        config.openSearchUrl(),
                        config.openSearchUsername().orElse(null),
                        config.openSearchPassword().orElse(null)
                ),
                indexName
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

    public void recreateIndex(int dimensions) throws IOException {
        deleteIndexIfExists();
        createIndex(dimensions);
    }

    public void createIndexIfMissing(int dimensions) throws IOException {
        if (!indexExists()) {
            createIndex(dimensions);
        }
    }

    public boolean indexExists() throws IOException {
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

    public VectorIndexValidation validateVectorIndex(int expectedDimensions) throws IOException {
        try {
            Response response = client.performRequest(new Request("GET", "/" + indexName + "/_mapping"));
            JsonNode root = MAPPER.readTree(response.getEntity().getContent());
            return validateVectorIndexMapping(root, indexName, expectedDimensions);
        } catch (ResponseException e) {
            if (e.getResponse().getStatusLine().getStatusCode() == 404) {
                return new VectorIndexValidation(false, "", -1, "Index not found: " + indexName);
            }
            throw e;
        }
    }

    public VectorIndexPreparation prepareVectorIndex(int expectedDimensions, boolean recreate) throws IOException {
        if (recreate) {
            recreateIndex(expectedDimensions);
            return new VectorIndexPreparation("recreated", validateVectorIndex(expectedDimensions));
        }
        if (!indexExists()) {
            createIndex(expectedDimensions);
            return new VectorIndexPreparation("created", validateVectorIndex(expectedDimensions));
        }
        VectorIndexValidation validation = validateVectorIndex(expectedDimensions);
        if (validation.indexMissing()) {
            createIndex(expectedDimensions);
            return new VectorIndexPreparation("created", validateVectorIndex(expectedDimensions));
        }
        return new VectorIndexPreparation(validation.valid() ? "reused" : "invalid", validation);
    }

    private void createIndex(int dimensions) throws IOException {
        Map<String, Object> body = Map.of(
                "settings", Map.of("index", Map.of("knn", true)),
                "mappings", Map.of("properties", Map.of(
                        "service", Map.of("type", "keyword"),
                        "pattern", Map.of("type", "keyword"),
                        "incidentFamily", Map.of("type", "keyword"),
                        "nativeLabel", Map.of("type", "keyword"),
                        "scenario", Map.of("type", "keyword"),
                        "timestamp", Map.of("type", "date"),
                        "originalTimestamp", Map.of("type", "date"),
                        "message", Map.of("type", "text"),
                        "embedding", Map.of(
                                "type", "knn_vector",
                                "dimension", dimensions
                        )
                ))
        );
        request("PUT", "/" + indexName, body);
    }

    public void deleteIndexIfExists() throws IOException {
        Request request = new Request("DELETE", "/" + indexName);
        request.addParameter("ignore_unavailable", "true");
        client.performRequest(request);
    }

    public void index(String id, String service, String message, float[] embedding) throws IOException {
        index(new LogDocument(id, Instant.now(), service, "unknown", "unknown", "unknown", "manual", message, embedding));
    }

    public void index(LogDocument document) throws IOException {
        Map<String, Object> body = Map.of(
                "timestamp", document.timestamp().toString(),
                "originalTimestamp", document.originalTimestamp().toString(),
                "service", document.service(),
                "pattern", document.pattern(),
                "incidentFamily", document.incidentFamily(),
                "nativeLabel", document.nativeLabel(),
                "scenario", document.scenario(),
                "message", document.message(),
                "embedding", document.embedding()
        );
        request("PUT", "/" + indexName + "/_doc/" + document.id(), body);
    }

    public void indexAll(List<LogDocument> documents) throws IOException {
        for (LogDocument document : documents) {
            index(document);
        }
    }

    public void bulkIndex(List<LogDocument> documents, int batchSize) throws IOException {
        if (documents.isEmpty()) {
            return;
        }
        int effectiveBatchSize = Math.max(1, batchSize);
        for (int from = 0; from < documents.size(); from += effectiveBatchSize) {
            int to = Math.min(documents.size(), from + effectiveBatchSize);
            bulkIndexBatch(documents.subList(from, to));
        }
    }

    public long countAll() throws IOException {
        Response response = request("GET", "/" + indexName + "/_count", Map.of("query", Map.of("match_all", Map.of())));
        return MAPPER.readTree(response.getEntity().getContent()).path("count").asLong();
    }

    public long countIncidentFamily(String incidentFamily) throws IOException {
        Map<String, Object> body = Map.of(
                "query", Map.of("term", Map.of("incidentFamily", incidentFamily))
        );
        Response response = request("GET", "/" + indexName + "/_count", body);
        return MAPPER.readTree(response.getEntity().getContent()).path("count").asLong();
    }

    public long countNativeLabel(String nativeLabel) throws IOException {
        Map<String, Object> body = Map.of(
                "query", Map.of("term", Map.of("nativeLabel", nativeLabel))
        );
        Response response = request("GET", "/" + indexName + "/_count", body);
        return MAPPER.readTree(response.getEntity().getContent()).path("count").asLong();
    }

    public void refresh() throws IOException {
        request("POST", "/" + indexName + "/_refresh", Map.of());
    }

    public List<String> knnMessages(float[] queryVector, int k) throws IOException {
        return knn(queryVector, k).stream()
                .map(KnnNeighbor::message)
                .toList();
    }

    public List<KnnNeighbor> knn(float[] queryVector, int k) throws IOException {
        Map<String, Object> body = Map.of(
                "size", k,
                "query", Map.of("script_score", Map.of(
                        "query", Map.of("match_all", Map.of()),
                        "script", Map.of(
                                "source", "cosineSimilarity(params.queryVector, doc['embedding']) + 1.0",
                                "params", Map.of("queryVector", queryVector)
                        )
                ))
        );
        Response response = request("GET", "/" + indexName + "/_search", body);
        JsonNode hits = MAPPER.readTree(response.getEntity().getContent()).path("hits").path("hits");
        List<KnnNeighbor> neighbors = new ArrayList<>();
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            float[] embedding = parseEmbedding(source.path("embedding"));
            neighbors.add(new KnnNeighbor(
                    hit.path("_id").asText(),
                    source.path("timestamp").asText(),
                    source.path("service").asText(),
                    source.path("pattern").asText(),
                    source.path("incidentFamily").asText("unknown"),
                    source.path("scenario").asText(),
                    source.path("message").asText(),
                    hit.path("_score").asDouble(),
                    cosineSimilarity(queryVector, embedding)
            ));
        }
        return neighbors;
    }

    public long countPatternBetween(String pattern, Instant fromInclusive, Instant toExclusive) throws IOException {
        Map<String, Object> body = Map.of(
                "query", Map.of("bool", Map.of("filter", List.of(
                        Map.of("term", Map.of("pattern", pattern)),
                        Map.of("range", Map.of("timestamp", Map.of(
                                "gte", fromInclusive.toString(),
                                "lt", toExclusive.toString()
                        )))
                )))
        );
        Response response = request("GET", "/" + indexName + "/_count", body);
        return MAPPER.readTree(response.getEntity().getContent()).path("count").asLong();
    }

    public long countSemanticNeighborsBetween(
            float[] queryVector,
            Instant fromInclusive,
            Instant toExclusive,
            double similarityThreshold
    ) throws IOException {
        Map<String, Object> body = Map.of(
                "size", 0,
                "track_total_hits", true,
                "min_score", similarityThreshold + 1.0,
                "query", Map.of("script_score", Map.of(
                        "query", Map.of("bool", Map.of("filter", List.of(
                                Map.of("range", Map.of("timestamp", Map.of(
                                        "gte", fromInclusive.toString(),
                                        "lt", toExclusive.toString()
                                )))
                        ))),
                        "script", Map.of(
                                "source", "cosineSimilarity(params.queryVector, doc['embedding']) + 1.0",
                                "params", Map.of("queryVector", queryVector)
                        )
                ))
        );
        Response response = request("GET", "/" + indexName + "/_search", body);
        JsonNode total = MAPPER.readTree(response.getEntity().getContent()).path("hits").path("total");
        if (total.isObject()) {
            return total.path("value").asLong();
        }
        return total.asLong();
    }

    public long countSemanticNeighborsForPatternBetween(
            float[] queryVector,
            String pattern,
            Instant fromInclusive,
            Instant toExclusive,
            double similarityThreshold
    ) throws IOException {
        Map<String, Object> body = Map.of(
                "size", 0,
                "track_total_hits", true,
                "min_score", similarityThreshold + 1.0,
                "query", Map.of("script_score", Map.of(
                        "query", Map.of("bool", Map.of("filter", List.of(
                                Map.of("term", Map.of("pattern", pattern)),
                                Map.of("range", Map.of("timestamp", Map.of(
                                        "gte", fromInclusive.toString(),
                                        "lt", toExclusive.toString()
                                )))
                        ))),
                        "script", Map.of(
                                "source", "cosineSimilarity(params.queryVector, doc['embedding']) + 1.0",
                                "params", Map.of("queryVector", queryVector)
                        )
                ))
        );
        Response response = request("GET", "/" + indexName + "/_search", body);
        JsonNode total = MAPPER.readTree(response.getEntity().getContent()).path("hits").path("total");
        if (total.isObject()) {
            return total.path("value").asLong();
        }
        return total.asLong();
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

    static VectorIndexValidation validateVectorIndexMapping(JsonNode root, String indexName, int expectedDimensions) {
        JsonNode indexNode = root.path(indexName);
        if (indexNode.isMissingNode() && root.fields().hasNext()) {
            indexNode = root.fields().next().getValue();
        }
        JsonNode properties = indexNode.path("mappings").path("properties");
        String embeddingType = properties.path("embedding").path("type").asText("");
        int embeddingDimension = properties.path("embedding").path("dimension").asInt(-1);
        String timestampType = properties.path("timestamp").path("type").asText("");
        String originalTimestampType = properties.path("originalTimestamp").path("type").asText("");
        String incidentFamilyType = properties.path("incidentFamily").path("type").asText("");

        if (!"knn_vector".equals(embeddingType)) {
            return new VectorIndexValidation(
                    false,
                    embeddingType,
                    embeddingDimension,
                    "Expected embedding.type=knn_vector but found '%s'".formatted(emptyToMissing(embeddingType))
            );
        }
        if (embeddingDimension != expectedDimensions) {
            return new VectorIndexValidation(
                    false,
                    embeddingType,
                    embeddingDimension,
                    "Expected embedding.dimension=%d but found %d".formatted(expectedDimensions, embeddingDimension)
            );
        }
        if (!"date".equals(timestampType)) {
            return new VectorIndexValidation(
                    false,
                    embeddingType,
                    embeddingDimension,
                    "Expected timestamp.type=date but found '%s'".formatted(emptyToMissing(timestampType))
            );
        }
        if (!"date".equals(originalTimestampType)) {
            return new VectorIndexValidation(
                    false,
                    embeddingType,
                    embeddingDimension,
                    "Expected originalTimestamp.type=date but found '%s'".formatted(emptyToMissing(originalTimestampType))
            );
        }
        if (!"keyword".equals(incidentFamilyType)) {
            return new VectorIndexValidation(
                    false,
                    embeddingType,
                    embeddingDimension,
                    "Expected incidentFamily.type=keyword but found '%s'".formatted(emptyToMissing(incidentFamilyType))
            );
        }
        return new VectorIndexValidation(true, embeddingType, embeddingDimension, "OK");
    }

    private static String emptyToMissing(String value) {
        return value == null || value.isBlank() ? "<missing>" : value;
    }

    public record VectorIndexValidation(
            boolean valid,
            String embeddingType,
            int embeddingDimension,
            String message
    ) {
        public boolean indexMissing() {
            return message != null && message.startsWith("Index not found:");
        }
    }

    public record VectorIndexPreparation(
            String action,
            VectorIndexValidation validation
    ) {
    }

    private Response request(String method, String endpoint, Map<String, Object> body) throws IOException {
        Request request = new Request(method, endpoint);
        request.setEntity(new NStringEntity(MAPPER.writeValueAsString(body), ContentType.APPLICATION_JSON));
        return client.performRequest(request);
    }

    private void bulkIndexBatch(List<LogDocument> documents) throws IOException {
        StringBuilder body = new StringBuilder();
        for (LogDocument document : documents) {
            body.append(MAPPER.writeValueAsString(Map.of("index", Map.of("_id", document.id())))).append('\n');
            body.append(MAPPER.writeValueAsString(Map.of(
                    "timestamp", document.timestamp().toString(),
                    "originalTimestamp", document.originalTimestamp().toString(),
                    "service", document.service(),
                    "pattern", document.pattern(),
                    "incidentFamily", document.incidentFamily(),
                    "nativeLabel", document.nativeLabel(),
                    "scenario", document.scenario(),
                    "message", document.message(),
                    "embedding", document.embedding()
            ))).append('\n');
        }
        Request request = new Request("POST", "/" + indexName + "/_bulk");
        request.setEntity(new NStringEntity(body.toString(), ContentType.create("application/x-ndjson", StandardCharsets.UTF_8)));
        Response response = client.performRequest(request);
        JsonNode root = MAPPER.readTree(response.getEntity().getContent());
        if (root.path("errors").asBoolean(false)) {
            throw new IOException("OpenSearch bulk index request completed with errors");
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
    }
}
