# GhidraEx Workbench developer preview

This archive contains the platform-qualified JavaFX desktop workbench. It currently uses a
deterministic analysis fixture; it does not bundle Ghidra and is not a stable release.

Requirements: a JDK 25 runtime matching this archive's operating system and architecture.

Run on macOS or Linux:

```text
bin/ghidra-fx-workbench
```

Run on Windows:

```text
bin\ghidra-fx-workbench.bat
```

Set `JAVA_HOME` to the JDK 25 installation if `java` is not already available. The archive is
unsigned, so operating-system trust prompts may require an explicit local developer override.
Setting `GHIDRA_HOME` only adds a detected installation to the runtime inventory; this preview does
not connect its listing, decompiler, debugger, script console, or extensions to that installation.

Before running, verify the artifact against `SHA256SUMS` and its GitHub attestation. License and
OpenJFX notice materials are included beside this file and under `legal/openjfx`.
