package dev.ghidraex.intellij.session;

import com.intellij.util.messages.Topic;

public interface SessionListener {
    Topic<SessionListener> TOPIC = Topic.create("GhidraEx workbench session", SessionListener.class);

    void sessionChanged(WorkbenchSession session);
}
