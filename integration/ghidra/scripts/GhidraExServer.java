// Persistent, bounded GhidraEx engine worker for analyzeHeadless.
// @category GhidraEx
// @menupath
// @toolbar

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.decompiler.DecompiledFunction;
import ghidra.app.util.headless.HeadlessScript;
import ghidra.framework.Application;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.CodeUnit;
import ghidra.program.model.listing.CodeUnitIterator;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;
import ghidra.program.model.symbol.SymbolType;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitorAdapter;

/**
 * A process-local, read-only Ghidra engine session.
 *
 * <p>The script deliberately blocks as a post-script.  HeadlessAnalyzer retains ownership of
 * {@code currentProgram} until {@link #run()} returns, so this class never releases or closes the
 * Program.  All Ghidra work is serialized on one worker.  The script thread only reads control
 * frames, which lets request cancellation and graceful shutdown remain responsive while the native
 * decompiler is busy.</p>
 */
public class GhidraExServer extends HeadlessScript {
    private static final int PROTOCOL_VERSION = 1;
    private static final String FRAME_PREFIX = "GHIDRAEX/1 ";
    private static final int MIN_FRAME_BYTES = 65_536;
    private static final int MAX_SUPPORTED_FRAME_BYTES = 2_097_152;
    private static final int MAX_JSON_DEPTH = 32;
    private static final int MAX_JSON_OBJECT_FIELDS = 128;
    private static final int MAX_JSON_ARRAY_ITEMS = 2_000;
    private static final int MAX_TIMEOUT_MS = 30_000;
    private static final int DEFAULT_TIMEOUT_MS = 5_000;
    private static final int MAX_LISTING_ITEMS = 1_000;
    private static final int DEFAULT_LISTING_ITEMS = 128;
    private static final int MAX_SYMBOL_ITEMS = 500;
    private static final int DEFAULT_SYMBOL_ITEMS = 100;
    private static final int MAX_REFERENCE_ITEMS = 2_000;
    private static final int DEFAULT_REFERENCE_ITEMS = 256;
    private static final int MAX_DECOMPILE_CHARS = 500_000;
    private static final int DEFAULT_DECOMPILE_CHARS = 200_000;
    private static final int MAX_CODE_UNIT_BYTES = 131_072;
    private static final int MAX_SYMBOL_SCAN = 1_000_000;
    private static final int MAX_REFERENCE_SCAN = 250_000;
    private static final int WORK_QUEUE_CAPACITY = 32;
    private static final int COMPLETED_ID_CAPACITY = 2_048;
    private static final int MAX_PROTOCOL_ERRORS = 8;
    private static final String SERVER_NAME = "ghidraex-engine";
    private static final String SERVER_VERSION = "0.1.0";

    private static final Pattern NONCE_PATTERN = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern REQUEST_ID_PATTERN =
        Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern METHOD_PATTERN =
        Pattern.compile("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+$");
    private static final Set<String> PROGRAM_METHODS = Set.of(
        "program.summary",
        "listing.window",
        "symbols.search",
        "references.list",
        "decompile.function"
    );
    private static final Request POISON = Request.poison();

    private final BlockingQueue<Request> workQueue =
        new ArrayBlockingQueue<>(WORK_QUEUE_CAPACITY);
    private final ConcurrentMap<String, Request> pending = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicReference<ActiveRequest> active = new AtomicReference<>();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean negotiated = new AtomicBoolean();
    private final AtomicLong eventSequence = new AtomicLong();
    private final AtomicLong invalidRequestSequence = new AtomicLong();
    private final long startedNanos = System.nanoTime();
    private final CountDownLatch workerStarted = new CountDownLatch(1);
    private final Object outputLock = new Object();
    private final Object completedLock = new Object();
    private final LinkedHashMap<String, Boolean> completedIds = new LinkedHashMap<>();

    private volatile DecompInterface decompiler;
    private volatile boolean decompilerAvailable;
    private volatile String decompilerUnavailableReason;
    private volatile long instructionCount;
    private volatile Throwable workerStartupFailure;
    private volatile boolean outputBroken;

    private String wirePrefix;
    private int maxFrameBytes;
    private String sessionId;
    private String programId;
    private String programSha256;
    private Thread workerThread;

    @Override
    protected void run() throws Exception {
        if (currentProgram == null) {
            throw new IllegalStateException("GhidraExServer requires a current program");
        }

        String[] arguments = getScriptArgs();
        if (arguments.length != 2 || !NONCE_PATTERN.matcher(arguments[0]).matches()) {
            throw new IllegalArgumentException(
                "GhidraExServer requires a framing nonce and maxFrameBytes"
            );
        }

        try {
            maxFrameBytes = Integer.parseInt(arguments[1]);
        }
        catch (NumberFormatException error) {
            throw new IllegalArgumentException("maxFrameBytes must be an integer", error);
        }
        if (maxFrameBytes < MIN_FRAME_BYTES || maxFrameBytes > MAX_SUPPORTED_FRAME_BYTES) {
            throw new IllegalArgumentException(
                "maxFrameBytes must be between 65536 and 2097152"
            );
        }

        wirePrefix = FRAME_PREFIX + arguments[0] + " ";
        sessionId = "s-" + UUID.randomUUID().toString().replace("-", "");
        programSha256 = normalizeSha256(currentProgram.getExecutableSHA256());
        if (programSha256 == null) {
            throw new IllegalStateException(
                "GhidraExServer requires a valid executable SHA-256"
            );
        }
        programId = "p-" + programSha256;

        workerThread = new Thread(this::engineLoop, "GhidraEx engine worker");
        workerThread.setDaemon(false);
        workerThread.start();
        workerStarted.await();
        if (workerStartupFailure != null) {
            workerThread.join();
            throw new IllegalStateException(
                "GhidraEx engine worker initialization failed",
                workerStartupFailure
            );
        }

        emitReady();
        if (!decompilerAvailable) {
            emitWarning(
                "DECOMPILER_UNAVAILABLE",
                safeText(decompilerUnavailableReason, 2_048, "Decompiler is unavailable")
            );
        }

        int protocolErrors = 0;
        try {
            while (!closing.get()) {
                String line;
                try {
                    line = readBoundedLine(System.in, maxFrameBytes);
                }
                catch (IOException error) {
                    initiateClose("parent-lost", "Protocol input became unavailable");
                    break;
                }
                catch (FrameException error) {
                    protocolErrors++;
                    sendProtocolError(error.getMessage());
                    if (protocolErrors >= MAX_PROTOCOL_ERRORS) {
                        initiateClose("protocol-error", "Too many invalid protocol frames");
                    }
                    continue;
                }

                if (line == null) {
                    initiateClose("parent-lost", "Protocol input reached EOF");
                    break;
                }
                if (!line.startsWith(wirePrefix)) {
                    // Ghidra owns the surrounding process streams, so ordinary unframed input is
                    // ignored.  A protocol-shaped line with another nonce is instead a malformed
                    // or cross-session frame and counts toward the bounded protocol-error budget.
                    if (line.startsWith(FRAME_PREFIX)) {
                        protocolErrors++;
                        sendProtocolError("Protocol frame nonce does not match this session");
                        if (protocolErrors >= MAX_PROTOCOL_ERRORS) {
                            initiateClose(
                                "protocol-error",
                                "Too many invalid protocol frames"
                            );
                        }
                    }
                    continue;
                }

                String document = line.substring(wirePrefix.length());
                try {
                    Object decoded = Json.parse(document);
                    Request request = Request.parse(decoded);
                    accept(request);
                    protocolErrors = 0;
                }
                catch (RequestFailure error) {
                    protocolErrors++;
                    sendEnvelopeFailure(document, error);
                    if (protocolErrors >= MAX_PROTOCOL_ERRORS) {
                        initiateClose("protocol-error", "Too many invalid requests");
                    }
                }
                catch (RuntimeException error) {
                    protocolErrors++;
                    sendEnvelopeFailure(
                        document,
                        failure("INVALID_REQUEST", "Malformed request envelope", false)
                    );
                    if (protocolErrors >= MAX_PROTOCOL_ERRORS) {
                        initiateClose("protocol-error", "Too many malformed requests");
                    }
                }
            }
        }
        finally {
            if (!closing.get()) {
                initiateClose("engine-exit", "Engine reader stopped");
            }
            workerThread.join();
        }
    }

    private void engineLoop() {
        try {
            initializeDecompiler();
        }
        catch (Throwable error) {
            workerStartupFailure = error;
        }
        finally {
            workerStarted.countDown();
        }
        if (workerStartupFailure != null) {
            return;
        }

        try {
            while (true) {
                Request request;
                try {
                    request = workQueue.take();
                }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (request.poison) {
                    break;
                }

                TaskMonitorAdapter requestMonitor = new TaskMonitorAdapter(true);
                ActiveRequest activeRequest = new ActiveRequest(request, requestMonitor);
                active.set(activeRequest);
                pending.remove(request.id, request);
                if (closing.get()) {
                    requestMonitor.cancel();
                }
                try {
                    checkRequest(request, requestMonitor);
                    Map<String, Object> result = execute(request, requestMonitor);
                    checkRequest(request, requestMonitor);
                    sendSuccess(request, result);
                }
                catch (RequestFailure error) {
                    sendError(request.id, request.method, error);
                }
                catch (CancelledException error) {
                    sendError(request.id, request.method, cancellationFailure(request));
                }
                catch (Exception error) {
                    sendError(
                        request.id,
                        request.method,
                        failure("GHIDRA_FAILURE", "Ghidra could not complete the operation", false)
                    );
                }
                finally {
                    active.compareAndSet(activeRequest, null);
                    markCompleted(request.id);
                }
            }
        }
        finally {
            DecompInterface ownedDecompiler = decompiler;
            decompiler = null;
            decompilerAvailable = false;
            if (ownedDecompiler != null) {
                try {
                    ownedDecompiler.closeProgram();
                }
                catch (RuntimeException ignored) {
                    // The supervisor owns the final process-tree kill deadline.
                }
            }
        }
    }

    private void initializeDecompiler() {
        InstructionIterator instructions = currentProgram.getListing().getInstructions(true);
        long totalInstructions = 0;
        while (instructions.hasNext()) {
            instructions.next();
            totalInstructions++;
        }
        instructionCount = totalInstructions;

        DecompInterface candidate = new DecompInterface();
        try {
            DecompileOptions options = new DecompileOptions();
            options.setMaxPayloadMBytes(16);
            options.setMaxInstructions(1_000_000);
            candidate.setOptions(options);
            candidate.toggleCCode(true);
            candidate.toggleSyntaxTree(false);
            if (!candidate.openProgram(currentProgram)) {
                decompilerUnavailableReason = candidate.getLastMessage();
                candidate.closeProgram();
                return;
            }
            decompiler = candidate;
            decompilerAvailable = true;
        }
        catch (RuntimeException error) {
            decompilerUnavailableReason = error.getMessage();
            try {
                candidate.closeProgram();
            }
            catch (RuntimeException ignored) {
                // Best effort; the outer Ghidra process is the isolation boundary.
            }
        }
    }

    private void accept(Request request) throws RequestFailure {
        if (!inFlight.add(request.id)) {
            throw failure("INVALID_REQUEST", "Request id is already in flight", false);
        }

        if (request.method.equals("request.cancel")) {
            try {
                handleCancel(request);
            }
            finally {
                markCompleted(request.id);
            }
            return;
        }
        if (request.method.equals("session.close")) {
            try {
                handleClose(request);
            }
            finally {
                markCompleted(request.id);
            }
            return;
        }
        if (request.method.equals("session.ping")) {
            try {
                handlePing(request);
            }
            finally {
                markCompleted(request.id);
            }
            return;
        }

        if (closing.get()) {
            markCompleted(request.id);
            sendError(
                request.id,
                request.method,
                failure("ENGINE_UNAVAILABLE", "Engine session is closing", true)
            );
            return;
        }

        pending.put(request.id, request);
        if (!workQueue.offer(request)) {
            pending.remove(request.id, request);
            markCompleted(request.id);
            RequestFailure busy = failure(
                "ENGINE_BUSY",
                "The bounded engine work queue is full",
                true
            );
            busy.retryAfterMs = 50;
            sendError(request.id, request.method, busy);
        }
    }

    private void handlePing(Request request) throws RequestFailure {
        requireSessionContextAbsent(request);
        requireNegotiated(request.method);
        exactKeys(request.params, Set.of(), Set.of("nonce"));

        Map<String, Object> result = object();
        if (request.params.containsKey("nonce")) {
            result.put("nonce", string(request.params.get("nonce"), "nonce", 1, 128));
        }
        result.put(
            "monotonicTimeMs",
            Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L)
        );
        result.put(
            "state",
            closing.get()
                ? "closing"
                : active.get() != null || !workQueue.isEmpty() ? "busy" : "ready"
        );
        sendSuccess(request, result);
    }

    private void handleCancel(Request request) throws RequestFailure {
        requireSessionContextAbsent(request);
        requireNegotiated(request.method);
        exactKeys(request.params, Set.of("targetId"), Set.of());
        String targetId = requestId(request.params.get("targetId"), "targetId");

        boolean accepted = false;
        String state;
        ActiveRequest running = active.get();
        if (running != null && running.request.id.equals(targetId)) {
            running.monitor.cancel();
            accepted = true;
            state = "requested";
        }
        else {
            Request queued = pending.remove(targetId);
            if (queued != null && workQueue.remove(queued)) {
                sendError(targetId, queued.method, cancellationFailure(queued));
                markCompleted(targetId);
                accepted = true;
                state = "requested";
            }
            else {
                // Close the take/remove race: the worker publishes active before dropping pending.
                running = active.get();
                if (running != null && running.request.id.equals(targetId)) {
                    running.monitor.cancel();
                    accepted = true;
                    state = "requested";
                }
                else if (wasCompleted(targetId)) {
                    state = "already-complete";
                }
                else {
                    state = "not-cancellable";
                }
            }
        }

        Map<String, Object> result = object();
        result.put("accepted", accepted);
        result.put("state", state);
        sendSuccess(request, result);
    }

    private void handleClose(Request request) throws RequestFailure {
        requireSessionContextAbsent(request);
        requireNegotiated(request.method);
        exactKeys(request.params, Set.of(), Set.of("reason"));
        if (request.params.containsKey("reason")) {
            string(request.params.get("reason"), "reason", 1, 512);
        }

        Map<String, Object> result = object();
        result.put("accepted", true);
        sendSuccess(request, result);
        initiateClose("requested", "Client requested graceful shutdown");
    }

    private void initiateClose(String reason, String message) {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        Map<String, Object> data = object();
        data.put("reason", reason);
        if (message != null && !message.isBlank()) {
            data.put("message", safeText(message, 512, "Engine session is closing"));
        }
        emitEvent("session.closing", data, false);

        ActiveRequest running = active.get();
        if (running != null) {
            running.monitor.cancel();
        }

        List<Request> cancelled = new ArrayList<>();
        workQueue.drainTo(cancelled);
        for (Request request : cancelled) {
            if (request.poison) {
                continue;
            }
            pending.remove(request.id, request);
            sendError(request.id, request.method, cancellationFailure(request));
            markCompleted(request.id);
        }
        workQueue.offer(POISON);
    }

    private Map<String, Object> execute(Request request, TaskMonitorAdapter requestMonitor)
            throws Exception {
        if (request.method.equals("session.hello")) {
            return sessionHello(request);
        }
        requireNegotiated(request.method);
        if (PROGRAM_METHODS.contains(request.method)) {
            requireProgramContext(request);
        }

        return switch (request.method) {
            case "program.summary" -> programSummary(request);
            case "listing.window" -> listingWindow(request, requestMonitor);
            case "symbols.search" -> symbolsSearch(request, requestMonitor);
            case "references.list" -> referencesList(request, requestMonitor);
            case "decompile.function" -> decompileFunction(request, requestMonitor);
            default -> throw failure("METHOD_NOT_FOUND", "Unknown engine method", false);
        };
    }

    private Map<String, Object> sessionHello(Request request) throws RequestFailure {
        requireSessionContextAbsent(request);
        exactKeys(
            request.params,
            Set.of("client", "protocol"),
            Set.of("requestedCapabilities")
        );

        Map<String, Object> client = map(request.params.get("client"), "client");
        exactKeys(client, Set.of("name", "version"), Set.of("instanceId"));
        string(client.get("name"), "client.name", 1, 128);
        string(client.get("version"), "client.version", 1, 128);
        if (client.containsKey("instanceId")) {
            string(client.get("instanceId"), "client.instanceId", 1, 128);
        }

        Map<String, Object> protocol = map(request.params.get("protocol"), "protocol");
        exactKeys(protocol, Set.of("min", "max"), Set.of());
        long minimum = integer(protocol.get("min"), "protocol.min", 1, Long.MAX_VALUE);
        long maximum = integer(protocol.get("max"), "protocol.max", 1, Long.MAX_VALUE);
        if (minimum > maximum) {
            throw invalidParams("protocol.min must not exceed protocol.max");
        }
        if (minimum > PROTOCOL_VERSION || maximum < PROTOCOL_VERSION) {
            throw failure("UNSUPPORTED_VERSION", "Protocol version ranges do not overlap", false);
        }

        if (request.params.containsKey("requestedCapabilities")) {
            List<Object> requested = list(
                request.params.get("requestedCapabilities"),
                "requestedCapabilities",
                64
            );
            Set<String> unique = new HashSet<>();
            for (int index = 0; index < requested.size(); index++) {
                String capability = string(
                    requested.get(index),
                    "requestedCapabilities[" + index + "]",
                    3,
                    128
                );
                if (!METHOD_PATTERN.matcher(capability).matches() || !unique.add(capability)) {
                    throw invalidParams("requestedCapabilities must be unique method names");
                }
            }
        }

        negotiated.set(true);
        Map<String, Object> result = object();
        result.put("selectedVersion", PROTOCOL_VERSION);
        result.put("sessionId", sessionId);
        result.put("server", serverIdentity());
        result.put("ghidra", ghidraIdentity());
        result.put("capabilities", capabilities());
        return result;
    }

    private Map<String, Object> programSummary(Request request) throws Exception {
        exactKeys(request.params, Set.of(), Set.of());
        Memory memory = currentProgram.getMemory();
        Address minimum = memory.getMinAddress();
        Address maximum = memory.getMaxAddress();
        if (minimum == null || maximum == null || programSha256 == null) {
            throw failure("GHIDRA_FAILURE", "Program metadata is incomplete", false);
        }

        boolean timedOut = analysisTimeoutOccurred();
        Map<String, Object> analysis = object();
        analysis.put("complete", !timedOut);
        analysis.put("timedOut", timedOut);

        Map<String, Object> result = object();
        result.put("name", safeText(currentProgram.getName(), 512, "unnamed"));
        result.put(
            "executableFormat",
            safeText(currentProgram.getExecutableFormat(), 256, "unknown")
        );
        result.put("languageId", safeText(currentProgram.getLanguageID().toString(), 256, "unknown"));
        result.put(
            "compilerSpecId",
            safeText(currentProgram.getCompilerSpec().getCompilerSpecID().toString(), 256, "unknown")
        );
        result.put("imageBase", address(currentProgram.getImageBase()));
        result.put("minAddress", address(minimum));
        result.put("maxAddress", address(maximum));
        result.put("sha256", programSha256);
        result.put("analysis", analysis);
        result.put("memoryBlockCount", memory.getBlocks().length);
        result.put("functionCount", currentProgram.getFunctionManager().getFunctionCount());
        result.put("instructionCount", instructionCount);
        result.put("symbolCount", currentProgram.getSymbolTable().getNumSymbols());
        return result;
    }

    private Map<String, Object> listingWindow(Request request, TaskMonitorAdapter requestMonitor)
            throws Exception {
        exactKeys(
            request.params,
            Set.of(),
            Set.of("start", "cursor", "direction", "limit", "includeBytes")
        );
        boolean hasStart = request.params.containsKey("start");
        boolean hasCursor = request.params.containsKey("cursor");
        if (hasStart == hasCursor) {
            throw invalidParams("Exactly one of start or cursor is required");
        }

        String direction = optionalEnum(
            request.params,
            "direction",
            Set.of("forward", "backward"),
            "forward"
        );
        boolean includeBytes = optionalBoolean(request.params, "includeBytes", false);
        int limit = optionalInteger(
            request.params,
            "limit",
            DEFAULT_LISTING_ITEMS,
            1,
            MAX_LISTING_ITEMS
        );

        Address start;
        if (hasStart) {
            start = parseMemoryAddress(string(request.params.get("start"), "start", 1, 128));
        }
        else {
            ListingCursor cursor = decodeListingCursor(
                string(request.params.get("cursor"), "cursor", 1, 1_024)
            );
            if (!cursor.direction.equals(direction) || cursor.includeBytes != includeBytes) {
                throw invalidParams("Listing cursor does not match this query");
            }
            start = parseMemoryAddress(cursor.address);
        }

        Listing listing = currentProgram.getListing();
        boolean forward = direction.equals("forward");
        CodeUnitIterator iterator = listing.getCodeUnits(start, forward);
        List<Map<String, Object>> items = new ArrayList<>();
        Address continuation = null;
        while (iterator.hasNext() && items.size() <= limit) {
            checkRequest(request, requestMonitor);
            CodeUnit unit = iterator.next();
            if (items.size() == limit) {
                continuation = unit.getAddress();
                break;
            }
            items.add(listingItem(unit, includeBytes));
        }

        Map<String, Object> result = object();
        result.put("items", items);
        result.put("truncated", continuation != null);
        if (continuation != null) {
            result.put(
                "nextCursor",
                encodeListingCursor(direction, includeBytes, address(continuation))
            );
        }
        return result;
    }

    private Map<String, Object> listingItem(CodeUnit unit, boolean includeBytes)
            throws RequestFailure {
        int length = unit.getLength();
        if (length <= 0 || length > 1_048_576) {
            throw failure("LIMIT_EXCEEDED", "Code unit length exceeds the protocol bound", false);
        }

        Map<String, Object> item = object();
        item.put("address", address(unit.getAddress()));
        item.put("length", length);

        if (unit instanceof Instruction instruction) {
            item.put("kind", "instruction");
            putNonBlank(item, "mnemonic", instruction.getMnemonicString(), 256);
            StringBuilder operands = new StringBuilder();
            for (int index = 0; index < instruction.getNumOperands(); index++) {
                if (index != 0) {
                    operands.append(", ");
                }
                operands.append(instruction.getDefaultOperandRepresentation(index));
            }
            item.put("operands", safeText(operands.toString(), 4_096, ""));
            putNonBlank(item, "flowType", instruction.getFlowType().getName(), 128);
        }
        else if (unit instanceof Data data) {
            item.put("kind", data.isDefined() ? "data" : "undefined");
            putNonBlank(item, "mnemonic", data.getMnemonicString(), 256);
            item.put("operands", safeText(data.getDefaultValueRepresentation(), 4_096, ""));
        }
        else {
            item.put("kind", "undefined");
        }

        Symbol primary = currentProgram.getSymbolTable().getPrimarySymbol(unit.getAddress());
        if (primary != null) {
            putNonBlank(item, "label", primary.getName(true), 1_024);
        }
        Function function = currentProgram.getFunctionManager().getFunctionContaining(unit.getAddress());
        if (function != null) {
            putNonBlank(item, "function", function.getName(true), 1_024);
        }

        if (includeBytes) {
            if (length > MAX_CODE_UNIT_BYTES) {
                throw failure(
                    "LIMIT_EXCEEDED",
                    "A code unit is too large to include its bytes in one frame",
                    false
                );
            }
            try {
                byte[] bytes = unit.getBytes();
                if (bytes.length == length) {
                    item.put("bytes", hex(bytes));
                }
            }
            catch (Exception ignored) {
                // Bytes are optional because uninitialized memory has no backing value.
            }
        }
        return item;
    }

    private Map<String, Object> symbolsSearch(Request request, TaskMonitorAdapter requestMonitor)
            throws Exception {
        exactKeys(
            request.params,
            Set.of("query"),
            Set.of("kinds", "limit", "cursor")
        );
        String query = string(request.params.get("query"), "query", 0, 256);
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        int limit = optionalInteger(
            request.params,
            "limit",
            DEFAULT_SYMBOL_ITEMS,
            1,
            MAX_SYMBOL_ITEMS
        );
        Set<String> kinds = symbolKinds(request.params.get("kinds"));
        String binding = symbolQueryBinding(normalizedQuery, kinds);

        SymbolFact after = null;
        if (request.params.containsKey("cursor")) {
            SymbolCursor cursor = decodeSymbolCursor(
                string(request.params.get("cursor"), "cursor", 1, 1_024)
            );
            if (!cursor.binding.equals(binding)) {
                throw invalidParams("Symbol cursor does not match this query");
            }
            Symbol boundary = currentProgram.getSymbolTable().getSymbol(cursor.symbolId);
            if (boundary == null) {
                throw invalidParams("Symbol cursor is no longer valid");
            }
            after = new SymbolFact(boundary);
        }

        Comparator<SymbolFact> order = SymbolFact.ORDER;
        PriorityQueue<SymbolFact> selected = new PriorityQueue<>(limit + 1, order.reversed());
        SymbolIterator iterator = currentProgram.getSymbolTable().getAllSymbols(false);
        int scanned = 0;
        while (iterator.hasNext()) {
            if (++scanned > MAX_SYMBOL_SCAN) {
                throw failure("LIMIT_EXCEEDED", "Symbol search scan exceeds the engine bound", false);
            }
            if ((scanned & 0xff) == 0) {
                checkRequest(request, requestMonitor);
            }
            Symbol symbol = iterator.next();
            SymbolFact fact = new SymbolFact(symbol);
            if (!matchesSymbol(fact, normalizedQuery, kinds)) {
                continue;
            }
            if (after != null && order.compare(fact, after) <= 0) {
                continue;
            }
            if (selected.size() < limit + 1) {
                selected.add(fact);
            }
            else if (order.compare(fact, selected.peek()) < 0) {
                selected.poll();
                selected.add(fact);
            }
        }
        checkRequest(request, requestMonitor);

        List<SymbolFact> sorted = new ArrayList<>(selected);
        sorted.sort(order);
        boolean truncated = sorted.size() > limit;
        if (truncated) {
            sorted.remove(sorted.size() - 1);
        }
        List<Map<String, Object>> items = new ArrayList<>(sorted.size());
        for (SymbolFact fact : sorted) {
            items.add(fact.json());
        }

        Map<String, Object> result = object();
        result.put("items", items);
        result.put("truncated", truncated);
        if (truncated && !sorted.isEmpty()) {
            result.put(
                "nextCursor",
                encodeSymbolCursor(binding, sorted.get(sorted.size() - 1).id)
            );
        }
        return result;
    }

    private Map<String, Object> referencesList(Request request, TaskMonitorAdapter requestMonitor)
            throws Exception {
        exactKeys(
            request.params,
            Set.of("address"),
            Set.of("direction", "limit", "cursor")
        );
        Address target = parseEngineAddress(string(request.params.get("address"), "address", 1, 128));
        String direction = optionalEnum(
            request.params,
            "direction",
            Set.of("to", "from", "both"),
            "both"
        );
        int limit = optionalInteger(
            request.params,
            "limit",
            DEFAULT_REFERENCE_ITEMS,
            1,
            MAX_REFERENCE_ITEMS
        );
        String binding = referenceQueryBinding(address(target), direction);

        ReferenceFact after = null;
        if (request.params.containsKey("cursor")) {
            ReferenceCursor cursor = decodeReferenceCursor(
                string(request.params.get("cursor"), "cursor", 1, 1_024)
            );
            if (!cursor.binding.equals(binding)) {
                throw invalidParams("Reference cursor does not match this query");
            }
            after = cursor.boundary;
        }

        Comparator<ReferenceFact> order = ReferenceFact.ORDER;
        PriorityQueue<ReferenceFact> selected =
            new PriorityQueue<>(limit + 1, order.reversed());
        Set<String> seen = direction.equals("both") ? new HashSet<>() : null;
        int[] scanned = {0};
        if (direction.equals("to") || direction.equals("both")) {
            if (target.isStackAddress() || target.isRegisterAddress()) {
                throw invalidParams("Incoming references require a memory or external address");
            }
            ReferenceIterator iterator = currentProgram.getReferenceManager().getReferencesTo(target);
            collectReferences(
                iterator,
                request,
                requestMonitor,
                after,
                selected,
                seen,
                limit,
                scanned
            );
        }
        if (direction.equals("from") || direction.equals("both")) {
            collectReferences(
                Arrays.asList(currentProgram.getReferenceManager().getReferencesFrom(target)).iterator(),
                request,
                requestMonitor,
                after,
                selected,
                seen,
                limit,
                scanned
            );
        }
        checkRequest(request, requestMonitor);

        List<ReferenceFact> sorted = new ArrayList<>(selected);
        sorted.sort(order);
        boolean truncated = sorted.size() > limit;
        if (truncated) {
            sorted.remove(sorted.size() - 1);
        }
        List<Map<String, Object>> items = new ArrayList<>(sorted.size());
        for (ReferenceFact fact : sorted) {
            items.add(fact.json());
        }

        Map<String, Object> result = object();
        result.put("items", items);
        result.put("truncated", truncated);
        if (truncated && !sorted.isEmpty()) {
            result.put(
                "nextCursor",
                encodeReferenceCursor(binding, sorted.get(sorted.size() - 1))
            );
        }
        return result;
    }

    private void collectReferences(
            Iterator<Reference> iterator,
            Request request,
            TaskMonitorAdapter requestMonitor,
            ReferenceFact after,
            PriorityQueue<ReferenceFact> selected,
            Set<String> seen,
            int limit,
            int[] scanned) throws Exception {
        while (iterator.hasNext()) {
            if (++scanned[0] > MAX_REFERENCE_SCAN) {
                throw failure(
                    "LIMIT_EXCEEDED",
                    "Reference scan exceeds the engine bound",
                    false
                );
            }
            if ((scanned[0] & 0xff) == 0) {
                checkRequest(request, requestMonitor);
            }
            ReferenceFact fact = new ReferenceFact(iterator.next());
            if (seen != null && !seen.add(fact.key())) {
                continue;
            }
            if (after != null && ReferenceFact.ORDER.compare(fact, after) <= 0) {
                continue;
            }
            if (selected.size() < limit + 1) {
                selected.add(fact);
            }
            else if (ReferenceFact.ORDER.compare(fact, selected.peek()) < 0) {
                selected.poll();
                selected.add(fact);
            }
        }
    }

    private Map<String, Object> decompileFunction(
            Request request,
            TaskMonitorAdapter requestMonitor) throws Exception {
        exactKeys(request.params, Set.of("address"), Set.of("maxChars"));
        if (!decompilerAvailable || decompiler == null) {
            throw failure("ENGINE_UNAVAILABLE", "Decompiler is not available for this program", true);
        }

        Address requestedAddress = parseMemoryAddress(
            string(request.params.get("address"), "address", 1, 128)
        );
        int maxChars = optionalInteger(
            request.params,
            "maxChars",
            DEFAULT_DECOMPILE_CHARS,
            1_024,
            MAX_DECOMPILE_CHARS
        );
        Function function = currentProgram.getFunctionManager().getFunctionContaining(requestedAddress);
        if (function == null) {
            throw failure("NOT_FOUND", "No function contains the requested address", false);
        }
        if (function.isExternal()) {
            throw failure("NOT_FOUND", "External functions cannot be decompiled", false);
        }

        checkRequest(request, requestMonitor);
        long remainingMillis = request.remainingMillis();
        if (remainingMillis <= 0) {
            throw deadlineFailure();
        }
        int timeoutSeconds = (int) Math.max(1, Math.min(30, (remainingMillis + 999) / 1_000));
        DecompileResults decompiled =
            decompiler.decompileFunction(function, timeoutSeconds, requestMonitor);

        if (requestMonitor.isCancelled()) {
            throw cancellationFailure(request);
        }
        if (decompiled.isTimedOut() || request.expired()) {
            throw deadlineFailure();
        }
        if (decompiled.isCancelled()) {
            throw cancellationFailure(request);
        }
        if (!decompiled.decompileCompleted()) {
            throw failure("GHIDRA_FAILURE", "Decompiler did not produce a result", true);
        }
        DecompiledFunction value = decompiled.getDecompiledFunction();
        if (value == null || value.getC() == null) {
            throw failure("GHIDRA_FAILURE", "Decompiler result did not contain C text", false);
        }

        String originalText = sanitize(value.getC());
        String text = truncateCodePoints(originalText, maxChars);
        boolean truncated = text.codePointCount(0, text.length()) <
            originalText.codePointCount(0, originalText.length());

        Map<String, Object> functionValue = object();
        functionValue.put("name", safeText(function.getName(true), 1_024, "unnamed"));
        functionValue.put("entryPoint", address(function.getEntryPoint()));
        functionValue.put(
            "signature",
            safeText(function.getPrototypeString(false, true), 16_384, function.getName())
        );

        Map<String, Object> result = object();
        result.put("function", functionValue);
        result.put("language", "c");
        result.put("text", text);
        result.put("truncated", truncated);
        result.put("warnings", warnings(decompiled.getErrorMessage()));
        return result;
    }

    private void emitReady() {
        Map<String, Object> protocol = object();
        protocol.put("min", PROTOCOL_VERSION);
        protocol.put("max", PROTOCOL_VERSION);

        Map<String, Object> data = object();
        data.put("sessionId", sessionId);
        data.put("protocol", protocol);
        data.put("server", serverIdentity());
        data.put("ghidra", ghidraIdentity());
        data.put("context", context());
        data.put("programSha256", programSha256);
        emitEvent("session.ready", data, false);
    }

    private void emitWarning(String code, String message) {
        Map<String, Object> data = object();
        data.put("code", code);
        data.put("message", message);
        emitEvent("engine.warning", data, true);
    }

    private void emitEvent(String event, Map<String, Object> data, boolean withContext) {
        Map<String, Object> envelope = object();
        envelope.put("kind", "event");
        envelope.put("version", PROTOCOL_VERSION);
        envelope.put("event", event);
        envelope.put("sequence", eventSequence.incrementAndGet());
        if (withContext) {
            envelope.put("context", context());
        }
        envelope.put("data", data);
        emitEnvelope(envelope);
    }

    private void sendSuccess(Request request, Map<String, Object> result) {
        Map<String, Object> envelope = responseBase(request.id, request.method);
        envelope.put("ok", true);
        if (PROGRAM_METHODS.contains(request.method)) {
            envelope.put("context", context());
        }
        envelope.put("result", result);

        String serialized = Json.write(envelope);
        if (!fitsFrame(serialized) && request.method.equals("decompile.function")) {
            serialized = fitDecompileResponse(envelope, result);
        }
        if (serialized == null || !fitsFrame(serialized)) {
            sendError(
                request.id,
                request.method,
                failure("LIMIT_EXCEEDED", "Response exceeds the maximum frame size", false)
            );
            return;
        }
        emitSerialized(serialized);
    }

    private String fitDecompileResponse(
            Map<String, Object> envelope,
            Map<String, Object> result) {
        Object value = result.get("text");
        if (!(value instanceof String original)) {
            return null;
        }
        int codePoints = original.codePointCount(0, original.length());
        int low = 0;
        int high = codePoints;
        String best = null;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            result.put("text", truncateCodePoints(original, middle));
            result.put("truncated", true);
            String candidate = Json.write(envelope);
            if (fitsFrame(candidate)) {
                best = candidate;
                low = middle + 1;
            }
            else {
                high = middle - 1;
            }
        }
        return best;
    }

    private void sendError(String id, String method, RequestFailure failure) {
        Map<String, Object> error = object();
        error.put("code", failure.code);
        error.put("message", safeText(failure.getMessage(), 2_048, "Engine request failed"));
        error.put("retryable", failure.retryable);
        if (failure.retryAfterMs != null) {
            error.put("retryAfterMs", failure.retryAfterMs);
        }
        if (failure.details != null && !failure.details.isEmpty()) {
            error.put("details", failure.details);
        }

        Map<String, Object> envelope = responseBase(id, method);
        envelope.put("ok", false);
        envelope.put("error", error);
        emitEnvelope(envelope);
    }

    private void sendProtocolError(String message) {
        String id = "invalid-" + invalidRequestSequence.incrementAndGet();
        sendError(
            id,
            "session.protocol",
            failure("INVALID_REQUEST", safeText(message, 512, "Invalid protocol frame"), false)
        );
    }

    private void sendEnvelopeFailure(String document, RequestFailure failure) {
        String id = "invalid-" + invalidRequestSequence.incrementAndGet();
        String method = "session.protocol";
        try {
            Object decoded = Json.parse(document);
            if (decoded instanceof Map<?, ?> raw) {
                Object possibleId = raw.get("id");
                Object possibleMethod = raw.get("method");
                if (possibleId instanceof String candidate &&
                    REQUEST_ID_PATTERN.matcher(candidate).matches()) {
                    id = candidate;
                }
                if (possibleMethod instanceof String candidate &&
                    METHOD_PATTERN.matcher(candidate).matches()) {
                    method = candidate;
                }
            }
        }
        catch (RuntimeException ignored) {
            // The generated correlation values remain valid.
        }
        sendError(id, method, failure);
    }

    private Map<String, Object> responseBase(String id, String method) {
        Map<String, Object> envelope = object();
        envelope.put("kind", "response");
        envelope.put("version", PROTOCOL_VERSION);
        envelope.put("id", id);
        envelope.put("method", method);
        return envelope;
    }

    private void emitEnvelope(Map<String, Object> envelope) {
        String serialized = Json.write(envelope);
        if (!fitsFrame(serialized)) {
            outputBroken = true;
            return;
        }
        emitSerialized(serialized);
    }

    private void emitSerialized(String serialized) {
        if (outputBroken) {
            return;
        }
        String frame = wirePrefix + serialized;
        synchronized (outputLock) {
            System.out.print(frame + "\n");
            System.out.flush();
            if (System.out.checkError()) {
                outputBroken = true;
            }
        }
    }

    private boolean fitsFrame(String serialized) {
        return wirePrefix.getBytes(StandardCharsets.US_ASCII).length +
            serialized.getBytes(StandardCharsets.UTF_8).length + 1 <= maxFrameBytes;
    }

    private Map<String, Object> capabilities() {
        List<String> methods = new ArrayList<>(List.of(
            "session.hello",
            "session.ping",
            "session.close",
            "request.cancel",
            "program.summary",
            "listing.window",
            "symbols.search",
            "references.list"
        ));
        if (decompilerAvailable) {
            methods.add("decompile.function");
        }
        Collections.sort(methods);

        Map<String, Object> limits = object();
        limits.put("maxFrameBytes", maxFrameBytes);
        limits.put("maxTimeoutMs", MAX_TIMEOUT_MS);
        limits.put("maxListingItems", MAX_LISTING_ITEMS);
        limits.put("maxSymbolItems", MAX_SYMBOL_ITEMS);
        limits.put("maxReferenceItems", MAX_REFERENCE_ITEMS);
        limits.put("maxDecompileChars", MAX_DECOMPILE_CHARS);

        Map<String, Object> capabilities = object();
        capabilities.put("methods", methods);
        capabilities.put(
            "events",
            List.of("engine.warning", "session.closing", "session.ready")
        );
        capabilities.put("transports", List.of("stdio"));
        capabilities.put("limits", limits);
        return capabilities;
    }

    private Map<String, Object> serverIdentity() {
        Map<String, Object> server = object();
        server.put("name", SERVER_NAME);
        server.put("version", SERVER_VERSION);
        server.put("instanceId", sessionId);
        return server;
    }

    private Map<String, Object> ghidraIdentity() {
        Map<String, Object> ghidra = object();
        ghidra.put("version", Application.getApplicationVersion());
        return ghidra;
    }

    private Map<String, Object> context() {
        Map<String, Object> context = object();
        context.put("programId", programId);
        context.put("revision", 0);
        return context;
    }

    private void requireNegotiated(String method) throws RequestFailure {
        if (!negotiated.get()) {
            throw failure(
                "SESSION_NOT_READY",
                method + " requires a successful session.hello negotiation",
                true
            );
        }
    }

    private void requireSessionContextAbsent(Request request) throws RequestFailure {
        if (request.context != null) {
            throw failure(
                "INVALID_REQUEST",
                "Session methods must not include program context",
                false
            );
        }
    }

    private void requireProgramContext(Request request) throws RequestFailure {
        if (request.context == null) {
            throw failure("INVALID_REQUEST", "Program context is required", false);
        }
        exactKeys(request.context, Set.of("programId", "revision"), Set.of());
        String requestedProgram = string(
            request.context.get("programId"),
            "context.programId",
            1,
            128
        );
        if (!programId.equals(requestedProgram)) {
            RequestFailure mismatch = failure(
                "PROGRAM_MISMATCH",
                "Request context names another program",
                true
            );
            mismatch.details = Map.of("expectedProgramId", programId);
            throw mismatch;
        }
        long revision = integer(
            request.context.get("revision"),
            "context.revision",
            0,
            Long.MAX_VALUE
        );
        if (revision != 0) {
            RequestFailure mismatch = failure(
                "REVISION_MISMATCH",
                "Requested program revision is not current",
                true
            );
            mismatch.details = Map.of("currentRevision", 0);
            throw mismatch;
        }
    }

    private Address parseMemoryAddress(String text) throws RequestFailure {
        Address parsed = parseEngineAddress(text);
        if (!currentProgram.getMemory().contains(parsed)) {
            throw failure("NOT_FOUND", "Address is outside program memory", false);
        }
        return parsed;
    }

    private Address parseEngineAddress(String text) throws RequestFailure {
        Address parsed = currentProgram.getAddressFactory().getAddress(text);
        if (parsed == null) {
            throw invalidParams("Address is not valid for this program");
        }
        return parsed;
    }

    private void checkRequest(Request request, TaskMonitorAdapter requestMonitor)
            throws CancelledException, RequestFailure {
        if (request.expired()) {
            requestMonitor.cancel();
            throw deadlineFailure();
        }
        requestMonitor.checkCancelled();
    }

    private RequestFailure cancellationFailure(Request request) {
        return request.expired()
            ? deadlineFailure()
            : failure("REQUEST_CANCELLED", "Request was cancelled", true);
    }

    private RequestFailure deadlineFailure() {
        return failure("DEADLINE_EXCEEDED", "Request deadline was exceeded", true);
    }

    private void markCompleted(String id) {
        inFlight.remove(id);
        synchronized (completedLock) {
            completedIds.put(id, Boolean.TRUE);
            while (completedIds.size() > COMPLETED_ID_CAPACITY) {
                Iterator<String> iterator = completedIds.keySet().iterator();
                iterator.next();
                iterator.remove();
            }
        }
    }

    private boolean wasCompleted(String id) {
        synchronized (completedLock) {
            return completedIds.containsKey(id);
        }
    }

    private Set<String> symbolKinds(Object value) throws RequestFailure {
        Set<String> allowed = Set.of(
            "label",
            "function",
            "namespace",
            "class",
            "parameter",
            "local",
            "external",
            "library",
            "unknown"
        );
        if (value == null) {
            return Set.of();
        }
        List<Object> values = list(value, "kinds", 10);
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            String kind = string(values.get(index), "kinds[" + index + "]", 1, 32);
            if (!allowed.contains(kind) || !result.add(kind)) {
                throw invalidParams("kinds must contain unique supported symbol kinds");
            }
        }
        return result;
    }

    private boolean matchesSymbol(SymbolFact fact, String query, Set<String> kinds) {
        if (!kinds.isEmpty() && !kinds.contains(fact.kind)) {
            return false;
        }
        return query.isEmpty() || fact.searchText.contains(query);
    }

    private String symbolQueryBinding(String query, Set<String> kinds) {
        List<String> orderedKinds = new ArrayList<>(kinds);
        Collections.sort(orderedKinds);
        return shortHash(
            "symbols\u0000" + sessionId + "\u0000" + query + "\u0000" +
                String.join(",", orderedKinds)
        );
    }

    private String referenceQueryBinding(String target, String direction) {
        return shortHash(
            "references\u0000" + sessionId + "\u0000" + target + "\u0000" + direction
        );
    }

    private String encodeListingCursor(String direction, boolean includeBytes, String address) {
        String payload = sessionId + "\u0000" + direction + "\u0000" + includeBytes +
            "\u0000" + address;
        return "l:" + base64(payload);
    }

    private ListingCursor decodeListingCursor(String cursor) throws RequestFailure {
        if (!cursor.startsWith("l:")) {
            throw invalidParams("Invalid listing cursor");
        }
        String[] parts = unbase64(cursor.substring(2)).split("\\u0000", -1);
        if (parts.length != 4 ||
            !sessionId.equals(parts[0]) ||
            !Set.of("forward", "backward").contains(parts[1]) ||
            !Set.of("true", "false").contains(parts[2])) {
            throw invalidParams("Invalid listing cursor");
        }
        return new ListingCursor(parts[1], Boolean.parseBoolean(parts[2]), parts[3]);
    }

    private String encodeSymbolCursor(String binding, long symbolId) {
        return "s:" + binding + ":" + symbolId;
    }

    private SymbolCursor decodeSymbolCursor(String cursor) throws RequestFailure {
        if (!cursor.startsWith("s:")) {
            throw invalidParams("Invalid symbol cursor");
        }
        int separator = cursor.indexOf(':', 2);
        if (separator < 0) {
            throw invalidParams("Invalid symbol cursor");
        }
        try {
            return new SymbolCursor(
                cursor.substring(2, separator),
                Long.parseLong(cursor.substring(separator + 1))
            );
        }
        catch (NumberFormatException error) {
            throw invalidParams("Invalid symbol cursor");
        }
    }

    private String encodeReferenceCursor(String binding, ReferenceFact fact) {
        List<Object> boundary = List.of(
            fact.from,
            fact.to,
            fact.type,
            fact.operandIndex,
            fact.primary,
            fact.external,
            fact.source
        );
        return "r:" + binding + ":" + base64(Json.write(boundary));
    }

    private ReferenceCursor decodeReferenceCursor(String cursor) throws RequestFailure {
        if (!cursor.startsWith("r:")) {
            throw invalidParams("Invalid reference cursor");
        }
        int separator = cursor.indexOf(':', 2);
        if (separator < 0) {
            throw invalidParams("Invalid reference cursor");
        }
        String binding = cursor.substring(2, separator);
        Object decoded;
        try {
            decoded = Json.parse(unbase64(cursor.substring(separator + 1)));
        }
        catch (RuntimeException error) {
            throw invalidParams("Invalid reference cursor");
        }
        List<Object> values = list(decoded, "cursor", 7);
        if (values.size() != 7) {
            throw invalidParams("Invalid reference cursor");
        }
        ReferenceFact boundary = new ReferenceFact(
            string(values.get(0), "cursor.from", 1, 128),
            string(values.get(1), "cursor.to", 1, 128),
            string(values.get(2), "cursor.type", 1, 128),
            (int) integer(values.get(3), "cursor.operandIndex", Integer.MIN_VALUE, Integer.MAX_VALUE),
            bool(values.get(4), "cursor.primary"),
            bool(values.get(5), "cursor.external"),
            string(values.get(6), "cursor.source", 0, 128)
        );
        return new ReferenceCursor(binding, boundary);
    }

    private static String base64(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String unbase64(String value) throws RequestFailure {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded))
                    .toString();
        }
        catch (IllegalArgumentException | CharacterCodingException error) {
            throw invalidParams("Cursor is not valid base64url UTF-8");
        }
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return hex(Arrays.copyOf(digest, 12));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String normalizeSha256(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.matches("^[0-9a-f]{64}$") ? normalized : null;
    }

    private static String readBoundedLine(InputStream input, int maximumBytes)
            throws IOException, FrameException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4_096);
        boolean overflow = false;
        boolean carriageReturn = false;
        while (true) {
            int value = input.read();
            if (value < 0) {
                if (bytes.size() == 0 && !overflow) {
                    return null;
                }
                throw new EOFException("Protocol frame ended before LF");
            }
            if (value == '\n') {
                if (overflow) {
                    throw new FrameException(
                        "Protocol frame exceeds " + maximumBytes + " bytes"
                    );
                }
                if (carriageReturn) {
                    throw new FrameException(
                        "Raw carriage returns are forbidden inside a protocol frame"
                    );
                }
                break;
            }
            if (value == '\r') {
                carriageReturn = true;
            }
            if (bytes.size() + 2 > maximumBytes) {
                overflow = true;
            }
            else if (!overflow) {
                bytes.write(value);
            }
        }

        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        }
        catch (CharacterCodingException error) {
            throw new FrameException("Protocol frame is not valid UTF-8");
        }
    }

    private static RequestFailure failure(
            String code,
            String message,
            boolean retryable) {
        return new RequestFailure(code, message, retryable);
    }

    private static RequestFailure invalidParams(String message) {
        return failure("INVALID_PARAMS", message, false);
    }

    private static RequestFailure invalidRequest(String message) {
        return failure("INVALID_REQUEST", message, false);
    }

    private static void exactEnvelopeKeys(
            Map<String, Object> value,
            Set<String> required,
            Set<String> optional) throws RequestFailure {
        if (!value.keySet().containsAll(required)) {
            Set<String> missing = new LinkedHashSet<>(required);
            missing.removeAll(value.keySet());
            throw invalidRequest("Missing required request field: " + missing.iterator().next());
        }
        Set<String> allowed = new HashSet<>(required);
        allowed.addAll(optional);
        for (String key : value.keySet()) {
            if (!allowed.contains(key)) {
                throw invalidRequest("Unknown request field: " + key);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> envelopeMap(Object value, String field)
            throws RequestFailure {
        if (!(value instanceof Map<?, ?> raw)) {
            throw invalidRequest(field + " must be an object");
        }
        return (Map<String, Object>) raw;
    }

    private static String envelopeRequestId(Object value) throws RequestFailure {
        if (!(value instanceof String id) ||
            id.codePointCount(0, id.length()) < 1 ||
            id.codePointCount(0, id.length()) > 128 ||
            !REQUEST_ID_PATTERN.matcher(id).matches()) {
            throw invalidRequest("id has an invalid request-id format");
        }
        return id;
    }

    private static String envelopeMethod(Object value) throws RequestFailure {
        if (!(value instanceof String method) ||
            method.codePointCount(0, method.length()) < 3 ||
            method.codePointCount(0, method.length()) > 128 ||
            !METHOD_PATTERN.matcher(method).matches()) {
            throw invalidRequest("method has invalid syntax");
        }
        return method;
    }

    private static void validateContextShape(Map<String, Object> context)
            throws RequestFailure {
        exactEnvelopeKeys(context, Set.of("programId", "revision"), Set.of());
        string(context.get("programId"), "context.programId", 1, 128);
        Object revision = context.get("revision");
        if (!(revision instanceof Long value) || value < 0) {
            throw invalidParams("context.revision must be a non-negative integer");
        }
    }

    private static int envelopeTimeout(Object value) throws RequestFailure {
        if (!(value instanceof Long timeout) || timeout < 100 || timeout > MAX_TIMEOUT_MS) {
            throw failure(
                "LIMIT_EXCEEDED",
                "timeoutMs must be an integer between 100 and " + MAX_TIMEOUT_MS,
                false
            );
        }
        return timeout.intValue();
    }

    private static void exactKeys(
            Map<String, Object> value,
            Set<String> required,
            Set<String> optional) throws RequestFailure {
        if (!value.keySet().containsAll(required)) {
            Set<String> missing = new LinkedHashSet<>(required);
            missing.removeAll(value.keySet());
            throw invalidParams("Missing required field: " + missing.iterator().next());
        }
        Set<String> allowed = new HashSet<>(required);
        allowed.addAll(optional);
        for (String key : value.keySet()) {
            if (!allowed.contains(key)) {
                throw invalidParams("Unknown field: " + key);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String field) throws RequestFailure {
        if (!(value instanceof Map<?, ?> raw)) {
            throw invalidParams(field + " must be an object");
        }
        return (Map<String, Object>) raw;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value, String field, int maximum)
            throws RequestFailure {
        if (!(value instanceof List<?> raw)) {
            throw invalidParams(field + " must be an array");
        }
        if (raw.size() > maximum) {
            throw failure("LIMIT_EXCEEDED", field + " exceeds its item bound", false);
        }
        return (List<Object>) raw;
    }

    private static String string(
            Object value,
            String field,
            int minimum,
            int maximum) throws RequestFailure {
        if (!(value instanceof String text)) {
            throw invalidParams(field + " must be a string");
        }
        int length = text.codePointCount(0, text.length());
        if (length < minimum || length > maximum) {
            throw invalidParams(field + " length is outside its bound");
        }
        return text;
    }

    private static long integer(
            Object value,
            String field,
            long minimum,
            long maximum) throws RequestFailure {
        if (!(value instanceof Long number)) {
            throw invalidParams(field + " must be an integer");
        }
        if (number < minimum || number > maximum) {
            throw failure("LIMIT_EXCEEDED", field + " is outside its bound", false);
        }
        return number;
    }

    private static boolean bool(Object value, String field) throws RequestFailure {
        if (!(value instanceof Boolean result)) {
            throw invalidParams(field + " must be a boolean");
        }
        return result;
    }

    private static String requestId(Object value, String field) throws RequestFailure {
        String id = string(value, field, 1, 128);
        if (!REQUEST_ID_PATTERN.matcher(id).matches()) {
            throw invalidParams(field + " has invalid characters");
        }
        return id;
    }

    private static int optionalInteger(
            Map<String, Object> params,
            String field,
            int defaultValue,
            int minimum,
            int maximum) throws RequestFailure {
        return params.containsKey(field)
            ? (int) integer(params.get(field), field, minimum, maximum)
            : defaultValue;
    }

    private static boolean optionalBoolean(
            Map<String, Object> params,
            String field,
            boolean defaultValue) throws RequestFailure {
        return params.containsKey(field) ? bool(params.get(field), field) : defaultValue;
    }

    private static String optionalEnum(
            Map<String, Object> params,
            String field,
            Set<String> allowed,
            String defaultValue) throws RequestFailure {
        if (!params.containsKey(field)) {
            return defaultValue;
        }
        String value = string(params.get(field), field, 1, 64);
        if (!allowed.contains(value)) {
            throw invalidParams(field + " has an unsupported value");
        }
        return value;
    }

    private static String address(Address value) {
        return value.toString(true);
    }

    private static String hex(byte[] bytes) {
        char[] encoded = new char[bytes.length * 2];
        char[] digits = "0123456789abcdef".toCharArray();
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            encoded[index * 2] = digits[value >>> 4];
            encoded[index * 2 + 1] = digits[value & 0x0f];
        }
        return new String(encoded);
    }

    private static void putNonBlank(
            Map<String, Object> destination,
            String key,
            String value,
            int maximum) {
        if (value == null || value.isBlank()) {
            return;
        }
        destination.put(key, safeText(value, maximum, "unknown"));
    }

    private static String safeText(String value, int maximum, String fallback) {
        String selected = value == null || value.isBlank() ? fallback : value;
        return truncateCodePoints(sanitize(selected), maximum);
    }

    private static String sanitize(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 < value.length() && Character.isLowSurrogate(value.charAt(index + 1))) {
                    result.append(character).append(value.charAt(++index));
                }
                else {
                    result.append('\ufffd');
                }
            }
            else if (Character.isLowSurrogate(character)) {
                result.append('\ufffd');
            }
            else {
                result.append(character);
            }
        }
        return result.toString();
    }

    private static String truncateCodePoints(String value, int maximum) {
        if (maximum <= 0) {
            return "";
        }
        int count = value.codePointCount(0, value.length());
        if (count <= maximum) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maximum));
    }

    private static List<String> warnings(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String line : message.split("\\R")) {
            if (!line.isBlank()) {
                result.add(safeText(line, 2_048, "Decompiler warning"));
                if (result.size() == 64) {
                    break;
                }
            }
        }
        return result;
    }

    private static Map<String, Object> object() {
        return new LinkedHashMap<>();
    }

    private static String symbolKind(Symbol symbol) {
        SymbolType type = symbol.getSymbolType();
        if (type == SymbolType.LIBRARY) {
            return "library";
        }
        if (symbol.isExternal()) {
            return "external";
        }
        if (type == SymbolType.LABEL) {
            return "label";
        }
        if (type == SymbolType.FUNCTION) {
            return "function";
        }
        if (type == SymbolType.NAMESPACE) {
            return "namespace";
        }
        if (type == SymbolType.CLASS) {
            return "class";
        }
        if (type == SymbolType.PARAMETER) {
            return "parameter";
        }
        if (type == SymbolType.LOCAL_VAR) {
            return "local";
        }
        return "unknown";
    }

    private static final class Request {
        private final String id;
        private final String method;
        private final Map<String, Object> params;
        private final Map<String, Object> context;
        private final long deadlineNanos;
        private final boolean poison;

        private Request(
                String id,
                String method,
                Map<String, Object> params,
                Map<String, Object> context,
                int timeoutMs,
                boolean poison) {
            this.id = id;
            this.method = method;
            this.params = params;
            this.context = context;
            this.deadlineNanos = poison
                ? Long.MAX_VALUE
                : System.nanoTime() + timeoutMs * 1_000_000L;
            this.poison = poison;
        }

        private static Request poison() {
            return new Request(null, null, null, null, MAX_TIMEOUT_MS, true);
        }

        private static Request parse(Object decoded) throws RequestFailure {
            Map<String, Object> envelope = envelopeMap(decoded, "request");
            exactEnvelopeKeys(
                envelope,
                Set.of("kind", "version", "id", "method", "params"),
                Set.of("context", "timeoutMs")
            );
            if (!"request".equals(envelope.get("kind"))) {
                throw invalidRequest("kind must equal request");
            }
            if (!(envelope.get("version") instanceof Long version) ||
                version != PROTOCOL_VERSION) {
                throw failure("UNSUPPORTED_VERSION", "Protocol version is not supported", false);
            }
            String id = envelopeRequestId(envelope.get("id"));
            String method = envelopeMethod(envelope.get("method"));
            Map<String, Object> params = envelopeMap(envelope.get("params"), "params");
            Map<String, Object> context = envelope.containsKey("context")
                ? envelopeMap(envelope.get("context"), "context")
                : null;
            if (context != null) {
                validateContextShape(context);
            }
            int timeout = envelope.containsKey("timeoutMs")
                ? envelopeTimeout(envelope.get("timeoutMs"))
                : DEFAULT_TIMEOUT_MS;
            return new Request(id, method, params, context, timeout, false);
        }

        private boolean expired() {
            return System.nanoTime() - deadlineNanos >= 0;
        }

        private long remainingMillis() {
            long remaining = deadlineNanos - System.nanoTime();
            return remaining <= 0 ? 0 : Math.max(1, remaining / 1_000_000L);
        }
    }

    private static final class ActiveRequest {
        private final Request request;
        private final TaskMonitorAdapter monitor;

        private ActiveRequest(Request request, TaskMonitorAdapter monitor) {
            this.request = request;
            this.monitor = monitor;
        }
    }

    private static final class ListingCursor {
        private final String direction;
        private final boolean includeBytes;
        private final String address;

        private ListingCursor(String direction, boolean includeBytes, String address) {
            this.direction = direction;
            this.includeBytes = includeBytes;
            this.address = address;
        }
    }

    private static final class SymbolCursor {
        private final String binding;
        private final long symbolId;

        private SymbolCursor(String binding, long symbolId) {
            this.binding = binding;
            this.symbolId = symbolId;
        }
    }

    private static final class ReferenceCursor {
        private final String binding;
        private final ReferenceFact boundary;

        private ReferenceCursor(String binding, ReferenceFact boundary) {
            this.binding = binding;
            this.boundary = boundary;
        }
    }

    private static final class SymbolFact {
        private static final Comparator<SymbolFact> ORDER = Comparator
            .comparing((SymbolFact value) -> value.normalizedName)
            .thenComparing(value -> value.name)
            .thenComparing(value -> value.address)
            .thenComparing(value -> value.kind)
            .thenComparing(value -> value.namespace)
            .thenComparingLong(value -> value.id);

        private final long id;
        private final String name;
        private final String normalizedName;
        private final String address;
        private final String kind;
        private final String namespace;
        private final String source;
        private final boolean primary;
        private final String searchText;

        private SymbolFact(Symbol symbol) {
            id = symbol.getID();
            name = safeText(symbol.getName(), 1_024, "unnamed");
            normalizedName = name.toLowerCase(Locale.ROOT);
            address = GhidraExServer.address(symbol.getAddress());
            kind = symbolKind(symbol);
            namespace = safeText(
                symbol.getParentNamespace() == null
                    ? "Global"
                    : symbol.getParentNamespace().getName(true),
                2_048,
                "Global"
            );
            source = safeText(symbol.getSource().toString(), 128, "UNKNOWN");
            primary = symbol.isPrimary();
            searchText = (name + "\u0000" + namespace).toLowerCase(Locale.ROOT);
        }

        private Map<String, Object> json() {
            Map<String, Object> value = object();
            value.put("name", name);
            value.put("address", address);
            value.put("kind", kind);
            value.put("namespace", namespace);
            value.put("source", source);
            value.put("primary", primary);
            return value;
        }
    }

    private static final class ReferenceFact {
        private static final Comparator<ReferenceFact> ORDER = Comparator
            .comparing((ReferenceFact value) -> value.from)
            .thenComparing(value -> value.to)
            .thenComparing(value -> value.type)
            .thenComparingInt(value -> value.operandIndex)
            .thenComparing(value -> value.primary)
            .thenComparing(value -> value.external)
            .thenComparing(value -> value.source);

        private final String from;
        private final String to;
        private final String type;
        private final int operandIndex;
        private final boolean primary;
        private final boolean external;
        private final String source;

        private ReferenceFact(Reference reference) {
            this(
                address(reference.getFromAddress()),
                address(reference.getToAddress()),
                safeText(reference.getReferenceType().getName(), 128, "UNKNOWN"),
                reference.getOperandIndex(),
                reference.isPrimary(),
                reference.isExternalReference() || reference.getToAddress().isExternalAddress(),
                safeText(reference.getSource().toString(), 128, "UNKNOWN")
            );
        }

        private ReferenceFact(
                String from,
                String to,
                String type,
                int operandIndex,
                boolean primary,
                boolean external,
                String source) {
            this.from = from;
            this.to = to;
            this.type = type;
            this.operandIndex = operandIndex;
            this.primary = primary;
            this.external = external;
            this.source = source;
        }

        private String key() {
            return from + "\u0000" + to + "\u0000" + type + "\u0000" + operandIndex +
                "\u0000" + primary + "\u0000" + external + "\u0000" + source;
        }

        private Map<String, Object> json() {
            Map<String, Object> value = object();
            value.put("fromAddress", from);
            value.put("toAddress", to);
            value.put("type", type);
            if (operandIndex >= -1 && operandIndex <= 65_535) {
                value.put("operandIndex", operandIndex);
            }
            value.put("primary", primary);
            value.put("external", external);
            return value;
        }
    }

    private static final class RequestFailure extends Exception {
        private final String code;
        private final boolean retryable;
        private Integer retryAfterMs;
        private Map<String, Object> details;

        private RequestFailure(String code, String message, boolean retryable) {
            super(message);
            this.code = code;
            this.retryable = retryable;
        }
    }

    private static final class FrameException extends Exception {
        private FrameException(String message) {
            super(message);
        }
    }

    /** Strict, duplicate-key-rejecting JSON reader and compact ASCII writer. */
    private static final class Json {
        private static Object parse(String document) {
            return new Parser(document).parse();
        }

        private static String write(Object value) {
            StringBuilder output = new StringBuilder();
            append(output, value);
            return output.toString();
        }

        private static void append(StringBuilder output, Object value) {
            if (value == null) {
                output.append("null");
            }
            else if (value instanceof String text) {
                string(output, text);
            }
            else if (value instanceof Boolean || value instanceof Byte ||
                     value instanceof Short || value instanceof Integer ||
                     value instanceof Long) {
                output.append(value);
            }
            else if (value instanceof BigDecimal decimal) {
                output.append(decimal.toPlainString());
            }
            else if (value instanceof Map<?, ?> map) {
                output.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        output.append(',');
                    }
                    first = false;
                    string(output, String.valueOf(entry.getKey()));
                    output.append(':');
                    append(output, entry.getValue());
                }
                output.append('}');
            }
            else if (value instanceof Iterable<?> iterable) {
                output.append('[');
                boolean first = true;
                for (Object item : iterable) {
                    if (!first) {
                        output.append(',');
                    }
                    first = false;
                    append(output, item);
                }
                output.append(']');
            }
            else {
                throw new IllegalArgumentException("Unsupported JSON value type");
            }
        }

        private static void string(StringBuilder output, String raw) {
            String value = sanitize(raw);
            output.append('"');
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                switch (character) {
                    case '"' -> output.append("\\\"");
                    case '\\' -> output.append("\\\\");
                    case '\b' -> output.append("\\b");
                    case '\f' -> output.append("\\f");
                    case '\n' -> output.append("\\n");
                    case '\r' -> output.append("\\r");
                    case '\t' -> output.append("\\t");
                    default -> {
                        if (character < 0x20 || character > 0x7e) {
                            output.append(String.format("\\u%04x", (int) character));
                        }
                        else {
                            output.append(character);
                        }
                    }
                }
            }
            output.append('"');
        }

        private static final class Parser {
            private final String source;
            private int index;

            private Parser(String source) {
                this.source = source;
            }

            private Object parse() {
                skipWhitespace();
                Object value = value(0);
                skipWhitespace();
                if (index != source.length()) {
                    throw syntax("Trailing JSON content");
                }
                return value;
            }

            private Object value(int depth) {
                if (depth > MAX_JSON_DEPTH) {
                    throw syntax("JSON nesting exceeds 32 levels");
                }
                if (index >= source.length()) {
                    throw syntax("Unexpected end of JSON");
                }
                return switch (source.charAt(index)) {
                    case '{' -> object(depth + 1);
                    case '[' -> array(depth + 1);
                    case '"' -> string();
                    case 't' -> literal("true", Boolean.TRUE);
                    case 'f' -> literal("false", Boolean.FALSE);
                    case 'n' -> literal("null", null);
                    default -> number();
                };
            }

            private Map<String, Object> object(int depth) {
                expect('{');
                skipWhitespace();
                Map<String, Object> result = new LinkedHashMap<>();
                if (consume('}')) {
                    return result;
                }
                while (true) {
                    skipWhitespace();
                    if (index >= source.length() || source.charAt(index) != '"') {
                        throw syntax("JSON object key must be a string");
                    }
                    String key = string();
                    if (key.codePointCount(0, key.length()) > 256) {
                        throw syntax("JSON object key is too long");
                    }
                    if (result.containsKey(key)) {
                        throw syntax("Duplicate JSON object key");
                    }
                    skipWhitespace();
                    expect(':');
                    skipWhitespace();
                    result.put(key, value(depth));
                    if (result.size() > MAX_JSON_OBJECT_FIELDS) {
                        throw syntax("JSON object has too many fields");
                    }
                    skipWhitespace();
                    if (consume('}')) {
                        return result;
                    }
                    expect(',');
                }
            }

            private List<Object> array(int depth) {
                expect('[');
                skipWhitespace();
                List<Object> result = new ArrayList<>();
                if (consume(']')) {
                    return result;
                }
                while (true) {
                    skipWhitespace();
                    result.add(value(depth));
                    if (result.size() > MAX_JSON_ARRAY_ITEMS) {
                        throw syntax("JSON array has too many items");
                    }
                    skipWhitespace();
                    if (consume(']')) {
                        return result;
                    }
                    expect(',');
                }
            }

            private String string() {
                expect('"');
                StringBuilder result = new StringBuilder();
                while (index < source.length()) {
                    char character = source.charAt(index++);
                    if (character == '"') {
                        String value = result.toString();
                        validateSurrogates(value);
                        return value;
                    }
                    if (character == '\\') {
                        if (index >= source.length()) {
                            throw syntax("Incomplete JSON escape");
                        }
                        char escaped = source.charAt(index++);
                        switch (escaped) {
                            case '"', '\\', '/' -> result.append(escaped);
                            case 'b' -> result.append('\b');
                            case 'f' -> result.append('\f');
                            case 'n' -> result.append('\n');
                            case 'r' -> result.append('\r');
                            case 't' -> result.append('\t');
                            case 'u' -> result.append(unicodeEscape());
                            default -> throw syntax("Invalid JSON escape");
                        }
                    }
                    else {
                        if (character < 0x20) {
                            throw syntax("Raw control character in JSON string");
                        }
                        result.append(character);
                    }
                }
                throw syntax("Unterminated JSON string");
            }

            private char unicodeEscape() {
                if (index + 4 > source.length()) {
                    throw syntax("Incomplete Unicode escape");
                }
                int value = 0;
                for (int count = 0; count < 4; count++) {
                    int digit = Character.digit(source.charAt(index++), 16);
                    if (digit < 0) {
                        throw syntax("Invalid Unicode escape");
                    }
                    value = value * 16 + digit;
                }
                return (char) value;
            }

            private Object number() {
                int start = index;
                if (consume('-') && index >= source.length()) {
                    throw syntax("Invalid JSON number");
                }
                if (consume('0')) {
                    if (index < source.length() && Character.isDigit(source.charAt(index))) {
                        throw syntax("JSON number has a leading zero");
                    }
                }
                else {
                    requireDigits();
                }
                boolean decimal = false;
                if (consume('.')) {
                    decimal = true;
                    requireDigits();
                }
                if (consume('e') || consume('E')) {
                    decimal = true;
                    consume('+');
                    consume('-');
                    requireDigits();
                }
                String text = source.substring(start, index);
                try {
                    return decimal ? new BigDecimal(text) : Long.valueOf(text);
                }
                catch (NumberFormatException error) {
                    throw syntax("JSON number is outside the supported range");
                }
            }

            private void requireDigits() {
                int start = index;
                while (index < source.length() && Character.isDigit(source.charAt(index))) {
                    index++;
                }
                if (index == start) {
                    throw syntax("Invalid JSON number");
                }
            }

            private Object literal(String text, Object value) {
                if (!source.startsWith(text, index)) {
                    throw syntax("Invalid JSON literal");
                }
                index += text.length();
                return value;
            }

            private boolean consume(char expected) {
                if (index < source.length() && source.charAt(index) == expected) {
                    index++;
                    return true;
                }
                return false;
            }

            private void expect(char expected) {
                if (!consume(expected)) {
                    throw syntax("Expected '" + expected + "'");
                }
            }

            private void skipWhitespace() {
                while (index < source.length()) {
                    char character = source.charAt(index);
                    if (character != ' ' && character != '\t' &&
                        character != '\r' && character != '\n') {
                        return;
                    }
                    index++;
                }
            }

            private IllegalArgumentException syntax(String message) {
                return new IllegalArgumentException(message + " at character " + index);
            }

            private static void validateSurrogates(String value) {
                for (int position = 0; position < value.length(); position++) {
                    char character = value.charAt(position);
                    if (Character.isHighSurrogate(character)) {
                        if (position + 1 >= value.length() ||
                            !Character.isLowSurrogate(value.charAt(position + 1))) {
                            throw new IllegalArgumentException("Unpaired JSON surrogate");
                        }
                        position++;
                    }
                    else if (Character.isLowSurrogate(character)) {
                        throw new IllegalArgumentException("Unpaired JSON surrogate");
                    }
                }
            }
        }
    }
}
