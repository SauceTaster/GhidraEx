from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


TEST_ROOT = Path(__file__).resolve().parent
INTEGRATION_ROOT = TEST_ROOT.parent
sys.path.insert(0, str(INTEGRATION_ROOT))

from ghidra_bridge import (  # noqa: E402
    AnalysisLimits,
    GhidraHeadlessBridge,
    GhidraInstallation,
    GhidraUnavailableError,
)


class RealGhidraIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        try:
            cls.installation = GhidraInstallation.discover()
        except GhidraUnavailableError as error:
            raise unittest.SkipTest(str(error)) from error
        cls.compiler = shutil.which(os.environ.get("CC", "cc"))
        if cls.compiler is None:
            raise unittest.SkipTest("a native C compiler is required to build fixtures/tiny.c")

    def test_native_fixture_import_analysis_and_export_are_deterministic(self) -> None:
        with tempfile.TemporaryDirectory(prefix="ghidraex-fixture-") as temporary:
            fixture = Path(temporary) / ("tiny.exe" if os.name == "nt" else "tiny")
            compile_result = subprocess.run(
                [self.compiler, "-O0", "-g0", str(INTEGRATION_ROOT / "fixtures" / "tiny.c"), "-o", str(fixture)],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=False,
            )
            if compile_result.returncode != 0:
                self.fail(f"fixture compilation failed:\n{compile_result.stdout}")

            bridge = GhidraHeadlessBridge(
                self.installation, process_timeout=600, analysis_timeout=300, max_cpu=2
            )
            limits = AnalysisLimits(functions=64, symbols=128, instructions=128)
            first = bridge.analyze(fixture, limits=limits)
            second = bridge.analyze(fixture, limits=limits)

            self.assertEqual(first, second)
            self.assertEqual(1, first["schemaVersion"])
            self.assertFalse(first["analysis"]["timeoutOccurred"])
            self.assertGreater(first["counts"]["functions"], 0)
            self.assertGreater(first["counts"]["instructions"], 0)
            self.assertTrue(first["program"]["languageId"])
            self.assertTrue(first["program"]["executableFormat"])
            self.assertLessEqual(len(first["functions"]), limits.functions)
            self.assertLessEqual(len(first["symbols"]), limits.symbols)
            self.assertLessEqual(len(first["instructions"]), limits.instructions)
            self.assertEqual(
                sorted(first["entryPoints"]), first["entryPoints"], "entry points must be stable"
            )


if __name__ == "__main__":
    unittest.main()
