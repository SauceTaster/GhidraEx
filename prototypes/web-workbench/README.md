# GhidraEx — web workbench prototype

A local-first web shell around a synthetic JVM analysis engine. This prototype exercises the risky product seam: a dense reverse-engineering workbench in the browser, fed by coarse versioned engine documents and server-sent state updates rather than per-object RPC.

## Run it

Prerequisites: Node.js 22+ and JDK 25. From this directory:

```bash
npm install
./dev.sh
```

Open <http://127.0.0.1:5173>. The launcher starts the JVM service on `127.0.0.1:8787` and starts Vite only after receiving a valid API v1 project snapshot. If readiness is not confirmed within 45 seconds, it shuts the service down and exits without starting the frontend. It automatically uses the repository-local JDK at `../../.toolchains/jdk-25/Contents/Home` when one exists and no system Java is available.

To run the processes separately:

```bash
cd server
./gradlew run
```

```bash
npm run dev
```

## Vertical slice

- Dense, responsive dark analyst workbench with project explorer and target metadata
- Searchable functions/imports/labels backed by the service
- Selectable synthetic x86-64 listing backed by 100,000 deterministic, addressable instructions
- Syntax-colored decompiler output synchronized with an inspector surface
- Keyboard command deck (`Cmd/Ctrl+K`), address navigation (`Cmd/Ctrl+G`), and symbol focus (`/`)
- Start/cancel analysis controls with phase/progress updates over SSE
- Graceful service-offline state and compact layouts down to a narrow viewport

## Workbench API v1

The service is dependency-free JDK code using virtual threads and the built-in HTTP server.

| Method | Route | Shape |
| --- | --- | --- |
| `GET` | `/api/v1/snapshot` | Project, target, analysis, initial symbols, 50-row listing viewport/extent, decompiler and inspector document |
| `GET` | `/api/v1/symbols?q=crypto` | Bounded symbol search result |
| `GET` | `/api/v1/listing?address=0x00401200&rows=50` | Deterministic bounded viewport centered anywhere across 100,000 instructions (8–120 rows) |
| `POST` | `/api/v1/analysis/start` | Start/restart the synthetic analysis pipeline |
| `POST` | `/api/v1/analysis/cancel` | Cancel a running pipeline |
| `GET` | `/api/v1/events` | Named `engine` and `analysis` server-sent events plus keepalives |

The replacement seam for real Ghidra is `server/src/main/java/ex/ghidra/web/SyntheticEngine.java`. Its immutable snapshots model the intended adapter: fetch viewport-sized documents, issue coarse commands, and stream small state changes. The 100,000-row synthetic address space is generated on demand; neither the engine nor the wire snapshot materializes the whole listing. The frontend never receives live Ghidra object identities.

## Security posture

- HTTP binds explicitly to IPv4 loopback; the host is not configurable.
- Browser requests with an `Origin` are accepted only from `http://127.0.0.1:5173`.
- Mutations use `POST`, unknown methods return `405`, response caching is disabled, and basic isolation/content headers are set.
- Query size and listing window size are bounded. There is no filesystem or process API in this prototype.

This is suitable for local architecture evaluation, not for exposure through a reverse proxy. A remote version needs authentication, authorization, TLS, CSRF strategy, resource quotas, and program-level isolation.

## Verify

```bash
npm test
npm run build
```

```bash
cd server
./gradlew test
```

The frontend tests cover command matching, progress hardening, address parsing, and coarse viewport navigation. The backend tests cover immutable snapshots, search, analysis events, JSON encoding, HTTP/CORS behavior, and SSE framing.
