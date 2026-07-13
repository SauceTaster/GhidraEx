package dev.ghidraex.fx;

import dev.ghidraex.engine.AnalysisEngine;
import dev.ghidraex.engine.Symbol;
import dev.ghidraex.workbench.command.CommandRegistry;
import dev.ghidraex.workbench.command.WorkbenchCommand;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

final class WorkbenchShell extends StackPane {
    private final AnalysisEngine engine;
    private final LayoutPreferences layout = new LayoutPreferences();
    private final CommandRegistry commands = new CommandRegistry();
    private final ListingPane listing;
    private final DecompilerPane decompiler;
    private final InspectorPane inspector;
    private final ProjectExplorerPane explorer;
    private final TaskBar taskBar;
    private final TabPane workTabs = new TabPane();
    private final SplitPane split = new SplitPane();
    private final CommandPalette palette;
    private final Tab listingTab;
    private final Tab decompilerTab;

    WorkbenchShell(AnalysisEngine engine) {
        this.engine = engine;
        getStyleClass().add("workbench");

        listing = new ListingPane(engine);
        decompiler = new DecompilerPane(engine);
        inspector = new InspectorPane(engine.program());
        explorer = new ProjectExplorerPane(engine);
        taskBar = new TaskBar(engine);

        listingTab = new Tab("LISTING", listing);
        decompilerTab = new Tab("DECOMPILER", decompiler);
        workTabs.getTabs().addAll(listingTab, decompilerTab);
        workTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        workTabs.getStyleClass().add("work-tabs");

        split.getItems().addAll(explorer, workTabs, inspector);
        split.getStyleClass().add("workbench-split");

        registerCommands();
        palette = new CommandPalette(commands);

        BorderPane frame = new BorderPane();
        frame.setTop(buildChrome());
        frame.setCenter(split);
        frame.setBottom(taskBar);
        getChildren().add(frame);
        getChildren().add(palette);
        wireInteractions();
    }

    void installSceneBindings(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.K && event.isShortcutDown()) {
                if (palette.isShowing()) palette.hide(); else palette.show();
                event.consume();
            } else if (event.getCode() == KeyCode.P && event.isShortcutDown()) {
                palette.show();
                event.consume();
            } else if (event.getCode() == KeyCode.F5 && !palette.isShowing()) {
                taskBar.runAnalysis();
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE && palette.isShowing()) {
                palette.hide();
                event.consume();
            }
        });
    }

    void restoreLayout() {
        split.setDividerPositions(layout.leftDivider(), layout.rightDivider());
        workTabs.getSelectionModel().select(layout.activeTab());
        Platform.runLater(listing::selectInitialLine);
    }

    private VBox buildChrome() {
        Label productMark = new Label("GX");
        productMark.getStyleClass().add("product-mark");
        Label productName = new Label("GHIDRA");
        productName.getStyleClass().add("product-name");
        Label edition = new Label("FX LAB");
        edition.getStyleClass().add("edition-chip");

        Separator brandSeparator = new Separator();
        brandSeparator.setOrientation(javafx.geometry.Orientation.VERTICAL);

        Label project = new Label(engine.program().projectName());
        project.getStyleClass().add("breadcrumb-muted");
        Label slash = new Label("/");
        slash.getStyleClass().add("breadcrumb-slash");
        MenuButton binary = new MenuButton(engine.program().binaryName());
        binary.getStyleClass().add("binary-menu");
        MenuItem properties = new MenuItem("Program properties");
        MenuItem close = new MenuItem("Close program");
        close.setDisable(true);
        binary.getItems().addAll(properties, close);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button paletteButton = new Button("⌕  Commands     ⌘K");
        paletteButton.getStyleClass().add("command-button");
        paletteButton.setOnAction(event -> palette.show());
        Label analysisState = new Label("ANALYSIS CURRENT");
        analysisState.getStyleClass().add("analysis-chip");
        Button overflow = new Button("•••");
        overflow.getStyleClass().add("icon-button");

        HBox titleBar = new HBox(9, productMark, productName, edition, brandSeparator,
                project, slash, binary, spacer, paletteButton, analysisState, overflow);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.getStyleClass().add("title-bar");

        Label workspace = new Label("WORKSPACE");
        workspace.getStyleClass().add("eyebrow");
        Button functions = navButton("ƒ", "Functions", false);
        Button graph = navButton("⌘", "Graph", false);
        Button memory = navButton("▦", "Memory", false);
        Button strings = navButton("“", "Strings", false);
        Region navSpacer = new Region();
        HBox.setHgrow(navSpacer, Priority.ALWAYS);
        Label database = new Label("PROGRAM DB  ·  435 FUNCTIONS  ·  892 STRINGS");
        database.getStyleClass().add("toolbar-metadata");

        HBox toolbar = new HBox(7, workspace, functions, graph, memory, strings, navSpacer, database);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("workspace-toolbar");
        return new VBox(titleBar, toolbar);
    }

    private static Button navButton(String glyph, String title, boolean active) {
        Button button = new Button(glyph + "  " + title);
        button.getStyleClass().add("nav-button");
        if (active) button.getStyleClass().add("active");
        return button;
    }

    private void registerCommands() {
        commands.register(new WorkbenchCommand("navigate.symbol", "Go to symbol", "Navigation", "⌘⇧O",
                explorer::focusSymbolSearch));
        commands.register(new WorkbenchCommand("view.listing", "Open listing", "View", "⌘1",
                () -> workTabs.getSelectionModel().select(listingTab)));
        commands.register(new WorkbenchCommand("view.decompiler", "Open decompiler", "View", "⌘2",
                () -> workTabs.getSelectionModel().select(decompilerTab)));
        commands.register(new WorkbenchCommand("analysis.run", "Run auto-analysis", "Analysis", "F5",
                taskBar::runAnalysis));
        commands.register(new WorkbenchCommand("analysis.retype", "Retype current function", "Analysis", "Y",
                () -> workTabs.getSelectionModel().select(decompilerTab)));
        commands.register(new WorkbenchCommand("navigation.entry", "Navigate to entry point", "Navigation", "G E",
                () -> listing.goToAddress(engine.program().imageBase())));
        commands.register(new WorkbenchCommand("layout.reset", "Reset saved layout", "Workspace", "",
                this::resetLayout));
        commands.register(new WorkbenchCommand("project.properties", "Show program properties", "Project", "",
                () -> split.getDividers().get(1).setPosition(0.72)));
    }

    private void wireInteractions() {
        explorer.setOnSymbolActivated(this::activateSymbol);
        listing.setOnLineSelected(inspector::showLine);

        workTabs.getSelectionModel().selectedIndexProperty().addListener((observable, oldIndex, index) ->
                layout.saveActiveTab(index.intValue()));
        split.getDividers().forEach(divider -> divider.positionProperty().addListener((observable, oldValue, value) -> {
            if (split.getDividers().size() == 2) {
                layout.saveDividers(split.getDividerPositions()[0], split.getDividerPositions()[1]);
            }
        }));
    }

    private void activateSymbol(Symbol symbol) {
        inspector.showSymbol(symbol);
        listing.goToAddress(symbol.address());
        decompiler.showSymbol(symbol.name());
        workTabs.getSelectionModel().select(listingTab);
    }

    private void resetLayout() {
        layout.reset();
        split.setDividerPositions(0.205, 0.79);
        workTabs.getSelectionModel().select(listingTab);
    }
}
