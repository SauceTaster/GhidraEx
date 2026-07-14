package dev.ghidraex.fx;

import dev.ghidraex.engine.SyntheticAnalysisEngine;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.Objects;

public final class GhidraFxApp extends Application {
    private SyntheticAnalysisEngine engine;
    private EngineSessionSnapshot session;
    private WorkbenchShell shell;

    @Override
    public void init() {
        // Application.init runs before interactive FX controls exist. A real remote Ghidra attach
        // would perform this bounded metadata handshake behind an attach/splash stage, then pass the
        // same immutable snapshot into start(); controls never discover provider state from getters.
        engine = new SyntheticAnalysisEngine();
        session = EngineSessionSnapshot.capture(engine);
    }

    @Override
    public void start(Stage stage) {
        shell = new WorkbenchShell(engine, session);
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
        if (shell != null) {
            shell.dispose();
        }
        if (engine != null) {
            SyntheticAnalysisEngine closing = engine;
            engine = null;
            // A non-daemon closer keeps process shutdown honest without blocking the FX lifecycle
            // thread on a future remote transport's teardown.
            Thread.ofPlatform().name("ghidra-engine-close").daemon(false).start(closing::close);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
