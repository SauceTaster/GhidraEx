# GhidraEx workbench prototypes

[![CI](https://github.com/SauceTaster/GhidraEx/actions/workflows/ci.yml/badge.svg)](https://github.com/SauceTaster/GhidraEx/actions/workflows/ci.yml)
[![Real Ghidra](https://github.com/SauceTaster/GhidraEx/actions/workflows/ghidra-integration.yml/badge.svg)](https://github.com/SauceTaster/GhidraEx/actions/workflows/ghidra-integration.yml)
[![Release train](https://github.com/SauceTaster/GhidraEx/actions/workflows/release.yml/badge.svg)](https://github.com/SauceTaster/GhidraEx/actions/workflows/release.yml)

This is an independent architecture experiment. It is not affiliated with or endorsed by the
National Security Agency or the Ghidra project.

Four runnable prototypes explore the same clean-break Ghidra workbench vertical slice:

| Prototype | Architecture | Entry point |
| --- | --- | --- |
| [JavaFX workbench](prototypes/javafx-workbench/README.md) | JavaFX 26 shell with listing/decompiler plus debugger, scripting, and extension workspaces | `./gradlew run` |
| [Web workbench](prototypes/web-workbench/README.md) | TypeScript/Vite host with synthetic JDK/SSE mode and a real persistent Ghidra v1 mode | `./dev.sh` / `./dev.sh /path/to/binary` |
| [IntelliJ workbench](prototypes/intellij-workbench/README.md) | Native tool windows, editors, actions, debugger state, `ConsoleView` REPL, and runtime inventory | `./gradlew runIde` |
| [VS Code / Code OSS workbench](prototypes/vscode-workbench/README.md) | Seven native TreeViews, virtual editors, inline DAP, pseudoterminal REPL, and desktop/web bundles | `npm run open:desktop` / `npm run open:web` |

The four UI prototypes still default to deterministic synthetic program/debugger fixtures so every
host remains independently runnable. The repository now also contains a proven
[real, process-isolated Ghidra engine](integration/ghidra/README.md). It retains the original
deterministic batch exporter and adds a persistent Ghidra worker, a strict
[versioned protocol](integration/protocol/README.md), and a
[token-authenticated loopback gateway](integration/gateway/README.md). A real Ghidra 12.1.2 test
imports a native binary and exercises summary, listing paging, symbols, references, decompilation,
ready-event replay, liveness, graceful shutdown, and project cleanup through that boundary.

The web workbench is the first real host: when launched with a binary, it consumes the persistent
engine through the authenticated gateway and does not substitute fixtures on failure. JavaFX,
IntelliJ, and VS Code / Code OSS still have synthetic host adapters. Detection, batch availability,
engine readiness, and a host connection remain distinct states. Finding `GHIDRA_HOME` never enables
writes, live debugging, arbitrary scripts, or third-party plugins.

The shared operations and evaluation gates are documented in
[the prototype contract](docs/prototype-contract.md), verified results are in
[the prototype results](docs/prototype-results.md), and the difficult workflows live in the
[advanced Ghidra scenario pack](docs/advanced-scenario-pack.md) and its reusable
[scenario manifest](docs/advanced-scenarios.json). The process, security, lifecycle, and future
authority boundaries are in the [engine architecture](docs/engine-architecture.md). The deeper
Ghidra source audit, v2 project-runtime/Protobuf direction, native-host tradeoffs, agent policy,
edge-case solutions, and conformance backlog are indexed in
[architecture research](docs/research/README.md). The first cross-host architecture proof is the
[generation-aware view-state spine](integration/view-state/STATE_SPINE.md): bounded semantic
listing windows plus contextual program/symbol/decompiler/reference reads, exact response
acceptance, explicit stale/partial/resync states, bounded cancellation-aware workers, and native
projections for JavaFX, IntelliJ, and VS Code.

The coordinated pre-1.0 [product line](docs/product-line.md) and
[release engineering contract](release/README.md) treat Workbench, JetBrains, VS Code, Web,
Engine, and the future SDK as separate products over one protocol/state platform. Valid
preview/candidate tags build platform-qualified Workbench ZIPs, a JetBrains plugin, and a VSIX;
the workflow attaches per-artifact SPDX SBOMs, checksums, a capability-truth manifest, and GitHub
provenance to a reviewable draft release. Stable tags and invented Web/Engine source archives are
blocked until their stated product gates pass.

## Requirements

- JDK 25 for the synthetic Java hosts; the real web mode uses Ghidra's JDK 21 instead
- Node.js 22 or later for the web and VS Code / Code OSS prototypes
- Python 3.10 or later for the protocol, supervisor, and gateway
- VS Code 1.100 or later for the desktop extension-host launch. The VSIX targets Code OSS and
  VSCodium too, but no local VSCodium binary has been tested in this workspace.

Running the conditional real integration test additionally requires Ghidra 12.1.x, a JDK 21
supported by Ghidra, and a GCC/Clang-compatible C compiler. The runtimes stay separate:
`JAVA_HOME` selects JDK 25 for the prototypes and `GHIDRA_JAVA_HOME` selects JDK 21 for the child
process.

Every runnable Java prototype includes a Gradle 9.1 wrapper; the shared view-state included build
uses the JavaFX wrapper in the repository verifier. The ignored `.toolchains` directory may
optionally hold a repository-local JDK at `.toolchains/jdk-25`; the verifier accepts `bin/java`
there directly or under the macOS `Contents/Home` layout.

## Quick start

Set the UI JDK once (not needed for a real-only web launch):

```bash
export JAVA_HOME="/path/to/jdk-25"
```

Run JavaFX:

```bash
cd prototypes/javafx-workbench
./gradlew run
```

Run the web shell with its synthetic JVM service:

```bash
cd prototypes/web-workbench
npm ci
./dev.sh
```

Run the same web host against a real binary:

```bash
export GHIDRA_HOME="/path/to/ghidra_12.1.2_PUBLIC"
export GHIDRA_JAVA_HOME="/path/to/jdk-21"
cd prototypes/web-workbench
npm ci
./dev.sh /path/to/native-binary
```

Real mode imports and analyzes before Vite starts, then renders context-bound summary, listing,
symbols, references, and decompilation. Its gateway credential remains in the launcher/Vite proxy;
the browser never receives it. Unsupported write, analysis-control, debugger, script/REPL, and
engine-plugin actions remain disabled by negotiated capabilities.

Run the IntelliJ sandbox:

```bash
cd prototypes/intellij-workbench
./gradlew runIde
```

Run the VS Code / Code OSS extension in a desktop host:

```bash
cd prototypes/vscode-workbench
npm ci
npm run open:desktop
```

In the Extension Development Host, use **GhidraEx: Open Workbench** from the Command Palette or
select the GhidraEx Activity Bar item. The command opens a native Listing/Decompiler editor split;
it does not open an embedded HTML workbench. The Activity Bar also exposes native Debug Sessions,
Scripting & REPL, and Capabilities & Plugins views.

Run that same extension through a browser WebWorker host:

```bash
npm run open:web
```

Probe and test the real backend:

```bash
export GHIDRA_HOME="/path/to/ghidra_12.1.2_PUBLIC"
export GHIDRA_JAVA_HOME="/path/to/jdk-21"
python3 integration/ghidra/ghidra_bridge.py doctor
python3 -m unittest discover -s integration/protocol/tests -v
python3 -m unittest discover -s integration/ghidra/tests -v
python3 -m unittest discover -s integration/gateway/tests -v
python3 integration/ghidra/ghidra_bridge.py analyze /path/to/binary \
  --output /tmp/ghidraex-facts.json
```

The dependency-free protocol, fake-worker, and gateway tests always run. Real batch and persistent
tests report `skipped` when Ghidra or a compiler is absent and become hard compatibility gates as
soon as both are discoverable.

For protocol or adapter development without the web host, start the real gateway directly:

```bash
python3 integration/gateway/gateway.py /path/to/binary \
  --ghidra-home "$GHIDRA_HOME" \
  --ghidra-java-home "$GHIDRA_JAVA_HOME" \
  --metadata-file /tmp/ghidraex-gateway.json
```

The descriptor path must not already exist. It is created with owner-only permissions, removed on
exit, and contains the private loopback URL and bearer token. The launching host is identity-leased;
if it exits or its PID is reused, the gateway closes the listener and owned Ghidra session. For
normal interactive use, prefer the web launcher's `./dev.sh /path/to/binary` flow because it owns
this descriptor, gateway, Vite proxy, and cleanup as one lifecycle.

Run every automated gate from the repository root:

```bash
./scripts/verify-all.sh
```

## Engine direction

The persistent read boundary now exists and is real-tested. One gateway/supervisor/worker unit owns
one imported program at immutable revision `0`; host adapters consume bounded JSON and never receive
Ghidra objects, filesystem handles, Swing components, or classloader identities. Startup identity,
program SHA-256, stdio nonces, bearer authentication, exact Host/Origin checks, parent leases,
context matching, and bounded replay are part of that boundary.

Ignored cooperative cancellation now escalates to bounded process-tree termination and session
invalidation. The next read-path engineering is an explicit restart/recovery policy, pre-ready
import progress, OS resource containment, and a broader Ghidra/platform compatibility matrix.
Transactions and undo, Debugger Trace/RMI, sandboxed scripting/REPL, engine plugins, and agent write
authority are later capability planes—not generic code-execution methods. See the
[engine architecture](docs/engine-architecture.md) for the implemented v1 boundary and the
[architecture research](docs/research/README.md) for the proposed mutable v2 model.

## License

Licensed under the [Apache License 2.0](LICENSE).
