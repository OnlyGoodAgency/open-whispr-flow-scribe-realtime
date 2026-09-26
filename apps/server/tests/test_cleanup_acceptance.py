"""Check the acceptance runner's validity, not the paid model's accuracy."""
import asyncio
import json

import check_cleanup
from cleanup import CleanupOptions
from cleanup_cases import CASES


def test_suite_has_every_spec_section_and_valid_request_options():
    assert {case.category for case in CASES} == {"numbers", "corrections", "filler", "punctuation", "structure", "symbols", "vocabulary", "voice"}
    assert len({case.name for case in CASES}) == len(CASES)
    for case in CASES:
        CleanupOptions.model_validate(case.options)
        # A malformed check must not fail its own documented expected output.
        assert check_cleanup.passes(case, case.expected, True), case.name


def test_acceptance_checks_case_sensitive_requirements_and_fallback():
    brands = check_cleanup.selected_cases("brand-casing")[0]
    assert not check_cleanup.passes(brands, brands.expected.lower(), True)
    assert not check_cleanup.passes(brands, brands.expected, False)
    deletion = check_cleanup.selected_cases("deletion")[0]
    assert not check_cleanup.passes(deletion, "Delete that last sentence.", True)
    assert not check_cleanup.passes(deletion, "", False)
    assert check_cleanup.passes(deletion, "", True)


def test_category_selection_does_not_mix_spec_sections():
    selected = check_cleanup.selected_cases(category="structure")
    assert {case.name for case in selected} == {"numbered-list", "bullets", "bullet-command", "email", "no-added-structure"}


def test_runner_passes_learning_options_closes_client_and_saves_report(monkeypatch, tmp_path):
    instances = []
    case = check_cleanup.selected_cases("learned-correction")[0]

    class FakeTranscriber:
        def __init__(self, settings):
            instances.append(self)
            self.closed = False

        async def cleanup_or_original(self, transcript, options):
            assert transcript == case.spoken
            assert options.learned_vocabulary[0].written == "ACME"
            return case.expected, True

        async def close(self):
            self.closed = True

    monkeypatch.setenv("TEXT_CLEANUP_ENABLED", "true")
    monkeypatch.setenv("OPENROUTER_API_KEY", "test-only")
    monkeypatch.setenv("CLIENT_API_KEY", "test-only-" * 4)
    monkeypatch.setattr(check_cleanup, "OpenRouterTranscriber", FakeTranscriber)
    report = tmp_path / "report.json"
    assert asyncio.run(check_cleanup.run(case.name, report=report)) == 0
    assert instances[0].closed
    assert json.loads(report.read_text())["results"][0]["actual"] == case.expected
