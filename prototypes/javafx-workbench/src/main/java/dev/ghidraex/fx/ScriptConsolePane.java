package dev.ghidraex.fx;

import dev.ghidraex.workbench.advanced.AdvancedWorkbenchModel;
import dev.ghidraex.workbench.advanced.AdvancedWorkbenchModel.Capability;
import dev.ghidraex.workbench.advanced.AdvancedWorkbenchModel.ScriptResult;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** A reviewable, capability-gated script and REPL surface; it is not an unrestricted JVM shell. */
final class ScriptConsolePane extends BorderPane {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AdvancedWorkbenchModel model;
    private final ComboBox<String> runtime = new ComboBox<>();
    private final TextArea editor = new TextArea();
    private final TextArea output = new TextArea();
    private final Label resultState = chip("READ-ONLY");
    private final HBox capabilities = new HBox(6);
    private final ListView<String> history = new ListView<>();
    private final List<String> historyItems = new ArrayList<>();

    ScriptConsolePane(AdvancedWorkbenchModel model) {
        this.model = model;
        getStyleClass().add("advanced-pane");
        setTop(buildToolbar());
        setCenter(buildBody());
        setBottom(buildCapabilityBar());
        initializeContent();
    }

    private HBox buildToolbar() {
        Label title = new Label("SCRIPTING & REPL");
        title.getStyleClass().add("advanced-heading");
        runtime.setItems(FXCollections.observableArrayList(
                "REPL commands",
                "Ghidra Java script",
                "PyGhidra session"));
        runtime.getSelectionModel().selectFirst();
        runtime.getStyleClass().add("runtime-picker");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label backend = new Label(model.backend().connected()
                ? "GHIDRA " + model.backend().version()
                : model.backend().distributionDetected()
                        ? "GHIDRA DETECTED · FIXTURE PREVIEW"
                        : "FIXTURE PREVIEW");
        backend.getStyleClass().add("state-chip");
        Button clear = action("Clear output");
        clear.setOnAction(event -> output.clear());
        Button run = primary("▶  Run read-only");
        run.setOnAction(event -> evaluate());
        HBox toolbar = new HBox(9, title, runtime, resultState, spacer, backend, clear, run);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().addAll("pane-toolbar", "advanced-toolbar");
        return toolbar;
    }

    private SplitPane buildBody() {
        editor.getStyleClass().add("script-editor");
        editor.setPromptText("Enter :where, functions(\"packet\"), bytes(...), or a reviewed Ghidra expression");
        editor.setWrapText(false);

        output.setEditable(false);
        output.setWrapText(false);
        output.getStyleClass().add("script-output");

        history.getStyleClass().add("advanced-list");
        history.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                String selected = history.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    int split = selected.indexOf("  ");
                    editor.setText(split < 0 ? selected : selected.substring(split + 2));
                }
            }
        });

        VBox sourcePanel = section("SOURCE", "Exact source is shown before execution", editor);
        VBox outputPanel = section("OUTPUT", "Every run records status and requested capabilities", output);
        VBox historyPanel = section("SESSION HISTORY", "Double-click to restore an expression", history);
        SplitPane body = new SplitPane(sourcePanel, outputPanel, historyPanel);
        body.setDividerPositions(0.39, 0.78);
        body.getStyleClass().add("advanced-split");
        return body;
    }

    private HBox buildCapabilityBar() {
        Label shield = new Label("◇");
        shield.getStyleClass().add("safety-glyph");
        Label title = new Label("DECLARED CAPABILITIES");
        title.getStyleClass().add("advanced-heading");
        capabilities.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label note = new Label("Read-only project mode is not a Java sandbox");
        note.getStyleClass().add("muted-label");
        HBox bar = new HBox(9, shield, title, capabilities, spacer, note);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("advanced-safety-bar");
        return bar;
    }

    private void initializeContent() {
        editor.setText("functions(\"packet\")");
        output.setText("GhidraEx script worker\n"
                + "Mode: read-only preview\n"
                + "Try :backend or :where. Mutations produce an approval plan; process/network access is blocked.\n");
        showCapabilities(List.of(Capability.PROGRAM_READ));
    }

    private void evaluate() {
        String source = editor.getText().strip();
        ScriptResult result = model.evaluate(source);
        String timestamp = LocalTime.now().format(TIME);
        output.appendText("\n[" + timestamp + "] " + result.status() + "\n" + result.output() + "\n");
        resultState.setText(result.status().name().replace('_', ' '));
        resultState.getStyleClass().removeAll("warning-chip", "safe-chip", "error-chip");
        resultState.getStyleClass().add(switch (result.status()) {
            case SUCCESS -> "safe-chip";
            case APPROVAL_REQUIRED, BACKEND_UNAVAILABLE -> "warning-chip";
            case BLOCKED -> "error-chip";
        });
        showCapabilities(result.requiredCapabilities().stream().sorted().toList());
        if (!source.isEmpty()) {
            historyItems.addFirst(timestamp + "  " + source.replace('\n', ' '));
            history.setItems(FXCollections.observableArrayList(historyItems));
        }
    }

    private void showCapabilities(List<Capability> requested) {
        capabilities.getChildren().clear();
        if (requested.isEmpty()) {
            capabilities.getChildren().add(chip("NONE"));
            return;
        }
        requested.forEach(capability -> capabilities.getChildren().add(chip(capability.id())));
    }

    private static VBox section(String heading, String detail, javafx.scene.Node content) {
        Label title = new Label(heading);
        title.getStyleClass().add("advanced-heading");
        Label subtitle = new Label(detail);
        subtitle.getStyleClass().add("advanced-subtitle");
        Separator separator = new Separator();
        VBox panel = new VBox(7, title, subtitle, separator, content);
        panel.setPadding(new Insets(14));
        panel.getStyleClass().add("advanced-section");
        VBox.setVgrow(content, Priority.ALWAYS);
        return panel;
    }

    private static Label chip(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("state-chip");
        return label;
    }

    private static Button action(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("quiet-button");
        return button;
    }

    private static Button primary(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("primary-button");
        return button;
    }
}
