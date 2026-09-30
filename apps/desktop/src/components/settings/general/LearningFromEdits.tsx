import React, { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { type } from "@tauri-apps/plugin-os";
import { toast } from "sonner";
import { useSettings } from "../../../hooks/useSettings";
import { Button } from "../../ui/Button";
import { ToggleSwitch } from "../../ui/ToggleSwitch";

type VocabularyEntry = { spoken: string; written: string };

export const LearningFromEdits: React.FC = () => {
  const { settings, refreshSettings } = useSettings();
  const [saving, setSaving] = useState(false);
  useEffect(() => {
    let disposed = false;
    let unlisten: (() => void) | undefined;
    void listen("personal-vocabulary-learned", () => void refreshSettings()).then((stop) => {
      if (disposed) stop();
      else unlisten = stop;
    });
    return () => {
      disposed = true;
      unlisten?.();
    };
  }, [refreshSettings]);
  if (type() !== "windows") return null;
  const learning = settings as (typeof settings & {
    learning_from_edits_enabled?: boolean;
    learned_vocabulary?: VocabularyEntry[];
  }) | null;
  const entries = learning?.learned_vocabulary ?? [];

  const change = async (command: string, args: Record<string, unknown>) => {
    setSaving(true);
    try {
      await invoke(command, args);
      await refreshSettings();
    } catch {
      toast.error("Could not update learned vocabulary");
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
      <ToggleSwitch
        checked={learning?.learning_from_edits_enabled ?? true}
        onChange={(enabled) => void change("change_learning_from_edits_enabled_setting", { enabled })}
        isUpdating={saving}
        label="Learn from edits"
        description="Learn spelling corrections made shortly after desktop dictation is pasted into an editable field. Your personal vocabulary takes priority."
        grouped
      />
      {entries.length > 0 && (
        <div className="px-4 pb-2 space-y-1 text-sm">
          <p className="font-medium">Learned corrections</p>
          {entries.map((entry) => (
            <div key={entry.spoken} className="flex items-center justify-between gap-3">
              <span className="min-w-0 break-words">{entry.spoken} → {entry.written}</span>
              <Button
                variant="ghost"
                size="sm"
                aria-label={`Forget ${entry.spoken}`}
                disabled={saving}
                onClick={() => void change("update_learned_vocabulary", {
                  entries: entries.filter((item) => item.spoken !== entry.spoken),
                })}
              >
                Forget
              </Button>
            </div>
          ))}
        </div>
      )}
    </>
  );
};
