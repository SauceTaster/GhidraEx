package ex.ghidra.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Versioned loopback-only workbench API. It deliberately exposes viewport-sized
 * documents and event snapshots instead of chatty program-object RPC.
 */
public final class WorkbenchServer implements AutoCloseable {
    static final String ALLOWED_ORIGIN = "http://127.0.0.1:5173";
    private static final String API_PREFIX = "/api/v1";
    private final SyntheticEngine engine;
    private final HttpServer server;

    WorkbenchServer(int port, SyntheticEngine engine) throws IOException {
        this.engine = Objects.requireNonNull(engine);
        InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port);
        server = HttpServer.create(address, 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        route("/snapshot", "GET", exchange -> sendJson(exchange, 200, WorkbenchJson.snapshot(engine.snapshot())));
        route("/symbols", "GET", this::handleSymbols);
        route("/listing", "GET", this::handleListing);
        route("/analysis/start", "POST", exchange -> sendJson(exchange, 202, WorkbenchJson.analysis(engine.startAnalysis())));
        route("/analysis/cancel", "POST", exchange -> sendJson(exchange, 200, WorkbenchJson.analysis(engine.cancelAnalysis())));
        server.createContext(API_PREFIX + "/events", this::handleEvents);
    }

    public WorkbenchServer(int port) throws IOException {
        this(port, new SyntheticEngine());
    }

    public void start() {
        server.start();
    }

    int port() {
        return server.getAddress().getPort();
    }

    private void route(String path, String method, ExchangeAction action) {
        String exactPath = API_PREFIX + path;
        server.createContext(exactPath, exchange -> {
            if (!exactPath.equals(exchange.getRequestURI().getPath())) {
                sendJson(exchange, 404, WorkbenchJson.error("not_found", "No API route matches this path"));
                return;
            }
            if (!prepare(exchange, method)) return;
            try {
                action.handle(exchange);
            } catch (RuntimeException exception) {
                sendJson(exchange, 500, WorkbenchJson.error("internal_error", "The synthetic engine rejected the request"));
            }
        });
    }

    private void handleSymbols(HttpExchange exchange) throws IOException {
        String query = queryParameters(exchange.getRequestURI()).getOrDefault("q", "");
        if (query.length() > 128) {
            sendJson(exchange, 400, WorkbenchJson.error("invalid_query", "Symbol query exceeds 128 characters"));
            return;
        }
        sendJson(exchange, 200, WorkbenchJson.symbolSearch(query, engine.searchSymbols(query)));
    }

    private void handleListing(HttpExchange exchange) throws IOException {
        Map<String, String> query = queryParameters(exchange.getRequestURI());
        String address = query.getOrDefault("address", "0x004011d0");
        int rows;
        try {
            rows = Integer.parseInt(query.getOrDefault("rows", "50"));
        } catch (NumberFormatException ignored) {
            sendJson(exchange, 400, WorkbenchJson.error("invalid_rows", "Rows must be an integer"));
            return;
        }
        sendJson(exchange, 200, WorkbenchJson.listingWindow(engine.listingWindow(address, rows)));
    }

    private void handleEvents(HttpExchange exchange) throws IOException {
        String exactPath = API_PREFIX + "/events";
        if (!exactPath.equals(exchange.getRequestURI().getPath())) {
            sendJson(exchange, 404, WorkbenchJson.error("not_found", "No API route matches this path"));
            return;
        }
        if (!prepare(exchange, "GET")) return;

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-store, no-transform");
        headers.set("Connection", "keep-alive");
        headers.set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        BlockingQueue<SyntheticEngine.AnalysisState> events = new LinkedBlockingQueue<>(64);
        try (AutoCloseable ignored = engine.subscribe(state -> {
            if (!events.offer(state)) {
                events.poll();
                events.offer(state);
            }
        }); OutputStream output = exchange.getResponseBody()) {
            writeEvent(output, "engine", "{\"apiVersion\":1,\"status\":\"ready\"}");
            writeEvent(output, "analysis", WorkbenchJson.analysis(engine.analysisState()));
            while (!Thread.currentThread().isInterrupted()) {
                SyntheticEngine.AnalysisState state = events.poll(15, TimeUnit.SECONDS);
                if (state == null) {
                    output.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                } else {
                    writeEvent(output, "analysis", WorkbenchJson.analysis(state));
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception disconnected) {
            // Closing a browser tab normally tears down the response stream.
        } finally {
            exchange.close();
        }
    }

    private static void writeEvent(OutputStream output, String name, String json) throws IOException {
        output.write(("event: " + name + "\ndata: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private static boolean prepare(HttpExchange exchange, String expectedMethod) throws IOException {
        addSecurityHeaders(exchange);
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !ALLOWED_ORIGIN.equals(origin)) {
            sendJson(exchange, 403, WorkbenchJson.error("origin_denied", "This service accepts only the local workbench origin"));
            return false;
        }
        if (ALLOWED_ORIGIN.equals(origin)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", ALLOWED_ORIGIN);
        }
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", expectedMethod + ", OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            exchange.getResponseHeaders().set("Access-Control-Max-Age", "600");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return false;
        }
        if (!expectedMethod.equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", expectedMethod + ", OPTIONS");
            sendJson(exchange, 405, WorkbenchJson.error("method_not_allowed", "Expected " + expectedMethod));
            return false;
        }
        return true;
    }

    private static void addSecurityHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Vary", "Origin");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cross-Origin-Resource-Policy", "same-site");
        headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        } finally {
            exchange.close();
        }
    }

    static Map<String, String> queryParameters(URI uri) {
        Map<String, String> result = new LinkedHashMap<>();
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) return result;
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            result.putIfAbsent(key, value);
        }
        return result;
    }

    @Override
    public void close() {
        server.stop(0);
        engine.close();
    }

    public static void main(String[] args) throws Exception {
        int port = parsePort(args);
        WorkbenchServer service = new WorkbenchServer(port);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(service::close));
        service.start();
        System.out.printf("GhidraEx workbench API v1 listening on http://127.0.0.1:%d%n", service.port());
        System.out.printf("Allowed browser origin: %s%n", ALLOWED_ORIGIN);
    }

    private static int parsePort(String[] args) {
        int port = 18_787;
        for (String argument : args) {
            if (argument.startsWith("--port=")) {
                port = Integer.parseInt(argument.substring("--port=".length()));
            } else {
                throw new IllegalArgumentException("Unknown argument: " + argument);
            }
        }
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("Port must be between 1 and 65535");
        return port;
    }

    @FunctionalInterface
    private interface ExchangeAction {
        void handle(HttpExchange exchange) throws IOException;
    }
}
