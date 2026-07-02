package com.metafore.edge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * adr-158 — MCP-client transport for the Access Controller.
 *
 * <p>Executes a Model Context Protocol tool call locally at the edge, so
 * the substrate can consume MCP-native systems (Compass, and any modern
 * system that exposes an MCP interface) as just another integrated
 * source/effect — with credentials staying local to the controller,
 * exactly like the JDBC and REST transports.
 *
 * <p>Wire contract (route-command {@code parameters}, see
 * {@code contracts/mqtt/route_command.schema.json}):
 * <pre>{
 *   "mcp_server_url": "https://compass.example/mcp",  // http/sse transport
 *   "mcp_transport":  "http",                          // http|sse|stdio
 *   "mcp_tool":       "get_roadmap",
 *   "mcp_arguments":  { ... },
 *   "mcp_token":      "..."                             // optional bearer
 * }</pre>
 *
 * <p>Protocol (MCP Streamable HTTP, rev 2025-06-18): a single JSON-RPC
 * 2.0 endpoint. This client performs the minimal correct session:
 * {@code initialize} → {@code notifications/initialized} → {@code
 * tools/call}, echoing any {@code Mcp-Session-Id} the server assigns.
 * Responses arrive either as a plain {@code application/json} body or as
 * an {@code text/event-stream} (SSE) frame — both are parsed.
 *
 * <p>The client is hand-rolled on {@link java.net.http.HttpClient} +
 * Jackson (already on the edge classpath) rather than pulling in the
 * official MCP Java SDK, keeping the runtime image lean and consistent
 * with {@code RestAdapterRoute}. The SDK remains a drop-in future swap
 * if richer capability negotiation is needed.
 *
 * <p>Result mapping: the tool's {@code content[]} + {@code
 * structuredContent} are flattened into the standard route-result
 * {@code data} row list — text items that parse as a JSON array become
 * one row each; a JSON object becomes one row; opaque text becomes
 * {@code {"text": ...}}. Integration-specific field mapping is applied
 * downstream in core, mirroring how HTTP response bodies are handled.
 */
public final class McpExecutor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final HttpClient httpClient;

    public McpExecutor() {
        this(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build());
    }

    /** Test-friendly constructor accepting a pre-built (mock) HttpClient. */
    McpExecutor(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Whether this command is an MCP transport command. Keyed on
     * {@code mcp_tool} presence — the dispatcher uses the same signal.
     */
    public static boolean isMcpCommand(Map<String, Object> params) {
        if (params == null) return false;
        Object tool = params.get("mcp_tool");
        return tool instanceof String && !((String) tool).isBlank();
    }

    /**
     * Execute the MCP tool call. Returns a partial route-result map
     * carrying {@code status}, {@code action="mcp"}, {@code latency_ms},
     * and either {@code data} (success) or {@code error}/{@code
     * error_details} (failure). The caller wraps it via
     * {@code MessageFactory.routeResult}.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> params) {
        long start = System.currentTimeMillis();

        String transport = str(params, "mcp_transport", "http").toLowerCase();
        String tool = str(params, "mcp_tool", "");
        Object argsObj = params.get("mcp_arguments");
        Map<String, Object> arguments = argsObj instanceof Map
            ? (Map<String, Object>) argsObj : Map.of();

        if (tool.isBlank()) {
            return err(start, "invalid_mcp_command",
                "Missing 'mcp_tool' in route parameters.");
        }
        if (!"http".equals(transport) && !"sse".equals(transport)) {
            // stdio (local child-process MCP servers) is a planned
            // second increment; fail honestly rather than silently.
            return err(start, "unsupported_mcp_transport",
                "MCP transport '" + transport + "' is not yet supported "
                + "on this controller. Supported: http, sse.");
        }

        String serverUrl = str(params, "mcp_server_url", "");
        if (serverUrl.isBlank()) {
            return err(start, "invalid_mcp_command",
                "Missing 'mcp_server_url' for MCP transport '" + transport + "'.");
        }
        Map<String, String> reqHeaders = buildHeaders(params);

        try {
            // 1. initialize — negotiate + capture session id.
            ObjectNode initReq = jsonRpc(1, "initialize", initParams());
            HttpResponse<String> initResp = post(serverUrl, reqHeaders, null, initReq);
            String sessionId = header(initResp, "Mcp-Session-Id");
            JsonNode initResult = extractResult(initResp, 1);
            if (initResult == null) {
                return err(start, "mcp_initialize_failed",
                    "MCP server did not return an initialize result: "
                    + truncate(initResp.body()));
            }

            // 2. notifications/initialized — no id, no response expected.
            ObjectNode initedNote = MAPPER.createObjectNode();
            initedNote.put("jsonrpc", "2.0");
            initedNote.put("method", "notifications/initialized");
            post(serverUrl, reqHeaders, sessionId, initedNote);

            // 3. tools/call.
            ObjectNode callParams = MAPPER.createObjectNode();
            callParams.put("name", tool);
            callParams.set("arguments", MAPPER.valueToTree(arguments));
            ObjectNode callReq = jsonRpc(2, "tools/call", callParams);
            HttpResponse<String> callResp = post(serverUrl, reqHeaders, sessionId, callReq);

            JsonNode error = extractError(callResp, 2);
            if (error != null) {
                return err(start, "mcp_tool_error",
                    "MCP tool '" + tool + "' returned an error: "
                    + error.toString());
            }
            JsonNode result = extractResult(callResp, 2);
            if (result == null) {
                return err(start, "mcp_no_result",
                    "MCP tool '" + tool + "' returned no result: "
                    + truncate(callResp.body()));
            }

            // isError on the tool result envelope (protocol-level ok,
            // tool-level failure) — surface as an error status.
            if (result.path("isError").asBoolean(false)) {
                return err(start, "mcp_tool_error",
                    "MCP tool '" + tool + "' reported isError: "
                    + flattenText(result));
            }

            List<Map<String, Object>> data = mapResultToRows(result);
            long latency = System.currentTimeMillis() - start;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "success");
            out.put("action", "mcp");
            out.put("latency_ms", latency);
            out.put("row_count", data.size());
            out.put("data", data);
            return out;

        } catch (Exception e) {
            return err(start, "mcp_transport_error",
                "MCP call failed: " + e.getClass().getSimpleName()
                + ": " + e.getMessage());
        }
    }

    // ── JSON-RPC / HTTP plumbing ─────────────────────────────────────

    private ObjectNode initParams() {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("protocolVersion", PROTOCOL_VERSION);
        p.set("capabilities", MAPPER.createObjectNode());
        ObjectNode clientInfo = MAPPER.createObjectNode();
        clientInfo.put("name", "metafore-edge");
        clientInfo.put("version", "1.0.0");
        p.set("clientInfo", clientInfo);
        return p;
    }

    private ObjectNode jsonRpc(int id, String method, JsonNode params) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        return req;
    }

    /**
     * Assemble the per-request auth/routing headers. Supports arbitrary
     * custom headers via {@code mcp_headers} (e.g. Compass needs
     * {@code X-API-Key} + {@code X-Project} — it does NOT use a bearer
     * token), plus the {@code mcp_token} convenience which becomes
     * {@code Authorization: Bearer <token>} unless an Authorization
     * header was already supplied in {@code mcp_headers}.
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> buildHeaders(Map<String, Object> params) {
        Map<String, String> headers = new LinkedHashMap<>();
        Object raw = params.get("mcp_headers");
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    headers.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        }
        String token = str(params, "mcp_token", "");
        if (!token.isBlank()
            && headers.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("authorization"))) {
            headers.put("Authorization", "Bearer " + token);
        }
        return headers;
    }

    private HttpResponse<String> post(
        String url, Map<String, String> extraHeaders, String sessionId, JsonNode body
    ) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", PROTOCOL_VERSION);
        if (extraHeaders != null) {
            for (Map.Entry<String, String> h : extraHeaders.entrySet()) {
                b.header(h.getKey(), h.getValue());
            }
        }
        if (sessionId != null && !sessionId.isBlank()) {
            b.header("Mcp-Session-Id", sessionId);
        }
        b.POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        return httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String header(HttpResponse<?> resp, String name) {
        return resp.headers().firstValue(name).orElse(null);
    }

    /**
     * Pull the JSON-RPC message matching {@code id} out of a response
     * that is either a plain JSON body or an SSE (text/event-stream)
     * frame, and return its {@code result} node (or null).
     */
    private JsonNode extractResult(HttpResponse<String> resp, int id) throws Exception {
        JsonNode msg = extractMessage(resp, id);
        return msg != null && msg.has("result") ? msg.get("result") : null;
    }

    private JsonNode extractError(HttpResponse<String> resp, int id) throws Exception {
        JsonNode msg = extractMessage(resp, id);
        return msg != null && msg.has("error") ? msg.get("error") : null;
    }

    private JsonNode extractMessage(HttpResponse<String> resp, int id) throws Exception {
        String body = resp.body();
        if (body == null || body.isBlank()) {
            return null;
        }
        String contentType = header(resp, "Content-Type");
        boolean sse = contentType != null
            && contentType.toLowerCase().contains("text/event-stream");
        if (sse || body.stripLeading().startsWith("event:")
            || body.stripLeading().startsWith("data:")) {
            return matchInSse(body, id);
        }
        // Plain JSON body — a single JSON-RPC message.
        return MAPPER.readTree(body);
    }

    /** Scan SSE {@code data:} lines for the JSON-RPC message with {@code id}. */
    private JsonNode matchInSse(String body, int id) throws Exception {
        JsonNode last = null;
        StringBuilder dataBuf = new StringBuilder();
        for (String line : body.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("data:")) {
                if (dataBuf.length() > 0) dataBuf.append("\n");
                dataBuf.append(trimmed.substring(5).strip());
            } else if (trimmed.isEmpty() && dataBuf.length() > 0) {
                JsonNode parsed = tryParse(dataBuf.toString());
                dataBuf.setLength(0);
                if (parsed != null) {
                    last = parsed;
                    if (matchesId(parsed, id)) return parsed;
                }
            }
        }
        if (dataBuf.length() > 0) {
            JsonNode parsed = tryParse(dataBuf.toString());
            if (parsed != null) {
                if (matchesId(parsed, id)) return parsed;
                last = parsed;
            }
        }
        // Fallback: the response was labelled text/event-stream but carried
        // no SSE data frames (some servers label a plain JSON body as SSE).
        // Parse the whole body as a single JSON-RPC message.
        if (last == null) {
            return tryParse(body);
        }
        return last;
    }

    private JsonNode tryParse(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean matchesId(JsonNode node, int id) {
        return node != null && node.has("id") && node.get("id").asInt(-1) == id;
    }

    // ── result → rows mapping ────────────────────────────────────────

    /**
     * Flatten an MCP tool result into route-result rows. Precedence:
     * {@code structuredContent} (array→rows, object→one row) when present,
     * else each {@code content[]} text item parsed as JSON (array→rows,
     * object→one row) or wrapped as {@code {"text": ...}}.
     */
    private List<Map<String, Object>> mapResultToRows(JsonNode result) {
        List<Map<String, Object>> rows = new ArrayList<>();

        JsonNode structured = result.get("structuredContent");
        if (structured != null && !structured.isNull()) {
            addNodeAsRows(structured, rows);
            if (!rows.isEmpty()) return rows;
        }

        JsonNode content = result.get("content");
        if (content instanceof ArrayNode) {
            for (JsonNode item : content) {
                String type = item.path("type").asText("");
                if ("text".equals(type)) {
                    String text = item.path("text").asText("");
                    JsonNode parsed = tryParse(text);
                    if (parsed != null && (parsed.isArray() || parsed.isObject())) {
                        addNodeAsRows(parsed, rows);
                    } else {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("text", text);
                        rows.add(row);
                    }
                } else {
                    rows.add(nodeToMap(item));
                }
            }
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private void addNodeAsRows(JsonNode node, List<Map<String, Object>> rows) {
        if (node.isArray()) {
            for (JsonNode el : node) {
                rows.add(nodeToMap(el));
            }
        } else if (node.isObject()) {
            rows.add(nodeToMap(node));
        } else {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("value", MAPPER.convertValue(node, Object.class));
            rows.add(row);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nodeToMap(JsonNode node) {
        if (node.isObject()) {
            return MAPPER.convertValue(node, Map.class);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("value", MAPPER.convertValue(node, Object.class));
        return row;
    }

    private String flattenText(JsonNode result) {
        StringBuilder sb = new StringBuilder();
        JsonNode content = result.get("content");
        if (content instanceof ArrayNode) {
            for (JsonNode item : content) {
                if ("text".equals(item.path("type").asText(""))) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(item.path("text").asText(""));
                }
            }
        }
        return sb.length() > 0 ? sb.toString() : truncate(result.toString());
    }

    // ── helpers ──────────────────────────────────────────────────────

    private Map<String, Object> err(long start, String error, String details) {
        long latency = System.currentTimeMillis() - start;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "error");
        out.put("action", "mcp");
        out.put("latency_ms", latency);
        out.put("error", error);
        out.put("error_details", details);
        return out;
    }

    private static String str(Map<String, Object> params, String key, String def) {
        Object v = params.get(key);
        return v != null ? String.valueOf(v) : def;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }
}
