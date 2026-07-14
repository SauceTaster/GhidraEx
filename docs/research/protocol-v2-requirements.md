# Protocol v2 requirements

## Scope

Protocol v2 is a semantic contract for three native hosts, browser-capable VS Code, automation,
and scoped agents. It is not a serialization of Ghidra APIs and not a direct Protobuf translation
of JSON v1. JSON v1 remains the bounded immutable read slice described by
[`PROTOCOL.md`](../../integration/protocol/PROTOCOL.md).

The schema must preserve distinctions that Ghidra uses internally but that ordinary editor APIs
often collapse: address space and representation, absent versus empty scope, local versus
repository state, path entry versus target object, saved versus live content, result identity,
debugger time, control authority, partial completion, and indeterminate remote outcomes.

## Transport profile

| Client | Required transport | Notes |
| --- | --- | --- |
| JavaFX | native gRPC over owner-only local socket/TCP or authenticated remote TLS | unary, server stream, client stream, and bidirectional stream available |
| IntelliJ backend | native gRPC | connection belongs on the workspace/backend side under Remote Development |
| VS Code desktop/remote extension host | native gRPC when Node/native dependencies are available; browser profile otherwise | adapter must expose identical messages and status model |
| VS Code web extension | unary and server-stream browser adapter | no process spawn, filesystem path assumptions, client stream, or bidirectional stream |
| Agent/service | authenticated native gRPC or gateway | narrower scopes, lower quotas, mandatory audit context for authority-bearing calls |

The official gRPC-Web implementation supports unary and server-streaming RPCs; client-side and
bidirectional streaming are not supported, and binary mode supports unary calls only
([gRPC-Web README](https://github.com/grpc/grpc-web)). Browser workflows therefore use ordinary
commands plus server event streams:

- `StartJob`, `CancelJob`, `RespondToPrompt`, and `ApplyProposal` are unary;
- `WatchWorkspace`, `WatchJob`, and bounded result pages are server-streaming or resumable unary;
- upload/download use chunk resources or signed, scoped artifact transfer rather than assuming a
  client stream;
- debugger commands are unary and target events are a server stream ordered per connection.

The broker may offer JSON transcoding for inspection, but Protobuf binary semantics are canonical.
Protobuf JSON loses some presence and numeric distinctions and is not the persistence format.

## Presence and scope

Proto3 fields must use `optional`, wrapper messages, or `oneof` wherever omission has meaning. An
implicit scalar default cannot mean both “the client chose zero” and “use engine policy.” See the
official [field-presence guidance](https://protobuf.dev/programming-guides/field_presence/).

The minimum scope model is conceptually:

```proto
message Scope {
  oneof kind {
    WholeScope whole = 1;
    AddressRangeSet ranges = 2;
    EmptyScope empty = 3;
  }
}

message AnalysisRequest {
  optional Scope scope = 1; // absent: operation-specific default, never silently “whole”
}
```

`absent`, `empty`, and `whole` remain distinct through every adapter. This is important because
some Ghidra APIs use an empty address set to request whole-program work, while an empty user
selection normally means do nothing.

## Identity model

No path, display name, repository version number, scalar revision, or Ghidra database key is
sufficient as a universal identity. All durable references carry the smallest relevant owner and
epoch.

| Reference | Required identity material |
| --- | --- |
| `ProjectRuntimeRef` | broker instance, runtime ID, runtime epoch, exact Ghidra build, extension-set digest |
| `ProjectEntryRef` | runtime, entry ID, path generation, entry kind, link-edge identity |
| `DomainObjectRef` | runtime, broker stable object ID, upstream file ID when present, content type, object epoch |
| `ProgramRef` | domain object plus live/open generation and language/compiler fingerprint |
| `VersionRef` | domain object, repository/history epoch, version number, creation metadata, immutable content digest or broker snapshot ID |
| `ResultRef` | runtime/result arena, result ID, input generations, query/profile hash, expiry |
| `ArtifactRef` | content digest, size, media/type metadata, trust/provenance, storage lease |
| `DebugConnectionRef` | debug runtime and connection epoch |
| `TargetRef` / `TraceRef` | connection plus target/trace ID and epoch |
| `TimeSpec` | trace plus raw snap or structured schedule; optional materialization ref |

Ghidra can delete the latest repository version and later reuse its number for different content
([`VersionedDatabase`](https://github.com/NationalSecurityAgency/ghidra/blob/Ghidra_12.1.2_build/Ghidra/Framework/FileSystem/src/main/java/ghidra/framework/store/db/VersionedDatabase.java)).
Consequently `(file ID, version number)` is not an immutable cache or evidence key.

`ProjectEntryRef` and `DomainObjectRef` are separate because links and layered project storage can
give one object several paths, and deleting a link edge is different from deleting its target.
The project tree also permits a file and folder with the same sibling name
([`GhidraURL`](https://github.com/NationalSecurityAgency/ghidra/blob/Ghidra_12.1.2_build/Ghidra/Framework/Project/src/main/java/ghidra/framework/protocol/ghidra/GhidraURL.java)).

## Epochs and generations

A single `revision` cannot describe all invalidation. Responses include the relevant members of:

```text
RuntimeEpoch        process/runtime restart
RepositoryEpoch     reconnect or full repository resync
ProjectTreeEpoch    atomic project-tree snapshot replacement
PathGeneration      rename, move, shadow/reveal, link change
ObjectEpoch         close/reopen, identity repair, storage replacement
ContentGeneration   committed live DomainObject mutation
SaveGeneration      last saved local state
HistoryEpoch        destructive repository-history change
UserDataGeneration  private ProgramUserData change
AnalysisEpoch       analysis policy/run state
LanguageEpoch       language/compiler/spec-extension migration
ExtensionEpoch      engine extension set or schema change
ResultEpoch         result-arena eviction/reset
DebugEpoch          TraceRMI disconnect/reconnect
```

An operation declares which generations it reads and which it may advance. Clients stale-check
only against those dependencies; changing layout must not invalidate decompilation, while a
language migration must invalidate nearly every semantic result.

## Structured addresses and ranges

Ghidra addresses can be unsigned 64-bit memory offsets, signed stack offsets, word-addressed,
segmented, overlays, register/external/constant/hash/unique spaces, or artificial spaces. One text
form can be ambiguous, and `AddressFactory.getAllAddresses()` can return several candidates
([`Address`](https://ghidra.re/ghidra_docs/api/ghidra/program/model/address/Address.html),
[`AddressSpace`](https://ghidra.re/ghidra_docs/api/ghidra/program/model/address/AddressSpace.html),
[`SegmentedAddress`](https://ghidra.re/ghidra_docs/api/ghidra/program/model/address/SegmentedAddress.html)).

`AddressRef` therefore contains:

- a program/language-scoped `AddressSpaceRef` with stable broker ID, Ghidra name, kind, address
  width, addressable-unit size, overlay/base relationship, and space epoch;
- raw offset bits, not a JavaScript number;
- explicit signed interpretation where applicable;
- logical segment/offset and physical address for segmented forms;
- a canonical display string for people, never used as the only identity.

`AddressRangeSet` is an ordered collection of normalized ranges across spaces. APIs must not assume
one contiguous range or one address space. Navigation text goes through a broker `ResolveLocation`
workflow that returns ranked candidates and explanations; clients do not independently reproduce
Ghidra's radix-, context-, block-, file-offset-, and symbol-dependent Go To rules.

## Project and repository model

`ProjectTreeSnapshot` is a validated, atomic generation. It represents both local and remote
layers, not just a list of paths:

```text
ProjectEntryState {
  entry_ref
  local_layer               private | checkout | hijacked | absent
  repository_layer          present | absent | stale | inaccessible
  checkout_base
  repository_latest
  dirty_generation
  save_generation
  recovery_generation
  link_target/link_chain
  capabilities
}
```

After reconnect, the runtime emits `RESYNC_REQUIRED`, explicitly traverses a new snapshot, validates
it, and swaps it atomically. It does not relay transient missing children as deletion events.

Repository calls are journaled operations with a caller idempotency key, expected generations,
remote request identity when available, stages, observed postconditions, and reconciliation state.
Results include per-item manifests and may end in `OUTCOME_UNKNOWN`. The protocol never promises a
project-wide commit: Ghidra's multi-file check-in iterates files and can leave earlier files
committed when a later file fails
([`CheckInTask`](https://github.com/NationalSecurityAgency/ghidra/blob/Ghidra_12.1.2_build/Ghidra/Framework/Project/src/main/java/ghidra/framework/main/datatree/CheckInTask.java)).

Content capabilities are negotiated per object/type: `open_read_only`, `open_historical`,
`recoverable`, `shareable`, `checkoutable`, `mergeable`, `linkable`, `resettable`, and
`compound_members`. UI enablement derives from these capabilities, not filename or a generic
“program” assumption.

## Operation descriptors

Every method that does more than a simple immutable read has a machine-readable descriptor:

| Field | Purpose |
| --- | --- |
| operation kind/version | stable semantic contract and audit decoder |
| read/write set | resources and generations consulted or modified |
| authority | project read/write, repository write, live target, filesystem, network, process, secrets |
| atomicity | read, one-domain-object transaction, fixed compound transaction, or saga |
| idempotency | safe retry, deduplicated retry, reconcile-before-retry, or never retry |
| cancellation | no effect, rollback, partial valid state, staged remote effect, or indeterminate |
| analysis behavior | none, schedules analysis, waits for quiescence, changes analysis options |
| locks/leases | resources and maximum hold time |
| interaction | none, typed prompt, approval, or legacy UI required |
| result contract | complete/partial/truncated, coverage, warnings, per-item outcomes |

Clients can inspect a descriptor before enabling a command or delegating it to an agent. Extension
operations must provide the same information and live in a namespaced package.

## Intent mutations

Clients never call `transaction.begin` and keep a transaction open. The generic flow is:

```text
PlanOperation(intent, expected generations)
  -> ProposalRef + resolved targets + impact + validation + required authority

ApplyProposal(proposal, selected changes, approval, idempotency key)
  -> OperationRef

Get/WatchOperation(operation)
  -> stage + per-item outcomes + resulting generations + audit ref
```

The runtime revalidates immediately before starting a named Ghidra transaction. A one-object or
fixed compound operation either commits or rolls back. Repository effects become a saga and report
remote/local stages separately. External effects are never represented as undoable Ghidra edits.

Patch/assembly, context edits, datatype conflict resolution, function signature changes, reference
replacement, and decompiler-derived edits require specialized preview records because each can
alter collateral state. A generic `setProperty(path, value)` method is not sufficient.

Merge, archive synchronization, VT apply/unapply, and other category-driven operations also return
an `effective_plan`. Ghidra may coerce unsupported merge modes, couple categories, commit a
processed prefix, accept a VT association before individual markup succeeds, or commit placeholder
datatypes after unresolved fixups. The result therefore carries per-item outcomes, cascaded state
changes, implicit side effects, degradation records, and checkpoints. RPC completion is never
interpreted as semantic cleanliness or all-items success.

Destructive variants are distinct commands: `REPLACE_SOURCE` is not “sync,”
`DELETE_EVIDENCE_KEEP_CONTENT` is not ordinary delete, and `RESET_DESTINATION_CORRELATION` is not
the same operation as undoing content. A command that is not safely invertible requires a current
destination fingerprint plus snapshot/force policy; it never advertises generic undo.

## Queries, paging, and result arenas

Large queries create a revision-bound result resource. Pages use opaque continuation tokens bound
to the complete normalized query, sort, input generations, engine fingerprint, and result ID. A
cursor expires on arena eviction or relevant epoch change and fails explicitly.

Every result declares:

- `COMPLETE`, `COMPLETED_WITH_DEGRADATION`, `PARTIAL`, `TRUNCATED`, `CANCELLED_PARTIAL`,
  `STALE_INPUT`, `INDETERMINATE`, or `FAILED`;
- input start/end generations and consistency mode;
- returned coverage and omitted/unsupported categories;
- warnings independent of completion;
- engine, language/compiler, extension, and query/profile fingerprints;
- provenance for artifacts or external data.

Listing pages stream defined code units plus gaps, not one synthesized undefined byte at a time.
They preserve requested addresses, containing object starts, and offcut offsets. Instructions expose
logical and parsed ranges plus default/effective flow. Graph queries identify their block/flow/thunk
model; “CFG” is not a singular wire type.

Decompiler tokens, p-code operations, high variables, graph nodes, dynamic symbols, and datatype
component paths are result-local. Their references contain `ResultRef` and expire with it. Durable
mutation intents use semantic relocation anchors and are re-resolved/revalidated against current
content.

## Analysis and task model

Analysis is a staged reactive task graph, not one boolean. `AnalysisRun` records resolved analyzer
set, exact options/defaults, trigger, scope interpretation, language/compiler/extension
fingerprints, input generation, task stages, messages, failures, and quiescence generation.

Cancellation means one of:

- no changes occurred;
- an active domain-object transaction rolled back;
- valid partial analysis committed and is labeled partial;
- a worker was killed and its immutable output discarded;
- an external/repository outcome is unknown and requires reconciliation.

The result must choose explicitly. A generic `cancelled=true` is not enough.

## Debugger model

Debugger requests bind to `DebugConnectionRef`, `TargetRef`, `TraceRef`, `TimeSpec`, thread/frame,
platform mapping, and control authority. `TimeSpec` is a raw snap or a structured
[`TraceSchedule`](https://ghidra.re/ghidra_docs/api/ghidra/trace/model/time/schedule/TraceSchedule.html),
not an integer timestamp.

Memory responses return byte runs plus `KNOWN`, `UNKNOWN`, or `ERROR` state, coverage, source snap,
view schedule, live-read provenance, and partial failures. Mutations require one of `LIVE_TARGET`,
`TRACE_ONLY`, or `EMULATOR`; the server never infers it from the active UI tab. Step, resume, target
memory/register write, and breakpoint fan-out are not automatically retried after a lost reply.

Connection events are FIFO within one TraceRMI connection and carry an event sequence. Disconnect
advances `DebugEpoch`, expires all target/trace handles, and may leave the last control outcome
indeterminate. Reconnect creates a new connection identity rather than pretending to resume.

## Prompts and legacy interaction

Known interactions use typed prompt records with stable choices, redacted context, expiry,
authority, default policy, and whether cancellation leaves partial state. Passwords and credentials
use secret handles; they are never event payloads or audit text.

If Ghidra or an extension creates an unrecognized modal AWT/Swing window, the runtime pauses the
operation, reports `LEGACY_UI_REQUIRED`, and offers a visible compatibility-session lease. It must
not synthesize a default answer or hang a headless worker.

## Extension and script contracts

An engine extension advertises exact Ghidra/protocol compatibility, package/content digest,
dependencies, native libraries, services provided/required, DTO namespaces, operation descriptors,
property schemas, migrations, and authority. Changing the extension set creates a new runtime key
and extension epoch; hot class-loader mutation is not supported.

A script package declares language/runtime, entry point, content hash, deterministic inputs,
filesystem/network/process/secrets/live-target capabilities, quotas, and output schemas. General
Python/Java executes in a sandboxed runner. Required Ghidra writes return semantic proposals to the
`ProjectRuntime`; they do not hand the sandbox a live writable `Program` by default.

## Evolution rules

- Never reuse field numbers or enum numeric values; reserve removed values and names.
- Include `UNSPECIFIED = 0` for enums where absence is invalid, and reject it at semantic
  boundaries rather than guessing.
- Unknown enum values and unknown fields must survive compatible proxy/storage paths where
  Protobuf supports them; code must not collapse unknown to a known default. See the official
  [proto3 guide](https://protobuf.dev/programming-guides/proto3/).
- Adding an optional output field is compatible only when old clients can safely ignore it.
  Changing authority, atomicity, default scope, retry, or cancellation semantics requires a new
  method/version even if the wire shape is unchanged.
- Persisted proposals, audit records, and operation journals store their schema/type version and
  raw canonical payload so newer code can explain historical decisions.
- Capabilities are granular operation versions plus limits and authority; a broad `writes=true` or
  `debugger=true` flag is insufficient.

## Schema work gate

Do not begin bulk `.proto` generation until the edge-case ledger has assigned every v2 P0 finding
to an identity, operation, job, event, or explicit compatibility boundary. The first executable
schema should cover one end-to-end slice: open project safely, enumerate a tree snapshot, open a
program lease, page a sparse listing, plan/apply one semantic rename transaction, receive
invalidation, and reconnect with stale-reference rejection.
