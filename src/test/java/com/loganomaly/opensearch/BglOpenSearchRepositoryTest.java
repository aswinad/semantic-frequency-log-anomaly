package com.loganomaly.opensearch;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BglOpenSearchRepositoryTest {
    @Test
    void semanticTemplateIdSearchBodyDisablesSourceAndUsesConfiguredCap() {
        Map<String, Object> body = BglOpenSearchRepository.semanticTemplateIdSearchBody(new float[]{1.0f, 0.0f}, 0.85);

        assertEquals(10_000, body.get("size"));
        assertEquals(false, body.get("_source"));
        assertEquals(1.85, (double) body.get("min_score"), 0.0001);
        assertTrue(body.containsKey("query"));
    }

    @Test
    void topKNeighborSearchBodyRemainsRichNeighborQuery() {
        Map<String, Object> body = BglOpenSearchRepository.topKNeighborSearchBody(new float[]{1.0f, 0.0f}, 5);

        assertEquals(5, body.get("size"));
        assertFalse(body.containsKey("_source"));
        assertTrue(body.containsKey("query"));
    }
}
