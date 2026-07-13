package dev.ghidraex.intellij.toolwindow;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import dev.ghidraex.intellij.action.GoToSelectedSymbolAction;
import dev.ghidraex.intellij.session.SessionListener;
import dev.ghidraex.intellij.session.WorkbenchSession;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

public final class InspectorToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        var session = WorkbenchSession.getInstance(project);
        var details = new JBTextArea();
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setFont(JBFont.create(JBFont.label().deriveFont(13f)));
        details.setBorder(JBUI.Borders.empty(12));
        update(details, session);

        project.getMessageBus().connect(project).subscribe(
                SessionListener.TOPIC,
                changed -> update(details, changed)
        );

        ToolWindowSupport.install(
                toolWindow,
                new JBScrollPane(details),
                "GhidraExInspector",
                GoToSelectedSymbolAction.ID
        );
    }

    private static void update(JBTextArea details, WorkbenchSession session) {
        var symbol = session.selectedSymbol();
        var analysis = session.lastAnalysis();
        details.setText("""
                SYMBOL
                %s

                ADDRESS
                %08x

                KIND
                %s

                SIGNATURE
                %s

                REFERENCES
                %d incoming

                PROGRAM
                %s

                ANALYSIS
                %s
                """.formatted(
                symbol.name(),
                symbol.address(),
                symbol.kind().name().toLowerCase(Locale.ROOT),
                symbol.signature(),
                symbol.referenceCount(),
                session.program().name(),
                analysis == null
                        ? "Baseline synthetic metadata"
                        : analysis.functionsDiscovered() + " functions discovered · "
                        + analysis.referencesRecovered() + " references recovered"
        ));
        details.setCaretPosition(0);
    }
}
