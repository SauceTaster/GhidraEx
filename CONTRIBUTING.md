# Contributing

GhidraEx currently contains architecture prototypes, not a production Ghidra fork. Contributions
should keep engine boundaries toolkit-neutral and avoid coupling presentation code directly to
mutable Ghidra domain objects.

## Development setup

Install JDK 25, Node.js 22 or later, and Python 3.10 or later. Every Java project includes its
Gradle wrapper; npm dependencies are locked and should be installed with `npm ci`.

Run the complete verification suite from the repository root:

```bash
export JAVA_HOME="/path/to/jdk-25"
./scripts/verify-all.sh
```

The suite builds and tests all four prototypes, exercises the VS Code browser extension host,
packages the IntelliJ and VS Code artifacts, and runs the protocol, persistent supervisor, and
gateway lifecycle/security suites. Real batch and persistent tests report `skipped` when Ghidra or
a GCC/Clang-compatible compiler is absent; if they are discoverable, any import, analysis, paging,
decompilation, or cleanup failure fails the suite. Use `GHIDRA_JAVA_HOME` for the Ghidra JDK 21
without changing the prototypes' JDK 25 `JAVA_HOME`. Build outputs, downloaded IDEs, toolchains,
Python bytecode, and npm dependencies must remain untracked.

## Release changes

Run `python3 scripts/release.py plan --version <semver> --product all` when changing packaging,
versions, product eligibility, or release metadata. Release tags use one coordinated pre-1.0
version and are accepted only for preview/candidate channels while native clients remain
fixture-backed. Do not create product-specific tags, bundle Ghidra, remove license/notice files,
or hand-upload replacement assets. The [release contract](release/README.md) and
[product-line decision](docs/product-line.md) define the current gates.

## Pull requests

- Keep changes focused on one prototype or one shared architectural concern.
- Add deterministic tests for engine, navigation, protocol, or lifecycle behavior.
- Document any new runtime, network, filesystem, or process authority.
- Preserve cancellation, stale-result rejection, bounded retrieval, and read-only defaults.
- Do not commit proprietary binaries, analyzed programs, credentials, or generated build output.

By contributing, you agree that your contribution is licensed under the repository's
[Apache License 2.0](LICENSE).
