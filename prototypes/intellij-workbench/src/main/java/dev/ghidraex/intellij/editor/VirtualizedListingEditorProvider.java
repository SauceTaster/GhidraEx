package dev.ghidraex.intellij.editor;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import dev.ghidraex.viewstate.ViewStateReducer;
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
        var listingFile = (VirtualizedListingFile) file;
        var fallback = new VirtualizedListingEditor.State(
                listingFile.context(), listingFile.initialLocation());
        try {
            var context = new ViewStateReducer.ViewContext(
                    attribute(sourceElement, "runtime-id", fallback.context().runtimeId()),
                    longAttribute(sourceElement, "runtime-epoch", fallback.context().runtimeEpoch()),
                    attribute(sourceElement, "program-id", fallback.context().programId()),
                    longAttribute(sourceElement, "content-generation",
                            fallback.context().contentGeneration())
            );
            var requested = address(sourceElement, "requested", fallback.location().requestedAddress());
            var containing = address(sourceElement, "containing", fallback.location().containingAddress());
            var location = new ViewStateReducer.LocationRef(
                    requested,
                    containing,
                    intAttribute(sourceElement, "byte-offset", fallback.location().byteOffset()),
                    attribute(sourceElement, "field-id", fallback.location().fieldId())
            );
            return new VirtualizedListingEditor.State(context, location);
        } catch (IllegalArgumentException invalidState) {
            return fallback;
        }
    }

    @Override
    public void writeState(
            @NotNull FileEditorState state,
            @NotNull Project project,
            @NotNull Element targetElement
    ) {
        if (state instanceof VirtualizedListingEditor.State listingState) {
            var context = listingState.context();
            targetElement.setAttribute("runtime-id", context.runtimeId());
            targetElement.setAttribute("runtime-epoch", Long.toString(context.runtimeEpoch()));
            targetElement.setAttribute("program-id", context.programId());
            targetElement.setAttribute("content-generation",
                    Long.toString(context.contentGeneration()));
            writeAddress(targetElement, "requested", listingState.location().requestedAddress());
            writeAddress(targetElement, "containing", listingState.location().containingAddress());
            targetElement.setAttribute("byte-offset",
                    Integer.toString(listingState.location().byteOffset()));
            targetElement.setAttribute("field-id", listingState.location().fieldId());
        }
    }

    private static ViewStateReducer.AddressRef address(
            Element element,
            String prefix,
            ViewStateReducer.AddressRef fallback
    ) {
        return new ViewStateReducer.AddressRef(
                attribute(element, prefix + "-space-id", fallback.spaceId()),
                longAttribute(element, prefix + "-space-epoch", fallback.spaceEpoch()),
                attribute(element, prefix + "-offset-bits", fallback.offsetBits()),
                attribute(element, prefix + "-display", fallback.display())
        );
    }

    private static void writeAddress(
            Element element,
            String prefix,
            ViewStateReducer.AddressRef address
    ) {
        element.setAttribute(prefix + "-space-id", address.spaceId());
        element.setAttribute(prefix + "-space-epoch", Long.toString(address.spaceEpoch()));
        element.setAttribute(prefix + "-offset-bits", address.offsetBits());
        element.setAttribute(prefix + "-display", address.display());
    }

    private static String attribute(Element element, String name, String fallback) {
        return element.getAttributeValue(name, fallback);
    }

    private static long longAttribute(Element element, String name, long fallback) {
        try {
            return Long.parseLong(element.getAttributeValue(name, Long.toString(fallback)));
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static int intAttribute(Element element, String name, int fallback) {
        try {
            return Integer.parseInt(element.getAttributeValue(name, Integer.toString(fallback)));
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }
}
