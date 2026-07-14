package dev.ghidraex.viewstate;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.ghidraex.viewstate.ViewStateReducer.Acceptance.ACCEPTED;
import static dev.ghidraex.viewstate.ViewStateReducer.Acceptance.IGNORED_CONTEXT;
import static dev.ghidraex.viewstate.ViewStateReducer.Acceptance.IGNORED_DISPOSED;
import static dev.ghidraex.viewstate.ViewStateReducer.Acceptance.IGNORED_DISPOSED_VIEW;
import static dev.ghidraex.viewstate.ViewStateReducer.Acceptance.IGNORED_LIMIT;
import static dev.ghidraex.viewstate.ViewStateReducer.Acceptance.IGNORED_RESYNC_REQUIRED;
import static dev.ghidraex.viewstate.ViewStateReducer.Acceptance.IGNORED_SUPERSEDED;
import static dev.ghidraex.viewstate.ViewStateReducer.Completeness.COMPLETE;
import static dev.ghidraex.viewstate.ViewStateReducer.EventAcceptance.GAP_REQUIRES_RESYNC;
import static dev.ghidraex.viewstate.ViewStateReducer.EventAcceptance.INCONSISTENT_REQUIRES_RESYNC;
import static dev.ghidraex.viewstate.ViewStateReducer.EventAcceptance.QUARANTINED_REQUIRES_SNAPSHOT;
import static dev.ghidraex.viewstate.ViewStateReducer.Freshness.CURRENT;
import static dev.ghidraex.viewstate.ViewStateReducer.Freshness.FAILED;
import static dev.ghidraex.viewstate.ViewStateReducer.Freshness.RESYNCING;
import static dev.ghidraex.viewstate.ViewStateReducer.Freshness.STALE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewStateReducerTest {
    private final ViewStateReducer.ViewContext generation17 =
            new ViewStateReducer.ViewContext("runtime-local", 3, "orbit-controller", 17);
    private final ViewStateReducer.AddressRef ram1000 = address("ram", "1000", "ram:00001000");
    private final ViewStateReducer.AddressRef ram1001 = address("ram", "1001", "ram:00001001");
    private final ViewStateReducer.LocationRef instruction =
            new ViewStateReducer.LocationRef(ram1000, ram1000, 0, "mnemonic");
    private final ViewStateReducer.LocationRef offcut =
            new ViewStateReducer.LocationRef(ram1001, ram1000, 1, "bytes");

    @Test
    void spine01LatestRequestWinsWhenResponsesReverse() {
        var reducer = reducer();
        var a = reducer.beginRequest("listing", instruction, 512);
        var b = reducer.beginRequest("listing", instruction, 512);
        var c = reducer.beginRequest("listing", instruction, 512);

        assertEquals(IGNORED_SUPERSEDED, reducer.complete(window(b, COMPLETE)));
        assertEquals(IGNORED_SUPERSEDED, reducer.complete(window(a, COMPLETE)));
        assertEquals(ACCEPTED, reducer.complete(window(c, COMPLETE)));
        assertEquals(CURRENT, reducer.view("listing").orElseThrow().freshness());
        assertEquals(c.requestId(), reducer.view("listing").orElseThrow().displayed().requestId());
    }

    @Test
    void spine02OldGenerationCannotReplaceStaleView() {
        var reducer = reducer();
        var original = reducer.beginRequest("listing", instruction, 512);
        assertEquals(ACCEPTED, reducer.complete(window(original, COMPLETE)));
        reducer.acceptEvent(1, 18);

        var staleResponse = new ViewStateReducer.ListingWindow(
                "listing", 99, generation17, "result-old", instruction,
                List.of(row("instruction", ViewStateReducer.RowKind.INSTRUCTION, instruction)),
                COMPLETE, List.of());
        var currentRequest = reducer.beginRequest("listing", instruction, 512);
        staleResponse = new ViewStateReducer.ListingWindow(
                "listing", currentRequest.requestId(), generation17, "result-old", instruction,
                staleResponse.rows(), COMPLETE, List.of());

        assertEquals(IGNORED_CONTEXT, reducer.complete(staleResponse));
        assertEquals(STALE, reducer.view("listing").orElseThrow().freshness());
        assertEquals("result-" + original.requestId(),
                reducer.view("listing").orElseThrow().displayed().resultId());
    }

    @Test
    void spine03And04GapRequiresAtomicSnapshotThenRefresh() {
        var reducer = reducer();
        var first = reducer.beginRequest("listing", instruction, 512);
        reducer.complete(window(first, COMPLETE));
        reducer.acceptEvent(41, 17);

        assertEquals(GAP_REQUIRES_RESYNC, reducer.acceptEvent(43, 18));
        assertTrue(reducer.snapshot().resyncRequired());
        assertEquals(RESYNCING, reducer.view("listing").orElseThrow().freshness());
        assertEquals("result-" + first.requestId(),
                reducer.view("listing").orElseThrow().displayed().resultId());
        assertEquals(QUARANTINED_REQUIRES_SNAPSHOT, reducer.acceptEvent(42, 18));
        assertThrows(IllegalStateException.class,
                () -> reducer.beginRequest("listing", instruction, 512));

        var generation18 = new ViewStateReducer.ViewContext("runtime-local", 3, "orbit-controller", 18);
        reducer.applySnapshot(generation18, 44);
        assertFalse(reducer.snapshot().resyncRequired());
        assertEquals(STALE, reducer.view("listing").orElseThrow().freshness());
        var refresh = reducer.beginRequest("listing", instruction, 512);
        assertEquals(ACCEPTED, reducer.complete(window(refresh, COMPLETE)));
        assertEquals(CURRENT, reducer.view("listing").orElseThrow().freshness());
        assertEquals(18, reducer.view("listing").orElseThrow().displayed().context().contentGeneration());
    }

    @Test
    void spine05And06SpaceIdentityAndOffcutsSurvive() {
        var overlay = address("overlay:text", "1000", "overlay:text:00001000");
        var overlayLocation = new ViewStateReducer.LocationRef(overlay, overlay, 0, "mnemonic");
        var differentlyFormattedRam = address("ram", "1000", "0x1000");
        var differentlyPaddedRam = address("ram", "00001000", "ram:00001000");

        assertNotEquals(instruction, overlayLocation);
        assertEquals(ram1000, differentlyFormattedRam);
        assertEquals(ram1000, differentlyPaddedRam);
        assertTrue(offcut.offcut());
        assertEquals(ram1001, offcut.requestedAddress());
        assertEquals(ram1000, offcut.containingAddress());
        assertEquals(1, offcut.byteOffset());
    }

    @Test
    void spine07And08RowKindsAndPartialStateAreExplicit() {
        var reducer = reducer();
        var request = reducer.beginRequest("listing", offcut, 512);
        var result = new ViewStateReducer.ListingWindow(
                "listing",
                request.requestId(),
                request.context(),
                "partial-result",
                request.anchor(),
                List.of(
                        row("insn", ViewStateReducer.RowKind.INSTRUCTION, instruction),
                        row("data", ViewStateReducer.RowKind.DATA, offcut),
                        row("gap", ViewStateReducer.RowKind.GAP, offcut)),
                ViewStateReducer.Completeness.PARTIAL,
                List.of("coverage ends at unmapped overlay"));

        assertEquals(ACCEPTED, reducer.complete(result));
        var view = reducer.view("listing").orElseThrow();
        assertEquals(ViewStateReducer.Freshness.PARTIAL, view.freshness());
        assertEquals(List.of(
                        ViewStateReducer.RowKind.INSTRUCTION,
                        ViewStateReducer.RowKind.DATA,
                        ViewStateReducer.RowKind.GAP),
                view.displayed().rows().stream().map(ViewStateReducer.ListingRow::kind).toList());
    }

    @Test
    void spine09NavigationDoesNotOverwriteSelectionOrHighlights() {
        var reducer = reducer();
        reducer.setSelection(Set.of(instruction));
        reducer.setHighlight("user", Set.of(offcut));
        reducer.setHighlight("agent", Set.of(instruction));
        reducer.navigate(offcut);

        var snapshot = reducer.snapshot();
        assertEquals(offcut, snapshot.location());
        assertEquals(Set.of(instruction), snapshot.selection());
        assertEquals(Set.of(offcut), snapshot.highlights().get("user"));
        assertEquals(Set.of(instruction), snapshot.highlights().get("agent"));
    }

    @Test
    void spine10DisposedViewIgnoresCompletion() {
        var reducer = reducer();
        var request = reducer.beginRequest("listing", instruction, 512);
        reducer.disposeView("listing");

        assertEquals(IGNORED_DISPOSED_VIEW, reducer.complete(window(request, COMPLETE)));
        assertFalse(reducer.disposed());
        assertThrows(IllegalStateException.class,
                () -> reducer.beginRequest("listing", instruction, 512));
        assertTrue(reducer.view("listing").isEmpty());

        reducer.registerView("listing", true);
        var replacement = reducer.beginRequest("listing", instruction, 512);
        assertEquals(IGNORED_SUPERSEDED, reducer.complete(window(request, COMPLETE)));
        assertEquals(ACCEPTED, reducer.complete(window(replacement, COMPLETE)));
    }

    @Test
    void disposedWorkbenchIgnoresAllCompletions() {
        var reducer = reducer();
        var request = reducer.beginRequest("listing", instruction, 512);
        reducer.dispose();

        assertEquals(IGNORED_DISPOSED, reducer.complete(window(request, COMPLETE)));
        assertTrue(reducer.disposed());
    }

    @Test
    void boundedWindowCannotExceedItsRequestedLimit() {
        var reducer = reducer();
        var request = reducer.beginRequest("listing", instruction, 1);
        var oversized = new ViewStateReducer.ListingWindow(
                request.viewId(),
                request.requestId(),
                request.context(),
                "oversized",
                request.anchor(),
                List.of(
                        row("instruction-1", ViewStateReducer.RowKind.INSTRUCTION, instruction),
                        row("instruction-2", ViewStateReducer.RowKind.INSTRUCTION, offcut)),
                COMPLETE,
                List.of());

        assertEquals(IGNORED_LIMIT, reducer.complete(oversized));
        assertNull(reducer.view("listing").orElseThrow().pending());
        assertEquals(FAILED, reducer.view("listing").orElseThrow().freshness());
        assertEquals(IGNORED_SUPERSEDED, reducer.complete(window(request, COMPLETE)));
    }

    @Test
    void duplicateSemanticRowIdsAreRejectedAtTheBoundary() {
        var reducer = reducer();
        var request = reducer.beginRequest("listing", instruction, 2);

        assertThrows(IllegalArgumentException.class, () -> new ViewStateReducer.ListingWindow(
                request.viewId(),
                request.requestId(),
                request.context(),
                "duplicates",
                request.anchor(),
                List.of(
                        row("same", ViewStateReducer.RowKind.INSTRUCTION, instruction),
                        row("same", ViewStateReducer.RowKind.DATA, offcut)),
                COMPLETE,
                List.of()));
    }

    @Test
    void generationRegressionRequiresResyncAndDisablesAuthority() {
        var reducer = reducer();
        reducer.acceptEvent(41, 17);

        assertEquals(INCONSISTENT_REQUIRES_RESYNC, reducer.acceptEvent(42, 16));
        assertEquals(RESYNCING, reducer.view("listing").orElseThrow().freshness());
        assertFalse(reducer.authoritativeActionsAllowed());

        reducer.applySnapshot(generation17, 42);
        assertTrue(reducer.authoritativeActionsAllowed());
    }

    @Test
    void runtimeIdentityChangeMayResetSequenceAndClearsRuntimeLocalLocations() {
        var reducer = reducer();
        var request = reducer.beginRequest("listing", instruction, 8);
        reducer.complete(window(request, COMPLETE));
        reducer.setSelection(Set.of(instruction));
        reducer.setHighlight("agent", Set.of(offcut));
        reducer.acceptEvent(41, 17);

        var restarted = new ViewStateReducer.ViewContext("runtime-local", 4, "orbit-controller", 17);
        reducer.applySnapshot(restarted, 0);

        assertEquals(restarted, reducer.snapshot().context());
        assertEquals(0, reducer.snapshot().eventSequence());
        assertNull(reducer.snapshot().location());
        assertTrue(reducer.snapshot().selection().isEmpty());
        assertTrue(reducer.snapshot().highlights().isEmpty());
        assertNull(reducer.view("listing").orElseThrow().target());
        assertNull(reducer.view("listing").orElseThrow().displayed());
    }

    @Test
    void postGapCompletionCannotEscapeResyncing() {
        var reducer = reducer();
        var pending = reducer.beginRequest("listing", instruction, 8);
        reducer.acceptEvent(41, 17);
        assertEquals(GAP_REQUIRES_RESYNC, reducer.acceptEvent(43, 18));

        assertEquals(IGNORED_RESYNC_REQUIRED, reducer.complete(window(pending, COMPLETE)));
        assertEquals(RESYNCING, reducer.view("listing").orElseThrow().freshness());
        assertTrue(reducer.snapshot().resyncRequired());
    }

    @Test
    void conflictingDuplicateEventRequiresSnapshotAndQuarantinesIncrementals() {
        var reducer = reducer();
        reducer.acceptEvent(41, 17);

        assertEquals(INCONSISTENT_REQUIRES_RESYNC, reducer.acceptEvent(41, 18));
        assertEquals(QUARANTINED_REQUIRES_SNAPSHOT, reducer.acceptEvent(42, 18));
        assertEquals(RESYNCING, reducer.view("listing").orElseThrow().freshness());
    }

    @Test
    void fullSnapshotCannotRegressEpochGenerationOrRuntimeSequence() {
        var reducer = reducer();
        reducer.acceptEvent(41, 17);

        assertThrows(IllegalArgumentException.class, () -> reducer.applySnapshot(
                new ViewStateReducer.ViewContext("runtime-local", 2, "orbit-controller", 17), 0));
        assertThrows(IllegalArgumentException.class, () -> reducer.applySnapshot(
                new ViewStateReducer.ViewContext("runtime-local", 3, "orbit-controller", 16), 42));
        assertThrows(IllegalArgumentException.class, () -> reducer.applySnapshot(
                new ViewStateReducer.ViewContext("runtime-local", 3, "other-program", 0), 0));
    }

    @Test
    void programSwitchKeepsRuntimeSequenceButClearsTheOldProgramWindow() {
        var reducer = reducer();
        var request = reducer.beginRequest("listing", instruction, 8);
        reducer.complete(window(request, COMPLETE));
        reducer.acceptEvent(41, 17);

        var otherProgram = new ViewStateReducer.ViewContext("runtime-local", 3, "other-program", 0);
        reducer.applySnapshot(otherProgram, 42);

        assertEquals(42, reducer.snapshot().eventSequence());
        assertNull(reducer.view("listing").orElseThrow().displayed());
        assertNull(reducer.view("listing").orElseThrow().target());
    }

    @Test
    void offcutCannotCrossAddressSpaceEpochs() {
        var overlay = address("overlay:text", "1001", "overlay:text:00001001");

        assertThrows(IllegalArgumentException.class,
                () -> new ViewStateReducer.LocationRef(overlay, ram1000, 1, "bytes"));
    }

    @Test
    void navigationWithinDisplayedWindowStaysCurrentAndOutsideNavigationCancelsPending() {
        var reducer = reducer();
        var ram1004 = address("ram", "1004", "ram:00001004");
        var ram1008 = address("ram", "1008", "ram:00001008");
        var second = new ViewStateReducer.LocationRef(ram1004, ram1004, 0, "mnemonic");
        var outside = new ViewStateReducer.LocationRef(ram1008, ram1008, 0, "mnemonic");
        var request = reducer.beginRequest("listing", instruction, 8);
        var window = new ViewStateReducer.ListingWindow(
                request.viewId(), request.requestId(), request.context(), "two-rows", request.anchor(),
                List.of(
                        row("first", ViewStateReducer.RowKind.INSTRUCTION, instruction),
                        row("second", ViewStateReducer.RowKind.INSTRUCTION, second)),
                COMPLETE, List.of());
        reducer.complete(window);

        reducer.navigate(second);
        assertEquals(CURRENT, reducer.view("listing").orElseThrow().freshness());
        var refresh = reducer.beginRequest("listing", second, 8);
        reducer.navigate(outside);
        assertNull(reducer.view("listing").orElseThrow().pending());
        assertEquals(STALE, reducer.view("listing").orElseThrow().freshness());
        assertEquals(IGNORED_SUPERSEDED, reducer.complete(window(refresh, COMPLETE)));
    }

    @Test
    void connectedViewRegisteredAfterNavigationInheritsTheLocation() {
        var reducer = new ViewStateReducer(generation17);
        reducer.navigate(offcut);
        reducer.registerView("listing", true);

        assertEquals(offcut, reducer.view("listing").orElseThrow().target());
    }

    private ViewStateReducer reducer() {
        var reducer = new ViewStateReducer(generation17);
        reducer.registerView("listing", true);
        reducer.navigate(instruction);
        return reducer;
    }

    private ViewStateReducer.ListingWindow window(
            ViewStateReducer.ViewRequest request,
            ViewStateReducer.Completeness completeness) {
        return new ViewStateReducer.ListingWindow(
                request.viewId(),
                request.requestId(),
                request.context(),
                "result-" + request.requestId(),
                request.anchor(),
                List.of(row("instruction", ViewStateReducer.RowKind.INSTRUCTION, request.anchor())),
                completeness,
                List.of());
    }

    private static ViewStateReducer.ListingRow row(
            String id,
            ViewStateReducer.RowKind kind,
            ViewStateReducer.LocationRef location) {
        return new ViewStateReducer.ListingRow(
                id,
                kind,
                location,
                Map.of("address", location.requestedAddress().display(), "mnemonic", "mov"));
    }

    private static ViewStateReducer.AddressRef address(String space, String bits, String display) {
        return new ViewStateReducer.AddressRef(space, 1, bits, display);
    }
}
