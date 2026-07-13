package ex.ghidra.web;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Deterministic stand-in for Ghidra's program model. The public surface uses coarse,
 * immutable snapshots so a real engine adapter can replace this class later.
 */
final class SyntheticEngine implements AutoCloseable {
    record ProjectState(String name, String binary, String format, String architecture,
                        String imageBase, String sha256) {}

    record AnalysisState(String status, int progress, String phase, long elapsedMs,
                         int functionsDiscovered) {}

    record SymbolRecord(String name, String address, String namespace, String kind,
                        int refs, int confidence) {}

    record ListingRow(String address, String bytes, String mnemonic, String operands,
                      String annotation, String flow) {}

    record ListingExtent(int totalInstructions, String minAddress, String maxAddress,
                         int viewportRows) {}

    record ListingWindow(int apiVersion, String requestedAddress, int totalInstructions,
                         String minAddress, String maxAddress, List<ListingRow> rows) {}

    private record InstructionTemplate(String bytes, String mnemonic, String operands,
                                       String annotation, String flow) {}

    record InspectorState(String address, String functionName, String signature,
                          String segment, String offset, int xrefsIn, int xrefsOut,
                          int stackDelta, String prototypeSource) {}

    record WorkbenchSnapshot(int apiVersion, ProjectState project, AnalysisState analysis,
                             List<SymbolRecord> symbols, ListingExtent listingInfo,
                             List<ListingRow> listing,
                             String decompiler, InspectorState inspector) {}

    private static final int BASE_FUNCTIONS = 1_284;
    static final int TOTAL_INSTRUCTIONS = 100_000;
    static final int MAX_VIEWPORT_ROWS = 120;
    private static final long LISTING_BASE = 0x00401000L;
    private static final List<InstructionTemplate> INSTRUCTION_TEMPLATES = createInstructionTemplates();
    private static final int[] TEMPLATE_OFFSETS = createTemplateOffsets();
    private static final int TEMPLATE_CYCLE_BYTES = TEMPLATE_OFFSETS[TEMPLATE_OFFSETS.length - 1];
    private final ProjectState project = new ProjectState(
            "Quartz Research",
            "quartz-agent",
            "ELF 64-bit LSB pie executable",
            "x86-64 / little endian",
            "0x00400000",
            "1fb99e832e462ed957ba537a22d1d6303d7b418f7f71340447e810b28e5afc61"
    );
    private final List<SymbolRecord> symbols = createSymbols();
    private final CopyOnWriteArrayList<Consumer<AnalysisState>> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("synthetic-analysis").factory());

    private String status = "idle";
    private int progress;
    private String phase = "Ready to analyze";
    private long startedAtNanos;
    private int functionsDiscovered = BASE_FUNCTIONS;

    SyntheticEngine() {
        scheduler.scheduleAtFixedRate(this::advanceAnalysis, 300, 300, TimeUnit.MILLISECONDS);
    }

    synchronized WorkbenchSnapshot snapshot() {
        ListingWindow viewport = listingWindow("0x004011d0", 50);
        return new WorkbenchSnapshot(
                1,
                project,
                analysisState(),
                List.copyOf(symbols.subList(0, Math.min(18, symbols.size()))),
                new ListingExtent(
                        viewport.totalInstructions(),
                        viewport.minAddress(),
                        viewport.maxAddress(),
                        viewport.rows().size()),
                viewport.rows(),
                decompilerSource(),
                new InspectorState(
                        "0x004011d0",
                        "FUN_004011d0",
                        "int64_t FUN_004011d0(char *param_1, uint32_t param_2)",
                        ".text",
                        "0x000011d0",
                        12,
                        7,
                        -48,
                        "Decompiler inference · calling convention confirmed"
                )
        );
    }

    synchronized AnalysisState startAnalysis() {
        if ("running".equals(status)) {
            return analysisState();
        }
        status = "running";
        progress = 2;
        phase = "Hashing executable regions";
        startedAtNanos = System.nanoTime();
        functionsDiscovered = BASE_FUNCTIONS;
        return publishAndReturn();
    }

    synchronized AnalysisState cancelAnalysis() {
        if ("running".equals(status)) {
            status = "cancelled";
            phase = "Analysis cancelled";
        }
        return publishAndReturn();
    }

    synchronized AnalysisState analysisState() {
        long elapsed = startedAtNanos == 0
                ? 0
                : Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
        return new AnalysisState(status, progress, phase, elapsed, functionsDiscovered);
    }

    List<SymbolRecord> searchSymbols(String rawQuery) {
        String query = Objects.requireNonNullElse(rawQuery, "").strip().toLowerCase(Locale.ROOT);
        return symbols.stream()
                .filter(symbol -> query.isEmpty()
                        || symbol.name().toLowerCase(Locale.ROOT).contains(query)
                        || symbol.namespace().toLowerCase(Locale.ROOT).contains(query)
                        || symbol.kind().toLowerCase(Locale.ROOT).contains(query))
                .limit(30)
                .toList();
    }

    ListingWindow listingWindow(String requestedAddress, int requestedRows) {
        int rows = Math.clamp(requestedRows, 8, MAX_VIEWPORT_ROWS);
        long address = parseAddress(requestedAddress, 0x004011d0L);
        int closest = nearestOrdinal(address);
        int from = Math.max(0, closest - rows / 3);
        int to = Math.min(TOTAL_INSTRUCTIONS, from + rows);
        from = Math.max(0, to - rows);
        List<ListingRow> viewport = new ArrayList<>(to - from);
        for (int ordinal = from; ordinal < to; ordinal++) {
            viewport.add(listingRowAtOrdinal(ordinal));
        }
        return new ListingWindow(
                1,
                formatAddress(address),
                TOTAL_INSTRUCTIONS,
                formatAddress(addressForOrdinal(0)),
                formatAddress(addressForOrdinal(TOTAL_INSTRUCTIONS - 1)),
                List.copyOf(viewport));
    }

    AutoCloseable subscribe(Consumer<AnalysisState> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void advanceAnalysis() {
        AnalysisState state;
        synchronized (this) {
            if (!"running".equals(status)) {
                return;
            }
            progress = Math.min(100, progress + 3);
            phase = phaseFor(progress);
            functionsDiscovered = BASE_FUNCTIONS + progress * 11 + progress * progress / 85;
            if (progress == 100) {
                status = "complete";
                phase = "Program model complete";
            }
            state = analysisState();
        }
        publish(state);
    }

    private synchronized AnalysisState publishAndReturn() {
        AnalysisState state = analysisState();
        publish(state);
        return state;
    }

    private void publish(AnalysisState state) {
        listeners.forEach(listener -> {
            try {
                listener.accept(state);
            } catch (RuntimeException ignored) {
                // A disconnected client must not interrupt the analysis pipeline.
            }
        });
    }

    private static String phaseFor(int currentProgress) {
        if (currentProgress < 16) return "Hashing executable regions";
        if (currentProgress < 34) return "Discovering entry points";
        if (currentProgress < 56) return "Propagating function boundaries";
        if (currentProgress < 74) return "Recovering parameters and types";
        if (currentProgress < 91) return "Resolving cross references";
        return "Finalizing program model";
    }

    private static long parseAddress(String address, long fallback) {
        if (address == null) return fallback;
        try {
            return Long.parseUnsignedLong(address.strip().replaceFirst("^0[xX]", ""), 16);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static long addressForOrdinal(int ordinal) {
        if (ordinal < 0 || ordinal >= TOTAL_INSTRUCTIONS) {
            throw new IllegalArgumentException("Instruction ordinal is outside the synthetic program");
        }
        int cycle = ordinal / INSTRUCTION_TEMPLATES.size();
        int templateIndex = ordinal % INSTRUCTION_TEMPLATES.size();
        return LISTING_BASE + (long) cycle * TEMPLATE_CYCLE_BYTES + TEMPLATE_OFFSETS[templateIndex];
    }

    private static int nearestOrdinal(long requestedAddress) {
        if (requestedAddress <= LISTING_BASE) return 0;
        if (requestedAddress >= addressForOrdinal(TOTAL_INSTRUCTIONS - 1)) return TOTAL_INSTRUCTIONS - 1;

        int low = 0;
        int high = TOTAL_INSTRUCTIONS - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            long candidate = addressForOrdinal(middle);
            if (candidate == requestedAddress) return middle;
            if (candidate < requestedAddress) low = middle + 1;
            else high = middle - 1;
        }
        int before = Math.max(0, high);
        int after = Math.min(TOTAL_INSTRUCTIONS - 1, low);
        return requestedAddress - addressForOrdinal(before) <= addressForOrdinal(after) - requestedAddress
                ? before : after;
    }

    private static ListingRow listingRowAtOrdinal(int ordinal) {
        InstructionTemplate template = INSTRUCTION_TEMPLATES.get(ordinal % INSTRUCTION_TEMPLATES.size());
        long address = addressForOrdinal(ordinal);
        String operands = template.operands();
        if ("branch".equals(template.flow()) && operands.startsWith("0x")) {
            int direction = ordinal % 3 == 0 ? -1 : 1;
            int targetOrdinal = Math.clamp(ordinal + direction * (4 + ordinal % 17), 0, TOTAL_INSTRUCTIONS - 1);
            operands = formatAddress(addressForOrdinal(targetOrdinal));
        }
        return new ListingRow(
                formatAddress(address),
                template.bytes(),
                template.mnemonic(),
                operands,
                template.annotation(),
                template.flow());
    }

    private static String formatAddress(long address) {
        return "0x%08x".formatted(address);
    }

    private static int[] createTemplateOffsets() {
        int[] offsets = new int[INSTRUCTION_TEMPLATES.size() + 1];
        for (int index = 0; index < INSTRUCTION_TEMPLATES.size(); index++) {
            offsets[index + 1] = offsets[index] + instructionLength(INSTRUCTION_TEMPLATES.get(index));
        }
        return offsets;
    }

    private static int instructionLength(InstructionTemplate template) {
        if (template.bytes().isBlank()) return 1;
        return template.bytes().split(" ").length;
    }

    private static List<SymbolRecord> createSymbols() {
        List<SymbolRecord> result = new ArrayList<>();
        result.add(new SymbolRecord("FUN_004011d0", "0x004011d0", "quartz::session", "function", 12, 98));
        result.add(new SymbolRecord("validate_handshake", "0x00401224", "quartz::protocol", "function", 9, 96));
        result.add(new SymbolRecord("transport_send", "0x00401318", "quartz::transport", "function", 22, 99));
        result.add(new SymbolRecord("rotate_key", "0x00401374", "quartz::crypto", "function", 7, 91));
        result.add(new SymbolRecord("parse_manifest", "0x00401420", "quartz::config", "function", 18, 94));
        result.add(new SymbolRecord("dispatch_frame", "0x00401508", "quartz::protocol", "function", 31, 97));
        result.add(new SymbolRecord("session_nonce", "0x00605040", ".bss", "label", 16, 88));
        result.add(new SymbolRecord("server_identity", "0x006041a0", ".rodata", "label", 5, 87));
        result.add(new SymbolRecord("memcpy", "0x00400830", "EXTERNAL", "import", 43, 100));
        result.add(new SymbolRecord("memcmp", "0x00400870", "EXTERNAL", "import", 19, 100));
        result.add(new SymbolRecord("EVP_DecryptInit_ex", "0x00400920", "OPENSSL", "import", 4, 100));
        result.add(new SymbolRecord("clock_gettime", "0x00400990", "GLIBC", "import", 11, 100));
        result.add(new SymbolRecord("entry", "0x00401090", "GLOBAL", "function", 1, 100));
        result.add(new SymbolRecord("__libc_start_main", "0x004008f0", "GLIBC", "import", 1, 100));
        result.add(new SymbolRecord("frame_table", "0x00603880", ".data", "label", 27, 82));
        result.add(new SymbolRecord("cleanup_session", "0x004017d0", "quartz::session", "function", 14, 95));
        result.add(new SymbolRecord("telemetry_flush", "0x00401940", "quartz::telemetry", "function", 6, 90));
        result.add(new SymbolRecord("fatal_protocol_error", "0x00401ac8", "quartz::protocol", "function", 8, 98));
        result.add(new SymbolRecord("derive_session_key", "0x00401bf0", "quartz::crypto", "function", 5, 93));
        result.add(new SymbolRecord("load_root_certificate", "0x00401de0", "quartz::crypto", "function", 3, 92));
        return List.copyOf(result);
    }

    private static List<InstructionTemplate> createInstructionTemplates() {
        String[][] instructions = {
                {"f3 0f 1e fa", "ENDBR64", "", "control-flow landing", "normal"},
                {"55", "PUSH", "RBP", "save frame", "normal"},
                {"48 89 e5", "MOV", "RBP,RSP", "", "normal"},
                {"41 57", "PUSH", "R15", "", "normal"},
                {"41 56", "PUSH", "R14", "", "normal"},
                {"41 55", "PUSH", "R13", "", "normal"},
                {"41 54", "PUSH", "R12", "", "normal"},
                {"53", "PUSH", "RBX", "", "normal"},
                {"48 83 ec 38", "SUB", "RSP,0x38", "local stack frame", "normal"},
                {"48 89 7d c8", "MOV", "qword ptr [RBP-0x38],RDI", "param_1", "normal"},
                {"89 75 c4", "MOV", "dword ptr [RBP-0x3c],ESI", "param_2", "normal"},
                {"83 fe 1f", "CMP", "ESI,0x1f", "minimum frame size", "normal"},
                {"76 62", "JBE", "0x0040125a", "reject short frame", "branch"},
                {"48 8b 5f 08", "MOV", "RBX,qword ptr [RDI+0x8]", "session context", "normal"},
                {"48 85 db", "TEST", "RBX,RBX", "", "normal"},
                {"0f 84 a1 00 00 00", "JZ", "0x004012a4", "null session", "branch"},
                {"48 8d 75 d0", "LEA", "RSI,[RBP-0x30]", "handshake digest", "normal"},
                {"ba 10 00 00 00", "MOV", "EDX,0x10", "128-bit token", "normal"},
                {"48 89 df", "MOV", "RDI,RBX", "session", "normal"},
                {"e8 0d 00 00 00", "CALL", "validate_handshake", "cross-module call", "call"},
                {"85 c0", "TEST", "EAX,EAX", "validation result", "normal"},
                {"74 47", "JZ", "0x0040124d", "validation failed", "branch"},
                {"48 8b 45 c8", "MOV", "RAX,qword ptr [RBP-0x38]", "frame", "normal"},
                {"8b 50 14", "MOV", "EDX,dword ptr [RAX+0x14]", "payload length", "normal"},
                {"48 8d 70 18", "LEA", "RSI,[RAX+0x18]", "payload", "normal"},
                {"48 89 df", "MOV", "RDI,RBX", "session", "normal"},
                {"e8 73 00 00 00", "CALL", "transport_send", "send authenticated frame", "call"},
                {"41 89 c4", "MOV", "R12D,EAX", "preserve result", "normal"},
                {"83 f8 00", "CMP", "EAX,0x0", "", "normal"},
                {"7c 2e", "JL", "0x00401251", "transport error", "branch"},
                {"48 8b 43 20", "MOV", "RAX,qword ptr [RBX+0x20]", "message counter", "normal"},
                {"48 83 c0 01", "ADD", "RAX,0x1", "increment counter", "normal"},
                {"48 89 43 20", "MOV", "qword ptr [RBX+0x20],RAX", "", "normal"},
                {"48 3d ff 03 00 00", "CMP", "RAX,0x3ff", "key rotation interval", "normal"},
                {"76 0b", "JBE", "0x00401239", "", "branch"},
                {"48 89 df", "MOV", "RDI,RBX", "session", "normal"},
                {"e8 36 01 00 00", "CALL", "rotate_key", "rotate after 1024 frames", "call"},
                {"44 89 e0", "MOV", "EAX,R12D", "return transport result", "normal"},
                {"eb 19", "JMP", "0x00401255", "epilogue", "branch"},
                {"b8 ff ff ff ff", "MOV", "EAX,0xffffffff", "protocol error", "normal"},
                {"eb 12", "JMP", "0x00401255", "", "branch"},
                {"b8 fe ff ff ff", "MOV", "EAX,0xfffffffe", "transport error", "normal"},
                {"48 83 c4 38", "ADD", "RSP,0x38", "release stack", "normal"},
                {"5b", "POP", "RBX", "", "normal"},
                {"41 5c", "POP", "R12", "", "normal"},
                {"41 5d", "POP", "R13", "", "normal"},
                {"41 5e", "POP", "R14", "", "normal"},
                {"41 5f", "POP", "R15", "", "normal"},
                {"5d", "POP", "RBP", "", "normal"},
                {"c3", "RET", "", "", "return"}
        };
        List<InstructionTemplate> templates = new ArrayList<>(instructions.length);
        for (String[] instruction : instructions) {
            templates.add(new InstructionTemplate(
                    instruction[0],
                    instruction[1],
                    instruction[2],
                    instruction[3],
                    instruction[4]
            ));
        }
        return List.copyOf(templates);
    }

    private static String decompilerSource() {
        return """
                int64_t FUN_004011d0(char *param_1, uint32_t param_2)
                {
                    int32_t result;
                    session_t *session;
                    uint8_t digest[16];

                    if (param_2 < 0x20) {
                        return -1;
                    }

                    session = *(session_t **)(param_1 + 8);
                    if (session == NULL) {
                        return -1;
                    }

                    result = validate_handshake(session, digest, 0x10);
                    if (result == 0) {
                        return -1;
                    }

                    result = transport_send(session, param_1 + 0x18,
                                            *(uint32_t *)(param_1 + 0x14));
                    if (result < 0) {
                        return -2;
                    }

                    session->message_counter = session->message_counter + 1;
                    if (session->message_counter > 0x3ff) {
                        rotate_key(session); // periodic forward secrecy
                    }

                    return result;
                }
                """;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        listeners.clear();
    }
}
