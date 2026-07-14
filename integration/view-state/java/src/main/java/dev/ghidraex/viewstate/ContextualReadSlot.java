package dev.ghidraex.viewstate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * A host-neutral state machine for one bounded, context-qualified non-listing read.
 *
 * <p>Decompiler output, symbol searches, references, summaries, and similar views all share the
 * same hard lifecycle problem even though their payloads differ. This slot owns request ordering,
 * context acceptance, freshness, resync quarantine, and disposal while a caller-supplied policy
 * validates and defensively copies the typed payload. It owns no executor, UI, transport, or
 * Ghidra object.</p>
 */
public final class ContextualReadSlot<Q, R> {
    private static final int MAX_SLOT_ID_CHARS = 128;
    private static final int MAX_RESULT_ID_CHARS = 256;
    private static final int MAX_DETAIL_CHARS = 4_096;

    public enum Freshness {
        EMPTY,
        LOADING,
        CURRENT,
        STALE,
        PARTIAL,
        FAILED,
        RESYNCING
    }

    public enum Completeness {
        COMPLETE,
        PARTIAL,
        TRUNCATED
    }

    public enum Acceptance {
        ACCEPTED,
        REJECTED_INVALID,
        IGNORED_DISPOSED,
        IGNORED_SUPERSEDED,
        IGNORED_CONTEXT,
        IGNORED_QUERY,
        IGNORED_RESYNC_REQUIRED;

        public boolean changesState() {
            return this == ACCEPTED
                    || this == REJECTED_INVALID
                    || this == IGNORED_CONTEXT
                    || this == IGNORED_QUERY
                    || this == IGNORED_RESYNC_REQUIRED;
        }
    }

    public record Request<Q>(
            String slotId,
            long requestId,
            ViewStateReducer.ViewContext context,
            Q query) {
        public Request {
            requireBoundedText(slotId, "slotId", MAX_SLOT_ID_CHARS);
            if (requestId <= 0) {
                throw new IllegalArgumentException("requestId must be positive");
            }
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(query, "query");
        }
    }

    public record LoadedResult<Q, R>(
            ViewStateReducer.ViewContext context,
            Q query,
            String resultId,
            R value,
            Completeness completeness,
            List<String> warnings) {
        public LoadedResult {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(query, "query");
            requireBoundedText(resultId, "resultId", MAX_RESULT_ID_CHARS);
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(completeness, "completeness");
            warnings = boundedWarnings(warnings);
        }
    }

    public record Result<Q, R>(
            String slotId,
            long requestId,
            ViewStateReducer.ViewContext context,
            String resultId,
            Q query,
            R value,
            Completeness completeness,
            List<String> warnings) {
        public Result {
            requireBoundedText(slotId, "slotId", MAX_SLOT_ID_CHARS);
            requireBoundedText(resultId, "resultId", MAX_RESULT_ID_CHARS);
            if (requestId <= 0) {
                throw new IllegalArgumentException("requestId must be positive");
            }
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(query, "query");
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(completeness, "completeness");
            warnings = boundedWarnings(warnings);
        }
    }

    public record Snapshot<Q, R>(
            String slotId,
            ViewStateReducer.ViewContext context,
            Freshness freshness,
            Request<Q> pending,
            Result<Q, R> displayed,
            String detail,
            boolean disposed) {
        public Snapshot {
            requireBoundedText(slotId, "slotId", MAX_SLOT_ID_CHARS);
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(freshness, "freshness");
            detail = boundedDetail(detail);
        }
    }

    private final String slotId;
    private final UnaryOperator<R> copyAndValidate;
    private ViewStateReducer.ViewContext context;
    private Freshness freshness = Freshness.EMPTY;
    private Request<Q> pending;
    private Result<Q, R> displayed;
    private String detail = "";
    private long nextRequestId;
    private boolean disposed;

    public ContextualReadSlot(
            String slotId,
            ViewStateReducer.ViewContext initialContext,
            UnaryOperator<R> copyAndValidate) {
        this.slotId = requireBoundedText(slotId, "slotId", MAX_SLOT_ID_CHARS);
        context = Objects.requireNonNull(initialContext, "initialContext");
        this.copyAndValidate = Objects.requireNonNull(copyAndValidate, "copyAndValidate");
    }

    public synchronized Request<Q> begin(
            ViewStateReducer.ViewContext currentContext,
            Q query,
            boolean resyncRequired) {
        ensureOpen();
        contextChanged(currentContext, resyncRequired);
        if (resyncRequired) {
            throw new IllegalStateException("A validated full snapshot is required before requesting " + slotId);
        }
        Request<Q> request = new Request<>(slotId, ++nextRequestId, context, query);
        pending = request;
        freshness = displayed == null ? Freshness.LOADING : Freshness.STALE;
        detail = displayed == null ? "Loading bounded result" : "Refreshing stale result";
        return request;
    }

    public synchronized Acceptance complete(
            Request<Q> request,
            LoadedResult<Q, R> loaded,
            ViewStateReducer.ViewContext currentContext,
            boolean resyncRequired) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(loaded, "loaded");
        Objects.requireNonNull(currentContext, "currentContext");
        if (disposed) {
            return Acceptance.IGNORED_DISPOSED;
        }
        if (resyncRequired) {
            enterResync("Event sequence gap; full snapshot required");
            return Acceptance.IGNORED_RESYNC_REQUIRED;
        }
        if (pending == null || pending.requestId() != request.requestId()) {
            return Acceptance.IGNORED_SUPERSEDED;
        }
        if (!request.context().equals(currentContext)) {
            return rejectForCurrentContext(currentContext);
        }
        if (!slotId.equals(request.slotId())
                || !pending.context().equals(request.context())
                || !request.context().equals(loaded.context())) {
            return rejectCurrent(Acceptance.IGNORED_CONTEXT,
                    "Provider returned a mismatched runtime/program/generation context");
        }
        if (!pending.query().equals(request.query())
                || !request.query().equals(loaded.query())) {
            return rejectCurrent(Acceptance.IGNORED_QUERY,
                    "Provider returned a result for a different semantic query");
        }
        R safeValue;
        try {
            safeValue = Objects.requireNonNull(copyAndValidate.apply(loaded.value()),
                    "Result policy returned null");
        } catch (RuntimeException invalid) {
            return rejectCurrent(Acceptance.REJECTED_INVALID,
                    "Provider returned an invalid bounded result: " + safeMessage(invalid));
        }
        displayed = new Result<>(
                slotId,
                request.requestId(),
                loaded.context(),
                loaded.resultId(),
                loaded.query(),
                safeValue,
                loaded.completeness(),
                loaded.warnings());
        pending = null;
        context = currentContext;
        freshness = loaded.completeness() == Completeness.COMPLETE
                ? Freshness.CURRENT
                : Freshness.PARTIAL;
        detail = loaded.warnings().isEmpty()
                ? "Bounded result is current"
                : String.join(" · ", loaded.warnings());
        return Acceptance.ACCEPTED;
    }

    public synchronized Acceptance fail(
            long requestId,
            String failureDetail,
            ViewStateReducer.ViewContext currentContext,
            boolean resyncRequired) {
        Objects.requireNonNull(currentContext, "currentContext");
        if (disposed) {
            return Acceptance.IGNORED_DISPOSED;
        }
        if (resyncRequired) {
            enterResync("Event sequence gap; full snapshot required");
            return Acceptance.IGNORED_RESYNC_REQUIRED;
        }
        if (pending == null || pending.requestId() != requestId) {
            return Acceptance.IGNORED_SUPERSEDED;
        }
        if (!pending.context().equals(currentContext)) {
            return rejectForCurrentContext(currentContext);
        }
        pending = null;
        freshness = displayed == null ? Freshness.FAILED : Freshness.STALE;
        detail = boundedDetail(Objects.requireNonNullElse(failureDetail, "Read request failed"));
        return Acceptance.ACCEPTED;
    }

    /** Applies a validated context/resync transition without starting work. */
    public synchronized void contextChanged(
            ViewStateReducer.ViewContext nextContext,
            boolean resyncRequired) {
        ensureOpen();
        Objects.requireNonNull(nextContext, "nextContext");
        if (resyncRequired) {
            enterResync("Event sequence gap; full snapshot required");
            return;
        }
        boolean identityChanged = !context.runtimeId().equals(nextContext.runtimeId())
                || context.runtimeEpoch() != nextContext.runtimeEpoch()
                || !context.programId().equals(nextContext.programId());
        boolean generationChanged = context.contentGeneration() != nextContext.contentGeneration();
        context = nextContext;
        if (!identityChanged && !generationChanged && freshness != Freshness.RESYNCING) {
            return;
        }
        pending = null;
        if (identityChanged) {
            displayed = null;
            freshness = Freshness.EMPTY;
            detail = "Validated snapshot changed runtime or program identity";
        } else if (displayed == null) {
            freshness = Freshness.EMPTY;
            detail = "Validated snapshot changed content generation";
        } else {
            freshness = Freshness.STALE;
            detail = "Validated snapshot changed content generation";
        }
    }

    public synchronized Optional<Result<Q, R>> displayed() {
        return Optional.ofNullable(displayed);
    }

    /** True only while this exact request is still eligible to enter a provider. */
    public synchronized boolean isPending(Request<Q> request) {
        Objects.requireNonNull(request, "request");
        return !disposed
                && pending != null
                && pending.requestId() == request.requestId()
                && pending.context().equals(request.context())
                && pending.query().equals(request.query());
    }

    public synchronized Snapshot<Q, R> snapshot() {
        return new Snapshot<>(slotId, context, freshness, pending, displayed, detail, disposed);
    }

    public synchronized void dispose() {
        if (disposed) {
            return;
        }
        pending = null;
        displayed = null;
        freshness = Freshness.EMPTY;
        detail = "Read slot disposed";
        disposed = true;
    }

    private void enterResync(String reason) {
        pending = null;
        freshness = Freshness.RESYNCING;
        detail = boundedDetail(reason);
    }

    private Acceptance rejectCurrent(Acceptance acceptance, String reason) {
        pending = null;
        freshness = displayed == null ? Freshness.FAILED : Freshness.STALE;
        detail = boundedDetail(reason);
        return acceptance;
    }

    private Acceptance rejectForCurrentContext(ViewStateReducer.ViewContext currentContext) {
        boolean identityChanged = !context.runtimeId().equals(currentContext.runtimeId())
                || context.runtimeEpoch() != currentContext.runtimeEpoch()
                || !context.programId().equals(currentContext.programId());
        context = currentContext;
        pending = null;
        if (identityChanged) {
            displayed = null;
            freshness = Freshness.EMPTY;
            detail = "Runtime or program identity changed before the result completed";
        } else {
            freshness = displayed == null ? Freshness.FAILED : Freshness.STALE;
            detail = "Content generation changed before the result completed";
        }
        return Acceptance.IGNORED_CONTEXT;
    }

    private void ensureOpen() {
        if (disposed) {
            throw new IllegalStateException("Read slot is disposed: " + slotId);
        }
    }

    private static List<String> boundedWarnings(List<String> warnings) {
        List<String> safe = List.copyOf(warnings == null ? List.of() : warnings);
        if (safe.size() > 64) {
            throw new IllegalArgumentException("A result may contain at most 64 warnings");
        }
        for (String warning : safe) {
            if (warning == null || warning.length() > 2_048) {
                throw new IllegalArgumentException("Warnings must be non-null and at most 2048 characters");
            }
        }
        return safe;
    }

    private static String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return boundedDetail(message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message);
    }

    private static String boundedDetail(String value) {
        String safe = Objects.requireNonNullElse(value, "");
        return safe.length() <= MAX_DETAIL_CHARS ? safe : safe.substring(0, MAX_DETAIL_CHARS);
    }

    private static String requireBoundedText(String value, String name, int maximum) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.length() > maximum) {
            throw new IllegalArgumentException(name + " must not exceed " + maximum + " characters");
        }
        return value;
    }
}
