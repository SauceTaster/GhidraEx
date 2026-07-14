package dev.ghidraex.script;

import dev.ghidraex.backend.BackendProbe;
import dev.ghidraex.debug.DebuggerModel;
import dev.ghidraex.engine.SyntheticAnalysisEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplModelTest {
    private final ReplModel repl = new ReplModel();
    private final SyntheticAnalysisEngine engine = new SyntheticAnalysisEngine(Duration.ZERO);
    private final DebuggerModel debugger = new DebuggerModel(
            SyntheticAnalysisEngine.IMAGE_BASE,
            SyntheticAnalysisEngine.IMAGE_BASE + 0x2A_000,
            "orbit-controller.bin"
    );
    private ReplModel.Context context;

    @BeforeEach
    void prepareContext() {
        var program = engine.programs().getFirst();
        var symbols = engine.symbols(program.id());
        context = new ReplModel.Context(
                program,
                symbols,
                symbols.getFirst(),
                debugger.snapshot(),
                BackendProbe.discover(Map.of(), new Properties())
        );
    }

    @Test
    void executesReadOnlyBridgeCommandsInBothPromptModes() {
        var program = repl.evaluate(ReplModel.Language.PYTHON_CONTRACT, "program()", context);
        var symbol = repl.evaluate(ReplModel.Language.JAVA_CONTRACT, "symbol(\"crc\");", context);
        var backend = repl.evaluate(ReplModel.Language.PYTHON_CONTRACT, "backend()", context);

        assertTrue(program.output().contains("orbit-controller.bin"));
        assertTrue(symbol.output().contains("crc16"));
        assertTrue(backend.output().contains("active=SyntheticAnalysisEngine"));
        assertEquals(3, repl.history().size());
    }

    @Test
    void returnsATypedNavigationRequestWithoutTouchingUiState() {
        var result = repl.evaluate(
                ReplModel.Language.PYTHON_CONTRACT,
                "goTo(0x00401120)",
                context
        );

        assertEquals(ReplModel.ResultKind.OUTPUT, result.resultKind());
        assertTrue(result.navigationAddress().isPresent());
        assertEquals(0x0040_1120L, result.navigationAddress().getAsLong());

        var outside = repl.evaluate(
                ReplModel.Language.PYTHON_CONTRACT,
                "goTo(0x00700000)",
                context
        );
        assertEquals(ReplModel.ResultKind.ERROR, outside.resultKind());
        assertFalse(outside.navigationAddress().isPresent());
    }

    @Test
    void failsUnsupportedLanguageCodeInsteadOfPretendingToEvaluateIt() {
        var result = repl.evaluate(
                ReplModel.Language.PYTHON_CONTRACT,
                "for function in currentProgram.getFunctionManager(): pass",
                context
        );

        assertEquals(ReplModel.ResultKind.ERROR, result.resultKind());
        assertTrue(result.output().contains("No Python or Java VM is attached"));
    }

    @Test
    void scriptCatalogMarksMutationAndTraceWorkflowsAsBackendDependent() {
        var scripts = ScriptCatalog.scripts();
        assertEquals(3, scripts.size());
        assertEquals(ScriptCatalog.Requirement.ENGINE_CONTRACT, scripts.getFirst().requirement());
        assertTrue(scripts.get(1).availability(context.backend()).contains("Requires"));
        assertTrue(scripts.get(2).availability(context.backend()).contains("Requires"));
    }
}
