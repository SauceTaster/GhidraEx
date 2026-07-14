package dev.ghidraex.intellij.session;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.LightVirtualFile;
import dev.ghidraex.backend.BackendProbe;
import dev.ghidraex.backend.PluginIntegrationCatalog;
import dev.ghidraex.backend.RuntimeCapabilityCatalog;
import dev.ghidraex.debug.DebuggerModel;
import dev.ghidraex.engine.AnalysisEngine;
import dev.ghidraex.engine.AnalysisMonitor;
import dev.ghidraex.engine.AnalysisResult;
import dev.ghidraex.engine.ProgramDescriptor;
import dev.ghidraex.engine.SymbolDescriptor;
import dev.ghidraex.engine.SymbolMatcher;
import dev.ghidraex.engine.SyntheticAnalysisEngine;
import dev.ghidraex.intellij.editor.ListingSemantics;
import dev.ghidraex.intellij.editor.VirtualizedListingFile;
import dev.ghidraex.intellij.read.IntelliJContextualReadProjection;
import dev.ghidraex.intellij.read.LatestOnlyExecutorService;
import dev.ghidraex.intellij.read.ReadModels;
import dev.ghidraex.script.ReplModel;
import dev.ghidraex.viewstate.ContextualReadSlot;
import dev.ghidraex.viewstate.ViewStateReducer;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.ghidraex.intellij.read.ReadModels.MAX_DECOMPILER_CHARS;
import static dev.ghidraex.intellij.read.ReadModels.MAX_PROGRAMS;
import static dev.ghidraex.intellij.read.ReadModels.MAX_REFERENCES;
import static dev.ghidraex.intellij.read.ReadModels.MAX_SYMBOLS;

/**
 * Project-scoped state and native async read projections.
 *
 * <p>UI clients read immutable snapshots only. Engine, filesystem discovery, filtering, decompiler,
 * and inspector providers are entered exclusively by their dedicated bounded workers, and accepted
 * state reaches Swing through one IntelliJ EDT dispatcher.</p>
 */
public final class WorkbenchSession implements Disposable {
    private static final BackendProbe.Status LOADING_BACKEND = new BackendProbe.Status(
            BackendProbe.DiscoveryState.NOT_CONFIGURED,
            "SyntheticAnalysisEngine",
            "probe pending",
            null,
            null,
            "pending",
            "Backend discovery is loading on its bounded runtime worker."
    );

    public record AnalysisRequest(long requestId, ViewStateReducer.ViewContext context) {
        public AnalysisRequest {
            if (requestId <= 0) {
                throw new IllegalArgumentException("analysis request ID must be positive");
            }
            Objects.requireNonNull(context, "context");
        }
    }

    private final Project project;
    private final AnalysisEngine engine;
    private final ProgramDescriptor program;
    private final VirtualizedListingFile listingFile;
    private final LightVirtualFile decompilerFile;
    private final DebuggerModel debuggerModel;
    private final ReplModel replModel;
    private final IntelliJContextualReadProjection<ReadModels.ProgramQuery, ReadModels.ProgramCatalog>
            programsRead;
    private final IntelliJContextualReadProjection<ReadModels.SymbolQuery, ReadModels.SymbolCatalog>
            symbolsRead;
    private final IntelliJContextualReadProjection<ReadModels.DecompilerQuery, ReadModels.DecompilerDocument>
            decompilerRead;
    private final IntelliJContextualReadProjection<ReadModels.InspectorQuery, ReadModels.InspectorDetails>
            inspectorRead;
    private final IntelliJContextualReadProjection<ReadModels.ReferenceQuery, ReadModels.ReferenceSet>
            referencesRead;
    private final IntelliJContextualReadProjection<ReadModels.RuntimeQuery, ReadModels.RuntimeDetails>
            runtimeRead;
    private final AtomicBoolean disposed = new AtomicBoolean();

    private volatile ViewStateReducer.ViewContext context;
    private volatile boolean resyncRequired;
    private volatile List<SymbolDescriptor> analyzedSymbols;
    private volatile List<SymbolDescriptor> knownSymbols = List.of(SyntheticAnalysisEngine.entrySymbol());
    private volatile ContextualReadSlot.Snapshot<ReadModels.ProgramQuery, ReadModels.ProgramCatalog>
            programsSnapshot;
    private volatile ContextualReadSlot.Snapshot<ReadModels.SymbolQuery, ReadModels.SymbolCatalog>
            symbolsSnapshot;
    private volatile ContextualReadSlot.Snapshot<ReadModels.DecompilerQuery, ReadModels.DecompilerDocument>
            decompilerSnapshot;
    private volatile ContextualReadSlot.Snapshot<ReadModels.InspectorQuery, ReadModels.InspectorDetails>
            inspectorSnapshot;
    private volatile ContextualReadSlot.Snapshot<ReadModels.ReferenceQuery, ReadModels.ReferenceSet>
            referencesSnapshot;
    private volatile ContextualReadSlot.Snapshot<ReadModels.RuntimeQuery, ReadModels.RuntimeDetails>
            runtimeSnapshot;
    private volatile SymbolDescriptor selectedSymbol;
    private volatile AnalysisResult lastAnalysis;
    private volatile DebuggerModel.Snapshot debuggerSnapshot;
    private long nextAnalysisRequestId;
    private long activeAnalysisRequestId;

    public WorkbenchSession(Project project) {
        this.project = Objects.requireNonNull(project, "project");
        engine = SyntheticAnalysisEngine.interactive();
        // Synthetic bootstrap identity is configuration, not a provider read. A real adapter must
        // obtain this from its validated attach snapshot before constructing resource-bound files.
        program = SyntheticAnalysisEngine.demoProgram();
        selectedSymbol = SyntheticAnalysisEngine.entrySymbol();
        context = new ViewStateReducer.ViewContext(
                "intellij-project-" + project.getLocationHash(),
                1,
                program.id(),
                1
        );
        listingFile = new VirtualizedListingFile(
                program.name() + ".listing",
                context,
                ListingSemantics.instructionLocation(program.imageBase())
        );
        decompilerFile = readOnlyFile(
                program.name() + ".decompiled.c",
                "// LOADING · bounded decompiler read has not completed yet\n"
        );
        debuggerModel = new DebuggerModel(
                program.imageBase(),
                program.imageBase() + program.byteSize(),
                program.name()
        );
        for (SymbolDescriptor symbol : List.of(
                new SymbolDescriptor("process_frame", 0x0040_1120L,
                        SymbolDescriptor.SymbolKind.FUNCTION, 18,
                        "int process_frame(uint8_t *frame, size_t len)"),
                new SymbolDescriptor("crc16", 0x0040_1240L,
                        SymbolDescriptor.SymbolKind.FUNCTION, 11,
                        "uint16_t crc16(const uint8_t *data, size_t len)"))) {
            debuggerModel.addBreakpoint(symbol.address(), symbol.name());
        }
        debuggerSnapshot = debuggerModel.snapshot();
        replModel = new ReplModel();

        var ui = new SessionUiAccess(project);
        programsRead = new IntelliJContextualReadProjection<>(
                "programs",
                context,
                value -> new ReadModels.ProgramCatalog(value.programs()),
                this::context,
                this::resyncRequired,
                request -> loadPrograms(request),
                new LatestOnlyExecutorService("programs"),
                ui,
                this::applyPrograms);
        symbolsRead = new IntelliJContextualReadProjection<>(
                "symbols",
                context,
                value -> new ReadModels.SymbolCatalog(value.query(), value.symbols()),
                this::context,
                this::resyncRequired,
                request -> loadSymbols(request),
                new LatestOnlyExecutorService("symbols"),
                ui,
                this::applySymbols);
        decompilerRead = new IntelliJContextualReadProjection<>(
                "decompiler",
                context,
                value -> new ReadModels.DecompilerDocument(value.functionAddress(), value.text()),
                this::context,
                this::resyncRequired,
                request -> loadDecompiler(request),
                new LatestOnlyExecutorService("decompiler"),
                ui,
                this::applyDecompiler);
        inspectorRead = new IntelliJContextualReadProjection<>(
                "inspector",
                context,
                value -> new ReadModels.InspectorDetails(
                        value.symbol(), value.programName(), value.analysisSummary()),
                this::context,
                this::resyncRequired,
                request -> loadInspector(request),
                new LatestOnlyExecutorService("inspector"),
                ui,
                this::applyInspector);
        referencesRead = new IntelliJContextualReadProjection<>(
                "references",
                context,
                value -> new ReadModels.ReferenceSet(
                        value.symbol(), value.references(), value.totalAvailable()),
                this::context,
                this::resyncRequired,
                request -> loadReferences(request),
                new LatestOnlyExecutorService("references"),
                ui,
                this::applyReferences);
        runtimeRead = new IntelliJContextualReadProjection<>(
                "runtime",
                context,
                value -> new ReadModels.RuntimeDetails(
                        value.backend(), value.capabilities(), value.integrations()),
                this::context,
                this::resyncRequired,
                request -> loadRuntime(request),
                new LatestOnlyExecutorService("runtime"),
                ui,
                this::applyRuntime);

        programsSnapshot = programsRead.snapshot();
        symbolsSnapshot = symbolsRead.snapshot();
        decompilerSnapshot = decompilerRead.snapshot();
        inspectorSnapshot = inspectorRead.snapshot();
        referencesSnapshot = referencesRead.snapshot();
        runtimeSnapshot = runtimeRead.snapshot();

        Runnable startReads = this::requestInitialReads;
        if (ui.isDispatchThread()) {
            startReads.run();
        } else {
            ui.dispatch(startReads);
        }
    }

    public static WorkbenchSession getInstance(Project project) {
        return project.getService(WorkbenchSession.class);
    }

    public ViewStateReducer.ViewContext context() {
        return context;
    }

    public boolean resyncRequired() {
        return resyncRequired;
    }

    /** Worker-only provider access retained for the editor listing adapter. */
    public AnalysisEngine engine() {
        return engine;
    }

    public ProgramDescriptor program() {
        return program;
    }

    public ContextualReadSlot.Snapshot<ReadModels.ProgramQuery, ReadModels.ProgramCatalog>
            programsRead() {
        return programsSnapshot;
    }

    public ContextualReadSlot.Snapshot<ReadModels.SymbolQuery, ReadModels.SymbolCatalog>
            symbolsRead() {
        return symbolsSnapshot;
    }

    public ContextualReadSlot.Snapshot<ReadModels.DecompilerQuery, ReadModels.DecompilerDocument>
            decompilerRead() {
        return decompilerSnapshot;
    }

    public ContextualReadSlot.Snapshot<ReadModels.InspectorQuery, ReadModels.InspectorDetails>
            inspectorRead() {
        return inspectorSnapshot;
    }

    public ContextualReadSlot.Snapshot<ReadModels.ReferenceQuery, ReadModels.ReferenceSet>
            referencesRead() {
        return referencesSnapshot;
    }

    public ContextualReadSlot.Snapshot<ReadModels.RuntimeQuery, ReadModels.RuntimeDetails>
            runtimeRead() {
        return runtimeSnapshot;
    }

    public List<SymbolDescriptor> symbols() {
        return knownSymbols;
    }

    public SymbolDescriptor selectedSymbol() {
        return selectedSymbol;
    }

    public AnalysisResult lastAnalysis() {
        return lastAnalysis;
    }

    public VirtualizedListingFile listingFile() {
        return listingFile;
    }

    public LightVirtualFile decompilerFile() {
        return decompilerFile;
    }

    public DebuggerModel.Snapshot debugger() {
        return debuggerSnapshot;
    }

    public BackendProbe.Status backendStatus() {
        var displayed = runtimeSnapshot.displayed();
        return displayed == null ? LOADING_BACKEND : displayed.value().backend();
    }

    public List<ReplModel.Entry> replHistory() {
        return replModel.history();
    }

    public void requestSymbols(String query) {
        requireUiThread();
        symbolsRead.request(new ReadModels.SymbolQuery(query, MAX_SYMBOLS));
    }

    public void selectSymbol(SymbolDescriptor symbol) {
        requireUiThread();
        SymbolDescriptor next = Objects.requireNonNull(symbol, "symbol");
        if (next.equals(selectedSymbol)) {
            return;
        }
        selectedSymbol = next;
        requestSelectedReads();
        publishChange();
    }

    public void selectSymbolAtAddress(long address) {
        requireUiThread();
        knownSymbols.stream()
                .filter(symbol -> symbol.address() == address)
                .findFirst()
                .ifPresent(this::selectSymbol);
    }

    public AnalysisRequest beginAnalysis() {
        requireUiThread();
        AnalysisRequest request = new AnalysisRequest(++nextAnalysisRequestId, context);
        activeAnalysisRequestId = request.requestId();
        return request;
    }

    public AnalysisResult analyze(AnalysisRequest request, AnalysisMonitor monitor) {
        Objects.requireNonNull(request, "request");
        return engine.analyze(request.context().programId(), Objects.requireNonNull(monitor, "monitor"));
    }

    public boolean applyAnalysis(AnalysisRequest request, AnalysisResult result) {
        requireUiThread();
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        if (request.requestId() != activeAnalysisRequestId || !request.context().equals(context)) {
            return false;
        }
        activeAnalysisRequestId = 0;
        lastAnalysis = result;
        analyzedSymbols = result.symbols();
        knownSymbols = result.symbols();
        context = new ViewStateReducer.ViewContext(
                context.runtimeId(),
                context.runtimeEpoch(),
                context.programId(),
                context.contentGeneration() + 1);
        reconcileReadContext();
        programsRead.request(new ReadModels.ProgramQuery(MAX_PROGRAMS));
        requestSymbols(currentSymbolQuery());
        requestSelectedReads();
        runtimeRead.request(new ReadModels.RuntimeQuery(projectSearchPath()));
        publishChange();
        return true;
    }

    public void requireResync() {
        requireUiThread();
        resyncRequired = true;
        reconcileReadContext();
    }

    public void applyValidatedContext(ViewStateReducer.ViewContext validatedContext) {
        requireUiThread();
        ViewStateReducer.ViewContext next = Objects.requireNonNull(validatedContext, "validatedContext");
        validateContextTransition(context, next);
        context = next;
        resyncRequired = false;
        reconcileReadContext();
        if (program.id().equals(context.programId())) {
            requestInitialReads();
        }
    }

    public void startDebugger() {
        debuggerModel.start();
        debuggerChanged();
    }

    public void resumeDebugger() {
        debuggerModel.resume();
        debuggerChanged();
    }

    public void pauseDebugger() {
        debuggerModel.pause();
        debuggerChanged();
    }

    public void stepDebuggerInto() {
        debuggerModel.stepInto();
        debuggerChanged();
    }

    public void stepDebuggerOver() {
        debuggerModel.stepOver();
        debuggerChanged();
    }

    public void stepDebuggerOut() {
        debuggerModel.stepOut();
        debuggerChanged();
    }

    public void stopDebugger() {
        debuggerModel.stop();
        debuggerChanged();
    }

    public void toggleBreakpointAtSelectedSymbol() {
        debuggerModel.toggleBreakpoint(selectedSymbol.address(), selectedSymbol.name());
        debuggerChanged();
    }

    public void setBreakpointEnabled(long address, boolean enabled) {
        debuggerModel.setBreakpointEnabled(address, enabled);
        debuggerChanged();
    }

    public ReplModel.Entry evaluateRepl(ReplModel.Language language, String command) {
        var result = replModel.evaluate(language, command, new ReplModel.Context(
                program,
                symbols(),
                selectedSymbol,
                debuggerSnapshot,
                backendStatus()
        ));
        publishChange();
        return result;
    }

    public void clearReplHistory() {
        replModel.clear();
        publishChange();
    }

    public ContextualReadSlot.Request<ReadModels.RuntimeQuery> refreshBackendStatus() {
        requireUiThread();
        return runtimeRead.request(new ReadModels.RuntimeQuery(projectSearchPath()));
    }

    @Override
    public void dispose() {
        if (disposed.compareAndSet(false, true)) {
            programsRead.close();
            symbolsRead.close();
            decompilerRead.close();
            inspectorRead.close();
            referencesRead.close();
            runtimeRead.close();
        }
    }

    private void requestInitialReads() {
        if (disposed.get() || project.isDisposed()) {
            return;
        }
        programsRead.request(new ReadModels.ProgramQuery(MAX_PROGRAMS));
        symbolsRead.request(new ReadModels.SymbolQuery("", MAX_SYMBOLS));
        requestSelectedReads();
        runtimeRead.request(new ReadModels.RuntimeQuery(projectSearchPath()));
    }

    private void requestSelectedReads() {
        if (resyncRequired || disposed.get()) {
            return;
        }
        decompilerRead.request(new ReadModels.DecompilerQuery(
                selectedSymbol.address(), MAX_DECOMPILER_CHARS));
        inspectorRead.request(new ReadModels.InspectorQuery(
                selectedSymbol, program, analysisSummary()));
        referencesRead.request(new ReadModels.ReferenceQuery(selectedSymbol, MAX_REFERENCES));
    }

    private void reconcileReadContext() {
        programsRead.contextChanged();
        symbolsRead.contextChanged();
        decompilerRead.contextChanged();
        inspectorRead.contextChanged();
        referencesRead.contextChanged();
        runtimeRead.contextChanged();
    }

    private ContextualReadSlot.LoadedResult<ReadModels.ProgramQuery, ReadModels.ProgramCatalog>
            loadPrograms(ContextualReadSlot.Request<ReadModels.ProgramQuery> request) {
        List<ProgramDescriptor> all = engine.programs();
        int end = Math.min(all.size(), request.query().limit());
        boolean truncated = end < all.size();
        return new ContextualReadSlot.LoadedResult<>(
                request.context(),
                request.query(),
                "programs-" + request.requestId(),
                new ReadModels.ProgramCatalog(all.subList(0, end)),
                truncated ? ContextualReadSlot.Completeness.TRUNCATED
                        : ContextualReadSlot.Completeness.COMPLETE,
                truncated ? List.of("Program catalog truncated to " + end + " rows") : List.of());
    }

    private ContextualReadSlot.LoadedResult<ReadModels.SymbolQuery, ReadModels.SymbolCatalog>
            loadSymbols(ContextualReadSlot.Request<ReadModels.SymbolQuery> request) {
        List<SymbolDescriptor> source = analyzedSymbols;
        if (source == null) {
            source = engine.symbols(request.context().programId());
        }
        List<SymbolDescriptor> matches = source.stream()
                .filter(symbol -> SymbolMatcher.matches(symbol, request.query().text()))
                .toList();
        int end = Math.min(matches.size(), request.query().limit());
        boolean truncated = end < matches.size();
        return new ContextualReadSlot.LoadedResult<>(
                request.context(),
                request.query(),
                "symbols-" + request.requestId(),
                new ReadModels.SymbolCatalog(request.query().text(), matches.subList(0, end)),
                truncated ? ContextualReadSlot.Completeness.TRUNCATED
                        : ContextualReadSlot.Completeness.COMPLETE,
                truncated ? List.of("Symbol results truncated to " + end + " rows") : List.of());
    }

    private ContextualReadSlot.LoadedResult<ReadModels.DecompilerQuery, ReadModels.DecompilerDocument>
            loadDecompiler(ContextualReadSlot.Request<ReadModels.DecompilerQuery> request) {
        String full = engine.decompile(request.context().programId(), request.query().functionAddress());
        boolean truncated = full.length() > request.query().maxCharacters();
        String bounded = truncated ? full.substring(0, request.query().maxCharacters()) : full;
        return new ContextualReadSlot.LoadedResult<>(
                request.context(),
                request.query(),
                "decompiler-" + request.requestId(),
                new ReadModels.DecompilerDocument(request.query().functionAddress(), bounded),
                truncated ? ContextualReadSlot.Completeness.TRUNCATED
                        : ContextualReadSlot.Completeness.COMPLETE,
                truncated ? List.of("Decompiler text reached the character budget") : List.of());
    }

    private ContextualReadSlot.LoadedResult<ReadModels.InspectorQuery, ReadModels.InspectorDetails>
            loadInspector(ContextualReadSlot.Request<ReadModels.InspectorQuery> request) {
        ReadModels.InspectorQuery query = request.query();
        return new ContextualReadSlot.LoadedResult<>(
                request.context(),
                query,
                "inspector-" + request.requestId(),
                new ReadModels.InspectorDetails(
                        query.symbol(), query.program().name(), query.analysisSummary()),
                ContextualReadSlot.Completeness.COMPLETE,
                List.of());
    }

    private ContextualReadSlot.LoadedResult<ReadModels.ReferenceQuery, ReadModels.ReferenceSet>
            loadReferences(ContextualReadSlot.Request<ReadModels.ReferenceQuery> request) {
        SymbolDescriptor symbol = request.query().symbol();
        int total = Math.max(0, symbol.referenceCount());
        int count = Math.min(total, request.query().limit());
        List<ReadModels.ReferenceRow> rows = java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> {
                    long from = program.imageBase() + 0x80L + index * 0x34L
                            + Math.floorMod(symbol.address() - program.imageBase(), 0x40L);
                    String kind = switch (index % 3) {
                        case 0 -> "CALL";
                        case 1 -> "READ";
                        default -> "DATA";
                    };
                    return new ReadModels.ReferenceRow(
                            request.context().programId() + ":xref:" + symbol.name() + ":" + index,
                            from,
                            symbol.address(),
                            kind);
                })
                .toList();
        boolean truncated = count < total;
        return new ContextualReadSlot.LoadedResult<>(
                request.context(),
                request.query(),
                "references-" + request.requestId(),
                new ReadModels.ReferenceSet(symbol, rows, total),
                truncated ? ContextualReadSlot.Completeness.TRUNCATED
                        : ContextualReadSlot.Completeness.COMPLETE,
                truncated ? List.of("References truncated to " + count + " rows") : List.of());
    }

    private ContextualReadSlot.LoadedResult<ReadModels.RuntimeQuery, ReadModels.RuntimeDetails>
            loadRuntime(ContextualReadSlot.Request<ReadModels.RuntimeQuery> request) {
        BackendProbe.Status backend = BackendProbe.discover(request.query().searchFrom());
        return new ContextualReadSlot.LoadedResult<>(
                request.context(),
                request.query(),
                "runtime-" + request.requestId(),
                new ReadModels.RuntimeDetails(
                        backend,
                        RuntimeCapabilityCatalog.forStatus(backend),
                        PluginIntegrationCatalog.forStatus(backend)),
                ContextualReadSlot.Completeness.COMPLETE,
                List.of());
    }

    private void applyPrograms(
            ContextualReadSlot.Snapshot<ReadModels.ProgramQuery, ReadModels.ProgramCatalog> snapshot) {
        programsSnapshot = snapshot;
        publishChange();
    }

    private void applySymbols(
            ContextualReadSlot.Snapshot<ReadModels.SymbolQuery, ReadModels.SymbolCatalog> snapshot) {
        symbolsSnapshot = snapshot;
        if (snapshot.displayed() != null
                && snapshot.displayed().query().text().isEmpty()
                && snapshot.displayed().context().equals(context)) {
            knownSymbols = snapshot.displayed().value().symbols();
        }
        publishChange();
    }

    private void applyDecompiler(
            ContextualReadSlot.Snapshot<ReadModels.DecompilerQuery, ReadModels.DecompilerDocument> snapshot) {
        decompilerSnapshot = snapshot;
        StringBuilder content = new StringBuilder()
                .append("// ").append(snapshot.freshness())
                .append(" · generation ").append(snapshot.context().contentGeneration())
                .append(" · ").append(snapshot.detail()).append('\n')
                .append("// Bounded to ").append(MAX_DECOMPILER_CHARS).append(" characters\n\n");
        if (snapshot.displayed() != null) {
            content.append(snapshot.displayed().value().text());
        }
        String acceptedContent = content.toString();
        ApplicationManager.getApplication().runWriteAction(() -> {
            // The editor must remain read-only to analysts, but LightVirtualFile also enforces
            // that flag against its owning projection. Open one tightly scoped write window for
            // the accepted async snapshot and restore the public read-only contract immediately.
            decompilerFile.setWritable(true);
            try {
                decompilerFile.setContent(this, acceptedContent, true);
            } finally {
                decompilerFile.setWritable(false);
            }
        });
        publishChange();
    }

    private void applyInspector(
            ContextualReadSlot.Snapshot<ReadModels.InspectorQuery, ReadModels.InspectorDetails> snapshot) {
        inspectorSnapshot = snapshot;
        publishChange();
    }

    private void applyReferences(
            ContextualReadSlot.Snapshot<ReadModels.ReferenceQuery, ReadModels.ReferenceSet> snapshot) {
        referencesSnapshot = snapshot;
        publishChange();
    }

    private void applyRuntime(
            ContextualReadSlot.Snapshot<ReadModels.RuntimeQuery, ReadModels.RuntimeDetails> snapshot) {
        runtimeSnapshot = snapshot;
        publishChange();
    }

    private void debuggerChanged() {
        debuggerSnapshot = debuggerModel.snapshot();
        publishChange();
    }

    private String currentSymbolQuery() {
        var pending = symbolsSnapshot.pending();
        if (pending != null) {
            return pending.query().text();
        }
        var displayed = symbolsSnapshot.displayed();
        return displayed == null ? "" : displayed.query().text();
    }

    private String analysisSummary() {
        AnalysisResult analysis = lastAnalysis;
        return analysis == null
                ? "Baseline synthetic metadata"
                : analysis.functionsDiscovered() + " functions discovered · "
                + analysis.referencesRecovered() + " references recovered";
    }

    private Path projectSearchPath() {
        String basePath = project.getBasePath();
        return basePath == null ? Path.of(System.getProperty("user.dir", ".")) : Path.of(basePath);
    }

    private void publishChange() {
        if (!disposed.get() && !project.isDisposed()) {
            project.getMessageBus().syncPublisher(SessionListener.TOPIC).sessionChanged(this);
        }
    }

    private void requireUiThread() {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            throw new IllegalStateException("Workbench session mutations must run on the IntelliJ EDT");
        }
    }

    private static void validateContextTransition(
            ViewStateReducer.ViewContext current,
            ViewStateReducer.ViewContext next) {
        boolean sameRuntimeId = current.runtimeId().equals(next.runtimeId());
        if (sameRuntimeId && next.runtimeEpoch() < current.runtimeEpoch()) {
            throw new IllegalArgumentException("runtime epoch cannot move backward");
        }
        boolean sameRuntimeIncarnation = sameRuntimeId
                && current.runtimeEpoch() == next.runtimeEpoch();
        if (sameRuntimeIncarnation
                && current.programId().equals(next.programId())
                && next.contentGeneration() < current.contentGeneration()) {
            throw new IllegalArgumentException("content generation cannot move backward");
        }
    }

    private static LightVirtualFile readOnlyFile(String name, String content) {
        var file = new LightVirtualFile(name, PlainTextFileType.INSTANCE, content);
        file.setWritable(false);
        return file;
    }

    private record SessionUiAccess(Project project) implements IntelliJContextualReadProjection.UiAccess {
        @Override
        public void dispatch(Runnable update) {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) {
                    update.run();
                }
            });
        }

        @Override
        public boolean isDispatchThread() {
            return ApplicationManager.getApplication().isDispatchThread();
        }
    }
}
