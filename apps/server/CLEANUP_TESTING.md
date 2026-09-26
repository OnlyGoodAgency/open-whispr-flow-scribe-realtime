# Phase-one cleanup: deployment and acceptance

Live Scribe partials and committed phrases remain unchanged while recording.
After Stop, the complete transcript gets one cleanup request through the existing
OpenRouter key. The final event replaces the live preview; the client inserts it
once. If cleanup fails, the original committed transcript is still returned.

## Deploy

From the repository root, review and commit the changes before pushing main to
the separate realtime repository. Suggested commit name:
`feat: add bounded final dictation cleanup`.
This change also includes the previously updated Android gateway URL.

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
paragraph preservation and the updated HTTPS gateway address. Build an APK again
after testing to share the updated client.

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

Local tests cover deterministic quantities, preservation examples, unchanged live
events, one final cleanup call, cancellation, provider disconnects, HTTP failures,
truncated/invalid responses and bounded timeout fallback. Provider transport is
mocked: these tests do not prove paid-model accuracy for dates, grammar,
self-corrections or every dialect. Complete the phone checklist after redeploying.

Deletion commands, spoken punctuation commands, automatic list/email construction,
custom vocabulary, learning from edits and screen context are later phases.
