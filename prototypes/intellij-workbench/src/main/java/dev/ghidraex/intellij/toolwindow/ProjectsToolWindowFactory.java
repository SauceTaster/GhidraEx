package dev.ghidraex.intellij.toolwindow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import dev.ghidraex.engine.ProgramDescriptor;
import dev.ghidraex.intellij.action.OpenSyntheticProgramAction;
import dev.ghidraex.intellij.action.RunAnalysisAction;
import dev.ghidraex.intellij.session.WorkbenchSession;
import org.jetbrains.annotations.NotNull;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public final class ProjectsToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        var session = WorkbenchSession.getInstance(project);
        var model = new CollectionListModel<>(session.engine().programs());
        var list = new JBList<>(model);
        list.setEmptyText("No programs loaded");
        list.setCellRenderer(new ProgramCellRenderer());
        list.setSelectedIndex(0);
        list.setBorder(JBUI.Borders.empty(6));
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    var editors = FileEditorManager.getInstance(project);
                    editors.openFile(session.decompilerFile(), false);
                    editors.openFile(session.listingFile(), true);
                }
            }
        });

        ToolWindowSupport.install(
                toolWindow,
                new JBScrollPane(list),
                "GhidraExProjects",
                OpenSyntheticProgramAction.ID,
                RunAnalysisAction.ID
        );
    }

    private static final class ProgramCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus
        ) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof ProgramDescriptor program) {
                setText("<html><b>" + program.name() + "</b><br><small>"
                        + program.architecture() + " · " + (program.byteSize() / 1024) + " KiB</small></html>");
                setBorder(JBUI.Borders.empty(8));
            }
            return this;
        }
    }
}
