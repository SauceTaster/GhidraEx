package dev.ghidraex.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebuggerModelTest {
    private static final long ENTRY = 0x0040_1000L;
    private final DebuggerModel debugger = new DebuggerModel(ENTRY, ENTRY + 0x1000, "demo.bin");

    @Test
    void exercisesEntryBreakpointSteppingRunningPauseAndTerminationStates() {
        debugger.addBreakpoint(ENTRY + 0x20, "parse_header");
        debugger.start();

        var entry = debugger.snapshot();
        assertEquals(DebuggerModel.State.SUSPENDED, entry.state());
        assertEquals(DebuggerModel.StopReason.ENTRY, entry.stopReason());
        assertEquals("entry", entry.frames().getFirst().function());

        debugger.resume();
        var breakpoint = debugger.snapshot();
        assertEquals(DebuggerModel.State.SUSPENDED, breakpoint.state());
        assertEquals(DebuggerModel.StopReason.BREAKPOINT, breakpoint.stopReason());
        assertEquals(ENTRY + 0x20, breakpoint.programCounter());
        assertEquals(1, breakpoint.breakpoints().getFirst().hitCount());

        debugger.stepInto();
        assertEquals(2, debugger.snapshot().frames().size());
        debugger.stepOut();
        assertEquals(1, debugger.snapshot().frames().size());

        debugger.resume();
        assertEquals(DebuggerModel.State.RUNNING, debugger.snapshot().state());
        debugger.pause();
        assertEquals(DebuggerModel.StopReason.USER_PAUSE, debugger.snapshot().stopReason());
        debugger.stop();
        assertEquals(DebuggerModel.State.TERMINATED, debugger.snapshot().state());
    }

    @Test
    void breakpointEditsAndSnapshotsAreDeterministicAndImmutable() {
        debugger.addBreakpoint(ENTRY + 4, "first");
        debugger.setBreakpointEnabled(ENTRY + 4, false);
        assertEquals(false, debugger.snapshot().breakpoints().getFirst().enabled());

        debugger.toggleBreakpoint(ENTRY + 4, "first");
        assertTrue(debugger.snapshot().breakpoints().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> debugger.snapshot().events().clear());
        assertThrows(IllegalArgumentException.class,
                () -> debugger.addBreakpoint(-1, "invalid"));
    }

    @Test
    void rejectsCommandsThatDoNotMatchTheCurrentDebuggerState() {
        assertThrows(IllegalStateException.class, debugger::resume);
        assertThrows(IllegalStateException.class, debugger::pause);
        assertThrows(IllegalStateException.class, debugger::stop);

        debugger.start();
        assertThrows(IllegalStateException.class, debugger::start);
        debugger.resume();
        assertThrows(IllegalStateException.class, debugger::stepOver);
    }
}
