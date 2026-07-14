// Export a stable, bounded JSON projection of the current analyzed program.
// @category GhidraEx
// @menupath
// @toolbar

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import ghidra.app.util.headless.HeadlessScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressIterator;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.DataIterator;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolIterator;

public class ExportProgramFacts extends HeadlessScript {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_LIMIT = 100_000;

    @Override
    protected void run() throws Exception {
        if (currentProgram == null) {
            throw new IllegalStateException("ExportProgramFacts requires a current program");
        }

        String[] arguments = getScriptArgs();
        if (arguments.length != 4) {
            throw new IllegalArgumentException(
                "usage: ExportProgramFacts.java <output> <max-functions> <max-symbols> <max-instructions>"
            );
        }

        Path output = Path.of(arguments[0]).toAbsolutePath();
        int maxFunctions = parseLimit("max-functions", arguments[1]);
        int maxSymbols = parseLimit("max-symbols", arguments[2]);
        int maxInstructions = parseLimit("max-instructions", arguments[3]);

        Listing listing = currentProgram.getListing();
        Memory memory = currentProgram.getMemory();

        List<Map<String, Object>> memoryBlocks = memoryBlocks(memory);
        List<String> entryPoints = entryPoints();
        List<Map<String, Object>> functions = functions(maxFunctions);
        SymbolResult symbolResult = symbols(maxSymbols);
        InstructionResult instructionResult = instructions(listing, maxInstructions);
        long definedDataCount = countDefinedData(listing);

        Map<String, Object> counts = object();
        counts.put("definedData", definedDataCount);
        counts.put("functions", currentProgram.getFunctionManager().getFunctionCount());
        counts.put("instructions", instructionResult.total);
        counts.put("memoryBlocks", memoryBlocks.size());
        counts.put("symbols", symbolResult.total);

        Map<String, Object> limits = object();
        limits.put("functions", maxFunctions);
        limits.put("symbols", maxSymbols);
        limits.put("instructions", maxInstructions);

        Map<String, Object> truncated = object();
        truncated.put("functions", countsLong(counts, "functions") > functions.size());
        truncated.put("symbols", symbolResult.total > symbolResult.values.size());
        truncated.put("instructions", instructionResult.total > instructionResult.values.size());

        Map<String, Object> root = object();
        root.put("schemaVersion", SCHEMA_VERSION);
        Map<String, Object> analysis = object();
        analysis.put("timeoutOccurred", analysisTimeoutOccurred());
        root.put("analysis", analysis);
        root.put("program", program(memory));
        root.put("counts", counts);
        root.put("limits", limits);
        root.put("truncated", truncated);
        root.put("memoryBlocks", memoryBlocks);
        root.put("entryPoints", entryPoints);
        root.put("functions", functions);
        root.put("symbols", symbolResult.values);
        root.put("instructions", instructionResult.values);

        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, Json.write(root) + "\n", StandardCharsets.UTF_8);
        println("GHIDRAEX_FACTS_WRITTEN " + output);
    }

    private Map<String, Object> program(Memory memory) {
        Map<String, Object> value = object();
        value.put("name", currentProgram.getName());
        value.put("sha256", currentProgram.getExecutableSHA256());
        value.put("executableFormat", currentProgram.getExecutableFormat());
        value.put("languageId", currentProgram.getLanguageID().toString());
        value.put("compilerSpecId", currentProgram.getCompilerSpec().getCompilerSpecID().toString());
        value.put("processor", currentProgram.getLanguage().getProcessor().toString());
        value.put("bigEndian", currentProgram.getLanguage().isBigEndian());
        value.put("pointerSize", currentProgram.getDefaultPointerSize());
        value.put("imageBase", address(currentProgram.getImageBase()));
        value.put("minAddress", address(memory.getMinAddress()));
        value.put("maxAddress", address(memory.getMaxAddress()));
        value.put("memorySize", memory.getSize());
        return value;
    }

    private List<Map<String, Object>> memoryBlocks(Memory memory) {
        List<MemoryBlock> blocks = new ArrayList<>(List.of(memory.getBlocks()));
        blocks.sort(Comparator.comparing(MemoryBlock::getStart).thenComparing(MemoryBlock::getName));
        List<Map<String, Object>> values = new ArrayList<>();
        for (MemoryBlock block : blocks) {
            Map<String, Object> value = object();
            value.put("name", block.getName());
            value.put("start", address(block.getStart()));
            value.put("end", address(block.getEnd()));
            value.put("size", block.getSize());
            value.put("read", block.isRead());
            value.put("write", block.isWrite());
            value.put("execute", block.isExecute());
            value.put("initialized", block.isInitialized());
            value.put("loaded", block.isLoaded());
            value.put("overlay", block.isOverlay());
            values.add(value);
        }
        return values;
    }

    private List<String> entryPoints() throws Exception {
        List<String> values = new ArrayList<>();
        AddressIterator iterator = currentProgram.getSymbolTable().getExternalEntryPointIterator();
        while (iterator.hasNext()) {
            monitor.checkCancelled();
            values.add(address(iterator.next()));
        }
        Collections.sort(values);
        return values;
    }

    private List<Map<String, Object>> functions(int limit) throws Exception {
        List<Map<String, Object>> values = new ArrayList<>();
        FunctionIterator iterator = currentProgram.getFunctionManager().getFunctions(true);
        while (iterator.hasNext() && values.size() < limit) {
            monitor.checkCancelled();
            Function function = iterator.next();
            AddressSetView body = function.getBody();
            Map<String, Object> value = object();
            value.put("name", function.getName(true));
            value.put("entryPoint", address(function.getEntryPoint()));
            value.put("bodyMinAddress", address(body.getMinAddress()));
            value.put("bodyMaxAddress", address(body.getMaxAddress()));
            value.put("bodySize", body.getNumAddresses());
            value.put("parameterCount", function.getParameterCount());
            value.put("callingConvention", function.getCallingConventionName());
            value.put("returnType", function.getReturnType().getDisplayName());
            value.put("external", function.isExternal());
            value.put("thunk", function.isThunk());
            value.put("noReturn", function.hasNoReturn());
            values.add(value);
        }
        return values;
    }

    private SymbolResult symbols(int limit) throws Exception {
        Comparator<SymbolFact> order = Comparator
            .comparing((SymbolFact fact) -> fact.address)
            .thenComparing(fact -> fact.name)
            .thenComparing(fact -> fact.type)
            .thenComparing(fact -> fact.namespace);
        PriorityQueue<SymbolFact> selected = new PriorityQueue<>(Math.max(1, limit), order.reversed());
        long total = 0;
        SymbolIterator iterator = currentProgram.getSymbolTable().getAllSymbols(false);
        while (iterator.hasNext()) {
            if ((total & 0x3ff) == 0) {
                monitor.checkCancelled();
            }
            Symbol symbol = iterator.next();
            total++;
            if (limit == 0) {
                continue;
            }
            SymbolFact fact = new SymbolFact(symbol);
            if (selected.size() < limit) {
                selected.add(fact);
            }
            else if (order.compare(fact, selected.peek()) < 0) {
                selected.poll();
                selected.add(fact);
            }
        }
        List<SymbolFact> sorted = new ArrayList<>(selected);
        sorted.sort(order);
        List<Map<String, Object>> values = new ArrayList<>();
        for (SymbolFact fact : sorted) {
            values.add(fact.json);
        }
        return new SymbolResult(total, values);
    }

    private InstructionResult instructions(Listing listing, int limit) throws Exception {
        long total = 0;
        List<Map<String, Object>> values = new ArrayList<>();
        InstructionIterator iterator = listing.getInstructions(true);
        while (iterator.hasNext()) {
            if ((total & 0x3ff) == 0) {
                monitor.checkCancelled();
            }
            Instruction instruction = iterator.next();
            total++;
            if (values.size() >= limit) {
                continue;
            }
            Map<String, Object> value = object();
            value.put("address", address(instruction.getAddress()));
            value.put("length", instruction.getLength());
            value.put("bytes", instructionBytes(instruction));
            value.put("mnemonic", instruction.getMnemonicString());
            List<String> operands = new ArrayList<>();
            for (int index = 0; index < instruction.getNumOperands(); index++) {
                operands.add(instruction.getDefaultOperandRepresentation(index));
            }
            value.put("operands", operands);
            value.put("flowType", instruction.getFlowType().getName());
            values.add(value);
        }
        return new InstructionResult(total, values);
    }

    private long countDefinedData(Listing listing) throws Exception {
        long total = 0;
        DataIterator iterator = listing.getDefinedData(true);
        while (iterator.hasNext()) {
            if ((total & 0x3ff) == 0) {
                monitor.checkCancelled();
            }
            iterator.next();
            total++;
        }
        return total;
    }

    private static String instructionBytes(Instruction instruction) {
        try {
            byte[] bytes = instruction.getBytes();
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        }
        catch (Exception ignored) {
            return null;
        }
    }

    private static String address(Address value) {
        return value == null ? null : value.toString();
    }

    private static int parseLimit(String name, String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        }
        catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " is not an integer: " + value, error);
        }
        if (parsed < 0 || parsed > MAX_LIMIT) {
            throw new IllegalArgumentException(name + " must be between 0 and " + MAX_LIMIT);
        }
        return parsed;
    }

    private static long countsLong(Map<String, Object> counts, String key) {
        return ((Number) counts.get(key)).longValue();
    }

    private static Map<String, Object> object() {
        return new LinkedHashMap<>();
    }

    private static final class SymbolFact {
        private final Address address;
        private final String name;
        private final String type;
        private final String namespace;
        private final Map<String, Object> json;

        private SymbolFact(Symbol symbol) {
            address = symbol.getAddress();
            name = symbol.getName();
            type = symbol.getSymbolType().toString();
            namespace = symbol.getParentNamespace().getName(true);
            json = object();
            json.put("name", name);
            json.put("namespace", namespace);
            json.put("address", ExportProgramFacts.address(address));
            json.put("type", type);
            json.put("source", symbol.getSource().toString());
            json.put("primary", symbol.isPrimary());
            json.put("external", symbol.isExternal());
            json.put("entryPoint", symbol.isExternalEntryPoint());
        }
    }

    private static final class SymbolResult {
        private final long total;
        private final List<Map<String, Object>> values;

        private SymbolResult(long total, List<Map<String, Object>> values) {
            this.total = total;
            this.values = values;
        }
    }

    private static final class InstructionResult {
        private final long total;
        private final List<Map<String, Object>> values;

        private InstructionResult(long total, List<Map<String, Object>> values) {
            this.total = total;
            this.values = values;
        }
    }

    private static final class Json {
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
            else if (value instanceof Number || value instanceof Boolean) {
                output.append(value);
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
                for (Object element : iterable) {
                    if (!first) {
                        output.append(',');
                    }
                    first = false;
                    append(output, element);
                }
                output.append(']');
            }
            else {
                throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
            }
        }

        private static void string(StringBuilder output, String value) {
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
                        if (character < 0x20 || Character.isSurrogate(character)) {
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
    }
}
