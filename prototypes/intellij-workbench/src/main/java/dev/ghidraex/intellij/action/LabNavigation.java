package dev.ghidraex.intellij.action;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;

public final class LabNavigation {
    public static final String TOOL_WINDOW_ID = "RE Lab";
    public static final String DEBUGGER_CONTENT = "Debugger";
    public static final String SCRIPTING_CONTENT = "Scripts & REPL";
    public static final String RUNTIME_CONTENT = "Runtime & Plugins";

    private LabNavigation() {
    }

    public static void show(Project project, String contentName) {
        var toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow == null) {
            return;
        }
        toolWindow.show(() -> {
            for (var content : toolWindow.getContentManager().getContents()) {
                if (contentName.equals(content.getDisplayName())) {
                    toolWindow.getContentManager().setSelectedContent(content);
                    break;
                }
            }
        });
    }
}
