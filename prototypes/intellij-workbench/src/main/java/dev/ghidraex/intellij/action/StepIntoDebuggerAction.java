package dev.ghidraex.intellij.action;

import dev.ghidraex.debug.DebuggerModel;
import dev.ghidraex.intellij.session.WorkbenchSession;

public final class StepIntoDebuggerAction extends DebuggerCommandAction {
    @Override
    protected void perform(WorkbenchSession session) {
        session.stepDebuggerInto();
    }

    @Override
    protected boolean isEnabled(DebuggerModel.Snapshot snapshot) {
        return snapshot.state() == DebuggerModel.State.SUSPENDED;
    }
}
