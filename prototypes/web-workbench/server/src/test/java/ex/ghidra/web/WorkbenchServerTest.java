package ex.ghidra.web;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class WorkbenchServerTest {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Test
    void servesVersionedSnapshotOnLoopback() throws Exception {
        try (SyntheticEngine engine = new SyntheticEngine(); WorkbenchServer server = new WorkbenchServer(0, engine)) {
            server.start();
            HttpRequest request = HttpRequest.newBuilder(endpoint(server, "/api/v1/snapshot"))
                    .header("Origin", WorkbenchServer.ALLOWED_ORIGIN)
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(WorkbenchServer.ALLOWED_ORIGIN,
                    response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
            assertTrue(response.body().contains("\"apiVersion\":1"));
            assertTrue(response.body().contains("\"quartz-agent\""));
            assertTrue(response.body().contains("\"totalInstructions\":100000"));
            String farAddress = "0x%08x".formatted(SyntheticEngine.addressForOrdinal(90_000));
            assertFalse(response.body().contains(farAddress), "snapshot must remain viewport-sized");
        }
    }

    @Test
    void retrievesFarListingViewportWithoutSerializingTheProgram() throws Exception {
        try (SyntheticEngine engine = new SyntheticEngine(); WorkbenchServer server = new WorkbenchServer(0, engine)) {
            server.start();
            String farAddress = "0x%08x".formatted(SyntheticEngine.addressForOrdinal(90_000));
            HttpRequest request = HttpRequest.newBuilder(endpoint(server,
                            "/api/v1/listing?address=" + farAddress + "&rows=32"))
                    .header("Origin", WorkbenchServer.ALLOWED_ORIGIN)
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"totalInstructions\":100000"));
            assertTrue(response.body().contains(farAddress));
            assertEquals(32, response.body().split("\\\"address\\\":", -1).length - 1);
            assertTrue(response.body().length() < 20_000, "viewport response should remain bounded");
        }
    }

    @Test
    void rejectsUntrustedBrowserOrigin() throws Exception {
        try (SyntheticEngine engine = new SyntheticEngine(); WorkbenchServer server = new WorkbenchServer(0, engine)) {
            server.start();
            HttpRequest request = HttpRequest.newBuilder(endpoint(server, "/api/v1/analysis/start"))
                    .header("Origin", "https://attacker.example")
                    .timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(403, response.statusCode());
            assertTrue(response.body().contains("origin_denied"));
        }
    }

    @Test
    void streamsNamedSseEventsImmediately() throws Exception {
        try (SyntheticEngine engine = new SyntheticEngine(); WorkbenchServer server = new WorkbenchServer(0, engine)) {
            server.start();
            HttpRequest request = HttpRequest.newBuilder(endpoint(server, "/api/v1/events"))
                    .header("Origin", WorkbenchServer.ALLOWED_ORIGIN)
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            try (InputStream stream = response.body()) {
                String prefix = new String(stream.readNBytes(80));
                assertTrue(prefix.contains("event: engine"));
                assertTrue(prefix.contains("apiVersion"));
            }
        }
    }

    @Test
    void decodesQueryParametersWithoutThirdPartyRouting() {
        var values = WorkbenchServer.queryParameters(URI.create("http://localhost/api?q=rotate%20key&rows=12"));
        assertEquals("rotate key", values.get("q"));
        assertEquals("12", values.get("rows"));
    }

    private static URI endpoint(WorkbenchServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }
}
