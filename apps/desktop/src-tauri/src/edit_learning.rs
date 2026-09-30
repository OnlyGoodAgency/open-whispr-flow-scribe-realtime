//! Learn conservative spelling corrections from a recently pasted dictation.
//! Only the alias is persisted; editor contents stay in memory and expire quickly.

use crate::settings::PersonalVocabularyEntry;
use once_cell::sync::Lazy;
use regex::Regex;
use std::sync::atomic::{AtomicU64, Ordering};
use tauri::AppHandle;

static WORDS: Lazy<Regex> = Lazy::new(|| {
    Regex::new(r"(?u)\p{L}[\p{L}\p{M}\p{N}]*(?:[.'’-][\p{L}\p{M}\p{N}]+)*")
        .expect("valid vocabulary word pattern")
});
static WATCH_GENERATION: AtomicU64 = AtomicU64::new(0);

pub fn watch_after_paste(app: AppHandle, pasted: String) {
    let generation = WATCH_GENERATION.fetch_add(1, Ordering::Relaxed) + 1;
    if pasted.is_empty() || pasted.len() > 8_000 {
        return;
    }
    if !crate::settings::get_settings(&app).learning_from_edits_enabled {
        return;
    }
    #[cfg(windows)]
    std::thread::spawn(move || windows_observer::observe(app, pasted, generation));
    #[cfg(not(windows))]
    let _ = (app, pasted, generation);
}

fn correction(original: &str, edited: &str) -> Option<PersonalVocabularyEntry> {
    let before: Vec<_> = WORDS.find_iter(original).collect();
    let after: Vec<_> = WORDS.find_iter(edited).collect();
    let mut first = 0;
    while first < before.len().min(after.len()) && before[first].as_str() == after[first].as_str() {
        first += 1;
    }
    let mut tail = 0;
    while tail < before.len().min(after.len()) - first
        && before[before.len() - 1 - tail].as_str() == after[after.len() - 1 - tail].as_str()
    {
        tail += 1;
    }
    let old = &before[first..before.len() - tail];
    let new = &after[first..after.len() - tail];
    if !(1..=4).contains(&old.len()) || !(1..=4).contains(&new.len()) {
        return None;
    }
    let spoken = &original[old.first()?.start()..old.last()?.end()];
    let written = &edited[new.first()?.start()..new.last()?.end()];
    if spoken == written
        || spoken.chars().any(|c| c.is_ascii_digit())
        || written.chars().any(|c| c.is_ascii_digit())
    {
        return None;
    }
    let a: String = spoken
        .to_lowercase()
        .chars()
        .filter(|c| c.is_alphabetic())
        .collect();
    let b: String = written
        .to_lowercase()
        .chars()
        .filter(|c| c.is_alphabetic())
        .collect();
    if a.len() < 2 || b.len() < 2 || is_common(&a) || is_common(&b) {
        return None;
    }
    if distance(&a, &b) > 1.max(a.chars().count().max(b.chars().count()) / 3) {
        return None;
    }
    let entry = PersonalVocabularyEntry {
        spoken: spoken.to_string(),
        written: written.to_string(),
    };
    crate::settings::validate_learned_vocabulary(std::slice::from_ref(&entry)).ok()?;
    Some(entry)
}

fn is_common(word: &str) -> bool {
    matches!(
        word,
        "a" | "an"
            | "the"
            | "i"
            | "we"
            | "you"
            | "it"
            | "is"
            | "are"
            | "was"
            | "were"
            | "and"
            | "or"
            | "but"
            | "to"
            | "in"
            | "on"
            | "at"
            | "of"
            | "for"
            | "from"
            | "with"
            | "this"
            | "that"
            | "there"
            | "their"
            | "your"
            | "one"
            | "two"
            | "three"
            | "please"
            | "thanks"
            | "hello"
            | "hi"
            | "send"
            | "call"
            | "report"
            | "meet"
    )
}

fn distance(a: &str, b: &str) -> usize {
    let b: Vec<char> = b.chars().collect();
    let mut previous: Vec<usize> = (0..=b.len()).collect();
    for (i, left) in a.chars().enumerate() {
        let mut next = vec![i + 1; b.len() + 1];
        for (j, right) in b.iter().enumerate() {
            next[j + 1] = (next[j] + 1)
                .min(previous[j + 1] + 1)
                .min(previous[j] + usize::from(left != *right));
        }
        previous = next;
    }
    previous[b.len()]
}

#[cfg(windows)]
mod windows_observer {
    use super::{correction, AppHandle, PersonalVocabularyEntry, WATCH_GENERATION};
    use std::sync::atomic::Ordering;
    use std::time::{Duration, Instant};
    use tauri::Emitter;
    use windows::Win32::System::Com::{
        CoCreateInstance, CoInitializeEx, CoUninitialize, CLSCTX_INPROC_SERVER,
        COINIT_MULTITHREADED,
    };
    use windows::Win32::UI::Accessibility::{
        CUIAutomation, IUIAutomation, IUIAutomationElement, IUIAutomationTextPattern,
        IUIAutomationValuePattern, UIA_TextPatternId, UIA_ValuePatternId,
    };

    pub(super) fn observe(app: AppHandle, pasted: String, generation: u64) {
        // UI Automation calls must stay on this COM-initialized worker thread.
        if unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) }.is_err() {
            return;
        }
        if let Ok(automation) = unsafe {
            CoCreateInstance::<_, IUIAutomation>(&CUIAutomation, None, CLSCTX_INPROC_SERVER)
        } {
            watch(&app, &automation, &pasted, generation);
        }
        unsafe { CoUninitialize() };
    }

    fn focused_text(automation: &IUIAutomation) -> Option<(IUIAutomationElement, String)> {
        let element = unsafe { automation.GetFocusedElement().ok()? };
        if unsafe { element.CurrentIsPassword().ok()?.as_bool() }
            || unsafe { element.CurrentProcessId().ok()? } == std::process::id() as i32
        {
            return None;
        }
        let text = if let Ok(value) =
            unsafe { element.GetCurrentPatternAs::<IUIAutomationValuePattern>(UIA_ValuePatternId) }
        {
            unsafe { value.CurrentValue().ok()? }.to_string()
        } else {
            let pattern = unsafe {
                element.GetCurrentPatternAs::<IUIAutomationTextPattern>(UIA_TextPatternId)
            }
            .ok()?;
            let range = unsafe { pattern.DocumentRange().ok()? };
            unsafe { range.GetText(8_001).ok()? }.to_string()
        };
        let text = text.replace("\r\n", "\n");
        (text.len() <= 8_000).then_some((element, text))
    }

    fn watch(app: &AppHandle, automation: &IUIAutomation, pasted: &str, generation: u64) {
        let pasted = pasted.replace("\r\n", "\n");
        let start = Instant::now();
        let (element, initial, at) = loop {
            if WATCH_GENERATION.load(Ordering::Relaxed) != generation
                || start.elapsed() > Duration::from_secs(5)
            {
                return;
            }
            if let Some((element, text)) = focused_text(automation) {
                if let Some(at) = text.find(&pasted) {
                    if text.rfind(&pasted) == Some(at) {
                        break (element, text, at);
                    }
                }
            }
            std::thread::sleep(Duration::from_millis(250));
        };
        let prefix = &initial[..at];
        let suffix = &initial[at + pasted.len()..];
        let mut original = pasted.to_string();
        let mut last_seen = original.clone();
        let mut changed_at = Instant::now();
        while WATCH_GENERATION.load(Ordering::Relaxed) == generation
            && start.elapsed() < Duration::from_secs(120)
        {
            std::thread::sleep(Duration::from_millis(400));
            let Some((focused, full_text)) = focused_text(automation) else {
                break;
            };
            if !unsafe { automation.CompareElements(&element, &focused).ok() }
                .is_some_and(|same| same.as_bool())
                || !full_text.starts_with(prefix)
                || !full_text.ends_with(suffix)
                || full_text.len() < prefix.len() + suffix.len()
            {
                break;
            }
            let edited = &full_text[prefix.len()..full_text.len() - suffix.len()];
            if edited != last_seen {
                last_seen = edited.to_string();
                changed_at = Instant::now();
            } else if edited != original && changed_at.elapsed() >= Duration::from_millis(1_200) {
                if let Some(entry) = correction(&original, edited) {
                    save_alias(app, entry);
                    original = edited.to_string();
                }
            }
        }
    }

    fn save_alias(app: &AppHandle, entry: PersonalVocabularyEntry) {
        let mut settings = crate::settings::get_settings(app);
        if !settings.learning_from_edits_enabled
            || settings
                .personal_vocabulary
                .iter()
                .any(|item| item.spoken.eq_ignore_ascii_case(&entry.spoken))
        {
            return;
        }
        settings
            .learned_vocabulary
            .retain(|item| !item.spoken.eq_ignore_ascii_case(&entry.spoken));
        settings.learned_vocabulary.push(entry);
        if settings.learned_vocabulary.len() > 100 {
            settings.learned_vocabulary.remove(0);
        }
        crate::settings::write_settings(app, settings);
        let _ = app.emit("personal-vocabulary-learned", ());
    }
}

#[cfg(test)]
mod tests {
    use super::correction;

    #[test]
    fn learns_spelling_and_casing_but_not_rewrites() {
        let acme = correction("Send to Acme.", "Send to ACME.").unwrap();
        assert_eq!(
            (acme.spoken.as_str(), acme.written.as_str()),
            ("Acme", "ACME")
        );
        let clickup = correction("Use click up.", "Use ClickUp.").unwrap();
        assert_eq!(
            (clickup.spoken.as_str(), clickup.written.as_str()),
            ("click up", "ClickUp")
        );
        assert!(correction("Meet at five.", "Meet at six.").is_none());
        assert!(correction("It costs $45.", "It costs $65.").is_none());
        assert!(correction("Send the report.", "Delete the report.").is_none());
    }
}
