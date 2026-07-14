from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import time
import unittest


TEST_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(TEST_ROOT.parent))

from ghidra_bridge import (  # noqa: E402
    AnalysisLimits,
    GhidraAnalysisError,
    GhidraHeadlessBridge,
    GhidraInstallation,
    GhidraUnavailableError,
    SCRIPT_DIRECTORY,
    SCRIPT_NAME,
)


class InstallationDiscoveryTest(unittest.TestCase):
    def test_explicit_home_discovers_official_launcher_layout(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            support = home / "support"
            support.mkdir()
            launcher = support / ("analyzeHeadless.bat" if os.name == "nt" else "analyzeHeadless")
            launcher.write_text("#!/bin/sh\n", encoding="utf-8")
            launcher.chmod(launcher.stat().st_mode | stat.S_IXUSR)

            installation = GhidraInstallation.discover(home, environment={})

            self.assertEqual(home.resolve(), installation.home)
            self.assertEqual(launcher.resolve(), installation.analyze_headless)

    def test_invalid_explicit_home_has_actionable_error(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(GhidraUnavailableError, "No executable support"):
                GhidraInstallation.discover(temporary, environment={})

    def test_invalid_explicit_java_home_has_actionable_error(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(GhidraUnavailableError, "selected Ghidra JDK"):
                GhidraInstallation.discover(temporary, java_home=temporary, environment={})

    def test_invalid_environment_java_home_is_not_silently_ignored(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            support = home / "support"
            support.mkdir()
            launcher = support / ("analyzeHeadless.bat" if os.name == "nt" else "analyzeHeadless")
            launcher.write_text("#!/bin/sh\n", encoding="utf-8")
            launcher.chmod(launcher.stat().st_mode | stat.S_IXUSR)
            missing_jdk = home / "missing-jdk"

            with self.assertRaisesRegex(GhidraUnavailableError, "selected Ghidra JDK"):
                GhidraInstallation.discover(
                    home, environment={"GHIDRA_JAVA_HOME": str(missing_jdk)}
                )

    def test_environment_home_is_considered(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            support = home / "support"
            support.mkdir()
            launcher = support / ("analyzeHeadless.bat" if os.name == "nt" else "analyzeHeadless")
            launcher.write_text("#!/bin/sh\n", encoding="utf-8")
            launcher.chmod(launcher.stat().st_mode | stat.S_IXUSR)

            installation = GhidraInstallation.discover(environment={"GHIDRA_HOME": str(home)})

            self.assertEqual(home.resolve(), installation.home)


class BridgeContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.installation = GhidraInstallation(
            Path("/opt/ghidra"), Path("/opt/ghidra/support/analyzeHeadless"), None
        )
        self.bridge = GhidraHeadlessBridge(self.installation)

    def test_build_command_uses_import_analysis_and_official_post_script(self) -> None:
        command = self.bridge.build_command(
            Path("/tmp/input"),
            Path("/tmp/project"),
            "project",
            Path("/tmp/facts.json"),
            AnalysisLimits(3, 4, 5),
            delete_project=True,
        )

        self.assertIn("-import", command)
        self.assertIn("-analysisTimeoutPerFile", command)
        self.assertIn("-scriptPath", command)
        self.assertEqual(str(SCRIPT_DIRECTORY), command[command.index("-scriptPath") + 1])
        self.assertEqual(SCRIPT_NAME, command[command.index("-postScript") + 1])
        self.assertIn("-deleteProject", command)

    def test_limits_reject_unbounded_values(self) -> None:
        with self.assertRaises(ValueError):
            AnalysisLimits(instructions=100_001)

    def test_validate_facts_accepts_the_schema_contract(self) -> None:
        binary = b"ghidraex"
        digest = hashlib.sha256(binary).hexdigest()
        facts = {
            "schemaVersion": 1,
            "analysis": {"timeoutOccurred": False},
            "program": {"sha256": digest},
            "limits": {"functions": 1, "symbols": 2, "instructions": 3},
            "memoryBlocks": [],
            "entryPoints": [],
            "functions": [],
            "symbols": [],
            "instructions": [],
        }

        self.bridge._validate_facts(facts, digest, AnalysisLimits(1, 2, 3))

    def test_validate_facts_rejects_an_import_hash_mismatch(self) -> None:
        facts = {
            "schemaVersion": 1,
            "analysis": {"timeoutOccurred": False},
            "program": {"sha256": "0" * 64},
            "limits": {"functions": 1, "symbols": 2, "instructions": 3},
            "memoryBlocks": [],
            "entryPoints": [],
            "functions": [],
            "symbols": [],
            "instructions": [],
        }
        with self.assertRaisesRegex(GhidraAnalysisError, "SHA-256 mismatch"):
            self.bridge._validate_facts(facts, "1" * 64, AnalysisLimits(1, 2, 3))

    def test_validate_facts_rejects_timed_out_analysis(self) -> None:
        facts = {
            "schemaVersion": 1,
            "analysis": {"timeoutOccurred": True},
            "program": {"sha256": "1" * 64},
            "limits": {"functions": 1, "symbols": 2, "instructions": 3},
            "memoryBlocks": [],
            "entryPoints": [],
            "functions": [],
            "symbols": [],
            "instructions": [],
        }
        with self.assertRaisesRegex(GhidraAnalysisError, "per-file timeout"):
            self.bridge._validate_facts(facts, "1" * 64, AnalysisLimits(1, 2, 3))

    def test_process_timeout_terminates_the_child(self) -> None:
        bridge = GhidraHeadlessBridge(self.installation, process_timeout=1)
        with self.assertRaisesRegex(GhidraAnalysisError, "exceeded the 1s process timeout"):
            bridge._run(
                [sys.executable, "-c", "import time; time.sleep(30)"], os.environ.copy()
            )

    @unittest.skipIf(os.name == "nt", "POSIX process-group regression")
    def test_process_timeout_terminates_a_launcher_and_its_grandchild(self) -> None:
        bridge = GhidraHeadlessBridge(self.installation, process_timeout=1)
        with tempfile.TemporaryDirectory() as temporary:
            pid_file = Path(temporary) / "grandchild.pid"
            launcher = (
                "import pathlib,signal,subprocess,sys,time; "
                "child=subprocess.Popen([sys.executable,'-c',"
                "'import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(30)']); "
                f"pathlib.Path({str(pid_file)!r}).write_text(str(child.pid)); "
                "signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(30)"
            )
            with self.assertRaisesRegex(GhidraAnalysisError, "process timeout"):
                bridge._run([sys.executable, "-c", launcher], os.environ.copy())

            grandchild_pid = int(pid_file.read_text(encoding="utf-8"))
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline and _process_exists(grandchild_pid):
                time.sleep(0.05)
            self.assertFalse(_process_exists(grandchild_pid), "timed-out grandchild survived cleanup")

    def test_keep_project_requires_an_explicit_directory(self) -> None:
        with self.assertRaisesRegex(ValueError, "project_directory"):
            self.bridge.analyze(__file__, keep_project=True)


def _process_exists(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    return True


if __name__ == "__main__":
    unittest.main()
