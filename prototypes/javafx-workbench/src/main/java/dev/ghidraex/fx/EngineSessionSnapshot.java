package dev.ghidraex.fx;

import dev.ghidraex.engine.AnalysisEngine;
import dev.ghidraex.engine.ProgramInfo;
import dev.ghidraex.viewstate.ViewStateReducer.ViewContext;

import java.util.Objects;

/** Immutable metadata captured before JavaFX starts constructing controls. */
record EngineSessionSnapshot(ProgramInfo program, int listingSize, ViewContext context) {
    EngineSessionSnapshot {
        Objects.requireNonNull(program, "program");
        if (listingSize < 0) {
            throw new IllegalArgumentException("listingSize must not be negative");
        }
        Objects.requireNonNull(context, "context");
    }

    static EngineSessionSnapshot capture(AnalysisEngine engine) {
        Objects.requireNonNull(engine, "engine");
        ProgramInfo program = Objects.requireNonNull(engine.program(), "engine.program()");
        int listingSize = engine.listingSize();
        return new EngineSessionSnapshot(
                program,
                listingSize,
                new ViewContext("fixture-runtime", 1, program.sha256(), 0));
    }
}
