import React, { useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { toast } from "sonner";
import { useSettings } from "../../../hooks/useSettings";
import { Button } from "../../ui/Button";
import { Input } from "../../ui/Input";
import { SettingContainer } from "../../ui/SettingContainer";

type VocabularyEntry = { spoken: string; written: string };

export const PersonalVocabulary: React.FC = () => {
  const { settings, refreshSettings } = useSettings();
  const entries =
    (settings as (typeof settings & { personal_vocabulary?: VocabularyEntry[] }) | null)
      ?.personal_vocabulary ?? [];
  const [spoken, setSpoken] = useState("");
  const [written, setWritten] = useState("");
  const [saving, setSaving] = useState(false);

  const save = async (updated: VocabularyEntry[]) => {
    setSaving(true);
    try {
      await invoke("update_personal_vocabulary", { entries: updated });
      await refreshSettings();
      return true;
    } catch {
      toast.error("Could not save personal vocabulary");
      return false;
    } finally {
      setSaving(false);
    }
  };

  const add = async () => {
    const entry = { spoken: spoken.trim(), written: written.trim() };
    if (!entry.spoken || !entry.written) return;
    if (Array.from(entry.spoken).length > 80 || Array.from(entry.written).length > 80) {
      toast.error("Each term must be 80 characters or fewer");
      return;
    }
    if (entries.length >= 50) {
      toast.error("Personal vocabulary is limited to 50 entries");
      return;
    }
    if (entries.some((item) => item.spoken.toLowerCase() === entry.spoken.toLowerCase())) {
      toast.error("That spoken term is already in your vocabulary");
      return;
    }
    if (await save([...entries, entry])) {
      setSpoken("");
      setWritten("");
    }
  };

  return (
    <SettingContainer
      title="Personal vocabulary"
      description="Tell cloud transcription how to spell your names and terms. Example: akme → ACME."
      descriptionMode="inline"
      grouped
      layout="stacked"
    >
      <div className="flex flex-wrap items-center gap-2">
        <Input
          aria-label="Spoken term"
          placeholder="What you say"
          value={spoken}
          onChange={(event) => setSpoken(event.target.value)}
          maxLength={80}
          disabled={saving}
          className="min-w-32 flex-1"
        />
        <span aria-hidden="true">→</span>
        <Input
          aria-label="Written term"
          placeholder="How it should appear"
          value={written}
          onChange={(event) => setWritten(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") {
              event.preventDefault();
              void add();
            }
          }}
          maxLength={80}
          disabled={saving}
          className="min-w-32 flex-1"
        />
        <Button onClick={() => void add()} disabled={saving || !spoken.trim() || !written.trim()}>
          Add
        </Button>
      </div>
      {entries.length > 0 && (
        <div className="mt-3 space-y-1">
          {entries.map((entry) => (
            <div key={entry.spoken} className="flex items-center justify-between gap-3 text-sm">
              <span className="min-w-0 break-words">{entry.spoken} → {entry.written}</span>
              <Button
                variant="ghost"
                size="sm"
                aria-label={`Remove ${entry.spoken}`}
                disabled={saving}
                onClick={() => void save(entries.filter((item) => item.spoken !== entry.spoken))}
              >
                Remove
              </Button>
            </div>
          ))}
        </div>
      )}
    </SettingContainer>
  );
};
