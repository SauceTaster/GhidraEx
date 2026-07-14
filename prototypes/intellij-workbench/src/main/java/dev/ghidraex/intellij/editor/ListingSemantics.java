package dev.ghidraex.intellij.editor;

import dev.ghidraex.viewstate.ViewStateReducer;

import java.util.Locale;

/** Small address codec used by the synthetic IntelliJ listing projection. */
public final class ListingSemantics {
    public static final String MEMORY_SPACE_ID = "ram";
    public static final long MEMORY_SPACE_EPOCH = 1;

    private ListingSemantics() {
    }

    public static ViewStateReducer.AddressRef memoryAddress(long address) {
        return new ViewStateReducer.AddressRef(
                MEMORY_SPACE_ID,
                MEMORY_SPACE_EPOCH,
                Long.toUnsignedString(address, 16),
                String.format(Locale.ROOT, "ram:%016x", address)
        );
    }

    public static ViewStateReducer.LocationRef instructionLocation(long address) {
        var ref = memoryAddress(address);
        return new ViewStateReducer.LocationRef(ref, ref, 0, "mnemonic");
    }

    public static long offset(ViewStateReducer.AddressRef address) {
        return Long.parseUnsignedLong(address.offsetBits(), 16);
    }

    public static boolean sameResource(
            ViewStateReducer.ViewContext left,
            ViewStateReducer.ViewContext right
    ) {
        return left.runtimeId().equals(right.runtimeId())
                && left.runtimeEpoch() == right.runtimeEpoch()
                && left.programId().equals(right.programId());
    }
}
