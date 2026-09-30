import React from "react";
import { useSettings } from "../../../hooks/useSettings";
import { SettingsGroup } from "../../ui/SettingsGroup";
import { ToggleSwitch } from "../../ui/ToggleSwitch";
import { LanguageSelector } from "../LanguageSelector";
import { PersonalVocabulary } from "./PersonalVocabulary";
import { LearningFromEdits } from "./LearningFromEdits";
import { ScreenContextSettings } from "./ScreenContextSettings";
import { FilterProfanitySettings } from "./FilterProfanitySettings";

export const SharedServerSettings: React.FC = () => {
  const { getSetting, updateSetting, isUpdating } = useSettings();
  const enabled = getSetting("remote_stt_enabled") ?? true;
  const fallbackLocal = getSetting("remote_stt_fallback_local") ?? false;

  return (
    <SettingsGroup title="Cloud transcription">
      <ToggleSwitch
        checked={enabled}
        onChange={(value) => updateSetting("remote_stt_enabled", value)}
        isUpdating={isUpdating("remote_stt_enabled")}
        label="Use cloud transcription"
        description="Send dictation to your OpenWhisperFlow server."
        grouped={true}
      />
      {enabled && (
        <>
          <LanguageSelector descriptionMode="tooltip" grouped={true} />
          <PersonalVocabulary />
          <LearningFromEdits />
          <ScreenContextSettings />
          <FilterProfanitySettings />
          <ToggleSwitch
            checked={fallbackLocal}
            onChange={(value) =>
              updateSetting("remote_stt_fallback_local", value)
            }
            isUpdating={isUpdating("remote_stt_fallback_local")}
            label="Use local model if offline"
            description="If the server cannot be reached, retry with the installed desktop model."
            grouped={true}
          />
        </>
      )}
    </SettingsGroup>
  );
};
