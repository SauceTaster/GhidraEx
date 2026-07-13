package dev.ghidraex.engine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticAnalysisEngineTest {
    private final SyntheticAnalysisEngine engine = new SyntheticAnalysisEngine(Duration.ZERO);

    @Test
    void exposesAStableProgramAndBoundedListing() {
        var program = engine.programs().getFirst();
        var listing = engine.renderListing(program.id(), 0, 32);

        assertEquals(SyntheticAnalysisEngine.PROGRAM_ID, program.id());
        assertTrue(listing.contains("00401000"));
        assertTrue(listing.contains("entry:"));
        assertTrue(listing.lines().count() >= 32);
        assertThrows(IllegalArgumentException.class,
                () -> engine.renderListing("missing", 0, 1));
    }

    @Test
    void retrievesTheFarEndOfTheHundredThousandInstructionSpace() {
        assertEquals(100_000, engine.instructionCount(SyntheticAnalysisEngine.PROGRAM_ID));

        var finalPage = engine.listing(SyntheticAnalysisEngine.PROGRAM_ID, 99_984, 512);
        assertEquals(16, finalPage.size());
        assertEquals(99_984, finalPage.getFirst().ordinal());
        assertEquals(99_999, finalPage.getLast().ordinal());
        assertEquals(
                SyntheticAnalysisEngine.IMAGE_BASE + 99_999L * 4L,
                finalPage.getLast().address()
        );
        assertTrue(engine.listing(SyntheticAnalysisEngine.PROGRAM_ID, 100_000, 512).isEmpty());
    }

    @Test
    void symbolSearchMatchesUsefulAnalystFields() {
        var processFrame = engine.symbols(SyntheticAnalysisEngine.PROGRAM_ID).stream()
                .filter(symbol -> symbol.name().equals("process_frame"))
                .findFirst()
                .orElseThrow();

        assertTrue(SymbolMatcher.matches(processFrame, "PROCESS"));
        assertTrue(SymbolMatcher.matches(processFrame, "00401120"));
        assertTrue(SymbolMatcher.matches(processFrame, "function"));
        assertTrue(SymbolMatcher.matches(processFrame, "uint8_t"));
        assertTrue(SymbolMatcher.matches(processFrame, "  "));
        assertFalse(SymbolMatcher.matches(processFrame, "memcpy"));
    }

    @Test
    void analysisReportsMonotonicProgressAndReturnsImmutableResults() {
        var progress = new ArrayList<Double>();
        var result = engine.analyze(SyntheticAnalysisEngine.PROGRAM_ID, new AnalysisMonitor() {
            @Override
            public void checkCancelled() {
            }

            @Override
            public void report(double fraction, String phase, String detail) {
                progress.add(fraction);
                assertFalse(phase.isBlank());
                assertFalse(detail.isBlank());
            }
        });

        assertEquals(40, progress.size());
        assertEquals(1.0, progress.getLast());
        for (int index = 1; index < progress.size(); index++) {
            assertTrue(progress.get(index) >= progress.get(index - 1));
        }
        assertEquals(12, result.functionsDiscovered());
        assertEquals(19, result.symbols().size());
        assertThrows(UnsupportedOperationException.class,
                () -> result.symbols().add(engine.symbols(SyntheticAnalysisEngine.PROGRAM_ID).getFirst()));
    }

    @Test
    void analysisCooperatesWithCancellation() {
        assertThrows(SyntheticCancellation.class, () ->
                engine.analyze(SyntheticAnalysisEngine.PROGRAM_ID, new AnalysisMonitor() {
                    private double progress;

                    @Override
                    public void checkCancelled() {
                        if (progress >= 0.25) {
                            throw new SyntheticCancellation();
                        }
                    }

                    @Override
                    public void report(double fraction, String phase, String detail) {
                        progress = fraction;
                    }
                }));
    }

    private static final class SyntheticCancellation extends RuntimeException {
    }
}
