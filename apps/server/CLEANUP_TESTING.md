# Dictation cleanup: deployment and acceptance

Live Scribe partials and committed phrases remain unchanged while recording.
After Stop, the complete transcript gets one cleanup request through the existing
OpenRouter key. The final event replaces the live preview; the client inserts it
once. If cleanup fails, the original committed transcript is still returned.

## Deploy

From the repository root, review and commit the changes before pushing main to
the separate realtime repository. Suggested commit name:
`feat: add structured dictation cleanup and personal vocabulary`.

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
personal vocabulary settings and intentional deletion handling. Build an APK again
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

**Learn words I type** starts enabled. It retains up to 100 spelling hints after
repeated typing or explicit correction rejection using the Dictus keyboard.
These are hints rather than unconditional fuzzy replacements. Try typing a name
twice with a space after each, then dictate it. It does not observe another
keyboard's edits or learn directly from repeated speech. Turn it off to stop
adding/sending these hints; **Clear learned dictation words** removes them.

**Use nearby text for spelling** starts disabled. If you turn it on, up to 500
characters before and after the cursor in an eligible active field are sent as
cloud spelling hints when dictating through the Dictus keyboard. Password/private
fields are excluded. No entire-screen capture is used, and the context must not
be copied into the output. Test the same uncommon name with context on/off.

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
Run `python check_cleanup.py` for the full 11-example set.

All changes still need Git push, gateway redeployment, and rebuilding/installing
the Android app. No new environment variables or additional API keys are needed.
