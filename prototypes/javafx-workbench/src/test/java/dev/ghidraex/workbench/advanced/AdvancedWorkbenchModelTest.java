package dev.ghidraex.workbench.advanced;

import dev.ghidraex.workbench.advanced.AdvancedWorkbenchModel.BackendHealth;
import dev.ghidraex.workbench.advanced.AdvancedWorkbenchModel.BackendState;
import dev.ghidraex.workbench.advanced.AdvancedWorkbenchModel.Capability;
import dev.ghidraex.workbench.advanced.AdvancedWorkbenchModel.DebugMode;
import dev.ghidraex.workbench.advanced.AdvancedWorkbenchModel.DebugStatus;
import dev.ghidraex.workbench.advanced.AdvancedWorkbenchModel.ScriptStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancedWorkbenchModelTest {
    @Test
    void reportsAnUnconfiguredBackendWithoutPretendingSyntheticDataIsGhidra() {
        BackendState backend = AdvancedWorkbenchModel.discoverBackend(Map.of());

        assertEquals(BackendHealth.NOT_CONFIGURED, backend.health());
        assertFalse(backend.connected());
        assertTrue(backend.launcher().contains("GHIDRA_HOME"));
    }

    @Test
    void debuggerCoordinatesExposeHistoricalStalenessAndEmulatorWriteIsolation() {
        AdvancedWorkbenchModel model = new AdvancedWorkbenchModel(BackendState.unconfigured());

        var historical = model.selectSnapshot(118);
        assertEquals(DebugStatus.PAST_SNAPSHOT, historical.status());
        assertTrue(historical.staleMemory());
        assertFalse(historical.writesEnabled());

        var emulator = model.forkEmulator();
        assertEquals(DebugMode.EMULATOR, emulator.mode());
        assertTrue(emulator.writesEnabled());
        assertFalse(emulator.staleMemory());

        var stepped = model.stepEmulator(1);
        assertEquals(119, stepped.snapshot());
        assertTrue(stepped.registers().get("RIP").endsWith("11d4"));
        assertThrows(IllegalArgumentException.class, () -> model.selectSnapshot(10_000));
    }

    @Test
    void scriptPreviewSeparatesReadsMutationsAndExternalCapabilities() {
        AdvancedWorkbenchModel model = new AdvancedWorkbenchModel(BackendState.unconfigured());

        assertEquals(ScriptStatus.SUCCESS, model.evaluate(":where").status());
        assertEquals(ScriptStatus.SUCCESS, model.evaluate("functions(\"packet\")").status());

        var mutation = model.evaluate("setName(currentFunction, \"parse_frame\")");
        assertEquals(ScriptStatus.APPROVAL_REQUIRED, mutation.status());
        assertEquals(Set.of(Capability.PROGRAM_WRITE), mutation.requiredCapabilities());

        var process = model.evaluate("new ProcessBuilder(\"sh\").start()");
        assertEquals(ScriptStatus.BLOCKED, process.status());
        assertEquals(Set.of(Capability.PROCESS_EXEC), process.requiredCapabilities());

        assertEquals(ScriptStatus.BACKEND_UNAVAILABLE,
                model.evaluate("getFunctionAt(currentAddress)").status());
    }

    @Test
    void extensionEnablementNeedsBothBackendAndExplicitCapabilities() {
        AdvancedWorkbenchModel unavailable = new AdvancedWorkbenchModel(BackendState.unconfigured());
        var noBackend = unavailable.setExtensionEnabled(
                "ghidra-headless",
                true,
                Set.of(Capability.PROGRAM_READ, Capability.FILESYSTEM_READ));
        assertFalse(noBackend.enabled());
        assertTrue(noBackend.status().contains("sidecar"));

        AdvancedWorkbenchModel detected = new AdvancedWorkbenchModel(
                BackendState.detected("12.1.2", "/opt/ghidra/support/analyzeHeadless"));
        var unavailableExtension = detected.setExtensionEnabled(
                "bsim-client",
                true,
                Set.of(Capability.PROGRAM_READ, Capability.NETWORK));
        assertFalse(unavailableExtension.enabled());
        assertTrue(unavailableExtension.status().contains("not connected"));
        assertEquals(ScriptStatus.BACKEND_UNAVAILABLE,
                detected.evaluate("getFunctionAt(currentAddress)").status());
    }
}
