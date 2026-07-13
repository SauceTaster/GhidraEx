package dev.ghidraex.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static dev.ghidraex.engine.SymbolDescriptor.SymbolKind.DATA;
import static dev.ghidraex.engine.SymbolDescriptor.SymbolKind.FUNCTION;
import static dev.ghidraex.engine.SymbolDescriptor.SymbolKind.IMPORT;
import static dev.ghidraex.engine.SymbolDescriptor.SymbolKind.LABEL;

/** Deterministic data source used to exercise the workbench without a Ghidra runtime. */
public final class SyntheticAnalysisEngine implements AnalysisEngine {
    public static final String PROGRAM_ID = "demo-firmware";
    public static final long IMAGE_BASE = 0x0040_1000L;
    public static final int INSTRUCTION_COUNT = 100_000;

    private final Duration stepDelay;
    private final ProgramDescriptor program = new ProgramDescriptor(
            PROGRAM_ID,
            "orbit-controller.bin",
            "x86:LE:64:default",
            IMAGE_BASE,
            0x2A_000L
    );

    private final List<SymbolDescriptor> initialSymbols = List.of(
            symbol("entry", 0x0040_1000L, FUNCTION, 7, "void entry(void)"),
            symbol("init_board", 0x0040_1060L, FUNCTION, 4, "int init_board(board_t *board)"),
            symbol("process_frame", 0x0040_1120L, FUNCTION, 18, "int process_frame(uint8_t *frame, size_t len)"),
            symbol("crc16", 0x0040_1240L, FUNCTION, 11, "uint16_t crc16(const uint8_t *data, size_t len)"),
            symbol("dispatch_table", 0x0040_3000L, DATA, 9, "handler_t dispatch_table[8]"),
            symbol("LAB_0040118c", 0x0040_118cL, LABEL, 2, "local label"),
            symbol("memcpy", 0x0060_0020L, IMPORT, 6, "void *memcpy(void *, const void *, size_t)")
    );

    public SyntheticAnalysisEngine(Duration stepDelay) {
        if (stepDelay.isNegative()) {
            throw new IllegalArgumentException("stepDelay must not be negative");
        }
        this.stepDelay = stepDelay;
    }

    public static SyntheticAnalysisEngine interactive() {
        return new SyntheticAnalysisEngine(Duration.ofMillis(32));
    }

    @Override
    public List<ProgramDescriptor> programs() {
        return List.of(program);
    }

    @Override
    public List<SymbolDescriptor> symbols(String programId) {
        requireProgram(programId);
        return initialSymbols;
    }

    @Override
    public int instructionCount(String programId) {
        requireProgram(programId);
        return INSTRUCTION_COUNT;
    }

    @Override
    public List<ListingInstruction> listing(String programId, int startInstruction, int instructionCount) {
        requireProgram(programId);
        if (startInstruction < 0 || instructionCount < 0) {
            throw new IllegalArgumentException("Listing range must be non-negative");
        }
        if (startInstruction >= INSTRUCTION_COUNT || instructionCount == 0) {
            return List.of();
        }

        int end = startInstruction + Math.min(instructionCount, INSTRUCTION_COUNT - startInstruction);
        var page = new ArrayList<ListingInstruction>(end - startInstruction);
        for (int index = startInstruction; index < end; index++) {
            long address = IMAGE_BASE + index * 4L;
            page.add(new ListingInstruction(
                    index,
                    address,
                    bytesFor(index),
                    mnemonicFor(index),
                    operandsFor(index, address),
                    labelFor(address)
            ));
        }
        return List.copyOf(page);
    }

    @Override
    public String renderListing(String programId, int startInstruction, int instructionCount) {
        var result = new StringBuilder(32_000);
        result.append("; orbit-controller.bin — synthetic x86-64 listing\n")
                .append("; image base 00401000  |  100,000 logical instructions\n\n");

        for (var row : listing(programId, startInstruction, instructionCount)) {
            if (!row.label().isEmpty()) {
                result.append('\n').append(row.label()).append(':').append('\n');
            }
            result.append(String.format(Locale.ROOT, "%08x  %-12s  %-8s %s%n",
                    row.address(), row.bytes(), row.mnemonic(), row.operands()));
            if (row.ordinal() % 19 == 18) {
                result.append('\n');
            }
        }
        return result.toString();
    }

    @Override
    public String decompile(String programId, long functionAddress) {
        requireProgram(programId);
        var symbol = initialSymbols.stream()
                .filter(candidate -> candidate.address() == functionAddress && candidate.kind() == FUNCTION)
                .findFirst()
                .orElse(initialSymbols.getFirst());

        return switch (symbol.name()) {
            case "process_frame" -> processFramePseudocode();
            case "crc16" -> crcPseudocode();
            case "init_board" -> initBoardPseudocode();
            default -> entryPseudocode();
        };
    }

    @Override
    public AnalysisResult analyze(String programId, AnalysisMonitor monitor) {
        requireProgram(programId);
        var started = Instant.now();
        int steps = 40;

        for (int step = 0; step < steps; step++) {
            monitor.checkCancelled();
            String phase = step < 10 ? "Discovering entry points"
                    : step < 24 ? "Propagating function signatures"
                    : step < 34 ? "Recovering references"
                    : "Finalizing symbol graph";
            monitor.report((step + 1.0) / steps, phase,
                    "Block " + (step + 1) + " of " + steps);
            pauseBetweenSteps(monitor);
        }

        var discovered = new ArrayList<>(initialSymbols);
        for (int index = 0; index < 12; index++) {
            discovered.add(symbol(
                    "FUN_" + String.format(Locale.ROOT, "%08x", IMAGE_BASE + 0x180 + index * 0x34L),
                    IMAGE_BASE + 0x180 + index * 0x34L,
                    FUNCTION,
                    1 + (index * 3) % 13,
                    "int recovered_" + index + "(context_t *ctx)"
            ));
        }
        discovered.sort(Comparator.comparingLong(SymbolDescriptor::address));
        return new AnalysisResult(discovered, 12, 148, Duration.between(started, Instant.now()));
    }

    private void pauseBetweenSteps(AnalysisMonitor monitor) {
        if (stepDelay.isZero()) {
            return;
        }
        try {
            Thread.sleep(stepDelay.toMillis(), stepDelay.toNanosPart() % 1_000_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            monitor.checkCancelled();
            throw new IllegalStateException("Synthetic analysis was interrupted", interrupted);
        }
    }

    private String labelFor(long address) {
        return initialSymbols.stream()
                .filter(symbol -> symbol.address() == address)
                .findFirst()
                .map(SymbolDescriptor::name)
                .orElse("");
    }

    private static String bytesFor(int index) {
        return switch (index % 8) {
            case 0 -> "55 48 89 e5";
            case 1 -> "48 83 ec 20";
            case 2 -> "48 8b 47 08";
            case 3 -> "83 f8 00";
            case 4 -> "74 18";
            case 5 -> "48 01 d8";
            case 6 -> "e8 39 01 00 00";
            default -> "48 83 c4 20";
        };
    }

    private static String mnemonicFor(int index) {
        return switch (index % 8) {
            case 0 -> "push";
            case 1 -> "sub";
            case 2 -> "mov";
            case 3 -> "cmp";
            case 4 -> "je";
            case 5 -> "add";
            case 6 -> "call";
            default -> "add";
        };
    }

    private static String operandsFor(int index, long address) {
        return switch (index % 8) {
            case 0 -> "rbp                         ; function frame";
            case 1 -> "rsp, 0x20                   ; local storage";
            case 2 -> "rax, qword ptr [rdi+0x8]";
            case 3 -> "eax, 0x0";
            case 4 -> String.format(Locale.ROOT, "LAB_%08x", address + 0x1c);
            case 5 -> "rax, rbx";
            case 6 -> "crc16                      ; recovered call";
            default -> "rsp, 0x20";
        };
    }

    private static SymbolDescriptor symbol(
            String name,
            long address,
            SymbolDescriptor.SymbolKind kind,
            int references,
            String signature
    ) {
        return new SymbolDescriptor(name, address, kind, references, signature);
    }

    private static String entryPseudocode() {
        return """
                // orbit-controller.bin — synthetic decompilation
                // Function: entry @ 00401000

                void entry(void) {
                    board_t board;

                    if (init_board(&board) != 0) {
                        panic("board initialization failed");
                    }

                    while (board.running) {
                        frame_t frame = receive_frame(&board.transport);
                        if (frame.length != 0) {
                            process_frame(frame.bytes, frame.length);
                        }
                    }
                }
                """;
    }

    private static String initBoardPseudocode() {
        return """
                // Function: init_board @ 00401060

                int init_board(board_t *board) {
                    memset(board, 0, sizeof(board_t));
                    board->clock_hz = 48000000;
                    board->dispatch = dispatch_table;
                    board->running = configure_transport(&board->transport);
                    return board->running ? 0 : -1;
                }
                """;
    }

    private static String processFramePseudocode() {
        return """
                // Function: process_frame @ 00401120

                int process_frame(uint8_t *frame, size_t len) {
                    if (len < 4 || crc16(frame, len - 2) != read_u16(frame + len - 2)) {
                        return STATUS_BAD_FRAME;
                    }

                    uint8_t opcode = frame[0] & 7;
                    handler_t handler = dispatch_table[opcode];
                    return handler(frame + 1, len - 3);
                }
                """;
    }

    private static String crcPseudocode() {
        return """
                // Function: crc16 @ 00401240

                uint16_t crc16(const uint8_t *data, size_t len) {
                    uint16_t crc = 0xffff;
                    for (size_t i = 0; i < len; ++i) {
                        crc ^= (uint16_t)data[i] << 8;
                        for (int bit = 0; bit < 8; ++bit) {
                            crc = (crc & 0x8000) ? (crc << 1) ^ 0x1021 : crc << 1;
                        }
                    }
                    return crc;
                }
                """;
    }

    private static void requireProgram(String programId) {
        if (!PROGRAM_ID.equals(programId)) {
            throw new IllegalArgumentException("Unknown program: " + programId);
        }
    }
}
