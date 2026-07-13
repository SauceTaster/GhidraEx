package dev.ghidraex.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class SyntheticAnalysisEngine implements AnalysisEngine {
    private static final int LISTING_SIZE = 100_000;
    static final int MAX_LISTING_WINDOW = 512;
    private static final long IMAGE_BASE = 0x0000_0001_0040_0000L;
    private static final String[] MNEMONICS = {
            "push", "mov", "sub", "lea", "cmp", "jne", "xor", "call", "test", "je", "add", "pop"
    };

    private final ProgramInfo program = new ProgramInfo(
            "Nightfall", "telemetryd", "x86-64 / little endian", "Mach-O 64-bit", IMAGE_BASE,
            "a43b760ab3c62ffb682effab90b0676d398e9cc37248d84bdc4638fef7ab57d3");
    private final List<Symbol> symbols = buildSymbols();
    private final ScheduledExecutorService executor;

    public SyntheticAnalysisEngine() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "synthetic-analysis");
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newSingleThreadScheduledExecutor(factory);
    }

    @Override
    public ProgramInfo program() {
        return program;
    }

    @Override
    public int listingSize() {
        return LISTING_SIZE;
    }

    @Override
    public ListingWindow listingWindow(int startIndex, int requestedCount) {
        if (startIndex < 0 || startIndex > LISTING_SIZE) {
            throw new IndexOutOfBoundsException(startIndex);
        }
        if (requestedCount <= 0) {
            throw new IllegalArgumentException("requestedCount must be positive");
        }
        int count = Math.min(requestedCount, MAX_LISTING_WINDOW);
        int endIndex = Math.min(LISTING_SIZE, startIndex + count);
        List<DisassemblyLine> rows = new ArrayList<>(endIndex - startIndex);
        for (int index = startIndex; index < endIndex; index++) {
            rows.add(createListingLine(index));
        }
        return new ListingWindow(startIndex, LISTING_SIZE, rows);
    }

    private static DisassemblyLine createListingLine(int index) {
        long address = IMAGE_BASE + (long) index * 4;
        String mnemonic = MNEMONICS[Math.floorMod(index * 7 + index / 13, MNEMONICS.length)];
        DisassemblyLine.FlowType flow = switch (mnemonic) {
            case "call" -> DisassemblyLine.FlowType.CALL;
            case "jne", "je" -> DisassemblyLine.FlowType.CONDITIONAL;
            case "pop" -> index % 17 == 0
                    ? DisassemblyLine.FlowType.RETURN
                    : DisassemblyLine.FlowType.NORMAL;
            default -> DisassemblyLine.FlowType.NORMAL;
        };
        String bytes = bytesFor(index, mnemonic);
        String operands = operandsFor(index, mnemonic, address);
        String comment = commentFor(index, mnemonic);
        return new DisassemblyLine(index, address, bytes, mnemonic, operands, comment, flow);
    }

    @Override
    public List<Symbol> searchSymbols(String query, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String normalized = Objects.requireNonNullElse(query, "").strip().toLowerCase(Locale.ROOT);
        return symbols.stream()
                .filter(symbol -> normalized.isEmpty() || matches(symbol, normalized))
                .sorted(Comparator
                        .comparingInt((Symbol symbol) -> score(symbol, normalized))
                        .thenComparing(Symbol::name, String.CASE_INSENSITIVE_ORDER))
                .limit(limit)
                .toList();
    }

    @Override
    public List<String> decompile(String symbolName) {
        String function = symbolName == null || symbolName.isBlank() ? "parse_packet" : symbolName;
        List<String> lines = new ArrayList<>();
        lines.add("int64_t " + function + "(Packet *packet, Session *session) {");
        lines.add("    uint32_t payload_size = packet->payload_size;");
        lines.add("    uint8_t *cursor = packet->payload;");
        lines.add("");
        lines.add("    if (payload_size < HEADER_SIZE) {");
        lines.add("        log_error(\"truncated packet\", payload_size);");
        lines.add("        return STATUS_INVALID;");
        lines.add("    }");
        lines.add("");
        lines.add("    uint16_t message_type = read_u16_be(cursor + 2);");
        lines.add("    uint32_t sequence = read_u32_be(cursor + 4);");
        lines.add("    session->last_sequence = sequence;");
        lines.add("");
        lines.add("    switch (message_type) {");
        lines.add("        case MSG_HELLO:");
        lines.add("            return handle_hello(session, cursor + HEADER_SIZE);");
        lines.add("        case MSG_DATA:");
        lines.add("            if ((packet->flags & FLAG_ENCRYPTED) != 0) {");
        lines.add("                decrypt_payload(session->key, cursor, payload_size);");
        lines.add("            }");
        lines.add("            return dispatch_payload(session, cursor, payload_size);");
        lines.add("        case MSG_CLOSE:");
        lines.add("            session->state = SESSION_CLOSING;");
        lines.add("            return flush_pending(session);");
        lines.add("        default:");
        lines.add("            telemetry_increment(UNKNOWN_MESSAGE);");
        lines.add("            return STATUS_UNSUPPORTED;");
        lines.add("    }");
        lines.add("}");
        lines.add("");
        lines.add("// Recovered from 47 basic blocks, confidence 0.94");
        return List.copyOf(lines);
    }

    @Override
    public AnalysisJob startAnalysis(Consumer<AnalysisProgress> progressListener) {
        Objects.requireNonNull(progressListener, "progressListener");
        SyntheticJob job = new SyntheticJob("Auto-analysis");
        AtomicInteger tick = new AtomicInteger();
        progressListener.accept(new AnalysisProgress(0.0, "Preparing analyzers", true));
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
            if (job.isCancelled()) {
                return;
            }
            int current = tick.incrementAndGet();
            double fraction = Math.min(1.0, current / 48.0);
            progressListener.accept(new AnalysisProgress(fraction, phaseFor(fraction), fraction < 0.98));
            if (fraction >= 1.0) {
                job.complete();
            }
        }, 80, 70, TimeUnit.MILLISECONDS);
        job.attach(future);
        return job;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static String bytesFor(int index, String mnemonic) {
        int a = (index * 31 + 0x48) & 0xff;
        int b = (index * 17 + 0x83) & 0xff;
        int c = (index * 7 + 0xec) & 0xff;
        return switch (mnemonic) {
            case "call" -> "e8 %02x %02x %02x %02x".formatted(a, b, c, (a ^ c) & 0xff);
            case "jne", "je" -> "0f %02x %02x %02x".formatted(a, b, c);
            default -> "%02x %02x %02x %02x".formatted(a, b, c, (b ^ c) & 0xff);
        };
    }

    private static String operandsFor(int index, String mnemonic, long address) {
        return switch (mnemonic) {
            case "push", "pop" -> "rbp";
            case "mov" -> index % 2 == 0 ? "rbp, rsp" : "rax, qword ptr [rdi + 0x18]";
            case "sub", "add" -> "rsp, 0x%02x".formatted(0x20 + (index % 8) * 8);
            case "lea" -> "rdi, [rip + 0x%04x]".formatted(0x120 + index % 0x800);
            case "cmp", "test" -> "eax, eax";
            case "xor" -> "ecx, ecx";
            case "call" -> functionFor(index);
            case "jne", "je" -> "0x%016x".formatted(address + 0x10 + (index % 7) * 4L);
            default -> "";
        };
    }

    private static String commentFor(int index, String mnemonic) {
        if ("call".equals(mnemonic)) {
            return "← " + functionFor(index);
        }
        if (index % 97 == 0) {
            return "packet state transition";
        }
        if (index % 43 == 0) {
            return "XREF[3]";
        }
        return "";
    }

    private static String functionFor(int index) {
        String[] names = {"parse_packet", "decrypt_payload", "dispatch_payload", "telemetry_increment", "memcpy"};
        return names[Math.floorMod(index / 3, names.length)];
    }

    private static String phaseFor(double fraction) {
        if (fraction < 0.18) return "Discovering functions";
        if (fraction < 0.42) return "Propagating constants";
        if (fraction < 0.67) return "Recovering data types";
        if (fraction < 0.88) return "Building cross-references";
        if (fraction < 1.0) return "Finalizing indexes";
        return "Analysis complete";
    }

    private static boolean matches(Symbol symbol, String query) {
        String name = symbol.name().toLowerCase(Locale.ROOT);
        String namespace = symbol.namespace().toLowerCase(Locale.ROOT);
        String address = symbol.formattedAddress();
        return name.contains(query) || namespace.contains(query) || address.contains(query.replace("0x", ""));
    }

    private static int score(Symbol symbol, String query) {
        if (query.isEmpty()) {
            return symbol.kind() == Symbol.Kind.FUNCTION ? 0 : 1;
        }
        String name = symbol.name().toLowerCase(Locale.ROOT);
        if (name.equals(query)) return 0;
        if (name.startsWith(query)) return 1;
        if (name.contains(query)) return 2;
        return 3;
    }

    private static List<Symbol> buildSymbols() {
        List<Symbol> result = new ArrayList<>();
        String[] names = {
                "entry", "parse_packet", "decrypt_payload", "dispatch_payload", "handle_hello",
                "flush_pending", "read_u16_be", "read_u32_be", "telemetry_increment", "log_error",
                "session_create", "session_destroy", "validate_header", "derive_session_key", "memcpy"
        };
        for (int index = 0; index < names.length; index++) {
            Symbol.Kind kind = index == names.length - 1 ? Symbol.Kind.IMPORT : Symbol.Kind.FUNCTION;
            result.add(new Symbol(names[index], IMAGE_BASE + index * 0x130L, kind,
                    kind == Symbol.Kind.IMPORT ? "EXTERNAL" : "telemetryd"));
        }
        for (int index = 0; index < 420; index++) {
            result.add(new Symbol("sub_%08x".formatted(0x10001000 + index * 0x50),
                    IMAGE_BASE + 0x2000L + index * 0x50L, Symbol.Kind.FUNCTION, "telemetryd"));
        }
        for (int index = 0; index < 80; index++) {
            result.add(new Symbol("DAT_%08x".formatted(0x1000c000 + index * 16),
                    IMAGE_BASE + 0xc000L + index * 16L, Symbol.Kind.DATA, ".data"));
        }
        return List.copyOf(result);
    }

    private static final class SyntheticJob implements AnalysisJob {
        private final String id = UUID.randomUUID().toString();
        private final String title;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private volatile ScheduledFuture<?> future;

        private SyntheticJob(String title) {
            this.title = title;
        }

        private void attach(ScheduledFuture<?> future) {
            this.future = future;
            completion.whenComplete((unused, error) -> future.cancel(false));
            if (cancelled.get()) {
                future.cancel(false);
            }
        }

        private void complete() {
            completion.complete(null);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                ScheduledFuture<?> scheduled = future;
                if (scheduled != null) {
                    scheduled.cancel(false);
                }
                completion.complete(null);
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public CompletableFuture<Void> completion() {
            return completion;
        }
    }
}
