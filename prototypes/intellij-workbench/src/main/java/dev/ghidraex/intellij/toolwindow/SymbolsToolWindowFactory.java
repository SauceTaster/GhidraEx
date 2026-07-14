package dev.ghidraex.intellij.toolwindow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.ui.SearchTextField;
import com.intellij.util.ui.JBUI;
import dev.ghidraex.engine.SymbolDescriptor;
import dev.ghidraex.intellij.action.GoToSelectedSymbolAction;
import dev.ghidraex.intellij.action.RunAnalysisAction;
import dev.ghidraex.intellij.session.SessionListener;
import dev.ghidraex.intellij.session.WorkbenchSession;
import dev.ghidraex.intellij.read.ReadModels;
import dev.ghidraex.viewstate.ContextualReadSlot;
import org.jetbrains.annotations.NotNull;

import javax.swing.ListSelectionModel;
import javax.swing.JPanel;
import javax.swing.table.AbstractTableModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;

public final class SymbolsToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        var session = WorkbenchSession.getInstance(project);
        var model = new SymbolTableModel(List.of());
        var table = new JBTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setStriped(true);
        table.setShowGrid(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(170);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);

        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && table.getSelectedRow() >= 0) {
                int modelRow = table.convertRowIndexToModel(table.getSelectedRow());
                session.selectSymbol(model.symbolAt(modelRow));
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    GoToSelectedSymbolAction.navigate(project);
                }
            }
        });

        var status = new JBLabel();
        status.setBorder(JBUI.Borders.empty(5, 6, 0, 6));
        project.getMessageBus().connect(project).subscribe(SessionListener.TOPIC, changed -> {
            apply(changed.symbolsRead(), model, table, status);
        });

        var search = new SearchTextField(false);
        search.getTextEditor().getEmptyText().setText("Search name, address, kind, or signature");
        search.getTextEditor().getDocument().addDocumentListener(new DocumentListener() {
            private void updateFilter() {
                session.requestSymbols(search.getText());
            }

            @Override
            public void insertUpdate(DocumentEvent event) {
                updateFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                updateFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                updateFilter();
            }
        });

        var content = new JPanel(new BorderLayout());
        var searchContainer = new JPanel(new BorderLayout());
        searchContainer.setBorder(JBUI.Borders.empty(6));
        searchContainer.add(search, BorderLayout.CENTER);
        searchContainer.add(status, BorderLayout.SOUTH);
        content.add(searchContainer, BorderLayout.NORTH);
        content.add(new JBScrollPane(table), BorderLayout.CENTER);
        apply(session.symbolsRead(), model, table, status);

        ToolWindowSupport.install(
                toolWindow,
                content,
                "GhidraExSymbols",
                GoToSelectedSymbolAction.ID,
                RunAnalysisAction.ID
        );
    }

    private static void apply(
            ContextualReadSlot.Snapshot<ReadModels.SymbolQuery, ReadModels.SymbolCatalog> snapshot,
            SymbolTableModel model,
            JBTable table,
            JBLabel status) {
        status.setText(snapshot.freshness() + " · generation "
                + snapshot.context().contentGeneration() + " · " + snapshot.detail());
        if (snapshot.displayed() == null) {
            if (snapshot.freshness() == ContextualReadSlot.Freshness.EMPTY
                    || snapshot.freshness() == ContextualReadSlot.Freshness.FAILED
                    || snapshot.freshness() == ContextualReadSlot.Freshness.RESYNCING) {
                model.replace(List.of());
            }
            return;
        }
        model.replace(snapshot.displayed().value().symbols());
        if (model.getRowCount() > 0 && table.getSelectedRow() < 0) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    private static final class SymbolTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Name", "Address", "Kind", "Refs"};
        private List<SymbolDescriptor> symbols;

        private SymbolTableModel(List<SymbolDescriptor> symbols) {
            this.symbols = List.copyOf(symbols);
        }

        SymbolDescriptor symbolAt(int row) {
            return symbols.get(row);
        }

        void replace(List<SymbolDescriptor> replacement) {
            List<SymbolDescriptor> safe = List.copyOf(replacement);
            if (symbols.equals(safe)) {
                return;
            }
            symbols = safe;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return symbols.size();
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
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 3 ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            var symbol = symbols.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> symbol.name();
                case 1 -> String.format(Locale.ROOT, "%08x", symbol.address());
                case 2 -> symbol.kind().name().toLowerCase(Locale.ROOT);
                case 3 -> symbol.referenceCount();
                default -> throw new IndexOutOfBoundsException(columnIndex);
            };
        }
    }
}
