"""Synthetic acceptance checks using the container's configured cleanup model.

Run manually: python check_cleanup.py --list
              python check_cleanup.py --case numbered-list
Each selected case makes one billed OpenRouter request; no microphone audio.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import re
import time
from pathlib import Path

from app import OpenRouterTranscriber, Settings
from cleanup import CleanupOptions
from cleanup_cases import CASES, CleanupCase


def selected_cases(name: str | None = None, category: str | None = None) -> list[CleanupCase]:
    return [case for case in CASES if (name is None or case.name == name) and (category is None or case.category == category)]


def passes(case: CleanupCase, text: str, cleaned: bool) -> bool:
    # Casing is a requirement. Case-insensitivity is explicit only where appropriate.
    return cleaned and all(re.search(pattern, text, re.DOTALL) for pattern in case.patterns)


async def run(selected: str | None, category: str | None = None, report: Path | None = None) -> int:
    settings = Settings.from_env()
    if not settings.cleanup_enabled:
        print("Cleanup is disabled. Set TEXT_CLEANUP_ENABLED=true and redeploy.")
        return 1
    transcriber = OpenRouterTranscriber(settings)
    results = []
    try:
        for case in selected_cases(selected, category):
            started = time.monotonic()
            text, cleaned = await transcriber.cleanup_or_original(case.spoken, CleanupOptions(**case.options))
            passed = passes(case, text, cleaned)
            duration = int((time.monotonic()-started)*1000)
            results.append({"case": case.name, "category": case.category, "passed": passed, "duration_ms": duration, "cleaned": cleaned, "expected": case.expected, "actual": text})
            print(f"{'PASS' if passed else 'FAIL'} {case.name} duration_ms={duration} cleaned={cleaned}")
            if not passed:
                # Only fixed synthetic samples are ever sent by this script.
                print(f"  Synthetic result: {text!r}")
    finally:
        await transcriber.close()
    failures = sum(not result["passed"] for result in results)
    print(f"{len(results)-failures}/{len(results)} passed using {settings.cleanup_model}. This checks synthetic examples, not speech recognition.")
    if report:
        report.write_text(json.dumps({"model": settings.cleanup_model, "results": results}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 1 if failures else 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--case", choices=[case.name for case in CASES])
    selection.add_argument("--category", choices=sorted({case.category for case in CASES}))
    parser.add_argument("--list", action="store_true", help="Print every input/expected result without making API calls.")
    parser.add_argument("--report", type=Path, help="Save synthetic results as JSON.")
    args = parser.parse_args()
    if args.list:
        for case in selected_cases(args.case, args.category):
            print(f"{case.name} [{case.category}]\n  Say: {case.spoken}\n  Expected: {case.expected!r}")
    else:
        raise SystemExit(asyncio.run(run(args.case, args.category, args.report)))
