package dev.ghidraex.fx;

import dev.ghidraex.engine.AnalysisEngine;
import dev.ghidraex.engine.Reference;
import dev.ghidraex.engine.Symbol;
import dev.ghidraex.viewstate.AsyncContextualReadController;
import dev.ghidraex.viewstate.ContextualReadSlot;
import dev.ghidraex.viewstate.ContextualReadSlot.Completeness;
import dev.ghidraex.viewstate.ContextualReadSlot.LoadedResult;
import dev.ghidraex.viewstate.ContextualReadSlot.Request;
import dev.ghidraex.viewstate.ContextualReadSlot.Snapshot;
import dev.ghidraex.viewstate.ViewStateReducer.ViewContext;
import javafx.application.Platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * JavaFX host adapter for bounded non-listing reads.
 *
 * <p>Controls submit immutable semantic queries. Engine calls and cache lookups run on a bounded
 * worker, while the shared contextual slot decides whether each echoed result still belongs to the
 * latest runtime, program, generation, and query.</p>
 */
final class AsyncEngineReads implements AutoCloseable {
    static final int MAX_SYMBOL_QUERY_CHARS = 256;
    static final int MAX_SYMBOL_RESULTS = 100;
    static final int MAX_SYMBOL_NAME_CHARS = 512;
    static final int MAX_SYMBOL_NAMESPACE_CHARS = 512;
    static final int MAX_SYMBOL_PAYLOAD_CHARS = 64 * 1024;
    static final int MAX_SYMBOL_CACHE_ENTRIES = 32;
    static final int MAX_DECOMPILER_SYMBOL_CHARS = 256;
    static final int MAX_DECOMPILER_LINES = 512;
    static final int MAX_SOURCE_LINE_CHARS = 16_384;
    static final int MAX_DECOMPILER_PAYLOAD_CHARS = 1024 * 1024;
    static final int MAX_DECOMPILER_CACHE_ENTRIES = 16;
    static final int MAX_REFERENCES = 64;
    static final int REFERENCE_ENTRY_WIRE_BYTES = 24;
    static final int MAX_REFERENCE_PAYLOAD_BYTES = MAX_REFERENCES * REFERENCE_ENTRY_WIRE_BYTES;
    static final int MAX_REFERENCE_CACHE_ENTRIES = 64;

    private static final int PROVIDER_THREADS = 3;
    private static final int MAX_QUEUED_READS = 8;
    private static final AtomicInteger WORKER_SEQUENCE = new AtomicInteger();

    record SymbolQuery(String text, int limit, boolean inputTruncated) {
        SymbolQuery {
            text = Objects.requireNonNull(text, "text");
            if (text.length() > MAX_SYMBOL_QUERY_CHARS) {
                throw new IllegalArgumentException("Symbol query exceeds the transport bound");
            }
            if (limit <= 0 || limit > MAX_SYMBOL_RESULTS) {
                throw new IllegalArgumentException("Symbol result limit is outside the UI bound");
            }
        }

        static SymbolQuery from(String raw) {
            String safe = Objects.requireNonNullElse(raw, "").strip();
            boolean truncated = safe.length() > MAX_SYMBOL_QUERY_CHARS;
            if (truncated) {
                safe = safe.substring(0, MAX_SYMBOL_QUERY_CHARS);
            }
            return new SymbolQuery(safe, MAX_SYMBOL_RESULTS, truncated);
        }
    }

    record SymbolSearchResult(List<Symbol> symbols) {
        SymbolSearchResult {
            symbols = List.copyOf(Objects.requireNonNull(symbols, "symbols"));
            if (symbols.size() > MAX_SYMBOL_RESULTS) {
                throw new IllegalArgumentException("Symbol payload exceeds the UI bound");
            }
            long aggregateChars = 0;
            for (Symbol symbol : symbols) {
                Objects.requireNonNull(symbol, "symbol");
                String name = Objects.requireNonNull(symbol.name(), "symbol.name()");
                String namespace = Objects.requireNonNull(symbol.namespace(), "symbol.namespace()");
                if (name.length() > MAX_SYMBOL_NAME_CHARS
                        || namespace.length() > MAX_SYMBOL_NAMESPACE_CHARS) {
                    throw new IllegalArgumentException("Symbol field exceeds the transport text bound");
                }
                aggregateChars += (long) name.length() + namespace.length();
                if (aggregateChars > MAX_SYMBOL_PAYLOAD_CHARS) {
                    throw new IllegalArgumentException("Symbol payload exceeds the aggregate text bound");
                }
            }
        }
    }

    record DecompilerQuery(String symbol, int maxLines, boolean inputTruncated) {
        DecompilerQuery {
            symbol = requireText(symbol, "symbol");
            if (symbol.length() > MAX_DECOMPILER_SYMBOL_CHARS) {
                throw new IllegalArgumentException("Decompiler symbol exceeds the transport bound");
            }
            if (maxLines <= 0 || maxLines > MAX_DECOMPILER_LINES) {
                throw new IllegalArgumentException("Decompiler line limit is outside the UI bound");
            }
        }

        static DecompilerQuery from(String raw) {
            String safe = Objects.requireNonNullElse(raw, "").strip();
            if (safe.isEmpty()) {
                safe = "parse_packet";
            }
            boolean truncated = safe.length() > MAX_DECOMPILER_SYMBOL_CHARS;
            if (truncated) {
                safe = safe.substring(0, MAX_DECOMPILER_SYMBOL_CHARS);
            }
            return new DecompilerQuery(safe, MAX_DECOMPILER_LINES, truncated);
        }
    }

    record SourceLine(int number, String source) {
        SourceLine {
            if (number <= 0) {
                throw new IllegalArgumentException("Source line number must be positive");
            }
            source = Objects.requireNonNull(source, "source");
            if (source.length() > MAX_SOURCE_LINE_CHARS) {
                throw new IllegalArgumentException("Source line exceeds the UI bound");
            }
        }
    }

    record DecompiledFunction(String symbol, List<SourceLine> lines) {
        DecompiledFunction {
            symbol = requireText(symbol, "symbol");
            if (symbol.length() > MAX_DECOMPILER_SYMBOL_CHARS) {
                throw new IllegalArgumentException("Decompiler result symbol exceeds the text bound");
            }
            lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
            if (lines.size() > MAX_DECOMPILER_LINES) {
                throw new IllegalArgumentException("Decompiler payload exceeds the UI bound");
            }
            long aggregateChars = symbol.length();
            for (SourceLine line : lines) {
                aggregateChars += Objects.requireNonNull(line, "source line").source().length();
                if (aggregateChars > MAX_DECOMPILER_PAYLOAD_CHARS) {
                    throw new IllegalArgumentException("Decompiler payload exceeds the aggregate text bound");
                }
            }
        }
    }

    record ReferenceQuery(long address, int limit) {
        ReferenceQuery {
            if (limit <= 0 || limit > MAX_REFERENCES) {
                throw new IllegalArgumentException("Reference limit is outside the UI bound");
            }
        }

        static ReferenceQuery at(long address) {
            return new ReferenceQuery(address, MAX_REFERENCES);
        }
    }

    enum ReferenceDirection {
        FROM,
        TO
    }

    record ReferenceEntry(ReferenceDirection direction, long address, Reference.Kind kind) {
        ReferenceEntry {
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(kind, "kind");
        }
    }

    record ReferenceSet(long address, List<ReferenceEntry> references) {
        ReferenceSet {
            references = List.copyOf(Objects.requireNonNull(references, "references"));
            if (references.size() > MAX_REFERENCES) {
                throw new IllegalArgumentException("Reference payload exceeds the UI bound");
            }
            long estimatedBytes = (long) references.size() * REFERENCE_ENTRY_WIRE_BYTES;
            if (estimatedBytes > MAX_REFERENCE_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("Reference payload exceeds the aggregate wire bound");
            }
        }
    }

    final class ReadHandle<Q, R> implements AutoCloseable {
        private final AsyncContextualReadController<Q, R> controller;
        private final ContextualReadSlot<Q, R> slot;
        private final AtomicBoolean handleClosed = new AtomicBoolean();
        private volatile Q lastQuery;

        private ReadHandle(
                AsyncContextualReadController<Q, R> controller,
                ContextualReadSlot<Q, R> slot) {
            this.controller = controller;
            this.slot = slot;
        }

        Request<Q> request(Q query) {
            ensureHandleOpen();
            lastQuery = Objects.requireNonNull(query, "query");
            return controller.request(query);
        }

        /** UI-safe submission: a resync quarantine updates native state instead of throwing. */
        boolean submit(Q query) {
            ensureHandleOpen();
            lastQuery = Objects.requireNonNull(query, "query");
            if (resyncRequired) {
                controller.contextChanged();
                return false;
            }
            controller.request(query);
            return true;
        }

        void refresh() {
            ensureHandleOpen();
            Q query = lastQuery;
            if (resyncRequired) {
                controller.contextChanged();
            } else if (query != null) {
                controller.request(query);
            }
        }

        Snapshot<Q, R> snapshot() {
            return slot.snapshot();
        }

        private void contextChangedAndRefresh() {
            if (handleClosed.get()) {
                return;
            }
            Q query = lastQuery;
            controller.contextChanged();
            if (query != null && !resyncRequired) {
                controller.request(query);
            }
        }

        @Override
        public void close() {
            if (handleClosed.compareAndSet(false, true)) {
                controller.close();
                handles.remove(this);
            }
        }

        private void ensureHandleOpen() {
            if (handleClosed.get()) {
                throw new IllegalStateException("Read handle is closed");
            }
        }
    }

    private record CacheKey<Q>(ViewContext context, Q query) {
        private CacheKey {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(query, "query");
        }
    }

    private record CachedResult<R>(R value, Completeness completeness, List<String> warnings) {
        private CachedResult {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(completeness, "completeness");
            warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private final AnalysisEngine engine;
    private final ExecutorService worker;
    private final AsyncContextualReadController.UiDispatcher uiDispatcher;
    private final Runnable closeWorker;
    private final CopyOnWriteArrayList<ReadHandle<?, ?>> handles = new CopyOnWriteArrayList<>();
    private final BoundedResultCache<CacheKey<SymbolQuery>, CachedResult<SymbolSearchResult>> symbolCache =
            new BoundedResultCache<>(MAX_SYMBOL_CACHE_ENTRIES);
    private final BoundedResultCache<CacheKey<DecompilerQuery>, CachedResult<DecompiledFunction>> decompilerCache =
            new BoundedResultCache<>(MAX_DECOMPILER_CACHE_ENTRIES);
    private final BoundedResultCache<CacheKey<ReferenceQuery>, CachedResult<ReferenceSet>> referenceCache =
            new BoundedResultCache<>(MAX_REFERENCE_CACHE_ENTRIES);
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ViewContext context;
    private volatile boolean resyncRequired;

    static AsyncEngineReads createFx(AnalysisEngine engine, EngineSessionSnapshot session) {
        ThreadFactory threads = task -> {
            Thread thread = new Thread(task, "fx-context-read-" + WORKER_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService worker = newBoundedWorker(threads);
        return new AsyncEngineReads(engine, session.context(), worker, Platform::runLater, worker::shutdownNow);
    }

    static ExecutorService newBoundedWorker(ThreadFactory threads) {
        return new PurgingThreadPoolExecutor(
                PROVIDER_THREADS,
                new ArrayBlockingQueue<>(MAX_QUEUED_READS),
                Objects.requireNonNull(threads, "threads"));
    }

    AsyncEngineReads(
            AnalysisEngine engine,
            ViewContext initialContext,
            ExecutorService worker,
            AsyncContextualReadController.UiDispatcher uiDispatcher,
            Runnable closeWorker) {
        this.engine = Objects.requireNonNull(engine, "engine");
        context = Objects.requireNonNull(initialContext, "initialContext");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.uiDispatcher = Objects.requireNonNull(uiDispatcher, "uiDispatcher");
        this.closeWorker = Objects.requireNonNull(closeWorker, "closeWorker");
    }

    ReadHandle<SymbolQuery, SymbolSearchResult> openSymbolSearch(
            Consumer<Snapshot<SymbolQuery, SymbolSearchResult>> observer) {
        return open(
                "symbol-search",
                AsyncEngineReads::copySymbolSearchResult,
                request -> loadCached("symbol-search", request, symbolCache, () -> loadSymbols(request.query())),
                observer);
    }

    ReadHandle<DecompilerQuery, DecompiledFunction> openDecompiler(
            Consumer<Snapshot<DecompilerQuery, DecompiledFunction>> observer) {
        return open(
                "decompiler",
                AsyncEngineReads::copyDecompiledFunction,
                request -> loadCached("decompiler", request, decompilerCache,
                        () -> loadDecompiler(request.query())),
                observer);
    }

    ReadHandle<ReferenceQuery, ReferenceSet> openReferences(
            Consumer<Snapshot<ReferenceQuery, ReferenceSet>> observer) {
        return open(
                "references",
                AsyncEngineReads::copyReferenceSet,
                request -> loadCached("references", request, referenceCache,
                        () -> loadReferences(request.query())),
                observer);
    }

    void contentGenerationAdvanced() {
        ensureOpen();
        ViewContext previous = context;
        context = new ViewContext(
                previous.runtimeId(),
                previous.runtimeEpoch(),
                previous.programId(),
                previous.contentGeneration() + 1);
        resyncRequired = false;
        clearCaches();
        handles.forEach(ReadHandle::contextChangedAndRefresh);
    }

    void applyValidatedContext(ViewContext nextContext, boolean requiresResync, boolean refresh) {
        ensureOpen();
        context = Objects.requireNonNull(nextContext, "nextContext");
        resyncRequired = requiresResync;
        clearCaches();
        for (ReadHandle<?, ?> handle : handles) {
            if (refresh) {
                handle.contextChangedAndRefresh();
            } else {
                handle.controller.contextChanged();
            }
        }
    }

    ViewContext context() {
        return context;
    }

    int symbolCacheSize() {
        return symbolCache.size();
    }

    int decompilerCacheSize() {
        return decompilerCache.size();
    }

    int referenceCacheSize() {
        return referenceCache.size();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            List.copyOf(handles).forEach(ReadHandle::close);
            clearCaches();
            closeWorker.run();
        }
    }

    private <Q, R> ReadHandle<Q, R> open(
            String slotId,
            UnaryOperator<R> copyAndValidate,
            AsyncContextualReadController.Loader<Q, R> loader,
            Consumer<Snapshot<Q, R>> observer) {
        ensureOpen();
        ContextualReadSlot<Q, R> slot = new ContextualReadSlot<>(slotId, context, copyAndValidate);
        AsyncContextualReadController<Q, R> controller = new AsyncContextualReadController<>(
                slot,
                this::context,
                () -> resyncRequired,
                loader,
                worker,
                uiDispatcher,
                Objects.requireNonNull(observer, "observer"));
        ReadHandle<Q, R> handle = new ReadHandle<>(controller, slot);
        handles.add(handle);
        if (closed.get()) {
            handle.close();
            throw new IllegalStateException("Async engine reads are closed");
        }
        return handle;
    }

    private CachedResult<SymbolSearchResult> loadSymbols(SymbolQuery query) {
        int providerLimit = Math.min(MAX_SYMBOL_RESULTS + 1, query.limit() + 1);
        List<Symbol> provided = Objects.requireNonNull(
                engine.searchSymbols(query.text(), providerLimit), "engine.searchSymbols()");
        boolean truncated = query.inputTruncated() || provided.size() > query.limit();
        List<Symbol> bounded = List.copyOf(provided.subList(0, Math.min(query.limit(), provided.size())));
        List<String> warnings = warnings(
                query.inputTruncated() ? "Search text was capped at " + MAX_SYMBOL_QUERY_CHARS + " characters" : null,
                provided.size() > query.limit() ? "More symbols exist; showing the first " + query.limit() : null);
        return new CachedResult<>(
                new SymbolSearchResult(bounded),
                truncated ? Completeness.TRUNCATED : Completeness.COMPLETE,
                warnings);
    }

    private CachedResult<DecompiledFunction> loadDecompiler(DecompilerQuery query) {
        int providerLimit = query.maxLines() + 1;
        List<String> provided = Objects.requireNonNull(
                engine.decompile(query.symbol(), providerLimit), "engine.decompile()");
        int count = Math.min(query.maxLines(), provided.size());
        boolean longLine = false;
        boolean aggregateTruncated = false;
        int aggregateChars = query.symbol().length();
        List<SourceLine> lines = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String source = Objects.requireNonNull(provided.get(index), "Decompiler line");
            if (source.length() > MAX_SOURCE_LINE_CHARS) {
                source = source.substring(0, MAX_SOURCE_LINE_CHARS);
                longLine = true;
            }
            int remaining = MAX_DECOMPILER_PAYLOAD_CHARS - aggregateChars;
            if (remaining <= 0) {
                aggregateTruncated = true;
                break;
            }
            if (source.length() > remaining) {
                source = source.substring(0, remaining);
                aggregateTruncated = true;
            }
            lines.add(new SourceLine(index + 1, source));
            aggregateChars += source.length();
            if (aggregateTruncated) {
                break;
            }
        }
        boolean truncated = query.inputTruncated()
                || provided.size() > query.maxLines()
                || longLine
                || aggregateTruncated;
        List<String> warnings = warnings(
                query.inputTruncated()
                        ? "Function name was capped at " + MAX_DECOMPILER_SYMBOL_CHARS + " characters"
                        : null,
                provided.size() > query.maxLines()
                        ? "Decompiler output was capped at " + query.maxLines() + " lines"
                        : null,
                longLine ? "One or more source lines were capped" : null,
                aggregateTruncated ? "Decompiler text reached the aggregate payload budget" : null);
        return new CachedResult<>(
                new DecompiledFunction(query.symbol(), lines),
                truncated ? Completeness.TRUNCATED : Completeness.COMPLETE,
                warnings);
    }

    private CachedResult<ReferenceSet> loadReferences(ReferenceQuery query) {
        int providerLimit = Math.min(MAX_REFERENCES + 1, query.limit() + 1);
        List<Reference> provided = Objects.requireNonNull(
                engine.references(query.address(), providerLimit), "engine.references()");
        boolean truncated = provided.size() > query.limit();
        List<Reference> bounded = List.copyOf(
                provided.subList(0, Math.min(query.limit(), provided.size())));
        List<ReferenceEntry> entries = bounded.stream().map(reference -> {
            if (reference.toAddress() == query.address()) {
                return new ReferenceEntry(ReferenceDirection.FROM, reference.fromAddress(), reference.kind());
            }
            if (reference.fromAddress() == query.address()) {
                return new ReferenceEntry(ReferenceDirection.TO, reference.toAddress(), reference.kind());
            }
            throw new IllegalArgumentException("Reference provider returned an unrelated edge");
        }).toList();
        return new CachedResult<>(
                new ReferenceSet(query.address(), entries),
                truncated ? Completeness.TRUNCATED : Completeness.COMPLETE,
                truncated ? List.of("More references exist; showing the first " + query.limit()) : List.of());
    }

    private <Q, R> LoadedResult<Q, R> loadCached(
            String slotId,
            Request<Q> request,
            BoundedResultCache<CacheKey<Q>, CachedResult<R>> cache,
            CheckedSupplier<CachedResult<R>> provider) throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Read was cancelled before provider entry");
        }
        CacheKey<Q> key = new CacheKey<>(request.context(), request.query());
        CachedResult<R> cached = cache.get(key).orElse(null);
        if (cached == null) {
            cached = Objects.requireNonNull(provider.get(), "provider result");
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Read was cancelled after provider completion");
            }
            cache.put(key, cached);
        }
        String resultId = slotId + ":" + request.context().runtimeEpoch() + ":"
                + request.context().contentGeneration() + ":" + request.requestId();
        return new LoadedResult<>(
                request.context(),
                request.query(),
                resultId,
                cached.value(),
                cached.completeness(),
                cached.warnings());
    }

    private static SymbolSearchResult copySymbolSearchResult(SymbolSearchResult result) {
        return new SymbolSearchResult(result.symbols());
    }

    private static DecompiledFunction copyDecompiledFunction(DecompiledFunction result) {
        return new DecompiledFunction(result.symbol(), result.lines());
    }

    private static ReferenceSet copyReferenceSet(ReferenceSet result) {
        return new ReferenceSet(result.address(), result.references());
    }

    private void clearCaches() {
        symbolCache.clear();
        decompilerCache.clear();
        referenceCache.clear();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Async engine reads are closed");
        }
    }

    private static List<String> warnings(String... candidates) {
        List<String> warnings = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                warnings.add(candidate);
            }
        }
        return List.copyOf(warnings);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** Removes cancelled per-slot futures before admitting the replacement latest request. */
    private static final class PurgingThreadPoolExecutor extends ThreadPoolExecutor {
        private PurgingThreadPoolExecutor(
                int threads,
                ArrayBlockingQueue<Runnable> queue,
                ThreadFactory threadFactory) {
            super(
                    threads,
                    threads,
                    0,
                    TimeUnit.MILLISECONDS,
                    queue,
                    threadFactory,
                    new AbortPolicy());
        }

        @Override
        public void execute(Runnable command) {
            purge();
            super.execute(command);
        }
    }
}
