# Contributing

GhidraEx currently contains architecture prototypes, not a production Ghidra fork. Contributions
should keep engine boundaries toolkit-neutral and avoid coupling presentation code directly to
mutable Ghidra domain objects.

## Development setup

Install JDK 25 and Node.js 22 or later. Every Java project includes its Gradle wrapper; npm
dependencies are locked and should be installed with `npm ci`.

Run the complete verification suite from the repository root:

```bash
export JAVA_HOME="/path/to/jdk-25"
./scripts/verify-all.sh
```

The suite builds and tests all four prototypes, exercises the VS Code browser extension host, and
packages the IntelliJ and VS Code artifacts. Build outputs, downloaded IDEs, toolchains, and npm
dependencies must remain untracked.

## Pull requests

- Keep changes focused on one prototype or one shared architectural concern.
- Add deterministic tests for engine, navigation, protocol, or lifecycle behavior.
- Document any new runtime, network, filesystem, or process authority.
- Preserve cancellation, stale-result rejection, bounded retrieval, and read-only defaults.
- Do not commit proprietary binaries, analyzed programs, credentials, or generated build output.

By contributing, you agree that your contribution is licensed under the repository's
[Apache License 2.0](LICENSE).
