package dev.ghidraex.workbench.advanced;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Toolkit-neutral state for the difficult workbench surfaces that do not belong in the listing
 * engine: backend discovery, debugger coordinates, script capabilities, and extensions.
 *
 * <p>The model is deliberately honest about integration depth. A captured trace and emulator are
 * usable in the prototype, while live target control and Ghidra API evaluation remain gated on a
 * connected sidecar and explicit capabilities. Detecting a distribution on disk is a separate
 * state and grants no runtime authority.</p>
 */
public final class AdvancedWorkbenchModel {
    public enum Capability {
        PROGRAM_READ("program.read"),
        PROGRAM_WRITE("program.write"),
        DEBUG_TRACE_READ("debug.trace.read"),
        DEBUG_TARGET_CONTROL("debug.target.control"),
        FILESYSTEM_READ("filesystem.read"),
        FILESYSTEM_WRITE("filesystem.write"),
        NETWORK("network"),
        PROCESS_EXEC("process.exec");

        private final String id;

        Capability(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public enum BackendHealth {
        NOT_CONFIGURED,
        INVALID_HOME,
        DISTRIBUTION_DETECTED,
        CONNECTED
    }

    public record BackendState(
            BackendHealth health,
            String label,
            String version,
            String launcher,
            Set<Capability> capabilities) {
        public BackendState {
            Objects.requireNonNull(health, "health");
            label = Objects.requireNonNullElse(label, "Ghidra sidecar");
            version = Objects.requireNonNullElse(version, "unknown");
            launcher = Objects.requireNonNullElse(launcher, "");
            capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities);
        }

        public boolean distributionDetected() {
            return health == BackendHealth.DISTRIBUTION_DETECTED || health == BackendHealth.CONNECTED;
        }

        public boolean connected() {
            return health == BackendHealth.CONNECTED;
        }

        public static BackendState unconfigured() {
            return new BackendState(
                    BackendHealth.NOT_CONFIGURED,
                    "Synthetic fixture engine",
                    "deterministic",
                    "Set GHIDRA_HOME to enable the real sidecar",
                    Set.of(Capability.PROGRAM_READ, Capability.DEBUG_TRACE_READ));
        }

        public static BackendState detected(String version, String launcher) {
            return new BackendState(
                    BackendHealth.DISTRIBUTION_DETECTED,
                    "Ghidra distribution detected",
                    version,
                    launcher,
                    Set.of());
        }
    }

    public enum DebugMode {
        CAPTURED_TRACE,
        EMULATOR,
        LIVE_TARGET
    }

    public enum DebugStatus {
        DISCONNECTED,
        RUNNING,
        PAUSED,
        PAST_SNAPSHOT
    }

    public record DebuggerState(
            DebugMode mode,
            DebugStatus status,
            String trace,
            String thread,
            int frame,
            long snapshot,
            long latestSnapshot,
            boolean staleMemory,
            boolean writesEnabled,
            Map<String, String> registers,
            List<String> stack,
            List<String> watches) {
        public DebuggerState {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(status, "status");
            trace = Objects.requireNonNullElse(trace, "No trace");
            thread = Objects.requireNonNullElse(thread, "No thread");
            registers = Map.copyOf(registers == null ? Map.of() : registers);
            stack = List.copyOf(stack == null ? List.of() : stack);
            watches = List.copyOf(watches == null ? List.of() : watches);
        }
    }

    public enum ScriptStatus {
        SUCCESS,
        BACKEND_UNAVAILABLE,
        APPROVAL_REQUIRED,
        BLOCKED
    }

    public record ScriptResult(
            ScriptStatus status,
            String output,
            Set<Capability> requiredCapabilities) {
        public ScriptResult {
            Objects.requireNonNull(status, "status");
            output = Objects.requireNonNullElse(output, "");
            requiredCapabilities = Set.copyOf(
                    requiredCapabilities == null ? Set.of() : requiredCapabilities);
        }
    }

    public record ExtensionState(
            String id,
            String name,
            String provider,
            boolean enabled,
            boolean requiresBackend,
            Set<Capability> requiredCapabilities,
            String status) {
        public ExtensionState {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            provider = Objects.requireNonNullElse(provider, "GhidraEx");
            requiredCapabilities = Set.copyOf(
                    requiredCapabilities == null ? Set.of() : requiredCapabilities);
            status = Objects.requireNonNullElse(status, enabled ? "Active" : "Disabled");
        }

        ExtensionState withState(boolean nextEnabled, String nextStatus) {
            return new ExtensionState(id, name, provider, nextEnabled, requiresBackend,
                    requiredCapabilities, nextStatus);
        }
    }

    private final BackendState backend;
    private final Map<String, ExtensionState> extensions = new LinkedHashMap<>();
    private DebuggerState debugger;

    public AdvancedWorkbenchModel(BackendState backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
        debugger = capturedTrace(142, 142, DebugStatus.PAUSED, false);
        registerExtensions();
    }

    public static AdvancedWorkbenchModel createDefault() {
        return new AdvancedWorkbenchModel(discoverBackend(System.getenv()));
    }

    public static BackendState discoverBackend(Map<String, String> environment) {
        String configuredHome = environment.getOrDefault("GHIDRA_HOME", "").strip();
        if (configuredHome.isEmpty()) {
            return BackendState.unconfigured();
        }
        Path home;
        try {
            home = Path.of(configuredHome).toAbsolutePath().normalize();
        } catch (RuntimeException invalidPath) {
            return invalidBackend(configuredHome);
        }
        Path launcher = home.resolve("support").resolve("analyzeHeadless");
        if (!Files.isRegularFile(launcher) || !Files.isExecutable(launcher)) {
            return invalidBackend(launcher.toString());
        }
        return BackendState.detected(readVersion(home), launcher.toString());
    }

    public BackendState backend() {
        return backend;
    }

    public synchronized DebuggerState debugger() {
        return debugger;
    }

    public synchronized DebuggerState selectSnapshot(long snapshot) {
        if (snapshot < 0 || snapshot > debugger.latestSnapshot()) {
            throw new IllegalArgumentException("Snapshot is outside the captured trace");
        }
        boolean historical = snapshot < debugger.latestSnapshot();
        DebugStatus status = historical ? DebugStatus.PAST_SNAPSHOT : DebugStatus.PAUSED;
        debugger = capturedTrace(snapshot, debugger.latestSnapshot(), status, false);
        return debugger;
    }

    public synchronized DebuggerState forkEmulator() {
        debugger = new DebuggerState(
                DebugMode.EMULATOR,
                DebugStatus.PAUSED,
                debugger.trace() + " / emulator-fork",
                debugger.thread(),
                debugger.frame(),
                debugger.snapshot(),
                debugger.latestSnapshot(),
                false,
                true,
                debugger.registers(),
                debugger.stack(),
                appendWatch(debugger.watches(), "branch_taken = false  [emulated]"));
        return debugger;
    }

    public synchronized DebuggerState stepEmulator(int delta) {
        if (debugger.mode() != DebugMode.EMULATOR) {
            throw new IllegalStateException("Backward/forward stepping requires an emulator fork");
        }
        long next = Math.max(0, debugger.snapshot() + delta);
        long latest = Math.max(debugger.latestSnapshot(), next);
        debugger = new DebuggerState(
                DebugMode.EMULATOR,
                DebugStatus.PAUSED,
                debugger.trace(),
                debugger.thread(),
                debugger.frame(),
                next,
                latest,
                false,
                true,
                shiftedRegisters(debugger.registers(), delta),
                debugger.stack(),
                debugger.watches());
        return debugger;
    }

    public synchronized ScriptResult evaluate(String source) {
        String expression = Objects.requireNonNullElse(source, "").strip();
        if (expression.isEmpty()) {
            return new ScriptResult(ScriptStatus.BLOCKED, "Enter a script or REPL expression.", Set.of());
        }
        String lower = expression.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "processbuilder", "runtime.getruntime", "system.exit")) {
            return new ScriptResult(
                    ScriptStatus.BLOCKED,
                    "Process execution is blocked in the workbench preview. Run reviewed code in the isolated script worker.",
                    Set.of(Capability.PROCESS_EXEC));
        }
        if (containsAny(lower, "socket(", "http://", "https://", "urlconnection")) {
            return new ScriptResult(
                    ScriptStatus.BLOCKED,
                    "Network access is not granted to this script.",
                    Set.of(Capability.NETWORK));
        }
        if (containsAny(lower, "setname(", "rename ", "setcomment(", "createbookmark(", "writememory(")) {
            return new ScriptResult(
                    ScriptStatus.APPROVAL_REQUIRED,
                    "Dry-run only: the expression would mutate the program. Review the exact change plan before granting program.write.",
                    Set.of(Capability.PROGRAM_WRITE));
        }
        if (":backend".equals(lower)) {
            return new ScriptResult(
                    ScriptStatus.SUCCESS,
                    backend.label() + " · " + backend.version() + " · " + backend.health(),
                    Set.of());
        }
        if (":where".equals(lower) || "currentaddress".equals(lower)) {
            return new ScriptResult(
                    ScriptStatus.SUCCESS,
                    "telemetryd :: parse_packet @ 0x00000001004011d0\ntrace="
                            + debugger.trace() + " snap=" + debugger.snapshot(),
                    Set.of(Capability.PROGRAM_READ));
        }
        if (lower.startsWith("functions(")) {
            return new ScriptResult(
                    ScriptStatus.SUCCESS,
                    "parse_packet          0x00000001004011d0\n"
                            + "dispatch_payload      0x0000000100401670\n"
                            + "decrypt_payload       0x0000000100401b20",
                    Set.of(Capability.PROGRAM_READ));
        }
        if (lower.startsWith("bytes(")) {
            return new ScriptResult(
                    ScriptStatus.SUCCESS,
                    "48 89 e5 48 83 ec 30 48 8b 47 18 85 c0 74 16 90",
                    Set.of(Capability.PROGRAM_READ));
        }
        if (!backend.connected()) {
            String detail = backend.distributionDetected()
                    ? "A Ghidra distribution is detected, but the interactive script host is not connected; no code was executed."
                    : "This Ghidra API expression needs the JDK 21 sidecar. Set GHIDRA_HOME and start a real session; no code was executed.";
            return new ScriptResult(
                    ScriptStatus.BACKEND_UNAVAILABLE,
                    detail,
                    Set.of(Capability.PROGRAM_READ));
        }
        return new ScriptResult(
                ScriptStatus.SUCCESS,
                "Accepted by the read-only sidecar preview:\n" + expression,
                Set.of(Capability.PROGRAM_READ));
    }

    public synchronized List<ExtensionState> extensions() {
        return List.copyOf(extensions.values());
    }

    public synchronized ExtensionState setExtensionEnabled(
            String id,
            boolean enabled,
            Set<Capability> approvedCapabilities) {
        ExtensionState current = extensions.get(id);
        if (current == null) {
            throw new IllegalArgumentException("Unknown extension: " + id);
        }
        if (!enabled) {
            ExtensionState disabled = current.withState(false, "Disabled");
            extensions.put(id, disabled);
            return disabled;
        }
        if (current.requiresBackend() && !backend.connected()) {
            String detail = backend.distributionDetected()
                    ? "Distribution detected · interactive adapter not connected"
                    : "Requires a connected Ghidra sidecar";
            ExtensionState unavailable = current.withState(false, detail);
            extensions.put(id, unavailable);
            return unavailable;
        }
        Set<Capability> approved = approvedCapabilities == null ? Set.of() : approvedCapabilities;
        if (!approved.containsAll(current.requiredCapabilities())) {
            ExtensionState awaiting = current.withState(false,
                    "Awaiting capability approval: " + capabilitySummary(current.requiredCapabilities()));
            extensions.put(id, awaiting);
            return awaiting;
        }
        ExtensionState active = current.withState(true, "Active");
        extensions.put(id, active);
        return active;
    }

    private void registerExtensions() {
        extensions.put("ghidra-headless", new ExtensionState(
                "ghidra-headless",
                "Ghidra Headless Adapter",
                "GhidraEx",
                false,
                true,
                Set.of(Capability.PROGRAM_READ, Capability.FILESYSTEM_READ),
                backend.distributionDetected()
                        ? "Distribution detected · batch bridge available outside this UI"
                        : "GHIDRA_HOME not configured"));
        extensions.put("trace-ui", new ExtensionState(
                "trace-ui",
                "Debugger Trace & Emulator UI",
                "GhidraEx built-in",
                true,
                false,
                Set.of(Capability.DEBUG_TRACE_READ),
                "Active · captured trace fixture"));
        extensions.put("script-console", new ExtensionState(
                "script-console",
                "Capability-gated Script Console",
                "GhidraEx built-in",
                true,
                false,
                Set.of(Capability.PROGRAM_READ),
                "Active · read-only preview"));
        extensions.put("bsim-client", new ExtensionState(
                "bsim-client",
                "BSim Similarity Client",
                "Optional Ghidra capability",
                false,
                true,
                Set.of(Capability.PROGRAM_READ, Capability.NETWORK),
                "Disabled · network permission required"));
    }

    private static BackendState invalidBackend(String launcher) {
        return new BackendState(
                BackendHealth.INVALID_HOME,
                "Invalid Ghidra home",
                "unknown",
                launcher,
                Set.of(Capability.PROGRAM_READ, Capability.DEBUG_TRACE_READ));
    }

    private static String readVersion(Path home) {
        Path properties = home.resolve("Ghidra").resolve("application.properties");
        try {
            for (String line : Files.readAllLines(properties)) {
                if (line.startsWith("application.version=")) {
                    return line.substring("application.version=".length()).strip();
                }
            }
        } catch (IOException ignored) {
            // Discovery remains useful even when a customized distribution omits the property.
        }
        return home.getFileName() == null ? "unknown" : home.getFileName().toString();
    }

    private static DebuggerState capturedTrace(
            long snapshot,
            long latestSnapshot,
            DebugStatus status,
            boolean writesEnabled) {
        boolean stale = snapshot < latestSnapshot;
        Map<String, String> registers = new LinkedHashMap<>();
        registers.put("RIP", "0x00000001004011d0");
        registers.put("RSP", "0x000000016fdff2a0");
        registers.put("RBP", "0x000000016fdff310");
        registers.put("RAX", stale ? "0x000000000000002a  stale" : "0x0000000000000030");
        registers.put("EFLAGS", "0x00000246  ZF PF");
        return new DebuggerState(
                DebugMode.CAPTURED_TRACE,
                status,
                "telemetryd-crash-2026-07-12",
                "Thread 4 · worker/io",
                0,
                snapshot,
                latestSnapshot,
                stale,
                writesEnabled,
                registers,
                List.of(
                        "#0  parse_packet + 0x40",
                        "#1  dispatch_payload + 0xb8",
                        "#2  worker_loop + 0x114",
                        "#3  pthread_start + 0x88"),
                List.of(
                        "packet->payload_size = 0x2a",
                        "session->state = ACTIVE",
                        "message_type = MSG_DATA"));
    }

    private static List<String> appendWatch(List<String> watches, String item) {
        List<String> next = new ArrayList<>(watches);
        next.add(item);
        return List.copyOf(next);
    }

    private static Map<String, String> shiftedRegisters(Map<String, String> registers, int delta) {
        Map<String, String> shifted = new LinkedHashMap<>(registers);
        long rip = 0x0000_0001_0040_11d0L + delta * 4L;
        shifted.put("RIP", "0x%016x".formatted(Math.max(0, rip)));
        return Map.copyOf(shifted);
    }

    private static boolean containsAny(String source, String... needles) {
        for (String needle : needles) {
            if (source.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String capabilitySummary(Set<Capability> capabilities) {
        return capabilities.stream().map(Capability::id).sorted().reduce((left, right) -> left + ", " + right).orElse("none");
    }
}
