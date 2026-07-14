package dev.ghidraex.fx;

import dev.ghidraex.viewstate.ContextualReadSlot.Freshness;
import dev.ghidraex.viewstate.ContextualReadSlot.Snapshot;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;

/** Keeps native state labels and accessibility text consistent across contextual reads. */
final class ReadStatePresentation {
    private ReadStatePresentation() {
    }

    static void apply(Label state, Control content, Snapshot<?, ?> snapshot, String contentName) {
        Freshness freshness = snapshot.freshness();
        state.setText("● " + freshness.name());
        state.setTooltip(snapshot.detail().isBlank() ? null : new Tooltip(snapshot.detail()));
        state.getStyleClass().removeAll("safe-chip", "warning-chip", "error-chip");
        switch (freshness) {
            case CURRENT -> state.getStyleClass().add("safe-chip");
            case STALE, PARTIAL, RESYNCING -> state.getStyleClass().add("warning-chip");
            case FAILED -> state.getStyleClass().add("error-chip");
            default -> { }
        }
        content.setAccessibleText(contentName + ", " + freshness.name().toLowerCase()
                + (snapshot.detail().isBlank() ? "" : ", " + snapshot.detail()));
    }
}
