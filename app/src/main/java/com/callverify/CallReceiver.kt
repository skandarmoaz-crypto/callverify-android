// ============================================================
// ⚠️ تحذير | WARNING
// ------------------------------------------------------------
// 🇸🇦 لا تقم بتشفير رابط قاعدة البيانات أو إنشاء قاعدة بيانات جديدة.
//    المالك (ناير) يتحمل كامل المسؤولية عن هذا الاختيار.
// 🇬🇧 Do NOT encrypt the database URL or create a new database.
//    The owner (Nayer) takes full responsibility for this decision.
// ============================================================

package com.callverify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*

// الـ action الخاص ببث "تم رصد مكالمة" — يُستمع إليه في MainActivity لتجديد WebView
// Broadcast action sent after a call is detected — MainActivity listens to refresh WebView
const val ACTION_CALL_DETECTED = "com.callverify.CALL_DETECTED"

class CallReceiver : BroadcastReceiver() {

    companion object {
        @Volatile private var wasRinging = false

        private const val CH_CALLS  = "callverify_calls"
        private const val NOTIF_ID  = 42

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    CH_CALLS,
                    "مكالمات CallVerify",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "إشعارات المكالمات الواردة المرصودة"
                    enableLights(true)
                    enableVibration(true)
                }
                context.getSystemService(NotificationManager::class.java)
                    ?.createNotificationChannel(ch)
            }
        }

        fun showRingingNotification(context: Context) {
            ensureChannel(context)
            val openIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(context, CH_CALLS)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle("📞 مكالمة واردة")
                .setContentText("جاري رصد المكالمة وإرسالها للتحقق...")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentIntent(openIntent)
                .setAutoCancel(false)
                .build()

            try {
                NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
            } catch (_: SecurityException) { /* POST_NOTIFICATIONS not granted */ }
        }

        fun showDetectedNotification(context: Context, number: String?) {
            ensureChannel(context)
            val openIntent = PendingIntent.getActivity(
                context, 1,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val text = if (!number.isNullOrBlank())
                "✅ تم رصد المكالمة من: $number"
            else
                "✅ تم رصد المكالمة وإرسالها للتحقق"

            val notif = NotificationCompat.Builder(context, CH_CALLS)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle("CallVerify")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .build()

            try {
                NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
            } catch (_: SecurityException) { /* POST_NOTIFICATIONS not granted */ }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val appContext = context.applicationContext

        // نضمن تشغيل خدمة المراقبة | Always ensure monitoring service is running
        try {
            val svc = Intent(appContext, CallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                appContext.startForegroundService(svc)
            else
                appContext.startService(svc)
        } catch (e: Exception) { e.printStackTrace() }

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                wasRinging = true
                // ✅ إشعار فوري وقت الرنين حتى يرى المستخدم أن التطبيق يعمل
                // ✅ Immediate notification on ringing so user sees the app is active
                showRingingNotification(appContext)

                // فحص مبكر لسجل المكالمات (بعض OEMs تكتب مبكراً)
                // Early CallLog check (some OEMs write during ringing)
                val pendingResult = goAsync()
                val wl = acquireWakeLock(appContext, 10_000L)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        delay(1_200L)
                        CallLogReporter.checkAndReportLatest(appContext)
                    } finally {
                        releaseWakeLock(wl)
                        pendingResult.finish()
                    }
                }
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                wasRinging = false
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (wasRinging) {
                    wasRinging = false

                    val wl = acquireWakeLock(appContext, 25_000L)
                    val pendingResult = goAsync()

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            var reported = false
                            val delays = longArrayOf(0L, 1_500L, 3_500L, 6_000L, 10_000L)
                            for (waitMs in delays) {
                                if (waitMs > 0L) delay(waitMs)
                                val before = CallLogReporter.lastReportedId(appContext)
                                CallLogReporter.checkAndReportLatest(appContext)
                                val after = CallLogReporter.lastReportedId(appContext)
                                if (!reported && after != before) {
                                    reported = true
                                    val number = CallLogReporter.lastReportedNumber(appContext)
                                    // إشعار "تم الرصد" | "Detected" notification
                                    showDetectedNotification(appContext, number)
                                    // بث لـ MainActivity لتجديد الـ WebView فوراً
                                    // Broadcast to MainActivity to refresh WebView immediately
                                    appContext.sendBroadcast(
                                        Intent(ACTION_CALL_DETECTED).apply {
                                            setPackage(appContext.packageName)
                                        }
                                    )
                                }
                            }
                        } finally {
                            releaseWakeLock(wl)
                            pendingResult.finish()
                        }
                    }
                }
            }
        }
    }

    private fun acquireWakeLock(context: Context, ms: Long): PowerManager.WakeLock? = try {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CallVerify:receiverLock")
            .apply { acquire(ms) }
    } catch (e: Exception) { e.printStackTrace(); null }

    private fun releaseWakeLock(wl: PowerManager.WakeLock?) {
        try { wl?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
    }
}
