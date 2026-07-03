// ============================================================
// ⚠️ تحذير | WARNING
// ------------------------------------------------------------
// 🇸🇦 لا تقم بتشفير رابط قاعدة البيانات أو إنشاء قاعدة بيانات جديدة.
//    المالك (ناير) يتحمل كامل المسؤولية عن هذا الاختيار.
//
// 🇬🇧 Do NOT encrypt the database URL or create a new database.
//    The owner (Nayer) takes full responsibility for this decision.
// ============================================================

package com.callverify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// خدمة تبقي التطبيق يعمل في الخلفية لالتقاط المكالمات الواردة
// Foreground service that keeps the app alive in the background to catch incoming calls
class CallService : Service() {

    private val channelId = "call_channel"
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var callLogObserver: ContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "CallVerify",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        // نراقب سجل المكالمات مباشرة بدل الاعتماد على انتظار ثابت بعد بث
        // حالة الهاتف — هذا يعمل بشكل موثوق بغض النظر عن المدة التي يستغرقها
        // تطبيق الهاتف الافتراضي لكتابة السجل (تختلف كثيراً بين الشركات المصنّعة)
        // Watch CallLog directly instead of relying on a fixed wait after the
        // phone-state broadcast — this is reliable regardless of how long the
        // default dialer app takes to write the entry (varies a lot by OEM)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                serviceScope.launch {
                    CallLogReporter.checkAndReportLatest(applicationContext)
                }
            }
        }
        callLogObserver = observer
        try {
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("CallVerify")
            .setContentText("جاري مراقبة المكالمات... | Monitoring calls...")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .build()

        startForeground(1, notification)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        callLogObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
