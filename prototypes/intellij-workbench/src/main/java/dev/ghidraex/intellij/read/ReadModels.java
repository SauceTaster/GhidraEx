package dev.ghidraex.intellij.read;

import dev.ghidraex.backend.BackendProbe;
import dev.ghidraex.backend.PluginIntegrationCatalog;
import dev.ghidraex.backend.RuntimeCapabilityCatalog;
import dev.ghidraex.engine.ProgramDescriptor;
import dev.ghidraex.engine.SymbolDescriptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.ArrayList;

/** Immutable, typed, explicitly bounded payloads used by native IntelliJ read projections. */
public final class ReadModels {
    public static final int MAX_PROGRAMS = 64;
    public static final int MAX_SYMBOLS = 512;
    public static final int MAX_QUERY_CHARS = 256;
    public static final int MAX_DECOMPILER_CHARS = 262_144;
    public static final int MAX_REFERENCES = 256;
    public static final int MAX_DESCRIPTOR_FIELD_CHARS = 4_096;
    public static final int MAX_RUNTIME_FIELD_CHARS = 16_384;
    public static final int MAX_CATALOG_TEXT_CHARS = 1_048_576;

    private ReadModels() {
    }

    public record ProgramQuery(int limit) {
        public ProgramQuery {
            requireLimit(limit, MAX_PROGRAMS, "program limit");
        }
    }

    public record ProgramCatalog(List<ProgramDescriptor> programs) {
        public ProgramCatalog {
            List<ProgramDescriptor> source = boundedCopy(programs, MAX_PROGRAMS, "program catalog");
            var safe = new ArrayList<ProgramDescriptor>(source.size());
            int textBudget = 0;
            for (ProgramDescriptor program : source) {
                ProgramDescriptor copy = copyProgram(program);
                textBudget = addText(textBudget, copy.id(), copy.name(), copy.architecture());
                safe.add(copy);
            }
            programs = List.copyOf(safe);
        }
    }

    public record SymbolQuery(String text, int limit) {
        public SymbolQuery {
            text = Objects.requireNonNullElse(text, "").strip();
            if (text.length() > MAX_QUERY_CHARS) {
                throw new IllegalArgumentException("symbol query exceeds " + MAX_QUERY_CHARS + " characters");
            }
            requireLimit(limit, MAX_SYMBOLS, "symbol limit");
        }
    }

    public record SymbolCatalog(String query, List<SymbolDescriptor> symbols) {
        public SymbolCatalog {
            query = Objects.requireNonNullElse(query, "");
            if (query.length() > MAX_QUERY_CHARS) {
                throw new IllegalArgumentException("symbol result query is unbounded");
            }
            List<SymbolDescriptor> source = boundedCopy(symbols, MAX_SYMBOLS, "symbol catalog");
            var safe = new ArrayList<SymbolDescriptor>(source.size());
            int textBudget = query.length();
            for (SymbolDescriptor symbol : source) {
                SymbolDescriptor copy = copySymbol(symbol);
                textBudget = addText(textBudget, copy.name(), copy.signature());
                safe.add(copy);
            }
            symbols = List.copyOf(safe);
        }
    }

    public record DecompilerQuery(long functionAddress, int maxCharacters) {
        public DecompilerQuery {
            requireLimit(maxCharacters, MAX_DECOMPILER_CHARS, "decompiler character limit");
        }
    }

    public record DecompilerDocument(long functionAddress, String text) {
        public DecompilerDocument {
            text = Objects.requireNonNull(text, "text");
            if (text.length() > MAX_DECOMPILER_CHARS) {
                throw new IllegalArgumentException("decompiler document exceeds the character budget");
            }
        }
    }

    public record InspectorQuery(
            SymbolDescriptor symbol,
            ProgramDescriptor program,
            String analysisSummary) {
        public InspectorQuery {
            symbol = copySymbol(symbol);
            program = copyProgram(program);
            analysisSummary = boundedText(analysisSummary, 4_096, "analysis summary");
        }
    }

    public record InspectorDetails(
            SymbolDescriptor symbol,
            String programName,
            String analysisSummary) {
        public InspectorDetails {
            symbol = copySymbol(symbol);
            programName = boundedText(programName, 1_024, "program name");
            analysisSummary = boundedText(analysisSummary, 4_096, "analysis summary");
        }
    }

    public record ReferenceQuery(SymbolDescriptor symbol, int limit) {
        public ReferenceQuery {
            symbol = copySymbol(symbol);
            requireLimit(limit, MAX_REFERENCES, "reference limit");
        }
    }

    public record ReferenceRow(
            String referenceId,
            long fromAddress,
            long toAddress,
            String kind) {
        public ReferenceRow {
            referenceId = boundedText(referenceId, 512, "reference ID");
            kind = boundedText(kind, 128, "reference kind");
        }
    }

    public record ReferenceSet(
            SymbolDescriptor symbol,
            List<ReferenceRow> references,
            int totalAvailable) {
        public ReferenceSet {
            symbol = copySymbol(symbol);
            references = boundedCopy(references, MAX_REFERENCES, "reference result");
            if (totalAvailable < references.size()) {
                throw new IllegalArgumentException("total references cannot be smaller than returned references");
            }
        }
    }

    public record RuntimeQuery(Path searchFrom) {
        public RuntimeQuery {
            searchFrom = Objects.requireNonNull(searchFrom, "searchFrom").toAbsolutePath().normalize();
        }
    }

    public record RuntimeDetails(
            BackendProbe.Status backend,
            List<RuntimeCapabilityCatalog.Capability> capabilities,
            List<PluginIntegrationCatalog.Integration> integrations) {
        public RuntimeDetails {
            backend = copyBackend(backend);
            List<RuntimeCapabilityCatalog.Capability> capabilitySource =
                    boundedCopy(capabilities, 128, "runtime capabilities");
            var safeCapabilities = new ArrayList<RuntimeCapabilityCatalog.Capability>(
                    capabilitySource.size());
            int textBudget = addText(0,
                    backend.activeBackend(), backend.configuredBy(), backend.ghidraVersion(), backend.detail(),
                    backend.ghidraHome() == null ? "" : backend.ghidraHome().toString(),
                    backend.headlessLauncher() == null ? "" : backend.headlessLauncher().toString());
            for (RuntimeCapabilityCatalog.Capability capability : capabilitySource) {
                RuntimeCapabilityCatalog.Capability copy = copyCapability(capability);
                textBudget = addText(textBudget,
                        copy.surface(), copy.provider(), copy.detail());
                safeCapabilities.add(copy);
            }
            capabilities = List.copyOf(safeCapabilities);

            List<PluginIntegrationCatalog.Integration> integrationSource =
                    boundedCopy(integrations, 128, "plugin integrations");
            var safeIntegrations = new ArrayList<PluginIntegrationCatalog.Integration>(
                    integrationSource.size());
            for (PluginIntegrationCatalog.Integration integration : integrationSource) {
                PluginIntegrationCatalog.Integration copy = copyIntegration(integration);
                textBudget = addText(textBudget,
                        copy.host(), copy.extensionPoint(), copy.lifecycle());
                safeIntegrations.add(copy);
            }
            integrations = List.copyOf(safeIntegrations);
        }
    }

    private static void requireLimit(int value, int maximum, String name) {
        if (value <= 0 || value > maximum) {
            throw new IllegalArgumentException(name + " must be between 1 and " + maximum);
        }
    }

    private static String boundedText(String value, int maximum, String name) {
        String safe = Objects.requireNonNullElse(value, "");
        if (safe.length() > maximum) {
            throw new IllegalArgumentException(name + " exceeds " + maximum + " characters");
        }
        return safe;
    }

    private static ProgramDescriptor copyProgram(ProgramDescriptor value) {
        ProgramDescriptor program = Objects.requireNonNull(value, "program descriptor");
        String id = requiredDescriptorText(program.id(), "program ID");
        String name = requiredDescriptorText(program.name(), "program name");
        String architecture = requiredDescriptorText(program.architecture(), "program architecture");
        if (program.byteSize() < 0) {
            throw new IllegalArgumentException("program byte size must be non-negative");
        }
        return new ProgramDescriptor(id, name, architecture, program.imageBase(), program.byteSize());
    }

    private static SymbolDescriptor copySymbol(SymbolDescriptor value) {
        SymbolDescriptor symbol = Objects.requireNonNull(value, "symbol descriptor");
        String name = requiredDescriptorText(symbol.name(), "symbol name");
        String signature = requiredDescriptorText(symbol.signature(), "symbol signature");
        Objects.requireNonNull(symbol.kind(), "symbol kind");
        if (symbol.referenceCount() < 0) {
            throw new IllegalArgumentException("symbol reference count must be non-negative");
        }
        return new SymbolDescriptor(
                name, symbol.address(), symbol.kind(), symbol.referenceCount(), signature);
    }

    private static BackendProbe.Status copyBackend(BackendProbe.Status value) {
        BackendProbe.Status backend = Objects.requireNonNull(value, "backend status");
        Objects.requireNonNull(backend.discoveryState(), "backend discovery state");
        return new BackendProbe.Status(
                backend.discoveryState(),
                requiredRuntimeText(backend.activeBackend(), "active backend"),
                requiredRuntimeText(backend.configuredBy(), "backend configuration source"),
                copyPath(backend.ghidraHome(), "Ghidra home"),
                copyPath(backend.headlessLauncher(), "headless launcher"),
                requiredRuntimeText(backend.ghidraVersion(), "Ghidra version"),
                requiredRuntimeText(backend.detail(), "backend detail"));
    }

    private static RuntimeCapabilityCatalog.Capability copyCapability(
            RuntimeCapabilityCatalog.Capability value) {
        RuntimeCapabilityCatalog.Capability capability =
                Objects.requireNonNull(value, "runtime capability");
        return new RuntimeCapabilityCatalog.Capability(
                requiredRuntimeText(capability.surface(), "capability surface"),
                requiredRuntimeText(capability.provider(), "capability provider"),
                Objects.requireNonNull(capability.availability(), "capability availability"),
                requiredRuntimeText(capability.detail(), "capability detail"));
    }

    private static PluginIntegrationCatalog.Integration copyIntegration(
            PluginIntegrationCatalog.Integration value) {
        PluginIntegrationCatalog.Integration integration =
                Objects.requireNonNull(value, "plugin integration");
        return new PluginIntegrationCatalog.Integration(
                requiredRuntimeText(integration.host(), "integration host"),
                requiredRuntimeText(integration.extensionPoint(), "integration extension point"),
                Objects.requireNonNull(integration.state(), "integration state"),
                requiredRuntimeText(integration.lifecycle(), "integration lifecycle"));
    }

    private static String requiredDescriptorText(String value, String name) {
        String safe = Objects.requireNonNull(value, name);
        if (safe.isBlank() || safe.length() > MAX_DESCRIPTOR_FIELD_CHARS) {
            throw new IllegalArgumentException(
                    name + " must be nonblank and at most " + MAX_DESCRIPTOR_FIELD_CHARS + " characters");
        }
        return safe;
    }

    private static String requiredRuntimeText(String value, String name) {
        String safe = Objects.requireNonNull(value, name);
        if (safe.isBlank() || safe.length() > MAX_RUNTIME_FIELD_CHARS) {
            throw new IllegalArgumentException(
                    name + " must be nonblank and at most " + MAX_RUNTIME_FIELD_CHARS + " characters");
        }
        return safe;
    }

    private static Path copyPath(Path value, String name) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        if (text.isBlank() || text.length() > MAX_DESCRIPTOR_FIELD_CHARS) {
            throw new IllegalArgumentException(
                    name + " must be at most " + MAX_DESCRIPTOR_FIELD_CHARS + " characters");
        }
        return Path.of(text);
    }

    private static int addText(int current, String... values) {
        long total = current;
        for (String value : values) {
            total += value.length();
        }
        if (total > MAX_CATALOG_TEXT_CHARS) {
            throw new IllegalArgumentException(
                    "read payload exceeds the aggregate " + MAX_CATALOG_TEXT_CHARS + " character budget");
        }
        return (int) total;
    }

    private static <T> List<T> boundedCopy(List<T> values, int maximum, String name) {
        List<T> safe = List.copyOf(values == null ? List.of() : values);
        if (safe.size() > maximum) {
            throw new IllegalArgumentException(name + " exceeds " + maximum + " items");
        }
        return safe;
    }
}
