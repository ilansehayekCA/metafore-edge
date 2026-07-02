package com.metafore.edge.service;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * adr-158 — MCP-client transport coverage. Drives {@link McpExecutor}
 * against a scripted HttpClient that replays the initialize →
 * initialized-notification → tools/call handshake without a live MCP
 * server. No Camel scaffolding.
 */
class McpExecutorTest {

    /** initialize result, common to the happy-path scripts. */
    private static final String INIT_BODY =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{"
        + "\"protocolVersion\":\"2025-06-18\","
        + "\"capabilities\":{\"tools\":{}},"
        + "\"serverInfo\":{\"name\":\"compass\",\"version\":\"1.0\"}}}";

    private static Map<String, Object> mcpCmd(String tool) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("mcp_server_url", "https://compass.example/mcp");
        p.put("mcp_transport", "http");
        p.put("mcp_tool", tool);
        p.put("mcp_arguments", Map.of("slug_or_id", "roadmap"));
        p.put("mcp_token", "tok-123");
        return p;
    }

    @Test
    void successfulToolCallMapsJsonArrayToRows() {
        String callBody =
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{"
            + "\"type\":\"text\",\"text\":\"[{\\\"id\\\":\\\"T-1\\\"},"
            + "{\\\"id\\\":\\\"T-2\\\"}]\"}],\"isError\":false}}";
        ScriptedHttpClient client = new ScriptedHttpClient(
            resp(200, "application/json", "sess-abc", INIT_BODY),
            resp(202, "application/json", null, ""),           // initialized note
            resp(200, "application/json", null, callBody));

        Map<String, Object> r = new McpExecutor(client).execute(mcpCmd("get_roadmap"));

        assertEquals("success", r.get("status"));
        assertEquals("mcp", r.get("action"));
        assertEquals(2, r.get("row_count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) r.get("data");
        assertEquals("T-1", data.get(0).get("id"));
        assertEquals("T-2", data.get(1).get("id"));
    }

    @Test
    void sessionHeaderEchoedOnSubsequentRequests() {
        String callBody =
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{"
            + "\"type\":\"text\",\"text\":\"{\\\"ok\\\":true}\"}]}}";
        ScriptedHttpClient client = new ScriptedHttpClient(
            resp(200, "application/json", "sess-XYZ", INIT_BODY),
            resp(202, "application/json", null, ""),
            resp(200, "application/json", null, callBody));

        new McpExecutor(client).execute(mcpCmd("get_context"));

        // The initialize request has no session; the notification + call
        // must carry the server-assigned Mcp-Session-Id.
        assertNull(client.sentSession(0));
        assertEquals("sess-XYZ", client.sentSession(1));
        assertEquals("sess-XYZ", client.sentSession(2));
        // Bearer token applied on every request.
        assertEquals("Bearer tok-123", client.sentAuth(2));
    }

    @Test
    void customHeadersAppliedOnEveryRequest() {
        // Compass shape: X-API-Key + X-Project, no bearer token.
        String callBody =
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{"
            + "\"type\":\"text\",\"text\":\"{\\\"ok\\\":true}\"}]}}";
        ScriptedHttpClient client = new ScriptedHttpClient(
            resp(200, "application/json", "s1", INIT_BODY),
            resp(202, "application/json", null, ""),
            resp(200, "application/json", null, callBody));

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("mcp_server_url", "https://campass-btv.up.railway.app/mcp/");
        p.put("mcp_transport", "http");
        p.put("mcp_tool", "manage_features");
        p.put("mcp_arguments", Map.of("operation", "list"));
        p.put("mcp_headers", Map.of("X-API-Key", "cmps-xxx", "X-Project", "metafore"));

        Map<String, Object> r = new McpExecutor(client).execute(p);

        assertEquals("success", r.get("status"));
        // Custom headers applied on initialize (0), notification (1), call (2).
        for (int i = 0; i < 3; i++) {
            assertEquals("cmps-xxx", client.sentHeader(i, "X-API-Key"), "req " + i);
            assertEquals("metafore", client.sentHeader(i, "X-Project"), "req " + i);
        }
        // No bearer token supplied → no Authorization header.
        assertNull(client.sentAuth(2));
    }

    @Test
    void explicitAuthorizationHeaderWinsOverToken() {
        String callBody =
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{"
            + "\"type\":\"text\",\"text\":\"{\\\"ok\\\":true}\"}]}}";
        ScriptedHttpClient client = new ScriptedHttpClient(
            resp(200, "application/json", "s1", INIT_BODY),
            resp(202, "application/json", null, ""),
            resp(200, "application/json", null, callBody));

        Map<String, Object> p = mcpCmd("get_context");        // carries mcp_token=tok-123
        p.put("mcp_headers", Map.of("Authorization", "Custom keep-me"));

        new McpExecutor(client).execute(p);

        // mcp_headers Authorization must not be overwritten by the token.
        assertEquals("Custom keep-me", client.sentHeader(2, "Authorization"));
    }

    @Test
    void structuredContentPreferredOverText() {
        String callBody =
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{"
            + "\"structuredContent\":[{\"slug\":\"a\"},{\"slug\":\"b\"},{\"slug\":\"c\"}],"
            + "\"content\":[{\"type\":\"text\",\"text\":\"ignored summary\"}]}}";
        ScriptedHttpClient client = new ScriptedHttpClient(
            resp(200, "application/json", "s", INIT_BODY),
            resp(202, "application/json", null, ""),
            resp(200, "application/json", null, callBody));

        Map<String, Object> r = new McpExecutor(client).execute(mcpCmd("list_features"));

        assertEquals("success", r.get("status"));
        assertEquals(3, r.get("row_count"));
    }

    @Test
    void sseFramedResponseParsed() {
        String sse =
            "event: message\n"
            + "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":"
            + "[{\"type\":\"text\",\"text\":\"{\\\"n\\\":42}\"}]}}\n\n";
        ScriptedHttpClient client = new ScriptedHttpClient(
            resp(200, "text/event-stream", "s", INIT_BODY),  // init still json-parseable
            resp(202, "application/json", null, ""),
            resp(200, "text/event-stream", null, sse));

        Map<String, Object> r = new McpExecutor(client).execute(mcpCmd("count"));

        assertEquals("success", r.get("status"));
        assertEquals(1, r.get("row_count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) r.get("data");
        assertEquals(42, data.get(0).get("n"));
    }

    @Test
    void opaqueTextBecomesTextRow() {
        String callBody =
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{"
            + "\"type\":\"text\",\"text\":\"just a human sentence\"}]}}";
        ScriptedHttpClient client = new ScriptedHttpClient(
            resp(200, "application/json", "s", INIT_BODY),
            resp(202, "application/json", null, ""),
            resp(200, "application/json", null, callBody));

        Map<String, Object> r = new McpExecutor(client).execute(mcpCmd("describe"));

        assertEquals("success", r.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) r.get("data");
        assertEquals("just a human sentence", data.get(0).get("text"));
    }

    @Test
    void jsonRpcErrorSurfacedAsError() {
        String callBody =
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"error\":{\"code\":-32601,"
            + "\"message\":\"Method not found\"}}";
        ScriptedHttpClient client = new ScriptedHttpClient(
            resp(200, "application/json", "s", INIT_BODY),
            resp(202, "application/json", null, ""),
            resp(200, "application/json", null, callBody));

        Map<String, Object> r = new McpExecutor(client).execute(mcpCmd("nope"));

        assertEquals("error", r.get("status"));
        assertEquals("mcp_tool_error", r.get("error"));
        assertTrue(((String) r.get("error_details")).contains("Method not found"));
    }

    @Test
    void toolLevelIsErrorSurfaced() {
        String callBody =
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"isError\":true,"
            + "\"content\":[{\"type\":\"text\",\"text\":\"quota exceeded\"}]}}";
        ScriptedHttpClient client = new ScriptedHttpClient(
            resp(200, "application/json", "s", INIT_BODY),
            resp(202, "application/json", null, ""),
            resp(200, "application/json", null, callBody));

        Map<String, Object> r = new McpExecutor(client).execute(mcpCmd("do_thing"));

        assertEquals("error", r.get("status"));
        assertEquals("mcp_tool_error", r.get("error"));
        assertTrue(((String) r.get("error_details")).contains("quota exceeded"));
    }

    @Test
    void missingToolRejectedWithoutNetwork() {
        Map<String, Object> p = mcpCmd("");
        p.remove("mcp_tool");
        ScriptedHttpClient client = new ScriptedHttpClient();  // no responses scripted

        Map<String, Object> r = new McpExecutor(client).execute(p);

        assertEquals("error", r.get("status"));
        assertEquals("invalid_mcp_command", r.get("error"));
        assertEquals(0, client.sendCount());
    }

    @Test
    void stdioTransportRejectedForNow() {
        Map<String, Object> p = mcpCmd("get_roadmap");
        p.put("mcp_transport", "stdio");
        ScriptedHttpClient client = new ScriptedHttpClient();

        Map<String, Object> r = new McpExecutor(client).execute(p);

        assertEquals("error", r.get("status"));
        assertEquals("unsupported_mcp_transport", r.get("error"));
        assertEquals(0, client.sendCount());
    }

    @Test
    void missingServerUrlRejected() {
        Map<String, Object> p = mcpCmd("get_roadmap");
        p.remove("mcp_server_url");
        ScriptedHttpClient client = new ScriptedHttpClient();

        Map<String, Object> r = new McpExecutor(client).execute(p);

        assertEquals("error", r.get("status"));
        assertEquals("invalid_mcp_command", r.get("error"));
    }

    @Test
    void initializeFailureSurfaced() {
        String badInit = "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,"
            + "\"message\":\"unauthorized\"}}";
        ScriptedHttpClient client = new ScriptedHttpClient(
            resp(401, "application/json", null, badInit));

        Map<String, Object> r = new McpExecutor(client).execute(mcpCmd("get_roadmap"));

        assertEquals("error", r.get("status"));
        assertEquals("mcp_initialize_failed", r.get("error"));
    }

    @Test
    void transportExceptionSurfaced() {
        ScriptedHttpClient client = new ScriptedHttpClient() {
            @Override
            public <T> HttpResponse<T> send(HttpRequest request,
                    HttpResponse.BodyHandler<T> handler) {
                throw new RuntimeException(new java.io.IOException("Connection refused"));
            }
        };

        Map<String, Object> r = new McpExecutor(client).execute(mcpCmd("get_roadmap"));

        assertEquals("error", r.get("status"));
        assertEquals("mcp_transport_error", r.get("error"));
    }

    @Test
    void isMcpCommandKeyedOnToolPresence() {
        assertTrue(McpExecutor.isMcpCommand(Map.of("mcp_tool", "x")));
        assertFalse(McpExecutor.isMcpCommand(Map.of("mcp_tool", "  ")));
        assertFalse(McpExecutor.isMcpCommand(Map.of("operation", "read")));
        assertFalse(McpExecutor.isMcpCommand(null));
    }

    // ── scripted stub ────────────────────────────────────────────────

    private static Stub resp(int status, String contentType,
                             String sessionId, String body) {
        return new Stub(status, contentType, sessionId, body);
    }

    private record Stub(int status, String contentType, String sessionId, String body) {}

    /**
     * HttpClient that replays a fixed list of responses in order and
     * records the session-id / auth headers sent on each request, so the
     * handshake sequencing can be asserted.
     */
    private static class ScriptedHttpClient extends HttpClient {
        private final List<Stub> script;
        private int idx = 0;
        private final List<String> sentSessions = new ArrayList<>();
        private final List<String> sentAuths = new ArrayList<>();

        ScriptedHttpClient(Stub... stubs) {
            this.script = new ArrayList<>(Arrays.asList(stubs));
        }

        private final List<HttpRequest> sentRequests = new ArrayList<>();

        int sendCount() { return idx; }
        String sentSession(int i) { return sentSessions.get(i); }
        String sentAuth(int i) { return sentAuths.get(i); }
        String sentHeader(int i, String name) {
            return sentRequests.get(i).headers().firstValue(name).orElse(null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request,
                HttpResponse.BodyHandler<T> handler) {
            sentRequests.add(request);
            sentSessions.add(request.headers().firstValue("Mcp-Session-Id").orElse(null));
            sentAuths.add(request.headers().firstValue("Authorization").orElse(null));
            Stub s = script.get(idx++);
            Map<String, List<String>> hdrs = new HashMap<>();
            if (s.contentType() != null) {
                hdrs.put("Content-Type", List.of(s.contentType()));
            }
            if (s.sessionId() != null) {
                hdrs.put("Mcp-Session-Id", List.of(s.sessionId()));
            }
            return (HttpResponse<T>) new StubResponse(s.status(), s.body(), hdrs, request);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler) {
            return CompletableFuture.completedFuture(send(request, handler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> push) {
            return sendAsync(request, handler);
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
    }

    private static class StubResponse implements HttpResponse<String> {
        private final int status;
        private final String body;
        private final HttpHeaders headers;
        private final HttpRequest request;

        StubResponse(int status, String body, Map<String, List<String>> hdrs,
                     HttpRequest request) {
            this.status = status;
            this.body = body;
            this.headers = HttpHeaders.of(hdrs, (a, b) -> true);
            this.request = request;
        }

        @Override public int statusCode() { return status; }
        @Override public String body() { return body; }
        @Override public HttpRequest request() { return request; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return headers; }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
    }
}
