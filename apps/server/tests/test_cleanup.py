import asyncio
import json
from dataclasses import replace

import httpx
import pytest

from app import OpenRouterTranscriber, Settings
from cleanup import normalize_formats


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


def run_cleanup(handler, text="I paid forty-five dollars.", settings=SETTINGS):
    async def run():
        transcriber = OpenRouterTranscriber(settings, httpx.MockTransport(handler))
        try:
            return await transcriber.cleanup_or_original(text)
        finally:
            await transcriber.close()
    return asyncio.run(run())


def test_model_receives_original_context_and_rules_then_formats_quantities():
    def handler(request):
        payload = json.loads(request.content)
        assert payload["messages"][1]["content"] == "I paid forty-five dollars."
        prompt = payload["messages"][0]["content"]
        for example in ("nine to five", "March third -> March 3", "3 March", "6pm", "5:30pm", "3.5", "2/3", "6'2", "Do not summarize"):
            assert example in prompt
        assert payload["temperature"] == 0
        return httpx.Response(200, json={"choices": [{"finish_reason": "stop", "message": {"content": "I paid 45 dollars."}}]})
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
