package dev.ghidraex.intellij.startup;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import dev.ghidraex.intellij.session.WorkbenchSession;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Opens the complete prototype only for the disposable project prepared by the runIde task. */
public final class DemoWorkbenchStartupActivity implements StartupActivity.DumbAware {
    private static final Logger LOG = Logger.getInstance(DemoWorkbenchStartupActivity.class);
    private static final String DEMO_MARKER = ".ghidraex-demo";
    private static final String NOTIFICATION_GROUP = "GhidraEx Analysis";
    private static final String PROJECTS_TOOL_WINDOW = "RE Projects";
    private static final String SYMBOLS_TOOL_WINDOW = "RE Symbols";
    private static final String INSPECTOR_TOOL_WINDOW = "RE Inspector";

    @Override
    public void runActivity(@NotNull Project project) {
        if (!isDemoProject(project)) {
            return;
        }

        ToolWindowManager.getInstance(project).invokeLater(() -> openWorkbench(project));
    }

    private static boolean isDemoProject(Project project) {
        String basePath = project.getBasePath();
        return basePath != null && Files.isRegularFile(Path.of(basePath).resolve(DEMO_MARKER));
    }

    private static void openWorkbench(Project project) {
        if (project.isDisposed()) {
            return;
        }

        var session = WorkbenchSession.getInstance(project);
        var editorManager = FileEditorManager.getInstance(project);
        editorManager.openFile(session.decompilerFile(), false);
        editorManager.openFile(session.listingFile(), true);

        var toolWindows = ToolWindowManager.getInstance(project);
        show(toolWindows.getToolWindow(PROJECTS_TOOL_WINDOW), false);
        show(toolWindows.getToolWindow(SYMBOLS_TOOL_WINDOW), true);
        show(toolWindows.getToolWindow(INSPECTOR_TOOL_WINDOW), false);

        // Keep the listing selected after tool-window construction has initialized all providers.
        editorManager.openFile(session.listingFile(), true);

        long visibleToolWindows = Arrays.stream(new String[]{
                        PROJECTS_TOOL_WINDOW,
                        SYMBOLS_TOOL_WINDOW,
                        INSPECTOR_TOOL_WINDOW
                })
                .map(toolWindows::getToolWindow)
                .filter(window -> window != null && window.isVisible())
                .count();
        int listingEditors = editorManager.getAllEditors(session.listingFile()).length;
        int decompilerEditors = editorManager.getAllEditors(session.decompilerFile()).length;

        LOG.info("GHIDRAEX_DEMO_READY project=" + project.getBasePath()
                + " listingRows=" + session.engine().instructionCount(session.program().id())
                + " listingEditors=" + listingEditors
                + " decompilerEditors=" + decompilerEditors
                + " visibleToolWindows=" + visibleToolWindows);

        NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(
                        "GhidraEx demo workbench ready",
                        "Opened the virtualized listing, decompiler, symbols, and inspector.",
                        NotificationType.INFORMATION
                )
                .notify(project);
    }

    private static void show(ToolWindow toolWindow, boolean splitMode) {
        if (toolWindow == null) {
            return;
        }
        if (toolWindow.isSplitMode() != splitMode) {
            toolWindow.setSplitMode(splitMode, null);
        }
        toolWindow.show();
    }
}
