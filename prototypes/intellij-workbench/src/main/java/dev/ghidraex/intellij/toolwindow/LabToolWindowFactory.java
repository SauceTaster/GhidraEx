package dev.ghidraex.intellij.toolwindow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import dev.ghidraex.intellij.action.LabNavigation;
import dev.ghidraex.intellij.action.WorkbenchActionIds;
import org.jetbrains.annotations.NotNull;

/** Hosts advanced workflows as native tool-window contents, not embedded web pages. */
public final class LabToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ToolWindowSupport.installContent(
                toolWindow,
                new DebuggerPanel(project),
                LabNavigation.DEBUGGER_CONTENT,
                "GhidraExDebugger",
                WorkbenchActionIds.DEBUG_START,
                WorkbenchActionIds.DEBUG_RESUME,
                WorkbenchActionIds.DEBUG_PAUSE,
                WorkbenchActionIds.DEBUG_STEP_INTO,
                WorkbenchActionIds.DEBUG_STEP_OVER,
                WorkbenchActionIds.DEBUG_STEP_OUT,
                WorkbenchActionIds.DEBUG_STOP,
                WorkbenchActionIds.DEBUG_TOGGLE_BREAKPOINT
        );
        ToolWindowSupport.installContent(
                toolWindow,
                new ScriptConsolePanel(project),
                LabNavigation.SCRIPTING_CONTENT,
                "GhidraExScripts",
                WorkbenchActionIds.SHOW_SCRIPT_CONSOLE
        );
        ToolWindowSupport.installContent(
                toolWindow,
                new RuntimePanel(project),
                LabNavigation.RUNTIME_CONTENT,
                "GhidraExRuntime",
                WorkbenchActionIds.REFRESH_BACKEND
        );
    }
}
