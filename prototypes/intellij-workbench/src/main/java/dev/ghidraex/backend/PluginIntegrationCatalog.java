package dev.ghidraex.backend;

import java.util.List;
import java.util.Objects;

/** Plugin/extension boundaries shown by the native runtime inspector. */
public final class PluginIntegrationCatalog {
    public enum State {
        LOADED_NATIVE,
        PROTOTYPE_ONLY,
        BACKEND_REQUIRED
    }

    public record Integration(String host, String extensionPoint, State state, String lifecycle) {
        public Integration {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(extensionPoint, "extensionPoint");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(lifecycle, "lifecycle");
        }
    }

    private PluginIntegrationCatalog() {
    }

    public static List<Integration> forStatus(BackendProbe.Status backend) {
        String backendLifecycle = backend.realBackendAvailable()
                ? "Distribution detected; isolated host/adapter handshake is still required"
                : "Configure and connect a Ghidra distribution first";
        return List.of(
                integration("IntelliJ", "projectService / WorkbenchSession", State.LOADED_NATIVE,
                        "Project-scoped; disposed with project"),
                integration("IntelliJ", "toolWindow / actions / keymap", State.LOADED_NATIVE,
                        "Declarative plugin.xml registration"),
                integration("IntelliJ", "fileEditorProvider", State.LOADED_NATIVE,
                        "Virtual listing routed to a paged native editor"),
                integration("GhidraEx", "AnalysisEngine adapter", State.PROTOTYPE_ONLY,
                        "Synthetic provider active; replace explicitly, never by implicit discovery"),
                integration("Ghidra", "ProgramPlugin / DomainObject transactions", State.BACKEND_REQUIRED,
                        backendLifecycle),
                integration("Ghidra", "GhidraScript / PyGhidra", State.BACKEND_REQUIRED,
                        backendLifecycle),
                integration("Ghidra", "Debugger Trace/RMI services", State.BACKEND_REQUIRED,
                        backendLifecycle),
                integration("Ghidra", "Third-party extension discovery", State.BACKEND_REQUIRED,
                        "Keep plugin loading inside an isolated Ghidra host and report compatibility")
        );
    }

    private static Integration integration(
            String host,
            String extensionPoint,
            State state,
            String lifecycle
    ) {
        return new Integration(host, extensionPoint, state, lifecycle);
    }
}
