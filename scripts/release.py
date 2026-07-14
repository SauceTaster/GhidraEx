#!/usr/bin/env python3
"""Plan, build, validate, and assemble GhidraEx release artifacts.

The GitHub workflow passes untrusted event fields through environment variables. This module
strictly validates them before they can become a runner label, command argument, or filename.
It intentionally packages only products marked releaseEligible in release/products.json.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tempfile
from typing import Any, Iterable
import zipfile


ROOT = Path(__file__).resolve().parents[1]
CATALOG_PATH = ROOT / "release" / "products.json"
FIXED_ZIP_TIME = (1980, 1, 1, 0, 0, 0)
VERSION_RE = re.compile(
    r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-(alpha|rc)\.(0|[1-9][0-9]*))?$"
)
COMMIT_RE = re.compile(r"^[0-9a-f]{40,64}$")
SAFE_NAME_RE = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._-]*$")
MAX_SBOM_EXTRACT_BYTES = 1024 * 1024 * 1024


class ReleaseError(RuntimeError):
    pass


def load_catalog() -> dict[str, Any]:
    with CATALOG_PATH.open(encoding="utf-8") as stream:
        catalog = json.load(stream)
    if catalog.get("schemaVersion") != 1:
        raise ReleaseError("Unsupported release product catalog schema")
    products = catalog.get("products")
    if not isinstance(products, dict) or not products:
        raise ReleaseError("Release product catalog has no products")
    return catalog


def validate_version(version: str) -> re.Match[str]:
    match = VERSION_RE.fullmatch(version)
    if match is None:
        raise ReleaseError(
            "Version must be MAJOR.MINOR.PATCH, MAJOR.MINOR.PATCH-alpha.N, "
            "or MAJOR.MINOR.PATCH-rc.N with no leading zeroes"
        )
    return match


def parse_tag(tag: str) -> tuple[str, str, bool]:
    if not tag.startswith("v"):
        raise ReleaseError("Coordinated release tags must begin with v")
    version = tag[1:]
    match = validate_version(version)
    prerelease_kind = match.group(4)
    channel = "stable" if prerelease_kind is None else (
        "preview" if prerelease_kind == "alpha" else "candidate"
    )
    return version, channel, prerelease_kind is not None


def eligible_products(catalog: dict[str, Any]) -> list[str]:
    train = catalog["releaseTrain"].get("products")
    if not isinstance(train, list) or not train:
        raise ReleaseError("Release train has no product order")
    for product_id in train:
        product = catalog["products"].get(product_id)
        if not isinstance(product, dict) or product.get("releaseEligible") is not True:
            raise ReleaseError(f"Release train contains ineligible product: {product_id}")
    return list(train)


def selected_products(catalog: dict[str, Any], selection: str) -> list[str]:
    eligible = eligible_products(catalog)
    if selection == "all":
        return eligible
    if selection not in eligible:
        raise ReleaseError(f"Product is not release-eligible: {selection}")
    return [selection]


def build_matrix(catalog: dict[str, Any], selection: str) -> list[dict[str, str]]:
    matrix: list[dict[str, str]] = []
    for product_id in selected_products(catalog, selection):
        product = catalog["products"][product_id]
        platforms = product.get("platforms")
        if not isinstance(platforms, list) or not platforms:
            raise ReleaseError(f"Release product has no build platforms: {product_id}")
        for platform in platforms:
            platform_id = platform.get("id")
            runner = platform.get("runner")
            if not isinstance(platform_id, str) or not SAFE_NAME_RE.fullmatch(platform_id):
                raise ReleaseError(f"Invalid platform id for {product_id}")
            if not isinstance(runner, str) or not SAFE_NAME_RE.fullmatch(runner):
                raise ReleaseError(f"Invalid runner for {product_id}/{platform_id}")
            matrix.append(
                {
                    "product": product_id,
                    "platform": platform_id,
                    "runner": runner,
                    "artifact": f"release-{product_id}-{platform_id}",
                }
            )
    return matrix


def plan(*, tag: str | None, selection: str, version: str | None, publish: bool) -> dict[str, Any]:
    catalog = load_catalog()
    if tag is not None:
        parsed_version, channel, prerelease = parse_tag(tag)
        if version is not None and version != parsed_version:
            raise ReleaseError("Explicit version does not match tag")
        version = parsed_version
    else:
        if version is None:
            raise ReleaseError("A version is required for a manual release dry run")
        match = validate_version(version)
        kind = match.group(4)
        channel = "stable" if kind is None else ("preview" if kind == "alpha" else "candidate")
        prerelease = kind is not None
        tag = f"v{version}"
    if publish and selection != "all":
        raise ReleaseError("Tagged GitHub releases must contain the complete coordinated train")
    if publish and channel == "stable" and catalog["releaseTrain"].get("allowStable") is not True:
        raise ReleaseError("Stable releases are blocked until every advertised product gate passes")
    return {
        "schemaVersion": 1,
        "tag": tag,
        "version": version,
        "selection": selection,
        "channel": channel,
        "prerelease": prerelease,
        "publish": publish,
        "title": f"GhidraEx {version}",
        "matrix": build_matrix(catalog, selection),
    }


def github_plan() -> dict[str, Any]:
    event = os.environ.get("GITHUB_EVENT_NAME", "")
    if event == "push":
        if os.environ.get("GITHUB_REF_TYPE") != "tag":
            raise ReleaseError("Release push was not a tag")
        tag = os.environ.get("GITHUB_REF_NAME", "")
        return plan(tag=tag, selection="all", version=None, publish=True)
    if event == "workflow_dispatch":
        selection = os.environ.get("GHIDRAEX_RELEASE_PRODUCT", "all")
        version = os.environ.get("GHIDRAEX_RELEASE_VERSION", "")
        return plan(tag=None, selection=selection, version=version, publish=False)
    raise ReleaseError(f"Unsupported release event: {event or '<empty>'}")


def write_github_outputs(path: Path, values: dict[str, Any]) -> None:
    scalar_keys = (
        "tag",
        "version",
        "selection",
        "channel",
        "prerelease",
        "publish",
        "title",
        "matrix",
    )
    with path.open("a", encoding="utf-8") as stream:
        for key in scalar_keys:
            value = values[key]
            if isinstance(value, bool):
                encoded = "true" if value else "false"
            elif key == "matrix":
                encoded = json.dumps(value, separators=(",", ":"), sort_keys=True)
            else:
                encoded = str(value)
            if "\n" in encoded or "\r" in encoded:
                raise ReleaseError(f"GitHub output contains a newline: {key}")
            stream.write(f"{key}={encoded}\n")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def command_version(command: list[str]) -> str | None:
    try:
        completed = subprocess.run(
            command,
            cwd=ROOT,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=20,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    output = completed.stdout.strip().splitlines()
    return output[0] if output else None


def run(command: list[str], *, cwd: Path, env: dict[str, str]) -> None:
    printable = " ".join(command)
    print(f"[{cwd.relative_to(ROOT)}] {printable}", flush=True)
    if os.name == "nt" and command[0].lower().endswith(".bat"):
        command = ["cmd.exe", "/d", "/s", "/c", subprocess.list2cmdline(command)]
    subprocess.run(command, cwd=cwd, env=env, check=True)


def safe_zip_parts(name: str) -> tuple[str, ...]:
    if not name or "\x00" in name or "\\" in name:
        raise ReleaseError(f"Unsafe ZIP entry: {name!r}")
    path = PurePosixPath(name)
    parts = path.parts
    canonical = "/".join(parts) + ("/" if name.endswith("/") else "")
    if (
        path.is_absolute()
        or not parts
        or canonical != name
        or any(part in ("", ".", "..") for part in parts)
        or ":" in parts[0]
    ):
        raise ReleaseError(f"Unsafe ZIP entry: {name}")
    return parts


def normalize_zip(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=destination.parent, suffix=".zip", delete=False) as temp:
        temporary = Path(temp.name)
    try:
        with zipfile.ZipFile(source) as incoming, zipfile.ZipFile(
            temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9
        ) as outgoing:
            outgoing.comment = b""
            seen: set[str] = set()
            for old in sorted(incoming.infolist(), key=lambda item: item.filename):
                safe_zip_parts(old.filename)
                if old.filename in seen:
                    raise ReleaseError(f"Duplicate ZIP entry: {old.filename}")
                seen.add(old.filename)
                data = b"" if old.is_dir() else incoming.read(old.filename)
                new = zipfile.ZipInfo(old.filename, FIXED_ZIP_TIME)
                new.compress_type = zipfile.ZIP_STORED if old.is_dir() else zipfile.ZIP_DEFLATED
                new.create_system = old.create_system
                new.external_attr = old.external_attr
                new.internal_attr = old.internal_attr
                new.flag_bits = old.flag_bits & 0x800
                outgoing.writestr(new, data)
        with zipfile.ZipFile(temporary) as check:
            bad = check.testzip()
            if bad is not None:
                raise ReleaseError(f"Normalized ZIP failed integrity check at {bad}")
        temporary.replace(destination)
        destination.chmod(0o644)
    finally:
        temporary.unlink(missing_ok=True)


def prepare_sbom_scan_root(archive: Path, scan_root: Path) -> Path:
    """Safely materialize the exact shipped archive for directory-based SBOM inventory."""
    if scan_root.exists():
        raise ReleaseError(f"Refusing to overwrite SBOM scan root: {scan_root}")
    scan_root.mkdir(parents=True)
    total_bytes = 0
    try:
        with zipfile.ZipFile(archive) as source:
            seen: set[tuple[str, ...]] = set()
            for entry in source.infolist():
                parts = safe_zip_parts(entry.filename)
                if parts in seen:
                    raise ReleaseError(f"Duplicate ZIP entry: {entry.filename}")
                seen.add(parts)
                mode = entry.external_attr >> 16
                if mode and stat.S_ISLNK(mode):
                    raise ReleaseError(f"SBOM input archive contains a symbolic link: {entry.filename}")
                total_bytes += entry.file_size
                if total_bytes > MAX_SBOM_EXTRACT_BYTES:
                    raise ReleaseError("SBOM input archive exceeds the extraction size limit")
                destination = scan_root.joinpath(*parts)
                if entry.is_dir():
                    destination.mkdir(parents=True, exist_ok=True)
                    continue
                destination.parent.mkdir(parents=True, exist_ok=True)
                if destination.exists():
                    raise ReleaseError(f"Colliding ZIP entry: {entry.filename}")
                with source.open(entry) as incoming, destination.open("xb") as outgoing:
                    shutil.copyfileobj(incoming, outgoing)
                destination.chmod(0o644)
        return scan_root
    except Exception:
        shutil.rmtree(scan_root, ignore_errors=True)
        raise


def artifact_filename(product: str, version: str, platform: str) -> str:
    validate_version(version)
    if product == "workbench":
        return f"ghidraex-workbench-{version}-{platform}.zip"
    if product == "jetbrains" and platform == "universal":
        return f"ghidraex-jetbrains-{version}.zip"
    if product == "vscode" and platform == "universal":
        return f"ghidraex-vscode-{version}.vsix"
    raise ReleaseError(f"Unsupported product/platform: {product}/{platform}")


def expected_platform(catalog: dict[str, Any], product: str, platform: str) -> dict[str, Any]:
    selected = selected_products(catalog, product)
    if selected != [product]:
        raise ReleaseError(f"Product is not independently buildable: {product}")
    for candidate in catalog["products"][product]["platforms"]:
        if candidate["id"] == platform:
            return candidate
    raise ReleaseError(f"Unsupported platform for {product}: {platform}")


def verify_no_snapshot(names: Iterable[str]) -> None:
    snapshots = [name for name in names if "SNAPSHOT" in name.upper()]
    if snapshots:
        raise ReleaseError(f"Release archive contains snapshot artifacts: {snapshots}")


def verify_workbench(path: Path, version: str, classifier: str) -> None:
    prefix = f"ghidraex-workbench-{version}/"
    required = {
        prefix + "LICENSE.txt",
        prefix + "README.md",
        prefix + "THIRD-PARTY-NOTICES.txt",
        prefix + "bin/ghidra-fx-workbench",
        prefix + "bin/ghidra-fx-workbench.bat",
        prefix + "legal/openjfx/LICENSE",
        prefix + "legal/openjfx/ADDITIONAL_LICENSE_INFO",
        prefix + "legal/openjfx/ASSEMBLY_EXCEPTION",
        prefix + f"lib/ghidra-fx-workbench-{version}.jar",
        prefix + f"lib/ghidraex-view-state-{version}.jar",
        prefix + f"lib/javafx-base-26-{classifier}.jar",
        prefix + f"lib/javafx-controls-26-{classifier}.jar",
        prefix + f"lib/javafx-graphics-26-{classifier}.jar",
    }
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        missing = sorted(required - names)
        if missing:
            raise ReleaseError(f"Workbench archive is missing required files: {missing}")
        graphics = sorted(
            Path(name).name
            for name in names
            if Path(name).name.startswith("javafx-graphics-26-") and name.endswith(".jar")
        )
        expected = [f"javafx-graphics-26-{classifier}.jar"]
        if graphics != expected:
            raise ReleaseError(f"Workbench JavaFX platform mismatch: expected {expected}, got {graphics}")
        verify_no_snapshot(names)


def verify_jetbrains(path: Path, version: str) -> None:
    plugin_jar_name = (
        "ghidraex-intellij-workbench/lib/"
        f"ghidraex-intellij-workbench-{version}.jar"
    )
    shared_jar_name = (
        "ghidraex-intellij-workbench/lib/"
        f"ghidraex-view-state-{version}.jar"
    )
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        verify_no_snapshot(names)
        for required in (plugin_jar_name, shared_jar_name):
            if required not in names:
                raise ReleaseError(f"JetBrains archive is missing {required}")
        plugin_bytes = archive.read(plugin_jar_name)
    with zipfile.ZipFile(io.BytesIO(plugin_bytes)) as plugin:
        names = set(plugin.namelist())
        if "LICENSE.txt" not in names:
            raise ReleaseError("JetBrains plugin JAR has no LICENSE.txt")
        plugin_xml = plugin.read("META-INF/plugin.xml").decode("utf-8")
        if f"<version>{version}</version>" not in plugin_xml:
            raise ReleaseError("JetBrains plugin manifest version does not match release")


def verify_vscode(path: Path, version: str, prerelease: bool) -> None:
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        required = {
            "extension/package.json",
            "extension/LICENSE.txt",
            "extension.vsixmanifest",
            "extension/dist/node/extension.js",
            "extension/dist/web/extension.js",
        }
        if missing := sorted(required - names):
            raise ReleaseError(f"VSIX is missing required files: {missing}")
        package = json.loads(archive.read("extension/package.json"))
        manifest = archive.read("extension.vsixmanifest").decode("utf-8")
        verify_no_snapshot(names)
    if package.get("version") != version:
        raise ReleaseError("VSIX package.json version does not match release")
    if f'Version="{version}"' not in manifest:
        raise ReleaseError("VSIX identity version does not match release")
    marker = 'Id="Microsoft.VisualStudio.Code.PreRelease" Value="true"'
    if (marker in manifest) != prerelease:
        raise ReleaseError("VSIX prerelease marker does not match release channel")


def validate_spdx_sbom(
    document: dict[str, Any], *, product: str, platform: str, version: str, classifier: str | None
) -> None:
    root_name = f"ghidraex-{product}"
    expected_packages = {
        root_name: version,
    }
    required_files: set[str]
    if product == "workbench":
        if not classifier:
            raise ReleaseError(f"Workbench SBOM has no platform classifier for {platform}")
        expected_packages.update(
            {
                "ghidra-fx-workbench": version,
                "ghidraex-view-state": version,
                "javafx-base": f"26-{classifier}",
                "javafx-controls": f"26-{classifier}",
                "javafx-graphics": f"26-{classifier}",
            }
        )
        prefix = f"ghidraex-workbench-{version}/"
        required_files = {
            prefix + "LICENSE.txt",
            prefix + "THIRD-PARTY-NOTICES.txt",
            prefix + "legal/openjfx/LICENSE",
            prefix + f"lib/ghidra-fx-workbench-{version}.jar",
            prefix + f"lib/ghidraex-view-state-{version}.jar",
            prefix + f"lib/javafx-base-26-{classifier}.jar",
            prefix + f"lib/javafx-controls-26-{classifier}.jar",
            prefix + f"lib/javafx-graphics-26-{classifier}.jar",
        }
    elif product == "jetbrains":
        expected_packages.update(
            {
                "ghidraex-intellij-workbench": version,
                "ghidraex-view-state": version,
            }
        )
        required_files = {
            f"ghidraex-intellij-workbench/lib/ghidraex-intellij-workbench-{version}.jar",
            f"ghidraex-intellij-workbench/lib/ghidraex-view-state-{version}.jar",
        }
    elif product == "vscode":
        required_files = {
            "extension.vsixmanifest",
            "extension/LICENSE.txt",
            "extension/package.json",
            "extension/dist/node/extension.js",
            "extension/dist/web/extension.js",
        }
    else:
        raise ReleaseError(f"Unsupported SBOM product: {product}")

    if document.get("name") != root_name:
        raise ReleaseError(f"SBOM root identity mismatch for {product}/{platform}")
    packages = document.get("packages")
    if not isinstance(packages, list):
        raise ReleaseError(f"SBOM has no package inventory for {product}/{platform}")
    package_pairs = {
        (entry.get("name"), entry.get("versionInfo"))
        for entry in packages
        if isinstance(entry, dict)
    }
    missing_packages = sorted(
        f"{name}@{expected_version}"
        for name, expected_version in expected_packages.items()
        if (name, expected_version) not in package_pairs
    )
    if missing_packages:
        raise ReleaseError(
            f"SBOM is missing expected packages for {product}/{platform}: {missing_packages}"
        )

    root_ids = {
        entry.get("SPDXID")
        for entry in packages
        if isinstance(entry, dict)
        and entry.get("name") == root_name
        and entry.get("versionInfo") == version
        and isinstance(entry.get("SPDXID"), str)
    }
    document_describes = document.get("documentDescribes")
    described = set(document_describes) if isinstance(document_describes, list) else set()
    relationships = document.get("relationships")
    for relationship in relationships if isinstance(relationships, list) else []:
        if (
            isinstance(relationship, dict)
            and relationship.get("spdxElementId") == "SPDXRef-DOCUMENT"
            and relationship.get("relationshipType") == "DESCRIBES"
        ):
            described.add(relationship.get("relatedSpdxElement"))
    if not root_ids.intersection(described):
        raise ReleaseError(f"SBOM does not describe its product root for {product}/{platform}")

    files = document.get("files")
    if not isinstance(files, list):
        raise ReleaseError(f"SBOM has no file inventory for {product}/{platform}")
    file_names = {
        entry.get("fileName").replace("\\", "/")
        for entry in files
        if isinstance(entry, dict) and isinstance(entry.get("fileName"), str)
    }
    if missing_files := sorted(required_files - file_names):
        raise ReleaseError(
            f"SBOM is missing shipped files for {product}/{platform}: {missing_files}"
        )


def build_product(
    *, product: str, platform: str, version: str, output: Path, github_output: Path | None
) -> Path:
    match = validate_version(version)
    catalog = load_catalog()
    platform_config = expected_platform(catalog, product, platform)
    output.mkdir(parents=True, exist_ok=True)
    destination = output / artifact_filename(product, version, platform)
    scan_root = output / ".sbom-input" / f"{product}-{platform}"
    if (
        destination.exists()
        or destination.with_name(destination.name + ".build.json").exists()
        or scan_root.exists()
    ):
        raise ReleaseError(f"Refusing to overwrite release output: {destination}")

    env = os.environ.copy()
    env["ORG_GRADLE_PROJECT_releaseVersion"] = version
    env.setdefault("SOURCE_DATE_EPOCH", "315532800")
    if product == "workbench":
        project = ROOT / "prototypes" / "javafx-workbench"
        wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
        run([wrapper, "clean", "test", "distZip", "--no-daemon"], cwd=project, env=env)
        source = project / "build" / "distributions" / f"ghidraex-workbench-{version}.zip"
        if not source.is_file():
            raise ReleaseError(f"Workbench build did not produce {source}")
        normalize_zip(source, destination)
        verify_workbench(destination, version, platform_config["javafxClassifier"])
    elif product == "jetbrains":
        project = ROOT / "prototypes" / "intellij-workbench"
        wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
        run(
            [
                wrapper,
                "clean",
                "test",
                "buildPlugin",
                "verifyPluginProjectConfiguration",
                "verifyPluginStructure",
                "--no-configuration-cache",
                "--no-daemon",
            ],
            cwd=project,
            env=env,
        )
        source = project / "build" / "distributions" / f"ghidraex-intellij-workbench-{version}.zip"
        if not source.is_file():
            raise ReleaseError(f"JetBrains build did not produce {source}")
        normalize_zip(source, destination)
        verify_jetbrains(destination, version)
    elif product == "vscode":
        project = ROOT / "prototypes" / "vscode-workbench"
        run(["npm", "ci"], cwd=project, env=env)
        run(["npm", "test"], cwd=project, env=env)
        run(["npm", "run", "check"], cwd=project, env=env)
        run(["node", "esbuild.mjs", "--production"], cwd=project, env=env)
        source = project / "dist" / "ghidraex-vscode-workbench.release.vsix"
        command = [
            "npx",
            "--no-install",
            "vsce",
            "package",
            version,
            "--no-update-package-json",
            "--out",
            str(source),
        ]
        if match.group(4) is not None:
            command.append("--pre-release")
        run(command, cwd=project, env=env)
        normalize_zip(source, destination)
        verify_vscode(destination, version, match.group(4) is not None)
    else:
        raise ReleaseError(f"Unsupported release product: {product}")

    prepare_sbom_scan_root(destination, scan_root)
    sidecar = {
        "schemaVersion": 1,
        "product": product,
        "platform": platform,
        "version": version,
        "artifact": destination.name,
        "sha256": sha256(destination),
        "bytes": destination.stat().st_size,
        "runner": {
            "os": os.environ.get("RUNNER_OS", sys.platform),
            "arch": os.environ.get("RUNNER_ARCH", "unknown"),
            "image": os.environ.get("ImageOS", "local"),
        },
        "toolchains": {
            "java": command_version(["java", "-version"]) if product != "vscode" else None,
            "node": command_version(["node", "--version"]) if product == "vscode" else None,
            "python": sys.version.split()[0],
        },
    }
    sidecar_path = destination.with_name(destination.name + ".build.json")
    sidecar_path.write_text(json.dumps(sidecar, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if github_output is not None:
        sbom_path = destination.with_name(destination.name + ".spdx.json")
        with github_output.open("a", encoding="utf-8") as stream:
            stream.write(f"asset_path={destination}\n")
            stream.write(f"asset_name={destination.name}\n")
            stream.write(f"sbom_path={sbom_path}\n")
            stream.write(f"sbom_scan_path={scan_root}\n")
            stream.write(f"sidecar_path={sidecar_path}\n")
    print(json.dumps(sidecar, indent=2, sort_keys=True))
    return destination


def finalize(
    *,
    assets: Path,
    selection: str,
    version: str,
    tag: str,
    commit: str,
    repository: str,
    publish: bool = False,
) -> dict[str, Any]:
    validate_version(version)
    release_plan = plan(tag=tag, selection=selection, version=version, publish=publish)
    channel = release_plan["channel"]
    prerelease = release_plan["prerelease"]
    if not COMMIT_RE.fullmatch(commit):
        raise ReleaseError("Commit must be a full lowercase hexadecimal Git object id")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ReleaseError("Repository must be owner/name")
    catalog = load_catalog()
    expected = {(row["product"], row["platform"]) for row in build_matrix(catalog, selection)}
    sidecars = sorted(assets.glob("*.build.json"))
    records: list[dict[str, Any]] = []
    actual: set[tuple[str, str]] = set()
    allowed_files: set[str] = set()
    for sidecar_path in sidecars:
        record = json.loads(sidecar_path.read_text(encoding="utf-8"))
        if record.get("schemaVersion") != 1 or record.get("version") != version:
            raise ReleaseError(f"Invalid build sidecar: {sidecar_path.name}")
        product = record.get("product")
        platform = record.get("platform")
        pair = (product, platform)
        if pair in actual:
            raise ReleaseError(f"Duplicate build sidecar for {product}/{platform}")
        actual.add(pair)
        expected_name = artifact_filename(product, version, platform)
        if record.get("artifact") != expected_name or not SAFE_NAME_RE.fullmatch(expected_name):
            raise ReleaseError(f"Unexpected artifact name in {sidecar_path.name}")
        artifact = assets / expected_name
        sbom = assets / f"{expected_name}.spdx.json"
        if not artifact.is_file() or not sbom.is_file():
            raise ReleaseError(f"Artifact or per-artifact SBOM is missing for {product}/{platform}")
        if sha256(artifact) != record.get("sha256") or artifact.stat().st_size != record.get("bytes"):
            raise ReleaseError(f"Artifact changed after build: {artifact.name}")
        try:
            sbom_document = json.loads(sbom.read_text(encoding="utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ReleaseError(f"Invalid SPDX JSON for {artifact.name}: {error}") from error
        if not str(sbom_document.get("spdxVersion", "")).startswith("SPDX-"):
            raise ReleaseError(f"SBOM is not an SPDX document: {sbom.name}")
        platform_config = expected_platform(catalog, product, platform)
        validate_spdx_sbom(
            sbom_document,
            product=product,
            platform=platform,
            version=version,
            classifier=platform_config.get("javafxClassifier"),
        )
        record["sbom"] = {
            "artifact": sbom.name,
            "sha256": sha256(sbom),
            "bytes": sbom.stat().st_size,
        }
        records.append(record)
        allowed_files.update({sidecar_path.name, artifact.name, sbom.name})
    if actual != expected:
        raise ReleaseError(f"Release build matrix mismatch: expected {sorted(expected)}, got {sorted(actual)}")
    existing = {path.name for path in assets.iterdir() if path.is_file()}
    extras = sorted(existing - allowed_files)
    if extras:
        raise ReleaseError(f"Unexpected files in release assembly directory: {extras}")

    product_status = {
        product_id: {
            "displayName": product["displayName"],
            "releaseEligible": product["releaseEligible"],
            "backendStatus": product["backendStatus"],
            **({"blockedBy": product["blockedBy"]} if "blockedBy" in product else {}),
        }
        for product_id, product in catalog["products"].items()
    }
    manifest = {
        "schemaVersion": 1,
        "release": {
            "repository": repository,
            "tag": tag,
            "version": version,
            "commit": commit,
            "channel": channel,
            "prerelease": prerelease,
            "publish": publish,
            "dryRun": not publish,
            "selection": selection,
        },
        "compatibility": {
            "protocol": {
                "major": 1,
                "encoding": "bounded-json",
                "scope": "one immutable imported program, read-only",
            },
            "ghidra": {
                "version": "12.1.2",
                "build": "20260605",
                "jdk": "21",
                "officialArchiveSha256": "b62e81a0390618466c019c60d8c2f796ced2509c4c1aea4a37644a77272cf99d",
                "bundled": False,
            },
            "clientJdk": "25",
            "javafx": "26",
            "intellijBaseline": "251 / IntelliJ IDEA Community 2025.1.5",
            "vscodeEngine": "^1.100.0",
        },
        "verification": {
            "fullRepositoryVerifier": True,
            "realGhidraRequiredForTaggedRelease": True,
            "realGhidraFixture": "native compiler fixture through Ghidra 12.1.2 worker and gateway",
            "releaseArchiveInspection": True,
        },
        "products": product_status,
        "artifacts": sorted(records, key=lambda item: item["artifact"]),
    }
    manifest_path = assets / "release-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    for sidecar in sidecars:
        sidecar.unlink()
    checksum_targets = sorted(
        path for path in assets.iterdir() if path.is_file() and path.name != "SHA256SUMS"
    )
    checksums = "".join(f"{sha256(path)}  {path.name}\n" for path in checksum_targets)
    (assets / "SHA256SUMS").write_text(checksums, encoding="utf-8")
    return manifest


def verify_bundle(assets: Path) -> None:
    sums = assets / "SHA256SUMS"
    manifest_path = assets / "release-manifest.json"
    if not sums.is_file() or not manifest_path.is_file():
        raise ReleaseError("Release bundle is missing SHA256SUMS or release-manifest.json")
    entries: dict[str, str] = {}
    for line in sums.read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9][A-Za-z0-9._-]*)", line)
        if match is None:
            raise ReleaseError(f"Malformed checksum line: {line}")
        digest, name = match.groups()
        if name in entries:
            raise ReleaseError(f"Duplicate checksum entry: {name}")
        entries[name] = digest
    actual = {path.name for path in assets.iterdir() if path.is_file() and path.name != "SHA256SUMS"}
    if set(entries) != actual:
        raise ReleaseError("Checksum file does not cover the release bundle exactly")
    for name, digest in entries.items():
        if sha256(assets / name) != digest:
            raise ReleaseError(f"Checksum mismatch: {name}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    for artifact in manifest.get("artifacts", []):
        if entries.get(artifact["artifact"]) != artifact["sha256"]:
            raise ReleaseError(f"Manifest artifact digest mismatch: {artifact['artifact']}")
        sbom = artifact["sbom"]
        if entries.get(sbom["artifact"]) != sbom["sha256"]:
            raise ReleaseError(f"Manifest SBOM digest mismatch: {sbom['artifact']}")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    subcommands = result.add_subparsers(dest="command", required=True)

    plan_parser = subcommands.add_parser("plan", help="Validate and display a release plan")
    source = plan_parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--tag")
    source.add_argument("--version")
    plan_parser.add_argument("--product", default="all")
    plan_parser.add_argument("--github-output", type=Path)

    github_parser = subcommands.add_parser("github-plan", help="Plan from validated GitHub event env")
    github_parser.add_argument("--github-output", type=Path, required=True)

    build_parser = subcommands.add_parser("build", help="Build one release matrix cell")
    build_parser.add_argument("--product", required=True)
    build_parser.add_argument("--platform", required=True)
    build_parser.add_argument("--version", required=True)
    build_parser.add_argument("--output", type=Path, required=True)
    build_parser.add_argument("--github-output", type=Path)

    final_parser = subcommands.add_parser("finalize", help="Validate and assemble a release bundle")
    final_parser.add_argument("--assets", type=Path, required=True)
    final_parser.add_argument("--selection", required=True)
    final_parser.add_argument("--version", required=True)
    final_parser.add_argument("--tag", required=True)
    final_parser.add_argument("--commit", required=True)
    final_parser.add_argument("--repository", required=True)
    final_parser.add_argument("--publish", action="store_true")

    verify_parser = subcommands.add_parser("verify-bundle", help="Verify release manifest/checksums")
    verify_parser.add_argument("--assets", type=Path, required=True)
    return result


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        if args.command == "plan":
            values = plan(
                tag=args.tag,
                selection=args.product,
                version=args.version,
                publish=args.tag is not None,
            )
            if args.github_output is not None:
                write_github_outputs(args.github_output, values)
            print(json.dumps(values, indent=2, sort_keys=True))
        elif args.command == "github-plan":
            values = github_plan()
            write_github_outputs(args.github_output, values)
            print(json.dumps(values, indent=2, sort_keys=True))
        elif args.command == "build":
            build_product(
                product=args.product,
                platform=args.platform,
                version=args.version,
                output=args.output.resolve(),
                github_output=args.github_output,
            )
        elif args.command == "finalize":
            manifest = finalize(
                assets=args.assets.resolve(),
                selection=args.selection,
                version=args.version,
                tag=args.tag,
                commit=args.commit,
                repository=args.repository,
                publish=args.publish,
            )
            print(json.dumps(manifest, indent=2, sort_keys=True))
        elif args.command == "verify-bundle":
            verify_bundle(args.assets.resolve())
            print("Release bundle verification passed.")
        return 0
    except (ReleaseError, OSError, subprocess.CalledProcessError) as error:
        print(f"release error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
