package com.metafore.edge.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * adr-196 — coverage for {@link SqlExecutor#executeWriteWithEvents} input
 * validation (no live DB, mirroring the existing null-DataSource style) and
 * the pure diff -> events derivation ({@link SqlExecutor#deriveEvents}).
 *
 * The transactional DB behavior (atomic record-write + event-insert,
 * rollback on failure) is exercised in the deploy+verify phase against a
 * live tenant PG — this class locks the input contract + the "one event
 * per actually-changed tracked field" semantics that must hold regardless
 * of the backing store.
 */
class WriteWithEventsTest {

    private static Map<String, Object> baseEvents() {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("tenant_id", "t-1");
        ev.put("app_id", "a-1");
        ev.put("object_type", "Case");
        ev.put("record_id", "rec-1");
        ev.put("source", "test");
        ev.put("occurred_at", "2026-07-23T00:00:00Z");
        Map<String, String> fe = new LinkedHashMap<>();
        fe.put("status", "StatusChanged");
        fe.put("owner_id", "OwnerChanged");
        ev.put("field_events", fe);
        ev.put("on_create", "Created");
        ev.put("on_delete", "Deleted");
        return ev;
    }

    // ── input validation (null DataSource, fails before DB contact) ──

    @Test
    void rejectsBadInnerOperation() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("inner_operation", "upsert");
        payload.put("table_name", "case_x");
        payload.put("events", baseEvents());
        Map<String, Object> r =
            SqlExecutor.executeWriteWithEvents(null, payload);
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains("inner_operation"));
    }

    @Test
    void rejectsMissingTableName() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("inner_operation", "update");
        payload.put("events", baseEvents());
        Map<String, Object> r =
            SqlExecutor.executeWriteWithEvents(null, payload);
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains("table_name"));
    }

    @Test
    void rejectsMissingEventsBlock() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("inner_operation", "create");
        payload.put("table_name", "case_x");
        Map<String, Object> r =
            SqlExecutor.executeWriteWithEvents(null, payload);
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains("events"));
    }

    @Test
    void rejectsUpdateWithoutWhereColumn() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("inner_operation", "update");
        payload.put("table_name", "case_x");
        payload.put("columns", List.of("status"));
        payload.put("values", List.of("closed"));
        payload.put("events", baseEvents());
        Map<String, Object> r =
            SqlExecutor.executeWriteWithEvents(null, payload);
        assertEquals("error", r.get("status"));
        assertTrue(((String) r.get("error")).contains("where_column"));
    }

    // ── diff -> events derivation (pure, no DB) ──────────────────────

    @Test
    void updateEmitsOneEventPerActuallyChangedTrackedField() {
        Map<String, Object> events = baseEvents();
        @SuppressWarnings("unchecked")
        Map<String, String> fe = (Map<String, String>) events.get("field_events");
        List<String> cols = List.of("status", "owner_id", "notes");
        List<Object> vals = new ArrayList<>(List.of("closed", "u-2", "hi"));
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("status", "open");     // changed  -> StatusChanged
        old.put("owner_id", "u-2");    // unchanged -> no event
        List<Map<String, Object>> out = SqlExecutor.deriveEvents(
            "update", events, fe, cols, vals, old);
        assertEquals(1, out.size(), "only the changed tracked field emits");
        assertEquals("StatusChanged", out.get(0).get("event_type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> cf =
            (Map<String, Object>) out.get(0).get("changed_fields");
        assertTrue(cf.containsKey("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> diff = (Map<String, Object>) cf.get("status");
        assertEquals("open", diff.get("old"));
        assertEquals("closed", diff.get("new"));
    }

    @Test
    void updateWithNoTrackedChangeEmitsNothing() {
        Map<String, Object> events = baseEvents();
        @SuppressWarnings("unchecked")
        Map<String, String> fe = (Map<String, String>) events.get("field_events");
        List<String> cols = List.of("status");
        List<Object> vals = new ArrayList<>(List.of("open"));
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("status", "open");     // unchanged
        List<Map<String, Object>> out = SqlExecutor.deriveEvents(
            "update", events, fe, cols, vals, old);
        assertTrue(out.isEmpty());
    }

    @Test
    void createEmitsOnCreateEvent() {
        Map<String, Object> events = baseEvents();
        @SuppressWarnings("unchecked")
        Map<String, String> fe = (Map<String, String>) events.get("field_events");
        List<String> cols = List.of("status");
        List<Object> vals = new ArrayList<>(List.of("open"));
        List<Map<String, Object>> out = SqlExecutor.deriveEvents(
            "create", events, fe, cols, vals, new LinkedHashMap<>());
        assertEquals(1, out.size());
        assertEquals("Created", out.get(0).get("event_type"));
    }

    @Test
    void deleteEmitsOnDeleteEvent() {
        Map<String, Object> events = baseEvents();
        @SuppressWarnings("unchecked")
        Map<String, String> fe = (Map<String, String>) events.get("field_events");
        List<Map<String, Object>> out = SqlExecutor.deriveEvents(
            "delete", events, fe, List.of(), new ArrayList<>(),
            new LinkedHashMap<>());
        assertEquals(1, out.size());
        assertEquals("Deleted", out.get(0).get("event_type"));
    }

    @Test
    void multipleChangedFieldsEmitDistinctTypedEvents() {
        Map<String, Object> events = baseEvents();
        @SuppressWarnings("unchecked")
        Map<String, String> fe = (Map<String, String>) events.get("field_events");
        List<String> cols = List.of("status", "owner_id");
        List<Object> vals = new ArrayList<>(List.of("closed", "u-9"));
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("status", "open");
        old.put("owner_id", "u-2");
        List<Map<String, Object>> out = SqlExecutor.deriveEvents(
            "update", events, fe, cols, vals, old);
        assertEquals(2, out.size());
        List<String> types = out.stream()
            .map(e -> (String) e.get("event_type")).sorted().toList();
        assertEquals(List.of("OwnerChanged", "StatusChanged"), types);
    }
}
