#!/usr/bin/env python3
"""Informe de trazabilidad: RF de cada spec ↔ tests que los citan.

- Las specs `implementada` (en `docs/hoja-de-ruta.md`) deben tener todos sus RF citados por
  al menos un test; si no, el informe falla (S001/RF-26).
- Las specs `en curso` solo listan sus RF pendientes, sin fallar (S001/RF-35).
- Una cita a un RF inexistente o a una spec sin `spec.md` también es un error.

Una cita es `S###/RF-n`, `S### RF-n` o `s###_rfn` (sin distinguir mayúsculas), con listas
(`S002/RF-1, RF-2 y RF-4`) y rangos (`S003/RF-2..RF-5`). Solo cuentan los ficheros de test:
`**/src/test*/**/*.kt`, `**/src/androidTest/**/*.kt` y `tools/**/test_*.py`.

Uso: uv run --project tools tools/trazabilidad/rf_report.py [--root RUTA] [--detalle]
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

ENFORCED_STATE = "implementada"
LISTED_STATE = "en curso"

ROADMAP_ROW = re.compile(r"^\|\s*(\d{3})\s+[\w-]+\s*\|.*\|\s*([^|]+?)\s*\|\s*$")
RF_LINE = re.compile(r"^\s*-\s*RF-(\d+):", re.MULTILINE)
CITATION = re.compile(r"S(\d{3})[ /_]RF-?(\d+)((?:\s*(?:,|\by\b|\band\b|\.\.|–)\s*RF-?\d+)*)", re.IGNORECASE)
CONTINUATION = re.compile(r"(,|\by\b|\band\b|\.\.|–)\s*RF-?(\d+)", re.IGNORECASE)

TEST_GLOBS = ("**/src/test*/**/*.kt", "**/src/androidTest/**/*.kt", "tools/**/test_*.py")
EXCLUDED_DIRS = {"build", ".gradle", ".git", ".venv", ".kotlin", ".idea", "node_modules"}


def read_roadmap(path: Path) -> dict[str, str]:
    """Estado de cada spec según las tablas de la hoja de ruta: {"001": "en curso", …}."""
    states: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        match = ROADMAP_ROW.match(line.strip())
        if match:
            states[match.group(1)] = match.group(2).strip().lower()
    return states


def read_spec_rfs(root: Path, number: str) -> set[int] | None:
    """RF declarados en `specs/NNN-*/spec.md`, o `None` si la spec no existe."""
    candidates = sorted((root / "specs").glob(f"{number}-*/spec.md"))
    if not candidates:
        return None
    return {int(n) for n in RF_LINE.findall(candidates[0].read_text(encoding="utf-8"))}


def extract_citations(text: str) -> set[tuple[str, int]]:
    """Pares (spec, RF) citados en un texto, expandiendo listas y rangos."""
    cited: set[tuple[str, int]] = set()
    for match in CITATION.finditer(text):
        spec, first = match.group(1), int(match.group(2))
        cited.add((spec, first))
        previous = first
        for separator, number in CONTINUATION.findall(match.group(3)):
            current = int(number)
            if separator in ("..", "–"):
                cited.update((spec, rf) for rf in range(previous, current + 1))
            else:
                cited.add((spec, current))
            previous = current
    return cited


def iter_test_files(root: Path):
    seen: set[Path] = set()
    for pattern in TEST_GLOBS:
        for path in root.glob(pattern):
            relative = path.relative_to(root)
            if path in seen or not path.is_file() or EXCLUDED_DIRS.intersection(relative.parts):
                continue
            seen.add(path)
            yield path


@dataclass
class Report:
    states: dict[str, str]
    declared: dict[str, set[int]] = field(default_factory=dict)
    citations: dict[tuple[str, int], list[str]] = field(default_factory=lambda: defaultdict(list))
    errors: list[str] = field(default_factory=list)

    def covered(self, spec: str) -> set[int]:
        return {rf for (s, rf) in self.citations if s == spec}


def build_report(root: Path) -> Report:
    report = Report(states=read_roadmap(root / "docs" / "hoja-de-ruta.md"))

    for path in iter_test_files(root):
        text = path.read_text(encoding="utf-8", errors="replace")
        relative = path.relative_to(root).as_posix()
        for line_number, line in enumerate(text.splitlines(), start=1):
            for spec, rf in extract_citations(line):
                report.citations[(spec, rf)].append(f"{relative}:{line_number}")

    cited_specs = {spec for spec, _ in report.citations}
    tracked = {s for s, state in report.states.items() if state in (ENFORCED_STATE, LISTED_STATE)}
    for spec in sorted(tracked | cited_specs):
        rfs = read_spec_rfs(root, spec)
        if rfs is None:
            if spec in tracked:
                report.errors.append(f"S{spec} ({report.states[spec]}): no existe specs/{spec}-*/spec.md")
            else:
                where = ", ".join(sorted({loc for (s, _), locs in report.citations.items() if s == spec for loc in locs}))
                report.errors.append(f"cita a S{spec}, que no tiene spec.md ({where})")
            continue
        report.declared[spec] = rfs

    for (spec, rf), locations in sorted(report.citations.items()):
        if spec in report.declared and rf not in report.declared[spec]:
            report.errors.append(f"cita a un RF inexistente S{spec}/RF-{rf} en {', '.join(locations)}")

    for spec in sorted(tracked):
        if spec in report.declared and report.states[spec] == ENFORCED_STATE:
            missing = sorted(report.declared[spec] - report.covered(spec))
            if missing:
                report.errors.append(f"S{spec} ({ENFORCED_STATE}): RF sin test: " + ", ".join(f"RF-{n}" for n in missing))
    return report


def render(report: Report, detail: bool) -> str:
    lines = ["Trazabilidad RF <-> tests", ""]
    for spec in sorted(report.declared):
        state = report.states.get(spec, "sin estado")
        if state not in (ENFORCED_STATE, LISTED_STATE):
            continue
        declared = report.declared[spec]
        covered = report.covered(spec) & declared
        pending = sorted(declared - covered)
        lines.append(f"S{spec} ({state}): {len(declared)} RF, {len(covered)} con test, {len(pending)} pendientes")
        if pending:
            lines.append("  pendientes: " + ", ".join(f"RF-{n}" for n in pending))
        if detail:
            for rf in sorted(covered):
                lines.append(f"  RF-{rf}: " + ", ".join(sorted(report.citations[(spec, rf)])))
    if report.errors:
        lines += ["", "ERRORES:"] + [f"  - {error}" for error in report.errors]
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Informe de trazabilidad RF <-> tests de TopoPhone.")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2], help="raíz del repositorio")
    parser.add_argument("--detalle", action="store_true", help="muestra qué tests citan cada RF")
    args = parser.parse_args(argv)

    try:
        sys.stdout.reconfigure(encoding="utf-8")  # consola de Windows
    except (AttributeError, ValueError):
        pass

    report = build_report(args.root.resolve())
    print(render(report, args.detalle), end="")
    return 1 if report.errors else 0


if __name__ == "__main__":
    sys.exit(main())
