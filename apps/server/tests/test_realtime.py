import asyncio
import base64
import json
import logging
import httpx
from dataclasses import replace

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app import Settings, create_app

SETTINGS = Settings(
    client_api_key="client-secret-that-is-at-least-32-chars",
    openrouter_api_key="openrouter-secret",
    elevenlabs_api_key="elevenlabs-server-secret",
    cleanup_enabled=False,
)
AUTH = {"Authorization": f"Bearer {SETTINGS.client_api_key}"}


class FakeProvider:
    def __init__(self, *, error=None, final_error=None, fail_final=False):
        self.events = asyncio.Queue()
        self.sent = []
        self.closed = False
        self.error = error
        self.final_error = final_error
        self.fail_final = fail_final

    async def __aenter__(self):
        self.events.put_nowait(self.error or {"message_type": "session_started"})
        return self

    async def __aexit__(self, *args):
        self.closed = True

    async def recv(self):
        return json.dumps(await self.events.get())

    async def send(self, message):
        event = json.loads(message)
        self.sent.append(event)
        if event.get("commit"):
            if self.fail_final:
                return
            self.events.put_nowait(self.final_error or {
                "message_type": "committed_transcript", "text": "Another phrase.",
            })
        elif len(self.sent) == 1:
            self.events.put_nowait({"message_type": "partial_transcript", "text": "Hello wor"})
            self.events.put_nowait({"message_type": "partial_transcript", "text": "Hello world"})
            self.events.put_nowait({"message_type": "committed_transcript", "text": "Hello world."})
        else:
            self.events.put_nowait({"message_type": "partial_transcript", "text": "Another phrase"})


class FakeConnector:
    def __init__(self, provider=None):
        self.provider = provider or FakeProvider()
        self.calls = []

    def __call__(self, url, **options):
        self.calls.append((url, options))
        return self.provider


def make_app(connector, settings=SETTINGS, verifier=None):
    return create_app(settings, token_verifier=verifier, realtime_connector=connector)


def start(socket):
    socket.send_json({"type": "start", "language": "en"})
    assert socket.receive_json() == {"type": "ready", "model": "scribe_v2_realtime"}


def read_first_phrase(socket):
    assert socket.receive_json() == {"type": "partial", "text": "Hello wor"}
    assert socket.receive_json() == {"type": "partial", "text": "Hello world"}
    assert socket.receive_json() == {"type": "committed", "text": "Hello world."}


def test_websocket_rejects_auth_before_contacting_provider():
    connector = FakeConnector()
    with TestClient(make_app(connector)) as client:
        with client.websocket_connect("/realtime/transcription") as socket:
            assert socket.receive_json() == {"type": "error", "code": "unauthorized"}
            with pytest.raises(WebSocketDisconnect) as closed:
                socket.receive_json()
            assert closed.value.code == 4401
    assert connector.calls == []


def test_verified_supabase_session_can_stream():
    class Verifier:
        async def verify(self, token):
            assert token == "user-session"
            return "user-1"
    connector = FakeConnector()
    with TestClient(make_app(connector, verifier=Verifier())) as client:
        with client.websocket_connect("/realtime/transcription", headers={"Authorization": "Bearer user-session"}) as socket:
            start(socket)
            socket.send_json({"type": "cancel"})
    assert connector.provider.closed


def test_pcm_live_events_and_final_are_relayed_without_secret_or_duplicate_segments(caplog):
    caplog.set_level(logging.INFO, logger="openwhisperflow.realtime")
    connector = FakeConnector()
    audio = bytes(6_400)
    with TestClient(make_app(connector)) as client:
        with client.websocket_connect("/realtime/transcription", headers=AUTH) as socket:
            start(socket)
            socket.send_bytes(audio)
            read_first_phrase(socket)
            socket.send_bytes(audio)
            assert socket.receive_json() == {"type": "partial", "text": "Another phrase"}
            socket.send_json({"type": "finish"})
            assert socket.receive_json() == {"type": "committed", "text": "Another phrase."}
            assert socket.receive_json() == {"type": "final", "text": "Hello world. Another phrase."}
    url, options = connector.calls[0]
    assert "scribe_v2_realtime" in url
    assert "language_code=en" in url
    assert "commit_strategy=vad" in url
    assert options["additional_headers"] == {"xi-api-key": SETTINGS.elevenlabs_api_key}
    assert base64.b64decode(connector.provider.sent[0]["audio_base_64"]) == audio
    assert connector.provider.sent[-1]["commit"] is True
    assert connector.provider.closed
    assert SETTINGS.elevenlabs_api_key not in caplog.text
    assert "Hello world" not in caplog.text


@pytest.mark.parametrize("audio", [b"x", bytes(32_002), b""], ids=["odd-length", "oversized", "empty"])
def test_invalid_pcm_is_rejected_before_forwarding(audio):
    connector = FakeConnector()
    with TestClient(make_app(connector)) as client:
        with client.websocket_connect("/realtime/transcription", headers=AUTH) as socket:
            start(socket)
            socket.send_bytes(audio)
            assert socket.receive_json() == {"type": "error", "code": "invalid_audio"}
    assert connector.provider.sent == []


def test_cancel_closes_provider_without_final_commit():
    connector = FakeConnector()
    with TestClient(make_app(connector)) as client:
        with client.websocket_connect("/realtime/transcription", headers=AUTH) as socket:
            start(socket)
            socket.send_json({"type": "cancel"})
            with pytest.raises(WebSocketDisconnect):
                socket.receive_json()
    assert connector.provider.closed
    assert connector.provider.sent == []


def test_disconnect_closes_provider():
    connector = FakeConnector()
    with TestClient(make_app(connector)) as client:
        with client.websocket_connect("/realtime/transcription", headers=AUTH) as socket:
            start(socket)
    assert connector.provider.closed


def test_missing_key_keeps_batch_server_alive_and_check_protected():
    connector = FakeConnector()
    with TestClient(make_app(connector, replace(SETTINGS, elevenlabs_api_key=""))) as client:
        assert client.get("/health").json() == {"status": "ok"}
        assert client.get("/health/realtime").json()["configured"] is False
        assert client.post("/v1/realtime/check").status_code == 401
        response = client.post("/v1/realtime/check", headers=AUTH)
        assert response.status_code == 503
        assert response.json() == {"detail": "realtime_not_configured"}
    assert connector.calls == []


def test_protected_check_authenticates_realtime_without_sending_audio():
    connector = FakeConnector()
    with TestClient(make_app(connector)) as client:
        response = client.post("/v1/realtime/check", headers=AUTH)
        assert response.json() == {"elevenlabs": "connected", "model": "scribe_v2_realtime"}
    assert connector.provider.sent == []
    assert connector.provider.closed


def test_provider_error_does_not_expose_private_body():
    connector = FakeConnector(FakeProvider(error={
        "message_type": "auth_error", "error": "private account body elevenlabs-server-secret",
    }))
    with TestClient(make_app(connector)) as client:
        response = client.post("/v1/realtime/check", headers=AUTH)
        assert response.status_code == 503
        assert response.json() == {"detail": "provider_access_denied"}


def test_failed_finalization_reports_error_instead_of_promoting_partial(monkeypatch):
    monkeypatch.setattr("realtime.FINAL_TIMEOUT", 0.1)
    connector = FakeConnector(FakeProvider(fail_final=True))
    with TestClient(make_app(connector)) as client:
        with client.websocket_connect("/realtime/transcription", headers=AUTH) as socket:
            start(socket)
            socket.send_bytes(bytes(6_400))
            read_first_phrase(socket)
            socket.send_bytes(bytes(6_400))
            assert socket.receive_json()["type"] == "partial"
            socket.send_json({"type": "finish"})
            assert socket.receive_json() == {"type": "error", "code": "realtime_timeout"}


def test_stop_after_vad_commit_retains_the_committed_phrase():
    connector = FakeConnector(FakeProvider(final_error={"message_type": "commit_throttled"}))
    with TestClient(make_app(connector)) as client:
        with client.websocket_connect("/realtime/transcription", headers=AUTH) as socket:
            start(socket)
            socket.send_bytes(bytes(6_400))
            read_first_phrase(socket)
            socket.send_json({"type": "finish"})
            assert socket.receive_json() == {"type": "final", "text": "Hello world."}


def test_finish_drains_delayed_last_phrase_instead_of_returning_an_earlier_commit():
    class DelayedFinalProvider(FakeProvider):
        async def send(self, message):
            if not json.loads(message).get("commit"):
                return await super().send(message)
            self.sent.append(json.loads(message))
            self.events.put_nowait({"message_type": "committed_transcript", "text": ""})
            loop = asyncio.get_running_loop()
            loop.call_later(0.05, self.events.put_nowait, {
                "message_type": "partial_transcript", "text": "Another phrase",
            })
            loop.call_later(0.45, self.events.put_nowait, {
                "message_type": "committed_transcript", "text": "Another phrase.",
            })

    connector = FakeConnector(DelayedFinalProvider())
    with TestClient(make_app(connector)) as client:
        with client.websocket_connect("/realtime/transcription", headers=AUTH) as socket:
            start(socket)
            socket.send_bytes(bytes(6_400))
            read_first_phrase(socket)
            socket.send_bytes(bytes(6_400))
            assert socket.receive_json()["type"] == "partial"
            socket.send_json({"type": "finish"})
            assert socket.receive_json() == {"type": "committed", "text": ""}
            assert socket.receive_json() == {"type": "partial", "text": "Another phrase"}
            assert socket.receive_json() == {"type": "committed", "text": "Another phrase."}
            assert socket.receive_json() == {"type": "final", "text": "Hello world. Another phrase."}


def test_cleanup_runs_once_after_stop_leaving_live_events_untouched():
    calls = []

    def handler(request):
        calls.append(request.url.path)
        payload = json.loads(request.content)
        assert json.loads(payload["messages"][1]["content"])["transcript"] == "Hello world. Another phrase."
        return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps({"text": "Hello world.\n\nAnother phrase.", "discarded": False})}, "finish_reason": "stop"}]})

    connector = FakeConnector()
    app = create_app(replace(SETTINGS, cleanup_enabled=True), httpx.MockTransport(handler), realtime_connector=connector)
    with TestClient(app) as client:
        with client.websocket_connect("/realtime/transcription", headers=AUTH) as socket:
            start(socket)
            socket.send_bytes(bytes(6_400))
            read_first_phrase(socket)
            assert calls == []
            socket.send_json({"type": "finish"})
            assert socket.receive_json()["type"] == "committed"
            assert socket.receive_json() == {"type": "final", "text": "Hello world.\n\nAnother phrase.", "cleaned": True}
    assert calls == ["/api/v1/chat/completions"]


@pytest.mark.parametrize("failure", ["timeout", "http", "truncated"])
def test_cleanup_failure_returns_streamed_text_without_triggering_batch(failure):
    async def handler(request):
        if failure == "timeout":
            await asyncio.sleep(1)
        if failure == "truncated":
            return httpx.Response(200, json={"choices": [{"message": {"content": "Hello"}, "finish_reason": "length"}]})
        return httpx.Response(503)

    connector = FakeConnector()
    settings = replace(SETTINGS, cleanup_enabled=True, cleanup_timeout_seconds=0.1)
    app = create_app(settings, httpx.MockTransport(handler), realtime_connector=connector)
    with TestClient(app) as client:
        with client.websocket_connect("/realtime/transcription", headers=AUTH) as socket:
            start(socket)
            socket.send_bytes(bytes(6_400))
            read_first_phrase(socket)
            socket.send_json({"type": "finish"})
            assert socket.receive_json()["type"] == "committed"
            assert socket.receive_json() == {"type": "final", "text": "Hello world. Another phrase."}


def test_cancel_does_not_request_cleanup():
    def handler(request):
        pytest.fail("cancel must not request cleanup")
    app = create_app(replace(SETTINGS, cleanup_enabled=True), httpx.MockTransport(handler), realtime_connector=FakeConnector())
    with TestClient(app) as client:
        with client.websocket_connect("/realtime/transcription", headers=AUTH) as socket:
            start(socket)
            socket.send_bytes(bytes(6_400))
            read_first_phrase(socket)
            socket.send_json({"type": "cancel"})
            with pytest.raises(WebSocketDisconnect):
                socket.receive_json()


def test_provider_disconnect_after_final_commit_cannot_discard_cleanup():
    class ClosingProvider(FakeProvider):
        async def send(self, message):
            await super().send(message)
            if json.loads(message).get("commit"):
                asyncio.get_running_loop().call_later(0.5, self.events.put_nowait, {"closed": True})

        async def recv(self):
            event = await self.events.get()
            if event.get("closed"):
                raise OSError("provider closed its session")
            return json.dumps(event)

    async def handler(request):
        await asyncio.sleep(0.6)
        return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps({"text": "Hello world. Another phrase.", "discarded": False})}}]})

    app = create_app(replace(SETTINGS, cleanup_enabled=True), httpx.MockTransport(handler), realtime_connector=FakeConnector(ClosingProvider()))
    with TestClient(app) as client:
        with client.websocket_connect("/realtime/transcription", headers=AUTH) as socket:
            start(socket)
            socket.send_bytes(bytes(6_400))
            read_first_phrase(socket)
            socket.send_json({"type": "finish"})
            assert socket.receive_json()["type"] == "committed"
            assert socket.receive_json() == {"type": "final", "text": "Hello world. Another phrase.", "cleaned": True}


def test_realtime_cleanup_options_and_intentional_empty_final():
    calls = []
    def handler(request):
        data = json.loads(json.loads(request.content)["messages"][1]["content"])
        assert data["vocabulary"] == [{"spoken": "akme", "written": "ACME"}]
        assert data["learned_terms"] == ["Patel"]
        calls.append(request.url.path)
        return httpx.Response(200, json={"choices": [{"message": {"content": json.dumps({"text": "", "discarded": True})}}]})
    app = create_app(replace(SETTINGS, cleanup_enabled=True), httpx.MockTransport(handler), realtime_connector=FakeConnector())
    with TestClient(app) as client:
        with client.websocket_connect("/realtime/transcription", headers=AUTH) as socket:
            socket.send_json({"type": "start", "cleanup": {"supports_discard": True, "vocabulary": [{"spoken": "akme", "written": "ACME"}], "learned_terms": ["Patel"]}})
            assert socket.receive_json()["type"] == "ready"
            socket.send_bytes(bytes(6_400))
            read_first_phrase(socket)
            assert calls == []
            socket.send_json({"type": "finish"})
            assert socket.receive_json()["type"] == "committed"
            assert socket.receive_json() == {"type": "final", "text": "", "cleaned": True, "discarded": True}
    assert calls == ["/api/v1/chat/completions"]


def test_invalid_realtime_cleanup_options_do_not_open_provider():
    connector = FakeConnector()
    with TestClient(make_app(connector)) as client:
        with client.websocket_connect("/realtime/transcription", headers=AUTH) as socket:
            socket.send_json({"type": "start", "cleanup": {"filter_profanity": "false"}})
            assert socket.receive_json() == {"type": "error", "code": "invalid_request"}
    assert connector.calls == []


def test_learned_and_explicit_vocabulary_reach_realtime_recognition():
    from urllib.parse import parse_qs, urlparse
    connector = FakeConnector()
    with TestClient(make_app(connector)) as client:
        with client.websocket_connect("/realtime/transcription", headers=AUTH) as socket:
            socket.send_json({"type": "start", "cleanup": {"vocabulary": [{"spoken": "akme", "written": "ACME"}], "learned_vocabulary": [{"spoken": "click up", "written": "ClickUp"}], "learned_terms": ["Patel", "acme"]}})
            assert socket.receive_json()["type"] == "ready"
            socket.send_json({"type": "cancel"})
    query = parse_qs(urlparse(connector.calls[0][0]).query)
    assert query["keyterms"] == ["ACME", "ClickUp", "Patel"]
