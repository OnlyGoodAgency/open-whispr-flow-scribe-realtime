import React, { useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { toast } from "sonner";
import { useSettings } from "../../../hooks/useSettings";
import { ToggleSwitch } from "../../ui/ToggleSwitch";

export const FilterProfanitySettings: React.FC = () => {
  const { settings, refreshSettings } = useSettings();
  const [saving, setSaving] = useState(false);

  const change = async (enabled: boolean) => {
    setSaving(true);
    try {
      await invoke("change_filter_profanity_setting", { enabled });
      await refreshSettings();
    } catch {
      toast.error("Could not update profanity filter setting");
    } finally {
      setSaving(false);
    }
  };

  return (
    <ToggleSwitch
      checked={(settings as typeof settings & { filter_profanity?: boolean })?.filter_profanity ?? false}
      onChange={(enabled) => void change(enabled)}
      isUpdating={saving}
      label="Filter profanity"
      description="Replace profanities with [redacted] in cloud dictation. Off keeps your spoken words."
      grouped
    />
  );
};
