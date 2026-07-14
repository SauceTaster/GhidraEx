package dev.ghidraex.intellij.editor;

import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.testFramework.LightVirtualFile;
import dev.ghidraex.viewstate.ViewStateReducer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/** ID-qualified editor projection; it deliberately does not claim project-filesystem semantics. */
public final class VirtualizedListingFile extends LightVirtualFile {
    private final ViewStateReducer.ViewContext context;
    private final ViewStateReducer.LocationRef initialLocation;
    private final String resourcePath;

    public VirtualizedListingFile(
            String displayName,
            ViewStateReducer.ViewContext context,
            ViewStateReducer.LocationRef initialLocation
    ) {
        super(displayName, PlainTextFileType.INSTANCE, "");
        this.context = Objects.requireNonNull(context, "context");
        this.initialLocation = Objects.requireNonNull(initialLocation, "initialLocation");
        this.resourcePath = "/ghidraex/listing/"
                + token(context.runtimeId()) + "/"
                + context.runtimeEpoch() + "/"
                + token(context.programId());
        setWritable(false);
    }

    public ViewStateReducer.ViewContext context() {
        return context;
    }

    public ViewStateReducer.LocationRef initialLocation() {
        return initialLocation;
    }

    public boolean identifies(ViewStateReducer.ViewContext candidate) {
        return ListingSemantics.sameResource(context, candidate);
    }

    @Override
    public String getPath() {
        return resourcePath == null ? super.getPath() : resourcePath;
    }

    private static String token(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
