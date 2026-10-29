package com.example.okulumbenim

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private val TAG = "OKULUMBENIM_MAIN"
    private lateinit var rootLayout: LinearLayout
    private lateinit var statusText: TextView

    private var tapCount = 0
    private var lastTapTime: Long = 0
    private val TAP_THRESHOLD_MS = 2000L
    private val EMERGENCY_TEST_NUMBER = "9333"
    
    private var pinFailCount = 0
    private var pinLockUntil = 0L
    private var emergencyDialog: AlertDialog? = null
    private var countDownTimer: CountDownTimer? = null
    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleQrContent(result.contents)
        } else {
            Log.i(TAG, "QR tarama iptal.")
        }
    }

    companion object {
        // TODO: bunu serverdan/DPM extras'tan al, test PIN'i
        private const val ADMIN_PIN = "2024"
        private const val MAX_PIN_FAIL = 3
        private const val PIN_LOCK_MS = 30_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!PolicyHelper.isManaged(dpm, packageName)) {
            setupProvisioningUI()
            return
        }
        setupUI()

        try {
            startService(Intent(this, SecurityService::class.java))
            startService(Intent(this, ServerService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Servisler baslatilamadi: ${e.message}")
        }

        handleKioskIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleKioskIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        checkAndRestoreKiosk()
    }

    private fun handleKioskIntent(intent: Intent?) {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = AdminReceiver.getComponentName(this)

        if (!PolicyHelper.isManaged(dpm, packageName)) return

        val action = intent?.getStringExtra("KIOSK_ACTION")
        
        when (action) {
            "START_KIOSK" -> enableKioskMode(dpm, adminComponent)
            "STOP_KIOSK" -> disableKioskMode()
            else -> checkAndRestoreKiosk()
        }
    }

    private fun checkAndRestoreKiosk() {
        val calendar = Calendar.getInstance()
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val currentTimeInMinutes = hour * 60 + minute

        val isWeekday = dayOfWeek in Calendar.MONDAY..Calendar.FRIDAY
        val startMinutes = 8 * 60 + 20
        val endMinutes = 15 * 60 + 35
        val isSchoolHours = isWeekday && (currentTimeInMinutes in startMinutes..endMinutes)

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = AdminReceiver.getComponentName(this)

        if (isSchoolHours && PolicyHelper.isManaged(dpm, packageName)) {
            enableKioskMode(dpm, adminComponent)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            val currentTime = System.currentTimeMillis()
            
            if (currentTime - lastTapTime < TAP_THRESHOLD_MS) {
                tapCount++
            } else {
                tapCount = 1
            }
            lastTapTime = currentTime

            Log.d(TAG, "Dokunus Algilandi: $tapCount / 5")

            if (tapCount == 5) {
                tapCount = 0
                val dpmNow = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (PolicyHelper.isManaged(dpmNow, packageName)) {
                    showEmergencyCountdownDialog()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showEmergencyCountdownDialog() {
        if (emergencyDialog?.isShowing == true) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), 101)
        }

        disableKioskMode()

        val messageView = TextView(this).apply {
            text = "ACIL DURUM TESPIT EDILDI\n\n3 saniye icinde $EMERGENCY_TEST_NUMBER aranacak!"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
        }

        emergencyDialog = AlertDialog.Builder(this)
            .setTitle("Acil Durum Uyarisi")
            .setView(messageView)
            .setCancelable(false)
            .setNegativeButton("Iptal Et") { dialog, _ ->
                countDownTimer?.cancel()
                dialog.dismiss()
                Toast.makeText(this, "Acil durum aramasi iptal edildi.", Toast.LENGTH_SHORT).show()
                checkAndRestoreKiosk()
            }
            .create()

        emergencyDialog?.show()

        countDownTimer = object : CountDownTimer(3000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                messageView.text = "ACIL DURUM TESPIT EDILDI\n\n${millisUntilFinished / 1000} saniye icinde $EMERGENCY_TEST_NUMBER aranacak!"
            }

            override fun onFinish() {
                emergencyDialog?.dismiss()
                executeEmergencyCall()
            }
        }.start()
    }

    private fun executeEmergencyCall() {
        Log.e(TAG, "$EMERGENCY_TEST_NUMBER CAGRISI BASLATILIYOR...")
        try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$EMERGENCY_TEST_NUMBER")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(callIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Arama gerceklestirilemedi: ${e.message}")
            try {
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:$EMERGENCY_TEST_NUMBER")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(dialIntent)
            } catch (ex: Exception) {
                Log.e(TAG, "Dial ekrani acilamadi: ${ex.message}")
            }
        }
    }

    private fun enableKioskMode(dpm: DevicePolicyManager, adminComponent: android.content.ComponentName) {
        try {
            if (PolicyHelper.isOwner(dpm, packageName)) {
                dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
            }
            startLockTask()
            Log.i(TAG, "Lock Task (Kiosk) Modu BASLATILDI.")
        } catch (e: Exception) {
            Log.e(TAG, "Kiosk baslatilamadi: ${e.message}")
        }
    }

    private fun disableKioskMode() {
        try {
            stopLockTask()
            Log.i(TAG, "Lock Task (Kiosk) Modu DURDURULDU.")
        } catch (e: Exception) {
            Log.e(TAG, "Kiosk durdurulamadi: ${e.message}")
        }
    }

    private fun setupUI() {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(50, 100, 50, 50)
            setBackgroundColor(Color.WHITE)
        }

        statusText = TextView(this).apply {
            val dpmNow = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val mode = PolicyHelper.modeName(dpmNow, packageName)
            text = "OKULUMBENIM - Akilli Yonetim [$mode]\n\nOkul saatlerinde Kiosk aktif.\n\nAcil Durumda Ekrana 5 Kez Hizlica Dokunun!"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
        }
        rootLayout.addView(statusText)

        val removeAdminBtn = Button(this).apply {
            text = "Yonetim Yetkisini Kaldir"
            setOnClickListener {
                showAdminPinDialog()
            }
        }
        rootLayout.addView(removeAdminBtn)
        val qrBtn = Button(this).apply {
            text = "Sunucu QR Tara"
            setOnClickListener { launchQrScanner() }
        }
        rootLayout.addView(qrBtn)

        setContentView(rootLayout)
    }

    private fun showAdminPinDialog() {
        val now = System.currentTimeMillis()
        if (now < pinLockUntil) {
            val left = (pinLockUntil - now) / 1000
            Toast.makeText(this, "$left sn sonra tekrar dene.", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "PIN kilitli, kalan: ${left}sn")
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Admin PIN"
            setPadding(40, 40, 40, 40)
        }
        AlertDialog.Builder(this)
            .setTitle("Admin Dogrulama")
            .setMessage("Device Owner kaldirma icin PIN gerekli.")
            .setView(input)
            .setCancelable(true)
            .setPositiveButton("Dogrula") { dialog, _ ->
                if (input.text.toString() == ADMIN_PIN) {
                    pinFailCount = 0
                    dialog.dismiss()
                    removeDeviceOwnership()
                } else {
                    pinFailCount++
                    Log.w(TAG, "Yanlis PIN ($pinFailCount/$MAX_PIN_FAIL)")
                    if (pinFailCount >= MAX_PIN_FAIL) {
                        pinLockUntil = System.currentTimeMillis() + PIN_LOCK_MS
                        pinFailCount = 0
                        Toast.makeText(this, "3 hatali deneme, 30sn kilitlendi.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Hatali PIN.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Vazgec", null)
            .show()
    }

    private fun launchQrScanner() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (PolicyHelper.isManaged(dpm, packageName) &&
                dpm.getCameraDisabled(AdminReceiver.getComponentName(this))) {
                Toast.makeText(this, "Okul saatinde kamera kapali, QR okul disinda okutulur.", Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Kamera durumu okunamadi: ${e.message}")
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 102)
            Toast.makeText(this, "Kamera izni verip tekrar dene.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            qrLauncher.launch(ScanOptions().setPrompt("Sunucu QR kodunu okut").setBeepEnabled(true))
        } catch (e: Exception) {
            Log.e(TAG, "Tarayici acilamadi: ${e.message}")
            Toast.makeText(this, "Tarayici acilamadi.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleQrContent(raw: String) {
        try {
            Log.i(TAG, "QR ham (${raw.length} char): ${raw.take(200)}")
            val serverUrl = findServerUrl(raw)
            if (serverUrl.isNullOrBlank()) {
                Toast.makeText(this, "QR'da server_url yok.", Toast.LENGTH_LONG).show()
                Log.w(TAG, "Gecersiz QR icerik.")
                return
            }
            getSharedPreferences("okulumbenim_cfg", Context.MODE_PRIVATE)
                .edit().putString("server_url", serverUrl).apply()
            Toast.makeText(this, "Sunucu kaydedildi!", Toast.LENGTH_LONG).show()
            Log.i(TAG, "QR ile server_url kaydedildi: $serverUrl")
        } catch (e: Exception) {
            Log.e(TAG, "QR islenemedi: ${e.message}")
            Toast.makeText(this, "QR okunamadi.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findServerUrl(raw: String): String? {
        val trimmed = raw.trim().trim('\uFEFF')
        if (trimmed.startsWith("http", ignoreCase = true)) return trimmed
        if (trimmed.startsWith("{")) {
            try {
                val json = org.json.JSONObject(trimmed)
                deepFindUrl(json)?.let { return it }
            } catch (e: Exception) {
                Log.w(TAG, "QR JSON parse hatasi: ${e.message}")
            }
        }
        Regex("https?://[^\\s\"'<>]+").find(trimmed)?.let { return it.value }
        return null
    }

    private fun deepFindUrl(obj: Any?): String? {
        when (obj) {
            is org.json.JSONObject -> {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.opt(k)
                    if (v is String) {
                        if (k.contains("server", ignoreCase = true) && v.trim().startsWith("http", ignoreCase = true)) {
                            return v.trim()
                        }
                    }
                    deepFindUrl(v)?.let { return it }
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until obj.length()) {
                    deepFindUrl(obj.opt(i))?.let { return it }
                }
            }
            is String -> {
                if (obj.trim().startsWith("http", ignoreCase = true)) return obj.trim()
            }
        }
        return null
    }

    private fun setupProvisioningUI() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(50, 100, 50, 50)
            setBackgroundColor(Color.WHITE)
        }
        val info = TextView(this).apply {
            text = "OKULUMBENIM Kurulum\n\nBu cihaz henuz yonetilmiyor.\n\n1. Cihazi fabrika ayarlarina dondur\n2. Karsilama ekranina 6 kez dokun\n3. Okul QR kodunu okut\n4. Kurulum bitince bu ekran kapanir\n\nQR icin ogretmene danis."
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
        }
        layout.addView(info)
        val retryBtn = Button(this).apply {
            text = "Tekrar Kontrol Et"
            setOnClickListener { recreate() }
        }
        layout.addView(retryBtn)
        val qrBtn = Button(this).apply {
            text = "QR ile Sunucu Kur"
            setOnClickListener { launchQrScanner() }
        }
        layout.addView(qrBtn)
        setContentView(layout)
        Log.w(TAG, "Owner yok, kurulum ekrani gosterildi.")
    }

    private fun removeDeviceOwnership() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!PolicyHelper.isManaged(dpm, packageName)) {
            Toast.makeText(this, "Zaten yonetilmiyor.", Toast.LENGTH_SHORT).show()
            return
        }
        disableKioskMode()
        try {
            if (PolicyHelper.isOwner(dpm, packageName)) {
                @Suppress("DEPRECATION")
                dpm.clearDeviceOwnerApp(packageName)
            } else {
                dpm.removeActiveAdmin(AdminReceiver.getComponentName(this))
            }
            Toast.makeText(this, "Yonetim Yetkisi Kaldirildi!", Toast.LENGTH_LONG).show()
            Log.w(TAG, "Device Owner PIN ile kaldirildi.")
        } catch (e: Exception) {
            Log.e(TAG, "Yetki kaldirilamadi: ${e.message}")
        }
    }
}
