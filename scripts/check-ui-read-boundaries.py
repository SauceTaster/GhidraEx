#!/usr/bin/env python3
"""Reject direct engine/provider reads from desktop host presentation code."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


RECEIVER = r"""
    (?P<receiver>
        (?:(?:this|self)\s*\.\s*)?
        (?:
            (?:engine|provider|transport|backend|client|loader|source|gateway|bridge)
            | [A-Za-z_$][A-Za-z0-9_$]*(?:Engine|Provider|Transport|Backend|Client|Loader|Source|Gateway|Bridge)
        )
        (?:\s*\(\s*\))?
    )
"""


@dataclass(frozen=True)
class Boundary:
    name: str
    files: tuple[Path, ...]
    allowed: frozenset[Path]
    methods: tuple[str, ...]
    guidance: str

    def pattern(self) -> re.Pattern[str]:
        methods = "|".join(self.methods)
        return re.compile(
            rf"{RECEIVER}\s*(?:\?\.|\.)\s*(?P<method>{methods})\s*\(",
            re.VERBOSE,
        )


@dataclass(frozen=True, order=True)
class Violation:
    path: Path
    line: int
    column: int
    host: str
    receiver: str
    method: str
    guidance: str


COMMON_READ_METHODS = (
    r"read[A-Z][A-Za-z0-9_$]*",
    r"fetch[A-Z][A-Za-z0-9_$]*",
    r"query[A-Z][A-Za-z0-9_$]*",
    r"load[A-Z][A-Za-z0-9_$]*",
    r"load",
    r"request",
)


def source_files(root: Path, relative: str, suffix: str) -> tuple[Path, ...]:
    directory = root / relative
    if not directory.is_dir():
        raise FileNotFoundError(f"required source directory is missing: {directory}")
    return tuple(sorted(path for path in directory.rglob(f"*{suffix}") if path.is_file()))


def boundaries(root: Path) -> tuple[Boundary, ...]:
    javafx_root = "prototypes/javafx-workbench/src/main/java/dev/ghidraex/fx"
    intellij_root = "prototypes/intellij-workbench/src/main/java/dev/ghidraex/intellij"
    vscode_native = "prototypes/vscode-workbench/src/native"
    vscode_extension = root / "prototypes/vscode-workbench/src/extension.ts"
    if not vscode_extension.is_file():
        raise FileNotFoundError(f"required source file is missing: {vscode_extension}")

    vscode_files = tuple(
        path
        for path in source_files(root, vscode_native, ".ts")
        if not path.name.endswith(".test.ts")
    ) + (vscode_extension,)

    return (
        Boundary(
            "JavaFX",
            source_files(root, javafx_root, ".java"),
            frozenset(
                root / path
                for path in (
                    f"{javafx_root}/AsyncAnalysisLauncher.java",
                    f"{javafx_root}/AsyncEngineReads.java",
                    f"{javafx_root}/EngineSessionSnapshot.java",
                    f"{javafx_root}/SyntheticSemanticListingProvider.java",
                )
            ),
            (
                r"program",
                r"listingSize",
                r"listingWindow",
                r"searchSymbols",
                r"decompile",
                r"references",
                r"startAnalysis",
                *COMMON_READ_METHODS,
            ),
            "route the call through AsyncEngineReads/AsyncListingProjection or pre-control bootstrap",
        ),
        Boundary(
            "IntelliJ",
            source_files(root, intellij_root, ".java"),
            frozenset(
                root / path
                for path in (
                    f"{intellij_root}/editor/IntelliJListingProjection.java",
                    f"{intellij_root}/editor/SyntheticListingWindowSource.java",
                    f"{intellij_root}/session/WorkbenchSession.java",
                )
            ),
            (
                r"programs",
                r"symbols",
                r"instructionCount",
                r"listing",
                r"renderListing",
                r"decompile",
                r"analyze",
                *COMMON_READ_METHODS,
            ),
            "route the call through WorkbenchSession or the bounded listing worker source",
        ),
        Boundary(
            "VS Code",
            tuple(sorted(vscode_files)),
            frozenset({root / f"{vscode_native}/analysis.ts"}),
            (
                r"openProgram",
                r"program",
                r"listing",
                r"searchSymbols",
                r"symbols",
                r"decompile",
                r"referencesTo",
                r"references",
                r"evidenceFor",
                r"evidence",
                r"runAnalysis",
                *COMMON_READ_METHODS,
            ),
            "route the call through NativeReadService and render its accepted immutable snapshot",
        ),
    )


def mask_comments_and_strings(source: str) -> str:
    """Replace comments and quoted content with spaces while preserving offsets/newlines."""
    output = list(source)
    index = 0
    state = "code"
    quote = ""
    while index < len(source):
        current = source[index]
        following = source[index + 1] if index + 1 < len(source) else ""
        if state == "line-comment":
            if current == "\n":
                state = "code"
            else:
                output[index] = " "
            index += 1
            continue
        if state == "block-comment":
            if current == "*" and following == "/":
                output[index] = output[index + 1] = " "
                index += 2
                state = "code"
            else:
                if current != "\n":
                    output[index] = " "
                index += 1
            continue
        if state == "string":
            if quote == '\"\"\"' and source.startswith('\"\"\"', index):
                output[index:index + 3] = [" ", " ", " "]
                index += 3
                state = "code"
            elif current == "\\":
                output[index] = " "
                if index + 1 < len(source):
                    if source[index + 1] != "\n":
                        output[index + 1] = " "
                    index += 2
                else:
                    index += 1
            elif quote != '\"\"\"' and current == quote:
                output[index] = " "
                index += 1
                state = "code"
            else:
                if current != "\n":
                    output[index] = " "
                index += 1
            continue
        if current == "/" and following == "/":
            output[index] = output[index + 1] = " "
            index += 2
            state = "line-comment"
        elif current == "/" and following == "*":
            output[index] = output[index + 1] = " "
            index += 2
            state = "block-comment"
        elif source.startswith('\"\"\"', index):
            output[index:index + 3] = [" ", " ", " "]
            index += 3
            state = "string"
            quote = '\"\"\"'
        elif current in {'"', "'", "`"}:
            output[index] = " "
            index += 1
            state = "string"
            quote = current
        else:
            index += 1
    return "".join(output)


def line_and_column(source: str, offset: int) -> tuple[int, int]:
    line = source.count("\n", 0, offset) + 1
    line_start = source.rfind("\n", 0, offset) + 1
    return line, offset - line_start + 1


def scan(root: Path, rules: Iterable[Boundary]) -> list[Violation]:
    violations: list[Violation] = []
    for rule in rules:
        missing_allowed = sorted(path for path in rule.allowed if not path.is_file())
        if missing_allowed:
            raise FileNotFoundError(f"{rule.name} allowlist entry is missing: {missing_allowed[0]}")
        pattern = rule.pattern()
        for path in rule.files:
            if path in rule.allowed:
                continue
            source = path.read_text(encoding="utf-8")
            masked = mask_comments_and_strings(source)
            for match in pattern.finditer(masked):
                line, column = line_and_column(masked, match.start("receiver"))
                violations.append(
                    Violation(
                        path.relative_to(root),
                        line,
                        column,
                        rule.name,
                        re.sub(r"\s+", "", match.group("receiver")),
                        match.group("method"),
                        rule.guidance,
                    )
                )
    return sorted(violations)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="repository root (defaults to the parent of scripts/)",
    )
    args = parser.parse_args()
    root = args.root.resolve()
    try:
        violations = scan(root, boundaries(root))
    except (OSError, UnicodeError) as error:
        print(f"ui-read-boundary: cannot scan repository: {error}", file=sys.stderr)
        return 2
    if violations:
        for violation in violations:
            print(
                f"{violation.path}:{violation.line}:{violation.column}: "
                f"ui-read-boundary: {violation.host} presentation code calls "
                f"{violation.receiver}.{violation.method}(); {violation.guidance}",
                file=sys.stderr,
            )
        print(
            f"ui-read-boundary: failed with {len(violations)} direct provider/engine call(s)",
            file=sys.stderr,
        )
        return 1
    print("UI read boundary check passed (JavaFX, IntelliJ, VS Code).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
