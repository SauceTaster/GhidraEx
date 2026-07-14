package dev.ghidraex.intellij.editor;

import dev.ghidraex.engine.AnalysisEngine;
import dev.ghidraex.engine.ProgramDescriptor;
import dev.ghidraex.viewstate.AsyncListingController;
import dev.ghidraex.viewstate.ViewStateReducer;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Adapts the synthetic ordinal engine to one bounded semantic window on a worker thread. */
final class SyntheticListingWindowSource implements AsyncListingController.ListingLoader {
    private static final int SYNTHETIC_INSTRUCTION_SIZE = 4;

    private final AnalysisEngine engine;
    private final ProgramDescriptor program;

    SyntheticListingWindowSource(AnalysisEngine engine, ProgramDescriptor program) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.program = Objects.requireNonNull(program, "program");
    }

    @Override
    public ViewStateReducer.ListingWindow load(ViewStateReducer.ViewRequest request) {
        if (!program.id().equals(request.context().programId())) {
            return emptyPartial(request, "Program identity is not available in the synthetic provider");
        }

        long containingOffset;
        try {
            containingOffset = ListingSemantics.offset(request.anchor().containingAddress());
        } catch (NumberFormatException invalidOffset) {
            return emptyPartial(request, "Address offset is not representable by the synthetic provider");
        }

        int instructionCount = engine.instructionCount(program.id());
        long relative = containingOffset - program.imageBase();
        if (relative < 0 || relative / SYNTHETIC_INSTRUCTION_SIZE >= instructionCount) {
            return emptyPartial(request, "Address is outside the synthetic listing coverage");
        }

        int anchorOrdinal = (int) (relative / SYNTHETIC_INSTRUCTION_SIZE);
        int start = Math.max(0, anchorOrdinal - request.limit() / 3);
        var instructions = engine.listing(program.id(), start, request.limit());
        List<ViewStateReducer.ListingRow> rows = instructions.stream().map(instruction -> {
            var location = ListingSemantics.instructionLocation(instruction.address());
            String operands = instruction.label().isEmpty()
                    ? instruction.operands()
                    : instruction.operands() + "    ; " + instruction.label();
            return new ViewStateReducer.ListingRow(
                    request.context().programId() + ":ram:"
                            + Long.toUnsignedString(instruction.address(), 16) + ":instruction",
                    ViewStateReducer.RowKind.INSTRUCTION,
                    location,
                    Map.of(
                            "address", location.requestedAddress().display(),
                            "bytes", instruction.bytes(),
                            "mnemonic", instruction.mnemonic(),
                            "operands", operands
                    )
            );
        }).toList();

        return new ViewStateReducer.ListingWindow(
                request.viewId(),
                request.requestId(),
                request.context(),
                "intellij-synthetic-" + request.context().contentGeneration()
                        + "-" + request.requestId(),
                request.anchor(),
                rows,
                ViewStateReducer.Completeness.COMPLETE,
                List.of()
        );
    }

    private static ViewStateReducer.ListingWindow emptyPartial(
            ViewStateReducer.ViewRequest request,
            String warning
    ) {
        return new ViewStateReducer.ListingWindow(
                request.viewId(),
                request.requestId(),
                request.context(),
                "intellij-synthetic-partial-" + request.requestId(),
                request.anchor(),
                List.of(),
                ViewStateReducer.Completeness.PARTIAL,
                List.of(warning)
        );
    }
}
