package dev.ghidraex.fx;

import dev.ghidraex.engine.SyntheticAnalysisEngine;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.Objects;

public final class GhidraFxApp extends Application {
    private SyntheticAnalysisEngine engine;

    @Override
    public void start(Stage stage) {
        engine = new SyntheticAnalysisEngine();
        WorkbenchShell shell = new WorkbenchShell(engine);
        Scene scene = new Scene(shell, 1540, 940);
        scene.getStylesheets().add(Objects.requireNonNull(
                GhidraFxApp.class.getResource("/dev/ghidraex/fx/workbench.css")).toExternalForm());
        shell.installSceneBindings(scene);

        stage.setTitle("GhidraFX — telemetryd");
        stage.setMinWidth(1120);
        stage.setMinHeight(720);
        stage.setScene(scene);
        stage.show();
        shell.restoreLayout();
    }

    @Override
    public void stop() {
        if (engine != null) {
            engine.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
