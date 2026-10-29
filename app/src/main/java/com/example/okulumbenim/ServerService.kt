package com.example.okulumbenim

import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class ServerService : Service() {

    private val TAG = "OKULUMBENIM_SERVER_SVC"
    private val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private val DEFAULT_BASE_URL = "http://192.168.1.106:8000/api/v1"

    private fun baseUrl(): String {
        return try {
            getSharedPreferences("okulumbenim_cfg", Context.MODE_PRIVATE)
                .getString("server_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        } catch (e: Exception) {
            DEFAULT_BASE_URL
        }
    }
    
    @Volatile
    private var isRunning = true
    private var pollingThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Server Communication Service Başlatıldı.")

        if (pollingThread == null || !pollingThread!!.isAlive) {
            isRunning = true
            pollingThread = Thread {
                val deviceId = try {
                    Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "UNKNOWN_DEV"
                } catch (e: Exception) {
                    "FALLBACK_DEV_ID"
                }

                while (isRunning) {
                    try {
                        registerDevice(deviceId)
                        pollServerCommands(deviceId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Döngü içi hata: ${e.message}")
                    }

                    try {
                        Thread.sleep(3000) // Acil durumlar için polling süresi 3 saniyeye düşürüldü
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }.apply { start() }
        }

        return START_STICKY
    }

    private fun registerDevice(deviceId: String) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("${baseUrl()}/register")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 3000
                readTimeout = 3000
                doOutput = true
            }

            val jsonParam = JSONObject().apply {
                put("device_id", deviceId)
                put("model", Build.MODEL)
                put("android_version", Build.VERSION.RELEASE)
            }

            OutputStreamWriter(conn.outputStream).use { it.write(jsonParam.toString()) }
            conn.responseCode
        } catch (e: Exception) {
            // Ignored in quiet polling
        } finally {
            conn?.disconnect()
        }
    }

    private fun pollServerCommands(deviceId: String) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("${baseUrl()}/poll/$deviceId")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
            }

            if (conn.responseCode == 200) {
                val response = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                val json = JSONObject(response)
                val commands = json.getJSONArray("commands")

                for (i in 0 until commands.length()) {
                    val cmd = commands.getString(i)
                    Log.w(TAG, "Sunucudan Öncelikli Komut Alındı: $cmd")
                    handleCommand(cmd)
                }
            }
        } catch (e: Exception) {
            // Quiet fail for lost connection
        } finally {
            conn?.disconnect()
        }
    }

    private fun handleCommand(command: String) {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = AdminReceiver.getComponentName(this)

        if (!PolicyHelper.isManaged(dpm, packageName)) return

        try {
        when (command) {
            "EMERGENCY_LOCK" -> {
                Log.e(TAG, "ACİL DURUM KİLİDİ AKTİF EDİLDİ")
                dpm.setCameraDisabled(adminComponent, true)
                try {
                    dpm.setApplicationHidden(adminComponent, YOUTUBE_PACKAGE, true)
                } catch (e: Exception) {
                    Log.w(TAG, "YouTube gizlenemedi: ${e.message}")
                }
                dpm.addUserRestriction(adminComponent, android.os.UserManager.DISALLOW_USB_FILE_TRANSFER)
                
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("KIOSK_ACTION", "START_KIOSK")
                    putExtra("EMERGENCY_MODE", true)
                }
                startActivity(intent)

                dpm.lockNow()
            }

            "EMERGENCY_UNLOCK" -> {
                Log.i(TAG, "Acil durum kilit modu kaldırıldı.")
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("KIOSK_ACTION", "STOP_KIOSK")
                    putExtra("EMERGENCY_MODE", false)
                }
                startActivity(intent)
            }

            "LOCK" -> dpm.lockNow()
            "RESTRICT_CAMERA" -> dpm.setCameraDisabled(adminComponent, true)
            "ALLOW_CAMERA" -> dpm.setCameraDisabled(adminComponent, false)
            "RESTRICT_YOUTUBE" -> dpm.setApplicationHidden(adminComponent, YOUTUBE_PACKAGE, true)
            "ALLOW_YOUTUBE" -> dpm.setApplicationHidden(adminComponent, YOUTUBE_PACKAGE, false)
        }
        } catch (e: SecurityException) {
            Log.e(TAG, "Komut yetkisi yok ($command): ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Komut hatasi ($command): ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        pollingThread?.interrupt()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
