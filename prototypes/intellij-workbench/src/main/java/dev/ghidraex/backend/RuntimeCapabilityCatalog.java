package dev.ghidraex.backend;

import java.util.List;
import java.util.Objects;

/** Truthful capability rows shared by the runtime UI and tests. */
public final class RuntimeCapabilityCatalog {
    public enum Availability {
        ACTIVE_NATIVE,
        ACTIVE_SYNTHETIC,
        AVAILABLE_NOT_CONNECTED,
        ADAPTER_REQUIRED,
        NOT_CONFIGURED
    }

    public record Capability(
            String surface,
            String provider,
            Availability availability,
            String detail
    ) {
        public Capability {
            Objects.requireNonNull(surface, "surface");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(availability, "availability");
            Objects.requireNonNull(detail, "detail");
        }
    }

    private RuntimeCapabilityCatalog() {
    }

    public static List<Capability> forStatus(BackendProbe.Status status) {
        Objects.requireNonNull(status, "status");
        Availability ghidraAvailability = status.realBackendAvailable()
                ? Availability.AVAILABLE_NOT_CONNECTED
                : Availability.NOT_CONFIGURED;
        Availability adapterAvailability = status.realBackendAvailable()
                ? Availability.ADAPTER_REQUIRED
                : Availability.NOT_CONFIGURED;
        String ghidraProvider = status.realBackendAvailable()
                ? "Ghidra " + status.ghidraVersion()
                : "No distribution";

        return List.of(
                capability("Actions, menus, editor, tool windows", "IntelliJ Platform",
                        Availability.ACTIVE_NATIVE, "Registered native extension points"),
                capability("Program, symbols, listing, decompiler", "SyntheticAnalysisEngine",
                        Availability.ACTIVE_SYNTHETIC, "Deterministic coarse-grained engine contract"),
                capability("Debugger control and trace state", "DebuggerModel",
                        Availability.ACTIVE_SYNTHETIC, "Workflow simulation; no target process attached"),
                capability("Script command bridge", "IntelliJ ConsoleView",
                        Availability.ACTIVE_SYNTHETIC, "Safe command contract; no Python or Java VM attached"),
                capability("Headless import and analysis", ghidraProvider,
                        ghidraAvailability, status.realBackendAvailable()
                                ? status.headlessLauncher().toString()
                                : "Configure a Ghidra home to enable integration tests"),
                capability("Ghidra program transactions", ghidraProvider,
                        adapterAvailability, "Adapter implementation and lifecycle still required"),
                capability("PyGhidra script execution", ghidraProvider,
                        adapterAvailability, "Interpreter bridge is not connected"),
                capability("Debugger Trace/RMI", ghidraProvider,
                        adapterAvailability, "Trace synchronization adapter is not connected"),
                capability("Third-party Ghidra extensions", ghidraProvider,
                        adapterAvailability, "Extension discovery/loading remains in the Ghidra process")
        );
    }

    private static Capability capability(
            String surface,
            String provider,
            Availability availability,
            String detail
    ) {
        return new Capability(surface, provider, availability, detail);
    }
}
