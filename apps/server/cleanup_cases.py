"""Fixed synthetic acceptance samples, not recordings or claims of model accuracy.

Each rule in the client's spec has a case or a phone integration check documented
in CLEANUP_TESTING.md. Patterns allow inferred punctuation but check exact casing,
digits, line structure, and preservation of meaning. No network calls on import.
"""
from dataclasses import dataclass, field


@dataclass(frozen=True)
class CleanupCase:
    name: str
    category: str
    spoken: str
    expected: str
    patterns: tuple[str, ...]
    options: dict = field(default_factory=dict)


CASES = [
    CleanupCase("digits", "numbers", "Twenty five.", "25.", (r"^25[.!]?\s*$",)),
    CleanupCase("quantities", "numbers", "I paid forty-five dollars for thirty-seven kilos, thirty percent off.", "I paid $45 for 37kg, 30% off.", (r"\$45", r"37kg", r"30%")),
    CleanupCase("quid", "numbers", "Fifty quid.", "£50.", (r"^£50[.!]?\s*$",)),
    CleanupCase("percentage", "numbers", "Thirty percent.", "30%.", (r"^30%[.!]?\s*$",)),
    CleanupCase("time", "numbers", "Let's meet at five thirty PM.", "Let's meet at 5:30pm.", (r"5:30pm", r"^(?!.*five)")),
    CleanupCase("half-past", "numbers", "Half past nine.", "9:30.", (r"^9:30[.!]?\s*$",)),
    CleanupCase("date-order", "numbers", "March third. The third of March.", "March 3. 3 March.", (r"March 3", r"3 March", r"^(?!.*\d{4})")),
    CleanupCase("weekday-date", "numbers", "Friday the fourteenth of October.", "Friday, 14 October.", (r"Friday,? 14 October", r"^(?!.*\d{4})")),
    CleanupCase("range", "numbers", "The range is five to ten.", "The range is 5–10.", (r"5–10",)),
    CleanupCase("decimal-fraction", "numbers", "Three point five and two thirds.", "3.5 and 2/3.", (r"3\.5", r"2/3")),
    CleanupCase("measurements", "numbers", "Ten kilos. Six feet two inches. Twenty degrees.", "10kg. 6'2\". 20°.", (r"10kg", r"6'2\"", r"20°")),
    CleanupCase("models", "numbers", "Version two point one, GPT four and iPhone fifteen.", "v2.1, GPT-4 and iPhone 15.", (r"v2\.1", r"GPT-4", r"iPhone 15")),
    CleanupCase("phone", "numbers", "My phone number is zero seven seven zero zero nine zero zero one two three.", "My phone number is 07700 900123.", (r"07700[ -]?900123", r"^(?!.*\+\d)")),
    CleanupCase("reference", "numbers", "Reference zero zero seven two five. Order one two three four five.", "Reference 00725. Order 12345.", (r"00725", r"12345", r"^(?!.*[0-9]{6})")),
    CleanupCase("ordinal", "numbers", "This is my twenty-first order.", "This is my 21st order.", (r"21st",)),
    CleanupCase("idioms", "numbers", "One of a kind, a thousand times better, nine to five.", "One of a kind, a thousand times better, nine to five.", (r"(?i)one of a kind", r"a thousand times better", r"nine to five", r"^[^0-9]*$")),
    *[CleanupCase("correction-" + name, "corrections", f"Let's meet at five, {cue} six PM.", "Let's meet at 6pm.", (r"meet at 6pm", r"(?i)^(?!.*\b(?:actually|sorry|mean|wait|rather|make|rephrase|five|5)\b)"))
      for name, cue in [("actually", "actually"), ("sorry", "sorry"), ("i-mean", "I mean"), ("no-wait", "no wait"), ("rather", "rather"), ("make-that", "make that"), ("or-rather", "or rather"), ("rephrase", "let me rephrase")]],
    CleanupCase("deletion", "corrections", "Meet at five PM. Delete that last sentence.", "", (r"^$",), {"supports_discard": True}),
    CleanupCase("scratch-that", "corrections", "Send it today, scratch that, send it tomorrow.", "Send it tomorrow.", (r"(?i)^Send it tomorrow[.!]?\s*$",)),
    CleanupCase("stutter", "corrections", "The the report is ready.", "The report is ready.", (r"^The report is ready[.!]?\s*$",)),
    CleanupCase("false-start", "corrections", "We should go to the, let's meet at the station.", "Let's meet at the station.", (r"(?i)^Let's meet at the station[.!]?\s*$",)),
    CleanupCase("fillers", "filler", "Um, uh, er, ah, the report is ready.", "The report is ready.", (r"^The report is ready[.!]?\s*$",)),
    CleanupCase("empty-tics", "filler", "Like, you know, I mean, it's, sort of, kind of, basically ready, right? So yeah.", "It's ready.", (r"(?i)^It's ready[.!]?\s*$",)),
    CleanupCase("meaningful-tics", "filler", "It works like a charm. Right, let's go. I mean, the real issue is cost.", "It works like a charm. Right, let's go. I mean, the real issue is cost.", (r"like a charm", r"Right, let's go", r"I mean, the real issue is cost")),
    CleanupCase("non-speech", "filler", "[cough] The report [laughter] is ready. [inaudible]", "The report is ready.", (r"^The report is ready[.!]?\s*$",)),
    CleanupCase("inferred-punctuation", "punctuation", "the report is ready can you send it to Alex", "The report is ready. Can you send it to Alex?", (r"ready[.!]\s+Can", r"Alex\?\s*$")),
    CleanupCase("spoken-punctuation", "punctuation", "Hello comma Alex period Can you send it question mark new line Thanks comma Patel", "Hello, Alex. Can you send it?\nThanks, Patel", (r"Hello, Alex\.", r"send it\?\nThanks, Patel", r"(?i)^(?!.*\b(?:comma|period|question mark|new line)\b)")),
    CleanupCase("paragraph-command", "punctuation", "Send the report. New paragraph. Then call Patel.", "Send the report.\n\nThen call Patel.", (r"report\.\n\nThen", r"(?i)^(?!.*new paragraph)")),
    CleanupCase("quotes-colon", "punctuation", "He said colon open quote see you soon close quote period", "He said: \"See you soon\".", (r"He said: [\"“][Ss]ee you soon[\"”]\.", r"(?i)^(?!.*\b(?:colon|open quote|close quote|period)\b)")),
    CleanupCase("topic-shift", "punctuation", "The project launches Friday. The budget is forty-five dollars. On a different topic, my holiday starts Monday.", "The project launches Friday. The budget is $45.\n\nOn a different topic, my holiday starts Monday.", (r"\$45", r"\n\nOn a different topic", r"my holiday starts Monday")),
    CleanupCase("numbered-list", "structure", "Tasks. First send the report. Second pay forty-five dollars. Third call Patel.", "Tasks:\n1. Send the report.\n2. Pay $45.\n3. Call Patel.", (r"(?m)^1\. [Ss]end the report", r"(?m)^2\. [Pp]ay \$45", r"(?m)^3\. [Cc]all Patel")),
    CleanupCase("bullets", "structure", "Shopping list. Apples, milk, bread.", "Shopping list:\n- Apples\n- Milk\n- Bread", (r"(?m)^- [Aa]pples", r"(?m)^- [Mm]ilk", r"(?m)^- [Bb]read")),
    CleanupCase("bullet-command", "structure", "Bullet point send the report new line bullet point call Patel", "- Send the report\n- Call Patel", (r"(?m)^- [Ss]end the report", r"(?m)^- [Cc]all Patel", r"(?i)^(?!.*bullet point)")),
    CleanupCase("email", "structure", "Hi Alex. Can we meet at five thirty PM? Thanks, Patel.", "Hi Alex,\n\nCan we meet at 5:30pm?\n\nThanks,\nPatel", (r"^Hi Alex[^\n]*\n", r"5:30pm\?", r"Thanks[^\n]*\nPatel[.!]?\s*$", r"(?i)^(?!.*subject:)")),
    CleanupCase("no-added-structure", "structure", "I bought apples, milk and bread.", "I bought apples, milk and bread.", (r"^I bought apples, milk and bread[.!]?\s*$",)),
    CleanupCase("email-address", "symbols", "Email john at acme dot com.", "Email john@acme.com.", (r"john@acme\.com", r"^(?!.*mailto:)")),
    CleanupCase("symbols", "symbols", "The code is alpha underscore beta slash two. Hashtag launch. Alpha plus beta ampersand gamma. The path is folder backslash file.", "The code is alpha_beta/2. #launch. Alpha+beta&gamma. The path is folder\\file.", (r"alpha_beta/2", r"#launch", r"[Aa]lpha\s*\+\s*beta\s*&\s*gamma", r"folder\\file")),
    CleanupCase("spelling", "symbols", "My name is P-A-T-E-L.", "My name is Patel.", (r"^My name is Patel[.!]?\s*$",)),
    CleanupCase("acronym", "symbols", "A P I.", "API.", (r"^API[.!]?\s*$",)),
    CleanupCase("caps", "symbols", "All caps urgent. Capital monday.", "URGENT. Monday.", (r"URGENT", r"Monday", r"(?i)^(?!.*\b(?:all caps|capital)\b)")),
    CleanupCase("vocabulary", "vocabulary", "Send it to akme through click up.", "Send it to ACME through ClickUp.", (r"ACME", r"ClickUp"), {"vocabulary": [{"spoken": "akme", "written": "ACME"}, {"spoken": "click up", "written": "ClickUp"}]}),
    CleanupCase("dictionary-wins", "vocabulary", "Send it to akme.", "Send it to AcmeCorp.", (r"AcmeCorp", r"^(?!.*ACME)"), {"vocabulary": [{"spoken": "akme", "written": "AcmeCorp"}], "learned_vocabulary": [{"spoken": "akme", "written": "ACME"}]}),
    CleanupCase("learned-correction", "vocabulary", "Send it to akme.", "Send it to ACME.", (r"ACME",), {"learned_vocabulary": [{"spoken": "akme", "written": "ACME"}]}),
    CleanupCase("learned-term", "vocabulary", "Use wispr flow.", "Use WisprFlow.", (r"WisprFlow",), {"learned_terms": ["WisprFlow"]}),
    CleanupCase("screen-context", "vocabulary", "Send it to patell.", "Send it to Patel.", (r"Patel", r"(?i)^(?!.*(?:Project launch|Monday))"), {"context": "Contact: Patel. Project launch Monday."}),
    CleanupCase("unusual-name", "vocabulary", "Send the report to Xochitl.", "Send the report to Xochitl.", (r"Xochitl",)),
    CleanupCase("brand-casing", "vocabulary", "I use iphone, github, api, saas, ios, pdf and clickup.", "I use iPhone, GitHub, API, SaaS, iOS, PDF and ClickUp.", (r"iPhone", r"GitHub", r"API", r"SaaS", r"iOS", r"PDF", r"ClickUp")),
    CleanupCase("homophones", "vocabulary", "Their going to send they're report over their. Its ready. Send it too me to. Your welcome.", "They're going to send their report over there. It's ready. Send it to me too. You're welcome.", (r"They're going", r"their report over there", r"It's ready", r"to me too", r"You're welcome")),
    CleanupCase("casual-voice", "voice", "Yeah, I'm gonna send it when I'm done.", "Yeah, I'm gonna send it when I'm done.", (r"^Yeah, I'm gonna send it when I'm done[.!]?\s*$",)),
    CleanupCase("formal-voice", "voice", "Please provide the report at your earliest convenience.", "Please provide the report at your earliest convenience.", (r"^Please provide the report at your earliest convenience[.!]?\s*$",)),
    CleanupCase("contractions", "voice", "I don't think it's ready, and we won't send it yet.", "I don't think it's ready, and we won't send it yet.", (r"don't", r"it's", r"won't")),
    CleanupCase("swearing", "voice", "That fucking report is late.", "That fucking report is late.", (r"^That fucking report is late[.!]?\s*$",)),
    CleanupCase("filter-profanity", "voice", "That fucking report is late.", "That [redacted] report is late.", (r"^That \[redacted\] report is late[.!]?\s*$",), {"filter_profanity": True}),
    CleanupCase("hedges", "voice", "I think it's probably roughly forty-five dollars.", "I think it's probably roughly $45.", (r"I think", r"it's", r"probably", r"roughly", r"\$45")),
    CleanupCase("grammar-slip", "voice", "The reports is ready.", "The reports are ready.", (r"^The reports are ready[.!]?\s*$",)),
    CleanupCase("dropped-article", "voice", "Please send report to Alex.", "Please send the report to Alex.", (r"^Please send the report to Alex[.!]?\s*$",)),
    CleanupCase("tense-slip", "voice", "Yesterday I send the report to Alex.", "Yesterday I sent the report to Alex.", (r"^Yesterday I sent the report to Alex[.!]?\s*$",)),
    CleanupCase("dialect", "voice", "Y'all ain't seen nothing yet.", "Y'all ain't seen nothing yet.", (r"^Y'all ain't seen nothing yet[.!]?\s*$",)),
    CleanupCase("deliberate-repetition", "voice", "I had had enough. It was very, very good.", "I had had enough. It was very, very good.", (r"had had", r"very,? very good")),
    CleanupCase("no-rewording", "voice", "The red file is on the desk, the blue file is on the shelf, and Alex has the green file.", "The red file is on the desk, the blue file is on the shelf, and Alex has the green file.", (r"red file is on the desk", r"blue file is on the shelf", r"Alex has the green file")),
    CleanupCase("language-switch", "voice", "I think mañana works, pero depende sa schedule natin.", "I think mañana works, pero depende sa schedule natin.", (r"mañana", r"pero depende sa schedule natin", r"I think")),
]
