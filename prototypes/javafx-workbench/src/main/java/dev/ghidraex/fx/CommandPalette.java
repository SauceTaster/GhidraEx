package dev.ghidraex.fx;

import dev.ghidraex.workbench.command.CommandRegistry;
import dev.ghidraex.workbench.command.WorkbenchCommand;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

final class CommandPalette extends StackPane {
    private final CommandRegistry registry;
    private final TextField query = new TextField();
    private final ListView<WorkbenchCommand> results = new ListView<>();

    CommandPalette(CommandRegistry registry) {
        this.registry = registry;
        getStyleClass().add("palette-overlay");
        setVisible(false);
        setManaged(false);

        Region scrim = new Region();
        scrim.getStyleClass().add("palette-scrim");
        scrim.setOnMouseClicked(event -> hide());

        Label searchIcon = new Label("⌘");
        searchIcon.getStyleClass().add("palette-search-icon");
        query.setPromptText("Search commands…");
        query.getStyleClass().add("palette-query");
        HBox.setHgrow(query, Priority.ALWAYS);
        Label shortcut = new Label("ESC");
        shortcut.getStyleClass().add("keycap");
        HBox searchRow = new HBox(10, searchIcon, query, shortcut);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        searchRow.getStyleClass().add("palette-search-row");

        results.getStyleClass().add("palette-results");
        results.setFixedCellSize(55);
        results.setPrefHeight(330);
        results.setCellFactory(ignored -> new CommandCell());
        results.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) executeSelected();
        });

        HBox footer = new HBox(14,
                footerHint("↑↓", "navigate"),
                footerHint("↵", "run"),
                footerHint("esc", "close"));
        footer.getStyleClass().add("palette-footer");

        VBox panel = new VBox(searchRow, results, footer);
        panel.getStyleClass().add("palette-panel");
        panel.setMaxWidth(620);
        panel.setMaxHeight(440);
        StackPane.setAlignment(panel, Pos.TOP_CENTER);
        StackPane.setMargin(panel, new javafx.geometry.Insets(92, 20, 20, 20));
        getChildren().addAll(scrim, panel);

        query.textProperty().addListener((observable, oldValue, value) -> refresh());
        query.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DOWN) {
                results.getSelectionModel().selectNext();
                results.scrollTo(results.getSelectionModel().getSelectedIndex());
                event.consume();
            } else if (event.getCode() == KeyCode.UP) {
                results.getSelectionModel().selectPrevious();
                results.scrollTo(results.getSelectionModel().getSelectedIndex());
                event.consume();
            } else if (event.getCode() == KeyCode.ENTER) {
                executeSelected();
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                hide();
                event.consume();
            }
        });
    }

    void show() {
        setManaged(true);
        setVisible(true);
        query.clear();
        refresh();
        Platform.runLater(query::requestFocus);
    }

    void hide() {
        setVisible(false);
        setManaged(false);
    }

    boolean isShowing() {
        return isVisible();
    }

    private void refresh() {
        results.setItems(FXCollections.observableArrayList(registry.search(query.getText(), 40)));
        if (!results.getItems().isEmpty()) {
            results.getSelectionModel().selectFirst();
        }
    }

    private void executeSelected() {
        WorkbenchCommand command = results.getSelectionModel().getSelectedItem();
        if (command != null && registry.execute(command.id())) {
            hide();
        }
    }

    private static HBox footerHint(String key, String action) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("footer-key");
        Label actionLabel = new Label(action);
        actionLabel.getStyleClass().add("footer-action");
        return new HBox(5, keyLabel, actionLabel);
    }

    private static final class CommandCell extends ListCell<WorkbenchCommand> {
        private final Label glyph = new Label("›");
        private final Label title = new Label();
        private final Label category = new Label();
        private final Label hint = new Label();
        private final VBox labels = new VBox(2, title, category);
        private final Region spacer = new Region();
        private final HBox row = new HBox(11, glyph, labels, spacer, hint);

        private CommandCell() {
            getStyleClass().add("command-cell");
            glyph.getStyleClass().add("command-glyph");
            title.getStyleClass().add("command-title");
            category.getStyleClass().add("command-category");
            hint.getStyleClass().add("command-hint");
            HBox.setHgrow(spacer, Priority.ALWAYS);
            row.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(WorkbenchCommand command, boolean empty) {
            super.updateItem(command, empty);
            if (empty || command == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            title.setText(command.title());
            category.setText(command.category().toUpperCase());
            hint.setText(command.hint());
            row.setOpacity(command.enabled().getAsBoolean() ? 1 : 0.45);
            setText(null);
            setGraphic(row);
        }
    }
}
