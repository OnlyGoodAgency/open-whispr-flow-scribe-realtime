# Dictation cleanup: deployment and acceptance

Live Scribe partials and committed phrases remain unchanged while recording.
After Stop, the complete transcript gets one cleanup request through the existing
OpenRouter key. The final event replaces the live preview; the client inserts it
once. If cleanup fails, the original committed transcript is still returned.

## Deploy

From the repository root, review and commit the changes before pushing main to
the separate realtime repository. Suggested commit name:
`feat: complete dictation cleanup and automatic vocabulary learning`.

In Coolify use:

```text
TEXT_CLEANUP_ENABLED=true
TEXT_CLEANUP_MODEL=openai/gpt-4.1-mini
TEXT_CLEANUP_TIMEOUT_SECONDS=4
```

The timeout variable is optional; Compose defaults it to four seconds. Keep the
existing API keys, Supabase URL, domain and realtime variables. Reload the
updated Compose file from Git and redeploy the gateway; changing variables alone
does not install the new source. `cleanup.py` must be present in the Docker image.

In Android Studio sync Gradle and Run the app on the phone. This rebuild includes
automatic vocabulary learning and intentional deletion handling. Build an APK again
after testing to share the updated client. Existing APKs get most final formatting
from the updated server, but require a rebuild for the new settings/deletions.

The cloud path now preserves final formatting without adding punctuation to an
email signature. Offline fallback keeps its existing local processing; it does
not offer the contextual cloud cleanup rules.

## Configure vocabulary and voice

In Settings enable cloud transcription, then open **Personal vocabulary**. Add
one term per line, or an alias and exact spelling:

```text
ClickUp
akme => ACME
patell => Patel
```

Save supports up to 50 entries, each spelling up to 80 characters. Invalid or
duplicate aliases must be corrected before Save becomes available. Matching
literal aliases/casing are enforced even if the model times out.

**Learn my vocabulary** starts enabled. It retains up to 100 spelling hints and
100 heard-to-written aliases. Terms become hints after use in three separate
dictations; repeating a term three times in one recording counts once. Dictus
typing continues to contribute hints. Close spelling/casing edits to recently
inserted or pasted dictations create learned aliases. Manual entries always win.
Turn learning off to stop adding/sending hints; **Clear learned dictation words**
removes hints, aliases and repetition counts, and cancels pending edit learning.

The Dictus keyboard tracks eligible-editor edits directly. For another keyboard
or text outside the field, enable the existing **Floating microphone** service in
Settings and Android's accessibility settings. It observes text-change events
for recent dictation only. Paste detection recognizes the last output or explicitly
copied history item for five
minutes; spelling corrections are tracked for two minutes after insertion. Short
multiword spelling changes are supported; semantic rewrites, quantity edits and
contextual homophones are not memorized as permanent replacements.

**Use screen text for spelling** starts disabled. If enabled, up to 500 characters
before/after the cursor and, with accessibility enabled, visible text in the
active app are combined into at most 4,000 characters sent as cloud hints.
Password/Android-marked sensitive fields and editor privacy restrictions are
excluded. There are no screenshots, OCR or background-app reads. A protected app
that withholds text cannot supply screen/edit hints; ordinary dictation still
works. Full field/screen prose is not saved by the learning store. Distinctive
screen words seen across three dictations can become learned hints. Test the same
uncommon name with context on/off; the rest of the screen must not be copied.

Up to 50 manual/learned spellings are also sent to Scribe recognition, with manual
terms first. ElevenLabs applies a
[20% realtime keyterms premium](https://elevenlabs.io/docs/api-reference/speech-to-text/v-1-speech-to-text-realtime)
to sessions using these hints. Empty vocabulary sends no keyterms.

**Filter profanity** starts disabled. When enabled, the model uses `[redacted]`
for profanity while preserving the surrounding words.

## Test on a phone

Enable cloud transcription. Dictate each example as a separate recording. First
check that text still appears while speaking, then press Stop and check the final
inserted result. Periods at the end are expected; compare formatting and meaning.

| Say | Expected final text/behavior |
| --- | --- |
| I paid forty-five dollars. | I paid $45. |
| Fifty quid. | £50. |
| Thirty-seven kilos. | 37kg. |
| Thirty percent. | 30%. |
| Let's meet at five thirty PM. | Let's meet at 5:30pm. |
| Half past nine. | 9:30. No invented AM/PM. |
| March third. | March 3. |
| The third of March. | 3 March. |
| Friday the fourteenth of October. | Friday, 14 October. No invented year. |
| The range is five to ten. | The range is 5–10. |
| Three point five and two thirds. | 3.5 and 2/3. |
| Six feet two inches. | 6'2". |
| Twenty degrees. | 20°. |
| Version two point one, GPT four and iPhone fifteen. | v2.1, GPT-4 and iPhone 15. |
| This is my twenty-first order. | This is my 21st order. |
| One of a kind, a thousand times better, nine to five. | Keep those idioms as words. |
| Let's meet at five, actually six PM. | Let's meet at 6pm. |
| Um, the the report is ready. | The report is ready. |
| Right, it works like a charm. I mean, the real issue is cost. | Preserve the meaningful right, like and I mean. |
| I think it's probably roughly forty-five dollars. | Keep I think, probably and roughly; format $45. |
| Reference zero zero seven two five. | Retain all five digits, including the two leading zeros. |
| I had had enough. It was very, very good. | Keep grammatical repetition and deliberate emphasis. |
| A client name or mixed-language sentence you actually use. | Keep names, personal style, contractions and foreign words. |
| Tasks. First send the report. Second pay forty-five dollars. Third call Patel. | A three-item numbered list; second item uses $45. |
| Shopping list. Apples, milk, bread. | Shopping list: followed by three bullet lines. |
| I bought apples, milk and bread. | A normal sentence can remain a sentence; no forced bullets. |
| Hi Alex. Can we meet at five thirty PM? Thanks, Patel. | Greeting on its own line, body with 5:30pm, sign-off/name at the bottom. No invented subject/signature. |
| Send the report. New paragraph. Then call Patel. | Two paragraphs; command words disappear. |
| Can you send it question mark new line Thanks comma Alex | Question mark and line break; punctuation controls disappear. |
| Meet at five PM. Delete that last sentence. | Nothing is inserted or saved; no batch retry. Requires updated APK. |
| Send it today, scratch that, send it tomorrow. | Only the intended tomorrow clause remains. |
| Email john at acme dot com. | john@acme.com as plain text. |
| The code is alpha underscore beta slash two. | alpha_beta/2. |
| My name is P A T E L. A P I. All caps urgent. | Patel, API and URGENT. |
| Send it to akme through click up. | ACME and ClickUp with the sample vocabulary above. |
| A sentence with swearing, then repeat with Filter profanity enabled. | Preserved when off; [redacted] when on. |

Also try several paragraphs, a 60-second recording and cancellation. Each
completed dictation should appear once in the target field/history, with no
missing final words. Cancel should insert/save nothing and should not call cleanup.

Record both time to the first visible text and time from Stop to final output.
`RealtimeDictation completed total_ms` includes speaking time; it is not just
cleanup latency. Coolify runtime logs show `cleanup completed duration_ms` for
successful calls, or `cleanup fallback=original duration_ms` for failure/timeouts.
If fallback appears, check OpenRouter credits/access and the selected model.
Longer transcripts may exceed the short budget; keep their original text rather
than waiting indefinitely. No raw text, audio or keys are included in timing logs.

## Verification scope

Local tests cover deterministic quantities, exact vocabulary, bounded options,
structured list/email preservation, unchanged live events, one final cleanup call,
intentional empty finals, cancellation, provider disconnects, HTTP failures,
truncated/invalid responses and bounded timeout fallback. Provider transport is
mocked: these tests do not prove paid-model accuracy for dates, grammar,
self-corrections or every dialect. Complete the phone checklist after redeploying.
Text-only cleanup cannot recover digits/words already omitted by speech recognition
or access pronunciation/pause cues that did not reach the transcript.

## Check the deployed model

Inside Coolify's **gateway container terminal**, after redeploying:

```sh
python check_cleanup.py --case numbered-list
python check_cleanup.py --case email
python check_cleanup.py --case deletion
```

Each command makes one billed OpenRouter request using the existing environment
key. No real recordings or user text are used. `PASS` means the selected synthetic
example met the critical formatting checks within the configured deadline.
`cleaned=False` indicates timeout/provider fallback. Review `FAIL` results before
sharing the APK; passing these examples is not a guarantee for every recording.
Run `python check_cleanup.py --list` to print every input and expected result
without making API calls. Run `python check_cleanup.py --category vocabulary` to
check one section, or `python check_cleanup.py --report /tmp/cleanup-results.json`
for the full spec sample set and a JSON report. Each selected example makes one
billed request. The runner checks exact casing as well as critical formatting.

The eight sections in `cleanup_cases.py` map to every text-cleanup bullet in the
client's spec. This is an acceptance suite to run against the deployed model;
having an expected example in the suite does not mean the model has passed it.
Review both PASS checks and actual outputs for voice/content preservation.

## Test learning integration on the phone

1. Enable **Learn my vocabulary** and clear previously learned words. Remove any
   manual ACME entry for this learning test. Use an ordinary notes field.
2. Dictate or paste a fresh result containing `akme`. Edit only that word to
   `ACME` and leave it untouched for at least 1.2 seconds. Dictate it again: the
   learned alias should produce `ACME`. Repeat using your usual keyboard with
   Floating microphone accessibility enabled. Try changing `Acme` to `ACME` to
   check that later casing edits update earlier learned aliases.
3. Use a distinctive term such as `WisprFlow` across three separate recordings.
   On the fourth recording it should be available as a spelling/recognition hint.
   Repeat it three times in one recording to confirm that does not teach it early.
4. Enable **Use screen text for spelling** and show `Patel` in the active app,
   outside the dictation field. Dictate the name: cleanup can use its spelling but
   must not copy other screen text. Repeat three times to check screen learning.
5. Turn learning off and repeat an edit. Stored learned words must not be sent
   or updated. Clear learning, turn it on again, and verify old hints are gone.
   Manual dictionary entries must still win over conflicting learned hints.
6. Try a password/private field and a field in a different app. Passwords must
   contribute no learning/context. Edits outside the recent dictation span and
   edits after two minutes must not teach replacements. Changing a price must
   not teach that quantity as vocabulary.

Android's accessibility/input APIs govern which editor and screen text is exposed.
If an app hides those views, the app cannot learn edits from that screen. Test the
client's actual target apps before claiming coverage for their daily workflow.

All changes still need Git push, gateway redeployment, and rebuilding/installing
the Android app. No new environment variables or additional API keys are needed.
