package dev.ghidraex.fx;

import dev.ghidraex.engine.AnalysisEngine;
import dev.ghidraex.engine.AnalysisJob;
import dev.ghidraex.engine.AnalysisProgress;
import dev.ghidraex.engine.ListingWindow;
import dev.ghidraex.engine.ProgramInfo;
import dev.ghidraex.engine.Symbol;
import dev.ghidraex.engine.SyntheticAnalysisEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PagedListingTest {
    @Test
    void repeatedRowsUseOneBoundedEnginePage() {
        try (var engine = new CountingEngine()) {
            PagedListing listing = new PagedListing(engine, 256, 3);

            listing.get(0);
            listing.get(1);
            listing.get(255);
            assertEquals(1, engine.windowRequests());

            listing.get(256);
            listing.get(0);
            assertEquals(2, engine.windowRequests());
            assertEquals(2, listing.cachedPageCount());
        }
    }

    @Test
    void accessOrderEvictsLeastRecentlyUsedPage() {
        try (var engine = new CountingEngine()) {
            PagedListing listing = new PagedListing(engine, 4, 2);

            listing.get(0);  // page 0
            listing.get(4);  // page 1
            listing.get(0);  // page 0 becomes most recently used
            listing.get(8);  // evicts page 1
            listing.get(4);  // fetches page 1 again

            assertEquals(4, engine.windowRequests());
            assertEquals(2, listing.cachedPageCount());
        }
    }

    private static final class CountingEngine implements AnalysisEngine {
        private final SyntheticAnalysisEngine delegate = new SyntheticAnalysisEngine();
        private final AtomicInteger windowRequests = new AtomicInteger();

        int windowRequests() {
            return windowRequests.get();
        }

        @Override
        public ProgramInfo program() {
            return delegate.program();
        }

        @Override
        public int listingSize() {
            return delegate.listingSize();
        }

        @Override
        public ListingWindow listingWindow(int startIndex, int requestedCount) {
            windowRequests.incrementAndGet();
            return delegate.listingWindow(startIndex, requestedCount);
        }

        @Override
        public List<Symbol> searchSymbols(String query, int limit) {
            return delegate.searchSymbols(query, limit);
        }

        @Override
        public List<String> decompile(String symbolName) {
            return delegate.decompile(symbolName);
        }

        @Override
        public AnalysisJob startAnalysis(Consumer<AnalysisProgress> progressListener) {
            return delegate.startAnalysis(progressListener);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
