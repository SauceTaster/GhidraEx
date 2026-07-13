package dev.ghidraex.engine;

import java.util.Locale;

/** Shared symbol-query semantics, intentionally independent of the UI toolkit. */
public final class SymbolMatcher {
    private SymbolMatcher() {
    }

    public static boolean matches(SymbolDescriptor symbol, String query) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return true;
        }
        String address = String.format(Locale.ROOT, "%08x", symbol.address());
        return symbol.name().toLowerCase(Locale.ROOT).contains(needle)
                || address.contains(needle)
                || symbol.kind().name().toLowerCase(Locale.ROOT).contains(needle)
                || symbol.signature().toLowerCase(Locale.ROOT).contains(needle);
    }
}
