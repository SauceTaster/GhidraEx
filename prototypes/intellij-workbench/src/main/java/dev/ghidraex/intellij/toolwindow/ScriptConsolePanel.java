package dev.ghidraex.intellij.toolwindow;

import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import dev.ghidraex.intellij.action.GoToSelectedSymbolAction;
import dev.ghidraex.intellij.session.SessionListener;
import dev.ghidraex.intellij.session.WorkbenchSession;
import dev.ghidraex.script.ReplModel;
import dev.ghidraex.script.ScriptCatalog;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;

/** Native IntelliJ console plus a script-workflow catalog with explicit adapter requirements. */
final class ScriptConsolePanel extends JPanel implements Disposable {
    private final Project project;
    private final WorkbenchSession session;
    private final ConsoleView console;
    private final JBList<ScriptCatalog.Script> scripts;
    private final JBTextArea scriptDetails = new JBTextArea();
    private final ComboBox<ReplModel.Language> language = new ComboBox<>(ReplModel.Language.values());
    private final JBTextField input = new JBTextField();

    ScriptConsolePanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        this.session = WorkbenchSession.getInstance(project);
        this.console = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
        Disposer.register(project, this);
        Disposer.register(this, console);

        this.scripts = new JBList<>(ScriptCatalog.scripts());
        scripts.setCellRenderer(new ScriptRenderer());
        scripts.setSelectedIndex(0);
        scripts.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateScriptDetails();
            }
        });

        scriptDetails.setEditable(false);
        scriptDetails.setLineWrap(true);
        scriptDetails.setWrapStyleWord(true);
        scriptDetails.setRows(7);
        scriptDetails.setBorder(JBUI.Borders.empty(8));

        var runScript = new JButton("Run / Explain Requirement");
        runScript.addActionListener(event -> runSelectedScript());

        var scriptPanel = new JPanel(new BorderLayout());
        scriptPanel.add(new JBScrollPane(scripts), BorderLayout.CENTER);
        var scriptFooter = new JPanel(new BorderLayout());
        scriptFooter.add(new JBScrollPane(scriptDetails), BorderLayout.CENTER);
        var scriptButton = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        scriptButton.add(runScript);
        scriptFooter.add(scriptButton, BorderLayout.SOUTH);
        scriptPanel.add(scriptFooter, BorderLayout.SOUTH);

        input.getEmptyText().setText("Bridge command, for example symbol(\"crc\") or goTo(0x00401120)");
        input.addActionListener(event -> executeInput());
        var execute = new JButton("Execute");
        execute.addActionListener(event -> executeInput());
        var clear = new JButton("Clear");
        clear.addActionListener(event -> {
            console.clear();
            session.clearReplHistory();
            printBanner();
        });

        var inputPanel = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        inputPanel.setBorder(JBUI.Borders.empty(6));
        inputPanel.add(language, BorderLayout.WEST);
        inputPanel.add(input, BorderLayout.CENTER);
        var inputActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        inputActions.add(clear);
        inputActions.add(execute);
        inputPanel.add(inputActions, BorderLayout.EAST);

        var replPanel = new JPanel(new BorderLayout());
        replPanel.add(console.getComponent(), BorderLayout.CENTER);
        replPanel.add(inputPanel, BorderLayout.SOUTH);

        var splitter = new JBSplitter(false, 0.30f);
        splitter.setFirstComponent(scriptPanel);
        splitter.setSecondComponent(replPanel);
        add(splitter, BorderLayout.CENTER);

        updateScriptDetails();
        printBanner();
        project.getMessageBus().connect(this).subscribe(
                SessionListener.TOPIC,
                changed -> updateScriptDetails()
        );
    }

    private void executeInput() {
        String command = input.getText();
        if (command == null || command.isBlank()) {
            return;
        }
        var selectedLanguage = (ReplModel.Language) language.getSelectedItem();
        if (selectedLanguage == null) {
            return;
        }
        execute(selectedLanguage, command);
        input.setText("");
        input.requestFocusInWindow();
    }

    private void execute(ReplModel.Language selectedLanguage, String command) {
        console.print(selectedLanguage.prompt() + command + System.lineSeparator(),
                ConsoleViewContentType.USER_INPUT);
        var result = session.evaluateRepl(selectedLanguage, command);
        ConsoleViewContentType outputType = result.resultKind() == ReplModel.ResultKind.ERROR
                ? ConsoleViewContentType.ERROR_OUTPUT
                : ConsoleViewContentType.NORMAL_OUTPUT;
        console.print(result.output() + System.lineSeparator(), outputType);
        if (result.navigationAddress().isPresent()) {
            GoToSelectedSymbolAction.navigate(project, result.navigationAddress().getAsLong());
        }
    }

    private void runSelectedScript() {
        ScriptCatalog.Script script = scripts.getSelectedValue();
        if (script == null) {
            return;
        }
        String availability = script.availability(session.backendStatus());
        console.print("[script] " + script.name() + System.lineSeparator(),
                ConsoleViewContentType.SYSTEM_OUTPUT);
        console.print(availability + System.lineSeparator(),
                script.requirement() == ScriptCatalog.Requirement.ENGINE_CONTRACT
                        ? ConsoleViewContentType.NORMAL_OUTPUT
                        : ConsoleViewContentType.ERROR_OUTPUT);
        if (script.requirement() == ScriptCatalog.Requirement.ENGINE_CONTRACT) {
            execute(ReplModel.Language.PYTHON_CONTRACT, script.demoCommand());
        } else {
            console.print("Execution was blocked: detection alone does not provide a connected "
                            + "transaction or trace adapter." + System.lineSeparator(),
                    ConsoleViewContentType.ERROR_OUTPUT);
        }
    }

    private void updateScriptDetails() {
        ScriptCatalog.Script script = scripts.getSelectedValue();
        if (script == null) {
            scriptDetails.setText("");
            return;
        }
        scriptDetails.setText(script.language() + System.lineSeparator()
                + script.description() + System.lineSeparator() + System.lineSeparator()
                + "Requirement: " + script.requirement() + System.lineSeparator()
                + "State: " + script.availability(session.backendStatus()));
        scriptDetails.setCaretPosition(0);
    }

    private void printBanner() {
        console.print("GhidraEx Script Console — native IntelliJ ConsoleView" + System.lineSeparator(),
                ConsoleViewContentType.SYSTEM_OUTPUT);
        console.print("Contract mode is active. No Python, Java, PyGhidra, or GhidraScript VM is "
                        + "attached; unsupported code fails visibly." + System.lineSeparator()
                        + "Type help() for available bridge commands." + System.lineSeparator()
                        + System.lineSeparator(),
                ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    @Override
    public void dispose() {
    }

    private static final class ScriptRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list,
                Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus
        ) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof ScriptCatalog.Script script) {
                setText("<html><b>" + script.name() + "</b><br><small>"
                        + script.language() + "</small></html>");
                setBorder(JBUI.Borders.empty(7));
            }
            return this;
        }
    }
}
