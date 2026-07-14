package dev.ghidraex.intellij.editor;

import dev.ghidraex.viewstate.ViewStateReducer;

import javax.swing.table.AbstractTableModel;
import java.util.List;
import java.util.Locale;

/** EDT-owned bounded projection. No transport or engine reference is reachable from a getter. */
final class SemanticListingTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {"Kind", "Address", "Bytes", "Mnemonic", "Operands"};

    private List<ViewStateReducer.ListingRow> rows = List.of();

    void replace(ViewStateReducer.ViewSnapshot snapshot) {
        rows = snapshot.displayed() == null ? List.of() : snapshot.displayed().rows();
        fireTableDataChanged();
    }

    ViewStateReducer.ListingRow rowAt(int row) {
        return rows.get(row);
    }

    int indexOf(ViewStateReducer.LocationRef target) {
        for (int index = 0; index < rows.size(); index++) {
            ViewStateReducer.LocationRef row = rows.get(index).location();
            if (row.requestedAddress().equals(target.requestedAddress())
                    || row.containingAddress().equals(target.containingAddress())) {
                return index;
            }
        }
        return -1;
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
        ViewStateReducer.ListingRow row = rows.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> row.kind().name().toLowerCase(Locale.ROOT);
            case 1 -> row.fields().getOrDefault("address", row.location().requestedAddress().display());
            case 2 -> row.fields().getOrDefault("bytes", "");
            case 3 -> row.fields().getOrDefault("mnemonic", row.kind().name().toLowerCase(Locale.ROOT));
            case 4 -> row.fields().getOrDefault("operands", "");
            default -> throw new IndexOutOfBoundsException(columnIndex);
        };
    }
}
