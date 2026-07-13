package dev.ghidraex.workbench.command;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** A UI-toolkit-independent action description. */
public record WorkbenchCommand(
        String id,
        String title,
        String category,
        String hint,
        BooleanSupplier enabled,
        Runnable action) {

    public WorkbenchCommand {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(category, "category");
        hint = Objects.requireNonNullElse(hint, "");
        enabled = Objects.requireNonNullElse(enabled, () -> true);
        Objects.requireNonNull(action, "action");
    }

    public WorkbenchCommand(String id, String title, String category, String hint, Runnable action) {
        this(id, title, category, hint, () -> true, action);
    }
}
