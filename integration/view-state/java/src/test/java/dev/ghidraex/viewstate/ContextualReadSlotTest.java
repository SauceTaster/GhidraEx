package dev.ghidraex.viewstate;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextualReadSlotTest {
    private final ViewStateReducer.ViewContext generation17 =
            new ViewStateReducer.ViewContext("runtime", 3, "program", 17);

    @Test
    void latestSemanticQueryWinsWhenResultsReverse() {
        var slot = slot();
        var first = slot.begin(generation17, "decode", false);
        var second = slot.begin(generation17, "dispatch", false);

        assertEquals(ContextualReadSlot.Acceptance.IGNORED_SUPERSEDED,
                slot.complete(first, loaded("decode", "old"), generation17, false));
        assertEquals(ContextualReadSlot.Acceptance.ACCEPTED,
                slot.complete(second, loaded("dispatch", "new"), generation17, false));
        assertEquals(ContextualReadSlot.Freshness.CURRENT, slot.snapshot().freshness());
        assertEquals(List.of("new"), slot.snapshot().displayed().value());
    }

    @Test
    void generationMismatchIsTerminalAndRetainsTheCoherentResultAsStale() {
        var slot = slot();
        var original = slot.begin(generation17, "decode", false);
        slot.complete(original, loaded("decode", "current"), generation17, false);
        var refresh = slot.begin(generation17, "decode", false);
        var generation18 = new ViewStateReducer.ViewContext("runtime", 3, "program", 18);

        assertEquals(ContextualReadSlot.Acceptance.IGNORED_CONTEXT,
                slot.complete(refresh, loaded("decode", "wrong"), generation18, false));
        assertEquals(ContextualReadSlot.Freshness.STALE, slot.snapshot().freshness());
        assertEquals(List.of("current"), slot.snapshot().displayed().value());
        assertNull(slot.snapshot().pending());
    }

    @Test
    void providerMustEchoItsActualContextAndNormalizedQuery() {
        var wrongContextSlot = slot();
        var contextRequest = wrongContextSlot.begin(generation17, "decode", false);
        var generation18 = new ViewStateReducer.ViewContext("runtime", 3, "program", 18);
        var wrongContext = new ContextualReadSlot.LoadedResult<>(
                generation18, "decode", "wrong-context", List.of("unsafe"),
                ContextualReadSlot.Completeness.COMPLETE, List.of());
        assertEquals(ContextualReadSlot.Acceptance.IGNORED_CONTEXT,
                wrongContextSlot.complete(contextRequest, wrongContext, generation17, false));

        var wrongQuerySlot = slot();
        var queryRequest = wrongQuerySlot.begin(generation17, "decode", false);
        var wrongQuery = new ContextualReadSlot.LoadedResult<>(
                generation17, "dispatch", "wrong-query", List.of("unsafe"),
                ContextualReadSlot.Completeness.COMPLETE, List.of());
        assertEquals(ContextualReadSlot.Acceptance.IGNORED_QUERY,
                wrongQuerySlot.complete(queryRequest, wrongQuery, generation17, false));
        assertEquals(ContextualReadSlot.Freshness.FAILED, wrongQuerySlot.snapshot().freshness());
    }

    @Test
    void resyncQuarantinesReadsUntilAValidatedContextIsApplied() {
        var slot = slot();
        var original = slot.begin(generation17, "decode", false);
        slot.complete(original, loaded("decode", "current"), generation17, false);
        var pending = slot.begin(generation17, "decode", false);

        assertEquals(ContextualReadSlot.Acceptance.IGNORED_RESYNC_REQUIRED,
                slot.complete(pending, loaded("decode", "late"), generation17, true));
        assertEquals(ContextualReadSlot.Freshness.RESYNCING, slot.snapshot().freshness());
        assertEquals(List.of("current"), slot.snapshot().displayed().value());
        assertThrows(IllegalStateException.class, () -> slot.begin(generation17, "decode", true));

        slot.contextChanged(generation17, false);
        assertEquals(ContextualReadSlot.Freshness.STALE, slot.snapshot().freshness());
        assertFalse(slot.snapshot().disposed());
    }

    @Test
    void runtimeOrProgramReplacementClearsTheOldPayload() {
        var slot = slot();
        var request = slot.begin(generation17, "decode", false);
        slot.complete(request, loaded("decode", "old program"), generation17, false);

        var replacement = new ViewStateReducer.ViewContext("runtime", 4, "other", 0);
        slot.contextChanged(replacement, false);

        assertEquals(ContextualReadSlot.Freshness.EMPTY, slot.snapshot().freshness());
        assertNull(slot.snapshot().displayed());
        assertEquals(replacement, slot.snapshot().context());
    }

    @Test
    void completionThatDiscoversAnIdentitySwitchCannotRetainAnotherProgramsPayload() {
        var slot = slot();
        var original = slot.begin(generation17, "decode", false);
        slot.complete(original, loaded("decode", "old program"), generation17, false);
        var pending = slot.begin(generation17, "decode", false);
        var replacement = new ViewStateReducer.ViewContext("runtime", 3, "other", 0);

        assertEquals(ContextualReadSlot.Acceptance.IGNORED_CONTEXT,
                slot.complete(pending, loaded("decode", "late"), replacement, false));
        assertEquals(ContextualReadSlot.Freshness.EMPTY, slot.snapshot().freshness());
        assertNull(slot.snapshot().displayed());
        assertEquals(replacement, slot.snapshot().context());
    }

    @Test
    void invalidPayloadPolicyCannotPoisonThePendingRequest() {
        var slot = new ContextualReadSlot<String, List<String>>(
                "symbols",
                generation17,
                values -> {
                    if (values.size() > 2) throw new IllegalArgumentException("too many symbols");
                    return List.copyOf(values);
                });
        var request = slot.begin(generation17, "", false);

        assertEquals(ContextualReadSlot.Acceptance.REJECTED_INVALID,
                slot.complete(request, loaded("", "a", "b", "c"), generation17, false));
        assertEquals(ContextualReadSlot.Freshness.FAILED, slot.snapshot().freshness());
        assertNull(slot.snapshot().pending());
        assertTrue(slot.snapshot().detail().contains("too many symbols"));
    }

    @Test
    void providerOwnedCollectionsAreDefensivelyCopied() {
        var slot = slot();
        var request = slot.begin(generation17, "decode", false);
        var mutable = new ArrayList<>(List.of("safe"));
        slot.complete(request, new ContextualReadSlot.LoadedResult<>(
                generation17, request.query(), "result", mutable,
                ContextualReadSlot.Completeness.COMPLETE, List.of()),
                generation17, false);

        mutable.set(0, "corrupt");
        assertEquals(List.of("safe"), slot.snapshot().displayed().value());
    }

    @Test
    void partialAndTruncatedPayloadsNeverAppearCurrent() {
        var slot = slot();
        var request = slot.begin(generation17, "decode", false);
        var partial = new ContextualReadSlot.LoadedResult<>(
                generation17, request.query(), "partial", List.of("line"),
                ContextualReadSlot.Completeness.TRUNCATED,
                List.of("output capped"));

        assertEquals(ContextualReadSlot.Acceptance.ACCEPTED,
                slot.complete(request, partial, generation17, false));
        assertEquals(ContextualReadSlot.Freshness.PARTIAL, slot.snapshot().freshness());
        assertEquals("output capped", slot.snapshot().detail());
    }

    @Test
    void disposalClearsPayloadAndRejectsLateCompletion() {
        var slot = slot();
        var request = slot.begin(generation17, "decode", false);
        slot.dispose();

        assertEquals(ContextualReadSlot.Acceptance.IGNORED_DISPOSED,
                slot.complete(request, loaded("decode", "late"), generation17, false));
        assertTrue(slot.snapshot().disposed());
        assertNull(slot.snapshot().displayed());
        assertThrows(IllegalStateException.class,
                () -> slot.begin(generation17, "again", false));
    }

    @Test
    void envelopeMetadataAndWarningsAreBoundedBeforeRetention() {
        assertThrows(IllegalArgumentException.class, () -> new ContextualReadSlot.LoadedResult<>(
                generation17,
                "decode",
                "r".repeat(257),
                List.of("line"),
                ContextualReadSlot.Completeness.COMPLETE,
                List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ContextualReadSlot.LoadedResult<>(
                generation17,
                "decode",
                "result",
                List.of("line"),
                ContextualReadSlot.Completeness.COMPLETE,
                List.of("w".repeat(2_049))));
    }

    private ContextualReadSlot<String, List<String>> slot() {
        return new ContextualReadSlot<>("symbols", generation17, List::copyOf);
    }

    private ContextualReadSlot.LoadedResult<String, List<String>> loaded(
            String query,
            String... values) {
        return new ContextualReadSlot.LoadedResult<>(
                generation17,
                query,
                "result-" + String.join("-", values),
                List.of(values),
                ContextualReadSlot.Completeness.COMPLETE,
                List.of());
    }
}
