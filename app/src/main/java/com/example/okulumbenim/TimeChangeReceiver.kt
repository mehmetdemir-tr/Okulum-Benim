package com.example.okulumbenim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class TimeChangeReceiver : BroadcastReceiver() {

    private val TAG = "OKULUMBENIM_TIME"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED
        ) return

        Log.w(TAG, "Saat degisikligi algilandi ($action), politika yeniden degerlendiriliyor.")
        try {
            val i = Intent(context, SecurityService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        } catch (e: Exception) {
            Log.e(TAG, "SecurityService tetiklenemedi: ${e.message}")
        }
    }
}
