package com.example.util

import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

class SoundManager(private val context: Context) {

    fun playAlertSound() {
        try {
            // Attempt to play default notification ringtone
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val ringtone = RingtoneManager.getRingtone(context, notificationUri)
            if (ringtone != null) {
                ringtone.play()
            } else {
                playFallbackTone()
            }
        } catch (e: Exception) {
            Log.e("SoundManager", "Failed to play ringtone, fallback to ToneGenerator", e)
            playFallbackTone()
        }
    }

    private fun playFallbackTone() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 500)
        } catch (e: Exception) {
            Log.e("SoundManager", "ToneGenerator failed", e)
        }
    }

    fun vibrate() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pattern = longArrayOf(0, 300, 200, 300, 200, 500)
                val effect = VibrationEffect.createWaveform(pattern, -1)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 300, 200, 300), -1)
            }
        } catch (e: Exception) {
            Log.e("SoundManager", "Failed to vibrate", e)
        }
    }
}
