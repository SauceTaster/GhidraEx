package dev.ghidraex.fx;

import dev.ghidraex.engine.AnalysisEngine;
import dev.ghidraex.engine.ProgramInfo;
import dev.ghidraex.engine.Symbol;
import dev.ghidraex.workbench.advanced.AdvancedWorkbenchModel;
import dev.ghidraex.workbench.command.CommandRegistry;
import dev.ghidraex.workbench.command.WorkbenchCommand;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
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
    private final ProgramInfo program;
    private final AsyncEngineReads reads;
    private final AdvancedWorkbenchModel advanced = AdvancedWorkbenchModel.createDefault();
    private final LayoutPreferences layout = new LayoutPreferences();
    private final CommandRegistry commands = new CommandRegistry();
    private final ListingPane listing;
    private final DecompilerPane decompiler;
    private final InspectorPane inspector;
    private final ProjectExplorerPane explorer;
    private final TaskBar taskBar;
    private final DebuggerPane debugger;
    private final ScriptConsolePane scripting;
    private final ExtensionsPane extensions;
    private final TabPane workTabs = new TabPane();
    private final SplitPane split = new SplitPane();
    private final CommandPalette palette;
    private final Tab listingTab;
    private final Tab decompilerTab;
    private final Tab debuggerTab;
    private final Tab scriptingTab;
    private final Tab extensionsTab;

    WorkbenchShell(AnalysisEngine engine, EngineSessionSnapshot session) {
        EngineSessionSnapshot safeSession = java.util.Objects.requireNonNull(session, "session");
        program = safeSession.program();
        reads = AsyncEngineReads.createFx(engine, safeSession);
        getStyleClass().add("workbench");

        listing = new ListingPane(engine, safeSession);
        decompiler = new DecompilerPane(reads);
        inspector = new InspectorPane(program, reads);
        explorer = new ProjectExplorerPane(program, reads);
        taskBar = new TaskBar(engine, program, this::contentGenerationAdvanced);
        debugger = new DebuggerPane(advanced);
        scripting = new ScriptConsolePane(advanced);
        extensions = new ExtensionsPane(advanced);

        listingTab = new Tab("LISTING", listing);
        decompilerTab = new Tab("DECOMPILER", decompiler);
        debuggerTab = new Tab("DEBUGGER", debugger);
        scriptingTab = new Tab("SCRIPTING", scripting);
        extensionsTab = new Tab("EXTENSIONS", extensions);
        workTabs.getTabs().addAll(listingTab, decompilerTab, debuggerTab, scriptingTab, extensionsTab);
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

        Label project = new Label(program.projectName());
        project.getStyleClass().add("breadcrumb-muted");
        Label slash = new Label("/");
        slash.getStyleClass().add("breadcrumb-slash");
        MenuButton binary = new MenuButton(program.binaryName());
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
        Label analysisState = new Label(advanced.backend().connected()
                ? "GHIDRA " + advanced.backend().version()
                : advanced.backend().distributionDetected()
                        ? "GHIDRA " + advanced.backend().version() + " DETECTED"
                        : "FIXTURE ENGINE");
        analysisState.getStyleClass().add("analysis-chip");
        MenuButton overflow = buildToolsMenu();
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
        Button debug = navButton("◉", "Debugger", false);
        debug.setOnAction(event -> workTabs.getSelectionModel().select(debuggerTab));
        Button scripts = navButton(">_", "Scripts", false);
        scripts.setOnAction(event -> workTabs.getSelectionModel().select(scriptingTab));
        Button plugins = navButton("◇", "Extensions", false);
        plugins.setOnAction(event -> workTabs.getSelectionModel().select(extensionsTab));
        Region navSpacer = new Region();
        HBox.setHgrow(navSpacer, Priority.ALWAYS);
        Label database = new Label("PROGRAM DB  ·  435 FUNCTIONS  ·  892 STRINGS");
        database.getStyleClass().add("toolbar-metadata");

        HBox toolbar = new HBox(7, workspace, functions, graph, memory, strings,
                debug, scripts, plugins, navSpacer, database);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("workspace-toolbar");
        return new VBox(titleBar, toolbar);
    }

    private MenuButton buildToolsMenu() {
        Menu debuggerMenu = new Menu("Debugger");
        MenuItem openDebugger = new MenuItem("Open Debugger Coordinates");
        openDebugger.setOnAction(event -> workTabs.getSelectionModel().select(debuggerTab));
        MenuItem attach = new MenuItem("Attach Live Target…");
        attach.setDisable(true);
        MenuItem trace = new MenuItem("Open Captured Trace");
        trace.setOnAction(event -> workTabs.getSelectionModel().select(debuggerTab));
        debuggerMenu.getItems().addAll(openDebugger, trace, new SeparatorMenuItem(), attach);

        Menu scriptMenu = new Menu("Scripting");
        MenuItem console = new MenuItem("Open Script Console / REPL");
        console.setOnAction(event -> workTabs.getSelectionModel().select(scriptingTab));
        MenuItem manager = new MenuItem("Ghidra Script Manager…");
        manager.setDisable(!advanced.backend().connected());
        scriptMenu.getItems().addAll(console, manager);

        Menu extensionMenu = new Menu("Extensions");
        MenuItem inventory = new MenuItem("Backend & Extension Inventory");
        inventory.setOnAction(event -> workTabs.getSelectionModel().select(extensionsTab));
        MenuItem install = new MenuItem("Install Extension from Archive…");
        install.setDisable(true);
        extensionMenu.getItems().addAll(inventory, install);

        MenuItem backend = new MenuItem(advanced.backend().connected()
                ? "Backend: Ghidra " + advanced.backend().version()
                : advanced.backend().distributionDetected()
                        ? "Backend: Ghidra detected · adapter disconnected"
                        : "Backend: set GHIDRA_HOME");
        backend.setDisable(true);
        MenuButton menu = new MenuButton("•••");
        menu.getItems().addAll(debuggerMenu, scriptMenu, extensionMenu, new SeparatorMenuItem(), backend);
        return menu;
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
        commands.register(new WorkbenchCommand("view.debugger", "Open debugger coordinates", "View", "⌘3",
                () -> workTabs.getSelectionModel().select(debuggerTab)));
        commands.register(new WorkbenchCommand("view.scripting", "Open scripting and REPL", "View", "⌘4",
                () -> workTabs.getSelectionModel().select(scriptingTab)));
        commands.register(new WorkbenchCommand("view.extensions", "Open backend and extensions", "View", "⌘5",
                () -> workTabs.getSelectionModel().select(extensionsTab)));
        commands.register(new WorkbenchCommand("analysis.run", "Run auto-analysis", "Analysis", "F5",
                taskBar::runAnalysis));
        commands.register(new WorkbenchCommand("analysis.retype", "Retype current function", "Analysis", "Y",
                () -> workTabs.getSelectionModel().select(decompilerTab)));
        commands.register(new WorkbenchCommand("navigation.entry", "Navigate to entry point", "Navigation", "G E",
                () -> listing.goToAddress(program.imageBase())));
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

    private void contentGenerationAdvanced() {
        listing.contentGenerationAdvanced();
        reads.contentGenerationAdvanced();
    }

    void dispose() {
        taskBar.close();
        explorer.close();
        decompiler.close();
        inspector.close();
        reads.close();
        listing.close();
    }
}
