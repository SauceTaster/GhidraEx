package dev.ghidraex.backend;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Discovers a Ghidra distribution without loading any Ghidra classes into the IDE process.
 *
 * <p>Discovery is deliberately separate from activation. Finding {@code analyzeHeadless} proves
 * that a distribution can be used by a future adapter; it does not imply that the current
 * synthetic {@code AnalysisEngine} has silently become a real Ghidra backend.</p>
 */
public final class BackendProbe {
    public static final String HOME_PROPERTY = "ghidraex.ghidra.home";
    public static final String HOME_ENVIRONMENT = "GHIDRA_HOME";
    public static final String INSTALL_ENVIRONMENT = "GHIDRA_INSTALL_DIR";

    public enum DiscoveryState {
        NOT_CONFIGURED,
        INVALID_CONFIGURATION,
        GHIDRA_AVAILABLE
    }

    public record Status(
            DiscoveryState discoveryState,
            String activeBackend,
            String configuredBy,
            Path ghidraHome,
            Path headlessLauncher,
            String ghidraVersion,
            String detail
    ) {
        public Status {
            Objects.requireNonNull(discoveryState, "discoveryState");
            Objects.requireNonNull(activeBackend, "activeBackend");
            Objects.requireNonNull(configuredBy, "configuredBy");
            Objects.requireNonNull(ghidraVersion, "ghidraVersion");
            Objects.requireNonNull(detail, "detail");
        }

        public boolean realBackendAvailable() {
            return discoveryState == DiscoveryState.GHIDRA_AVAILABLE;
        }
    }

    private BackendProbe() {
    }

    public static Status discover() {
        return discover(Path.of(System.getProperty("user.dir", ".")));
    }

    public static Status discover(Path searchFrom) {
        return discover(System.getenv(), System.getProperties(), searchFrom);
    }

    static Status discover(Map<String, String> environment, Properties properties, Path searchFrom) {
        Status configured = discover(environment, properties);
        if (configured.discoveryState() != DiscoveryState.NOT_CONFIGURED) {
            return configured;
        }
        for (Candidate candidate : automaticCandidates(environment, searchFrom)) {
            Status inspected = inspect(candidate.home(), candidate.source());
            if (inspected.realBackendAvailable()) {
                return inspected;
            }
        }
        return configured;
    }

    public static Status discover(Map<String, String> environment, Properties properties) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(properties, "properties");

        String configuredHome = firstNonBlank(
                properties.getProperty(HOME_PROPERTY),
                environment.get(HOME_ENVIRONMENT),
                environment.get(INSTALL_ENVIRONMENT)
        );
        if (configuredHome == null) {
            return new Status(
                    DiscoveryState.NOT_CONFIGURED,
                    "SyntheticAnalysisEngine",
                    "none",
                    null,
                    null,
                    "not detected",
                    "Set -D" + HOME_PROPERTY + "=/path/to/ghidra or " + HOME_ENVIRONMENT
                            + " to make a real distribution available for adapter tests."
            );
        }

        String configuredBy = configuredSource(environment, properties);
        try {
            return inspect(Path.of(configuredHome).toAbsolutePath().normalize(), configuredBy);
        } catch (InvalidPathException invalidPath) {
            return new Status(
                    DiscoveryState.INVALID_CONFIGURATION,
                    "SyntheticAnalysisEngine",
                    configuredBy,
                    null,
                    null,
                    "unknown",
                    "The configured Ghidra home is not a valid filesystem path."
            );
        }
    }

    public static Status inspect(Path ghidraHome, String configuredBy) {
        Objects.requireNonNull(ghidraHome, "ghidraHome");
        Objects.requireNonNull(configuredBy, "configuredBy");

        Path launcher = findLauncher(ghidraHome);
        Path propertiesFile = ghidraHome.resolve("Ghidra/application.properties");
        if (launcher == null || !Files.isRegularFile(propertiesFile)) {
            return new Status(
                    DiscoveryState.INVALID_CONFIGURATION,
                    "SyntheticAnalysisEngine",
                    configuredBy,
                    ghidraHome,
                    launcher,
                    "unknown",
                    "The configured directory is not a complete Ghidra distribution; expected "
                            + "support/analyzeHeadless and Ghidra/application.properties."
            );
        }

        return new Status(
                DiscoveryState.GHIDRA_AVAILABLE,
                "SyntheticAnalysisEngine",
                configuredBy,
                ghidraHome,
                launcher,
                readVersion(propertiesFile),
                "A real Ghidra headless launcher is available, but the workbench remains on the "
                        + "synthetic adapter until an explicit backend connection is started."
        );
    }

    private static Path findLauncher(Path ghidraHome) {
        if (isWindows()) {
            Path windowsLauncher = ghidraHome.resolve("support/analyzeHeadless.bat");
            return Files.isRegularFile(windowsLauncher) ? windowsLauncher : null;
        }
        Path unixLauncher = ghidraHome.resolve("support/analyzeHeadless");
        return Files.isRegularFile(unixLauncher) && Files.isExecutable(unixLauncher)
                ? unixLauncher
                : null;
    }

    private static List<Candidate> automaticCandidates(
            Map<String, String> environment, Path searchFrom) {
        var candidates = new LinkedHashMap<Path, String>();
        Path cursor = searchFrom.toAbsolutePath().normalize();
        while (cursor != null) {
            addGhidraDirectories(candidates, cursor.resolve(".toolchains"), "repository toolchain");
            cursor = cursor.getParent();
        }

        Path home = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        addGhidraDirectories(candidates, home, "user home");
        addGhidraDirectories(candidates, home.resolve("Applications"), "user Applications");
        addGhidraDirectories(candidates, Path.of("/opt"), "/opt");
        addGhidraDirectories(candidates, Path.of("/usr/local"), "/usr/local");

        String pathValue = environment.get("PATH");
        if (pathValue != null) {
            for (String entry : pathValue.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (entry.isBlank()) continue;
                Path directory;
                try {
                    directory = Path.of(entry);
                } catch (InvalidPathException ignored) {
                    continue;
                }
                for (String launcherName : isWindows()
                        ? List.of("analyzeHeadless.bat")
                        : List.of("analyzeHeadless")) {
                    Path launcher = directory.resolve(launcherName);
                    if (Files.isRegularFile(launcher) && (isWindows() || Files.isExecutable(launcher))) {
                        Path resolvedLauncher;
                        try {
                            resolvedLauncher = launcher.toRealPath();
                        } catch (IOException ignored) {
                            resolvedLauncher = launcher.toAbsolutePath().normalize();
                        }
                        Path support = resolvedLauncher.getParent();
                        if (support != null && support.getParent() != null) {
                            candidates.putIfAbsent(support.getParent(), "PATH");
                        }
                    }
                }
            }
        }

        var result = new ArrayList<Candidate>();
        candidates.forEach((path, source) -> result.add(new Candidate(path, source)));
        return result;
    }

    private static void addGhidraDirectories(
            Map<Path, String> candidates, Path parent, String source) {
        if (!Files.isDirectory(parent)) return;
        try (var paths = Files.list(parent)) {
            paths.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("ghidra_"))
                    .filter(path -> path.getFileName().toString().endsWith("_PUBLIC"))
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> candidates.putIfAbsent(
                            path.toAbsolutePath().normalize(), source));
        } catch (IOException ignored) {
            // Discovery is best-effort; explicit configuration still reports actionable errors.
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String readVersion(Path propertiesFile) {
        var properties = new Properties();
        try (InputStream input = Files.newInputStream(propertiesFile)) {
            properties.load(input);
            return firstNonBlank(properties.getProperty("application.version"), "unknown");
        } catch (IOException ignored) {
            return "unknown";
        }
    }

    private static String configuredSource(Map<String, String> environment, Properties properties) {
        if (!isBlank(properties.getProperty(HOME_PROPERTY))) {
            return "system property " + HOME_PROPERTY;
        }
        if (!isBlank(environment.get(HOME_ENVIRONMENT))) {
            return "environment " + HOME_ENVIRONMENT;
        }
        return "environment " + INSTALL_ENVIRONMENT;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.strip();
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Candidate(Path home, String source) {}
}
