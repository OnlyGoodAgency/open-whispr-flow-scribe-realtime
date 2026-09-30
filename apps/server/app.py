from __future__ import annotations

import asyncio
import base64
import hmac
import json
import logging
import os
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import AsyncIterator, Protocol
from uuid import UUID

import httpx
import jwt
from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile, WebSocket
from jwt import PyJWKClient
from jwt.exceptions import PyJWTError
from pydantic import BaseModel

from realtime import RealtimeBridge, RealtimeProviderError
from cleanup import CLEANUP_PROMPT, CLEANUP_SCHEMA, CleanupOptions, apply_vocabulary, normalize_email_layout, normalize_formats


LOGGER = logging.getLogger("openwhisperflow")
SUPPORTED_FORMATS = {"aac", "flac", "m4a", "mp3", "mp4", "ogg", "wav", "webm"}
CONTENT_TYPE_FORMATS = {
    "audio/aac": "aac",
    "audio/flac": "flac",
    "audio/m4a": "m4a",
    "audio/mp4": "m4a",
    "audio/mpeg": "mp3",
    "audio/ogg": "ogg",
    "audio/wav": "wav",
    "audio/wave": "wav",
    "audio/webm": "webm",
    "audio/x-wav": "wav",
}


@dataclass(frozen=True)
class Settings:
    client_api_key: str
    openrouter_api_key: str
    supabase_url: str = ""
    primary_model: str = "openai/whisper-large-v3-turbo"
    fallback_model: str = "openai/whisper-1"
    cleanup_enabled: bool = True
    cleanup_model: str = "openai/gpt-4.1-mini"
    cleanup_timeout_seconds: float = 4.0
    openrouter_url: str = "https://openrouter.ai/api/v1/audio/transcriptions"
    openrouter_chat_url: str = "https://openrouter.ai/api/v1/chat/completions"
    max_upload_mb: int = 25
    timeout_seconds: float = 60.0
    app_url: str = ""
    app_title: str = "OpenWhisperFlow"
    elevenlabs_api_key: str = ""
    realtime_max_seconds: float = 900.0

    @classmethod
    def from_env(cls) -> "Settings":
        client_api_key = os.getenv("CLIENT_API_KEY") or os.getenv("WHISPER_API_KEY", "")
        openrouter_api_key = os.getenv("OPENROUTER_API_KEY", "")
        if len(client_api_key) < 32:
            raise RuntimeError("CLIENT_API_KEY must contain at least 32 characters")
        if not openrouter_api_key:
            raise RuntimeError("OPENROUTER_API_KEY is required")
        return cls(
            client_api_key=client_api_key,
            openrouter_api_key=openrouter_api_key,
            supabase_url=os.getenv("SUPABASE_URL", "").rstrip("/"),
            primary_model=os.getenv(
                "STT_PRIMARY_MODEL", "openai/whisper-large-v3-turbo"
            ),
            fallback_model=os.getenv("STT_FALLBACK_MODEL", "openai/whisper-1"),
            cleanup_enabled=os.getenv("TEXT_CLEANUP_ENABLED", "true").lower()
            in {"1", "true", "yes", "on"},
            cleanup_model=os.getenv("TEXT_CLEANUP_MODEL", "openai/gpt-4.1-mini"),
            cleanup_timeout_seconds=float(os.getenv("TEXT_CLEANUP_TIMEOUT_SECONDS", "4")),
            openrouter_url=os.getenv(
                "OPENROUTER_STT_URL",
                "https://openrouter.ai/api/v1/audio/transcriptions",
            ),
            openrouter_chat_url=os.getenv(
                "OPENROUTER_CHAT_URL",
                "https://openrouter.ai/api/v1/chat/completions",
            ),
            max_upload_mb=int(os.getenv("MAX_UPLOAD_MB", "25")),
            timeout_seconds=float(os.getenv("UPSTREAM_TIMEOUT_SECONDS", "60")),
            app_url=os.getenv("APP_URL", ""),
            app_title=os.getenv("APP_TITLE", "OpenWhisperFlow"),
            elevenlabs_api_key=os.getenv("ELEVENLABS_API_KEY", ""),
            realtime_max_seconds=float(os.getenv("REALTIME_MAX_SECONDS", "900")),
        )


class TranscriptionResponse(BaseModel):
    text: str
    model: str
    cleaned: bool
    discarded: bool = False


class ProviderError(Exception):
    def __init__(self, message: str, *, retriable: bool = True) -> None:
        super().__init__(message)
        self.retriable = retriable


class AccessTokenVerifier(Protocol):
    async def verify(self, token: str) -> str: ...


class SupabaseJwtVerifier:
    """Verify Supabase access tokens against the project's public signing keys."""

    def __init__(self, supabase_url: str) -> None:
        self.issuer = f"{supabase_url.rstrip('/')}/auth/v1"
        self.jwks_client = PyJWKClient(
            f"{self.issuer}/.well-known/jwks.json",
            cache_jwk_set=True,
            lifespan=600,
        )

    async def verify(self, token: str) -> str:
        try:
            signing_key = await asyncio.to_thread(
                self.jwks_client.get_signing_key_from_jwt,
                token,
            )
            claims = jwt.decode(
                token,
                signing_key.key,
                algorithms=["ES256", "RS256"],
                audience="authenticated",
                issuer=self.issuer,
                options={"require": ["exp", "iat", "iss", "sub", "role"]},
            )
        except PyJWTError as error:
            raise ValueError("Invalid Supabase access token") from error

        if claims.get("role") != "authenticated":
            raise ValueError("Invalid Supabase access token role")
        try:
            return str(UUID(str(claims["sub"])))
        except (KeyError, TypeError, ValueError) as error:
            raise ValueError("Invalid Supabase access token subject") from error


class OpenRouterTranscriber:
    def __init__(
        self,
        settings: Settings,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self.settings = settings
        self.client = httpx.AsyncClient(
            timeout=httpx.Timeout(settings.timeout_seconds),
            transport=transport,
        )

    async def close(self) -> None:
        await self.client.aclose()

    async def transcribe(
        self,
        audio: bytes,
        audio_format: str,
        language: str | None,
        prompt: str | None,
    ) -> tuple[str, str]:
        models = [self.settings.primary_model]
        if (
            self.settings.fallback_model
            and self.settings.fallback_model != self.settings.primary_model
        ):
            models.append(self.settings.fallback_model)

        last_error: ProviderError | None = None
        for index, model in enumerate(models):
            try:
                return await self._request(model, audio, audio_format, language, prompt), model
            except ProviderError as error:
                last_error = error
                has_fallback = index + 1 < len(models)
                if not error.retriable or not has_fallback:
                    break
                LOGGER.warning("Primary STT provider failed; trying the fallback model")

        raise last_error or ProviderError("No transcription model is configured")

    async def _request(
        self,
        model: str,
        audio: bytes,
        audio_format: str,
        language: str | None,
        prompt: str | None,
    ) -> str:
        payload: dict[str, object] = {
            "model": model,
            "input_audio": {
                "data": base64.b64encode(audio).decode("ascii"),
                "format": audio_format,
            },
            "temperature": 0,
        }
        if language and language != "auto":
            payload["language"] = language
        if prompt:
            payload["provider"] = {"options": {"groq": {"prompt": prompt[:1000]}}}

        headers = {
            "Authorization": f"Bearer {self.settings.openrouter_api_key}",
            "Content-Type": "application/json",
            "X-Title": self.settings.app_title,
        }
        if self.settings.app_url:
            headers["HTTP-Referer"] = self.settings.app_url

        try:
            response = await self.client.post(
                self.settings.openrouter_url,
                headers=headers,
                json=payload,
            )
        except httpx.HTTPError as error:
            raise ProviderError("Could not reach the transcription provider") from error

        if not response.is_success:
            retriable = response.status_code in {408, 409, 425, 429} or response.status_code >= 500
            raise ProviderError(
                f"Transcription provider returned HTTP {response.status_code}",
                retriable=retriable,
            )

        try:
            text = response.json().get("text", "").strip()
        except (ValueError, AttributeError) as error:
            raise ProviderError("Transcription provider returned invalid JSON") from error
        if not text:
            raise ProviderError("Transcription provider returned an empty transcription")
        return text

    async def cleanup(self, transcript: str, options: CleanupOptions | None = None) -> str:
        options = options or CleanupOptions()
        payload = {
            "model": self.settings.cleanup_model,
            "messages": [
                {
                    "role": "system",
                    "content": CLEANUP_PROMPT,
                },
                {"role": "user", "content": json.dumps({"transcript": transcript, **options.model_dump(exclude={"supports_discard"})}, ensure_ascii=False)},
            ],
            "temperature": 0,
            "response_format": CLEANUP_SCHEMA,
            "provider": {"require_parameters": True},
            # Leave room for long recordings and multilingual tokenization.
            "max_tokens": min(8192, max(1024, len(transcript) + 128)),
        }
        headers = {
            "Authorization": f"Bearer {self.settings.openrouter_api_key}",
            "Content-Type": "application/json",
            "X-Title": self.settings.app_title,
        }
        if self.settings.app_url:
            headers["HTTP-Referer"] = self.settings.app_url
        try:
            response = await self.client.post(
                self.settings.openrouter_chat_url,
                headers=headers,
                json=payload,
                timeout=min(8.0, max(0.1, self.settings.cleanup_timeout_seconds)),
            )
        except httpx.HTTPError as error:
            raise ProviderError("Could not reach the text cleanup provider") from error
        if not response.is_success:
            raise ProviderError(
                f"Text cleanup provider returned HTTP {response.status_code}"
            )
        try:
            choice = response.json()["choices"][0]
            if choice.get("finish_reason") not in {None, "stop"} or choice["message"].get("refusal"):
                raise ProviderError("Text cleanup provider returned incomplete text")
            result = json.loads(choice["message"]["content"])
            if not isinstance(result, dict) or set(result) != {"text", "discarded"}:
                raise ProviderError("Text cleanup provider returned invalid fields")
            cleaned = result["text"]
            discarded = result["discarded"]
            if not isinstance(cleaned, str) or type(discarded) is not bool:
                raise ProviderError("Text cleanup provider returned invalid text")
            cleaned = cleaned.strip(" \t\r")
            if discarded != (not cleaned.strip()):
                raise ProviderError("Text cleanup provider returned inconsistent text")
            if discarded:
                cleaned = ""
        except (ValueError, KeyError, IndexError, TypeError, AttributeError) as error:
            raise ProviderError("Text cleanup provider returned invalid JSON") from error
        return apply_vocabulary(normalize_email_layout(normalize_formats(cleaned)), options)

    async def cleanup_or_original(self, transcript: str, options: CleanupOptions | None = None) -> tuple[str, bool]:
        """One bounded cleanup attempt. Never retry or re-transcribe on failure."""
        options = options or CleanupOptions()
        original = apply_vocabulary(transcript, options)
        if not self.settings.cleanup_enabled or not transcript.strip():
            return original, False
        started = time.monotonic()
        try:
            # HTTP timeouts alone bound inactivity, not the whole request.
            async with asyncio.timeout(min(8.0, max(0.1, self.settings.cleanup_timeout_seconds))):
                text = await self.cleanup(transcript, options)
            # Old clients interpret an empty final as failure and may re-transcribe it.
            if not text and not options.supports_discard:
                LOGGER.info("cleanup fallback=client_compatibility duration_ms=%d", int((time.monotonic() - started) * 1000))
                return original, False
            LOGGER.info("cleanup completed duration_ms=%d", int((time.monotonic() - started) * 1000))
            return text, True
        except (ProviderError, TimeoutError):
            LOGGER.warning("cleanup fallback=original duration_ms=%d", int((time.monotonic() - started) * 1000))
            return original, False


def detect_audio_format(file: UploadFile) -> str:
    extension = Path(file.filename or "").suffix.lower().lstrip(".")
    if extension in SUPPORTED_FORMATS:
        return extension
    content_type = (file.content_type or "").lower().split(";", 1)[0]
    audio_format = CONTENT_TYPE_FORMATS.get(content_type)
    if audio_format:
        return audio_format
    raise HTTPException(status_code=415, detail="Unsupported audio format")


def create_app(
    settings: Settings | None = None,
    transport: httpx.AsyncBaseTransport | None = None,
    token_verifier: AccessTokenVerifier | None = None,
    realtime_connector=None,
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(application: FastAPI) -> AsyncIterator[None]:
        logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")
        resolved_settings = settings or Settings.from_env()
        application.state.settings = resolved_settings
        application.state.transcriber = OpenRouterTranscriber(resolved_settings, transport)
        application.state.realtime = RealtimeBridge(
            resolved_settings.elevenlabs_api_key,
            max_seconds=resolved_settings.realtime_max_seconds,
            connector=realtime_connector,
            final_cleanup=application.state.transcriber.cleanup_or_original,
        )
        application.state.token_verifier = token_verifier
        if application.state.token_verifier is None and resolved_settings.supabase_url:
            application.state.token_verifier = SupabaseJwtVerifier(
                resolved_settings.supabase_url
            )
        yield
        await application.state.transcriber.close()

    application = FastAPI(
        title="OpenWhisperFlow Gateway",
        version="1.0.0",
        docs_url=None,
        redoc_url=None,
        lifespan=lifespan,
    )

    @application.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    async def authorize(authorization: str | None) -> None:
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(status_code=401, detail="Invalid access token", headers={"WWW-Authenticate": "Bearer"})
        token = authorization[len("Bearer ") :]
        static_token = application.state.settings.client_api_key
        if static_token and hmac.compare_digest(token, static_token):
            return
        verifier = application.state.token_verifier
        if verifier is not None:
            try:
                await verifier.verify(token)
                return
            except ValueError:
                pass
        raise HTTPException(status_code=401, detail="Invalid access token", headers={"WWW-Authenticate": "Bearer"})

    @application.get("/health/realtime")
    async def realtime_health() -> dict[str, object]:
        # Configuration only; actual provider authentication is checked separately.
        return {
            "configured": bool(application.state.settings.elevenlabs_api_key),
            "provider": "elevenlabs",
            "model": "scribe_v2_realtime",
        }

    @application.post("/v1/realtime/check")
    async def realtime_check(authorization: str | None = Header(default=None)):
        await authorize(authorization)
        try:
            await application.state.realtime.check()
        except RealtimeProviderError as error:
            raise HTTPException(status_code=503, detail=error.code) from error
        return {"elevenlabs": "connected", "model": "scribe_v2_realtime"}

    @application.websocket("/realtime/transcription")
    async def realtime_transcription(websocket: WebSocket):
        # Native Android supplies a Bearer header. Never put tokens in query strings.
        await websocket.accept()
        try:
            await authorize(websocket.headers.get("authorization"))
        except HTTPException:
            await websocket.send_json({"type": "error", "code": "unauthorized"})
            await websocket.close(code=4401)
            return
        await application.state.realtime.run(websocket)

    @application.post("/v1/audio/transcriptions", response_model=TranscriptionResponse)
    async def transcriptions(
        file: UploadFile = File(...),
        model: str | None = Form(default=None),
        language: str | None = Form(default=None),
        prompt: str | None = Form(default=None),
        cleanup_options: str | None = Form(default=None),
        authorization: str | None = Header(default=None),
    ) -> TranscriptionResponse:
        del model  # Model selection is controlled centrally on the server.
        await authorize(authorization)
        try:
            if cleanup_options is not None and len(cleanup_options) > 64_000:
                raise ValueError("Cleanup options too large")
            options = CleanupOptions.model_validate_json(cleanup_options) if cleanup_options else CleanupOptions()
        except ValueError as error:
            raise HTTPException(status_code=400, detail="Invalid cleanup options") from error

        audio_format = detect_audio_format(file)
        maximum = application.state.settings.max_upload_mb * 1024 * 1024
        audio = await file.read(maximum + 1)
        if not audio:
            raise HTTPException(status_code=400, detail="The audio file is empty")
        if len(audio) > maximum:
            raise HTTPException(status_code=413, detail="The audio file is too large")

        try:
            text, used_model = await application.state.transcriber.transcribe(
                audio,
                audio_format,
                language,
                prompt,
            )
        except ProviderError as error:
            LOGGER.error("STT request failed: %s", error)
            raise HTTPException(status_code=502, detail=str(error)) from error
        text, cleaned = await application.state.transcriber.cleanup_or_original(text, options)
        return TranscriptionResponse(text=text, model=used_model, cleaned=cleaned, discarded=cleaned and not text)

    return application


app = create_app()
