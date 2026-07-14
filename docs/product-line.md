# GhidraEx product line

## Decision

GhidraEx should become one platform with several native products, not several forks of Ghidra and
not several implementations of reverse-engineering semantics. The durable split is:

- one process-isolated engine platform and protocol;
- one host-neutral semantic state and conformance layer;
- native JavaFX, JetBrains, VS Code / Code OSS, and web clients;
- capability planes for projects, debugging, scripting, plugins, and agents that are shared by the
  products and enabled only when the connected runtime advertises them.

The current repository proves important pieces of this shape, but it is not yet a production
suite. Only the web prototype consumes real Ghidra data. JavaFX, JetBrains, and VS Code still use
deterministic fixture adapters, and JSON protocol v1 owns one immutable imported program. Product
names and release packaging must not conceal those gaps.

## Product map

| Product | Primary user and deployment | Native value | Current state | First credible product gate |
| --- | --- | --- | --- | --- |
| **GhidraEx Workbench** | Reverse engineers wanting a standalone desktop application | Purpose-built JavaFX shell, layout, dense listing, unrestricted product design | Usable fixture workbench; no real host adapter | Open an arbitrary binary through the supported engine, complete every v1 read workflow, recover visibly from engine loss, and ship a platform-specific signed package |
| **GhidraEx for JetBrains** | Developers working inside IntelliJ-family IDEs or Remote Development | Native editors, actions, tool windows, navigation, search, notifications, and IDE workspace behavior | Usable fixture plugin; Ghidra may be detected but is not connected | Pass the real-read conformance suite in a declared IDE build matrix and prove backend-side placement for Remote Development |
| **GhidraEx for VS Code** | Desktop, remote-workspace, Code OSS, and eventually browser users | Native text documents, language providers, TreeViews, DAP, terminals, tasks, commands, and extension ecosystem | Usable fixture extension for desktop and WebWorker hosts; local/remote backend selections are contracts, not providers | Connect the desktop workspace extension host to the real engine, connect the browser profile to a secured remote service, and test Code OSS/VSCodium explicitly |
| **GhidraEx Web** | Zero-install review, collaboration, and remote-service users | Fast visual iteration and a browser-native shared workspace | First real read-only host through the local development launcher; not a production remote service | Package a deployable client and broker with user authentication, TLS, authorization, quotas, tenant isolation, and an owned session lifecycle |
| **GhidraEx Engine** | Local clients, remote extension hosts, and service operators | One authoritative boundary around Ghidra process, project, decompiler, and later debugger state | Real-tested Ghidra 12.1.2 JSON v1 worker, supervisor, and authenticated loopback gateway; source-tree launch only | Installable, fingerprinted engine package with restart policy, progress, resource containment, compatibility manifest, and supportable diagnostics |
| **GhidraEx SDK** | Client authors, integrations, and internal product teams | Generated protocol clients, reducers, capability types, fixtures, and conformance tools | Shared Java reducer plus a manually mirrored TypeScript implementation; no published SDK and no v2 schema | Canonical Protobuf v2 schemas, generated Java/TypeScript clients, versioned packages, and cross-language fixture parity |

The four clients are products because they serve different workflows and deployment environments.
The engine and SDK are platform products because their compatibility and release cadence affect
every client. They should remain in this monorepo until their interfaces and ownership are stable.

### Capabilities, not more products yet

Debugging, project/repository mutation, scripts, plugins, and agent automation should initially be
capability planes shared by all applicable clients:

| Capability plane | Backend owner | Product projection | Current truth |
| --- | --- | --- | --- |
| Read and analyze | Engine session now; future `ProjectRuntime` and snapshot workers | Listing, symbols, decompiler, references, analysis jobs | Real bounded reads exist only through JSON v1; three retained native clients still need adapters |
| Projects and mutation | Future broker plus `ProjectRuntime` | Project explorers, previews, approvals, undo/recovery, repository operations | Research and policy only; no production write API |
| Debug | Future `DebugRuntime` | JavaFX debugger, JetBrains debugger UI, VS Code DAP, web trace views | Fixture UI models only; no TraceRMI or live-target authority |
| Scripts and extensions | Sandboxed runners and extension-scoped runtimes | Native consoles, editors, tasks, inventories, and controlled compatibility UI | Fixture/gated surfaces only; arbitrary Python/Java and engine plugin lifecycle are unavailable |
| Agents | Broker policy, evidence, proposals, approvals, and audit log | Evidence views, diffs, approval UI, durable task status | Read-only UI experiments and policy research; no agent write authority |

A capability becomes a separate commercial or operational product only when it needs an
independent operator, trust boundary, scaling model, or support policy. UI prominence alone is not
a reason to create another backend or protocol.

## Shared platform boundary

```text
GhidraEx Workbench    GhidraEx for JetBrains    GhidraEx for VS Code    GhidraEx Web
          \                    |                       |                    /
           +------- generated clients + semantic state + conformance -----+
                                      |
                          authenticated broker / gateway
                                      |
             ProjectRuntime | snapshot workers | DebugRuntime | sandboxes
                                      |
                          supported Ghidra distributions
```

### Shared core responsibilities

1. **Protocol and identity.** Runtime epochs, program generations, opaque addresses, leases,
   capabilities, job outcomes, event ordering, and bounded payloads have one definition. Protobuf
   v2 should generate client records; host code must not create competing wire models.
2. **Semantic state.** Latest-request acceptance, stale/partial/resync state, cancellation,
   disposal, result bounds, and navigation coordinates are shared behavior. Controls remain native.
3. **Engine lifecycle.** Discovery, launching, authentication, Ghidra/JDK selection, temporary
   projects, cleanup, process-tree termination, and compatibility fingerprints stay below the
   clients. A host must not parse Ghidra stdout or load Ghidra classes directly.
4. **Authority and audit.** The broker owns authentication, leases, operation journals, previews,
   approvals, audit records, and policy. A DAP command, IDE action, terminal command, or agent tool
   does not bypass it.
5. **Conformance.** The same semantic fixtures, malformed-result tests, event-gap tests, real
   binary corpus, and lifecycle fault injections gate every adapter.

### Host-owned responsibilities

Each client owns its shell, layout, focus, keyboard handling, accessibility, command discovery,
editor and debugger integration, notifications, and local presentation caches. Shared widgets are
not a goal. A graph or memory canvas may use a focused custom surface, but it must project shared
semantic state rather than becoming a second application inside the host.

### Repository ownership

The existing directories already approximate the intended boundaries:

| Area | Long-term owner |
| --- | --- |
| `integration/protocol` | Wire schemas, envelopes, negotiated capabilities, and compatibility fixtures |
| `integration/view-state` | Host-neutral reducers and acceptance semantics; generated records replace handwritten duplicates |
| `integration/ghidra` | Ghidra launch, process supervision, workers, and real compatibility tests |
| `integration/gateway` | Local transport until the broker subsumes it; authentication, replay, leases, and backpressure |
| `prototypes/*` | Product-specific native projections and bootstrap code only |
| `docs/research` | Edge-case ledger, v2 requirements, runtime topology, and release-blocking proofs |

Do not split repositories while a routine feature still requires atomic changes to the protocol,
two generated clients, the engine, and all product conformance fixtures. Publishable packages do
not require separate source repositories.

## Release model

### One train before 1.0

Use one coordinated semantic version for all release assets until the generated SDK and engine
compatibility contract are stable. A single GitHub release makes it clear which clients, engine,
and manifest were tested together.

| Channel | Git reference | GitHub treatment | Purpose |
| --- | --- | --- | --- |
| Nightly | No tag; artifact name `nightly-<short-sha>` | Actions artifacts only, with short retention | Developer feedback from the default branch; never an update target |
| Preview | `vX.Y.Z-alpha.N`, for example `v0.2.0-alpha.1` | GitHub prerelease | Real-user testing with known missing capabilities and no compatibility expectation across preview minors |
| Candidate | `vX.Y.Z-rc.N` | GitHub prerelease | Frozen feature set; signing, packaging, upgrade, and compatibility verification |
| Stable | `vX.Y.Z` | Normal GitHub release | Only after every advertised product/support cell passes its release gate |

Tags are immutable. Do not maintain moving `latest`, `preview`, or `nightly` Git tags. GitHub's
release/prerelease state and an update manifest can name channels without weakening provenance.

Development builds retain local snapshot/package defaults, but the release path now injects one
validated version into the shared Java core, JavaFX distribution, JetBrains plugin, and VSIX. It
rejects `SNAPSHOT` archive entries and mismatched embedded manifests. `v0.2.0-alpha.1` remains an
example, not a claim that the repository is already at that version. Stable tags are blocked in
the release catalog while the eligible native products are fixture-backed.

### Release assets

A coordinated release should eventually contain:

| Asset | Contents and conditions |
| --- | --- |
| `ghidraex-workbench-<version>-<os>-<arch>.<package>` | Standalone JavaFX product with owned runtime; until native packaging exists, label the portable JDK-dependent ZIP as a developer preview |
| `ghidraex-jetbrains-<version>.zip` | Installable plugin, including its exact tested IDE build range |
| `ghidraex-vscode-<version>.vsix` | Desktop and browser bundles; the manifest must distinguish desktop-local and browser-remote backend support |
| `ghidraex-web-<version>.tar.gz` | Deployable static client plus configuration contract; do not present the current local development launcher as a multi-user server |
| `ghidraex-engine-<version>-<os>-<arch>.tar.gz` | Supervisor, gateway/broker, workers, launch metadata, notices, and supported Ghidra/JDK fingerprints; Ghidra itself is not silently redistributed |
| `ghidraex-sdk-<version>.tar.gz` | Protobuf sources, generated client packages, conformance fixtures, and API documentation after v2 exists |
| `release-manifest.json` | Commit, source tag, product versions, protocol versions, engine fingerprints, toolchains, capability status, test matrix, and artifact digests |
| `SHA256SUMS` | Digest for every downloadable asset |
| Per-artifact `*.spdx.json` and attestations | Required from the first public preview; every executable archive receives both build-provenance and SBOM attestations |

The first GitHub preview may publish only the three product families that are currently packageable:
platform-qualified JavaFX distribution ZIPs, the JetBrains plugin ZIP, and the VSIX. Their ZIP
metadata is normalized during release assembly, but this is not yet a cross-run reproducibility
claim. Web and engine archives should not be
invented by zipping arbitrary source directories; add them when an installation and owned cleanup
contract exists. Every release description must say which workflows are fixture-only.

### When to split release versions

Move from the train to product tags only after all of the following are true:

1. The SDK and protocol are independently versioned and published.
2. Capability negotiation allows a client to work with more than one engine release.
3. Compatibility CI tests those supported combinations, not just a source-tree build.
4. At least one client needs a materially different cadence for two consecutive release cycles.

At that point use explicit tags such as `workbench-v1.2.0`, `jetbrains-v1.1.0`,
`vscode-v1.4.0`, `web-v1.0.0`, `engine-v2.0.0`, and `sdk-v2.0.0`. Do not introduce those tag
families early: synchronized `0.x` versions are cheaper to understand and test.

## Compatibility promises

Version numbers alone never establish runtime compatibility. A release supports only the cells in
its `release-manifest.json`, and a connection still requires protocol and capability negotiation.

### Promise by layer

| Layer | Pre-1.0 promise | Intended 1.0 promise |
| --- | --- | --- |
| Product API and behavior | Minor releases may change commands, settings, layout persistence, and preview schemas; patch releases do not intentionally break documented workflows | Semantic Versioning per independently released product |
| JSON protocol v1 | Frozen, bounded, immutable read compatibility slice; no writes/debug/scripts/plugins are added by pretending they are generic methods | Supported only as an explicitly deprecated compatibility adapter after v2 clients ship |
| Protobuf protocol v2 | No promise until schemas and generated conformance packages exist | Field-number stability, additive evolution by default, negotiated capabilities, and a published support window |
| Engine/client combinations | Only combinations built and tested in the same release train | Current and declared prior engine/SDK versions, proven by matrix CI |
| Ghidra | Exact tested build and JDK fingerprint only | A published matrix per OS, Ghidra build, JDK, extension digest, and capability plane |
| Persisted Ghidra projects | No implicit upgrade, downgrade, or cross-build write promise | Explicit maintenance jobs with preflight, backup/export, downtime, and recovery instructions |
| Host platforms | Exact tested baseline only; broad manifest ranges are not evidence | Published IDE/editor/browser/OS matrix with a removal policy |
| Workspace/layout state | Best effort across `0.x`; never used as program truth | Versioned migrations or a documented reset path |

### Current compatibility floor

The repository may accurately claim only the following today:

- the real backend is exercised against official Ghidra 12.1.2 with its supported JDK 21 lane;
- JavaFX uses JDK 25 and JavaFX 26; automated builds do not replace signed installer and GUI tests
  on each advertised operating system;
- the JetBrains plugin declares build `251` as its minimum and is tested with IntelliJ IDEA
  Community 2025.1.5; the absent upper bound is not a promise about future IDE releases;
- the VS Code extension declares VS Code 1.100 or later and has desktop plus browser-host tests;
  Code OSS/VSCodium are targets but remain unverified until their own CI lane exists;
- a VS Code browser extension cannot launch a local Ghidra process, and the current web launcher is
  not a secured remote multi-user service;
- Ghidra analysis of hostile inputs is process-isolated but not yet a strong OS sandbox.

If a product cannot meet an advertised cell, omit that cell from the release instead of enabling a
fixture fallback. Fixture mode remains a separately labeled demonstration mode.

## Staged roadmap

The roadmap is horizontal: complete a capability through the engine, SDK, conformance suite, and
every applicable product before starting independent host implementations of the next capability.

### Stage 0: reproducible preview train

- Add a tag/manual GitHub release workflow with least-privilege permissions.
- Normalize one version across Gradle and npm packages.
- Build the JavaFX ZIP, JetBrains ZIP, and VSIX from a clean checkout; attach checksums and a
  machine-readable manifest.
- Run the existing full verifier plus package-structure checks before upload.
- Mark the release as prerelease and state prominently that the three native products use fixtures.
- Add artifact signing, provenance, and SBOM work before declaring any stable channel.

Exit: another machine can verify that each attached artifact came from the tagged commit and can
launch its deterministic workflow without repository-local build output.

### Stage 1: one real read-only platform across every client

- Package the current engine/gateway lifecycle instead of asking each product to launch Python
  source from the repository.
- Implement typed Java and TypeScript JSON v1 compatibility clients behind the existing async
  read interfaces.
- Wire Workbench, JetBrains, and VS Code desktop to real `summary`, listing, symbol, reference, and
  decompiler operations with no synthetic substitution on failure.
- Preserve exact identity, bounds, cancellation, stale/resync state, parent lease, and cleanup.
- Keep browser clients remote-only and add a deliberate endpoint/credential contract.
- Run the same real fixture and fault suite through every adapter.

Exit: all four clients can inspect the same arbitrary binary read-only, and a failed engine is
visible and recoverable without mixing fixture data into the real program identity.

### Stage 2: v2 SDK and project-runtime read model

- Prove sparse listing, address spaces/overlays/offcuts, event replay, and large text/graph payloads.
- Define canonical Protobuf schemas and generate Java/TypeScript clients.
- Add native desktop gRPC and a browser-safe unary/server-stream transport.
- Introduce broker leases, `ProjectRuntime`, safe-open preflight, import progress, multiple program
  identities, engine fingerprints, and explicit restart/recovery.
- Publish the SDK and compatibility matrix; retire handwritten wire-record duplication.

Exit: client and engine releases can evolve independently under negotiated, tested compatibility.

### Stage 3: projects, writes, repositories, and collaboration

- Add intent-level previews and short server-owned transactions with expected generations,
  idempotency, audit records, and invalidation events.
- Implement safe open, crash-recovery choices, undo/redo semantics, and maintenance jobs.
- Model repository operations as durable sagas with reconciliation for unknown outcomes.
- Add a controlled visible Swing compatibility lease for merge and extension-defined workflows that
  have no semantic API yet.
- Expose the same approval and outcome model through every applicable product.

Exit: no mutation is possible through an unversioned command, hidden dialog, or un-audited agent
path, and crash/reconnect tests prove the declared outcomes.

### Stage 4: debugger, scripts, and extension ecosystem

- Build `DebugRuntime` around connection, target, trace, time/schedule, platform, and control
  authority; project it into JavaFX/JetBrains debugger surfaces and VS Code DAP.
- Add killable, quota-bound script runners with explicit filesystem/network/mutation capabilities.
- Define signed extension manifests, compatibility fingerprints, classloader/process isolation,
  lifecycle, and failure attribution.
- Keep arbitrary code out of the broker and host extension processes.

Exit: debug control, scripts, and plugins have separate leases and fault domains, and every action
reports whether it was applied, cancelled before apply, rolled back, partial, or outcome unknown.

### Stage 5: remote service and agent workflows

- Add TLS, user/workspace identity, authorization, secret storage, quotas, tenancy isolation,
  durable operation journals, observability, backup, and operator upgrade procedures.
- Make Web and VS Code browser first-class remote clients.
- Give agents immutable evidence packages, scoped capabilities, preview/apply separation, explicit
  approvals, idempotency, audit, and revocation.
- Validate accessibility, collaboration conflicts, large-project scale, and hostile-input resource
  containment across the supported matrix.

Exit: remote and agent claims are backed by operational and security tests, not by the presence of
a browser page, terminal, or model-driven command surface.

## Near-term ownership rules

For the next implementation slice:

1. Treat the real-read adapter as shared platform work. Finish it in Java and TypeScript before
   adding host-only feature depth.
2. Preserve the web client as the real-backend canary, but do not let its HTTP/JSON shapes become
   the v2 domain model.
3. Keep fixture adapters as deterministic demos and conformance tools behind an explicit mode.
4. Make every product report backend identity, protocol, capabilities, Ghidra build, freshness, and
   partial/stale state in a user-visible diagnostics surface.
5. Require one cross-product scenario manifest for navigation, cancellation, event gaps, engine
   restart, and cleanup.
6. Do not publish marketplace listings or call a channel stable until signing, update behavior,
   support matrix, licensing/notices, and real-adapter claims are verified.

This decomposition preserves genuine product differentiation at the UI and deployment layers while
keeping the expensive, dangerous semantics in one testable platform.
