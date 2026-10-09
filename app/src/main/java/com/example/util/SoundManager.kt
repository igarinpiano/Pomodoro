package com.example.util

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.example.R

/**
 * サウンド再生とバイブレーションを管理するクラス
 */
class SoundManager(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null

    /**
     * 通知音（鳩時計）を再生する
     * 設計方針:
     * 1. USAGE_MEDIA を使用し、通常のサウンドとして再生する（アラームではない）
     * 2. 再生完了後に適切にリソースを解放する
     */
    fun playAlertSound() {
        val resId = R.raw.alert

        try {
            // 既存の再生があれば停止して解放
            stopMediaPlayer()

            // 通常のサウンド（メディア音量）としての属性設定
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            mediaPlayer = MediaPlayer.create(context, resId, audioAttributes, 0) ?: run {
                Log.e("SoundManager", "Failed to create MediaPlayer for resource ID: $resId")
                return
            }

            mediaPlayer?.apply {
                setOnCompletionListener { mp ->
                    mp.release()
                    if (mediaPlayer == mp) {
                        mediaPlayer = null
                    }
                }
                start()
            }
        } catch (e: Exception) {
            Log.e("SoundManager", "Error playing sound", e)
            stopMediaPlayer()
        }
    }

    /**
     * バイブレーションを実行する
     */
    fun vibrate() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            // minSdk 24 なので、O (26) 以上の判定は必要
            val pattern = longArrayOf(0, 300, 200, 300, 200, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Log.e("SoundManager", "Failed to vibrate", e)
        }
    }

    /**
     * MediaPlayer を安全に停止・解放する
     */
    private fun stopMediaPlayer() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
        } catch (_: Exception) {
        } finally {
            mediaPlayer = null
        }
    }

    /**
     * 外部（サービスなど）から明示的に解放する場合
     */
    fun release() {
        stopMediaPlayer()
    }
}
