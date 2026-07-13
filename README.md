# GhidraEx workbench prototypes

[![CI](https://github.com/SauceTaster/GhidraEx/actions/workflows/ci.yml/badge.svg)](https://github.com/SauceTaster/GhidraEx/actions/workflows/ci.yml)

This is an independent architecture experiment. It is not affiliated with or endorsed by the
National Security Agency or the Ghidra project.

Four runnable prototypes explore the same clean-break Ghidra workbench vertical slice:

| Prototype | Architecture | Entry point |
| --- | --- | --- |
| [JavaFX workbench](prototypes/javafx-workbench/README.md) | JavaFX 26 in-process shell with a toolkit-neutral engine facade | `./gradlew run` |
| [Web workbench](prototypes/web-workbench/README.md) | TypeScript/Vite shell with a loopback JDK 25 service and SSE | `./dev.sh` |
| [IntelliJ workbench](prototypes/intellij-workbench/README.md) | IntelliJ Platform plugin using native tool windows, actions, editors, and tasks | `./gradlew runIde` |
| [VS Code / Code OSS workbench](prototypes/vscode-workbench/README.md) | Native Activity Bar TreeViews and virtual Listing/Decompiler editors, with desktop Node and browser WebWorker bundles | `npm run open:desktop` / `npm run open:web` |

The prototypes intentionally use deterministic synthetic analysis data. They compare UI and
workbench architecture before the project pays the cost of binding four experiments directly to
Ghidra internals. The shared operations and evaluation gates are documented in
[the prototype contract](docs/prototype-contract.md); verified results are in
[the prototype results](docs/prototype-results.md). The next comparison increment is defined by the
[advanced Ghidra scenario pack](docs/advanced-scenario-pack.md) and its reusable
[scenario manifest](docs/advanced-scenarios.json).

## Requirements

- JDK 25
- Node.js 22 or later for the web and VS Code / Code OSS prototypes
- VS Code 1.100 or later for the desktop extension-host launch. The VSIX targets Code OSS and
  VSCodium too, but no local VSCodium binary has been tested in this workspace.

Every Java project includes a Gradle 9.1 wrapper. The ignored `.toolchains` directory may
optionally hold a repository-local JDK at `.toolchains/jdk-25/Contents/Home`.

## Quick start

Set the JDK once:

```bash
export JAVA_HOME="/path/to/jdk-25"
```

Run JavaFX:

```bash
cd prototypes/javafx-workbench
./gradlew run
```

Run the web shell and service:

```bash
cd prototypes/web-workbench
npm ci
./dev.sh
```

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
it does not open an embedded HTML workbench.

Run that same extension through a browser WebWorker host:

```bash
npm run open:web
```

Run every automated gate from the repository root:

```bash
./scripts/verify-all.sh
```

## Next integration seam

The next shared milestone is a read-only Ghidra adapter implementing program metadata, paged
listing retrieval, symbol search, decompilation, and streamed task progress. That adapter should
return immutable workbench documents rather than exposing `Program`, Swing components, or other
Ghidra object identities to the UI layers.

## License

Licensed under the [Apache License 2.0](LICENSE).
