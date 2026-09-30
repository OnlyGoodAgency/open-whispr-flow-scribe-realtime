# Changelog

This log tracks changes to the OpenWhisperFlow fork, not the upstream Dictus projects. A commit date records source history; it does not prove that the gateway was deployed or that a new APK or desktop installer was distributed. Keep local work under **Unreleased** until it is committed, deployed where needed, and tested in the released client.

## Unreleased — local changes as of 2026-09-30

### Desktop

- Replace the user-entered server URL, API key, and model fields with built-in gateway configuration and Supabase session authentication. Cloud batch dictation is enabled by default for new installations, with an optional installed-model fallback.
- Add Personal Vocabulary with separate **What you say** and **How it should appear** fields, learned spelling corrections from recent edits, and optional active-window text as a spelling hint on Windows.
- Add a cloud **Filter profanity** setting, off by default, that sends the choice to the gateway for each dictation.
- Update visible desktop branding to OpenWhisperFlow. These desktop changes still require a reviewed commit, release build, and installed-app verification.

### Android

- Replace the manual vocabulary text-entry dialog with **What you say** and **How it should appear** fields plus an entry list. This local UI change still requires a reviewed commit and a new APK for distribution.

### Documentation

- Add [SOP.md](SOP.md) for configuration, testing, release, troubleshooting, and rollback. The documentation does not itself deploy or distribute an app.

### Build fixes

- Handle a trailing sentence period after an email address when lowercasing its domain, so Personal Vocabulary cannot change `john@acme.com.` back to `john@ACME.com.`.
- Update Android CI to the SDK setup action that no longer requests Google's removed `tools` package.
- Switch Windows CI native builds to Ninja to address the observed ggml Vulkan shader helper install/configure race; confirm in the next Actions run.

## 2026-09-30 — committed source changes

- `796ce2c` — Add a final server formatting rule for the work idiom “nine to five” and lowercase email domains after Personal Vocabulary has been applied. Literal numeric changes stay numeric; ordinary `ACME` retains its configured casing. The user reported successful manual testing, but automated tests for this latest fix were not run in the current environment because `pytest` was unavailable.
- `6d1dfb6` — Expand cleanup instructions for idioms, literal ranges, spoken symbols, and email addresses.
- `0add04f` — Correct dictated email greeting and sign-off layout.
- `513889d` — Update Android branding and allow long dictation results to scroll.
- `0e250c3` — Format spoken shopping requests as bullet lists while retaining spoken articles and quantities.

## 2026-09-26 — foundation of this fork

- `16620e8` — Expand the shared cleanup policy and add automatic vocabulary learning from appropriate dictation edits and repeated terms.
- `03f7d76` — Add broader cleanup rules and Personal Vocabulary.
- `93bd3a0` — Add bounded final cleanup after transcription, with the original transcript retained when cleanup fails.
- `51339fe` — Add ElevenLabs Scribe v2 Realtime transcription to Android through the gateway, with batch fallback.

## Release verification notes

- Android realtime partials are previews; check the final inserted result after Stop/Confirm. Desktop currently uses batch transcription, so it does not display realtime partials.
- Cloud cleanup is shared by both apps, but app UI and local behavior require separate client builds. Offline transcription does not use the same server cleanup policy.
- Tests and example prompts cover representative cases. They do not guarantee exact formatting for every accent, name, homophone, mixed-language sentence, or dictated structure.
- For the release procedure and minimum acceptance checks, see [SOP.md](SOP.md) and [apps/server/CLEANUP_TESTING.md](apps/server/CLEANUP_TESTING.md).
