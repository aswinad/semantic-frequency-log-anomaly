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
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class OpenSearchLogVectorRepository implements Closeable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        return new OpenSearchLogVectorRepository(
                buildClient(
                        config.openSearchUrl(),
                        config.openSearchUsername().orElse(null),
                        config.openSearchPassword().orElse(null)
                ),
                config.openSearchIndex()
        );
    }

    private static RestClient buildClient(String url, String username, String password) {
        URI uri = URI.create(url);
        RestClientBuilder builder = RestClient.builder(new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme()));
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
        Map<String, Object> body = Map.of(
                "settings", Map.of("index", Map.of("knn", true)),
                "mappings", Map.of("properties", Map.of(
                        "service", Map.of("type", "keyword"),
                        "pattern", Map.of("type", "keyword"),
                        "incidentFamily", Map.of("type", "keyword"),
                        "scenario", Map.of("type", "keyword"),
                        "timestamp", Map.of("type", "date"),
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
        index(new LogDocument(id, Instant.now(), service, "unknown", "unknown", "manual", message, embedding));
    }

    public void index(LogDocument document) throws IOException {
        Map<String, Object> body = Map.of(
                "timestamp", document.timestamp().toString(),
                "service", document.service(),
                "pattern", document.pattern(),
                "incidentFamily", document.incidentFamily(),
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

    private Response request(String method, String endpoint, Map<String, Object> body) throws IOException {
        Request request = new Request(method, endpoint);
        request.setEntity(new NStringEntity(MAPPER.writeValueAsString(body), ContentType.APPLICATION_JSON));
        return client.performRequest(request);
    }

    @Override
    public void close() throws IOException {
        client.close();
    }
}
