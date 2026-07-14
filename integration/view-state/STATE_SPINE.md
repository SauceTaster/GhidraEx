# GhidraEx view-state spine proof

## Problem being proved

Before this proof, the three retained hosts made different simplifying assumptions:

- JavaFX and IntelliJ exposed a logical 100,000-row listing, but a cache miss invoked the engine
  synchronously from a control getter/paint path. A real RPC would freeze the FX thread or EDT.
- VS Code used native virtual documents, but its listing provider synchronously joined all 100,000
  rows. `TextDocumentContentProvider` has no viewport callback, so page-sized engine calls did not
  make the resulting document bounded.
- All three routed navigation through dense integer rows and scalar addresses. None had a shared
  rule for delayed response reversal, runtime epochs, content generations, event gaps, partial
  results, offcuts, overlays, or pinned versus connected views.

The proof replaces those assumptions with one small semantic reducer and host-specific bounded
window projection. It does not claim to be the complete v2 protocol.

## Cross-host risk matrix

| Concern | JavaFX failure mode | IntelliJ failure mode | VS Code failure mode | Chosen design | Residual work |
| --- | --- | --- | --- | --- | --- |
| UI responsiveness | `ObservableList.get()` can make a synchronous engine call on the FX thread | `TableModel.getValueAt()` can make the same call on the EDT | a provider joins the complete listing before yielding to the extension host | worker-side query plus one host UI dispatcher; renderers read immutable local rows only | a real transport must honor the cancellation already propagated by each host adapter |
| Listing scale | a dense logical list assumes every item has an ordinal | a dense table repeats that assumption | a virtual document still materializes every line because VS Code has no viewport callback | bounded semantic windows, maximum 4,096 rows, opened around a location or scope | add opaque forward/back cursors and bounded result eviction |
| Address correctness | scalar `long` plus `(address-base)/4` loses spaces and offcuts | scalar `long` and row restoration do the same | JavaScript `number` and line numbers lose unsigned precision and semantic identity | space-qualified opaque offset bits plus requested/containing location | generate these records from the v2 Protobuf schema |
| Delayed responses | an old page can mix with newly analyzed pages | an old analysis/navigation callback can overwrite current UI | an older command/provider completion can reopen or refresh the wrong document | monotonic request IDs and exact runtime/program/generation matching | propagate request cancellation to Ghidra workers |
| Event loss/reconnect | no ordered event model | no reconnect snapshot boundary | refresh is a blind repaint | sequence-gap detection, visible `RESYNCING`, retained coherent result, atomic full snapshot | authenticated replay/snapshot transport for every host |
| Editor lifecycle | late worker callbacks can touch a closed scene | callbacks can outlive a disposed editor/project | providers can retain stale URI/document state | disposal invalidates acceptance before any host callback mutates UI | result/resource lease release acknowledgements |
| Interaction semantics | navigation imperatively updates a subset of panes | selection, caret, and editor restoration collapse to a row | focus and navigation can replace global selection | independent location, selection, focus, highlight-owner, and later debugger-coordinate channels | history, pinning, compare, and multi-program policies |
| Host integration | native controls and accessibility are valuable | native editor/actions/remote-development lifecycle are valuable | editors, language APIs, TreeViews, DAP, tasks, and terminals are valuable | share protocol/state semantics, not widgets; every host remains native | host-specific accessibility and remote-placement suites |
| Debug/scripts/plugins | synthetic switches appear more authoritative than they are | IDE actions can accidentally imply backend capability | DAP/terminal/plugin affordances can imply authority | keep these surfaces explicitly synthetic/gated outside the read-only proof | capability descriptors, leases, proposal/apply, audit, sandboxing |

## Chosen boundary

```text
Ghidra runtime / deterministic provider
                |
      async semantic query + ordered events
                |
       host-neutral state reducer
         /             |             \
 JavaFX projection  IntelliJ projection  VS Code projection
 native controls    native editor/actions native documents/providers
```

The reducer owns identity, acceptance, freshness, and interaction channels. It does not own
threads, controls, documents, RPC objects, Ghidra objects, or filesystem handles. A host adapter
may request work only after it has produced a reducer request; when work completes, it first asks
the reducer whether the result is still acceptable and only then schedules a native UI update.
This keeps protocol semantics testable without reducing the three hosts to a least-common-
denominator UI toolkit.

Several tempting alternatives fail the proof boundary:

- A larger page cache still permits mixed-generation pages unless replacement is atomic.
- A nominally virtual VS Code document is not bounded if the provider constructs the whole text.
- A shared webview UI would discard the native integration points this experiment is meant to
  evaluate.
- Request cancellation alone is insufficient because cancellation races; result acceptance must
  still be deterministic.
- A generic `Map<String, Object>` or JSON view model would move identity and version errors to
  runtime. These proof records are intended to become generated Protobuf types, while JSON remains
  only in the implemented v1 compatibility boundary.

## Backend bridge policy

The deterministic providers prove host scheduling and projection behavior, but they are not the
future engine API. There are deliberately three boundary levels:

1. Fixture adapters may translate the existing dense synthetic engines, but the scalar address and
   instruction-size arithmetic stays inside that adapter. It is never exposed to a control or
   saved editor state.
2. The implemented JSON v1 real backend may later supply a compatibility window for its one
   immutable program. The adapter must advertise that it has one limited address model and no
   mutation/event-generation authority; it must not invent overlays, offcut identity, or v2
   generations that v1 cannot prove.
3. Protobuf v2 becomes canonical for project runtimes. Generated Java and TypeScript records
   replace the handwritten proof records after the sparse-listing schema gate in
   [`ADR-0002`](../protocol/ADR-0002-semantic-protobuf-project-runtime.md) is met. Native gRPC and
   the browser unary/server-stream adapter feed the same reducer semantics.

This lets the UI proof run now without allowing the fixture or JSON compatibility shape to become
the durable protocol by accident.

## Invariants

1. A view context is `{runtimeId, runtimeEpoch, programId, contentGeneration}`. Results match the
   complete context, not only program name or a scalar row.
2. Addresses use a space identity and canonical opaque offset bits (lowercase hexadecimal with no
   redundant leading zeroes). Equal offsets in an overlay and base space are different locations;
   display formatting is never identity.
3. A location preserves requested address, containing-unit start, byte offset, and semantic field.
   Offcut navigation is never silently normalized.
4. This proof keeps location, selection, and owner-scoped highlight layers independent. Focus and
   debugger coordinates are explicitly future channels and may not be inferred from those three.
5. Every connected-view request receives a monotonic request ID. Only the current request for that
   view and exact context may replace its displayed result.
6. A displayed result is one bounded immutable window. Replacement is atomic; pages from two
   generations are never combined into one window.
7. A view explicitly reports `EMPTY`, `LOADING`, `CURRENT`, `STALE`, `PARTIAL`, `FAILED`, or
   `RESYNCING`. An old coherent result may remain visible while labeled stale.
8. An event sequence gap enters `RESYNCING`, disables authority-bearing actions, and retains the
   last coherent snapshot. Incremental events and view fetches are quarantined until a validated
   full snapshot changes context/sequence atomically.
9. Rendering getters never call transport code. Host adapters start work off the UI thread and
   apply accepted reducer snapshots through one UI dispatcher.
10. View disposal clears retained rows, invalidates pending requests, prevents queued provider
    entry, and suppresses late state/UI application. Already-running transport cancellation and
    result-lease release remain explicit provider responsibilities reported through completion
    outcomes.

## Semantic records

```text
ViewContext
  runtime_id
  runtime_epoch
  program_id
  content_generation

AddressRef
  space_id
  space_epoch
  offset_bits
  display

LocationRef
  requested_address
  containing_address
  byte_offset
  field_id

ListingWindow
  view_id
  request_id
  context
  result_id
  anchor
  rows[]                 // instruction | data | compressed gap
  completeness           // complete | partial | truncated
  warnings[]
```

`offset_bits` is an opaque hexadecimal bit pattern in this proof. The v2 schema will add complete
address-space metadata and segmented forms as described in
[`protocol-v2-requirements.md`](../../docs/research/protocol-v2-requirements.md).

## Host projections

### JavaFX

Use a bounded `ListView` window. The view model immediately enters `LOADING`/`STALE`, performs the
engine call on a worker, reduces the response, and atomically replaces `ObservableList` content on
the FX thread. Cells render instruction, data, gap, partial, stale, and error state from local data
only.

### IntelliJ

Keep the custom listing editor, but its table model contains only a bounded semantic window.
`getValueAt()` reads immutable local rows and never calls the engine. Requests run in background;
one EDT gateway applies accepted reducer state. Editor identity and restoration use runtime/program
identity plus semantic location, not display filename or ordinal row.

### VS Code

Keep native readonly documents, semantic providers, commands, decorations, TreeViews, and DAP.
Each listing document is a bounded projection (maximum 4,096 semantic rows) with an explicit
document-line-to-row-ref map. Navigation opens/replaces a preview projection around the target;
language providers translate through that map rather than equating editor line with program row.
A graph, memory grid, or timeline may later use a focused custom surface, but the whole workbench
does not move into a webview.

## Implemented proof boundary

| Layer | What is implemented | What the proof demonstrates |
| --- | --- | --- |
| Shared Java core | synchronized semantic reducer, asynchronous listing controller, and typed contextual read slot/controller | listing and non-listing results share exact request/context acceptance, completeness, event-gap quarantine, atomic recovery, and disposal before queued or late work |
| JavaFX projection | 256-row immutable listing windows plus bounded symbol, decompiler, xref, and analysis workers; one FX dispatcher; local-only controls/cells | every interactive engine entry is outside the FX thread and every result remains visibly current, stale, partial, failed, or resyncing |
| IntelliJ projection | 512-row immutable listing windows plus dedicated latest-only program, symbol, decompiler, inspector, xref, and runtime workers; one EDT gateway | native editors, actions, tables, and tool windows retain IntelliJ behavior without turning the EDT into a provider boundary |
| VS Code projection | at most 4,096 semantic rows per virtual document plus one async contextual read service for documents, language providers, TreeViews, evidence, commands, and scripting | native editor/language/TreeView integrations remain useful without whole-program materialization or synchronous fixture calls in presentation providers |

The important result is not one shared widget. It is one semantic acceptance model feeding three
native projections. A renderer can be replaced, a window can be evicted, and a host can restore an
editor without changing runtime identity or result-validity rules.

## Proof scenarios

| ID | Stimulus | Required result |
| --- | --- | --- |
| `SPINE-01` | request A, B, C; complete B, A, C | only C replaces the view |
| `SPINE-02` | complete a request after content generation changes | response is rejected; old result stays visibly stale |
| `SPINE-03` | receive event 41 then 43 | enter `RESYNCING`; keep coherent result; quarantine incrementals/fetches; disable authority |
| `SPINE-04` | apply validated snapshot at 44, then fetch | one atomic context swap followed by one current window |
| `SPINE-05` | base and overlay addresses share offset bits | locations and view identities remain distinct |
| `SPINE-06` | navigate to instruction/data offcut | requested and containing addresses plus byte offset survive |
| `SPINE-07` | window contains instruction, data and compressed gap | every row kind renders explicitly |
| `SPINE-08` | provider returns partial coverage/warning | view is `PARTIAL`, never blank or falsely current |
| `SPINE-09` | navigate while selection and user/agent highlights exist | those channels are unchanged |
| `SPINE-10` | dispose view before queued provider entry/response | provider entry is skipped when still queued; result/state/UI application is ignored; retained rows are cleared |
| `SPINE-11` | provider exceeds the request limit, duplicates a row ID, or mismatches context/anchor | terminate the malformed unary request as failed/stale without replacing coherent state |
| `SPINE-12` | an event or same-identity snapshot reports a lower generation/epoch | enter `RESYNCING` for an event or reject a regressive snapshot; identity cannot move backward |
| `SPINE-13` | runtime/program identity changes and event numbering restarts | only a newer runtime epoch may reset sequence; clear old results and runtime-local interaction state |
| `SPINE-14` | register a connected view after navigation | it inherits the current location without issuing work from a renderer |
| `SPINE-15` | navigate within/outside the displayed semantic rows | covered navigation stays current; outside navigation stales the view and supersedes pending work |
| `SPINE-16` | repeat one event sequence with conflicting generation, then send another incremental | require resync and quarantine both the conflict and later incrementals until a snapshot |
| `SPINE-17` | reverse two symbol/decompiler/xref requests or change identity while one is pending | only the latest exact context/query echo may replace that view; identity replacement clears the old payload |
| `SPINE-18` | return oversized text, metadata, warnings, or result collections | reject or explicitly truncate at the adapter budget; never publish an unbounded payload as current |

## Acceptance limits

- No host listing projection contains more than 4,096 semantic rows.
- No control getter, renderer, document provider loop, or event listener performs a whole-program
  scan or a potentially blocking transport call. Cheap synchronous fixture translations are not
  evidence that a real adapter may block the FX thread, EDT, or VS Code extension-host event loop.
- Page/window/result caches are bounded and context-qualified.
- Non-listing result adapters enforce per-field, collection, warning, and aggregate payload budgets
  before a value reaches a native control.
- Every late result has a deterministic accepted/ignored reason in tests.
- Native host integrations remain native; only the data source and state ownership change.
- Debugger, scripts, plugins, repository writes, and agent mutation remain synthetic/gated until
  their richer authority contracts are implemented.

## Deliberate non-claims

- The implemented host adapters enforce object-level field and aggregate budgets, but those are not
  a canonical encoded-wire budget. Generated v2 messages still need negotiated byte quotas for
  selections/highlights, active-view snapshots, batches, and streams before accepting untrusted
  remote payloads.
- `AsyncListingController` requires a non-inline worker and a live native dispatcher. JavaFX and
  IntelliJ production adapters and tests enforce those host obligations; the generic Java type
  cannot infer thread affinity from `Executor` alone.
- Closing a view prevents queued loader entry and rejects late application, but a provider already
  executing inside Ghidra needs cooperative cancellation plus result/resource release. The
  controller exposes every completion acceptance reason so an adapter can release rejected result
  leases.
- This slice does not yet implement focus ownership, navigation history, pinned/compare views,
  debugger coordinates/authority, or a mutation proposal. They remain separate state planes rather
  than aliases for listing selection.
- Program catalogs, symbol/reference searches, decompiler results, inspector details, project
  TreeViews, evidence reads, and bounded REPL reads now use the contextual result pattern in the
  retained hosts. Graphs, memory, debugger history/control, arbitrary scripts, mutations, and
  engine-plugin lifecycle still need their distinct identities, authority, and failure suites; a
  read-state proof must not be cited as evidence that those workflows are production-safe.
- The retained hosts still ship explicit synthetic data adapters. Their asynchronous seams are
  ready for a real provider, but only the web workbench currently maps the implemented JSON v1
  gateway. A detected Ghidra installation remains `AVAILABLE_NOT_CONNECTED`, never `CURRENT` real
  data, until an authenticated adapter handshake succeeds.

The shared Java reducer lives in [`java/`](java/). The VS Code implementation mirrors it in its
host-neutral TypeScript core. The Java and TypeScript reducer suites execute the same scenario IDs;
native JavaFX, IntelliJ, and VS Code projection tests add their platform scheduling and mapping
checks. This makes semantic drift visible even though Java and TypeScript use different
generated/runtime types today.
