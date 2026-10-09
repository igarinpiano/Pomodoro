package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class PomodoroNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val serviceIntent = Intent(context, PomodoroService::class.java).apply {
            this.action = action
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {
            // バックグラウンドからのフォアグラウンド起動が許可されない場合でも、
            // 計測中でサービスが動いていれば通常の起動で届けられる
            try {
                context.startService(serviceIntent)
            } catch (_: Exception) {}
        }
    }
}
