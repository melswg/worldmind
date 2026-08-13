package io.github.melswg.worldmind.testkit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loopback-only Chat Completions fake for transport contract tests. Captured
 * request data intentionally never exposes an authorization value.
 */
public final class FakeOpenAiCompatibleHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final CompletableFuture<CapturedRequest> receivedRequest = new CompletableFuture<>();
    private final BlockingQueue<CapturedRequest> receivedRequests = new LinkedBlockingQueue<>();
    private final AtomicReference<Response> response = new AtomicReference<>(new Response(200, "{}", Map.of(), false));
    private final BlockingQueue<Response> scriptedResponses = new LinkedBlockingQueue<>();
    private final AtomicReference<CountDownLatch> responseGate = new AtomicReference<>(new CountDownLatch(0));
    private volatile String expectedAuthorization;

    public FakeOpenAiCompatibleHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    public URI endpoint(String pathAndQuery) {
        Objects.requireNonNull(pathAndQuery, "pathAndQuery");
        if (!pathAndQuery.startsWith("/")) {
            throw new IllegalArgumentException("pathAndQuery must start with '/'.");
        }
        String host = server.getAddress().getAddress().getHostAddress();
        String uriHost = host.contains(":") ? "[" + host + "]" : host;
        return URI.create("http://" + uriHost + ":" + server.getAddress().getPort() + pathAndQuery);
    }

    /** Stores an expected synthetic value without making it observable to tests. */
    public void expectBearerCredential(String credential) {
        expectedAuthorization = "Bearer " + Objects.requireNonNull(credential, "credential");
    }

    public void respondWith(int statusCode, String body) {
        response.set(new Response(statusCode, Objects.requireNonNull(body, "body"), Map.of(), false));
    }

    /** Queues one synthetic response for deterministic retry and error contracts. */
    public void enqueueResponse(int statusCode, String body, Map<String, String> headers) {
        scriptedResponses.add(new Response(statusCode, Objects.requireNonNull(body, "body"), Map.copyOf(headers), false));
    }

    /** Makes one accepted request fail at the transport boundary without exposing a body. */
    public void enqueueConnectionClose() {
        scriptedResponses.add(new Response(0, "", Map.of(), true));
    }

    public void holdResponses() {
        responseGate.set(new CountDownLatch(1));
    }

    public void releaseResponses() {
        responseGate.get().countDown();
    }

    public CapturedRequest awaitRequest(Duration timeout) {
        try {
            return receivedRequest.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a loopback request.", failure);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("Loopback request was not received in time.", failure);
        }
    }

    public boolean hasReceivedRequest() {
        return receivedRequest.isDone();
    }

    /** Waits for the next request when an acceptance scenario intentionally makes several provider calls. */
    public CapturedRequest awaitNextRequest(Duration timeout) {
        try {
            CapturedRequest request = receivedRequests.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (request == null) throw new TimeoutException("No loopback request arrived before the timeout.");
            return request;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a loopback request.", failure);
        } catch (TimeoutException failure) {
            throw new IllegalStateException("Loopback request was not received in time.", failure);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        boolean authorizationMatchesExpected = expectedAuthorization != null && expectedAuthorization.equals(authorization);
        CapturedRequest captured = new CapturedRequest(
            exchange.getRequestMethod(),
            exchange.getRequestURI(),
            exchange.getRequestHeaders().getFirst("Content-Type"),
            exchange.getRequestHeaders().getFirst("Accept"),
            authorization != null,
            authorizationMatchesExpected,
            exchange.getRequestHeaders().keySet().stream().map(String::toLowerCase).collect(java.util.stream.Collectors.toUnmodifiableSet()),
            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
        );
        receivedRequest.complete(captured);
        receivedRequests.add(captured);

        try {
            responseGate.get().await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            exchange.close();
            return;
        }

        Response configuredResponse = scriptedResponses.poll();
        if (configuredResponse == null) configuredResponse = response.get();
        if (configuredResponse.closeConnection()) {
            exchange.close();
            return;
        }
        byte[] responseBody = configuredResponse.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        configuredResponse.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        exchange.sendResponseHeaders(configuredResponse.statusCode(), responseBody.length);
        exchange.getResponseBody().write(responseBody);
        exchange.close();
    }

    @Override
    public void close() {
        releaseResponses();
        server.stop(0);
    }

    /** Safe inspection surface: the authorization value is deliberately omitted. */
    public record CapturedRequest(
        String method,
        URI requestUri,
        String contentType,
        String accept,
        boolean authorizationPresent,
        boolean authorizationMatchesExpected,
        Set<String> headerNames,
        String body
    ) {
    }

    private record Response(int statusCode, String body, Map<String, String> headers, boolean closeConnection) {
    }
}
