import React, { useEffect, useState } from "react";
import { useSettings } from "../../../hooks/useSettings";
import { SettingsGroup } from "../../ui/SettingsGroup";
import { SettingContainer } from "../../ui/SettingContainer";
import { ToggleSwitch } from "../../ui/ToggleSwitch";
import { LanguageSelector } from "../LanguageSelector";

const fieldClass =
  "w-full px-2 py-1 text-sm bg-mid-gray/10 border border-mid-gray/80 rounded focus:outline-none focus:ring-1 focus:ring-logo-primary focus:border-logo-primary";

export const SharedServerSettings: React.FC = () => {
  const { getSetting, updateSetting, isUpdating } = useSettings();
  const enabled = getSetting("remote_stt_enabled") ?? false;
  const fallbackLocal = getSetting("remote_stt_fallback_local") ?? true;
  const storedUrl = getSetting("remote_stt_url") ?? "";
  const storedApiKey = getSetting("remote_stt_api_key") ?? "";
  const storedModel =
    getSetting("remote_stt_model") ?? "openai/whisper-large-v3-turbo";
  const [url, setUrl] = useState(storedUrl);
  const [apiKey, setApiKey] = useState(storedApiKey);
  const [model, setModel] = useState(storedModel);

  useEffect(() => setUrl(storedUrl), [storedUrl]);
  useEffect(() => setApiKey(storedApiKey), [storedApiKey]);
  useEffect(() => setModel(storedModel), [storedModel]);

  return (
    <SettingsGroup title="Shared transcription server">
      <ToggleSwitch
        checked={enabled}
        onChange={(value) => updateSetting("remote_stt_enabled", value)}
        isUpdating={isUpdating("remote_stt_enabled")}
        label="Use shared server"
        description="Send dictation to your OpenWhisperFlow server on Coolify."
        grouped={true}
      />
      {enabled && (
        <>
          <SettingContainer
            title="Server URL"
            description="HTTPS base URL or the full OpenAI-compatible transcription endpoint."
            grouped={true}
            layout="stacked"
          >
            <input
              aria-label="Shared server URL"
              className={fieldClass}
              type="url"
              placeholder="https://whisper.example.com"
              value={url}
              onChange={(event) => setUrl(event.target.value)}
              onBlur={() =>
                storedUrl !== url && updateSetting("remote_stt_url", url)
              }
            />
          </SettingContainer>
          <SettingContainer
            title="API key"
            description="Bearer token configured as CLIENT_API_KEY in Coolify."
            grouped={true}
            layout="stacked"
          >
            <input
              aria-label="Shared server API key"
              className={fieldClass}
              type="password"
              autoComplete="off"
              value={apiKey}
              onChange={(event) => setApiKey(event.target.value)}
              onBlur={() =>
                storedApiKey !== apiKey &&
                updateSetting("remote_stt_api_key", apiKey)
              }
            />
          </SettingContainer>
          <SettingContainer
            title="API model"
            description="The gateway uses Large V3 Turbo and automatically falls back to Whisper 1."
            grouped={true}
            layout="stacked"
          >
            <input
              aria-label="Shared server model"
              className={fieldClass}
              type="text"
              placeholder="openai/whisper-large-v3-turbo"
              value={model}
              onChange={(event) => setModel(event.target.value)}
              onBlur={() =>
                storedModel !== model &&
                updateSetting("remote_stt_model", model)
              }
            />
          </SettingContainer>
          <LanguageSelector descriptionMode="tooltip" grouped={true} />
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
