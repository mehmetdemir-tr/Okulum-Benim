package com.example.okulumbenim

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

class BootReceiver : BroadcastReceiver() {

    private val TAG = "OKULUMBENIM_BOOT"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        Log.w(TAG, "Reboot algilandi ($action), politikalar geri yukleniyor.")
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!PolicyHelper.isManaged(dpm, context.packageName)) {
                Log.w(TAG, "Yonetim yok, boot islemi atlandi.")
                return
            }

            startSvc(context, SecurityService::class.java)
            startSvc(context, ServerService::class.java)

            if (isSchoolHours()) {
                val admin = AdminReceiver.getComponentName(context)
                try {
                    dpm.setCameraDisabled(admin, true)
                    dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_USB_FILE_TRANSFER)
                    if (PolicyHelper.isOwner(dpm, context.packageName)) {
                        dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_FACTORY_RESET)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Boot lockdown hatasi: ${e.message}")
                }
                val kiosk = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("KIOSK_ACTION", "START_KIOSK")
                    putExtra("IS_SCHOOL_HOURS", true)
                }
                try {
                    context.startActivity(kiosk)
                } catch (e: Exception) {
                    Log.e(TAG, "Kiosk intent basarisiz: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "BootReceiver hatasi: ${e.message}")
        }
    }

    private fun startSvc(context: Context, cls: Class<*>) {
        try {
            val i = Intent(context, cls)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Servis baslatilamadi ${cls.simpleName}: ${e.message}")
            try {
                context.startService(Intent(context, cls))
            } catch (ignored: Exception) {
            }
        }
    }

    private fun isSchoolHours(): Boolean {
        val cal = Calendar.getInstance()
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val isWeekday = dow in Calendar.MONDAY..Calendar.FRIDAY
        return isWeekday && nowMin in (8 * 60 + 20)..(15 * 60 + 35)
    }
}
