package com.loganomaly.opensearch;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BglTwoIndexDocumentTest {
    @Test
    void templateIdIsDeterministicAndPatternSensitive() {
        String first = BglTemplateId.fromPattern("fatal");
        String second = BglTemplateId.fromPattern("fatal");
        String different = BglTemplateId.fromPattern("kernel panic");

        assertEquals(first, second);
        assertNotEquals(first, different);
        assertEquals(64, first.length());
    }

    @Test
    void templateDocumentCarriesEmbedding() {
        float[] embedding = new float[]{0.1f, 0.2f};
        BglTemplateDocument document = new BglTemplateDocument(
                BglTemplateId.fromPattern("fatal"),
                "fatal",
                "node|RAS|KERNEL|FATAL",
                "bgl-normal",
                "-",
                "FATAL",
                embedding
        );

        assertTrue(document.source().containsKey("embedding"));
        assertArrayEquals(embedding, (float[]) document.source().get("embedding"));
    }

    @Test
    void eventDocumentDoesNotCarryEmbedding() {
        BglEventDocument document = new BglEventDocument(
                "bgl-1",
                Instant.parse("2005-07-08T16:48:34.004234Z"),
                Instant.parse("2005-07-08T16:48:34.004234Z"),
                BglTemplateId.fromPattern("fatal"),
                "fatal",
                "node|RAS|KERNEL|FATAL",
                "bgl-normal",
                "-",
                "BGL.log",
                "FATAL"
        );

        assertFalse(document.source().containsKey("embedding"));
        assertEquals("fatal", document.source().get("pattern"));
        assertEquals(BglTemplateId.fromPattern("fatal"), document.source().get("templateId"));
    }
}
