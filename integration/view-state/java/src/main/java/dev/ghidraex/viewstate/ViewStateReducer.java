package dev.ghidraex.viewstate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Host-neutral state spine for bounded semantic views.
 *
 * <p>This class deliberately contains no UI, transport, Ghidra, filesystem, or executor types.
 * Hosts perform work off their UI thread and offer responses to this synchronized reducer before
 * applying an immutable snapshot through their UI dispatcher.</p>
 */
public final class ViewStateReducer {
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

    public enum RowKind {
        INSTRUCTION,
        DATA,
        GAP
    }

    public enum Acceptance {
        ACCEPTED,
        IGNORED_DISPOSED,
        IGNORED_DISPOSED_VIEW,
        IGNORED_UNKNOWN_VIEW,
        IGNORED_SUPERSEDED,
        IGNORED_CONTEXT,
        IGNORED_ANCHOR,
        IGNORED_LIMIT,
        IGNORED_RESYNC_REQUIRED;

        public boolean changesViewState() {
            return this == ACCEPTED
                    || this == IGNORED_CONTEXT
                    || this == IGNORED_ANCHOR
                    || this == IGNORED_LIMIT;
        }
    }

    public enum EventAcceptance {
        APPLIED,
        DUPLICATE_OR_OLD,
        GAP_REQUIRES_RESYNC,
        INCONSISTENT_REQUIRES_RESYNC,
        QUARANTINED_REQUIRES_SNAPSHOT
    }

    public record ViewContext(
            String runtimeId,
            long runtimeEpoch,
            String programId,
            long contentGeneration) {
        public ViewContext {
            requireText(runtimeId, "runtimeId");
            requireText(programId, "programId");
            if (runtimeEpoch < 0 || contentGeneration < 0) {
                throw new IllegalArgumentException("Epochs and generations must be non-negative");
            }
        }
    }

    public record AddressRef(
            String spaceId,
            long spaceEpoch,
            String offsetBits,
            String display) {
        public AddressRef {
            requireText(spaceId, "spaceId");
            requireText(offsetBits, "offsetBits");
            requireText(display, "display");
            if (spaceEpoch < 0) {
                throw new IllegalArgumentException("spaceEpoch must be non-negative");
            }
            if (!offsetBits.matches("[0-9a-fA-F]+")) {
                throw new IllegalArgumentException("offsetBits must be an opaque hexadecimal bit pattern");
            }
            String canonicalBits = offsetBits.toLowerCase(java.util.Locale.ROOT);
            int firstNonZero = 0;
            while (firstNonZero < canonicalBits.length() - 1
                    && canonicalBits.charAt(firstNonZero) == '0') {
                firstNonZero++;
            }
            offsetBits = canonicalBits.substring(firstNonZero);
        }

        /** Human-readable formatting is presentation metadata, never address identity. */
        @Override
        public boolean equals(Object other) {
            return other instanceof AddressRef that
                    && spaceEpoch == that.spaceEpoch
                    && spaceId.equals(that.spaceId)
                    && offsetBits.equals(that.offsetBits);
        }

        @Override
        public int hashCode() {
            return Objects.hash(spaceId, spaceEpoch, offsetBits);
        }
    }

    public record LocationRef(
            AddressRef requestedAddress,
            AddressRef containingAddress,
            int byteOffset,
            String fieldId) {
        public LocationRef {
            Objects.requireNonNull(requestedAddress, "requestedAddress");
            Objects.requireNonNull(containingAddress, "containingAddress");
            requireText(fieldId, "fieldId");
            if (byteOffset < 0) {
                throw new IllegalArgumentException("byteOffset must be non-negative");
            }
            if (!requestedAddress.spaceId().equals(containingAddress.spaceId())
                    || requestedAddress.spaceEpoch() != containingAddress.spaceEpoch()) {
                throw new IllegalArgumentException(
                        "Requested and containing addresses must belong to the same address space epoch");
            }
            if (byteOffset == 0 && !requestedAddress.equals(containingAddress)) {
                throw new IllegalArgumentException("A non-identical requested address requires an offcut byte offset");
            }
        }

        public boolean offcut() {
            return byteOffset != 0 || !requestedAddress.equals(containingAddress);
        }
    }

    public record ListingRow(
            String rowId,
            RowKind kind,
            LocationRef location,
            Map<String, String> fields) {
        public ListingRow {
            requireText(rowId, "rowId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(location, "location");
            fields = Map.copyOf(fields == null ? Map.of() : fields);
        }
    }

    public record ViewRequest(
            String viewId,
            long requestId,
            ViewContext context,
            LocationRef anchor,
            int limit) {
        public ViewRequest {
            requireText(viewId, "viewId");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(anchor, "anchor");
            if (requestId <= 0 || limit <= 0 || limit > 4_096) {
                throw new IllegalArgumentException("Invalid request ID or bounded window limit");
            }
        }
    }

    public record ListingWindow(
            String viewId,
            long requestId,
            ViewContext context,
            String resultId,
            LocationRef anchor,
            List<ListingRow> rows,
            Completeness completeness,
            List<String> warnings) {
        public ListingWindow {
            requireText(viewId, "viewId");
            requireText(resultId, "resultId");
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(completeness, "completeness");
            if (requestId <= 0) {
                throw new IllegalArgumentException("requestId must be positive");
            }
            rows = List.copyOf(rows == null ? List.of() : rows);
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
            if (rows.size() > 4_096) {
                throw new IllegalArgumentException("A listing projection may contain at most 4096 rows");
            }
            if (rows.stream().map(ListingRow::rowId).distinct().count() != rows.size()) {
                throw new IllegalArgumentException("Semantic row IDs must be unique within a window");
            }
        }
    }

    public record ViewSnapshot(
            String viewId,
            boolean connected,
            Freshness freshness,
            LocationRef target,
            ViewRequest pending,
            ListingWindow displayed,
            String detail) {
        public ViewSnapshot {
            requireText(viewId, "viewId");
            Objects.requireNonNull(freshness, "freshness");
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    public record WorkbenchSnapshot(
            ViewContext context,
            long eventSequence,
            boolean resyncRequired,
            LocationRef location,
            Set<LocationRef> selection,
            Map<String, Set<LocationRef>> highlights,
            Map<String, ViewSnapshot> views) {
        public WorkbenchSnapshot {
            Objects.requireNonNull(context, "context");
            selection = Set.copyOf(selection == null ? Set.of() : selection);
            if (highlights == null) {
                highlights = Map.of();
            } else {
                Map<String, Set<LocationRef>> copy = new LinkedHashMap<>();
                highlights.forEach((owner, values) -> copy.put(owner, Set.copyOf(values)));
                highlights = Map.copyOf(copy);
            }
            views = Map.copyOf(views == null ? Map.of() : views);
        }
    }

    private static final class MutableView {
        private final String id;
        private final boolean connected;
        private Freshness freshness = Freshness.EMPTY;
        private LocationRef target;
        private ViewRequest pending;
        private ListingWindow displayed;
        private String detail = "";
        private boolean disposed;

        private MutableView(String id, boolean connected) {
            this.id = id;
            this.connected = connected;
        }

        private ViewSnapshot snapshot() {
            return new ViewSnapshot(id, connected, freshness, target, pending, displayed, detail);
        }
    }

    private ViewContext context;
    private final Map<String, MutableView> views = new LinkedHashMap<>();
    private final Map<String, Set<LocationRef>> highlights = new LinkedHashMap<>();
    private Set<LocationRef> selection = Set.of();
    private LocationRef location;
    private long nextRequestId;
    private long eventSequence;
    private long lastEventContentGeneration;
    private boolean resyncRequired;
    private boolean disposed;

    public ViewStateReducer(ViewContext context) {
        this.context = Objects.requireNonNull(context, "context");
        lastEventContentGeneration = context.contentGeneration();
    }

    public synchronized void registerView(String viewId, boolean connected) {
        ensureOpen();
        requireText(viewId, "viewId");
        MutableView candidate = new MutableView(viewId, connected);
        if (connected) {
            candidate.target = location;
        }
        MutableView existing = views.get(viewId);
        if (existing != null && !existing.disposed) {
            throw new IllegalArgumentException("View already registered: " + viewId);
        }
        views.put(viewId, candidate);
    }

    public synchronized void navigate(LocationRef nextLocation) {
        ensureOpen();
        location = Objects.requireNonNull(nextLocation, "nextLocation");
        for (MutableView view : views.values()) {
            if (!view.connected) {
                continue;
            }
            if (view.disposed) {
                continue;
            }
            view.target = nextLocation;
            if (view.pending != null && !view.pending.anchor().equals(nextLocation)) {
                view.pending = null;
            }
            if (view.displayed == null) {
                if (view.pending == null && view.freshness == Freshness.LOADING) {
                    view.freshness = resyncRequired ? Freshness.RESYNCING : Freshness.EMPTY;
                    view.detail = "Connected location changed before the window completed";
                }
                continue;
            }
            boolean currentWindowCoversLocation = (view.freshness == Freshness.CURRENT
                    || view.freshness == Freshness.PARTIAL)
                    && windowCovers(view.displayed, nextLocation);
            if (!currentWindowCoversLocation) {
                view.freshness = resyncRequired ? Freshness.RESYNCING : Freshness.STALE;
                view.detail = "Displayed result does not cover the connected location";
            }
        }
    }

    public synchronized void setSelection(Set<LocationRef> nextSelection) {
        ensureOpen();
        selection = Set.copyOf(nextSelection == null ? Set.of() : nextSelection);
    }

    public synchronized void setHighlight(String owner, Set<LocationRef> locations) {
        ensureOpen();
        requireText(owner, "owner");
        Set<LocationRef> values = Set.copyOf(locations == null ? Set.of() : locations);
        if (values.isEmpty()) {
            highlights.remove(owner);
        } else {
            highlights.put(owner, values);
        }
    }

    public synchronized ViewRequest beginRequest(String viewId, LocationRef anchor, int limit) {
        ensureOpen();
        if (resyncRequired) {
            throw new IllegalStateException("A validated full snapshot is required before requesting views");
        }
        MutableView view = requireActiveView(viewId);
        LocationRef requestedAnchor = Objects.requireNonNull(anchor, "anchor");
        view.target = requestedAnchor;
        ViewRequest request = new ViewRequest(viewId, ++nextRequestId, context, requestedAnchor, limit);
        view.pending = request;
        view.freshness = resyncRequired
                ? Freshness.RESYNCING
                : view.displayed == null ? Freshness.LOADING : Freshness.STALE;
        view.detail = view.displayed == null ? "Loading bounded listing window" : "Refreshing stale window";
        return request;
    }

    public synchronized Acceptance complete(ListingWindow result) {
        Objects.requireNonNull(result, "result");
        if (disposed) {
            return Acceptance.IGNORED_DISPOSED;
        }
        MutableView view = views.get(result.viewId());
        if (view == null) {
            return Acceptance.IGNORED_UNKNOWN_VIEW;
        }
        if (view.disposed) {
            return Acceptance.IGNORED_DISPOSED_VIEW;
        }
        if (resyncRequired) {
            return Acceptance.IGNORED_RESYNC_REQUIRED;
        }
        ViewRequest pending = view.pending;
        if (pending == null || pending.requestId() != result.requestId()) {
            return Acceptance.IGNORED_SUPERSEDED;
        }
        if (!pending.context().equals(result.context()) || !context.equals(result.context())) {
            return rejectCurrent(view, Acceptance.IGNORED_CONTEXT,
                    "Provider returned a mismatched runtime/program/generation context");
        }
        if (!pending.anchor().equals(result.anchor())) {
            return rejectCurrent(view, Acceptance.IGNORED_ANCHOR,
                    "Provider returned a window for a different semantic anchor");
        }
        if (result.rows().size() > pending.limit()) {
            return rejectCurrent(view, Acceptance.IGNORED_LIMIT,
                    "Provider exceeded the requested bounded row limit");
        }
        view.displayed = result;
        view.pending = null;
        view.freshness = result.completeness() == Completeness.COMPLETE
                ? Freshness.CURRENT
                : Freshness.PARTIAL;
        view.detail = result.warnings().isEmpty()
                ? result.rows().size() + " semantic rows"
                : String.join(" · ", result.warnings());
        return Acceptance.ACCEPTED;
    }

    public synchronized Acceptance fail(String viewId, long requestId, String detail) {
        if (disposed) {
            return Acceptance.IGNORED_DISPOSED;
        }
        MutableView view = views.get(viewId);
        if (view == null) {
            return Acceptance.IGNORED_UNKNOWN_VIEW;
        }
        if (view.disposed) {
            return Acceptance.IGNORED_DISPOSED_VIEW;
        }
        if (resyncRequired) {
            return Acceptance.IGNORED_RESYNC_REQUIRED;
        }
        if (view.pending == null || view.pending.requestId() != requestId) {
            return Acceptance.IGNORED_SUPERSEDED;
        }
        view.pending = null;
        view.freshness = view.displayed == null ? Freshness.FAILED : Freshness.STALE;
        view.detail = Objects.requireNonNullElse(detail, "Listing request failed");
        return Acceptance.ACCEPTED;
    }

    public synchronized EventAcceptance acceptEvent(long sequence, long contentGeneration) {
        ensureOpen();
        if (sequence <= 0 || contentGeneration < 0) {
            throw new IllegalArgumentException(
                    "Incremental event sequence must be positive and generation non-negative");
        }
        if (resyncRequired) {
            return EventAcceptance.QUARANTINED_REQUIRES_SNAPSHOT;
        }
        if (sequence < eventSequence) {
            return EventAcceptance.DUPLICATE_OR_OLD;
        }
        if (sequence == eventSequence) {
            if (contentGeneration == lastEventContentGeneration) {
                return EventAcceptance.DUPLICATE_OR_OLD;
            }
            resyncRequired = true;
            markViews(Freshness.RESYNCING,
                    "Conflicting duplicate event; full snapshot required", true);
            return EventAcceptance.INCONSISTENT_REQUIRES_RESYNC;
        }
        if (eventSequence != 0 && sequence != eventSequence + 1) {
            resyncRequired = true;
            markViews(Freshness.RESYNCING, "Event sequence gap; full snapshot required", true);
            return EventAcceptance.GAP_REQUIRES_RESYNC;
        }
        if (contentGeneration < context.contentGeneration()) {
            resyncRequired = true;
            markViews(Freshness.RESYNCING, "Content generation moved backward; full snapshot required", true);
            return EventAcceptance.INCONSISTENT_REQUIRES_RESYNC;
        }
        eventSequence = sequence;
        lastEventContentGeneration = contentGeneration;
        if (contentGeneration > context.contentGeneration()) {
            context = new ViewContext(
                    context.runtimeId(),
                    context.runtimeEpoch(),
                    context.programId(),
                    contentGeneration);
            markViews(Freshness.STALE, "Program content generation advanced", true);
        }
        return EventAcceptance.APPLIED;
    }

    public synchronized void applySnapshot(ViewContext nextContext, long sequence) {
        ensureOpen();
        Objects.requireNonNull(nextContext, "nextContext");
        if (sequence < 0) {
            throw new IllegalArgumentException("A full snapshot sequence must be non-negative");
        }
        boolean sameRuntimeId = context.runtimeId().equals(nextContext.runtimeId());
        if (sameRuntimeId && nextContext.runtimeEpoch() < context.runtimeEpoch()) {
            throw new IllegalArgumentException("A runtime epoch cannot move backward");
        }
        boolean sameRuntimeIncarnation = sameRuntimeId
                && context.runtimeEpoch() == nextContext.runtimeEpoch();
        boolean sameProgram = context.programId().equals(nextContext.programId());
        if (sameRuntimeIncarnation && sequence < eventSequence) {
            throw new IllegalArgumentException("A full snapshot cannot move event sequence backward");
        }
        if (sameRuntimeIncarnation && sameProgram
                && nextContext.contentGeneration() < context.contentGeneration()) {
            throw new IllegalArgumentException("A content generation cannot move backward");
        }
        boolean identityChanged = !sameRuntimeIncarnation || !sameProgram;
        context = nextContext;
        eventSequence = sequence;
        lastEventContentGeneration = nextContext.contentGeneration();
        resyncRequired = false;
        if (identityChanged) {
            location = null;
            selection = Set.of();
            highlights.clear();
        }
        for (MutableView view : views.values()) {
            if (view.disposed) {
                continue;
            }
            view.pending = null;
            if (identityChanged) {
                view.target = null;
                view.displayed = null;
                view.freshness = Freshness.EMPTY;
                view.detail = "Validated snapshot changed runtime or program identity";
                continue;
            }
            if (view.displayed != null) {
                view.freshness = Freshness.STALE;
                view.detail = "Validated snapshot replaced the runtime context";
            } else {
                view.freshness = Freshness.EMPTY;
                view.detail = "Validated snapshot applied";
            }
        }
    }

    public synchronized Optional<ViewSnapshot> view(String viewId) {
        MutableView view = views.get(viewId);
        return view == null || view.disposed ? Optional.empty() : Optional.of(view.snapshot());
    }

    public synchronized WorkbenchSnapshot snapshot() {
        Map<String, ViewSnapshot> copies = new LinkedHashMap<>();
        views.forEach((id, view) -> {
            if (!view.disposed) {
                copies.put(id, view.snapshot());
            }
        });
        return new WorkbenchSnapshot(
                context,
                eventSequence,
                resyncRequired,
                location,
                selection,
                highlights,
                copies);
    }

    public synchronized void dispose() {
        disposed = true;
        for (MutableView view : views.values()) {
            view.pending = null;
            view.target = null;
            view.displayed = null;
            view.disposed = true;
        }
    }

    public synchronized void disposeView(String viewId) {
        if (disposed) {
            return;
        }
        MutableView view = views.get(viewId);
        if (view == null || view.disposed) {
            return;
        }
        view.pending = null;
        view.target = null;
        view.displayed = null;
        view.disposed = true;
        view.freshness = Freshness.EMPTY;
        view.detail = "View disposed";
    }

    public synchronized boolean disposed() {
        return disposed;
    }

    public synchronized boolean authoritativeActionsAllowed() {
        return !disposed && !resyncRequired;
    }

    private void markViews(Freshness state, String detail, boolean clearPending) {
        for (MutableView view : views.values()) {
            if (view.disposed) {
                continue;
            }
            if (clearPending) {
                view.pending = null;
            }
            view.freshness = view.displayed == null && state == Freshness.STALE
                    ? Freshness.EMPTY
                    : state;
            view.detail = detail;
        }
    }

    private MutableView requireActiveView(String viewId) {
        MutableView view = views.get(viewId);
        if (view == null) {
            throw new IllegalArgumentException("Unknown view: " + viewId);
        }
        if (view.disposed) {
            throw new IllegalStateException("View is disposed: " + viewId);
        }
        return view;
    }

    private static Acceptance rejectCurrent(
            MutableView view,
            Acceptance acceptance,
            String detail) {
        view.pending = null;
        view.freshness = view.displayed == null ? Freshness.FAILED : Freshness.STALE;
        view.detail = detail;
        return acceptance;
    }

    private static boolean windowCovers(ListingWindow window, LocationRef location) {
        if (window.anchor().equals(location)) {
            return true;
        }
        for (ListingRow row : window.rows()) {
            LocationRef candidate = row.location();
            if (candidate.requestedAddress().equals(location.requestedAddress())
                    || candidate.containingAddress().equals(location.containingAddress())
                    || candidate.containingAddress().equals(location.requestedAddress())
                    || candidate.requestedAddress().equals(location.containingAddress())) {
                return true;
            }
        }
        return false;
    }

    private void ensureOpen() {
        if (disposed) {
            throw new IllegalStateException("View-state reducer is disposed");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
