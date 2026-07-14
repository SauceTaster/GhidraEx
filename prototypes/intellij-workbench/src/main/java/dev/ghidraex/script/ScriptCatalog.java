package dev.ghidraex.script;

import dev.ghidraex.backend.BackendProbe;

import java.util.List;
import java.util.Objects;

/** Representative script workflows and their actual runtime requirements. */
public final class ScriptCatalog {
    public enum Requirement {
        ENGINE_CONTRACT,
        GHIDRA_PROGRAM_TRANSACTION,
        GHIDRA_DEBUGGER_TRACE
    }

    public record Script(
            String name,
            String language,
            String description,
            Requirement requirement,
            String demoCommand
    ) {
        public Script {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(language, "language");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(requirement, "requirement");
            Objects.requireNonNull(demoCommand, "demoCommand");
        }

        public String availability(BackendProbe.Status backend) {
            if (requirement == Requirement.ENGINE_CONTRACT) {
                return "Runnable through synthetic engine contract";
            }
            if (backend.realBackendAvailable()) {
                return "Ghidra detected; adapter connection still required";
            }
            return "Requires a configured and connected Ghidra backend";
        }
    }

    private static final List<Script> SCRIPTS = List.of(
            new Script(
                    "EnumerateFunctions.py",
                    "Python / PyGhidra contract",
                    "Read-only enumeration against the coarse symbol API.",
                    Requirement.ENGINE_CONTRACT,
                    "symbol(\"int\")"
            ),
            new Script(
                    "RenameFromCsv.java",
                    "Java / GhidraScript",
                    "Bulk rename workflow with preview, transaction, rollback, and audit output.",
                    Requirement.GHIDRA_PROGRAM_TRANSACTION,
                    "backend()"
            ),
            new Script(
                    "TraceCoverage.py",
                    "Python / Trace API",
                    "Color executed blocks from a live debugger trace and follow the active snap.",
                    Requirement.GHIDRA_DEBUGGER_TRACE,
                    "debugger()"
            )
    );

    private ScriptCatalog() {
    }

    public static List<Script> scripts() {
        return SCRIPTS;
    }
}
