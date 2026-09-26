import asyncio
import base64
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import httpx
import jwt
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from app import Settings, SupabaseJwtVerifier, create_app


SETTINGS = Settings(
    client_api_key="client-secret-that-is-at-least-32-chars",
    openrouter_api_key="openrouter-secret",
    cleanup_enabled=False,
)


class StubTokenVerifier:
    def __init__(self, *, user_id: str | None = None) -> None:
        self.user_id = user_id
        self.tokens: list[str] = []

    async def verify(self, token: str) -> str:
        self.tokens.append(token)
        if self.user_id is None:
            raise ValueError("invalid token")
        return self.user_id


def test_supabase_jwt_verifier_checks_standard_claims(monkeypatch) -> None:
    subject = "64b1475c-9cf3-42a6-9b6d-9409b1d2eb67"
    private_key = ec.generate_private_key(ec.SECP256R1())
    verifier = SupabaseJwtVerifier("https://example.supabase.co")
    monkeypatch.setattr(
        verifier.jwks_client,
        "get_signing_key_from_jwt",
        lambda token: SimpleNamespace(key=private_key.public_key()),
    )
    now = datetime.now(timezone.utc)
    token = jwt.encode(
        {
            "aud": "authenticated",
            "exp": now + timedelta(minutes=5),
            "iat": now,
            "iss": "https://example.supabase.co/auth/v1",
            "role": "authenticated",
            "sub": subject,
        },
        private_key,
        algorithm="ES256",
        headers={"kid": "test-key"},
    )

    assert asyncio.run(verifier.verify(token)) == subject


def test_requires_client_api_key() -> None:
    app = create_app(SETTINGS, httpx.MockTransport(lambda request: httpx.Response(200)))
    with TestClient(app) as client:
        response = client.post(
            "/v1/audio/transcriptions",
            files={"file": ("sample.wav", b"RIFFaudio", "audio/wav")},
        )
    assert response.status_code == 401


def test_accepts_verified_supabase_access_token() -> None:
    verifier = StubTokenVerifier(user_id="64b1475c-9cf3-42a6-9b6d-9409b1d2eb67")
    app = create_app(
        SETTINGS,
        httpx.MockTransport(
            lambda request: httpx.Response(200, json={"text": "Authenticated"})
        ),
        token_verifier=verifier,
    )

    with TestClient(app) as client:
        response = client.post(
            "/v1/audio/transcriptions",
            headers={"Authorization": "Bearer supabase-user-token"},
            files={"file": ("sample.wav", b"RIFFaudio", "audio/wav")},
        )

    assert response.status_code == 200
    assert response.json()["text"] == "Authenticated"
    assert verifier.tokens == ["supabase-user-token"]


def test_rejects_invalid_supabase_access_token() -> None:
    verifier = StubTokenVerifier()
    app = create_app(
        SETTINGS,
        httpx.MockTransport(
            lambda request: httpx.Response(200, json={"text": "must not run"})
        ),
        token_verifier=verifier,
    )

    with TestClient(app) as client:
        response = client.post(
            "/v1/audio/transcriptions",
            headers={"Authorization": "Bearer invalid-user-token"},
            files={"file": ("sample.wav", b"RIFFaudio", "audio/wav")},
        )

    assert response.status_code == 401
    assert response.json() == {"detail": "Invalid access token"}
    assert verifier.tokens == ["invalid-user-token"]


def test_converts_multipart_audio_to_openrouter_json() -> None:
    captured: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured.update(__import__("json").loads(request.content))
        assert request.headers["authorization"] == "Bearer openrouter-secret"
        return httpx.Response(200, json={"text": " Kumusta! "})

    app = create_app(SETTINGS, httpx.MockTransport(handler))
    with TestClient(app) as client:
        response = client.post(
            "/v1/audio/transcriptions",
            headers={"Authorization": f"Bearer {SETTINGS.client_api_key}"},
            files={"file": ("sample.wav", b"RIFFaudio", "audio/wav")},
            data={"model": "ignored-client-model", "language": "tl"},
        )

    assert response.status_code == 200
    assert response.json() == {
        "text": "Kumusta!",
        "model": "openai/whisper-large-v3-turbo",
        "cleaned": False,
    }
    assert captured["model"] == "openai/whisper-large-v3-turbo"
    assert captured["language"] == "tl"
    assert captured["input_audio"] == {
        "data": base64.b64encode(b"RIFFaudio").decode("ascii"),
        "format": "wav",
    }


def test_retries_whisper_1_after_retriable_primary_failure() -> None:
    models: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        payload = __import__("json").loads(request.content)
        models.append(payload["model"])
        if len(models) == 1:
            return httpx.Response(503, json={"error": "temporarily unavailable"})
        return httpx.Response(200, json={"text": "Fallback worked"})

    app = create_app(SETTINGS, httpx.MockTransport(handler))
    with TestClient(app) as client:
        response = client.post(
            "/v1/audio/transcriptions",
            headers={"Authorization": f"Bearer {SETTINGS.client_api_key}"},
            files={"file": ("sample.wav", b"RIFFaudio", "audio/wav")},
        )

    assert response.status_code == 200
    assert response.json()["model"] == "openai/whisper-1"
    assert models == ["openai/whisper-large-v3-turbo", "openai/whisper-1"]


def test_rejects_oversized_audio() -> None:
    settings = Settings(
        client_api_key=SETTINGS.client_api_key,
        openrouter_api_key=SETTINGS.openrouter_api_key,
        cleanup_enabled=False,
        max_upload_mb=1,
    )
    app = create_app(settings, httpx.MockTransport(lambda request: httpx.Response(200)))
    with TestClient(app) as client:
        response = client.post(
            "/v1/audio/transcriptions",
            headers={"Authorization": f"Bearer {SETTINGS.client_api_key}"},
            files={"file": ("sample.wav", b"x" * (1024 * 1024 + 1), "audio/wav")},
        )
    assert response.status_code == 413


def test_cleans_transcript_for_direct_insertion() -> None:
    calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.url.path)
        if request.url.path.endswith("/audio/transcriptions"):
            return httpx.Response(200, json={"text": "um hello there"})
        payload = __import__("json").loads(request.content)
        assert payload["model"] == "openai/gpt-4.1-mini"
        assert payload["messages"][1]["content"] == "um hello there"
        return httpx.Response(
            200,
            json={"choices": [{"message": {"content": "Hello there."}}]},
        )

    settings = Settings(
        client_api_key=SETTINGS.client_api_key,
        openrouter_api_key=SETTINGS.openrouter_api_key,
        cleanup_enabled=True,
    )
    app = create_app(settings, httpx.MockTransport(handler))
    with TestClient(app) as client:
        response = client.post(
            "/v1/audio/transcriptions",
            headers={"Authorization": f"Bearer {SETTINGS.client_api_key}"},
            files={"file": ("sample.wav", b"RIFFaudio", "audio/wav")},
        )

    assert response.status_code == 200
    assert response.json()["text"] == "Hello there."
    assert response.json()["cleaned"] is True
    assert calls == ["/api/v1/audio/transcriptions", "/api/v1/chat/completions"]


def test_returns_raw_transcript_when_cleanup_fails() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/audio/transcriptions"):
            return httpx.Response(200, json={"text": "raw transcript"})
        return httpx.Response(503)

    settings = Settings(
        client_api_key=SETTINGS.client_api_key,
        openrouter_api_key=SETTINGS.openrouter_api_key,
        cleanup_enabled=True,
    )
    app = create_app(settings, httpx.MockTransport(handler))
    with TestClient(app) as client:
        response = client.post(
            "/v1/audio/transcriptions",
            headers={"Authorization": f"Bearer {SETTINGS.client_api_key}"},
            files={"file": ("sample.wav", b"RIFFaudio", "audio/wav")},
        )

    assert response.status_code == 200
    assert response.json()["text"] == "raw transcript"
    assert response.json()["cleaned"] is False
