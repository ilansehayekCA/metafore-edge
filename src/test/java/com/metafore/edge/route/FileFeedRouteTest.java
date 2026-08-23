package com.metafore.edge.route;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G5 — the file-feed parser.
 *
 * <p>The edge parses SHAPE and nothing else. What a column MEANS is the app's
 * business, so these tests assert that rows come through with their headers
 * intact and untranslated — a partner's vocabulary must not be interpreted here,
 * because that is how a domain literal gets into the platform.
 */
class FileFeedRouteTest {

    @Test
    void csvWithAHeaderBecomesOneMapPerRow() throws Exception {
        byte[] csv = ("patient_id,rx_status,ship_date\n"
                    + "PAT-1,ready_to_ship,2026-08-01\n"
                    + "PAT-2,delivered,2026-08-02\n").getBytes();
        List<Map<String, Object>> rows = FileFeedRoute.parse(csv, "csv");
        assertEquals(2, rows.size());
        assertEquals("PAT-1", rows.get(0).get("patient_id"));
        assertEquals("delivered", rows.get(1).get("rx_status"));
        // headers arrive verbatim — not renamed, not mapped, not interpreted
        assertTrue(rows.get(0).containsKey("ship_date"));
    }

    @Test
    void aHeaderOnlyCsvYieldsNoRowsRatherThanOneBlankRow() throws Exception {
        byte[] csv = "patient_id,rx_status\n".getBytes();
        assertEquals(0, FileFeedRoute.parse(csv, "csv").size());
    }

    @Test
    void anEmptyFileYieldsNoRows() throws Exception {
        assertEquals(0, FileFeedRoute.parse(new byte[0], "csv").size());
        assertEquals(0, FileFeedRoute.parse(null, "csv").size());
    }

    @Test
    void jsonArrayBecomesOneMapPerElement() throws Exception {
        byte[] json = "[{\"a\":1},{\"a\":2}]".getBytes();
        List<Map<String, Object>> rows = FileFeedRoute.parse(json, "json");
        assertEquals(2, rows.size());
        assertEquals(1, rows.get(0).get("a"));
    }

    @Test
    void aSingleJsonObjectIsOneRow() throws Exception {
        List<Map<String, Object>> rows =
            FileFeedRoute.parse("{\"a\":1}".getBytes(), "json");
        assertEquals(1, rows.size());
    }

    @Test
    void rawKeepsTheFileWholeForAFormatWeDoNotParse() throws Exception {
        List<Map<String, Object>> rows =
            FileFeedRoute.parse("anything at all".getBytes(), "raw");
        assertEquals(1, rows.size());
        assertEquals("anything at all", rows.get(0).get("raw"));
    }

    @Test
    void quotedCsvValuesContainingCommasSurvive() throws Exception {
        byte[] csv = ("id,note\n1,\"delayed, awaiting patient\"\n").getBytes();
        List<Map<String, Object>> rows = FileFeedRoute.parse(csv, "csv");
        assertEquals("delayed, awaiting patient", rows.get(0).get("note"));
    }

    @Test
    void theDelayFallsBackWhenItIsNotANumber() {
        assertEquals(42L, FileFeedRoute.asLong(42, 1L));
        assertEquals(42L, FileFeedRoute.asLong("42", 1L));
        assertEquals(1L, FileFeedRoute.asLong("not a number", 1L));
        assertEquals(1L, FileFeedRoute.asLong(null, 1L));
    }
}
