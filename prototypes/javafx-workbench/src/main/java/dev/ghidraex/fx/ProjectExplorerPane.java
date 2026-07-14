package dev.ghidraex.fx;

import dev.ghidraex.engine.ProgramInfo;
import dev.ghidraex.engine.Symbol;
import dev.ghidraex.viewstate.ContextualReadSlot.Snapshot;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

final class ProjectExplorerPane extends VBox implements AutoCloseable {
    private final ProgramInfo program;
    private final AsyncEngineReads.ReadHandle<AsyncEngineReads.SymbolQuery, AsyncEngineReads.SymbolSearchResult>
            symbolReads;
    private final TextField symbolSearch = new TextField();
    private final ListView<Symbol> symbolResults = new ListView<>();
    private final Label symbolState = new Label("● EMPTY");
    private final Label symbolCount = new Label("No query");
    private final Label symbolPlaceholder = new Label("Loading symbols…");
    private final TabPane tabs = new TabPane();
    private Consumer<Symbol> symbolListener = ignored -> { };
    private String displayedResultId = "";

    ProjectExplorerPane(ProgramInfo program, AsyncEngineReads reads) {
        this.program = java.util.Objects.requireNonNull(program, "program");
        symbolReads = java.util.Objects.requireNonNull(reads, "reads").openSymbolSearch(this::applySymbols);
        getStyleClass().add("project-explorer");
        setMinWidth(190);
        setPrefWidth(292);

        getChildren().add(buildHeader());
        tabs.getStyleClass().add("sidebar-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(buildProjectTab(), buildSymbolsTab());
        VBox.setVgrow(tabs, Priority.ALWAYS);
        getChildren().add(tabs);
    }

    void setOnSymbolActivated(Consumer<Symbol> listener) {
        symbolListener = listener == null ? ignored -> { } : listener;
    }

    void focusSymbolSearch() {
        tabs.getSelectionModel().select(1);
        symbolSearch.requestFocus();
        symbolSearch.selectAll();
    }

    private HBox buildHeader() {
        Label mark = new Label("N");
        mark.getStyleClass().add("project-mark");
        VBox titles = new VBox(1);
        Label title = new Label(program.projectName());
        title.getStyleClass().add("project-title");
        Label subtitle = new Label("LOCAL PROJECT · 1 PROGRAM");
        subtitle.getStyleClass().add("eyebrow");
        titles.getChildren().addAll(title, subtitle);
        HBox header = new HBox(10, mark, titles);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("project-header");
        return header;
    }

    private Tab buildProjectTab() {
        TreeItem<String> root = new TreeItem<>(program.binaryName());
        root.setExpanded(true);
        TreeItem<String> memory = new TreeItem<>("Memory");
        memory.getChildren().setAll(List.of(
                new TreeItem<>("__TEXT       100400000–1004a7fff"),
                new TreeItem<>("__DATA       1004a8000–1004c2fff"),
                new TreeItem<>("__LINKEDIT   1004c3000–1004e5fff")));
        memory.setExpanded(true);
        TreeItem<String> analysis = new TreeItem<>("Analysis");
        analysis.getChildren().setAll(List.of(
                new TreeItem<>("Functions        435"),
                new TreeItem<>("Imports           71"),
                new TreeItem<>("Strings          892"),
                new TreeItem<>("Data types       126")));
        analysis.setExpanded(true);
        root.getChildren().setAll(List.of(memory, analysis));

        TreeView<String> tree = new TreeView<>(root);
        tree.getStyleClass().add("project-tree");
        tree.setShowRoot(true);
        return new Tab("PROJECT", tree);
    }

    private Tab buildSymbolsTab() {
        symbolSearch.setPromptText("Filter names or addresses");
        symbolSearch.getStyleClass().add("sidebar-search");
        symbolSearch.textProperty().addListener((observable, oldValue, value) -> requestSymbols());
        symbolSearch.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DOWN) {
                symbolResults.requestFocus();
                symbolResults.getSelectionModel().selectFirst();
                event.consume();
            } else if (event.getCode() == KeyCode.ENTER) {
                activateSelected();
                event.consume();
            }
        });

        symbolResults.getStyleClass().add("symbol-results");
        symbolResults.setFixedCellSize(44);
        symbolResults.setCellFactory(ignored -> new SymbolCell());
        symbolResults.setPlaceholder(symbolPlaceholder);
        symbolResults.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                activateSelected();
            }
        });
        symbolResults.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                activateSelected();
                event.consume();
            }
        });
        symbolState.getStyleClass().addAll("state-chip", "query-state");
        symbolCount.getStyleClass().add("muted-label");
        HBox readStatus = new HBox(7, symbolCount, symbolState);
        readStatus.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(symbolCount, Priority.ALWAYS);
        requestSymbols();

        Label hint = new Label("↵  OPEN SYMBOL");
        hint.getStyleClass().add("sidebar-hint");
        VBox content = new VBox(8, symbolSearch, readStatus, symbolResults, hint);
        content.getStyleClass().add("symbols-pane");
        VBox.setVgrow(symbolResults, Priority.ALWAYS);
        return new Tab("SYMBOLS", content);
    }

    private void requestSymbols() {
        symbolReads.submit(AsyncEngineReads.SymbolQuery.from(symbolSearch.getText()));
    }

    private void applySymbols(
            Snapshot<AsyncEngineReads.SymbolQuery, AsyncEngineReads.SymbolSearchResult> snapshot) {
        ReadStatePresentation.apply(symbolState, symbolResults, snapshot, "Symbol search results");
        symbolPlaceholder.setText(switch (snapshot.freshness()) {
            case LOADING -> "Loading symbols…";
            case FAILED -> "Symbol search failed";
            case RESYNCING -> "Waiting for a validated program snapshot";
            default -> "No matching symbols";
        });

        var displayed = snapshot.displayed();
        if (displayed == null) {
            if (!displayedResultId.isEmpty()) {
                displayedResultId = "";
                symbolResults.getItems().clear();
            }
            symbolCount.setText(snapshot.freshness() == dev.ghidraex.viewstate.ContextualReadSlot.Freshness.LOADING
                    ? "Loading ≤ " + AsyncEngineReads.MAX_SYMBOL_RESULTS
                    : "No symbols");
            return;
        }

        int size = displayed.value().symbols().size();
        symbolCount.setText(size + (displayed.completeness()
                == dev.ghidraex.viewstate.ContextualReadSlot.Completeness.COMPLETE
                ? " symbols"
                : "+ symbols · capped"));
        if (!displayed.resultId().equals(displayedResultId)) {
            Symbol selected = symbolResults.getSelectionModel().getSelectedItem();
            displayedResultId = displayed.resultId();
            symbolResults.getItems().setAll(displayed.value().symbols());
            int selectedIndex = selected == null ? -1 : symbolResults.getItems().indexOf(selected);
            if (selectedIndex >= 0) {
                symbolResults.getSelectionModel().select(selectedIndex);
            } else if (!symbolResults.getItems().isEmpty()) {
                symbolResults.getSelectionModel().selectFirst();
            }
        }
    }

    private void activateSelected() {
        Symbol symbol = symbolResults.getSelectionModel().getSelectedItem();
        if (symbol != null) {
            symbolListener.accept(symbol);
        }
    }

    @Override
    public void close() {
        symbolReads.close();
    }

    private static final class SymbolCell extends ListCell<Symbol> {
        private final Label kind = new Label();
        private final Label name = new Label();
        private final Label address = new Label();
        private final VBox labels = new VBox(1, name, address);
        private final HBox row = new HBox(9, kind, labels);

        private SymbolCell() {
            getStyleClass().add("symbol-cell");
            kind.getStyleClass().add("symbol-kind");
            name.getStyleClass().add("symbol-name");
            address.getStyleClass().add("symbol-address");
            row.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(Symbol symbol, boolean empty) {
            super.updateItem(symbol, empty);
            if (empty || symbol == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            kind.setText(switch (symbol.kind()) {
                case FUNCTION -> "ƒ";
                case IMPORT -> "↗";
                case DATA -> "◆";
                case LABEL -> "●";
            });
            name.setText(symbol.name());
            address.setText(symbol.formattedAddress() + "  ·  " + symbol.namespace());
            setText(null);
            setGraphic(row);
        }
    }
}
