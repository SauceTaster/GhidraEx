package dev.ghidraex.intellij.editor;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.fileEditor.FileEditorStateLevel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import dev.ghidraex.engine.AnalysisEngine;
import dev.ghidraex.engine.ListingInstruction;
import dev.ghidraex.intellij.session.WorkbenchSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A center-editor listing with 100,000 logical rows and bounded page materialization.
 *
 * <p>{@link JTable} virtualizes painting, while {@link ListingTableModel} keeps at most eight
 * 512-row immutable engine pages. Opening the editor therefore does not build a giant text
 * document or one Swing component per instruction.</p>
 */
public final class VirtualizedListingEditor extends UserDataHolderBase implements FileEditor {
    private final VirtualizedListingFile file;
    private final WorkbenchSession session;
    private final JBTable table;
    private final JPanel component;
    private final PropertyChangeSupport propertyChanges = new PropertyChangeSupport(this);

    public VirtualizedListingEditor(Project project, VirtualizedListingFile file) {
        this.file = file;
        this.session = WorkbenchSession.getInstance(project);
        var model = new ListingTableModel(
                session.engine(),
                session.program().id(),
                session.engine().instructionCount(session.program().id())
        );
        this.table = new JBTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowGrid(false);
        table.setStriped(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getTableHeader().setReorderingAllowed(false);
        table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, table.getFont().getSize()));
        table.setRowHeight(JBUI.scale(22));
        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(64));
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(100));
        table.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(135));
        table.getColumnModel().getColumn(3).setPreferredWidth(JBUI.scale(85));
        table.getColumnModel().getColumn(4).setPreferredWidth(JBUI.scale(520));

        table.getSelectionModel().addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || table.getSelectedRow() < 0) {
                return;
            }
            long address = model.instructionAt(table.getSelectedRow()).address();
            session.symbols().stream()
                    .filter(symbol -> symbol.address() == address)
                    .findFirst()
                    .ifPresent(session::selectSymbol);
        });

        var header = new JBLabel("100,000 instructions · lazy 512-row pages · bounded 8-page cache");
        header.setBorder(JBUI.Borders.empty(7, 10));
        this.component = new JPanel(new BorderLayout());
        component.add(header, BorderLayout.NORTH);
        component.add(new JBScrollPane(table), BorderLayout.CENTER);
        table.setRowSelectionInterval(0, 0);
    }

    public void revealAddress(long address) {
        long candidate = (address - session.program().imageBase()) / 4L;
        int row = (int) Math.max(0, Math.min(table.getRowCount() - 1L, candidate));
        table.setRowSelectionInterval(row, row);
        table.scrollRectToVisible(table.getCellRect(row, 0, true));
        table.requestFocusInWindow();
    }

    @Override
    public @NotNull JComponent getComponent() {
        return component;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return table;
    }

    @Override
    public @NotNull String getName() {
        return "Virtualized Listing";
    }

    @Override
    public @NotNull FileEditorState getState(@NotNull FileEditorStateLevel level) {
        return new State(Math.max(0, table.getSelectedRow()));
    }

    @Override
    public void setState(@NotNull FileEditorState state) {
        if (state instanceof State listingState) {
            int row = Math.max(0, Math.min(table.getRowCount() - 1, listingState.selectedRow()));
            table.setRowSelectionInterval(row, row);
            table.scrollRectToVisible(table.getCellRect(row, 0, true));
        }
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public boolean isValid() {
        return file.isValid();
    }

    @Override
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
        propertyChanges.addPropertyChangeListener(listener);
    }

    @Override
    public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
        propertyChanges.removePropertyChangeListener(listener);
    }

    @Override
    public @NotNull VirtualFile getFile() {
        return file;
    }

    @Override
    public void dispose() {
    }

    public record State(int selectedRow) implements FileEditorState {
        @Override
        public boolean canBeMergedWith(
                @NotNull FileEditorState otherState,
                @NotNull FileEditorStateLevel level
        ) {
            return otherState instanceof State;
        }
    }

    static final class ListingTableModel extends AbstractTableModel {
        private static final int PAGE_SIZE = 512;
        private static final int MAX_CACHED_PAGES = 8;
        private static final String[] COLUMNS = {"#", "Address", "Bytes", "Mnemonic", "Operands"};

        private final AnalysisEngine engine;
        private final String programId;
        private final int rowCount;
        private final Map<Integer, List<ListingInstruction>> pageCache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, List<ListingInstruction>> eldest) {
                return size() > MAX_CACHED_PAGES;
            }
        };

        private ListingTableModel(AnalysisEngine engine, String programId, int rowCount) {
            this.engine = engine;
            this.programId = programId;
            this.rowCount = rowCount;
        }

        ListingInstruction instructionAt(int row) {
            int pageStart = (row / PAGE_SIZE) * PAGE_SIZE;
            var page = pageCache.computeIfAbsent(
                    pageStart,
                    start -> engine.listing(programId, start, PAGE_SIZE)
            );
            return page.get(row - pageStart);
        }

        @Override
        public int getRowCount() {
            return rowCount;
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
            return columnIndex == 0 ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            var instruction = instructionAt(rowIndex);
            return switch (columnIndex) {
                case 0 -> instruction.ordinal();
                case 1 -> String.format(Locale.ROOT, "%08x", instruction.address());
                case 2 -> instruction.bytes();
                case 3 -> instruction.mnemonic();
                case 4 -> instruction.label().isEmpty()
                        ? instruction.operands()
                        : instruction.operands() + "    ; " + instruction.label();
                default -> throw new IndexOutOfBoundsException(columnIndex);
            };
        }
    }
}
