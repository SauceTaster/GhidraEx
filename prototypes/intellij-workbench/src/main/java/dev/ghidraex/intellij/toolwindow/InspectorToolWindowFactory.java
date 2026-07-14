package dev.ghidraex.intellij.toolwindow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import dev.ghidraex.intellij.action.GoToSelectedSymbolAction;
import dev.ghidraex.intellij.session.SessionListener;
import dev.ghidraex.intellij.session.WorkbenchSession;
import dev.ghidraex.intellij.read.ReadModels;
import dev.ghidraex.viewstate.ContextualReadSlot;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

public final class InspectorToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        var session = WorkbenchSession.getInstance(project);
        var details = new JBTextArea();
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setFont(JBFont.create(JBFont.label().deriveFont(13f)));
        details.setBorder(JBUI.Borders.empty(12));
        var status = new JBLabel();
        status.setBorder(JBUI.Borders.empty(7, 10));
        var referenceStatus = new JBLabel();
        referenceStatus.setBorder(JBUI.Borders.empty(5, 8));
        var referenceModel = new ReferenceTableModel();
        var referenceTable = new JBTable(referenceModel);
        referenceTable.setShowGrid(false);
        referenceTable.setStriped(true);
        referenceTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        referenceTable.getColumnModel().getColumn(0).setPreferredWidth(130);
        referenceTable.getColumnModel().getColumn(1).setPreferredWidth(130);
        referenceTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = referenceTable.getSelectedRow();
                if (event.getClickCount() == 2 && row >= 0) {
                    GoToSelectedSymbolAction.navigate(project, referenceModel.rowAt(row).fromAddress());
                }
            }
        });
        update(details, status, session.inspectorRead(),
                referenceStatus, referenceModel, session.referencesRead());

        project.getMessageBus().connect(project).subscribe(
                SessionListener.TOPIC,
                changed -> update(details, status, changed.inspectorRead(),
                        referenceStatus, referenceModel, changed.referencesRead())
        );

        var referencePanel = new JPanel(new BorderLayout());
        referencePanel.add(referenceStatus, BorderLayout.NORTH);
        referencePanel.add(new JBScrollPane(referenceTable), BorderLayout.CENTER);
        var split = new JBSplitter(true, 0.58f);
        split.setFirstComponent(new JBScrollPane(details));
        split.setSecondComponent(referencePanel);
        var panel = new JPanel(new BorderLayout());
        panel.add(status, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);

        ToolWindowSupport.install(
                toolWindow,
                panel,
                "GhidraExInspector",
                GoToSelectedSymbolAction.ID
        );
    }

    private static void update(
            JBTextArea details,
            JBLabel status,
            ContextualReadSlot.Snapshot<ReadModels.InspectorQuery, ReadModels.InspectorDetails> snapshot,
            JBLabel referenceStatus,
            ReferenceTableModel referenceModel,
            ContextualReadSlot.Snapshot<ReadModels.ReferenceQuery, ReadModels.ReferenceSet> references) {
        status.setText(snapshot.freshness() + " · generation "
                + snapshot.context().contentGeneration() + " · " + snapshot.detail());
        if (snapshot.displayed() == null) {
            details.setText(snapshot.detail());
        } else {
            ReadModels.InspectorDetails value = snapshot.displayed().value();
            var symbol = value.symbol();
            details.setText("""
                    SYMBOL
                    %s

                    ADDRESS
                    %08x

                    KIND
                    %s

                    SIGNATURE
                    %s

                    REFERENCES
                    %d incoming

                    PROGRAM
                    %s

                    ANALYSIS
                    %s
                    """.formatted(
                    symbol.name(),
                    symbol.address(),
                    symbol.kind().name().toLowerCase(Locale.ROOT),
                    symbol.signature(),
                    symbol.referenceCount(),
                    value.programName(),
                    value.analysisSummary()
            ));
            details.setCaretPosition(0);
        }

        referenceStatus.setText("XREFS · " + references.freshness() + " · " + references.detail());
        if (references.displayed() == null) {
            if (references.freshness() == ContextualReadSlot.Freshness.EMPTY
                    || references.freshness() == ContextualReadSlot.Freshness.FAILED
                    || references.freshness() == ContextualReadSlot.Freshness.RESYNCING) {
                referenceModel.replace(List.of());
            }
        } else {
            referenceModel.replace(references.displayed().value().references());
        }
    }

    private static final class ReferenceTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"From", "To", "Kind"};
        private List<ReadModels.ReferenceRow> rows = List.of();

        void replace(List<ReadModels.ReferenceRow> replacement) {
            List<ReadModels.ReferenceRow> safe = List.copyOf(replacement);
            if (rows.equals(safe)) {
                return;
            }
            rows = safe;
            fireTableDataChanged();
        }

        ReadModels.ReferenceRow rowAt(int row) {
            return rows.get(row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ReadModels.ReferenceRow row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> String.format(Locale.ROOT, "%016x", row.fromAddress());
                case 1 -> String.format(Locale.ROOT, "%016x", row.toAddress());
                case 2 -> row.kind();
                default -> throw new IndexOutOfBoundsException(columnIndex);
            };
        }
    }
}
