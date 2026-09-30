//! One recording's visible text, used only as a temporary spelling hint.

use once_cell::sync::Lazy;
use std::collections::HashMap;
use std::sync::{mpsc, Mutex};
use std::time::Duration;

static PENDING: Lazy<Mutex<HashMap<String, mpsc::Receiver<String>>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));

pub fn begin_capture(binding_id: &str) {
    #[cfg(windows)]
    {
        use windows::Win32::UI::WindowsAndMessaging::GetForegroundWindow;

        // Save the target window before the recording overlay can appear.
        let hwnd = unsafe { GetForegroundWindow().0 as usize };
        if hwnd == 0 {
            return;
        }
        let (sender, receiver) = mpsc::channel();
        let mut pending = PENDING.lock().unwrap();
        pending.clear();
        pending.insert(binding_id.to_string(), receiver);
        std::thread::spawn(move || {
            let _ = sender.send(windows_capture::read_window(hwnd));
        });
    }
    #[cfg(not(windows))]
    let _ = binding_id;
}

pub fn discard(binding_id: &str) {
    PENDING.lock().unwrap().remove(binding_id);
}

pub fn discard_all() {
    PENDING.lock().unwrap().clear();
}

pub async fn take(binding_id: &str) -> String {
    let receiver = PENDING.lock().unwrap().remove(binding_id);
    let Some(receiver) = receiver else {
        return String::new();
    };
    tauri::async_runtime::spawn_blocking(move || {
        receiver
            .recv_timeout(Duration::from_millis(700))
            .unwrap_or_default()
    })
    .await
    .unwrap_or_default()
}

#[cfg(windows)]
mod windows_capture {
    use std::collections::{HashSet, VecDeque};
    use windows::Win32::Foundation::HWND;
    use windows::Win32::System::Com::{
        CoCreateInstance, CoInitializeEx, CoUninitialize, CLSCTX_INPROC_SERVER,
        COINIT_MULTITHREADED,
    };
    use windows::Win32::UI::Accessibility::{
        CUIAutomation, IUIAutomation, IUIAutomationElement, IUIAutomationTextPattern,
        IUIAutomationValuePattern, UIA_TextPatternId, UIA_ValuePatternId,
    };

    pub(super) fn read_window(hwnd: usize) -> String {
        if unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) }.is_err() {
            return String::new();
        }
        let result = (|| {
            let automation: IUIAutomation =
                unsafe { CoCreateInstance(&CUIAutomation, None, CLSCTX_INPROC_SERVER) }.ok()?;
            let window = unsafe { automation.ElementFromHandle(HWND(hwnd as *mut _)) }.ok()?;
            if unsafe { window.CurrentProcessId().ok()? } == std::process::id() as i32 {
                return None;
            }
            if unsafe {
                automation
                    .GetFocusedElement()
                    .ok()?
                    .CurrentIsPassword()
                    .ok()?
                    .as_bool()
            } {
                return None;
            }
            Some(collect_visible_text(&automation, window))
        })()
        .unwrap_or_default();
        unsafe { CoUninitialize() };
        result
    }

    fn collect_visible_text(automation: &IUIAutomation, root: IUIAutomationElement) -> String {
        let Ok(walker) = (unsafe { automation.ControlViewWalker() }) else {
            return String::new();
        };
        let mut queue = VecDeque::from([root]);
        let mut seen = HashSet::new();
        let mut result = String::new();
        let mut visited = 0;
        while let Some(element) = queue.pop_front() {
            visited += 1;
            if visited > 150 || result.len() >= 4_000 {
                break;
            }
            if unsafe {
                element
                    .CurrentIsOffscreen()
                    .ok()
                    .is_some_and(|v| v.as_bool())
            } || unsafe {
                element
                    .CurrentIsPassword()
                    .ok()
                    .is_some_and(|v| v.as_bool())
            } {
                continue;
            }
            if let Ok(name) = unsafe { element.CurrentName() } {
                append(&mut result, &mut seen, &name.to_string());
            }
            let text = if let Ok(pattern) = unsafe {
                element.GetCurrentPatternAs::<IUIAutomationTextPattern>(UIA_TextPatternId)
            } {
                let ranges = unsafe { pattern.GetVisibleRanges().ok() };
                ranges.map(|ranges| {
                    let count = unsafe { ranges.Length().unwrap_or(0) }.clamp(0, 4);
                    (0..count)
                        .filter_map(|index| unsafe { ranges.GetElement(index).ok() })
                        .filter_map(|range| unsafe { range.GetText(1_500).ok() })
                        .map(|text| text.to_string())
                        .collect::<Vec<_>>()
                        .join("\n")
                })
            } else if let Ok(value) = unsafe {
                element.GetCurrentPatternAs::<IUIAutomationValuePattern>(UIA_ValuePatternId)
            } {
                unsafe { value.CurrentValue().ok() }.map(|value| value.to_string())
            } else {
                None
            };
            if let Some(text) = text {
                append(&mut result, &mut seen, &text);
            }
            if let Ok(child) = unsafe { walker.GetFirstChildElement(&element) } {
                queue.push_back(child.clone());
                let mut sibling = child;
                while queue.len() + visited < 150 {
                    let Ok(next) = (unsafe { walker.GetNextSiblingElement(&sibling) }) else {
                        break;
                    };
                    queue.push_back(next.clone());
                    sibling = next;
                }
            }
        }
        result
    }

    fn append(result: &mut String, seen: &mut HashSet<String>, raw: &str) {
        let text = raw.trim();
        if text.is_empty() {
            return;
        }
        let key = text.to_lowercase();
        if !seen.insert(key) {
            return;
        }
        if result.len() >= 3_999 {
            return;
        }
        let normalized = text.replace("\r\n", "\n").replace('\r', "\n");
        for ch in normalized.chars() {
            if ch.is_control() && ch != '\n' {
                continue;
            }
            if result.len() + ch.len_utf8() >= 4_000 {
                break;
            }
            result.push(ch);
        }
        result.push('\n');
    }
}
