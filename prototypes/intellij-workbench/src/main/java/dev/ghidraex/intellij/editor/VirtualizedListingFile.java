package dev.ghidraex.intellij.editor;

import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.testFramework.LightVirtualFile;

/** Marker virtual file routed to the paged listing editor instead of a text editor. */
public final class VirtualizedListingFile extends LightVirtualFile {
    public VirtualizedListingFile(String name) {
        super(name, PlainTextFileType.INSTANCE, "");
        setWritable(false);
    }
}
