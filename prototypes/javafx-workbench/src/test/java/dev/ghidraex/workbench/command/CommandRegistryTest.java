package dev.ghidraex.workbench.command;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRegistryTest {
    @Test
    void searchRanksExactThenPrefixThenContains() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("exact", "Open", () -> { }));
        registry.register(command("prefix", "Open listing", () -> { }));
        registry.register(command("contains", "Quick open dialog", () -> { }));

        var results = registry.search("open", 10);

        assertEquals("exact", results.get(0).id());
        assertEquals("prefix", results.get(1).id());
        assertEquals("contains", results.get(2).id());
    }

    @Test
    void executionRespectsEnabledStateAndRejectsDuplicateIds() {
        CommandRegistry registry = new CommandRegistry();
        AtomicInteger executions = new AtomicInteger();
        registry.register(command("run", "Run analysis", executions::incrementAndGet));
        registry.register(new WorkbenchCommand("disabled", "Disabled", "Test", "", () -> false,
                executions::incrementAndGet));

        assertTrue(registry.execute("run"));
        assertEquals(1, executions.get());
        assertFalse(registry.execute("disabled"));
        assertFalse(registry.execute("missing"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(command("run", "Run again", executions::incrementAndGet)));
    }

    private static WorkbenchCommand command(String id, String title, Runnable action) {
        return new WorkbenchCommand(id, title, "Test", "", action);
    }
}
