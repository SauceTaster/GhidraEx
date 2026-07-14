package dev.ghidraex.intellij.toolwindow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import dev.ghidraex.engine.ProgramDescriptor;
import dev.ghidraex.intellij.action.OpenSyntheticProgramAction;
import dev.ghidraex.intellij.action.RunAnalysisAction;
import dev.ghidraex.intellij.session.WorkbenchSession;
import dev.ghidraex.intellij.session.SessionListener;
import dev.ghidraex.intellij.read.ReadModels;
import dev.ghidraex.viewstate.ContextualReadSlot;
import org.jetbrains.annotations.NotNull;

import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public final class ProjectsToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        var session = WorkbenchSession.getInstance(project);
        var model = new DefaultListModel<ProgramDescriptor>();
        var list = new JBList<>(model);
        list.setEmptyText("No programs loaded");
        list.setCellRenderer(new ProgramCellRenderer());
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

        var status = new JBLabel();
        status.setBorder(JBUI.Borders.empty(7, 10));
        apply(session.programsRead(), status, model, list);
        project.getMessageBus().connect(project).subscribe(
                SessionListener.TOPIC,
                changed -> apply(changed.programsRead(), status, model, list)
        );

        var panel = new JPanel(new BorderLayout());
        panel.add(status, BorderLayout.NORTH);
        panel.add(new JBScrollPane(list), BorderLayout.CENTER);

        ToolWindowSupport.install(
                toolWindow,
                panel,
                "GhidraExProjects",
                OpenSyntheticProgramAction.ID,
                RunAnalysisAction.ID
        );
    }

    private static void apply(
            ContextualReadSlot.Snapshot<ReadModels.ProgramQuery, ReadModels.ProgramCatalog> snapshot,
            JBLabel status,
            DefaultListModel<ProgramDescriptor> model,
            JBList<ProgramDescriptor> list) {
        status.setText(snapshot.freshness() + " · generation "
                + snapshot.context().contentGeneration() + " · " + snapshot.detail());
        if (snapshot.displayed() == null) {
            if (snapshot.freshness() == ContextualReadSlot.Freshness.EMPTY
                    || snapshot.freshness() == ContextualReadSlot.Freshness.FAILED
                    || snapshot.freshness() == ContextualReadSlot.Freshness.RESYNCING) {
                model.clear();
            }
            return;
        }
        model.clear();
        for (ProgramDescriptor program : snapshot.displayed().value().programs()) {
            model.addElement(program);
        }
        if (!model.isEmpty() && list.getSelectedIndex() < 0) {
            list.setSelectedIndex(0);
        }
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
