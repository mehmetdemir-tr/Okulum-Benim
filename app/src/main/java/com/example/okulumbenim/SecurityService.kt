package com.example.okulumbenim

import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.util.Calendar

class SecurityService : Service() {

    private val TAG = "OKULUMBENIM_SECURITY"
    private val YOUTUBE_PACKAGE = "com.google.android.youtube"
    
    @Volatile
    private var isRunning = true
    private var schedulerThread: Thread? = null
    private var lastKioskState: Boolean? = null
    private var integrityFailCount = 0
    private var lastKnownTime = 0L

    companion object {
        private var isNativeLoaded = false
        init {
            try {
                System.loadLibrary("okulumbenim_core")
                isNativeLoaded = true
                Log.i("OKULUMBENIM_SECURITY", "Rust Native Library Loaded Successfully!")
            } catch (e: Throwable) {
                Log.e("OKULUMBENIM_SECURITY", "Native Library Load Failed: ${e.message}")
            }
        }
    }

    private external fun verifySystemIntegrity(): Boolean

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SecurityService Başlatıldı")

        if (schedulerThread == null || !schedulerThread!!.isAlive) {
            isRunning = true
            schedulerThread = Thread {
                while (isRunning) {
                    try {
                        evaluateSchoolHoursPolicy()
                        if (isNativeLoaded) {
                            verifyNativeIntegrity()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Politika değerlendirme hatası: ${e.message}")
                    }

                    try {
                        // Her 15 saniyede bir saati kontrol et
                        Thread.sleep(15000)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }.apply { start() }
        }

        return START_STICKY
    }

    private fun evaluateSchoolHoursPolicy() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = AdminReceiver.getComponentName(this)

        if (!PolicyHelper.isManaged(dpm, packageName)) {
            Log.w(TAG, "Uygulama yonetilmiyor, politika uygulanamıyor.")
            return
        }

        val prefs = getSharedPreferences("okulumbenim_sec", Context.MODE_PRIVATE)
        if (lastKnownTime == 0L) {
            lastKnownTime = prefs.getLong("last_time", 0L)
        }
        val now = System.currentTimeMillis()

        if (detectTimeTamper(prefs, now)) {
            Log.w(TAG, "SAAT HILESI SUPHESI! Zorla kilit uygulaniyor.")
            applyFullLockdown(dpm, adminComponent)
            triggerKioskMode(true)
            lastKioskState = true
            return
        }

        val calendar = Calendar.getInstance()
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val currentTimeInMinutes = hour * 60 + minute

        // Hafta içi kontrolü (Pazartesi - Cuma)
        val isWeekday = dayOfWeek in Calendar.MONDAY..Calendar.FRIDAY

        // 08:20 -> (8 * 60) + 20 = 500
        // 15:35 -> (15 * 60) + 35 = 935
        val startMinutes = 8 * 60 + 20
        val endMinutes = 15 * 60 + 35

        val isSchoolHours = isWeekday && (currentTimeInMinutes in startMinutes..endMinutes)

        if (isSchoolHours) {
            if (lastKioskState != true) {
                Log.w(TAG, "OKUL SAATİ BAŞLADI (08:20 - 15:35). Kiosk ve Tam Kısıtlama Aktif Ediliyor...")
                applyFullLockdown(dpm, adminComponent)
                triggerKioskMode(true)
                lastKioskState = true
            }
        } else {
            if (lastKioskState != false) {
                Log.i(TAG, "OKUL SAATLERİ DIŞINDAYIZ / HAFTA SONU. Kiosk ve Kısıtlamalar Kaldırılıyor...")
                removeLockdown(dpm, adminComponent)
                triggerKioskMode(false)
                lastKioskState = false
            }
        }
    }

    private fun detectTimeTamper(prefs: android.content.SharedPreferences, now: Long): Boolean {
        if (now < 1704067200000L) {
            Log.w(TAG, "Supheli sistem zamani: $now")
            return true
        }
        if (lastKnownTime > 0L && now < lastKnownTime - 5 * 60 * 1000L) {
            Log.w(TAG, "Saat geriye alinmis: last=$lastKnownTime now=$now")
            return true
        }
        lastKnownTime = now
        prefs.edit().putLong("last_time", now).apply()
        return false
    }
    private fun triggerKioskMode(enable: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("KIOSK_ACTION", if (enable) "START_KIOSK" else "STOP_KIOSK")
            putExtra("IS_SCHOOL_HOURS", enable)
        }
        startActivity(intent)
    }

    private fun applyFullLockdown(dpm: DevicePolicyManager, adminComponent: android.content.ComponentName) {
        try {
            dpm.setCameraDisabled(adminComponent, true)
            try {
                dpm.setApplicationHidden(adminComponent, YOUTUBE_PACKAGE, true)
            } catch (e: Exception) {
                Log.w(TAG, "YouTube gizlenemedi: ${e.message}")
            }
            dpm.addUserRestriction(adminComponent, android.os.UserManager.DISALLOW_USB_FILE_TRANSFER)
            if (PolicyHelper.isOwner(dpm, packageName)) {
                dpm.addUserRestriction(adminComponent, android.os.UserManager.DISALLOW_FACTORY_RESET)
            }
            Log.i(TAG, "Tam kısıtlama politikaları aktif edildi.")
        } catch (e: Exception) {
            Log.e(TAG, "Kısıtlama uygulanırken hata: ${e.message}")
        }
    }

    private fun removeLockdown(dpm: DevicePolicyManager, adminComponent: android.content.ComponentName) {
        try {
            dpm.setCameraDisabled(adminComponent, false)
            dpm.setApplicationHidden(adminComponent, YOUTUBE_PACKAGE, false)
            dpm.clearUserRestriction(adminComponent, android.os.UserManager.DISALLOW_USB_FILE_TRANSFER)
            dpm.clearUserRestriction(adminComponent, android.os.UserManager.DISALLOW_FACTORY_RESET)
            Log.i(TAG, "Kısıtlamalar başarıyla kaldırıldı.")
        } catch (e: Exception) {
            Log.e(TAG, "Kısıtlama kaldırılırken hata: ${e.message}")
        }
    }

    private fun verifyNativeIntegrity() {
        try {
            val isSecure = verifySystemIntegrity()
            Log.d(TAG, "Rust Integrity Guard Sonucu: $isSecure")
            if (!isSecure) {
                integrityFailCount++
                Log.w(TAG, "Integrity ihlali ($integrityFailCount/3)")
                if (integrityFailCount >= 3) {
                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    if (PolicyHelper.isManaged(dpm, packageName)) {
                        Log.e(TAG, "3x integrity fail, zorla kilit!")
                        applyFullLockdown(dpm, AdminReceiver.getComponentName(this))
                        triggerKioskMode(true)
                    }
                }
            } else {
                integrityFailCount = 0
            }
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native kutuphane yok, atlandi.")
        } catch (e: Throwable) {
            Log.e(TAG, "Native metod çağrılamadı: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        schedulerThread?.interrupt()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
