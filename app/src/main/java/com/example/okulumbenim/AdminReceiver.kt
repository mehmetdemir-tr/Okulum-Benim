package com.example.okulumbenim

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

class AdminReceiver : DeviceAdminReceiver() {

    companion object {
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, AdminReceiver::class.java)
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i("OKULUMBENIM_ADMIN", "Device Admin / Owner Aktif")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i("OKULUMBENIM_ADMIN", "QR provisioning tamamlandi.")
        try {
            val extras = intent.getParcelableExtra<android.os.PersistableBundle>(
                "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"
            )
            val serverUrl = extras?.getString("server_url")
            if (!serverUrl.isNullOrBlank()) {
                context.getSharedPreferences("okulumbenim_cfg", Context.MODE_PRIVATE)
                    .edit().putString("server_url", serverUrl).apply()
                Log.i("OKULUMBENIM_ADMIN", "Server URL kaydedildi: $serverUrl")
            }
            val launch = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("KIOSK_ACTION", "START_KIOSK")
                putExtra("FROM_PROVISIONING", true)
            }
            context.startActivity(launch)
        } catch (e: Exception) {
            Log.e("OKULUMBENIM_ADMIN", "Provisioning sonrasi hata: ${e.message}")
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w("OKULUMBENIM_ADMIN", "Device Admin Devre Dışı!")
    }
}
