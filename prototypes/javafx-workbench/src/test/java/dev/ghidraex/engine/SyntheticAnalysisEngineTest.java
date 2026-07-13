package dev.ghidraex.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticAnalysisEngineTest {
    @Test
    void listingIsLargeDeterministicAndBoundsChecked() {
        try (var engine = new SyntheticAnalysisEngine()) {
            assertEquals(100_000, engine.listingSize());
            ListingWindow firstWindow = engine.listingWindow(4_321, 1);
            ListingWindow secondWindow = engine.listingWindow(4_321, 1);
            DisassemblyLine firstRead = firstWindow.rows().getFirst();
            DisassemblyLine secondRead = secondWindow.rows().getFirst();

            assertEquals(firstRead, secondRead);
            assertEquals(engine.program().imageBase() + 4_321L * 4, firstRead.address());
            assertFalse(firstRead.bytes().isBlank());
            assertEquals(512, engine.listingWindow(0, 20_000).rows().size());
            assertEquals(10, engine.listingWindow(99_990, 512).rows().size());
            assertTrue(engine.listingWindow(100_000, 10).rows().isEmpty());
            assertThrows(IndexOutOfBoundsException.class, () -> engine.listingWindow(-1, 1));
            assertThrows(IndexOutOfBoundsException.class, () -> engine.listingWindow(100_001, 1));
            assertThrows(IllegalArgumentException.class, () -> engine.listingWindow(0, 0));
        }
    }

    @Test
    void analysisReportsImmediatelyAndCanBeCancelled() throws Exception {
        try (var engine = new SyntheticAnalysisEngine()) {
            List<AnalysisProgress> updates = new ArrayList<>();
            AnalysisJob job = engine.startAnalysis(updates::add);

            assertEquals("Preparing analyzers", updates.getFirst().phase());
            job.cancel();
            job.completion().get(500, TimeUnit.MILLISECONDS);
            assertTrue(job.isCancelled());
        }
    }
}
