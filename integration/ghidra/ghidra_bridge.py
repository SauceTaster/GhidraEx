#!/usr/bin/env python3
"""Process-isolated bridge from GhidraEx to Ghidra's headless analyzer.

The bridge deliberately does not import Ghidra classes into the UI runtime.  It invokes the
official ``support/analyzeHeadless`` launcher, lets that launcher select Ghidra's supported JDK,
and exchanges an immutable JSON document with a bundled Java ``GhidraScript``.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from dataclasses import dataclass
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
from typing import Any, Mapping, Sequence


BRIDGE_ROOT = Path(__file__).resolve().parent
REPOSITORY_ROOT = BRIDGE_ROOT.parents[1]
SCRIPT_DIRECTORY = BRIDGE_ROOT / "scripts"
SCRIPT_NAME = "ExportProgramFacts.java"
SCHEMA_VERSION = 1


class GhidraUnavailableError(RuntimeError):
    """Raised when an analyzeHeadless installation cannot be found."""


class GhidraAnalysisError(RuntimeError):
    """Raised when Ghidra fails or returns an invalid facts document."""


def _launcher_names() -> tuple[str, ...]:
    if os.name == "nt":
        return ("analyzeHeadless.bat", "analyzeHeadless")
    return ("analyzeHeadless",)


def _launcher_under(home: Path) -> Path | None:
    for name in _launcher_names():
        candidate = home / "support" / name
        if candidate.is_file() and (os.name == "nt" or os.access(candidate, os.X_OK)):
            return candidate.resolve()
    return None


def _home_from_launcher(launcher: Path) -> Path:
    if launcher.parent.name.lower() == "support":
        return launcher.parent.parent.resolve()
    return launcher.parent.resolve()


def _candidate_homes(environment: Mapping[str, str]) -> list[Path]:
    candidates: list[Path] = []
    for variable in ("GHIDRA_HOME", "GHIDRA_INSTALL_DIR"):
        if value := environment.get(variable):
            candidates.append(Path(value).expanduser())

    toolchains = REPOSITORY_ROOT / ".toolchains"
    if toolchains.is_dir():
        candidates.extend(sorted(toolchains.glob("ghidra_*_PUBLIC"), reverse=True))

    home = Path.home()
    for parent in (home, home / "Applications", Path("/opt"), Path("/usr/local")):
        if parent.is_dir():
            candidates.extend(sorted(parent.glob("ghidra_*_PUBLIC"), reverse=True))

    applications = Path("/Applications")
    if applications.is_dir():
        candidates.extend(sorted(applications.glob("Ghidra*.app/Contents/Resources/ghidra")))
    return candidates


def _discover_java_home(environment: Mapping[str, str]) -> Path | None:
    """Find an optional JDK for the child without imposing it on the UI process."""
    java_name = "java.exe" if os.name == "nt" else "java"
    if value := environment.get("GHIDRA_JAVA_HOME"):
        candidate = Path(value).expanduser()
        # This variable is an explicit backend selection. Return it even when broken so discovery
        # reports the configuration error instead of silently falling through to another JDK.
        return candidate.resolve()

    toolchains = REPOSITORY_ROOT / ".toolchains"
    patterns = ("jdk-21*/Contents/Home", "jdk-21*", "temurin-21*/Contents/Home", "temurin-21*")
    for pattern in patterns:
        for candidate in sorted(toolchains.glob(pattern), reverse=True):
            if (candidate / "bin" / java_name).is_file():
                return candidate.resolve()

    if value := environment.get("JAVA_HOME"):
        candidate = Path(value).expanduser()
        if (candidate / "bin" / java_name).is_file():
            return candidate.resolve()
    return None


@dataclass(frozen=True)
class GhidraInstallation:
    home: Path
    analyze_headless: Path
    java_home: Path | None = None

    @classmethod
    def discover(
        cls,
        explicit: str | os.PathLike[str] | None = None,
        *,
        java_home: str | os.PathLike[str] | None = None,
        environment: Mapping[str, str] | None = None,
    ) -> "GhidraInstallation":
        env = os.environ if environment is None else environment
        selected_java = Path(java_home).expanduser().resolve() if java_home else _discover_java_home(env)
        if selected_java is not None:
            java_executable = selected_java / "bin" / ("java.exe" if os.name == "nt" else "java")
            if not java_executable.is_file():
                raise GhidraUnavailableError(
                    f"The selected Ghidra JDK has no bin/{java_executable.name}: {selected_java}"
                )

        if explicit is not None:
            requested = Path(explicit).expanduser()
            if requested.is_file():
                launcher = (
                    requested.resolve()
                    if os.name == "nt" or os.access(requested, os.X_OK)
                    else None
                )
            else:
                launcher = _launcher_under(requested)
            if launcher is None:
                raise GhidraUnavailableError(
                    f"No executable support/{_launcher_names()[0]} found under {requested}"
                )
            return cls(_home_from_launcher(launcher), launcher, selected_java)

        for candidate_home in _candidate_homes(env):
            if launcher := _launcher_under(candidate_home):
                return cls(candidate_home.resolve(), launcher, selected_java)

        for name in _launcher_names():
            if found := shutil.which(name):
                launcher = Path(found).resolve()
                return cls(_home_from_launcher(launcher), launcher, selected_java)

        raise GhidraUnavailableError(
            "Ghidra headless analyzer was not found. Set GHIDRA_HOME to an extracted official "
            "Ghidra release, pass --ghidra-home, or put analyzeHeadless on PATH."
        )

    @property
    def version(self) -> str | None:
        properties = self.home / "Ghidra" / "application.properties"
        if not properties.is_file():
            return None
        for line in properties.read_text(encoding="utf-8").splitlines():
            if line.startswith("application.version="):
                return line.partition("=")[2].strip()
        return None


@dataclass(frozen=True)
class AnalysisLimits:
    functions: int = 256
    symbols: int = 512
    instructions: int = 512

    def __post_init__(self) -> None:
        for name, value in (
            ("functions", self.functions),
            ("symbols", self.symbols),
            ("instructions", self.instructions),
        ):
            if not 0 <= value <= 100_000:
                raise ValueError(f"{name} limit must be between 0 and 100000")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _tail(text: str, lines: int = 80) -> str:
    return "\n".join(text.splitlines()[-lines:])


class GhidraHeadlessBridge:
    def __init__(
        self,
        installation: GhidraInstallation,
        *,
        process_timeout: int = 600,
        analysis_timeout: int = 300,
        max_cpu: int = 2,
    ) -> None:
        if process_timeout <= 0 or analysis_timeout <= 0 or max_cpu <= 0:
            raise ValueError("timeouts and max_cpu must be positive")
        self.installation = installation
        self.process_timeout = process_timeout
        self.analysis_timeout = analysis_timeout
        self.max_cpu = max_cpu

    def build_command(
        self,
        binary: Path,
        project_directory: Path,
        project_name: str,
        facts_path: Path,
        limits: AnalysisLimits,
        *,
        delete_project: bool,
    ) -> list[str]:
        command = [
            str(self.installation.analyze_headless),
            str(project_directory),
            project_name,
            "-import",
            str(binary),
            "-overwrite",
            "-analysisTimeoutPerFile",
            str(self.analysis_timeout),
            "-max-cpu",
            str(self.max_cpu),
            "-scriptPath",
            str(SCRIPT_DIRECTORY),
            "-postScript",
            SCRIPT_NAME,
            str(facts_path),
            str(limits.functions),
            str(limits.symbols),
            str(limits.instructions),
        ]
        if delete_project:
            command.append("-deleteProject")
        if os.name == "nt" and self.installation.analyze_headless.suffix.lower() == ".bat":
            return ["cmd.exe", "/d", "/s", "/c", *command]
        return command

    def analyze(
        self,
        binary: str | os.PathLike[str],
        *,
        limits: AnalysisLimits | None = None,
        output: str | os.PathLike[str] | None = None,
        project_directory: str | os.PathLike[str] | None = None,
        keep_project: bool = False,
    ) -> dict[str, Any]:
        if keep_project and project_directory is None:
            raise ValueError("keep_project requires an explicit project_directory")
        binary_path = Path(binary).expanduser().resolve()
        if not binary_path.is_file():
            raise GhidraAnalysisError(f"Input binary is not a regular file: {binary_path}")
        if not SCRIPT_DIRECTORY.joinpath(SCRIPT_NAME).is_file():
            raise GhidraAnalysisError(f"Bundled Ghidra script is missing: {SCRIPT_NAME}")

        selected_limits = limits or AnalysisLimits()
        binary_sha256 = _sha256(binary_path)
        project_name = f"ghidraex_{binary_sha256[:16]}"

        temporary: tempfile.TemporaryDirectory[str] | None = None
        if project_directory is None:
            if keep_project:
                project_root = Path(tempfile.mkdtemp(prefix="ghidraex-project-"))
            else:
                temporary = tempfile.TemporaryDirectory(prefix="ghidraex-project-")
                project_root = Path(temporary.name)
        else:
            project_root = Path(project_directory).expanduser().resolve()
            project_root.mkdir(parents=True, exist_ok=True)

        try:
            facts_path = project_root / f"{project_name}-facts.json"
            command = self.build_command(
                binary_path,
                project_root,
                project_name,
                facts_path,
                selected_limits,
                delete_project=not keep_project and project_directory is None,
            )
            child_environment = os.environ.copy()
            child_environment.setdefault("LC_ALL", "C")
            child_environment.setdefault("LANG", "C")
            if self.installation.java_home is not None:
                child_environment["JAVA_HOME"] = str(self.installation.java_home)

            completed = self._run(command, child_environment)

            if completed.returncode != 0:
                raise GhidraAnalysisError(
                    f"analyzeHeadless exited with status {completed.returncode}.\n"
                    f"{_tail(completed.stdout)}"
                )
            if not facts_path.is_file():
                raise GhidraAnalysisError(
                    f"Ghidra completed without producing {facts_path.name}.\n{_tail(completed.stdout)}"
                )
            try:
                facts = json.loads(facts_path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError) as error:
                raise GhidraAnalysisError(f"Ghidra emitted invalid facts JSON: {error}") from error

            self._validate_facts(facts, binary_sha256, selected_limits)
            normalized = json.loads(json.dumps(facts, sort_keys=True, separators=(",", ":")))
            if output is not None:
                output_path = Path(output).expanduser().resolve()
                output_path.parent.mkdir(parents=True, exist_ok=True)
                output_path.write_text(
                    json.dumps(normalized, indent=2, sort_keys=True) + "\n", encoding="utf-8"
                )
            return normalized
        finally:
            if temporary is not None:
                temporary.cleanup()

    def _run(self, command: Sequence[str], environment: Mapping[str, str]) -> subprocess.CompletedProcess[str]:
        process_options: dict[str, Any] = {}
        if os.name == "nt":
            process_options["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
        else:
            process_options["start_new_session"] = True

        process = subprocess.Popen(
            command,
            cwd=BRIDGE_ROOT,
            env=environment,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            **process_options,
        )
        try:
            output, _ = process.communicate(timeout=self.process_timeout)
        except subprocess.TimeoutExpired as error:
            self._terminate_process_tree(process)
            try:
                output, _ = process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                # A hostile or broken descendant may have escaped normal launcher cleanup while
                # retaining the output pipe. Never turn a bounded analysis into an unbounded wait.
                self._terminate_process_tree(process, force=True)
                if process.stdout is not None:
                    process.stdout.close()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    pass
                output = ""
            captured = error.output or output or ""
            if isinstance(captured, bytes):
                captured = captured.decode(errors="replace")
            raise GhidraAnalysisError(
                f"Ghidra exceeded the {self.process_timeout}s process timeout.\n{_tail(captured)}"
            ) from error
        return subprocess.CompletedProcess(command, process.returncode, output, None)

    @staticmethod
    def _terminate_process_tree(process: subprocess.Popen[str], *, force: bool = False) -> None:
        if os.name == "nt":
            subprocess.run(
                ["taskkill", "/pid", str(process.pid), "/t", "/f"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=False,
            )
            return
        process_group = process.pid
        try:
            os.killpg(process_group, signal.SIGKILL if force else signal.SIGTERM)
        except ProcessLookupError:
            return
        if not force:
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                pass
            # The launcher may exit before a descendant. Target the PGID regardless of the
            # launcher's status so a surviving child cannot keep stdout open indefinitely.
            try:
                os.killpg(process_group, signal.SIGKILL)
            except ProcessLookupError:
                pass

    @staticmethod
    def _validate_facts(
        facts: Any, expected_sha256: str, expected_limits: AnalysisLimits
    ) -> None:
        if not isinstance(facts, dict):
            raise GhidraAnalysisError("Facts root must be a JSON object")
        if facts.get("schemaVersion") != SCHEMA_VERSION:
            raise GhidraAnalysisError(
                f"Unsupported facts schema: {facts.get('schemaVersion')!r}; expected {SCHEMA_VERSION}"
            )
        program = facts.get("program")
        if not isinstance(program, dict):
            raise GhidraAnalysisError("Facts document has no program object")
        analysis = facts.get("analysis")
        if not isinstance(analysis, dict) or not isinstance(analysis.get("timeoutOccurred"), bool):
            raise GhidraAnalysisError("Facts document has no valid analysis status")
        if analysis["timeoutOccurred"]:
            raise GhidraAnalysisError("Ghidra auto-analysis reached its per-file timeout")
        actual_sha256 = str(program.get("sha256") or "").lower()
        if actual_sha256 != expected_sha256.lower():
            raise GhidraAnalysisError(
                f"Imported program SHA-256 mismatch: expected {expected_sha256}, got {actual_sha256 or '<none>'}"
            )
        expected = {
            "functions": expected_limits.functions,
            "symbols": expected_limits.symbols,
            "instructions": expected_limits.instructions,
        }
        if facts.get("limits") != expected:
            raise GhidraAnalysisError(f"Facts limit contract mismatch: {facts.get('limits')!r}")
        for collection in ("memoryBlocks", "entryPoints", "functions", "symbols", "instructions"):
            if not isinstance(facts.get(collection), list):
                raise GhidraAnalysisError(f"Facts field {collection!r} must be an array")


def _positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be positive")
    return parsed


def _nonnegative_int(value: str) -> int:
    parsed = int(value)
    if not 0 <= parsed <= 100_000:
        raise argparse.ArgumentTypeError("must be between 0 and 100000")
    return parsed


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ghidra-home", help="Ghidra root or analyzeHeadless path")
    parser.add_argument("--java-home", help="JDK used only by the Ghidra child process")
    subparsers = parser.add_subparsers(dest="command", required=True)

    doctor = subparsers.add_parser("doctor", help="show the discovered sidecar installation")
    doctor.add_argument("--json", action="store_true", help="emit machine-readable JSON")

    analyze = subparsers.add_parser("analyze", help="import, analyze, and export program facts")
    analyze.add_argument("binary", help="native binary to import")
    analyze.add_argument("--output", help="also write normalized JSON to this path")
    analyze.add_argument("--project-directory", help="persistent Ghidra project parent directory")
    analyze.add_argument(
        "--keep-project",
        action="store_true",
        help="retain the project (requires --project-directory)",
    )
    analyze.add_argument("--process-timeout", type=_positive_int, default=600)
    analyze.add_argument("--analysis-timeout", type=_positive_int, default=300)
    analyze.add_argument("--max-cpu", type=_positive_int, default=2)
    analyze.add_argument("--max-functions", type=_nonnegative_int, default=256)
    analyze.add_argument("--max-symbols", type=_nonnegative_int, default=512)
    analyze.add_argument("--max-instructions", type=_nonnegative_int, default=512)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        installation = GhidraInstallation.discover(
            arguments.ghidra_home, java_home=arguments.java_home
        )
        if arguments.command == "doctor":
            details = {
                "available": True,
                "ghidraHome": str(installation.home),
                "analyzeHeadless": str(installation.analyze_headless),
                "javaHome": str(installation.java_home) if installation.java_home else None,
                "version": installation.version,
            }
            if arguments.json:
                print(json.dumps(details, indent=2, sort_keys=True))
            else:
                print(f"Ghidra {installation.version or 'unknown version'}")
                print(f"  home: {installation.home}")
                print(f"  launcher: {installation.analyze_headless}")
                print(f"  child JDK: {installation.java_home or 'selected by Ghidra'}")
            return 0

        limits = AnalysisLimits(
            functions=arguments.max_functions,
            symbols=arguments.max_symbols,
            instructions=arguments.max_instructions,
        )
        bridge = GhidraHeadlessBridge(
            installation,
            process_timeout=arguments.process_timeout,
            analysis_timeout=arguments.analysis_timeout,
            max_cpu=arguments.max_cpu,
        )
        facts = bridge.analyze(
            arguments.binary,
            limits=limits,
            output=arguments.output,
            project_directory=arguments.project_directory,
            keep_project=arguments.keep_project,
        )
        print(json.dumps(facts, indent=2, sort_keys=True))
        return 0
    except GhidraUnavailableError as error:
        print(f"ghidra unavailable: {error}", file=sys.stderr)
        return 2
    except (GhidraAnalysisError, ValueError) as error:
        print(f"ghidra analysis failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
