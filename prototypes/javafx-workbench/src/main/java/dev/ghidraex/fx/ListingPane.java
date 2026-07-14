package dev.ghidraex.fx;

import dev.ghidraex.engine.AnalysisEngine;
import dev.ghidraex.engine.DisassemblyLine;
import dev.ghidraex.viewstate.ViewStateReducer.Freshness;
import dev.ghidraex.viewstate.ViewStateReducer.ListingRow;
import dev.ghidraex.viewstate.ViewStateReducer.LocationRef;
import dev.ghidraex.viewstate.ViewStateReducer.RowKind;
import dev.ghidraex.viewstate.ViewStateReducer.ViewSnapshot;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** A bounded JavaFX projection of one immutable semantic listing window. */
final class ListingPane extends BorderPane implements AutoCloseable {
    private static final int WINDOW_LIMIT = 256;

    private final BoundedListingRows rows = new BoundedListingRows();
    private final ListView<ListingRow> listing = new ListView<>(rows);
    private final Label locationSymbol = new Label("location");
    private final Label locationAddress = new Label("—");
    private final Label count = new Label("Bounded window");
    private final Label freshness = new Label("EMPTY");
    private final SyntheticSemanticListingProvider provider;
    private final AsyncListingProjection projection;
    private Consumer<DisassemblyLine> selectionListener = ignored -> { };
    private String displayedResultId = "";

    ListingPane(AnalysisEngine engine, EngineSessionSnapshot session) {
        provider = new SyntheticSemanticListingProvider(engine, session);

        getStyleClass().add("listing-pane");
        setTop(buildToolbar());

        listing.getStyleClass().add("disassembly-list");
        listing.setFixedCellSize(27);
        listing.setCellFactory(ignored -> new DisassemblyCell());
        projection = AsyncListingProjection.createFx(
                provider.initialContext(), provider, this::applySnapshot);
        listing.getSelectionModel().selectedItemProperty().addListener((observable, oldRow, row) -> {
            if (row != null) {
                projection.setSelection(row.location());
                selectionListener.accept(toDisassemblyLine(row));
            }
        });
        setCenter(listing);

        goToAddress(session.program().imageBase());
    }

    void setOnLineSelected(Consumer<DisassemblyLine> listener) {
        selectionListener = listener == null ? ignored -> { } : listener;
    }

    void goToAddress(long address) {
        LocationRef target = provider.locationFor(address);
        projection.request(target, WINDOW_LIMIT);
        listing.requestFocus();
    }

    void contentGenerationAdvanced() {
        var state = projection.snapshot();
        long nextSequence = state.eventSequence() + 1;
        long nextGeneration = state.context().contentGeneration() + 1;
        projection.acceptContentEvent(nextSequence, nextGeneration);
        LocationRef target = state.location();
        if (target != null) {
            projection.request(target, WINDOW_LIMIT);
        }
    }

    void selectInitialLine() {
        if (listing.getSelectionModel().isEmpty() && !rows.isEmpty()) {
            selectTarget(projection.viewSnapshot().target());
        }
    }

    private void applySnapshot(ViewSnapshot snapshot) {
        updateFreshness(snapshot.freshness(), snapshot.detail());
        LocationRef target = snapshot.target();
        if (target != null) {
            locationSymbol.setText(target.offcut() ? "offcut +0x" + Integer.toHexString(target.byteOffset()) : "location");
            locationAddress.setText(target.requestedAddress().display());
        }

        var displayed = snapshot.displayed();
        if (displayed == null) {
            if (!displayedResultId.isEmpty()) {
                displayedResultId = "";
                rows.replaceWith(List.of());
                listing.getSelectionModel().clearSelection();
            }
            count.setText(snapshot.freshness() == Freshness.LOADING
                    ? "Loading ≤ " + WINDOW_LIMIT + " semantic rows"
                    : "No listing result");
            return;
        }

        count.setText(displayed.rows().size() + " semantic rows · bounded window");
        if (!displayed.resultId().equals(displayedResultId)) {
            displayedResultId = displayed.resultId();
            rows.replaceWith(displayed.rows());
            selectTarget(target);
        }
    }

    private void selectTarget(LocationRef target) {
        if (target == null || rows.isEmpty()) {
            return;
        }
        int selected = -1;
        for (int index = 0; index < rows.size(); index++) {
            LocationRef candidate = rows.get(index).location();
            if (candidate.requestedAddress().equals(target.requestedAddress())) {
                selected = index;
                break;
            }
            if (selected < 0 && candidate.containingAddress().equals(target.containingAddress())) {
                selected = index;
            }
        }
        if (selected < 0) {
            selected = Math.min(7, rows.size() - 1);
        }
        listing.getSelectionModel().select(selected);
        listing.scrollTo(Math.max(0, selected - 7));
    }

    private void updateFreshness(Freshness state, String detail) {
        freshness.setText("● " + state.name());
        freshness.setTooltip(detail == null || detail.isBlank() ? null : new Tooltip(detail));
        freshness.getStyleClass().removeAll("safe-chip", "warning-chip", "error-chip");
        switch (state) {
            case CURRENT -> freshness.getStyleClass().add("safe-chip");
            case STALE, PARTIAL, RESYNCING -> freshness.getStyleClass().add("warning-chip");
            case FAILED -> freshness.getStyleClass().add("error-chip");
            default -> { }
        }
        listing.setAccessibleText("Semantic listing, " + state.name().toLowerCase()
                + (detail == null || detail.isBlank() ? "" : ", " + detail));
    }

    /** Pure local projection used by selection and cells; it has no engine/provider reference. */
    private static DisassemblyLine toDisassemblyLine(ListingRow row) {
        Map<String, String> fields = row.fields();
        int index = Integer.parseInt(fields.getOrDefault("index", "0"));
        long address = Long.parseUnsignedLong(row.location().containingAddress().offsetBits(), 16);
        DisassemblyLine.FlowType flow = parseFlow(fields.get("flow"));
        return new DisassemblyLine(
                index,
                address,
                fields.getOrDefault("bytes", ""),
                fields.getOrDefault("mnemonic", row.kind().name().toLowerCase()),
                fields.getOrDefault("operands", ""),
                fields.getOrDefault("comment", ""),
                flow);
    }

    private static DisassemblyLine.FlowType parseFlow(String value) {
        if (value == null) {
            return DisassemblyLine.FlowType.NORMAL;
        }
        try {
            return DisassemblyLine.FlowType.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return DisassemblyLine.FlowType.NORMAL;
        }
    }

    private HBox buildToolbar() {
        locationSymbol.getStyleClass().add("location-symbol");
        locationAddress.getStyleClass().add("location-address");

        Button back = compactButton("‹", "Navigate back");
        Button forward = compactButton("›", "Navigate forward");
        Separator separator = new Separator();
        separator.setOrientation(javafx.geometry.Orientation.VERTICAL);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        count.getStyleClass().add("muted-label");
        freshness.getStyleClass().addAll("state-chip", "listing-freshness");

        HBox bar = new HBox(8, back, forward, separator, locationSymbol, locationAddress,
                spacer, count, freshness);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("pane-toolbar");
        return bar;
    }

    @Override
    public void close() {
        projection.close();
    }

    private static Button compactButton(String text, String accessibleText) {
        Button button = new Button(text);
        button.getStyleClass().addAll("icon-button", "navigation-button");
        button.setAccessibleText(accessibleText);
        return button;
    }

    private static final class DisassemblyCell extends ListCell<ListingRow> {
        private static final PseudoClass FLOW = PseudoClass.getPseudoClass("flow");
        private static final PseudoClass CALL = PseudoClass.getPseudoClass("call");
        private static final PseudoClass DATA = PseudoClass.getPseudoClass("data");
        private static final PseudoClass GAP = PseudoClass.getPseudoClass("gap");
        private final Label marker = new Label();
        private final Label address = new Label();
        private final Label bytes = new Label();
        private final Label mnemonic = new Label();
        private final Label operands = new Label();
        private final Label comment = new Label();
        private final HBox row = new HBox();

        private DisassemblyCell() {
            getStyleClass().add("disassembly-cell");
            marker.getStyleClass().add("flow-marker");
            marker.setMinWidth(14);
            marker.setPrefWidth(14);
            address.getStyleClass().add("asm-address");
            address.setMinWidth(136);
            address.setPrefWidth(136);
            bytes.getStyleClass().add("asm-bytes");
            bytes.setMinWidth(144);
            bytes.setPrefWidth(144);
            mnemonic.getStyleClass().add("asm-mnemonic");
            mnemonic.setMinWidth(72);
            mnemonic.setPrefWidth(72);
            operands.getStyleClass().add("asm-operands");
            HBox.setHgrow(operands, Priority.ALWAYS);
            comment.getStyleClass().add("asm-comment");
            comment.setMinWidth(170);
            comment.setPrefWidth(210);
            row.getChildren().addAll(marker, address, bytes, mnemonic, operands, comment);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setMaxWidth(Double.MAX_VALUE);
        }

        @Override
        protected void updateItem(ListingRow listingRow, boolean empty) {
            super.updateItem(listingRow, empty);
            resetPseudoClasses();
            if (empty || listingRow == null) {
                setGraphic(null);
                setText(null);
                setAccessibleText(null);
                return;
            }

            Map<String, String> fields = listingRow.fields();
            DisassemblyLine line = toDisassemblyLine(listingRow);
            boolean data = listingRow.kind() == RowKind.DATA;
            boolean gap = listingRow.kind() == RowKind.GAP;
            marker.setText(switch (listingRow.kind()) {
                case DATA -> "◆";
                case GAP -> "⋯";
                case INSTRUCTION -> switch (line.flowType()) {
                    case CALL -> "◆";
                    case CONDITIONAL -> "└";
                    case RETURN -> "↵";
                    default -> "";
                };
            });
            address.setText(fields.getOrDefault("address", listingRow.location().requestedAddress().display()));
            bytes.setText(fields.getOrDefault("bytes", ""));
            mnemonic.setText(fields.getOrDefault("mnemonic", gap ? "<gap>" : data ? "data" : ""));
            operands.setText(fields.getOrDefault("operands", fields.getOrDefault("range", "")));
            comment.setText(fields.getOrDefault("comment", fields.getOrDefault("summary", "")));

            pseudoClassStateChanged(FLOW, line.flowType() == DisassemblyLine.FlowType.CONDITIONAL);
            pseudoClassStateChanged(CALL, line.flowType() == DisassemblyLine.FlowType.CALL);
            pseudoClassStateChanged(DATA, data);
            pseudoClassStateChanged(GAP, gap);
            setAccessibleText(listingRow.kind().name().toLowerCase() + " "
                    + address.getText() + " " + mnemonic.getText() + " " + operands.getText());
            setText(null);
            setGraphic(row);
        }

        private void resetPseudoClasses() {
            pseudoClassStateChanged(FLOW, false);
            pseudoClassStateChanged(CALL, false);
            pseudoClassStateChanged(DATA, false);
            pseudoClassStateChanged(GAP, false);
        }
    }
}
