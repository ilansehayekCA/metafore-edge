package com.metafore.edge.route;

import com.metafore.edge.config.EdgeConfig;
import com.metafore.edge.topic.TopicBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
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

class RestAdapterRouteTest {

    private static EdgeConfig config;
    private static TopicBuilder topics;

    @BeforeAll
    static void setUp() {
        config = EdgeConfig.from(Map.of(
            "CONTROLLER_ID", "edge-core-banking",
            "TENANT_ID", "maybank-001"
        ));
        topics = new TopicBuilder("maybank-001", "edge-core-banking");
    }

    @Test
    void successfulPostReturns200() {
        HttpClient mockClient = new StubHttpClient(200, "{\"id\":\"INC001\"}");
        RestAdapterRoute route = new RestAdapterRoute(config, topics, mockClient);

        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("action_id", "wb-001");
        cmd.put("method", "POST");
        cmd.put("url", "https://servicenow.example.com/api/table/incident");
        cmd.put("headers", Map.of("Authorization", "Bearer tok123"));
        cmd.put("body", Map.of("short_description", "Server down"));
        cmd.put("old_values", Map.of("state", "open"));
        cmd.put("new_values", Map.of("state", "resolved"));

        Map<String, Object> result = route.executeHttpCall(cmd);

        assertEquals("success", result.get("status"));
        assertEquals(200, result.get("http_status"));
        assertEquals("{\"id\":\"INC001\"}", result.get("response_body"));
        assertEquals(Map.of("state", "open"), result.get("old_values"));
        assertEquals(Map.of("state", "resolved"), result.get("new_values"));
        assertEquals("wb-001", result.get("action_id"));
    }

    @Test
    void getRequestReturnsData() {
        HttpClient mockClient = new StubHttpClient(200, "[{\"id\":1}]");
        RestAdapterRoute route = new RestAdapterRoute(config, topics, mockClient);

        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("action_id", "wb-002");
        cmd.put("method", "GET");
        cmd.put("url", "https://api.example.com/items");

        Map<String, Object> result = route.executeHttpCall(cmd);

        assertEquals("success", result.get("status"));
        assertEquals(200, result.get("http_status"));
        assertEquals("[{\"id\":1}]", result.get("response_body"));
    }

    @Test
    void http401ReturnsError() {
        HttpClient mockClient = new StubHttpClient(401, "Unauthorized");
        RestAdapterRoute route = new RestAdapterRoute(config, topics, mockClient);

        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("action_id", "wb-003");
        cmd.put("method", "PATCH");
        cmd.put("url", "https://api.example.com/items/1");
        cmd.put("body", Map.of("status", "closed"));

        Map<String, Object> result = route.executeHttpCall(cmd);

        assertEquals("error", result.get("status"));
        assertEquals(401, result.get("http_status"));
        assertEquals("Unauthorized", result.get("response_body"));
    }

    @Test
    void http500ReturnsError() {
        HttpClient mockClient = new StubHttpClient(500, "Internal Server Error");
        RestAdapterRoute route = new RestAdapterRoute(config, topics, mockClient);

        Map<String, Object> cmd = Map.of(
            "action_id", "wb-004",
            "method", "DELETE",
            "url", "https://api.example.com/items/1"
        );

        Map<String, Object> result = route.executeHttpCall(cmd);

        assertEquals("error", result.get("status"));
        assertEquals(500, result.get("http_status"));
    }

    @Test
    void missingUrlReturnsError() {
        HttpClient mockClient = new StubHttpClient(200, "");
        RestAdapterRoute route = new RestAdapterRoute(config, topics, mockClient);

        Map<String, Object> cmd = Map.of(
            "action_id", "wb-005",
            "method", "GET"
        );

        Map<String, Object> result = route.executeHttpCall(cmd);

        assertEquals("error", result.get("status"));
        assertEquals(0, result.get("http_status"));
        assertTrue(((String) result.get("response_body")).contains("url"));
    }

    @Test
    void unsupportedMethodReturnsError() {
        HttpClient mockClient = new StubHttpClient(200, "");
        RestAdapterRoute route = new RestAdapterRoute(config, topics, mockClient);

        Map<String, Object> cmd = Map.of(
            "action_id", "wb-006",
            "method", "OPTIONS",
            "url", "https://api.example.com/"
        );

        Map<String, Object> result = route.executeHttpCall(cmd);

        assertEquals("error", result.get("status"));
        assertEquals(0, result.get("http_status"));
        assertTrue(((String) result.get("response_body")).contains("Unsupported"));
    }

    @Test
    void connectionFailureReturnsError() {
        HttpClient failClient = new FailingHttpClient();
        RestAdapterRoute route = new RestAdapterRoute(config, topics, failClient);

        Map<String, Object> cmd = Map.of(
            "action_id", "wb-007",
            "method", "GET",
            "url", "https://unreachable.example.com/api"
        );

        Map<String, Object> result = route.executeHttpCall(cmd);

        assertEquals("error", result.get("status"));
        assertEquals(0, result.get("http_status"));
        assertTrue(((String) result.get("response_body")).contains("Exception"));
    }

    @Test
    void methodIsCaseInsensitive() {
        HttpClient mockClient = new StubHttpClient(204, "");
        RestAdapterRoute route = new RestAdapterRoute(config, topics, mockClient);

        Map<String, Object> cmd = Map.of(
            "action_id", "wb-008",
            "method", "delete",
            "url", "https://api.example.com/items/1"
        );

        Map<String, Object> result = route.executeHttpCall(cmd);

        assertEquals("success", result.get("status"));
        assertEquals(204, result.get("http_status"));
    }

    @Test
    void putMethodSupported() {
        HttpClient mockClient = new StubHttpClient(200, "{\"updated\":true}");
        RestAdapterRoute route = new RestAdapterRoute(config, topics, mockClient);

        Map<String, Object> cmd = Map.of(
            "action_id", "wb-009",
            "method", "PUT",
            "url", "https://api.example.com/items/1",
            "body", Map.of("name", "updated")
        );

        Map<String, Object> result = route.executeHttpCall(cmd);

        assertEquals("success", result.get("status"));
        assertEquals(200, result.get("http_status"));
    }

    // ---- Stub HTTP client for testing ----

    /**
     * Minimal HttpClient stub that returns a fixed status code and body
     * without making any real network calls.
     */
    private static class StubHttpClient extends HttpClient {
        private final int statusCode;
        private final String body;

        StubHttpClient(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            return new StubHttpResponse<>(statusCode, (T) body, request);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            return CompletableFuture.completedFuture(send(request, responseBodyHandler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
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

    private static class StubHttpResponse<T> implements HttpResponse<T> {
        private final int statusCode;
        private final T body;
        private final HttpRequest request;

        StubHttpResponse(int statusCode, T body, HttpRequest request) {
            this.statusCode = statusCode;
            this.body = body;
            this.request = request;
        }

        @Override public int statusCode() { return statusCode; }
        @Override public T body() { return body; }
        @Override public HttpRequest request() { return request; }
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
        @Override public URI uri() { return request.uri(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
    }

    /** HttpClient that always throws IOException on send. */
    private static class FailingHttpClient extends StubHttpClient {
        FailingHttpClient() { super(0, ""); }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException {
            throw new IOException("Connection refused");
        }
    }
}
