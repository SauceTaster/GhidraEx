package dev.ghidraex.fx;

import dev.ghidraex.workbench.advanced.AdvancedWorkbenchModel;
import dev.ghidraex.workbench.advanced.AdvancedWorkbenchModel.DebuggerState;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Map;

/** Native JavaFX projection of TRACE-01 debugger coordinates and safety states. */
final class DebuggerPane extends BorderPane {
    private final AdvancedWorkbenchModel model;
    private final Label mode = chip("CAPTURED TRACE");
    private final Label status = chip("PAUSED");
    private final Label coordinates = new Label();
    private final Label memoryState = new Label();
    private final Label writeState = chip("WRITES DISABLED");
    private final ListView<String> registers = list();
    private final ListView<String> stack = list();
    private final ListView<String> watches = list();
    private final Button previousSnapshot = action("◀  Past snapshot");
    private final Button latestSnapshot = action("Latest");
    private final Button forkEmulator = primary("Fork emulator");
    private final Button stepBack = action("↶ Step back");
    private final Button stepForward = action("Step forward ↷");

    DebuggerPane(AdvancedWorkbenchModel model) {
        this.model = model;
        getStyleClass().add("advanced-pane");
        setTop(buildToolbar());
        setCenter(buildBody());
        setBottom(buildSafetyBar());
        wireActions();
        render(model.debugger());
    }

    private HBox buildToolbar() {
        Label title = new Label("DEBUGGER COORDINATES");
        title.getStyleClass().add("advanced-heading");
        coordinates.getStyleClass().add("coordinate-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button attachLive = action("Attach live target…");
        attachLive.setDisable(true);
        attachLive.setTooltip(new Tooltip(
                "Live target control requires a connected Ghidra Trace RMI session; a configured headless backend alone is not sufficient."));
        HBox toolbar = new HBox(9, title, mode, status, coordinates, spacer,
                previousSnapshot, latestSnapshot, forkEmulator, stepBack, stepForward, attachLive);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().addAll("pane-toolbar", "advanced-toolbar");
        return toolbar;
    }

    private SplitPane buildBody() {
        VBox stackPanel = section("CALL STACK", "Trace thread and frame are one atomic coordinate", stack);
        VBox registerPanel = section("REGISTERS", "Stale values are labeled instead of silently reused", registers);
        VBox watchPanel = section("WATCHES", "Evaluations are dropped when frame or snapshot changes", watches);
        SplitPane body = new SplitPane(stackPanel, registerPanel, watchPanel);
        body.setDividerPositions(0.31, 0.67);
        body.getStyleClass().add("advanced-split");
        return body;
    }

    private HBox buildSafetyBar() {
        Label shield = new Label("◉");
        shield.getStyleClass().add("safety-glyph");
        Label title = new Label("TARGET EFFECTS");
        title.getStyleClass().add("advanced-heading");
        memoryState.getStyleClass().add("muted-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label note = new Label("Trace edits are undoable · external target effects are not");
        note.getStyleClass().add("muted-label");
        HBox bar = new HBox(9, shield, title, memoryState, spacer, note, writeState);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("advanced-safety-bar");
        return bar;
    }

    private void wireActions() {
        previousSnapshot.setOnAction(event -> {
            DebuggerState current = model.debugger();
            long target = Math.max(0, current.snapshot() - 24);
            render(model.selectSnapshot(target));
        });
        latestSnapshot.setOnAction(event -> {
            DebuggerState current = model.debugger();
            if (current.mode() == AdvancedWorkbenchModel.DebugMode.CAPTURED_TRACE) {
                render(model.selectSnapshot(current.latestSnapshot()));
            }
        });
        forkEmulator.setOnAction(event -> render(model.forkEmulator()));
        stepBack.setOnAction(event -> render(model.stepEmulator(-1)));
        stepForward.setOnAction(event -> render(model.stepEmulator(1)));
    }

    private void render(DebuggerState state) {
        mode.setText(state.mode().name().replace('_', ' '));
        status.setText(state.status().name().replace('_', ' '));
        coordinates.setText("snap " + state.snapshot() + " / " + state.latestSnapshot()
                + "  ·  " + state.thread() + "  ·  frame #" + state.frame());
        memoryState.setText(state.staleMemory()
                ? "Historical snapshot · memory/register values may be stale"
                : "Current snapshot · mapped captured memory");
        writeState.setText(state.writesEnabled() ? "EMULATOR WRITES" : "WRITES DISABLED");
        writeState.getStyleClass().removeAll("warning-chip", "safe-chip");
        writeState.getStyleClass().add(state.writesEnabled() ? "warning-chip" : "safe-chip");

        registers.setItems(FXCollections.observableArrayList(
                state.registers().entrySet().stream().map(DebuggerPane::registerRow).toList()));
        stack.setItems(FXCollections.observableArrayList(state.stack()));
        watches.setItems(FXCollections.observableArrayList(state.watches()));

        boolean emulator = state.mode() == AdvancedWorkbenchModel.DebugMode.EMULATOR;
        previousSnapshot.setDisable(emulator || state.snapshot() == 0);
        latestSnapshot.setDisable(emulator || state.snapshot() == state.latestSnapshot());
        forkEmulator.setDisable(emulator);
        stepBack.setDisable(!emulator);
        stepForward.setDisable(!emulator);
    }

    private static String registerRow(Map.Entry<String, String> entry) {
        return "%-8s  %s".formatted(entry.getKey(), entry.getValue());
    }

    private static VBox section(String heading, String detail, ListView<String> content) {
        Label title = new Label(heading);
        title.getStyleClass().add("advanced-heading");
        Label subtitle = new Label(detail);
        subtitle.setWrapText(true);
        subtitle.getStyleClass().add("advanced-subtitle");
        Separator separator = new Separator();
        VBox panel = new VBox(7, title, subtitle, separator, content);
        panel.setPadding(new Insets(14));
        panel.getStyleClass().add("advanced-section");
        VBox.setVgrow(content, Priority.ALWAYS);
        return panel;
    }

    private static ListView<String> list() {
        ListView<String> list = new ListView<>();
        list.getStyleClass().add("advanced-list");
        return list;
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
