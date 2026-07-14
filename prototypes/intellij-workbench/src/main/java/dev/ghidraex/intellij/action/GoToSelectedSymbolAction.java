package dev.ghidraex.intellij.action;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAwareAction;
import dev.ghidraex.intellij.session.WorkbenchSession;
import org.jetbrains.annotations.NotNull;

import dev.ghidraex.intellij.editor.VirtualizedListingEditor;

public final class GoToSelectedSymbolAction extends DumbAwareAction {
    public static final String ID = "dev.ghidraex.intellij.GoToSelectedSymbol";

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        if (project == null) {
            return;
        }

        navigate(project);
    }

    public static void navigate(com.intellij.openapi.project.Project project) {
        var session = WorkbenchSession.getInstance(project);
        navigate(project, session.selectedSymbol().address());
    }

    public static void navigate(com.intellij.openapi.project.Project project, long address) {
        var session = WorkbenchSession.getInstance(project);
        var editors = FileEditorManager.getInstance(project);
        editors.openFile(session.listingFile(), true);
        for (var editor : editors.getAllEditors(session.listingFile())) {
            if (editor instanceof VirtualizedListingEditor listingEditor) {
                listingEditor.revealAddress(address);
            }
        }
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null);
    }
}
