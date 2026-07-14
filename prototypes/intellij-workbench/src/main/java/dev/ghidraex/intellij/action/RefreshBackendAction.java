package dev.ghidraex.intellij.action;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import dev.ghidraex.intellij.session.WorkbenchSession;
import org.jetbrains.annotations.NotNull;

public final class RefreshBackendAction extends DumbAwareAction {
    private static final String NOTIFICATION_GROUP = "GhidraEx Analysis";

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        if (project == null) {
            return;
        }
        WorkbenchSession.getInstance(project).refreshBackendStatus();
        LabNavigation.show(project, LabNavigation.RUNTIME_CONTENT);
        NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(
                        "Backend discovery queued",
                        "The runtime view will apply only the latest context-qualified result.",
                        NotificationType.INFORMATION
                )
                .notify(project);
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null);
    }
}
