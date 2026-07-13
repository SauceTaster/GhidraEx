package dev.ghidraex.intellij.editor;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;

public final class VirtualizedListingEditorProvider implements FileEditorProvider, DumbAware {
    private static final String EDITOR_TYPE_ID = "ghidraex-virtualized-listing";

    @Override
    public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
        return file instanceof VirtualizedListingFile;
    }

    @Override
    public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
        return new VirtualizedListingEditor(project, (VirtualizedListingFile) file);
    }

    @Override
    public @NotNull String getEditorTypeId() {
        return EDITOR_TYPE_ID;
    }

    @Override
    public @NotNull FileEditorPolicy getPolicy() {
        return FileEditorPolicy.HIDE_DEFAULT_EDITOR;
    }

    @Override
    public @NotNull FileEditorState readState(
            @NotNull Element sourceElement,
            @NotNull Project project,
            @NotNull VirtualFile file
    ) {
        int row;
        try {
            row = Integer.parseInt(sourceElement.getAttributeValue("selected-row", "0"));
        } catch (NumberFormatException ignored) {
            row = 0;
        }
        return new VirtualizedListingEditor.State(row);
    }

    @Override
    public void writeState(
            @NotNull FileEditorState state,
            @NotNull Project project,
            @NotNull Element targetElement
    ) {
        if (state instanceof VirtualizedListingEditor.State listingState) {
            targetElement.setAttribute("selected-row", Integer.toString(listingState.selectedRow()));
        }
    }
}
