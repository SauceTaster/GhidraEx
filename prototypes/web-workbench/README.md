# GhidraEx — web workbench prototype

A local-first TypeScript/Vite reverse-engineering workbench with two explicit engine modes:

- **Synthetic** is the zero-setup UI and advanced-workflow lab backed by a small JDK service.
- **Real** opens one native binary in the persistent, process-isolated Ghidra engine and reads
  bounded listing, symbol, reference, summary, and decompiler documents through protocol v1.

Real mode never falls back to fixture data under a real program identity.

## Run it

Install the frontend dependencies once with Node.js 22 or later:

```bash
npm ci
```

Start the synthetic workbench (JDK 25 required):

```bash
./dev.sh
```

Start the real workbench for a binary:

```bash
export GHIDRA_HOME="/path/to/ghidra_12.1.2_PUBLIC"
export GHIDRA_JAVA_HOME="/path/to/jdk-21"
./dev.sh /path/to/native-binary
```

`GHIDRAEX_BINARY=/path/to/native-binary ./dev.sh` is equivalent. Open
<http://127.0.0.1:5173> after the launcher reports that Vite is ready. Ghidra import and initial
analysis happen before the browser is started, so the first real launch can take several minutes.

In real mode, `dev.sh` creates an owner-only runtime directory, starts the authenticated gateway
with a lease on the launcher process, and waits for its private descriptor. The bearer token is
passed only to Vite's server process. Vite injects it into same-origin `/engine/v1/*` proxy calls;
the token is not placed in browser code, a URL, or normal diagnostics. Exiting the launcher closes
the listener, Ghidra process tree, and temporary project.

Useful launcher controls are:

- `GHIDRAEX_GATEWAY_STARTUP_TIMEOUT_SECONDS` (default `600`);
- `GHIDRAEX_GATEWAY_READINESS_TIMEOUT_SECONDS` (default `630`);
- `GHIDRAEX_SERVICE_PORT` for the synthetic JVM service (default `18787`); and
- `GHIDRAEX_READINESS_TIMEOUT_SECONDS` for synthetic startup (default `45`).

Ghidra and its JDK are discovered as described in
[`../../integration/ghidra/README.md`](../../integration/ghidra/README.md). Real mode needs Python
3.10 or later but does not need JDK 25. Synthetic mode automatically uses the repository-local
JDK at `../../.toolchains/jdk-25/Contents/Home` when available and system Java is unavailable.

## Vertical slice

- Dense responsive analyst shell with project explorer and target metadata
- Bounded, addressable listing viewport and synchronized inspector
- Real or synthetic symbol search and navigation
- Real Ghidra C decompilation and cross-reference summaries in real mode
- Keyboard command deck (`Cmd/Ctrl+K`), address navigation (`Cmd/Ctrl+G`), and symbol focus (`/`)
- Synthetic start/cancel analysis controls and SSE progress
- Code, Debugger, Scripts, and Extensions workspaces with advanced state fixtures
- Capability-driven controls: unsupported real debugger, mutation, scripting, and extension
  commands are disabled instead of being simulated
- Visible stale/offline state on transport, revision, context, or event-sequence failures

Engine addresses are opaque strings. The browser does not parse or numerically convert Ghidra
address-space values such as `ram:00401000`.

## Engine boundaries

Synthetic mode uses the dependency-free JDK service under `server/`:

| Method | Route | Shape |
| --- | --- | --- |
| `GET` | `/api/v1/snapshot` | Project, analysis, symbols, 50-row viewport, decompiler fixture, inspector |
| `GET` | `/api/v1/symbols?q=crypto` | Bounded symbol search |
| `GET` | `/api/v1/listing?address=0x00401200&rows=50` | Bounded viewport across 100,000 generated instructions |
| `POST` | `/api/v1/analysis/start` | Start/restart synthetic analysis |
| `POST` | `/api/v1/analysis/cancel` | Cancel synthetic analysis |
| `GET` | `/api/v1/events` | Named `engine` and `analysis` SSE events |

Real mode uses the canonical envelopes in [`../../integration/protocol`](../../integration/protocol)
through the authenticated gateway in
[`../../integration/gateway`](../../integration/gateway/README.md). The browser replays and
validates `session.ready`, negotiates `session.hello`, and then issues context-bound
`program.summary`, `listing.window`, `symbols.search`, `references.list`, and
`decompile.function` reads. Events are bounded reconnectable NDJSON. Every real read must retain
the negotiated `{programId, revision}` or the UI marks its view stale.

Protocol v1 is intentionally read-only: no program writes, live-target control, arbitrary script
execution, REPL, or third-party engine plugin activation is exposed yet. The authority-bearing
design for those features is in
[`../../docs/engine-architecture.md`](../../docs/engine-architecture.md).

## Security posture

- Both services bind literal IPv4 loopback only.
- The real gateway requires a fresh 256-bit bearer credential, exact `Host`, and exact browser
  `Origin`; loopback alone is never treated as authentication.
- Request bodies, responses, deadlines, connection count, event history, and stream duration are
  bounded.
- Real program paths are launch inputs and never RPC parameters.
- Browser responses disable caching and apply isolation/content headers.

This remains a local architecture prototype, not a remotely deployable multi-user service.

## Verify

```bash
npm test
npm run build
bash -n dev.sh
```

```bash
cd server
./gradlew test
```

Frontend tests cover command matching, address handling, advanced state, strict protocol
negotiation/correlation, real DTO mapping, canonical failures, and event gaps. Backend tests cover
immutable synthetic snapshots, detected-versus-connected truth, search, analysis events, JSON,
HTTP/CORS behavior, and SSE framing. The repository-level real test exercises this same worker and
gateway against an installed Ghidra release.
