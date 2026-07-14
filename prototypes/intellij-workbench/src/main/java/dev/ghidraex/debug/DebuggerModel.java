package dev.ghidraex.debug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Toolkit-neutral debugger workflow state.
 *
 * <p>This model intentionally simulates target control. It lets native IDE actions and views be
 * tested against realistic transitions while keeping "target attached" distinct from a future
 * Ghidra Trace/RMI adapter.</p>
 */
public final class DebuggerModel {
    private static final int MAX_EVENTS = 100;

    public enum State {
        INACTIVE,
        RUNNING,
        SUSPENDED,
        TERMINATED
    }

    public enum StopReason {
        NONE,
        ENTRY,
        BREAKPOINT,
        STEP,
        USER_PAUSE,
        TERMINATED
    }

    public record Breakpoint(long address, String symbol, boolean enabled, int hitCount) {
        public Breakpoint {
            Objects.requireNonNull(symbol, "symbol");
        }
    }

    public record StackFrame(int level, String function, long address, String module) {
        public StackFrame {
            Objects.requireNonNull(function, "function");
            Objects.requireNonNull(module, "module");
        }
    }

    public record Event(long sequence, String command, String detail) {
        public Event {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(detail, "detail");
        }
    }

    public record Snapshot(
            State state,
            StopReason stopReason,
            long programCounter,
            String activeThread,
            List<StackFrame> frames,
            Map<String, Long> registers,
            List<Breakpoint> breakpoints,
            List<Event> events
    ) {
        public Snapshot {
            frames = List.copyOf(frames);
            registers = Collections.unmodifiableMap(new LinkedHashMap<>(registers));
            breakpoints = List.copyOf(breakpoints);
            events = List.copyOf(events);
        }
    }

    private final long entryPoint;
    private final long imageEnd;
    private final String module;
    private final List<Breakpoint> breakpoints = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();
    private final List<String> callStack = new ArrayList<>();

    private State state = State.INACTIVE;
    private StopReason stopReason = StopReason.NONE;
    private long programCounter;
    private long sequence;

    public DebuggerModel(long entryPoint, long imageEnd, String module) {
        if (imageEnd <= entryPoint) {
            throw new IllegalArgumentException("imageEnd must follow entryPoint");
        }
        this.entryPoint = entryPoint;
        this.imageEnd = imageEnd;
        this.module = Objects.requireNonNull(module, "module");
        this.programCounter = entryPoint;
        record("create", "Synthetic target is not attached");
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                state,
                stopReason,
                programCounter,
                "Main Thread [1]",
                frames(),
                registers(),
                breakpoints,
                events
        );
    }

    public synchronized void addBreakpoint(long address, String symbol) {
        requireAddress(address);
        if (breakpoints.stream().noneMatch(candidate -> candidate.address() == address)) {
            breakpoints.add(new Breakpoint(address, symbol, true, 0));
            breakpoints.sort((left, right) -> Long.compare(left.address(), right.address()));
            record("breakpoint.add", symbol + " @ " + hex(address));
        }
    }

    public synchronized void toggleBreakpoint(long address, String symbol) {
        var existing = breakpoints.stream()
                .filter(candidate -> candidate.address() == address)
                .findFirst();
        if (existing.isPresent()) {
            breakpoints.remove(existing.get());
            record("breakpoint.remove", existing.get().symbol() + " @ " + hex(address));
        } else {
            addBreakpoint(address, symbol);
        }
    }

    public synchronized void setBreakpointEnabled(long address, boolean enabled) {
        for (int index = 0; index < breakpoints.size(); index++) {
            Breakpoint breakpoint = breakpoints.get(index);
            if (breakpoint.address() == address) {
                breakpoints.set(index, new Breakpoint(
                        breakpoint.address(),
                        breakpoint.symbol(),
                        enabled,
                        breakpoint.hitCount()
                ));
                record(enabled ? "breakpoint.enable" : "breakpoint.disable", breakpoint.symbol());
                return;
            }
        }
        throw new IllegalArgumentException("Unknown breakpoint: " + hex(address));
    }

    public synchronized void start() {
        if (state != State.INACTIVE && state != State.TERMINATED) {
            throw new IllegalStateException("Debugger is already active");
        }
        state = State.SUSPENDED;
        stopReason = StopReason.ENTRY;
        programCounter = entryPoint;
        callStack.clear();
        callStack.add("entry");
        record("start", "Suspended at program entry " + hex(programCounter));
    }

    public synchronized void resume() {
        requireSuspended("resume");
        Breakpoint next = breakpoints.stream()
                .filter(Breakpoint::enabled)
                .filter(candidate -> candidate.address() > programCounter)
                .findFirst()
                .orElse(null);
        if (next == null) {
            state = State.RUNNING;
            stopReason = StopReason.NONE;
            record("resume", "Synthetic target running; Pause is now available");
            return;
        }

        programCounter = next.address();
        stopReason = StopReason.BREAKPOINT;
        incrementHitCount(next.address());
        callStack.set(callStack.size() - 1, next.symbol());
        record("resume", "Hit " + next.symbol() + " @ " + hex(next.address()));
    }

    public synchronized void pause() {
        if (state != State.RUNNING) {
            throw new IllegalStateException("pause requires a running target");
        }
        state = State.SUSPENDED;
        stopReason = StopReason.USER_PAUSE;
        programCounter = advance(programCounter, 4);
        record("pause", "User pause @ " + hex(programCounter));
    }

    public synchronized void stepOver() {
        requireSuspended("step over");
        programCounter = advance(programCounter, 4);
        stopReason = StopReason.STEP;
        record("step.over", "Advanced to " + hex(programCounter));
    }

    public synchronized void stepInto() {
        requireSuspended("step into");
        programCounter = advance(programCounter, 4);
        callStack.add("sub_" + Long.toHexString(programCounter));
        stopReason = StopReason.STEP;
        record("step.into", "Entered synthetic frame @ " + hex(programCounter));
    }

    public synchronized void stepOut() {
        requireSuspended("step out");
        if (callStack.size() > 1) {
            callStack.remove(callStack.size() - 1);
        }
        programCounter = advance(programCounter, 4);
        stopReason = StopReason.STEP;
        record("step.out", "Returned to " + callStack.getLast() + " @ " + hex(programCounter));
    }

    public synchronized void stop() {
        if (state == State.INACTIVE || state == State.TERMINATED) {
            throw new IllegalStateException("stop requires an active target");
        }
        state = State.TERMINATED;
        stopReason = StopReason.TERMINATED;
        record("stop", "Synthetic target terminated");
    }

    private List<StackFrame> frames() {
        if (callStack.isEmpty()) {
            return List.of();
        }
        var frames = new ArrayList<StackFrame>(callStack.size());
        for (int index = callStack.size() - 1; index >= 0; index--) {
            int level = callStack.size() - 1 - index;
            frames.add(new StackFrame(level, callStack.get(index), programCounter - level * 4L, module));
        }
        return frames;
    }

    private Map<String, Long> registers() {
        var registers = new LinkedHashMap<String, Long>();
        registers.put("RIP", programCounter);
        registers.put("RSP", 0x0000_7fff_ffff_e000L - Math.max(0, callStack.size() - 1) * 0x20L);
        registers.put("RBP", 0x0000_7fff_ffff_e040L - Math.max(0, callStack.size() - 1) * 0x20L);
        registers.put("RAX", sequence);
        registers.put("RFLAGS", state == State.SUSPENDED ? 0x202L : 0x206L);
        return registers;
    }

    private void incrementHitCount(long address) {
        for (int index = 0; index < breakpoints.size(); index++) {
            Breakpoint breakpoint = breakpoints.get(index);
            if (breakpoint.address() == address) {
                breakpoints.set(index, new Breakpoint(
                        breakpoint.address(),
                        breakpoint.symbol(),
                        breakpoint.enabled(),
                        breakpoint.hitCount() + 1
                ));
                return;
            }
        }
    }

    private void requireSuspended(String operation) {
        if (state != State.SUSPENDED) {
            throw new IllegalStateException(operation + " requires a suspended target");
        }
    }

    private void requireAddress(long address) {
        if (address < 0) {
            throw new IllegalArgumentException("Address must be non-negative: " + address);
        }
    }

    private long advance(long address, long delta) {
        if (address < imageEnd) {
            return Math.min(imageEnd - 1, address + delta);
        }
        return address > Long.MAX_VALUE - delta ? Long.MAX_VALUE : address + delta;
    }

    private void record(String command, String detail) {
        events.add(new Event(++sequence, command, detail));
        if (events.size() > MAX_EVENTS) {
            events.removeFirst();
        }
    }

    private static String hex(long address) {
        return "0x%08x".formatted(address);
    }
}
