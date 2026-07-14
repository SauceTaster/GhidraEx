package dev.ghidraex.script;

import dev.ghidraex.backend.BackendProbe;
import dev.ghidraex.debug.DebuggerModel;
import dev.ghidraex.engine.ProgramDescriptor;
import dev.ghidraex.engine.SymbolDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A deterministic command bridge for exercising REPL UX without claiming to host a language VM.
 */
public final class ReplModel {
    private static final Pattern CALL = Pattern.compile("([a-zA-Z][a-zA-Z0-9_.]*)\\((.*)\\);?");
    private static final Pattern QUOTED = Pattern.compile("[\"'](.*)[\"']");
    private static final int MAX_HISTORY = 200;

    public enum Language {
        PYTHON_CONTRACT("Python / PyGhidra contract", ">>> "),
        JAVA_CONTRACT("Java / GhidraScript contract", "ghidra> ");

        private final String displayName;
        private final String prompt;

        Language(String displayName, String prompt) {
            this.displayName = displayName;
            this.prompt = prompt;
        }

        public String displayName() {
            return displayName;
        }

        public String prompt() {
            return prompt;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    public enum ResultKind {
        OUTPUT,
        ERROR
    }

    public record Context(
            ProgramDescriptor program,
            List<SymbolDescriptor> symbols,
            SymbolDescriptor selectedSymbol,
            DebuggerModel.Snapshot debugger,
            BackendProbe.Status backend
    ) {
        public Context {
            Objects.requireNonNull(program, "program");
            symbols = List.copyOf(symbols);
            Objects.requireNonNull(selectedSymbol, "selectedSymbol");
            Objects.requireNonNull(debugger, "debugger");
            Objects.requireNonNull(backend, "backend");
        }
    }

    public record Entry(
            long sequence,
            Language language,
            String command,
            String output,
            ResultKind resultKind,
            OptionalLong navigationAddress
    ) {
        public Entry {
            Objects.requireNonNull(language, "language");
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(resultKind, "resultKind");
            Objects.requireNonNull(navigationAddress, "navigationAddress");
        }
    }

    private final List<Entry> history = new ArrayList<>();
    private long sequence;

    public synchronized Entry evaluate(Language language, String command, Context context) {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(context, "context");
        String source = command == null ? "" : command.strip();
        Entry result = evaluateCommand(++sequence, language, source, context);
        history.add(result);
        if (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }
        return result;
    }

    public synchronized List<Entry> history() {
        return List.copyOf(history);
    }

    public synchronized void clear() {
        history.clear();
    }

    private static Entry evaluateCommand(
            long sequence,
            Language language,
            String source,
            Context context
    ) {
        if (source.isBlank()) {
            return error(sequence, language, source, "Enter a bridge command; try help().");
        }

        String bare = stripTrailingSemicolon(source);
        String lower = bare.toLowerCase(Locale.ROOT);
        if (lower.equals("help") || lower.equals("help()")) {
            return output(sequence, language, source, """
                    Contract commands (no language VM is attached):
                      program()             current program metadata
                      symbol("query")       find symbols by name/address/signature
                      goTo(0x00401120)       navigate the native listing
                      debugger()            debugger state and program counter
                      backend()             active and discovered backend state
                    """, OptionalLong.empty());
        }
        if (lower.equals("program") || lower.equals("program()") || lower.equals("currentprogram")) {
            ProgramDescriptor program = context.program();
            return output(sequence, language, source,
                    "%s [%s] imageBase=0x%08x size=%d bytes".formatted(
                            program.name(), program.architecture(), program.imageBase(), program.byteSize()),
                    OptionalLong.empty());
        }
        if (lower.equals("debugger") || lower.equals("debugger()") || lower.equals("debug.status()")) {
            var debugger = context.debugger();
            return output(sequence, language, source,
                    "%s / %s / RIP=0x%08x / %d frame(s) [synthetic adapter]".formatted(
                            debugger.state(), debugger.stopReason(), debugger.programCounter(),
                            debugger.frames().size()), OptionalLong.empty());
        }
        if (lower.equals("backend") || lower.equals("backend()")) {
            BackendProbe.Status backend = context.backend();
            String discovered = backend.realBackendAvailable()
                    ? "Ghidra " + backend.ghidraVersion() + " available at " + backend.ghidraHome()
                    : backend.discoveryState().name().toLowerCase(Locale.ROOT);
            return output(sequence, language, source,
                    "active=" + backend.activeBackend() + "; discovered=" + discovered,
                    OptionalLong.empty());
        }

        Matcher call = CALL.matcher(bare);
        if (!call.matches()) {
            return unsupported(sequence, language, source);
        }
        String function = call.group(1).toLowerCase(Locale.ROOT);
        String argument = call.group(2).strip();
        return switch (function) {
            case "symbol", "getsymbol" -> findSymbol(sequence, language, source, argument, context);
            case "goto", "go_to", "navigate" -> navigate(sequence, language, source, argument, context);
            default -> unsupported(sequence, language, source);
        };
    }

    private static Entry findSymbol(
            long sequence,
            Language language,
            String source,
            String argument,
            Context context
    ) {
        Matcher quoted = QUOTED.matcher(argument);
        if (!quoted.matches()) {
            return error(sequence, language, source, "symbol() expects one quoted query.");
        }
        String query = quoted.group(1).toLowerCase(Locale.ROOT);
        var matches = context.symbols().stream()
                .filter(symbol -> symbol.name().toLowerCase(Locale.ROOT).contains(query)
                        || symbol.signature().toLowerCase(Locale.ROOT).contains(query)
                        || String.format(Locale.ROOT, "%08x", symbol.address()).contains(query))
                .limit(20)
                .toList();
        if (matches.isEmpty()) {
            return output(sequence, language, source, "No symbols matched \"" + query + "\".",
                    OptionalLong.empty());
        }
        String rendered = matches.stream()
                .map(symbol -> "0x%08x  %-18s %s".formatted(
                        symbol.address(), symbol.name(), symbol.signature()))
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElseThrow();
        return output(sequence, language, source, rendered, OptionalLong.empty());
    }

    private static Entry navigate(
            long sequence,
            Language language,
            String source,
            String argument,
            Context context
    ) {
        try {
            long address = Long.decode(argument);
            long start = context.program().imageBase();
            long end = start + context.program().byteSize();
            if (address < start || address >= end) {
                return error(sequence, language, source,
                        "Address is outside the active program image.");
            }
            return output(sequence, language, source,
                    "Navigation requested for 0x%08x.".formatted(address),
                    OptionalLong.of(address));
        } catch (NumberFormatException invalidAddress) {
            return error(sequence, language, source,
                    "goTo() expects an integer address such as 0x00401120.");
        }
    }

    private static Entry unsupported(long sequence, Language language, String source) {
        return error(sequence, language, source,
                "No Python or Java VM is attached. This prototype executes only bridge commands; try help().");
    }

    private static Entry output(
            long sequence,
            Language language,
            String command,
            String output,
            OptionalLong navigationAddress
    ) {
        return new Entry(sequence, language, command, output, ResultKind.OUTPUT, navigationAddress);
    }

    private static Entry error(long sequence, Language language, String command, String output) {
        return new Entry(sequence, language, command, output, ResultKind.ERROR, OptionalLong.empty());
    }

    private static String stripTrailingSemicolon(String source) {
        return source.endsWith(";") ? source.substring(0, source.length() - 1).strip() : source;
    }
}
