# Security policy

## Supported versions

GhidraEx is currently a pre-1.0 architecture preview. There is no stable security-support branch
or production deployment claim yet. The default branch receives fixes, and a tagged preview is
supported only for the exact product, protocol, Ghidra, JDK, host, and capability cells recorded
in its `release-manifest.json`.

Deterministic fixture clients are not real Ghidra sandboxes. The real engine isolates analysis in
a supervised process, but it is not yet a hardened hostile-input sandbox or multi-tenant service.
Ghidra is never bundled in GhidraEx artifacts.

## Reporting a vulnerability

Use [GitHub private vulnerability reporting](https://github.com/SauceTaster/GhidraEx/security/advisories/new)
for suspected vulnerabilities. Do not open a public issue for credentials, authentication bypass,
process escape, arbitrary code execution, unsafe binary parsing, release-pipeline compromise, or
another issue that would put users at immediate risk.

Please include the affected commit/release, product and host, backend mode, Ghidra/JDK versions,
reproduction steps, impact, and any temporary mitigation. Avoid attaching proprietary binaries,
live credentials, or data you are not authorized to share. Maintainers will acknowledge a useful
report as soon as practical and coordinate disclosure after a fix or mitigation exists.

## Release verification

Public preview assets include SHA-256 checksums, per-artifact SPDX SBOMs, and GitHub artifact
attestations. Verify both integrity and provenance:

```bash
sha256sum --check SHA256SUMS
gh attestation verify <artifact> --repo SauceTaster/GhidraEx
```

Unsigned JavaFX ZIPs remain developer previews; code signing, notarization, owned runtimes, update
security, and platform sandboxing are required before a stable desktop support claim.
