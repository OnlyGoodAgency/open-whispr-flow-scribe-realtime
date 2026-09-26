package dev.pivisolutions.dictus.accessibility

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import dev.pivisolutions.dictus.R
import dev.pivisolutions.dictus.service.DictationService
import timber.log.Timber

/** Gives an explicit floating-mic tap a visible Activity context before microphone FGS startup. */
class FloatingMicStartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        try {
            startForegroundService(
                Intent(this, DictationService::class.java).apply {
                    action = DictationService.ACTION_START
                },
            )
        } catch (failure: RuntimeException) {
            Timber.e(failure, "Floating microphone could not start recording")
            Toast.makeText(this, R.string.recording_start_error, Toast.LENGTH_LONG).show()
        } finally {
            finish()
        }
    }
}
