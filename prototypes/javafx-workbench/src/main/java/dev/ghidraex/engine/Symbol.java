package dev.ghidraex.engine;

public record Symbol(String name, long address, Kind kind, String namespace) {
    public enum Kind {
        FUNCTION,
        IMPORT,
        DATA,
        LABEL
    }

    public String formattedAddress() {
        return "%016x".formatted(address);
    }
}
