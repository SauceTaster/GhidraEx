package dev.ghidraex.fx;

import dev.ghidraex.engine.AnalysisEngine;
import dev.ghidraex.engine.DisassemblyLine;
import dev.ghidraex.engine.ListingWindow;
import javafx.collections.ObservableListBase;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Virtual-list adapter that converts random row reads into bounded engine pages.
 * It is intentionally UI-side so remote and in-process engines share one contract.
 */
final class PagedListing extends ObservableListBase<DisassemblyLine> {
    static final int DEFAULT_PAGE_SIZE = 256;
    static final int DEFAULT_MAX_CACHED_PAGES = 12;

    private final AnalysisEngine engine;
    private final int pageSize;
    private final Map<Integer, List<DisassemblyLine>> pages;

    PagedListing(AnalysisEngine engine) {
        this(engine, DEFAULT_PAGE_SIZE, DEFAULT_MAX_CACHED_PAGES);
    }

    PagedListing(AnalysisEngine engine, int pageSize, int maxCachedPages) {
        this.engine = Objects.requireNonNull(engine, "engine");
        if (pageSize <= 0 || maxCachedPages <= 0) {
            throw new IllegalArgumentException("Page and cache sizes must be positive");
        }
        this.pageSize = pageSize;
        this.pages = new LinkedHashMap<>(maxCachedPages + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, List<DisassemblyLine>> eldest) {
                return size() > maxCachedPages;
            }
        };
    }

    @Override
    public DisassemblyLine get(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException(index);
        }
        int pageStart = index - index % pageSize;
        List<DisassemblyLine> page = pages.computeIfAbsent(pageStart, this::fetchPage);
        int pageOffset = index - pageStart;
        if (pageOffset >= page.size()) {
            throw new IllegalStateException("Engine returned a truncated listing page");
        }
        return page.get(pageOffset);
    }

    @Override
    public int size() {
        return engine.listingSize();
    }

    int cachedPageCount() {
        return pages.size();
    }

    private List<DisassemblyLine> fetchPage(int pageStart) {
        ListingWindow window = engine.listingWindow(pageStart, pageSize);
        if (window.startIndex() != pageStart || window.totalInstructions() != size()) {
            throw new IllegalStateException("Engine returned inconsistent listing metadata");
        }
        return window.rows();
    }
}
