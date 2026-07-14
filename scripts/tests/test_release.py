from __future__ import annotations

import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile

from scripts import release


class ReleasePlanTest(unittest.TestCase):
    def test_manual_stable_and_tagged_preview_produce_complete_train(self) -> None:
        stable = release.plan(tag=None, selection="all", version="1.2.3", publish=False)
        preview = release.plan(tag="v0.2.0-alpha.7", selection="all", version=None, publish=True)

        self.assertEqual("stable", stable["channel"])
        self.assertFalse(stable["prerelease"])
        self.assertEqual("preview", preview["channel"])
        self.assertTrue(preview["prerelease"])
        self.assertEqual(7, len(stable["matrix"]))
        self.assertEqual(
            {
                ("workbench", "linux-x64"),
                ("workbench", "linux-arm64"),
                ("workbench", "windows-x64"),
                ("workbench", "macos-x64"),
                ("workbench", "macos-arm64"),
                ("jetbrains", "universal"),
                ("vscode", "universal"),
            },
            {(entry["product"], entry["platform"]) for entry in stable["matrix"]},
        )

    def test_stable_tag_is_blocked_while_products_are_fixture_backed(self) -> None:
        with self.assertRaises(release.ReleaseError):
            release.plan(tag="v0.2.0", selection="all", version=None, publish=True)

    def test_manual_run_can_build_one_product_but_never_publish(self) -> None:
        with mock.patch.dict(
            "os.environ",
            {
                "GITHUB_EVENT_NAME": "workflow_dispatch",
                "GHIDRAEX_RELEASE_PRODUCT": "vscode",
                "GHIDRAEX_RELEASE_VERSION": "0.3.0-rc.2",
            },
            clear=True,
        ):
            planned = release.github_plan()

        self.assertEqual("vscode", planned["selection"])
        self.assertEqual("candidate", planned["channel"])
        self.assertFalse(planned["publish"])
        self.assertEqual(1, len(planned["matrix"]))

    def test_tag_push_is_the_only_publishing_event(self) -> None:
        with mock.patch.dict(
            "os.environ",
            {
                "GITHUB_EVENT_NAME": "push",
                "GITHUB_REF_TYPE": "tag",
                "GITHUB_REF_NAME": "v0.2.0-alpha.1",
            },
            clear=True,
        ):
            planned = release.github_plan()

        self.assertTrue(planned["publish"])
        self.assertEqual("all", planned["selection"])

    def test_rejects_unsafe_or_ambiguous_versions(self) -> None:
        invalid = (
            "1.2",
            "01.2.3",
            "1.02.3",
            "1.2.03",
            "1.2.3-beta.1",
            "1.2.3-alpha.01",
            "1.2.3+build",
            "1.2.3;touch-pwned",
            "../1.2.3",
        )
        for version in invalid:
            with self.subTest(version=version), self.assertRaises(release.ReleaseError):
                release.validate_version(version)

    def test_ineligible_products_cannot_enter_a_release_matrix(self) -> None:
        catalog = release.load_catalog()
        for product in ("web", "engine", "sdk"):
            with self.subTest(product=product), self.assertRaises(release.ReleaseError):
                release.build_matrix(catalog, product)


class DeterministicZipTest(unittest.TestCase):
    def test_normalization_removes_order_and_timestamp_variance(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = root / "first.zip"
            second = root / "second.zip"
            for path, names, date in (
                (first, ("b.txt", "a.txt"), (2025, 1, 2, 3, 4, 6)),
                (second, ("a.txt", "b.txt"), (2026, 7, 8, 9, 10, 12)),
            ):
                with zipfile.ZipFile(path, "w") as archive:
                    for name in names:
                        info = zipfile.ZipInfo(name, date)
                        info.compress_type = zipfile.ZIP_DEFLATED
                        archive.writestr(info, name.encode())
            normalized_first = root / "normalized-first.zip"
            normalized_second = root / "normalized-second.zip"
            release.normalize_zip(first, normalized_first)
            release.normalize_zip(second, normalized_second)

            self.assertEqual(normalized_first.read_bytes(), normalized_second.read_bytes())
            with zipfile.ZipFile(normalized_first) as archive:
                self.assertTrue(all(info.date_time == release.FIXED_ZIP_TIME for info in archive.infolist()))

    def test_normalization_rejects_parent_traversal(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            unsafe = root / "unsafe.zip"
            with zipfile.ZipFile(unsafe, "w") as archive:
                archive.writestr("../escape", b"bad")
            with self.assertRaises(release.ReleaseError):
                release.normalize_zip(unsafe, root / "out.zip")

    def test_zip_paths_reject_cross_platform_escape_forms(self) -> None:
        for name in ("/absolute", "C:/windows", "..\\escape", "./relative", "double//slash"):
            with self.subTest(name=name), self.assertRaises(release.ReleaseError):
                release.safe_zip_parts(name)

    def test_sbom_scan_root_materializes_only_archive_contents(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "artifact.zip"
            with zipfile.ZipFile(archive, "w") as zipped:
                zipped.writestr("product/", b"")
                zipped.writestr("product/app.txt", b"shipped")
            scan_root = release.prepare_sbom_scan_root(archive, root / "scan")

            self.assertEqual(b"shipped", (scan_root / "product" / "app.txt").read_bytes())
            self.assertEqual([scan_root / "product" / "app.txt"], [p for p in scan_root.rglob("*") if p.is_file()])


class ReleaseBundleTest(unittest.TestCase):
    VERSION = "0.2.0-alpha.1"

    def vscode_sbom(self, name: str) -> dict[str, object]:
        root_id = "SPDXRef-DocumentRoot-Directory-ghidraex-vscode"
        shipped = (
            "extension.vsixmanifest",
            "extension/LICENSE.txt",
            "extension/package.json",
            "extension/dist/node/extension.js",
            "extension/dist/web/extension.js",
        )
        return {
            "spdxVersion": "SPDX-2.3",
            "name": "ghidraex-vscode",
            "packages": [
                {
                    "name": "ghidraex-vscode",
                    "versionInfo": self.VERSION,
                    "SPDXID": root_id,
                }
            ],
            "files": [
                {"fileName": file_name, "SPDXID": f"SPDXRef-File-{index}"}
                for index, file_name in enumerate(shipped)
            ],
            "relationships": [
                {
                    "spdxElementId": "SPDXRef-DOCUMENT",
                    "relatedSpdxElement": root_id,
                    "relationshipType": "DESCRIBES",
                }
            ],
        }

    def create_vscode_cell(self, assets: Path) -> Path:
        name = release.artifact_filename("vscode", self.VERSION, "universal")
        artifact = assets / name
        artifact.write_bytes(b"deterministic-vsix")
        sbom = assets / f"{name}.spdx.json"
        sbom.write_text(
            json.dumps(self.vscode_sbom(name)) + "\n",
            encoding="utf-8",
        )
        sidecar = {
            "schemaVersion": 1,
            "product": "vscode",
            "platform": "universal",
            "version": self.VERSION,
            "artifact": name,
            "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
            "bytes": artifact.stat().st_size,
            "runner": {"os": "Linux", "arch": "X64", "image": "test"},
            "toolchains": {"java": None, "node": "v22", "python": "3.12"},
        }
        (assets / f"{name}.build.json").write_text(
            json.dumps(sidecar) + "\n", encoding="utf-8"
        )
        return artifact

    def test_finalize_and_verify_cover_every_bundle_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            assets = Path(directory)
            artifact = self.create_vscode_cell(assets)

            manifest = release.finalize(
                assets=assets,
                selection="vscode",
                version=self.VERSION,
                tag=f"v{self.VERSION}",
                commit="a" * 40,
                repository="SauceTaster/GhidraEx",
            )
            release.verify_bundle(assets)

            self.assertEqual("deterministic-fixture-only", manifest["products"]["vscode"]["backendStatus"])
            self.assertFalse(manifest["products"]["engine"]["releaseEligible"])
            self.assertFalse(manifest["release"]["publish"])
            self.assertTrue(manifest["release"]["dryRun"])
            self.assertFalse(any(assets.glob("*.build.json")))
            sums = (assets / "SHA256SUMS").read_text(encoding="utf-8")
            self.assertIn(artifact.name, sums)
            self.assertIn(artifact.name + ".spdx.json", sums)
            self.assertIn("release-manifest.json", sums)

    def test_bundle_verification_detects_tampering(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            assets = Path(directory)
            artifact = self.create_vscode_cell(assets)
            release.finalize(
                assets=assets,
                selection="vscode",
                version=self.VERSION,
                tag=f"v{self.VERSION}",
                commit="b" * 40,
                repository="SauceTaster/GhidraEx",
            )
            artifact.write_bytes(b"tampered")

            with self.assertRaises(release.ReleaseError):
                release.verify_bundle(assets)

    def test_finalize_rejects_unexpected_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            assets = Path(directory)
            self.create_vscode_cell(assets)
            (assets / "surprise.bin").write_bytes(b"unexpected")

            with self.assertRaises(release.ReleaseError):
                release.finalize(
                    assets=assets,
                    selection="vscode",
                    version=self.VERSION,
                    tag=f"v{self.VERSION}",
                    commit="c" * 40,
                    repository="SauceTaster/GhidraEx",
                )

    def test_sbom_validation_rejects_vacuous_component_inventory(self) -> None:
        root_id = "SPDXRef-DocumentRoot-Directory-ghidraex-workbench"
        document = {
            "spdxVersion": "SPDX-2.3",
            "name": "ghidraex-workbench",
            "packages": [
                {
                    "name": "ghidraex-workbench",
                    "versionInfo": self.VERSION,
                    "SPDXID": root_id,
                }
            ],
            "files": [],
            "relationships": [
                {
                    "spdxElementId": "SPDXRef-DOCUMENT",
                    "relatedSpdxElement": root_id,
                    "relationshipType": "DESCRIBES",
                }
            ],
        }

        with self.assertRaises(release.ReleaseError):
            release.validate_spdx_sbom(
                document,
                product="workbench",
                platform="macos-arm64",
                version=self.VERSION,
                classifier="mac-aarch64",
            )


if __name__ == "__main__":
    unittest.main()
