package dev.ghidraex.fx;

import dev.ghidraex.engine.DisassemblyLine;
import dev.ghidraex.engine.ProgramInfo;
import dev.ghidraex.engine.Symbol;
import dev.ghidraex.viewstate.ContextualReadSlot.Snapshot;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

final class InspectorPane extends VBox implements AutoCloseable {
    private final Label addressValue = valueLabel("—");
    private final Label instructionValue = valueLabel("—");
    private final Label flowValue = valueLabel("—");
    private final Label bytesValue = valueLabel("—");
    private final Label symbolName = new Label("entry");
    private final Label symbolMeta = new Label("FUNCTION · telemetryd");
    private final Label xrefState = new Label("● EMPTY");
    private final Label xrefCount = new Label("Select an instruction");
    private final Label xrefPlaceholder = new Label("Select an instruction");
    private final ListView<AsyncEngineReads.ReferenceEntry> xrefs = new ListView<>();
    private final AsyncEngineReads.ReadHandle<AsyncEngineReads.ReferenceQuery, AsyncEngineReads.ReferenceSet>
            referenceReads;
    private String displayedReferenceResultId = "";

    InspectorPane(ProgramInfo program, AsyncEngineReads reads) {
        referenceReads = java.util.Objects.requireNonNull(reads, "reads").openReferences(this::applyReferences);
        getStyleClass().add("inspector-pane");
        setMinWidth(210);
        setPrefWidth(310);
        getChildren().add(buildHeader());

        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("inspector-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(buildDetailsTab(program), buildXrefsTab());
        VBox.setVgrow(tabs, Priority.ALWAYS);
        getChildren().add(tabs);
    }

    void showLine(DisassemblyLine line) {
        addressValue.setText(line.formattedAddress());
        instructionValue.setText((line.mnemonic() + " " + line.operands()).strip());
        flowValue.setText(switch (line.flowType()) {
            case NORMAL -> "Fall-through";
            case CONDITIONAL -> "Conditional branch";
            case CALL -> "Call + fall-through";
            case RETURN -> "Function return";
        });
        bytesValue.setText(line.bytes());
        referenceReads.submit(AsyncEngineReads.ReferenceQuery.at(line.address()));
    }

    void showSymbol(Symbol symbol) {
        symbolName.setText(symbol.name());
        symbolMeta.setText(symbol.kind() + " · " + symbol.namespace());
    }

    private HBox buildHeader() {
        Label icon = new Label("◎");
        icon.getStyleClass().add("inspector-icon");
        symbolName.getStyleClass().add("inspector-title");
        symbolMeta.getStyleClass().add("eyebrow");
        VBox labels = new VBox(2, symbolName, symbolMeta);
        HBox header = new HBox(10, icon, labels);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("inspector-header");
        return header;
    }

    private Tab buildDetailsTab(ProgramInfo program) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("property-grid");
        grid.setHgap(10);
        grid.setVgap(10);
        addRow(grid, 0, "ADDRESS", addressValue);
        addRow(grid, 1, "INSTRUCTION", instructionValue);
        addRow(grid, 2, "FLOW", flowValue);
        addRow(grid, 3, "BYTES", bytesValue);

        Label programHeading = new Label("PROGRAM");
        programHeading.getStyleClass().add("section-heading");
        GridPane programGrid = new GridPane();
        programGrid.getStyleClass().add("property-grid");
        programGrid.setHgap(10);
        programGrid.setVgap(10);
        addRow(programGrid, 0, "FORMAT", valueLabel(program.format()));
        addRow(programGrid, 1, "LANGUAGE", valueLabel(program.architecture()));
        addRow(programGrid, 2, "IMAGE BASE", valueLabel("0x%016x".formatted(program.imageBase())));
        addRow(programGrid, 3, "SHA-256", valueLabel(program.sha256().substring(0, 16) + "…"));

        VBox content = new VBox(18, grid, programHeading, programGrid);
        content.getStyleClass().add("inspector-content");
        return new Tab("DETAILS", content);
    }

    private Tab buildXrefsTab() {
        xrefs.getStyleClass().add("xref-list");
        xrefs.setCellFactory(ignored -> new ReferenceCell());
        xrefs.setPlaceholder(xrefPlaceholder);
        xrefState.getStyleClass().addAll("state-chip", "query-state");
        xrefCount.getStyleClass().add("muted-label");
        HBox status = new HBox(7, xrefCount, xrefState);
        status.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(xrefCount, Priority.ALWAYS);
        status.getStyleClass().add("xref-status");
        VBox content = new VBox(status, xrefs);
        content.getStyleClass().add("xref-pane");
        VBox.setVgrow(xrefs, Priority.ALWAYS);
        return new Tab("XREFS", content);
    }

    private void applyReferences(
            Snapshot<AsyncEngineReads.ReferenceQuery, AsyncEngineReads.ReferenceSet> snapshot) {
        ReadStatePresentation.apply(xrefState, xrefs, snapshot, "Cross references");
        xrefPlaceholder.setText(switch (snapshot.freshness()) {
            case LOADING -> "Loading references…";
            case FAILED -> "Reference request failed";
            case RESYNCING -> "Waiting for a validated program snapshot";
            default -> "No references";
        });
        var displayed = snapshot.displayed();
        if (displayed == null) {
            if (!displayedReferenceResultId.isEmpty()) {
                displayedReferenceResultId = "";
                xrefs.getItems().clear();
            }
            xrefCount.setText(snapshot.pending() == null ? "Select an instruction" : "Loading references");
            return;
        }
        int size = displayed.value().references().size();
        xrefCount.setText(size + (size == 1 ? " reference" : " references"));
        if (!displayed.resultId().equals(displayedReferenceResultId)) {
            displayedReferenceResultId = displayed.resultId();
            xrefs.getItems().setAll(displayed.value().references());
        }
    }

    @Override
    public void close() {
        referenceReads.close();
    }

    private static final class ReferenceCell extends ListCell<AsyncEngineReads.ReferenceEntry> {
        @Override
        protected void updateItem(AsyncEngineReads.ReferenceEntry reference, boolean empty) {
            super.updateItem(reference, empty);
            if (empty || reference == null) {
                setText(null);
                return;
            }
            setText("%-4s  0x%016x  ·  %s".formatted(
                    reference.direction(), reference.address(), reference.kind()));
        }
    }

    private static void addRow(GridPane grid, int row, String key, Label value) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("property-key");
        value.setWrapText(true);
        GridPane.setHgrow(value, Priority.ALWAYS);
        grid.add(keyLabel, 0, row);
        grid.add(value, 1, row);
    }

    private static Label valueLabel(String value) {
        Label label = new Label(value);
        label.getStyleClass().add("property-value");
        return label;
    }
}
