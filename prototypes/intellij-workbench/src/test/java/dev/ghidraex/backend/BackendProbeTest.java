package dev.ghidraex.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendProbeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsSyntheticOnlyWhenNoGhidraHomeIsConfigured() {
        var status = BackendProbe.discover(Map.of(), new Properties());

        assertEquals(BackendProbe.DiscoveryState.NOT_CONFIGURED, status.discoveryState());
        assertEquals("SyntheticAnalysisEngine", status.activeBackend());
        assertFalse(status.realBackendAvailable());
        assertNull(status.ghidraHome());
    }

    @Test
    void rejectsAnIncompleteConfiguredDistribution() {
        var status = BackendProbe.inspect(temporaryDirectory, "test");

        assertEquals(BackendProbe.DiscoveryState.INVALID_CONFIGURATION, status.discoveryState());
        assertFalse(status.realBackendAvailable());
        assertTrue(status.detail().contains("not a complete Ghidra distribution"));
    }

    @Test
    void detectsHeadlessLauncherAndVersionWithoutActivatingIt() throws IOException {
        Files.createDirectories(temporaryDirectory.resolve("Ghidra"));
        Path launcher = createLauncher(temporaryDirectory, "#!/bin/sh\n");
        Files.writeString(temporaryDirectory.resolve("Ghidra/application.properties"),
                "application.name=Ghidra\napplication.version=12.1\n");

        var properties = new Properties();
        properties.setProperty(BackendProbe.HOME_PROPERTY, temporaryDirectory.toString());
        var status = BackendProbe.discover(
                Map.of(BackendProbe.HOME_ENVIRONMENT, "/ignored/environment/path"),
                properties
        );

        assertEquals(BackendProbe.DiscoveryState.GHIDRA_AVAILABLE, status.discoveryState());
        assertTrue(status.realBackendAvailable());
        assertEquals("12.1", status.ghidraVersion());
        assertEquals("SyntheticAnalysisEngine", status.activeBackend());
        assertEquals("system property " + BackendProbe.HOME_PROPERTY, status.configuredBy());
        assertEquals(launcher, status.headlessLauncher());
    }

    @Test
    void capabilityAndPluginCatalogsKeepDiscoverySeparateFromConnection() throws IOException {
        Files.createDirectories(temporaryDirectory.resolve("Ghidra"));
        createLauncher(temporaryDirectory, "launcher");
        Files.writeString(temporaryDirectory.resolve("Ghidra/application.properties"),
                "application.version=12.1\n");
        var status = BackendProbe.inspect(temporaryDirectory, "test");

        var capabilities = RuntimeCapabilityCatalog.forStatus(status);
        assertTrue(capabilities.stream().anyMatch(capability ->
                capability.surface().equals("Headless import and analysis")
                        && capability.availability()
                        == RuntimeCapabilityCatalog.Availability.AVAILABLE_NOT_CONNECTED));
        assertTrue(capabilities.stream().anyMatch(capability ->
                capability.provider().equals("SyntheticAnalysisEngine")
                        && capability.availability()
                        == RuntimeCapabilityCatalog.Availability.ACTIVE_SYNTHETIC));
        assertTrue(capabilities.stream().anyMatch(capability ->
                capability.surface().equals("Debugger Trace/RMI")
                        && capability.availability()
                        == RuntimeCapabilityCatalog.Availability.ADAPTER_REQUIRED));

        var integrations = PluginIntegrationCatalog.forStatus(status);
        assertTrue(integrations.stream().anyMatch(integration ->
                integration.host().equals("Ghidra")
                        && integration.state() == PluginIntegrationCatalog.State.BACKEND_REQUIRED));
    }

    @Test
    void productionDiscoveryFindsARepositoryLocalToolchainWithoutEnvironmentSetup() throws IOException {
        Path repository = Files.createDirectories(temporaryDirectory.resolve("repo"));
        Path home = Files.createDirectories(
                repository.resolve(".toolchains/ghidra_12.1.2_PUBLIC"));
        Files.createDirectories(home.resolve("Ghidra"));
        createLauncher(home, "#!/bin/sh\n");
        Files.writeString(home.resolve("Ghidra/application.properties"),
                "application.version=12.1.2\n");

        var status = BackendProbe.discover(
                Map.of(), new Properties(), repository.resolve("nested/project"));

        assertEquals(BackendProbe.DiscoveryState.GHIDRA_AVAILABLE, status.discoveryState());
        assertEquals(home, status.ghidraHome());
        assertEquals("repository toolchain", status.configuredBy());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void pathDiscoveryResolvesAStandardSymlinkedLauncher() throws IOException {
        Path home = Files.createDirectories(temporaryDirectory.resolve("ghidra_12.1.2_PUBLIC"));
        Files.createDirectories(home.resolve("support"));
        Files.createDirectories(home.resolve("Ghidra"));
        Path launcher = home.resolve("support/analyzeHeadless");
        Files.writeString(launcher, "#!/bin/sh\n");
        assertTrue(launcher.toFile().setExecutable(true));
        Files.writeString(home.resolve("Ghidra/application.properties"),
                "application.version=12.1.2\n");
        Path bin = Files.createDirectories(temporaryDirectory.resolve("bin"));
        Files.createSymbolicLink(bin.resolve("analyzeHeadless"), launcher);

        var status = BackendProbe.discover(
                Map.of("PATH", bin.toString()),
                new Properties(),
                temporaryDirectory.resolve("unrelated/project"));

        assertEquals(BackendProbe.DiscoveryState.GHIDRA_AVAILABLE, status.discoveryState());
        assertEquals(home.toRealPath(), status.ghidraHome());
        assertEquals("PATH", status.configuredBy());
    }

    private static Path createLauncher(Path home, String content) throws IOException {
        Path support = Files.createDirectories(home.resolve("support"));
        Path launcher = support.resolve(isWindows() ? "analyzeHeadless.bat" : "analyzeHeadless");
        Files.writeString(launcher, content);
        if (!isWindows()) {
            assertTrue(launcher.toFile().setExecutable(true));
        }
        return launcher;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
