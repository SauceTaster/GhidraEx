package dev.ghidraex.intellij.action;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAwareAction;
import dev.ghidraex.intellij.session.WorkbenchSession;
import org.jetbrains.annotations.NotNull;

public final class OpenSyntheticProgramAction extends DumbAwareAction {
    public static final String ID = "dev.ghidraex.intellij.OpenSyntheticProgram";

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        if (project == null) {
            return;
        }

        var session = WorkbenchSession.getInstance(project);
        var editors = FileEditorManager.getInstance(project);
        editors.openFile(session.decompilerFile(), false);
        editors.openFile(session.listingFile(), true);
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null);
    }
}
