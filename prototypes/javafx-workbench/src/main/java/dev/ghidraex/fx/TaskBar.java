package dev.ghidraex.fx;

import dev.ghidraex.engine.AnalysisEngine;
import dev.ghidraex.engine.AnalysisProgress;
import dev.ghidraex.engine.ProgramInfo;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

final class TaskBar extends HBox implements AutoCloseable {
    private final AsyncAnalysisLauncher launcher;
    private final Label state = new Label("Ready");
    private final Label phase = new Label();
    private final ProgressBar progress = new ProgressBar();
    private final Button cancel = new Button("Cancel");
    private final Button analyze = new Button("Run analysis");
    private final HBox runningGroup;
    private final Runnable analysisChanged;
    private AsyncAnalysisLauncher.RunHandle running;

    TaskBar(AnalysisEngine engine, ProgramInfo program, Runnable analysisChanged) {
        launcher = AsyncAnalysisLauncher.createFx(engine);
        this.analysisChanged = analysisChanged == null ? () -> { } : analysisChanged;
        getStyleClass().add("task-bar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(10);

        Label statusDot = new Label("●");
        statusDot.getStyleClass().add("status-dot");
        state.getStyleClass().add("status-label");
        Label model = new Label(program.architecture());
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
        if (running != null && !running.terminal()) {
            return;
        }
        state.setText("Analyzing");
        analyze.setDisable(true);
        runningGroup.setManaged(true);
        runningGroup.setVisible(true);
        phase.setText("Preparing analyzers");
        progress.setProgress(0);

        running = launcher.start(this::updateRun);
    }

    private void cancelAnalysis() {
        if (running != null && !running.terminal()) {
            phase.setText("Cancelling…");
            cancel.setDisable(true);
            running.cancel();
        }
    }

    private void updateRun(AsyncAnalysisLauncher.Snapshot snapshot) {
        if (snapshot.progress() != null) {
            updateProgress(snapshot.progress());
        }
        if (snapshot.state().terminal()) {
            finishAnalysis(snapshot);
        } else if (snapshot.state() == AsyncAnalysisLauncher.State.STARTING) {
            phase.setText("Preparing analyzers");
        }
    }

    private void updateProgress(AnalysisProgress update) {
        progress.setProgress(update.fraction());
        phase.setText(update.phase());
        cancel.setDisable(!update.cancellable());
    }

    private void finishAnalysis(AsyncAnalysisLauncher.Snapshot snapshot) {
        boolean wasCancelled = snapshot.state() == AsyncAnalysisLauncher.State.CANCELLED;
        state.setText(switch (snapshot.state()) {
            case CURRENT -> "Analysis current";
            case CANCELLED -> "Analysis cancelled";
            case FAILED -> "Analysis failed";
            default -> "Analyzing";
        });
        runningGroup.setVisible(false);
        runningGroup.setManaged(false);
        cancel.setDisable(false);
        analyze.setDisable(false);
        analyze.setText(wasCancelled ? "Run analysis" : "Re-run analysis");
        if (snapshot.state() == AsyncAnalysisLauncher.State.CURRENT) {
            analysisChanged.run();
        }
    }

    @Override
    public void close() {
        launcher.close();
    }
}
