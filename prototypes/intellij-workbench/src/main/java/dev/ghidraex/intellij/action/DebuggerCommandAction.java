package dev.ghidraex.intellij.action;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import dev.ghidraex.debug.DebuggerModel;
import dev.ghidraex.intellij.session.WorkbenchSession;
import org.jetbrains.annotations.NotNull;

abstract class DebuggerCommandAction extends DumbAwareAction {
    @Override
    public final void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        if (project == null) {
            return;
        }
        perform(WorkbenchSession.getInstance(project));
        LabNavigation.show(project, LabNavigation.DEBUGGER_CONTENT);
    }

    @Override
    public final void update(@NotNull AnActionEvent event) {
        var project = event.getProject();
        event.getPresentation().setEnabled(project != null
                && isEnabled(WorkbenchSession.getInstance(project).debugger()));
    }

    protected abstract void perform(WorkbenchSession session);

    protected abstract boolean isEnabled(DebuggerModel.Snapshot snapshot);
}
