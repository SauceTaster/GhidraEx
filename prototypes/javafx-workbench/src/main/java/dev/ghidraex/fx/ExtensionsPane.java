package dev.ghidraex.fx;

import dev.ghidraex.workbench.advanced.AdvancedWorkbenchModel;
import dev.ghidraex.workbench.advanced.AdvancedWorkbenchModel.ExtensionState;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** Extension/backend inventory with explicit capability approval and availability states. */
final class ExtensionsPane extends BorderPane {
    private final AdvancedWorkbenchModel model;
    private final ListView<ExtensionState> extensions = new ListView<>();
    private final Label inventory = new Label();

    ExtensionsPane(AdvancedWorkbenchModel model) {
        this.model = model;
        getStyleClass().add("advanced-pane");
        setTop(buildHeader());
        setCenter(buildInventory());
        setBottom(buildPolicyBar());
        refresh();
    }

    private VBox buildHeader() {
        Label heading = new Label("BACKEND & EXTENSIONS");
        heading.getStyleClass().add("advanced-heading");
        Label status = chip(model.backend().health().name().replace('_', ' '));
        status.getStyleClass().add(model.backend().distributionDetected() ? "safe-chip" : "warning-chip");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label version = new Label(model.backend().label() + " · " + model.backend().version());
        version.getStyleClass().add("coordinate-label");
        HBox title = new HBox(9, heading, status, spacer, version);
        title.setAlignment(Pos.CENTER_LEFT);

        Label launcherKey = new Label("LAUNCHER");
        launcherKey.getStyleClass().add("advanced-heading");
        Label launcher = new Label(model.backend().launcher());
        launcher.setWrapText(true);
        launcher.getStyleClass().add("backend-path");
        Label architecture = new Label(
                "UI runtime: JDK 25 / JavaFX 26  ·  Ghidra runtime: isolated JDK 21 process  ·  transport: immutable JSON documents");
        architecture.setWrapText(true);
        architecture.getStyleClass().add("advanced-subtitle");
        VBox header = new VBox(9, title, launcherKey, launcher, architecture);
        header.setPadding(new Insets(15));
        header.getStyleClass().add("backend-card");
        return header;
    }

    private VBox buildInventory() {
        Label heading = new Label("INSTALLED COMPONENTS");
        heading.getStyleClass().add("advanced-heading");
        inventory.getStyleClass().add("advanced-subtitle");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button refresh = new Button("Refresh inventory");
        refresh.getStyleClass().add("quiet-button");
        refresh.setOnAction(event -> refresh());
        HBox header = new HBox(8, heading, inventory, spacer, refresh);
        header.setAlignment(Pos.CENTER_LEFT);

        extensions.getStyleClass().add("extension-list");
        extensions.setCellFactory(ignored -> new ExtensionCell());
        VBox content = new VBox(9, header, extensions);
        content.setPadding(new Insets(14));
        VBox.setVgrow(extensions, Priority.ALWAYS);
        return content;
    }

    private HBox buildPolicyBar() {
        Label shield = new Label("◇");
        shield.getStyleClass().add("safety-glyph");
        Label heading = new Label("EXTENSION POLICY");
        heading.getStyleClass().add("advanced-heading");
        Label policy = new Label(
                "Extensions declare program, filesystem, network, process, and live-debug capabilities independently");
        policy.getStyleClass().add("muted-label");
        HBox bar = new HBox(9, shield, heading, policy);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("advanced-safety-bar");
        return bar;
    }

    private void refresh() {
        var states = model.extensions();
        extensions.setItems(FXCollections.observableArrayList(states));
        long active = states.stream().filter(ExtensionState::enabled).count();
        inventory.setText(active + " active / " + states.size() + " installed");
    }

    private static Label chip(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("state-chip");
        return label;
    }

    private final class ExtensionCell extends ListCell<ExtensionState> {
        @Override
        protected void updateItem(ExtensionState extension, boolean empty) {
            super.updateItem(extension, empty);
            if (empty || extension == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            Label icon = new Label(extension.enabled() ? "✓" : "○");
            icon.getStyleClass().add(extension.enabled() ? "extension-active" : "extension-idle");
            Label name = new Label(extension.name());
            name.getStyleClass().add("extension-name");
            Label provider = new Label(extension.provider());
            provider.getStyleClass().add("advanced-subtitle");
            VBox identity = new VBox(2, name, provider);

            Label capabilities = new Label(extension.requiredCapabilities().isEmpty()
                    ? "No elevated capabilities"
                    : extension.requiredCapabilities().stream()
                            .map(AdvancedWorkbenchModel.Capability::id)
                            .sorted()
                            .reduce((left, right) -> left + " · " + right)
                            .orElse(""));
            capabilities.getStyleClass().add("extension-capabilities");
            Label state = new Label(extension.status());
            state.getStyleClass().add("extension-status");
            VBox details = new VBox(3, capabilities, state);
            HBox.setHgrow(details, Priority.ALWAYS);

            Button toggle = new Button(extension.enabled() ? "Disable" :
                    extension.requiredCapabilities().isEmpty() ? "Enable" : "Review & enable");
            toggle.getStyleClass().add(extension.enabled() ? "quiet-button" : "primary-button");
            toggle.setOnAction(event -> {
                model.setExtensionEnabled(
                        extension.id(),
                        !extension.enabled(),
                        extension.requiredCapabilities());
                refresh();
            });

            HBox row = new HBox(11, icon, identity, details, toggle);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("extension-row");
            setText(null);
            setGraphic(row);
        }
    }
}
