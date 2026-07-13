package ex.ghidra.web;

import org.junit.jupiter.api.Test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SyntheticEngineTest {
    @Test
    void returnsCoarseImmutableViewportData() {
        try (SyntheticEngine engine = new SyntheticEngine()) {
            var snapshot = engine.snapshot();
            assertEquals(1, snapshot.apiVersion());
            assertEquals("quartz-agent", snapshot.project().binary());
            assertEquals(100_000, snapshot.listingInfo().totalInstructions());
            assertEquals(50, snapshot.listing().size());
            assertEquals(50, snapshot.listingInfo().viewportRows());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.listing().clear());
            assertTrue(snapshot.decompiler().contains("transport_send"));
        }
    }

    @Test
    void generatesBoundedDeterministicWindowsAcrossOneHundredThousandInstructions() {
        try (SyntheticEngine engine = new SyntheticEngine()) {
            String farAddress = "0x%08x".formatted(SyntheticEngine.addressForOrdinal(90_000));
            var first = engine.listingWindow(farAddress, 37);
            var repeated = engine.listingWindow(farAddress, 37);

            assertEquals(100_000, first.totalInstructions());
            assertEquals(37, first.rows().size());
            assertEquals(first, repeated);
            assertTrue(first.rows().stream().anyMatch(row -> row.address().equals(farAddress)));
            assertEquals("0x%08x".formatted(SyntheticEngine.addressForOrdinal(0)), first.minAddress());
            assertEquals("0x%08x".formatted(SyntheticEngine.addressForOrdinal(99_999)), first.maxAddress());
        }
    }

    @Test
    void clampsViewportSizeAndOutOfRangeAddresses() {
        try (SyntheticEngine engine = new SyntheticEngine()) {
            var beforeStart = engine.listingWindow("0x00000000", 1);
            var afterEnd = engine.listingWindow("0xffffffffff", 500);

            assertEquals(8, beforeStart.rows().size());
            assertEquals(beforeStart.minAddress(), beforeStart.rows().getFirst().address());
            assertEquals(120, afterEnd.rows().size());
            assertEquals(afterEnd.maxAddress(), afterEnd.rows().getLast().address());
        }
    }

    @Test
    void searchesAcrossSymbolNameAndNamespace() {
        try (SyntheticEngine engine = new SyntheticEngine()) {
            assertEquals("transport_send", engine.searchSymbols("transport").getFirst().name());
            assertTrue(engine.searchSymbols("crypto").stream()
                    .anyMatch(symbol -> symbol.name().equals("rotate_key")));
            assertTrue(engine.searchSymbols("no-such-symbol").isEmpty());
        }
    }

    @Test
    void publishesAnalysisTransitions() throws Exception {
        try (SyntheticEngine engine = new SyntheticEngine()) {
            LinkedBlockingQueue<SyntheticEngine.AnalysisState> updates = new LinkedBlockingQueue<>();
            try (AutoCloseable ignored = engine.subscribe(updates::offer)) {
                var started = engine.startAnalysis();
                assertEquals("running", started.status());
                assertEquals("running", updates.poll(1, TimeUnit.SECONDS).status());
                var cancelled = engine.cancelAnalysis();
                assertEquals("cancelled", cancelled.status());
            }
        }
    }

    @Test
    void escapesJsonControlCharacters() {
        assertEquals("\"a\\\"b\\\\c\\n\"", WorkbenchJson.quote("a\"b\\c\n"));
    }
}
