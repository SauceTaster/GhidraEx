package dev.ghidraex.intellij.action;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import dev.ghidraex.engine.AnalysisMonitor;
import dev.ghidraex.engine.AnalysisResult;
import dev.ghidraex.intellij.session.WorkbenchSession;
import org.jetbrains.annotations.NotNull;

public final class RunAnalysisAction extends DumbAwareAction {
    public static final String ID = "dev.ghidraex.intellij.RunAnalysis";
    private static final String NOTIFICATION_GROUP = "GhidraEx Analysis";

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        var project = event.getProject();
        if (project == null) {
            return;
        }

        var session = WorkbenchSession.getInstance(project);
        new Task.Backgroundable(project, "Analyzing " + session.program().name(), true) {
            private volatile AnalysisResult result;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                result = session.engine().analyze(session.program().id(), new AnalysisMonitor() {
                    @Override
                    public void checkCancelled() {
                        indicator.checkCanceled();
                    }

                    @Override
                    public void report(double fraction, String phase, String detail) {
                        indicator.setFraction(fraction);
                        indicator.setText(phase);
                        indicator.setText2(detail);
                    }
                });
            }

            @Override
            public void onSuccess() {
                session.applyAnalysis(result);
                NotificationGroupManager.getInstance()
                        .getNotificationGroup(NOTIFICATION_GROUP)
                        .createNotification(
                                "Analysis complete",
                                result.functionsDiscovered() + " functions and "
                                        + result.referencesRecovered() + " references recovered.",
                                NotificationType.INFORMATION
                        )
                        .notify(project);
            }

            @Override
            public void onCancel() {
                NotificationGroupManager.getInstance()
                        .getNotificationGroup(NOTIFICATION_GROUP)
                        .createNotification("Analysis cancelled", NotificationType.WARNING)
                        .notify(project);
            }
        }.queue();
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null);
    }
}
