use crate::settings::AppSettings;
use anyhow::{anyhow, bail, Context, Result};
use reqwest::multipart::{Form, Part};
use serde::Deserialize;
use std::io::Cursor;
use std::time::Duration;

#[derive(Deserialize)]
struct TranscriptionResponse {
    text: String,
}

pub async fn transcribe(settings: &AppSettings, samples: &[f32]) -> Result<String> {
    let endpoint = normalize_endpoint(&settings.remote_stt_url)?;
    let api_key = settings.remote_stt_api_key.expose().trim();
    if api_key.is_empty() {
        bail!("The shared server API key is missing");
    }

    let wav = encode_wav(samples)?;
    let audio = Part::bytes(wav)
        .file_name("dictation.wav")
        .mime_str("audio/wav")?;
    let mut form = Form::new().part("file", audio).text(
        "model",
        if settings.remote_stt_model.trim().is_empty() {
            "openai/whisper-large-v3-turbo".to_string()
        } else {
            settings.remote_stt_model.trim().to_string()
        },
    );

    if settings.selected_language != "auto" && !settings.selected_language.trim().is_empty() {
        form = form.text("language", settings.selected_language.clone());
    }
    if !settings.custom_words.is_empty() {
        form = form.text("prompt", settings.custom_words.join(", "));
    }

    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(15))
        .timeout(Duration::from_secs(135))
        .build()?;
    let response = client
        .post(endpoint)
        .bearer_auth(api_key)
        .multipart(form)
        .send()
        .await
        .context("Could not connect to the shared transcription server")?;
    let status = response.status();
    if !status.is_success() {
        bail!(
            "Shared transcription server returned HTTP {}",
            status.as_u16()
        );
    }
    let result: TranscriptionResponse = response
        .json()
        .await
        .context("Shared transcription server returned invalid JSON")?;
    let text = result.text.trim().to_string();
    if text.is_empty() {
        bail!("Shared transcription server returned an empty transcription");
    }
    Ok(text)
}

fn normalize_endpoint(value: &str) -> Result<reqwest::Url> {
    let trimmed = value.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        bail!("The shared server URL is missing");
    }
    let parsed = reqwest::Url::parse(trimmed).context("The shared server URL is invalid")?;
    let local_development = matches!(parsed.host_str(), Some("localhost" | "127.0.0.1"));
    if parsed.scheme() != "https" && !(local_development && parsed.scheme() == "http") {
        bail!("The shared server URL must use HTTPS");
    }
    let endpoint = if parsed.path().ends_with("/v1/audio/transcriptions") {
        trimmed.to_string()
    } else {
        format!("{trimmed}/v1/audio/transcriptions")
    };
    reqwest::Url::parse(&endpoint).map_err(|error| anyhow!(error))
}

fn encode_wav(samples: &[f32]) -> Result<Vec<u8>> {
    let spec = hound::WavSpec {
        channels: 1,
        sample_rate: 16_000,
        bits_per_sample: 16,
        sample_format: hound::SampleFormat::Int,
    };
    let mut cursor = Cursor::new(Vec::with_capacity(44 + samples.len() * 2));
    {
        let mut writer = hound::WavWriter::new(&mut cursor, spec)?;
        for sample in samples {
            let value = (sample.clamp(-1.0, 1.0) * i16::MAX as f32).round() as i16;
            writer.write_sample(value)?;
        }
        writer.finalize()?;
    }
    Ok(cursor.into_inner())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_https_base_url() {
        assert_eq!(
            normalize_endpoint("https://speech.example.com/")
                .unwrap()
                .as_str(),
            "https://speech.example.com/v1/audio/transcriptions"
        );
    }

    #[test]
    fn rejects_insecure_public_url() {
        assert!(normalize_endpoint("http://speech.example.com").is_err());
    }

    #[test]
    fn creates_valid_wav() {
        let wav = encode_wav(&[-1.0, 0.0, 1.0]).unwrap();
        assert_eq!(&wav[0..4], b"RIFF");
        assert_eq!(&wav[8..12], b"WAVE");
        assert_eq!(&wav[36..40], b"data");
        assert_eq!(wav.len(), 50);
    }
}
