package dev.ghidraex.intellij.action;

/** Registered action identifiers shared with native tool-window toolbars. */
public final class WorkbenchActionIds {
    public static final String DEBUG_START = "dev.ghidraex.intellij.DebugStart";
    public static final String DEBUG_RESUME = "dev.ghidraex.intellij.DebugResume";
    public static final String DEBUG_PAUSE = "dev.ghidraex.intellij.DebugPause";
    public static final String DEBUG_STEP_INTO = "dev.ghidraex.intellij.DebugStepInto";
    public static final String DEBUG_STEP_OVER = "dev.ghidraex.intellij.DebugStepOver";
    public static final String DEBUG_STEP_OUT = "dev.ghidraex.intellij.DebugStepOut";
    public static final String DEBUG_STOP = "dev.ghidraex.intellij.DebugStop";
    public static final String DEBUG_TOGGLE_BREAKPOINT = "dev.ghidraex.intellij.DebugToggleBreakpoint";
    public static final String SHOW_SCRIPT_CONSOLE = "dev.ghidraex.intellij.ShowScriptConsole";
    public static final String REFRESH_BACKEND = "dev.ghidraex.intellij.RefreshBackend";

    private WorkbenchActionIds() {
    }
}
