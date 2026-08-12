package com.example.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SoundManager(private val context: Context) {

    fun playAlertSound() {
        CoroutineScope(Dispatchers.IO).launch {
            var playedSuccessfully = false
            try {
                val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val ringtone = RingtoneManager.getRingtone(context, notificationUri)
                if (ringtone != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        ringtone.audioAttributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    }
                    ringtone.play()
                    playedSuccessfully = true
                }
            } catch (e: Exception) {
                Log.e("SoundManager", "Failed to play default ringtone", e)
            }

            // Fallback / chime beep sequence to guarantee sound on all devices
            if (!playedSuccessfully) {
                playChimeBeepSequence()
            } else {
                delay(200)
                playChimeBeepSequence()
            }
        }
    }

    private fun playChimeBeepSequence() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
            Thread.sleep(280)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
            Thread.sleep(380)
            toneGen.release()
        } catch (e: Exception) {
            try {
                val fallbackTone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                fallbackTone.startTone(ToneGenerator.TONE_PROP_BEEP, 400)
                Thread.sleep(450)
                fallbackTone.release()
            } catch (ex: Exception) {
                Log.e("SoundManager", "ToneGenerator error", ex)
            }
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
