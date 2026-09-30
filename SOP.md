# OpenWhisperFlow operating procedure

This procedure covers the OpenWhisperFlow fork in this repository: the Android app, the desktop app, and the shared `gateway` deployed through Coolify. It is for development, testing, release, and troubleshooting. The root [README](README.md) explains the architecture; [cleanup acceptance cases](apps/server/CLEANUP_TESTING.md) provide the longer dictation test set.

## 1. Know which part a change affects

| Change | Where it runs | What must be updated |
| --- | --- | --- |
| Transcription model, cleanup prompt, formatting, API behavior | `apps/server` | Push the server change and redeploy the Coolify `gateway` |
| Android interface, dictation controls, local settings or insertion | `apps/android` | Build and install a new Android app/APK |
| Desktop interface, settings, authentication or paste behavior | `apps/desktop` | Build and install a new desktop app |
| Shared server behavior used by both clients | `apps/server` | Redeploy the gateway; an app rebuild is needed only if client code also changed |

Android cloud dictation streams audio to the gateway, which connects to ElevenLabs Scribe v2 Realtime. On a realtime failure, Android can try the gateway's batch endpoint; an installed local model is an optional further fallback. Desktop currently sends completed audio to the gateway's batch endpoint. The gateway uses OpenRouter for batch transcription and final cleanup. Live Android partial text is a preview; assess formatting in the final inserted text after Stop/Confirm. Desktop does not currently stream realtime partial text.

## 2. Configuration and access

In Coolify, the repository root is the base directory and `/docker-compose.yml` is the Compose location. The `gateway` container listens on internal port `8000`; expose it through Coolify's HTTPS domain/proxy rather than publishing port `8000` directly.

Set secrets in Coolify environment variables, never in Git or client source:

| Variable | Purpose |
| --- | --- |
| `OPENROUTER_API_KEY` | Batch speech recognition and final cleanup; required |
| `CLIENT_API_KEY` | Legacy client authentication during migration; currently required for gateway startup, at least 32 characters |
| `SUPABASE_URL` | Enables the current clients' Supabase session authentication; use the same project configured in the clients |
| `ELEVENLABS_API_KEY` | Android realtime transcription |
| `TEXT_CLEANUP_ENABLED` | Set `true` for final cleanup and formatting |
| `TEXT_CLEANUP_MODEL` | Current default: `openai/gpt-4.1-mini` |
| `TEXT_CLEANUP_TIMEOUT_SECONDS` | Current default: `4`; failed or timed-out cleanup returns the original transcript |
| `STT_PRIMARY_MODEL`, `STT_FALLBACK_MODEL` | OpenRouter batch models; defaults are in `docker-compose.yml` |

The Android and desktop clients already have a default gateway URL and do not ask users for a server URL, provider key, or API model. Android builds may override `CLOUD_GATEWAY_URL` and the public Supabase configuration at build time; desktop development may override `CLOUD_GATEWAY_URL` in its environment. If the gateway domain changes, update both client builds or their supported overrides before distributing them. Provider keys stay only on the server.

## 3. Develop and verify locally

1. Check the current branch and working tree before editing. Stage only reviewed files for the intended release; this checkout can contain unrelated local changes.
2. For server changes, run the relevant Python tests from `apps/server` after installing `requirements-dev.txt` in an isolated Python environment. The targeted cleanup test is `python -m pytest tests/test_cleanup.py -q`. The broader acceptance examples are in `cleanup_cases.py`; `python check_cleanup.py --list` prints them without calling a paid model.
3. For Android changes, open `apps/android` in Android Studio, connect a phone, select the `app` configuration, and press **Run**. This installs a debug build for immediate testing. For a command-line debug build, use `./gradlew.bat testDebugUnitTest :app:assembleDebug` from `apps/android`; the APK appears at `app/build/outputs/apk/debug/app-debug.apk`.
4. For desktop changes, work in `apps/desktop`. `bun install`, `bun run build`, and `bun run tauri build` are the documented build commands. Tauri native prerequisites are described in [desktop BUILD.md](apps/desktop/BUILD.md). Use the running development app for quick checks; build an installer for release validation.
5. Check the final text in a real target app on both platforms. A passing mocked test or a healthy `/health` response does not prove provider accuracy, paste behavior, or device performance.

Do not run the full paid-model acceptance suite casually: each selected `check_cleanup.py` case makes a billed OpenRouter request. Use `--list` first, then run only the cases needed for the change.

## 4. Client acceptance checks

Test at least one short dictation and one 60-second dictation on a real phone and desktop. Confirm each completed result is inserted once, the last words remain, and Cancel inserts nothing. Use separate recordings for these examples:

| Say | Check the final inserted text for |
| --- | --- |
| “Twenty five dollars, thirty percent, ten kilos.” | `$25`, `30%`, `10kg` |
| “The third of March. March third.” | `3 March`, then `March 3` |
| “Nine to five was exhausting. Change the value from nine to five.” | Work idiom stays words; the literal change uses digits |
| “The temperature is between five and ten degrees.” | `5–10°` |
| “Email John at ACME dot com. ACME signed it.” | `john@acme.com`, with ordinary `ACME` still uppercase when it is in Personal Vocabulary |
| “Slash API underscore v2 plus test ampersand debug.” | `/API_v2+test&debug` |
| “First buy milk, second buy eggs, third buy bread.” | Three numbered lines |
| “Please buy milk, eggs, bread, and honey.” | Four bullet lines after a spoken lead-in |
| “Hi Alex. Can we meet at five thirty PM? Thanks, Patel.” | Greeting, body, and sign-off on separate email-style lines |
| “Let's meet at five, actually six PM.” | Only the corrected `6pm` time remains |

Also test Personal Vocabulary with an unusual client name, then edit a recent inserted spelling and check whether **Learn my vocabulary** offers the correction on a later dictation. On desktop, **Use text from active window** is available on Windows; on Android, screen hints depend on text exposed by the keyboard or accessibility service. Neither feature can read protected fields. Repeat a sentence with **Filter profanity** off and on; only the enabled result should show `[redacted]`. These tests verify representative behavior, not a guarantee that every name, language, or sentence will be recognized correctly.

## 5. Release sequence

1. Record the change under **Unreleased** in [CHANGELOG.md](CHANGELOG.md), including whether it affects server, Android, or desktop. Confirm tests and manual checks relevant to that change.
2. Review the diff and stage only intended source and documentation. Keep `.env`, secrets, build output, generated caches, and unrelated local work out of the commit.
3. Commit and push the intended change to the repository's `main` branch. Verify the commit appears on GitHub before using it for deployment.
4. If server code, Compose, or Coolify variables changed, redeploy the `gateway` in Coolify from the new commit. Wait for a healthy deployment. `/health` should return `{"status":"ok"}`. `/health/realtime` reports whether the ElevenLabs key is configured; it does not prove a provider connection. Confirm with a real Android dictation or the container's `python check_realtime.py` when needed.
5. If Android code changed, use Android Studio **Run** for local validation and build the final APK only when ready to distribute it. If desktop code changed, build and install the desktop release artifact. The root README also documents GitHub Actions installer artifacts.
6. Test the installed release on both devices against the same gateway. Confirm final text, timing, vocabulary, profanity setting, fallback behavior, and the exact release version being shared.
7. Once actually released, move the entries from **Unreleased** to a dated section in the changelog. Record the deployed commit and distributed artifact versions; a successful Git push alone is not a production release.

Do not perform steps 3–7 for a documentation-only review unless a release has been authorized.

## 6. Troubleshooting and rollback

| Symptom | Check |
| --- | --- |
| `/health` works but no transcription | Authentication, provider credentials, Coolify runtime logs, then a real audio request; `/health` only checks process health |
| Android partial text never appears | `RealtimeDictation` in Android Studio Logcat; `ready`, `first_partial`, provider errors, and `using_batch_fallback` identify the stage |
| Final text is slow or unformatted | Coolify `cleanup completed` or `cleanup fallback=original` timing; OpenRouter access, selected cleanup model, and timeout |
| Desktop cloud request fails | Gateway reachability, Supabase session refresh, and HTTP status in app/gateway logs; an installed local model is required for offline fallback |
| Vocabulary casing seems wrong | Manual entries take precedence; email domains are lowercased after vocabulary, while ordinary words retain the configured spelling |
| Build fails on Windows | Check the native Rust/CMake/MSVC prerequisites in [desktop BUILD.md](apps/desktop/BUILD.md); CMake policy warnings are not by themselves a compiler failure |

For a bad gateway release, redeploy the last known good server commit in Coolify and verify `/health` plus a real dictation. For a bad client release, reinstall the last known good APK or desktop installer. Preserve release artifacts and the corresponding commit IDs so rollback is possible. Do not remove client data or reset the repository to roll back a deployment.

No logs or reports should include raw audio, transcripts, Supabase tokens, or provider keys. Share only status codes, timings, and redacted diagnostics.
