package com.loganomaly.opensearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenSearchVectorIndexValidationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void validMappingPassesValidation() throws Exception {
        String json = """
                {
                  "log-anomaly-openstack": {
                    "mappings": {
                      "properties": {
                        "incidentFamily": {"type": "keyword"},
                        "originalTimestamp": {"type": "date"},
                        "embedding": {"type": "knn_vector", "dimension": 1536}
                      }
                    }
                  }
                }
                """;

        OpenSearchLogVectorRepository.VectorIndexValidation validation =
                OpenSearchLogVectorRepository.validateVectorIndexMapping(
                        MAPPER.readTree(json),
                        "log-anomaly-openstack",
                        1536
                );

        assertTrue(validation.valid());
        assertEquals("knn_vector", validation.embeddingType());
        assertEquals(1536, validation.embeddingDimension());
    }

    @Test
    void wrongEmbeddingTypeFailsWithUsefulMessage() throws Exception {
        String json = """
                {
                  "log-anomaly-openstack": {
                    "mappings": {
                      "properties": {
                        "incidentFamily": {"type": "keyword"},
                        "originalTimestamp": {"type": "date"},
                        "embedding": {"type": "float", "dimension": 1536}
                      }
                    }
                  }
                }
                """;

        OpenSearchLogVectorRepository.VectorIndexValidation validation =
                OpenSearchLogVectorRepository.validateVectorIndexMapping(
                        MAPPER.readTree(json),
                        "log-anomaly-openstack",
                        1536
                );

        assertFalse(validation.valid());
        assertTrue(validation.message().contains("embedding.type=knn_vector"));
    }

    @Test
    void wrongDimensionFailsWithUsefulMessage() throws Exception {
        String json = """
                {
                  "log-anomaly-openstack": {
                    "mappings": {
                      "properties": {
                        "incidentFamily": {"type": "keyword"},
                        "originalTimestamp": {"type": "date"},
                        "embedding": {"type": "knn_vector", "dimension": 768}
                      }
                    }
                  }
                }
                """;

        OpenSearchLogVectorRepository.VectorIndexValidation validation =
                OpenSearchLogVectorRepository.validateVectorIndexMapping(
                        MAPPER.readTree(json),
                        "log-anomaly-openstack",
                        1536
                );

        assertFalse(validation.valid());
        assertTrue(validation.message().contains("embedding.dimension=1536"));
    }
}
