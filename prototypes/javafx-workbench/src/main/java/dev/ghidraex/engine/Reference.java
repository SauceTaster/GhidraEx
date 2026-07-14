package dev.ghidraex.engine;

import java.util.Objects;

/** Immutable transport-safe reference; it owns no Ghidra domain object. */
public record Reference(long fromAddress, long toAddress, Kind kind) {
    public Reference {
        Objects.requireNonNull(kind, "kind");
    }

    public enum Kind {
        CALL,
        FLOW,
        READ,
        WRITE,
        DATA
    }
}
