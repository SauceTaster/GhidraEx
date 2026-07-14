package dev.ghidraex.fx;

import dev.ghidraex.engine.AnalysisEngine;
import dev.ghidraex.engine.DisassemblyLine;
import dev.ghidraex.engine.ListingWindow;
import dev.ghidraex.engine.ProgramInfo;
import dev.ghidraex.viewstate.ViewStateReducer;
import dev.ghidraex.viewstate.ViewStateReducer.AddressRef;
import dev.ghidraex.viewstate.ViewStateReducer.Completeness;
import dev.ghidraex.viewstate.ViewStateReducer.ListingRow;
import dev.ghidraex.viewstate.ViewStateReducer.LocationRef;
import dev.ghidraex.viewstate.ViewStateReducer.RowKind;
import dev.ghidraex.viewstate.ViewStateReducer.ViewContext;
import dev.ghidraex.viewstate.ViewStateReducer.ViewRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Adapts the deterministic fixture engine to the bounded semantic view-state contract. */
final class SyntheticSemanticListingProvider implements AsyncListingProjection.Provider {
    private static final String SPACE_ID = "fixture:ram";
    private static final long SPACE_EPOCH = 1;
    private static final int ROW_WIDTH = 4;
    private static final int ROWS_BEFORE_ANCHOR = 7;

    private final AnalysisEngine engine;
    private final ProgramInfo program;
    private final ViewContext initialContext;

    SyntheticSemanticListingProvider(AnalysisEngine engine, EngineSessionSnapshot session) {
        this.engine = Objects.requireNonNull(engine, "engine");
        EngineSessionSnapshot safeSession = Objects.requireNonNull(session, "session");
        program = safeSession.program();
        initialContext = safeSession.context();
    }

    ViewContext initialContext() {
        return initialContext;
    }

    LocationRef locationFor(long address) {
        long delta = address - program.imageBase();
        int byteOffset = delta >= 0 ? (int) Math.floorMod(delta, ROW_WIDTH) : 0;
        long containing = address - byteOffset;
        AddressRef requestedAddress = address(address);
        AddressRef containingAddress = address(containing);
        return new LocationRef(
                requestedAddress,
                containingAddress,
                byteOffset,
                byteOffset == 0 ? "mnemonic" : "bytes");
    }

    @Override
    public ViewStateReducer.ListingWindow load(ViewRequest request) {
        verifyContext(request.context());
        long anchorAddress = parseAddress(request.anchor().containingAddress());
        long delta = anchorAddress - program.imageBase();
        if (delta < 0 || Math.floorMod(delta, ROW_WIDTH) != 0) {
            throw new IllegalArgumentException("Listing anchor is outside the synthetic program");
        }
        long anchorIndex = delta / ROW_WIDTH;
        if (anchorIndex > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Listing anchor cannot be represented by the fixture engine");
        }
        int startIndex = (int) Math.max(0, anchorIndex - ROWS_BEFORE_ANCHOR);
        ListingWindow source = engine.listingWindow(startIndex, request.limit());
        List<ListingRow> rows = new ArrayList<>(source.rows().size());
        for (DisassemblyLine line : source.rows()) {
            rows.add(toSemanticRow(request.context(), line));
        }

        boolean unexpectedlyShort = rows.size() < request.limit()
                && source.endIndexExclusive() < source.totalInstructions();
        Completeness completeness = unexpectedlyShort ? Completeness.PARTIAL : Completeness.COMPLETE;
        List<String> warnings = unexpectedlyShort
                ? List.of("Fixture provider returned less than the requested bounded window")
                : List.of();
        String resultId = "fixture-listing:"
                + request.context().contentGeneration() + ":" + request.requestId();
        return new ViewStateReducer.ListingWindow(
                request.viewId(),
                request.requestId(),
                request.context(),
                resultId,
                request.anchor(),
                rows,
                completeness,
                warnings);
    }

    private ListingRow toSemanticRow(ViewContext context, DisassemblyLine line) {
        LocationRef location = new LocationRef(address(line.address()), address(line.address()), 0, "mnemonic");
        return new ListingRow(
                context.contentGeneration() + ":instruction:" + Long.toUnsignedString(line.address(), 16),
                RowKind.INSTRUCTION,
                location,
                Map.of(
                        "index", Integer.toString(line.index()),
                        "address", line.formattedAddress(),
                        "bytes", line.bytes(),
                        "mnemonic", line.mnemonic(),
                        "operands", line.operands(),
                        "comment", line.comment(),
                        "flow", line.flowType().name()));
    }

    private void verifyContext(ViewContext context) {
        if (!initialContext.runtimeId().equals(context.runtimeId())
                || initialContext.runtimeEpoch() != context.runtimeEpoch()
                || !initialContext.programId().equals(context.programId())) {
            throw new IllegalArgumentException("Listing request belongs to a different fixture runtime");
        }
    }

    private static AddressRef address(long value) {
        return new AddressRef(
                SPACE_ID,
                SPACE_EPOCH,
                Long.toUnsignedString(value, 16),
                "0x%016x".formatted(value));
    }

    private static long parseAddress(AddressRef address) {
        if (!SPACE_ID.equals(address.spaceId()) || address.spaceEpoch() != SPACE_EPOCH) {
            throw new IllegalArgumentException("Unsupported fixture address space");
        }
        return Long.parseUnsignedLong(address.offsetBits(), 16);
    }

}
