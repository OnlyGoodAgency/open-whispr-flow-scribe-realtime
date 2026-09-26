"""Authenticated Android PCM relay. Provider credentials and payloads aren't logged."""
from __future__ import annotations

import asyncio
import base64
import json
import logging
import time
from contextlib import suppress
from urllib.parse import urlencode

from fastapi import WebSocket, WebSocketDisconnect
from websockets.asyncio.client import connect
from cleanup import CleanupOptions

LOGGER = logging.getLogger("openwhisperflow.realtime")
PROVIDER_URL = "wss://api.elevenlabs.io/v1/speech-to-text/realtime"
SAMPLE_RATE = 16_000
MAX_CHUNK_BYTES = SAMPLE_RATE * 2  # at most one second of PCM16 mono
START_TIMEOUT = 15.0
FINAL_TIMEOUT = 10.0


class RealtimeProviderError(Exception):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


def provider_error(event: dict) -> RealtimeProviderError:
    kind = event.get("message_type", "")
    if kind in {"auth_error", "unaccepted_terms", "unaccepted_terms_error"}:
        return RealtimeProviderError("provider_access_denied")
    if kind in {"quota_exceeded", "quota_exceeded_error", "rate_limited", "throttled", "resource_exhausted"}:
        return RealtimeProviderError("provider_limit")
    return RealtimeProviderError("provider_failed")


class RealtimeBridge:
    def __init__(self, api_key: str, *, max_seconds: float = 900.0, connector=None, final_cleanup=None):
        self.api_key = api_key
        self.max_seconds = max_seconds
        self.connector = connector or connect
        self.final_cleanup = final_cleanup

    def connection(self, language: str | None):
        if not self.api_key:
            raise RealtimeProviderError("realtime_not_configured")
        params = {
            "model_id": "scribe_v2_realtime",
            "audio_format": "pcm_16000",
            "commit_strategy": "vad",
            "vad_silence_threshold_secs": "0.7",
            "include_timestamps": "false",
        }
        if language and language != "auto":
            params["language_code"] = language
        return self.connector(
            f"{PROVIDER_URL}?{urlencode(params)}",
            additional_headers={"xi-api-key": self.api_key},
            open_timeout=START_TIMEOUT,
            close_timeout=2,
            max_size=1_048_576,
            max_queue=16,
            ping_interval=20,
            ping_timeout=20,
        )

    async def await_started(self, provider) -> None:
        async with asyncio.timeout(START_TIMEOUT):
            event = json.loads(await provider.recv())
            if event.get("message_type") != "session_started":
                raise provider_error(event)

    async def check(self) -> None:
        """Checks actual realtime access without uploading any audio."""
        try:
            async with self.connection(None) as provider:
                await self.await_started(provider)
        except RealtimeProviderError:
            raise
        except Exception as error:
            # Never return provider bodies, headers, or exception messages to clients.
            raise RealtimeProviderError("provider_unavailable") from error

    async def run(self, client: WebSocket) -> None:
        started = time.monotonic()
        try:
            async with asyncio.timeout(self.max_seconds):
                setup = await asyncio.wait_for(client.receive_json(), timeout=10)
                options = CleanupOptions.model_validate(setup.get("cleanup", {}))
                language = setup.get("language")
                if setup.get("type") != "start" or (
                    language is not None and (not isinstance(language, str) or len(language) > 8)
                ):
                    raise RealtimeProviderError("invalid_request")
                async with self.connection(language) as provider:
                    await self.await_started(provider)
                    await client.send_json({"type": "ready", "model": "scribe_v2_realtime"})
                    LOGGER.info("ready connect_ms=%d", int((time.monotonic() - started) * 1000))
                    await self.relay(client, provider, started, options)
        except WebSocketDisconnect:
            pass
        except RealtimeProviderError as error:
            await self.send_error(client, error.code)
        except TimeoutError:
            await self.send_error(client, "realtime_timeout")
        except (ValueError, TypeError, AttributeError):
            await self.send_error(client, "invalid_request")
        except Exception:
            await self.send_error(client, "provider_unavailable")
        finally:
            with suppress(WebSocketDisconnect, RuntimeError, OSError):
                await client.close()

    async def send_error(self, client: WebSocket, code: str) -> None:
        LOGGER.warning("failed code=%s", code)
        with suppress(WebSocketDisconnect, RuntimeError, OSError):
            await client.send_json({"type": "error", "code": code})

    async def relay(self, client: WebSocket, provider, started: float, options: CleanupOptions) -> None:
        committed: list[str] = []
        partial = ""
        finish_requested = asyncio.Event()
        final_commit = asyncio.Event()
        last_event = time.monotonic()
        bytes_received = 0
        first_partial = True

        async def receive_audio():
            nonlocal bytes_received
            while True:
                message = await client.receive()
                if message["type"] == "websocket.disconnect":
                    raise WebSocketDisconnect(message.get("code", 1000))
                audio = message.get("bytes")
                if audio is not None:
                    if not audio or len(audio) > MAX_CHUNK_BYTES or len(audio) % 2:
                        raise RealtimeProviderError("invalid_audio")
                    bytes_received += len(audio)
                    if bytes_received > self.max_seconds * SAMPLE_RATE * 2:
                        raise RealtimeProviderError("audio_limit")
                    await provider.send(json.dumps({
                        "message_type": "input_audio_chunk",
                        "audio_base_64": base64.b64encode(audio).decode("ascii"),
                        "sample_rate": SAMPLE_RATE,
                    }))
                else:
                    command = json.loads(message.get("text", ""))
                    if command.get("type") == "cancel":
                        return "cancel"
                    if command.get("type") != "finish":
                        raise RealtimeProviderError("invalid_request")
                    finish_requested.set()
                    # A short silence tail lets VAD flush the last word. Explicit commit
                    # also flushes speech when the user stops without a natural pause.
                    await provider.send(json.dumps({
                        "message_type": "input_audio_chunk",
                        "audio_base_64": base64.b64encode(bytes(SAMPLE_RATE // 2)).decode("ascii"),
                        "sample_rate": SAMPLE_RATE,
                        "commit": True,
                    }))
                    return "finish"

        async def receive_transcripts():
            nonlocal partial, last_event, first_partial
            while True:
                event = json.loads(await provider.recv())
                kind = event.get("message_type")
                last_event = time.monotonic()
                if kind in {"partial_transcript", "committed_transcript"}:
                    text = event.get("text", "")
                    if not isinstance(text, str):
                        raise RealtimeProviderError("provider_failed")
                    if kind == "partial_transcript":
                        partial = text.strip()
                        if partial and first_partial:
                            LOGGER.info("first_partial elapsed_ms=%d", int((time.monotonic() - started) * 1000))
                            first_partial = False
                        await client.send_json({"type": "partial", "text": partial})
                    else:
                        if text.strip():
                            committed.append(text.strip())
                        partial = ""
                        await client.send_json({"type": "committed", "text": text.strip()})
                        if finish_requested.is_set():
                            final_commit.set()
                elif kind in {"session_started", "warning", "committed_transcript_with_timestamps"}:
                    continue
                elif kind in {"insufficient_audio_activity", "commit_throttled"} and finish_requested.is_set() and committed and not partial:
                    # The last phrase was already committed by VAD before Stop.
                    final_commit.set()
                    # Keep draining events until the finalizer closes the session.
                    continue
                else:
                    raise provider_error(event)

        async def finish():
            if bytes_received == 0:
                return ""
            # Require a commit, then drain trailing events. A partial alone is never
            # treated as final; if finalization fails Android can retry the full clip.
            async with asyncio.timeout(FINAL_TIMEOUT):
                await final_commit.wait()
                while partial or time.monotonic() - last_event < 0.4:
                    await asyncio.sleep(0.05)
            return " ".join(committed)

        audio_task = asyncio.create_task(receive_audio())
        transcript_task = asyncio.create_task(receive_transcripts())
        final_task = None
        try:
            done, _ = await asyncio.wait({audio_task, transcript_task}, return_when=asyncio.FIRST_COMPLETED)
            if transcript_task in done:
                transcript_task.result()
                raise RealtimeProviderError("provider_unavailable")
            if audio_task.result() == "cancel":
                return
            final_task = asyncio.create_task(finish())
            done, _ = await asyncio.wait({final_task, transcript_task}, return_when=asyncio.FIRST_COMPLETED)
            if transcript_task in done:
                transcript_task.result()
                raise RealtimeProviderError("provider_unavailable")
            text = final_task.result()
            # The final provider commit has been drained. Close the receiver
            # before cleanup so a provider disconnect cannot discard valid text.
            transcript_task.cancel()
            await asyncio.gather(transcript_task, return_exceptions=True)
            cleaned = False
            if self.final_cleanup is not None:
                text, cleaned = await self.final_cleanup(text, options)
            event = {"type": "final", "text": text}
            if cleaned:
                event["cleaned"] = True
                if not text:
                    event["discarded"] = True
            await client.send_json(event)
            LOGGER.info("completed audio_ms=%d total_ms=%d", bytes_received * 1000 // (SAMPLE_RATE * 2), int((time.monotonic() - started) * 1000))
        finally:
            tasks = [task for task in (audio_task, transcript_task, final_task) if task is not None]
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
