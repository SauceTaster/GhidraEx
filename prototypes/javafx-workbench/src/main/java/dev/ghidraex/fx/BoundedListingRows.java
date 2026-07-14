package dev.ghidraex.fx;

import dev.ghidraex.viewstate.ViewStateReducer.ListingRow;
import javafx.collections.ObservableListBase;

import java.util.List;
import java.util.Objects;

/** A bounded local-only list. Its control getters have no provider or transport reference. */
final class BoundedListingRows extends ObservableListBase<ListingRow> {
    private static final int MAX_ROWS = 4_096;

    private List<ListingRow> rows = List.of();

    @Override
    public ListingRow get(int index) {
        return rows.get(index);
    }

    @Override
    public int size() {
        return rows.size();
    }

    void replaceWith(List<ListingRow> nextRows) {
        List<ListingRow> replacement = List.copyOf(Objects.requireNonNull(nextRows, "nextRows"));
        if (replacement.size() > MAX_ROWS) {
            throw new IllegalArgumentException("A JavaFX listing projection may contain at most 4096 rows");
        }
        List<ListingRow> removed = rows;
        beginChange();
        rows = replacement;
        if (!removed.isEmpty() || !replacement.isEmpty()) {
            nextReplace(0, replacement.size(), removed);
        }
        endChange();
    }
}
