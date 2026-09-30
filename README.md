# OpenWhisperFlow

System-wide dictation for Android and desktop with one shared, self-hosted API
gateway. Android streams microphone audio to ElevenLabs Scribe v2 Realtime and
shows text while speaking, then inserts the finalized result on confirmation.
Desktop uses batch transcription through OpenRouter.

```text
Android microphone -- WSS + bearer --> Coolify gateway --> ElevenLabs Scribe v2 Realtime
                                               |
Android batch fallback / desktop -- HTTPS ----> +-------> OpenRouter STT + cleanup
                                               |
                                               +-------> Supabase Auth / text history
```

## What runs where

- **Coolify / Hostinger VPS:** authenticated gateway that keeps ElevenLabs and
  OpenRouter keys on the server, relays realtime audio, and controls fallback.
- **ElevenLabs:** runs `scribe_v2_realtime` for Android live cloud dictation.
  Partial text updates while recording; confirmed phrases are retained once.
- **Supabase:** hosted Auth and Postgres for accounts and cross-device text
  history. Raw audio is not stored in Supabase.
- **OpenRouter:** runs `openai/whisper-large-v3-turbo`; the gateway retries
  `openai/whisper-1` after a transient provider failure, then cleans the result
  with `openai/gpt-4.1-mini`.
- **Android:** use the voice keyboard, or keep Samsung Keyboard and enable the optional floating microphone to insert text into the focused field.
- **Windows/macOS/Linux:** a tray app captures a global hotkey and pastes the result.

Both included clients are derived from MIT-licensed Dictus projects and share
the same authenticated gateway. Android ships as a system keyboard;
desktop ships as a Tauri tray application with a global dictation shortcut. Both
can fall back to their installed local models when the gateway is unavailable.

The exact models used internally by Wispr Flow are not publicly documented.
This project recreates the same product pattern with an independently selected,
speech providers instead of claiming to reproduce proprietary internals.

## Deploy to Coolify

1. Push this repository to a Git provider reachable by Coolify.
2. In Coolify, create a project and choose **New Resource > Public/Private
   Repository**.
3. Select **Docker Compose** and use `/docker-compose.yml`.
4. Add these runtime environment variables:

   - `OPENROUTER_API_KEY`: the server-side key from your OpenRouter account.
   - `ELEVENLABS_API_KEY`: the server-side ElevenLabs key with speech-to-text access.
   - `REALTIME_MAX_SECONDS`: `900` (maximum live session duration, 15 minutes).
   - `CLIENT_API_KEY`: a different random 64-character secret used by the
     Android and desktop clients during the Supabase Auth migration.
   - `SUPABASE_URL`: the hosted Supabase project URL. When set, the gateway
     accepts verified Supabase user access tokens as well as the migration key.
   - `STT_PRIMARY_MODEL`: `openai/whisper-large-v3-turbo`.
   - `STT_FALLBACK_MODEL`: `openai/whisper-1`.
   - `TEXT_CLEANUP_ENABLED`: `true` for final dictation formatting and cleanup.
   - `TEXT_CLEANUP_MODEL`: `openai/gpt-4.1-mini`.
   - `TEXT_CLEANUP_TIMEOUT_SECONDS`: `4` (one bounded attempt; capped at 8 seconds).

5. On the `gateway` service, set a domain such as
   `https://whisper.example.com:8000`. The `:8000` tells Coolify which internal
   container port to proxy; clients still use normal HTTPS port 443.
6. Deploy, then confirm `https://whisper.example.com/health` returns
   `{"status":"ok"}`.

Do not publish port 8000 directly on the VPS. Coolify should be the only public
entry point so it can terminate TLS. The Compose file therefore uses `expose`
instead of `ports`.

## Verify the API

Use a short WAV, MP3, M4A, WebM, OGG, or FLAC recording:

```bash
curl https://whisper.example.com/v1/audio/transcriptions \
  -H "Authorization: Bearer YOUR_CLIENT_API_KEY" \
  -F "file=@sample.wav" \
  -F "model=openai/whisper-large-v3-turbo" \
  -F "language=tl"
```

A successful response identifies the model that actually handled the request:

```json
{"text":"Kumusta, this is a test.","model":"openai/whisper-large-v3-turbo","cleaned":true}
```

For Taglish, first test without the `language` field so Whisper can auto-detect.
If detection is inconsistent, compare explicit `tl` and `en` requests using
your real microphone and vocabulary.

## Client configuration

Android cloud dictation uses a fixed gateway URL and the same anonymous Supabase
session as history sync. The app refreshes expiring tokens automatically and
retries a gateway HTTP 401 once with a refreshed token. Users do not enter a
server URL, API key, or API model. Enable **Use cloud transcription** in Settings;
the Home card will show **Live cloud transcription**. The gateway uses
`scribe_v2_realtime` for streaming; the batch model settings still control fallback
and desktop requests. An expired streaming token is refreshed and retried once.

The default gateway is
`https://uhqgd4qlmep8pnndo8j893bc.187.52.126.169.sslip.io`.
Android builds can override it with the `CLOUD_GATEWAY_URL` Gradle property or
environment variable. This is a public address, not a secret. Existing saved
Android server credentials are no longer used for cloud requests.

The gateway must have `SUPABASE_URL` set to the same project as the clients.
Desktop and Android use anonymous Supabase sessions to authenticate cloud
dictation. Keep `CLIENT_API_KEY` in Coolify only for older installed clients:
gateway startup still requires it during the migration. The upstream provider
key and model settings remain on the server. Redeploy the gateway after changing
its environment variables. Client builds contain no provider secrets.

Desktop cloud transcription is enabled by default for new installations. The
gateway URL is built in and can be overridden for development with the
`CLOUD_GATEWAY_URL` environment variable. Desktop reuses its existing Supabase
session, refreshes expired tokens, and retries once after a gateway HTTP 401.
Users choose only the language and whether an installed local model should be
used when the gateway is unavailable. Older saved URL, API key, and model values
are removed from desktop settings when the upgraded app starts.

### Deploy and test Android realtime dictation

Deploy the changed source, not only new environment variables. The gateway
Docker image now includes `realtime.py`, `check_realtime.py`, and the WebSocket
dependency. Compose explicitly passes `ELEVENLABS_API_KEY` into the container.
Keep the existing OpenRouter, client-key, and Supabase variables for fallback.
Redeploy after making the updated code available to Coolify.

1. Visit `/health/realtime` on the gateway. Expect
   `{"configured":true,"provider":"elevenlabs","model":"scribe_v2_realtime"}`.
   This only checks whether a key is present. For an actual provider handshake,
   open the **gateway container terminal** in Coolify and run:

   ```sh
   python check_realtime.py
   ```

   Expect `{"elevenlabs":"connected","model":"scribe_v2_realtime"}`. This
   opens and closes a provider session without uploading audio or printing keys.
   `provider_access_denied` indicates key permissions or provider access;
   `provider_limit` indicates provider quota/concurrency; `realtime_not_configured`
   means the key was not passed into the running container.

2. In Android Studio, select your connected phone and the **app** configuration,
   then press **Run** to install the updated debug build.
3. Open Settings. Turn **Use cloud transcription** on and **Use local model if
   cloud is unavailable** off for the first test. Select Automatic or English.
4. Return Home and check that its card shows **Live cloud transcription**.
   Speak for 10 seconds and pause between two sentences. Text should update
   before you stop. Press Stop/Confirm; check the complete result and history,
   including the last word. Measure time to first visible text and time from
   Stop to final result separately. Initial token/connection setup and the
   provider's initial audio buffer add delay; sub-second startup is not promised.
5. Repeat with the Dictus keyboard in a text field. Live text remains a preview
   in the keyboard until Confirm inserts the final transcript once. Test a
   60-second recording with several pauses, repeated words, and a last sentence
   immediately followed by Confirm. Cancel should insert and save nothing.
6. In Android Studio Logcat, filter `tag:RealtimeDictation`. Expect `started`,
   `ready connect_ms=...`, `first_partial elapsed_ms=...`, and
   `completed total_ms=...`. Total time includes the recording itself. If
   `using_batch_fallback` appears, that dictation used batch rather than realtime.
   The app also displays a message explaining the switch.
7. Filter `tag:CloudDictation` to inspect batch fallback.
   `auth_ready` measures authentication; `completed` reports gateway request and
   total time. Neither audio, transcript, credentials, nor token values are
   included. `gateway_failed status=401` points to gateway authentication;
   `status=502` points to the upstream transcription provider. `auth_failed`
   means the app could not obtain or refresh its Supabase session.
8. With local fallback still off, disconnect during recording and stop. Realtime
   first attempts the existing full-clip cloud request; if internet is still
   unavailable, the app should show a failure message. Restore the
   connection. To test local fallback separately, first download a local model,
   enable fallback, and repeat offline; a message should explain the switch.

A successful `/health` response only checks that the gateway is running. It does
not prove token acceptance or transcription. Unit tests cover session reuse,
refresh, the one-retry limit, provider failures, and cancellation. The phone
test must confirm the deployed gateway returns text and establish actual speed.
The current automated tests use mocked provider connections, not paid ElevenLabs
sessions. Realtime now runs the same final cleanup policy as batch: live partial
and committed text stays untouched, then one OpenRouter cleanup request runs
after Stop. Successful cleanup formats quantities and context-sensitive speech
slips. Failure, incomplete model output, or timeout returns the original committed
transcript without re-transcribing the audio. Actual paid-model accuracy and
Stop-to-result latency must be checked on the deployed service.

### Dictation cleanup and personal vocabulary

The policy in `apps/server/cleanup.py` targets numbers, currencies, dates in spoken
order, times, percentages, ranges, decimals, fractions, measurements, ordinals,
version/model identifiers, filler removal, accidental repeats, obvious
self-corrections, deletion controls, spoken punctuation/symbols, spelling,
numbered lists, bullets and email layout. It preserves idioms, personal
wording, meaningful hedges, unusual names and language switching. Conservative
local quantity rules enforce `$45`, `£50`, `30%`, `37kg` and `20°` style; contextual
decisions depend on the cleanup model and aren't guaranteed by a prompt alone.

`TEXT_CLEANUP_ENABLED=false` bypasses model cleanup; explicit vocabulary aliases
can still apply locally in the gateway. The default deadline is
four seconds, with no retries; this is a latency budget, not a promised response
time. The gateway logs `cleanup completed duration_ms=...` or
`cleanup fallback=original duration_ms=...` without text or secrets.
The Android cloud insertion path preserves the returned paragraphs, lists,
email signatures and explicit trailing line breaks. Rebuild the Android app for
the new settings and deletion handling. Old clients do not advertise empty-final
support, so a cleanup result that deletes everything falls back to their original
text rather than accidentally triggering a second transcription.

Under Settings, enable cloud transcription to access **Personal vocabulary**.
Enter one preferred spelling per line, or `akme => ACME` for a heard alias, up to
50 entries. Exact aliases and casing still apply if model cleanup times out.
**Learn my vocabulary** retains up to 100 spelling hints and 100 heard-to-written
corrections. It learns from Dictus typing, terms used across three separate
dictations, and close spelling/casing edits to recently inserted or pasted
dictation. Enable the existing **Floating microphone** accessibility service to
observe edits made with other keyboards. The Dictus keyboard can observe its own
eligible editor without that permission. Numbers and semantic rewrites are not
learned as unconditional replacements. Explicit vocabulary wins over learned
aliases. Turn learning off to stop adding/sending learned hints, or clear them
from Settings; either action also invalidates pending edit tracking.

**Use screen text for spelling** is off by default. When enabled, the Dictus
keyboard reads up to 500 characters on each side of the cursor; the accessibility
service can supply visible text from the active app's accessible screen. Combined
context is capped at 4,000 characters and sent only as spelling hints. Passwords,
Android-marked sensitive fields and eligible-editor privacy restrictions are
respected. Apps that hide text from Android's input/accessibility APIs cannot
provide that text. No screenshots, OCR, background-app reading or bypass of
protected views is performed. Context must never be inserted as new content.
Repeated distinctive screen terms can become learned hints when learning is on.
**Filter profanity** is off by default and uses `[redacted]` when enabled.

Manual spellings, learned corrections and learned terms also reach Scribe as up
to 50 prioritized recognition hints. ElevenLabs currently charges a
[20% premium when realtime keyterms are used](https://elevenlabs.io/docs/api-reference/speech-to-text/v-1-speech-to-text-realtime).
Full editor text is held briefly in memory only for edit detection; only bounded
words/aliases/counts are persisted by the learning store. No API key or model
fields are required in these settings, and there are no new environment variables.

Contextual formatting depends on the selected model; local mocked tests do not
establish accuracy for every accent/language. Use the
[deployment and phone acceptance checklist](apps/server/CLEANUP_TESTING.md) to
verify the client requirements against the deployed model.

### Realtime gateway protocol

Native clients connect to `wss://<gateway>/realtime/transcription` with the same
`Authorization: Bearer <Supabase access token>` header as batch requests (the
desktop migration key is also accepted). Tokens are never URL parameters.
Send JSON `{"type":"start","language":"en"}` (omit language for automatic),
wait for `{"type":"ready","model":"scribe_v2_realtime"}`, then send binary
16 kHz mono PCM16 little-endian chunks, normally 200 ms each. The gateway caps a
chunk at one second and session duration at `REALTIME_MAX_SECONDS`.
The gateway emits `partial`, `committed`, `final`, or sanitized `error` events.
Partial text replaces the current phrase; committed text appends a completed
phrase. Send `{"type":"finish"}` to flush the last phrase, or `{"type":"cancel"}`
to discard. The final event contains the full transcript after optional bounded
cleanup, with `cleaned:true` when cleanup succeeded. Optional start field `cleanup`
contains `vocabulary` (spoken/written pairs), `learned_vocabulary` (observed
spoken/written corrections), `learned_terms`, `context`,
`filter_profanity`, and `supports_discard`. The batch equivalent is a JSON string
in multipart field `cleanup_options`. Options are bounded and validated before
contacting providers. Clients setting `supports_discard:true` must accept
`{"type":"final","text":"","cleaned":true,"discarded":true}` as a successful
deletion and insert nothing, without retrying. Old clients omit this capability.
Provider credentials
and transcripts are excluded from timing logs.

Android bounds its outbound audio queue. If setup, streaming, or finalization
fails, the full captured clip is sent through the existing batch route on Stop.
It does not restart a broken stream midway and risk duplicating or losing text.
If batch also fails, the existing local-fallback setting applies. Desktop has
not been migrated to realtime in this phase.

## Supabase database

The hosted database schema is tracked in `supabase/migrations`. Local Room and
SQLite history remain available for offline use; Supabase provides the shared
account and text-history layer. The schema:

- stores profiles and transcription text metadata, but never raw audio;
- enables RLS on every exposed table;
- grants Data API access only to authenticated users; and
- restricts select, insert, update, and delete operations to rows owned by the
  current `auth.uid()`.

The gateway verifies Supabase JWTs against the project's public ES256 JWKS.
The legacy `CLIENT_API_KEY` remains enabled only so existing clients can be
migrated without downtime. Android, Windows, macOS, and Linux keep their local
history and mirror completed entries to Supabase in the background. Failed
uploads are retried from local history the next time the client starts.

Before using a client build, enable **Anonymous Sign-Ins** under Supabase
**Authentication > Providers**. No login or signup screen is shown: Supabase
creates a private user ID for the installation, and the existing RLS policies
restrict every row to that ID. Client builds use only the project URL and
publishable key; a Supabase secret or service-role key must never be shipped in
a desktop or Android build.

The repository defaults to the OpenWhisperFlow Supabase project. Build-time
`SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` values can override it for another
environment.

## Build the clients

Android requires JDK 17, Android SDK 35, NDK `27.2.12479018`, and CMake `3.22.1`:

```powershell
cd apps/android
./gradlew.bat testDebugUnitTest :app:assembleDebug
```

The debug APK is written to
`apps/android/app/build/outputs/apk/debug/app-debug.apk`. Install it, enable
OpenWhisperFlow under Android's physical keyboard/input-method settings, then
select it as the current keyboard. To keep Samsung Keyboard instead, open
**OpenWhisperFlow → Settings → Floating microphone**, accept the focused-field
access disclosure, enable the service in Android Accessibility settings, and
leave Samsung Keyboard selected.

Desktop requires Bun, Rust, and the native prerequisites documented by Tauri:

```bash
cd apps/desktop
bun install
bun run build
bun run tauri build
```

On first launch, grant microphone and input/accessibility permissions. Cloud
dictation works without entering a server URL, API key, or model. You can install
a local model separately for offline use.

Every GitHub Actions run also builds an installable Windows `.msi` and `.exe`.
Open the repository's **Actions** run, then download the
`openwhisperflow-windows-installers` artifact. The Android debug APK is published
as `openwhisperflow-android-debug` in the same run.

## VPS sizing

The VPS runs the gateway; transcription happens on ElevenLabs or OpenRouter.
It does not need a GPU or enough RAM for a local Whisper model. Size the VPS for
Coolify, Docker, the operating system, concurrent streams, and batch uploads.

## Security

- Never put `OPENROUTER_API_KEY` or `ELEVENLABS_API_KEY` in a client build; only the
  Supabase publishable key and user session belong on devices.
- Never put a Supabase secret or service-role key in a client build.
- The gateway does not log audio or transcription text.
- Rotate the temporary `CLIENT_API_KEY` after all installed clients use
  Supabase Auth. Rotate any provider key separately if it is exposed.
- Add proxy rate limits before sharing the endpoint with multiple users.

See `THIRD_PARTY_NOTICES.md` for upstream projects and retained licenses.
