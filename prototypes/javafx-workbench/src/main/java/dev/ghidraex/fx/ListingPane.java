package dev.ghidraex.fx;

import dev.ghidraex.engine.AnalysisEngine;
import dev.ghidraex.engine.DisassemblyLine;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.util.function.Consumer;

final class ListingPane extends BorderPane {
    private final AnalysisEngine engine;
    private final ListView<DisassemblyLine> listing = new ListView<>();
    private Consumer<DisassemblyLine> selectionListener = ignored -> { };

    ListingPane(AnalysisEngine engine) {
        this.engine = engine;
        getStyleClass().add("listing-pane");
        setTop(buildToolbar());

        listing.getStyleClass().add("disassembly-list");
        listing.setFixedCellSize(27);
        listing.setItems(new PagedListing(engine));
        listing.setCellFactory(ignored -> new DisassemblyCell());
        listing.getSelectionModel().selectedItemProperty().addListener((observable, oldLine, line) -> {
            if (line != null) {
                selectionListener.accept(line);
            }
        });
        setCenter(listing);
    }

    void setOnLineSelected(Consumer<DisassemblyLine> listener) {
        selectionListener = listener == null ? ignored -> { } : listener;
    }

    void goToAddress(long address) {
        long offset = address - engine.program().imageBase();
        int index = (int) Math.max(0, Math.min(engine.listingSize() - 1, offset / 4));
        listing.getSelectionModel().select(index);
        listing.scrollTo(Math.max(0, index - 7));
        listing.requestFocus();
    }

    void selectInitialLine() {
        if (listing.getSelectionModel().isEmpty()) {
            listing.getSelectionModel().select(7);
        }
    }

    private HBox buildToolbar() {
        Label location = new Label("entry");
        location.getStyleClass().add("location-symbol");
        Label address = new Label("100400000");
        address.getStyleClass().add("location-address");

        Button back = compactButton("‹", "Navigate back");
        Button forward = compactButton("›", "Navigate forward");
        Separator separator = new Separator();
        separator.setOrientation(javafx.geometry.Orientation.VERTICAL);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label count = new Label("100,000 instructions");
        count.getStyleClass().add("muted-label");
        Label live = new Label("● LIVE MODEL");
        live.getStyleClass().add("live-chip");

        HBox bar = new HBox(8, back, forward, separator, location, address, spacer, count, live);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("pane-toolbar");
        return bar;
    }

    private static Button compactButton(String text, String accessibleText) {
        Button button = new Button(text);
        button.getStyleClass().addAll("icon-button", "navigation-button");
        button.setAccessibleText(accessibleText);
        return button;
    }

    private static final class DisassemblyCell extends ListCell<DisassemblyLine> {
        private static final PseudoClass FLOW = PseudoClass.getPseudoClass("flow");
        private static final PseudoClass CALL = PseudoClass.getPseudoClass("call");
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
        protected void updateItem(DisassemblyLine line, boolean empty) {
            super.updateItem(line, empty);
            if (empty || line == null) {
                setGraphic(null);
                setText(null);
                pseudoClassStateChanged(FLOW, false);
                pseudoClassStateChanged(CALL, false);
                return;
            }
            marker.setText(switch (line.flowType()) {
                case CALL -> "◆";
                case CONDITIONAL -> "└";
                case RETURN -> "↵";
                default -> "";
            });
            address.setText(line.formattedAddress());
            bytes.setText(line.bytes());
            mnemonic.setText(line.mnemonic());
            operands.setText(line.operands());
            comment.setText(line.comment());
            boolean flow = line.flowType() == DisassemblyLine.FlowType.CONDITIONAL;
            boolean call = line.flowType() == DisassemblyLine.FlowType.CALL;
            pseudoClassStateChanged(FLOW, flow);
            pseudoClassStateChanged(CALL, call);
            setText(null);
            setGraphic(row);
        }
    }
}
