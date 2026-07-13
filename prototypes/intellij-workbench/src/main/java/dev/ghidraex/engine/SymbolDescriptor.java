package dev.ghidraex.engine;

public record SymbolDescriptor(
        String name,
        long address,
        SymbolKind kind,
        int referenceCount,
        String signature
) {
    public enum SymbolKind {
        FUNCTION,
        LABEL,
        DATA,
        IMPORT
    }
}
