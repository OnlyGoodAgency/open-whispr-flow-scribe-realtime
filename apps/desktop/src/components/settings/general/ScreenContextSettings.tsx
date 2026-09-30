import React, { useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { type } from "@tauri-apps/plugin-os";
import { toast } from "sonner";
import { useSettings } from "../../../hooks/useSettings";
import { ToggleSwitch } from "../../ui/ToggleSwitch";

export const ScreenContextSettings: React.FC = () => {
  const { settings, refreshSettings } = useSettings();
  const [saving, setSaving] = useState(false);
  if (type() !== "windows") return null;

  const change = async (enabled: boolean) => {
    setSaving(true);
    try {
      await invoke("change_screen_context_enabled_setting", { enabled });
      await refreshSettings();
    } catch {
      toast.error("Could not update screen context setting");
    } finally {
      setSaving(false);
    }
  };

  return (
    <ToggleSwitch
      checked={settings?.screen_context_enabled ?? false}
      onChange={(enabled) => void change(enabled)}
      isUpdating={saving}
      label="Use text from active window"
      description="Send up to 4,000 characters of visible text to your transcription server as a temporary spelling hint for each dictation. Nothing from the window is saved to vocabulary."
      grouped
    />
  );
};
