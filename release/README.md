# Release engineering

GhidraEx uses one coordinated release train before 1.0. A tag matching
`vMAJOR.MINOR.PATCH-alpha.N` or `vMAJOR.MINOR.PATCH-rc.N` builds the currently eligible products
and creates a draft GitHub prerelease. Stable tags are deliberately rejected while the eligible
native products are fixture-backed. Drafts are reviewed and published only after every asset,
checksum, SBOM, and provenance attestation is present.

The machine-readable product catalog is [`products.json`](products.json). It deliberately lists
Web, Engine, and SDK as release-ineligible instead of manufacturing source ZIPs that do not have an
installation and lifecycle contract.

Run a local release-plan check without building:

```bash
python3 scripts/release.py plan --tag v0.2.0-alpha.1
```

Build one product artifact using a release version:

```bash
python3 scripts/release.py build \
  --product vscode \
  --platform universal \
  --version 0.2.0-alpha.1 \
  --output /tmp/ghidraex-release
```

The GitHub workflow supports manual dry runs for one eligible product or the complete train. A
manual run never creates a tag or release. Pushing a valid coordinated preview/candidate tag builds
all eligible products and creates a new draft release; an existing release fails closed rather than
having assets replaced. No attestation or release write occurs until the read-only
`release-approval` job passes the protected `release` environment, whose deployment policy accepts
version tags and requires explicit approval. Syft inventories the safely extracted contents of each
artifact, and finalization rejects an SBOM that omits the expected product, runtime components, or
shipped files. The active `Immutable release tags` repository ruleset permits initial `v*` tag
creation but prohibits tag updates and deletion, including during the draft-review window. The
workflow never bundles Ghidra itself.

Consumers can verify an attached artifact with both the release checksums and GitHub provenance:

```bash
sha256sum --check SHA256SUMS
gh attestation verify ghidraex-vscode-0.2.0-alpha.1.vsix \
  --repo SauceTaster/GhidraEx
```

Preview artifacts are unsigned developer previews. In particular, the JavaFX ZIP requires an
external JDK 25 and is qualified by operating system and architecture. Signing, notarization,
owned runtime images, and update channels remain release gates before any stable desktop claim.
