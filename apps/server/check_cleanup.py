"""Synthetic acceptance checks using the container's configured cleanup model.

Run manually: python check_cleanup.py [--case numbered-list]
Each selected case makes one billed OpenRouter request; no microphone audio.
"""
from __future__ import annotations

import argparse
import asyncio
import re
import time

from app import OpenRouterTranscriber, Settings
from cleanup import CleanupOptions


# Patterns check critical behavior while allowing reasonable inferred punctuation.
CASES = [
    ("quantities", "I paid forty-five dollars for thirty-seven kilos, thirty percent off.", [r"\$45", r"37kg", r"30%"], {}),
    ("date-order", "March third. The third of March. Half past nine.", [r"March 3", r"3 March", r"9:30"], {}),
    ("correction", "Let's meet at five, actually six PM.", [r"meet at 6pm", r"^(?!.*\b(?:actually|five|5)\b)"], {}),
    ("numbered-list", "Tasks. First send the report. Second pay forty-five dollars. Third call Patel.", [r"(?m)^1\.\s+Send the report", r"(?m)^2\.\s+Pay \$45", r"(?m)^3\.\s+Call Patel"], {}),
    ("bullets", "Shopping list. Apples, milk, bread.", [r"(?m)^-\s+Apples", r"(?m)^-\s+Milk", r"(?m)^-\s+Bread"], {}),
    ("email", "Hi Alex. Can we meet at five thirty PM? Thanks, Patel.", [r"^Hi Alex[^\n]*\n", r"5:30pm\?", r"Thanks[^\n]*\nPatel[.!]?\s*$"], {}),
    ("deletion", "Meet at five PM. Delete that last sentence.", [r"^$"], {"supports_discard": True}),
    ("symbols", "Email john at acme dot com. The code is alpha underscore beta slash two.", [r"john@acme\.com", r"alpha_beta/2"], {}),
    ("spelling", "My name is P A T E L. A P I. All caps urgent.", [r"Patel", r"API", r"URGENT"], {}),
    ("vocabulary", "Send it to akme through click up.", [r"ACME", r"ClickUp"], {"vocabulary": [{"spoken": "akme", "written": "ACME"}, {"spoken": "click up", "written": "ClickUp"}]}),
    ("voice", "I think it's probably roughly forty-five dollars. It works like a charm. One of a kind.", [r"I think", r"probably", r"roughly", r"it's", r"\$45", r"like a charm", r"one of a kind"], {}),
]


async def run(selected: str | None) -> int:
    settings = Settings.from_env()
    if not settings.cleanup_enabled:
        print("Cleanup is disabled. Set TEXT_CLEANUP_ENABLED=true and redeploy.")
        return 1
    transcriber = OpenRouterTranscriber(settings)
    failures = 0
    try:
        for name, transcript, patterns, values in CASES:
            if selected and name != selected:
                continue
            started = time.monotonic()
            text, cleaned = await transcriber.cleanup_or_original(transcript, CleanupOptions(**values))
            passed = cleaned and all(re.search(pattern, text, re.IGNORECASE | re.DOTALL) for pattern in patterns)
            failures += not passed
            print(f"{'PASS' if passed else 'FAIL'} {name} duration_ms={int((time.monotonic()-started)*1000)} cleaned={cleaned}")
            if not passed:
                # Only fixed synthetic samples are ever sent by this script.
                print(f"  Synthetic result: {text!r}")
    finally:
        await transcriber.close()
    return 1 if failures else 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case", choices=[case[0] for case in CASES])
    args = parser.parse_args()
    raise SystemExit(asyncio.run(run(args.case)))
