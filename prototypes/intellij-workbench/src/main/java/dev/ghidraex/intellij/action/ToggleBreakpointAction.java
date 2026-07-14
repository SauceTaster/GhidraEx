package dev.ghidraex.intellij.action;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import dev.ghidraex.intellij.session.WorkbenchSession;
import org.jetbrains.annotations.NotNull;

public final class ToggleBreakpointAction extends DumbAwareAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        if (project == null) {
            return;
        }
        WorkbenchSession.getInstance(project).toggleBreakpointAtSelectedSymbol();
        LabNavigation.show(project, LabNavigation.DEBUGGER_CONTENT);
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null);
    }
}
