package dev.ghidraex.fx;

import dev.ghidraex.engine.AnalysisEngine;
import dev.ghidraex.engine.AnalysisJob;
import dev.ghidraex.engine.AnalysisProgress;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

final class TaskBar extends HBox {
    private final AnalysisEngine engine;
    private final Label state = new Label("Ready");
    private final Label phase = new Label();
    private final ProgressBar progress = new ProgressBar();
    private final Button cancel = new Button("Cancel");
    private final Button analyze = new Button("Run analysis");
    private final HBox runningGroup;
    private AnalysisJob running;

    TaskBar(AnalysisEngine engine) {
        this.engine = engine;
        getStyleClass().add("task-bar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(10);

        Label statusDot = new Label("●");
        statusDot.getStyleClass().add("status-dot");
        state.getStyleClass().add("status-label");
        Label model = new Label(engine.program().architecture());
        model.getStyleClass().add("status-metadata");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        progress.setPrefWidth(150);
        progress.setProgress(0);
        phase.getStyleClass().add("task-phase");
        cancel.getStyleClass().add("quiet-button");
        cancel.setOnAction(event -> cancelAnalysis());
        runningGroup = new HBox(9, phase, progress, cancel);
        runningGroup.setAlignment(Pos.CENTER_LEFT);
        runningGroup.getStyleClass().add("running-task");
        runningGroup.setVisible(false);
        runningGroup.setManaged(false);

        analyze.getStyleClass().add("primary-button");
        analyze.setOnAction(event -> runAnalysis());
        getChildren().addAll(statusDot, state, model, spacer, runningGroup, analyze);
    }

    void runAnalysis() {
        if (running != null && !running.completion().isDone()) {
            return;
        }
        state.setText("Analyzing");
        analyze.setDisable(true);
        runningGroup.setManaged(true);
        runningGroup.setVisible(true);
        phase.setText("Preparing analyzers");
        progress.setProgress(0);

        running = engine.startAnalysis(update -> Platform.runLater(() -> updateProgress(update)));
        AnalysisJob launched = running;
        launched.completion().whenComplete((unused, error) -> Platform.runLater(() -> finishAnalysis(launched, error)));
    }

    private void cancelAnalysis() {
        if (running != null && !running.completion().isDone()) {
            phase.setText("Cancelling…");
            cancel.setDisable(true);
            running.cancel();
        }
    }

    private void updateProgress(AnalysisProgress update) {
        progress.setProgress(update.fraction());
        phase.setText(update.phase());
        cancel.setDisable(!update.cancellable());
    }

    private void finishAnalysis(AnalysisJob job, Throwable error) {
        if (running != job) {
            return;
        }
        boolean wasCancelled = job.isCancelled();
        state.setText(error != null ? "Analysis failed" : wasCancelled ? "Analysis cancelled" : "Analysis current");
        runningGroup.setVisible(false);
        runningGroup.setManaged(false);
        cancel.setDisable(false);
        analyze.setDisable(false);
        analyze.setText(wasCancelled ? "Run analysis" : "Re-run analysis");
    }
}
