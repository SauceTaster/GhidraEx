package dev.ghidraex.intellij.toolwindow;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.content.ContentFactory;
import com.intellij.openapi.ui.SimpleToolWindowPanel;

import javax.swing.JComponent;

final class ToolWindowSupport {
    private ToolWindowSupport() {
    }

    static void install(ToolWindow toolWindow, JComponent component, String toolbarPlace, String... actionIds) {
        installContent(toolWindow, component, "", toolbarPlace, actionIds);
    }

    static void installContent(
            ToolWindow toolWindow,
            JComponent component,
            String displayName,
            String toolbarPlace,
            String... actionIds
    ) {
        var panel = new SimpleToolWindowPanel(true, true);
        var group = new DefaultActionGroup();
        var actionManager = ActionManager.getInstance();
        for (var actionId : actionIds) {
            AnAction action = actionManager.getAction(actionId);
            if (action != null) {
                group.add(action);
            }
        }
        ActionToolbar toolbar = actionManager.createActionToolbar(toolbarPlace, group, true);
        toolbar.setTargetComponent(component);
        panel.setToolbar(toolbar.getComponent());
        panel.setContent(component);
        toolWindow.getContentManager().addContent(
                ContentFactory.getInstance().createContent(panel, displayName, false)
        );
    }
}
