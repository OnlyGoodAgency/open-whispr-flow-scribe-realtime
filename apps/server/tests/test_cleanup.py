import asyncio
import json
from dataclasses import replace

import httpx
import pytest

from app import OpenRouterTranscriber, Settings
from cleanup import CleanupOptions, apply_vocabulary, normalize_formats, realtime_keyterms


@pytest.mark.parametrize(("spoken", "expected"), [
    ("I paid 45 dollars.", "I paid $45."),
    ("I paid forty-five dollars.", "I paid $45."),
    ("Twenty five dollars", "$25"),
    ("Fifty quid", "£50"),
    ("three hundred and twenty-five euros", "€325"),
    ("one thousand two hundred dollars", "$1200"),
    ("Thirty percent", "30%"),
    ("30 per cent", "30%"),
    ("It weighs thirty-seven kilos.", "It weighs 37kg."),
    ("10 kg, 3.5 kilograms and 20 degrees Celsius", "10kg, 3.5kg and 20° Celsius"),
    ("20 %; 10 grams; 3 kilometers", "20%; 10g; 3km"),
    ("First paragraph.\n\n37 kg in another topic.", "First paragraph.\n\n37kg in another topic."),
    ("One of a kind, a thousand times better, nine to five.", "One of a kind, a thousand times better, nine to five."),
    ("It weighs 50 pounds.", "It weighs 50 pounds."),
    ("one two three dollars", "one two three dollars"),
    ('Keep "45 dollars" as the exact label.', 'Keep "45 dollars" as the exact label.'),
    ("john45@acme.com https://example.com/45-kilos", "john45@acme.com https://example.com/45-kilos"),
    ("I think it's roughly $45. One of a kind, like a charm.", "I think it's roughly $45. One of a kind, like a charm."),
    ("Kumusta, 45 dollars ang bayad.", "Kumusta, $45 ang bayad."),
])
def test_explicit_quantities_and_preservation(spoken, expected):
    assert normalize_formats(spoken) == expected


SETTINGS = Settings(client_api_key="x" * 32, openrouter_api_key="not-a-real-key")


def run_cleanup(handler, text="I paid forty-five dollars.", settings=SETTINGS, options=None):
    async def run():
        transcriber = OpenRouterTranscriber(settings, httpx.MockTransport(handler))
        try:
            return await transcriber.cleanup_or_original(text, options)
        finally:
            await transcriber.close()
    return asyncio.run(run())


def test_model_receives_original_context_and_rules_then_formats_quantities():
    def handler(request):
        payload = json.loads(request.content)
        assert json.loads(payload["messages"][1]["content"])["transcript"] == "I paid forty-five dollars."
        prompt = payload["messages"][0]["content"]
        for example in ("nine to five", "March third -> March 3", "3 March", "6pm", "5:30pm", "3.5", "2/3", "6'2", "Do not summarize"):
            assert example in prompt
        assert payload["temperature"] == 0
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": json.dumps({"text": "I paid 45 dollars.", "discarded": False})}}]})
    assert run_cleanup(handler) == ("I paid $45.", True)


@pytest.mark.parametrize("body", [
    {"choices": [{"finish_reason": "length", "message": {"content": "I paid"}}]},
    {"choices": [{"finish_reason": "content_filter", "message": {"content": "I paid"}}]},
    {"choices": [{"message": {"content": "", "refusal": "not allowed"}}]},
    {"choices": [{"message": {"content": ""}}]},
    {"choices": [{"message": {"content": None}}]},
    {"choices": []},
    {"wrong": "shape"},
])
def test_incomplete_or_invalid_cleanup_preserves_original(body):
    assert run_cleanup(lambda request: httpx.Response(200, json=body)) == ("I paid forty-five dollars.", False)


def test_disabled_cleanup_makes_no_request():
    def handler(request):
        pytest.fail("disabled cleanup must not contact OpenRouter")
    assert run_cleanup(handler, settings=replace(SETTINGS, cleanup_enabled=False))[1] is False


def test_cleanup_total_timeout_keeps_original(caplog):
    async def handler(request):
        await asyncio.sleep(1)
        pytest.fail("slow cleanup should be cancelled")
    assert run_cleanup(handler, settings=replace(SETTINGS, cleanup_timeout_seconds=0.1)) == ("I paid forty-five dollars.", False)
    assert "fallback=original" in caplog.text
    assert "forty-five" not in caplog.text
    assert SETTINGS.openrouter_api_key not in caplog.text


def test_http_error_and_malformed_json_keep_original():
    assert run_cleanup(lambda request: httpx.Response(402))[1] is False
    assert run_cleanup(lambda request: httpx.Response(200, text="invalid"))[1] is False


def test_explicit_vocabulary_wins_preserving_word_boundaries():
    options = CleanupOptions(vocabulary=[{"spoken": "akme", "written": "Acme"}, {"spoken": "github", "written": "GitHUB"}])
    assert apply_vocabulary("Akme and github, not akmeology.", options) == "Acme and GitHUB, not akmeology."


def test_observed_edit_alias_applies_but_manual_dictionary_wins():
    options = CleanupOptions(learned_vocabulary=[{"spoken": "akme", "written": "ACME"}], vocabulary=[{"spoken": "Acme", "written": "Acme"}])
    assert apply_vocabulary("Akme and ACME.", options) == "Acme and Acme."
    overridden = CleanupOptions(learned_vocabulary=[{"spoken": "akme", "written": "OldName"}], vocabulary=[{"spoken": "akme", "written": "MyName"}])
    assert apply_vocabulary("akme", overridden) == "MyName"
    assert apply_vocabulary("OldName", overridden) == "MyName"
    assert realtime_keyterms(overridden) == ["MyName"]


def test_scribe_hints_are_bounded_deduplicated_and_manual_first():
    options = CleanupOptions(vocabulary=[{"spoken": "akme", "written": "ACME"}], learned_vocabulary=[{"spoken": "click up", "written": "ClickUp"}], learned_terms=["acme"] + [f"Term{i}" for i in range(99)])
    terms = realtime_keyterms(options)
    assert terms[:2] == ["ACME", "ClickUp"]
    assert len(terms) == 50
    assert "acme" not in terms


@pytest.mark.parametrize("options", [
    {"vocabulary": [{"spoken": "", "written": "Name"}]},
    {"vocabulary": [{"spoken": "Name", "written": "x" * 81}]},
    {"vocabulary": [{"spoken": "Name\ncommand", "written": "Name"}]},
    {"vocabulary": [{"spoken": "Name", "written": "Name"}] * 51},
    {"learned_terms": ["x" * 81]},
    {"learned_terms": ["Name"] * 101},
    {"context": "x" * 4001},
    {"filter_profanity": "false"},
    {"unknown": True},
])
def test_options_are_bounded_and_validated(options):
    with pytest.raises(ValueError):
        CleanupOptions.model_validate(options)


@pytest.mark.parametrize("text", ["", "   "])
def test_intentionally_discarded_content_is_not_returned_as_a_command(text):
    body = {"choices": [{"message": {"content": json.dumps({"text": text, "discarded": True})}}]}
    assert run_cleanup(lambda request: httpx.Response(200, json=body), text="Meet at 5. Scratch that.", options=CleanupOptions(supports_discard=True)) == ("", True)


@pytest.mark.parametrize("result", [
    {"text": "", "discarded": False},
    {"text": "Must not disappear", "discarded": True},
    {"text": "", "discarded": "true"},
    {"text": "", "discarded": 1},
    {"text": "Incomplete schema"},
    {"text": "Added metadata", "discarded": False, "other": "unexpected"},
])
def test_invalid_discard_response_returns_original(result):
    body = {"choices": [{"message": {"content": json.dumps(result)}}]}
    assert run_cleanup(lambda request: httpx.Response(200, json=body))[1] is False


def test_old_client_keeps_original_instead_of_retrying_an_empty_final():
    body = {"choices": [{"message": {"content": json.dumps({"text": "", "discarded": True})}}]}
    assert run_cleanup(lambda request: httpx.Response(200, json=body), text="Meet at 5. Scratch that.") == ("Meet at 5. Scratch that.", False)


@pytest.mark.parametrize("status", [402, 503])
def test_personal_spelling_still_wins_during_provider_fallback(status):
    options = CleanupOptions(vocabulary=[{"spoken": "akme", "written": "ACME"}])
    assert run_cleanup(lambda request: httpx.Response(status), text="Send it to akme.", options=options) == ("Send it to ACME.", False)


@pytest.mark.parametrize("text", [
    "1. Send the report.\n2. Pay $45.\n3. Call Acme.",
    "Shopping list:\n- Apples\n- Milk\n- Bread",
    "Hi Alex,\n\nCan we meet at 5:30pm?\n\nThanks,\nPatel",
    "Hello.\n",
])
def test_structured_plain_text_survives_transport_and_normalization(text):
    def handler(request):
        payload = json.loads(request.content)
        assert payload["response_format"]["json_schema"]["strict"] is True
        assert payload["provider"]["require_parameters"] is True
        return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps({"text": text, "discarded": False})}}]})
    assert run_cleanup(handler) == (text, True)
