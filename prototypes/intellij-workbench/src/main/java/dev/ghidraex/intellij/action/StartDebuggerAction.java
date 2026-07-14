package dev.ghidraex.intellij.action;

import dev.ghidraex.debug.DebuggerModel;
import dev.ghidraex.intellij.session.WorkbenchSession;

public final class StartDebuggerAction extends DebuggerCommandAction {
    @Override
    protected void perform(WorkbenchSession session) {
        session.startDebugger();
    }

    @Override
    protected boolean isEnabled(DebuggerModel.Snapshot snapshot) {
        return snapshot.state() == DebuggerModel.State.INACTIVE
                || snapshot.state() == DebuggerModel.State.TERMINATED;
    }
}
