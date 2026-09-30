package dev.pivisolutions.dictus.onboarding

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pivisolutions.dictus.R
import androidx.compose.material3.MaterialTheme
import dev.pivisolutions.dictus.core.theme.LocalDictusColors
import dev.pivisolutions.dictus.core.ui.WaveformBars
import dev.pivisolutions.dictus.core.ui.WaveformDriver
import dev.pivisolutions.dictus.core.ui.rememberSyntheticMotionEnabled
import dev.pivisolutions.dictus.ui.onboarding.OnboardingStepScaffold

/**
 * Onboarding Step 1 — Welcome screen.
 *
 * Displays the OpenWhisperFlow wordmark with an animated 30-bar sine-wave waveform above it.
 *
 * WHY processing mode (not manual sine-wave): Uses WaveformDriver's processingEnergy()
 * which is the exact same formula as iOS BrandWaveformDriver. This ensures visual parity
 * across platforms and eliminates duplicated sine-wave math.
 *
 * WHY WaveformBars (not a custom Canvas): WaveformBars is the shared component from core/ui
 * used throughout the app (TranscribingScreen, recording feedback). Using the same component
 * ensures visual consistency and avoids duplicating bar rendering logic.
 *
 * @param onNext Called when the user taps "Commencer".
 */
@Composable
fun OnboardingWelcomeScreen(
    onNext: () -> Unit,
) {
    // Processing animation driver — produces a traveling sine wave via
    // WaveformDriver.processingEnergy(), matching iOS BrandWaveformDriver.
    val driver = remember {
        WaveformDriver().apply { isProcessing = true }
    }
    val phase by driver.processingPhase.collectAsState()
    val syntheticMotionEnabled by rememberSyntheticMotionEnabled()

    // Keep a static, recognizable waveform when Android asks to reduce motion or save power.
    LaunchedEffect(syntheticMotionEnabled) {
        if (syntheticMotionEnabled) driver.runLoop() else driver.reset()
    }

    OnboardingStepScaffold(
        currentStep = 1,
        ctaText = stringResource(R.string.onboarding_welcome_cta),
        onCtaClick = onNext,
    ) {
        // Animated sine-wave waveform using the shared WaveformBars component from core/ui
        WaveformBars(
            energyLevels = emptyList(),
            modifier = Modifier
                .fillMaxWidth()
                .height(106.dp),
            isProcessing = true,
            processingPhase = phase,
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Keep the full wordmark on one line across narrow and wide phones.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            val wordmarkSize = when {
                maxWidth < 320.dp -> 28.sp
                maxWidth < 360.dp -> 32.sp
                maxWidth < 400.dp -> 36.sp
                else -> 42.sp
            }

            Text(
                text = stringResource(R.string.onboarding_welcome_wordmark),
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = wordmarkSize,
                fontWeight = FontWeight.ExtraLight,
                letterSpacing = (-0.5).sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.onboarding_welcome_tagline),
            modifier = Modifier.fillMaxWidth(),
            color = LocalDictusColors.current.textSecondary,
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
        )
    }
}
